package gr.accio.services

import gr.accio.models.VideoFile
import gr.accio.models.Subtitle
import gr.accio.models.Task
import gr.accio.models.TaskType
import gr.accio.models.TaskStatus
import gr.accio.models.TaskPriority
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.Serializable
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID
import java.time.LocalDateTime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@ApplicationScoped
class SyncService {

    @ConfigProperty(name = "tools.ffsubsync", defaultValue = "ffsubsync")
    lateinit var ffsubsyncPath: String

    @ConfigProperty(name = "tools.ffmpeg", defaultValue = "ffmpeg")
    lateinit var ffmpegPath: String

    fun sync(videoId: UUID): Uni<Map<String, Any>> {
        return VideoFile.findById(videoId)
            .flatMap { video ->
                if (video == null) {
                    Uni.createFrom().item(mapOf("error" to "Video not found"))
                } else {
                    // Create a sync task
                    val task = Task().apply {
                        id = UUID.randomUUID()
                        type = TaskType.SYNC_SUBTITLES
                        status = TaskStatus.PENDING
                        priority = TaskPriority.MEDIUM
                        this.video = video
                        parameters = """{"videoId": "$videoId"}"""
                        createdAt = java.time.Instant.now()
                        updatedAt = java.time.Instant.now()
                    }
                    
                    task.persist<Task>()
                        .flatMap { 
                            // Start sync process
                            performSync(video, task)
                        }
                }
            }
    }

    private fun performSync(video: VideoFile, task: Task): Uni<Map<String, Any>> {
        return Uni.createFrom().item {
            try {
                // Update task status to running
                task.status = TaskStatus.RUNNING
                task.startedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                task.progressPercentage = 10
                
                println("Starting sync for video: ${video.title ?: video.path}")
                
                // Find subtitle files for this video
                val subtitleFiles = findSubtitleFiles(video)
                if (subtitleFiles.isEmpty()) {
                    throw IllegalStateException("No subtitle files found for video: ${video.title ?: video.path}")
                }
                
                task.progressPercentage = 20
                task.updatedAt = java.time.Instant.now()
                
                val syncResults = mutableListOf<Map<String, Any>>()
                val totalSubtitles = subtitleFiles.size
                
                // Process each subtitle file
                for ((index, subtitleFile) in subtitleFiles.withIndex()) {
                    try {
                        println("Syncing subtitle file: ${subtitleFile.absolutePath}")
                        
                        // Create output path for synced subtitle
                        val outputPath = generateSyncedSubtitlePath(subtitleFile, video)
                        
                        // Perform actual sync using ffsubsync
                        val syncResult = syncSubtitleWithFFSubSync(video.path, subtitleFile.absolutePath, outputPath, task)
                        
                        syncResults.add(mapOf(
                            "originalPath" to subtitleFile.absolutePath,
                            "syncedPath" to outputPath,
                            "success" to syncResult.success,
                            "message" to syncResult.message
                        ))
                        
                        // Update progress
                        val progressPercentage = 20 + ((index + 1).toDouble() / totalSubtitles * 70).toInt()
                        task.progressPercentage = progressPercentage
                        task.updatedAt = java.time.Instant.now()
                        
                    } catch (e: Exception) {
                        println("Failed to sync subtitle ${subtitleFile.absolutePath}: ${e.message}")
                        syncResults.add(mapOf(
                            "originalPath" to subtitleFile.absolutePath,
                            "syncedPath" to "",
                            "success" to false,
                            "message" to (e.message ?: "Sync failed")
                        ))
                    }
                }
                
                // Complete the sync
                task.status = TaskStatus.COMPLETED
                task.progressPercentage = 100
                task.completedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                
                val successCount = syncResults.count { it["success"] as Boolean }
                val resultMessage = "Sync completed: $successCount/$totalSubtitles subtitles synced successfully"
                task.result = """{"status": "success", "message": "$resultMessage", "results": $syncResults}"""
                
                // Update video sync status
                video.synced = successCount > 0
                video.updatedAt = java.time.Instant.now()
                
                println("Sync completed for video: ${video.title ?: video.path}")
                
                mapOf(
                    "status" to "success",
                    "message" to resultMessage,
                    "taskId" to task.id.toString(),
                    "videoId" to video.id.toString(),
                    "syncResults" to syncResults
                )
            } catch (e: Exception) {
                // Handle sync failure
                task.status = TaskStatus.FAILED
                task.errorMessage = e.message
                task.completedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                
                println("Sync failed for video: ${video.title ?: video.path}, error: ${e.message}")
                
                mapOf(
                    "status" to "error",
                    "message" to "Sync failed: ${e.message}",
                    "taskId" to task.id.toString(),
                    "videoId" to video.id.toString()
                )
            }
        }.flatMap { result ->
            // Persist task and video updates
            task.persist<Task>()
                .flatMap { video.persist<VideoFile>() }
                .map { result }
        }
    }

    fun getSyncStatus(videoId: UUID): Uni<Map<String, Any>> {
        return Task.findByVideoAndType(videoId, TaskType.SYNC_SUBTITLES)
            .map { tasks ->
                val latestTask = tasks.maxByOrNull { it.createdAt ?: java.time.Instant.MIN }
                if (latestTask != null && latestTask.createdAt != null) {
                    mapOf(
                        "taskId" to latestTask.id.toString(),
                        "status" to latestTask.status.toString(),
                        "progress" to latestTask.progressPercentage,
                        "createdAt" to latestTask.createdAt.toString(),
                        "updatedAt" to latestTask.updatedAt.toString()
                    )
                } else {
                    mapOf("status" to "not_started")
                }
            }
    }

    fun cancelSync(taskId: UUID): Uni<Map<String, Any>> {
        return Task.findById(taskId)
            .flatMap { task ->
                if (task == null) {
                    Uni.createFrom().item(mapOf("error" to "Task not found"))
                } else if (task.status == TaskStatus.RUNNING) {
                    task.status = TaskStatus.CANCELLED
                    task.completedAt = Instant.now()
                    task.updatedAt = Instant.now()
                    task.persist<Task>()
                        .map { 
                            mapOf(
                                "status" to "success",
                                "message" to "Sync task cancelled",
                                "taskId" to task.id.toString()
                            )
                        }
                } else {
                    Uni.createFrom().item(
                        mapOf(
                            "error" to "Task cannot be cancelled",
                            "currentStatus" to task.status.toString()
                        )
                    )
                }
            }
    }

    private fun findSubtitleFiles(video: VideoFile): List<File> {
        val videoFile = File(video.path)
        val videoDir = videoFile.parentFile ?: return emptyList()
        val videoNameWithoutExt = videoFile.nameWithoutExtension
        
        val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa", "sub")
        val subtitleFiles = mutableListOf<File>()
        
        // Look for subtitle files with the same name as the video
        for (extension in subtitleExtensions) {
            val subtitleFile = File(videoDir, "$videoNameWithoutExt.$extension")
            if (subtitleFile.exists()) {
                subtitleFiles.add(subtitleFile)
            }
        }
        
        // Also look for subtitle files in the same directory that might match
        videoDir.listFiles()?.forEach { file ->
            if (file.isFile && subtitleExtensions.contains(file.extension.lowercase()) && 
                file.nameWithoutExtension.contains(videoNameWithoutExt, ignoreCase = true)) {
                if (!subtitleFiles.contains(file)) {
                    subtitleFiles.add(file)
                }
            }
        }
        
        return subtitleFiles
    }
    
    private fun generateSyncedSubtitlePath(subtitleFile: File, video: VideoFile): String {
        val nameWithoutExtension = subtitleFile.nameWithoutExtension
        val extension = subtitleFile.extension
        val parentDir = subtitleFile.parent
        
        return "$parentDir${File.separator}${nameWithoutExtension}_synced.$extension"
    }

    @Serializable
    private data class SyncResult(val success: Boolean, val message: String)
    
    private fun syncSubtitleWithFFSubSync(videoPath: String, subtitlePath: String, outputPath: String, task: Task): SyncResult {
        return try {
            // Build ffsubsync command
            val command = listOf(
                ffsubsyncPath,
                videoPath,
                "-i", subtitlePath,
                "-o", outputPath,
                "--max-offset-seconds", "60",
                "--no-fix-framerate"
            )
            
            println("Executing ffsubsync command: ${command.joinToString(" ")}")
            
            // Execute the command
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            val output = process.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
            
            val completed = process.waitFor(300, TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                SyncResult(false, "FFSubSync process timed out after 5 minutes")
            } else if (process.exitValue() == 0) {
                // Check if output file was created
                val outputFile = File(outputPath)
                if (outputFile.exists() && outputFile.length() > 0) {
                    SyncResult(true, "Subtitle synchronized successfully")
                } else {
                    SyncResult(false, "FFSubSync completed but no output file was generated")
                }
            } else {
                SyncResult(false, "FFSubSync failed with exit code ${process.exitValue()}: $output")
            }
            
        } catch (e: Exception) {
            SyncResult(false, "Failed to execute FFSubSync: ${e.message}")
        }
    }
}