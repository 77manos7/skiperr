package gr.accio.scheduler

import gr.accio.models.Task
import gr.accio.models.TaskStatus
import gr.accio.models.TaskType
import gr.accio.models.VideoFile
import gr.accio.services.ScanService
import gr.accio.services.SyncService
import gr.accio.services.TranslateService
import gr.accio.services.SubtitleExtractionService
import gr.accio.services.SubtitleGenerationService
import gr.accio.services.TaskUpdateBroadcaster
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.quartz.*
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class TaskScheduler {

    @Inject
    lateinit var scheduler: Scheduler

    @Inject
    lateinit var scanService: ScanService

    @Inject
    lateinit var syncService: SyncService

    @Inject
    lateinit var translateService: TranslateService

    @ConfigProperty(name = "tools.ffmpeg", defaultValue = "ffmpeg")
    lateinit var ffmpegPath: String

    @ConfigProperty(name = "tools.ffprobe", defaultValue = "ffprobe")
    lateinit var ffprobePath: String

    @ConfigProperty(name = "tools.whisper", defaultValue = "whisper")
    lateinit var whisperPath: String

    @Inject
    lateinit var taskUpdateBroadcaster: TaskUpdateBroadcaster

    @Inject
    lateinit var subtitleExtractionService: SubtitleExtractionService

    @Inject
    lateinit var subtitleGenerationService: SubtitleGenerationService

    private val runningTasks = ConcurrentHashMap<UUID, JobExecutionContext>()

    /**
     * Schedule a new task for execution
     */
    fun scheduleTask(task: Task): Uni<Boolean> {
        return try {
            val jobDetail = JobBuilder.newJob(TaskExecutionJob::class.java)
                .withIdentity(task.id.toString(), "tasks")
                .usingJobData("taskId", task.id.toString())
                .usingJobData("taskType", task.type.name)
                .build()

            val trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-${task.id}", "tasks")
                .startNow()
                .build()

            scheduler.scheduleJob(jobDetail, trigger)
            Log.info("Scheduled task ${task.id} of type ${task.type}")
            Uni.createFrom().item(true)
        } catch (e: Exception) {
            Log.error("Failed to schedule task ${task.id}", e)
            Uni.createFrom().item(false)
        }
    }

    /**
     * Cancel a running task
     */
    fun cancelTask(taskId: UUID): Uni<Boolean> {
        return try {
            val jobKey = JobKey.jobKey(taskId.toString(), "tasks")
            val interrupted = scheduler.interrupt(jobKey)
            runningTasks.remove(taskId)
            Log.info("Cancelled task $taskId, interrupted: $interrupted")
            Uni.createFrom().item(interrupted)
        } catch (e: Exception) {
            Log.error("Failed to cancel task $taskId", e)
            Uni.createFrom().item(false)
        }
    }

    /**
     * Get status of all running tasks
     */
    fun getRunningTasks(): Uni<List<UUID>> {
        return Uni.createFrom().item(runningTasks.keys.toList())
    }

    /**
     * Resume stuck tasks on startup
     */
    @Scheduled(every = "30s", delay = 10)
    fun resumeStuckTasks() {
        Log.debug("Checking for stuck tasks...")
        
        Task.findStuckTasks()
            .onItem().transformToUni { tasks ->
                if (tasks.isNotEmpty()) {
                    Log.info("Found ${tasks.size} stuck tasks, resuming...")
                    tasks.forEach { task ->
                        task.status = TaskStatus.PENDING
                        task.lastHeartbeat = Instant.now()
                        task.persistAndFlush<Task>()
                            .onItem().transformToUni { scheduleTask(it) }
                            .subscribe().with(
                                { success -> 
                                    if (success) Log.info("Resumed stuck task ${task.id}")
                                    else Log.error("Failed to resume stuck task ${task.id}")
                                },
                                { error -> Log.error("Error resuming stuck task ${task.id}", error) }
                            )
                    }
                }
                Uni.createFrom().voidItem()
            }
            .subscribe().with(
                { Log.debug("Stuck task check completed") },
                { error -> Log.error("Error checking stuck tasks", error) }
            )
    }

    /**
     * Clean up completed tasks older than 24 hours
     */
    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    fun cleanupOldTasks() {
        Log.info("Starting cleanup of old completed tasks...")
        
        Task.findCompletedTasksOlderThan(LocalDateTime.now().minusDays(1))
            .onItem().transformToUni { tasks ->
                if (tasks.isNotEmpty()) {
                    Log.info("Cleaning up ${tasks.size} old completed tasks")
                    tasks.forEach { task ->
                        task.delete()
                            .subscribe().with(
                                { Log.debug("Deleted old task ${task.id}") },
                                { error -> Log.error("Failed to delete old task ${task.id}", error) }
                            )
                    }
                }
                Uni.createFrom().voidItem()
            }
            .subscribe().with(
                { Log.info("Old task cleanup completed") },
                { error -> Log.error("Error during old task cleanup", error) }
            )
    }

    /**
     * Execute a task based on its type
     */
    fun executeTask(task: Task): Uni<Void> {
        Log.info("Executing task ${task.id} of type ${task.type}")
        
        return when (task.type) {
            TaskType.SCAN_LIBRARY -> {
                val paths = parseLibraryPaths(task.parameters)
                scanService.scanLibrary(paths)
                    .onItem().transformToUni { results ->
                        updateTaskResult(task, mapOf(
                            "scannedFiles" to results.size,
                            "newFiles" to results.count { it.id == null }
                        ))
                    }
            }
            
            TaskType.SYNC_SUBTITLES -> {
                task.video?.let { videoFile ->
                    syncService.sync(videoFile.id!!)
                        .onItem().transformToUni { result ->
                            updateTaskResult(task, mapOf(
                            "syncResult" to (result as Any),
                            "videoFile" to (videoFile.title as Any)
                        ))
                        }
                } ?: Uni.createFrom().failure(IllegalArgumentException("No video file specified for sync task"))
            }
            
            TaskType.TRANSLATE_SUBTITLES -> {
                task.subtitle?.let { subtitle ->
                    val params = parseTranslationParams(task.parameters)
                    translateService.translate(
                        subtitle.id!!,
                        params["targetLanguage"] ?: "el",
                        params["provider"] ?: "openai"
                    ).onItem().transformToUni { result ->
                        updateTaskResult(task, mapOf(
                            "translationResult" to (result as Any),
                            "subtitle" to (subtitle.filePath as Any),
                            "targetLanguage" to (params["targetLanguage"] ?: "el" as Any)
                        ))
                    }
                } ?: Uni.createFrom().failure(IllegalArgumentException("No subtitle specified for translation task"))
            }
            
            TaskType.BATCH_PROCESS -> {
                // Handle batch processing of multiple files
                val params = parseBatchParams(task.parameters)
                executeBatchProcess(task, params)
            }
            
            // Add missing task types with default implementations
            TaskType.EXTRACT_SUBTITLES -> {
                task.video?.let { videoFile ->
                    subtitleExtractionService.extractSubtitles(videoFile, task)
                        .onItem().transformToUni { result ->
                            updateTaskResult(task, mapOf(
                                "extractionResult" to (result.success as Any),
                                "message" to (result.message as Any),
                                "extractedCount" to (result.extractedSubtitles.size as Any),
                                "extractedSubtitles" to (result.extractedSubtitles.map { 
                                    mapOf(
                                        "language" to it.language,
                                        "filePath" to it.filePath,
                                        "streamIndex" to it.streamIndex
                                    )
                                } as Any)
                            ))
                        }
                } ?: Uni.createFrom().failure(IllegalArgumentException("No video file specified for subtitle extraction task"))
            }
            
            TaskType.GENERATE_SUBTITLES -> {
                task.video?.let { videoFile ->
                    val params = parseGenerationParams(task.parameters)
                    val language = params["language"] ?: "auto"
                    
                    subtitleGenerationService.generateSubtitles(videoFile, task, language)
                        .onItem().transformToUni { result ->
                            updateTaskResult(task, mapOf(
                                "generationResult" to (result.success as Any),
                                "message" to (result.message as Any),
                                "subtitlePath" to (result.subtitlePath as Any),
                                "entryCount" to (result.entryCount as Any),
                                "subtitle" to (result.subtitle?.let { 
                                    mapOf(
                                        "id" to it.id,
                                        "language" to it.language,
                                        "filePath" to it.filePath,
                                        "type" to it.type.name
                                    )
                                } as Any)
                            ))
                        }
                } ?: Uni.createFrom().failure(IllegalArgumentException("No video file specified for subtitle generation task"))
            }
            
            TaskType.CLEANUP_FILES -> {
                performCleanupFiles(task)
            }
            
            TaskType.BACKUP_DATABASE -> {
                performBackupDatabase(task)
            }
            
            TaskType.OPTIMIZE_DATABASE -> {
                Log.info("Optimize database task not yet implemented")
                updateTaskResult(task, mapOf("status" to "not_implemented"))
            }
            
            TaskType.HEALTH_CHECK -> {
                performHealthCheck(task)
            }
            
            TaskType.USER_EXPORT -> {
                Log.info("User export task not yet implemented")
                updateTaskResult(task, mapOf("status" to "not_implemented"))
            }
        }
    }

    private fun parseLibraryPaths(parameters: String?): List<String> {
        return try {
            if (parameters.isNullOrBlank()) return emptyList()
            val json = Json.parseToJsonElement(parameters).jsonObject
            json["paths"]?.let { pathsElement ->
                pathsElement.jsonObject.values.map { it.jsonPrimitive.content }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.warn("Failed to parse library paths from parameters: $parameters", e)
            emptyList()
        }
    }

    private fun parseTranslationParams(parameters: String?): Map<String, String> {
        return try {
            if (parameters.isNullOrBlank()) return emptyMap()
            val json = Json.parseToJsonElement(parameters).jsonObject
            mapOf(
                "targetLanguage" to (json["targetLanguage"]?.jsonPrimitive?.content ?: "el"),
                "provider" to (json["provider"]?.jsonPrimitive?.content ?: "openai")
            )
        } catch (e: Exception) {
            Log.warn("Failed to parse translation parameters: $parameters", e)
            emptyMap()
        }
    }

    private fun parseBatchParams(parameters: String?): Map<String, Any> {
        return try {
            if (parameters.isNullOrBlank()) return emptyMap()
            val json = Json.parseToJsonElement(parameters).jsonObject
            mapOf(
                "operation" to (json["operation"]?.jsonPrimitive?.content ?: ""),
                "fileIds" to (json["fileIds"]?.jsonObject?.values?.map { it.jsonPrimitive.content } ?: emptyList<String>())
            )
        } catch (e: Exception) {
            Log.warn("Failed to parse batch parameters: $parameters", e)
            emptyMap()
        }
    }

    private fun parseGenerationParams(parameters: String?): Map<String, String> {
        return try {
            if (parameters.isNullOrBlank()) return emptyMap()
            val json = Json.parseToJsonElement(parameters).jsonObject
            mapOf(
                "language" to (json["language"]?.jsonPrimitive?.content ?: "auto"),
                "model" to (json["model"]?.jsonPrimitive?.content ?: "base")
            )
        } catch (e: Exception) {
            Log.warn("Failed to parse generation parameters: $parameters", e)
            emptyMap()
        }
    }

    private fun performHealthCheck(task: Task): Uni<Void> {
        return Uni.createFrom().item {
            try {
                Log.info("Starting system health check")
                
                task.status = TaskStatus.RUNNING
                task.startedAt = Instant.now()
                task.progressPercentage = 10
                task.persistAndFlush<Task>()

                val healthResults = mutableMapOf<String, Any>()
                
                // Check database connectivity (20%)
                task.progressPercentage = 20
                task.persistAndFlush<Task>()
                val dbHealth = checkDatabaseHealth()
                healthResults["database"] = dbHealth
                
                // Check file system access (40%)
                task.progressPercentage = 40
                task.persistAndFlush<Task>()
                val fsHealth = checkFileSystemHealth()
                healthResults["filesystem"] = fsHealth
                
                // Check external tools availability (60%)
                task.progressPercentage = 60
                task.persistAndFlush<Task>()
                val toolsHealth = checkExternalToolsHealth()
                healthResults["tools"] = toolsHealth
                
                // Check system resources (80%)
                task.progressPercentage = 80
                task.persistAndFlush<Task>()
                val resourcesHealth = checkSystemResources()
                healthResults["resources"] = resourcesHealth
                
                // Determine overall health status
                val overallHealthy = listOf(dbHealth, fsHealth, toolsHealth, resourcesHealth)
                     .all { (it as Map<String, Any>)["status"] == "healthy" }
                
                healthResults["overall"] = mapOf(
                    "status" to if (overallHealthy) "healthy" else "unhealthy",
                    "timestamp" to Instant.now().toString()
                )
                
                task.progressPercentage = 100
                task.status = TaskStatus.COMPLETED
                task.completedAt = Instant.now()
                
                updateTaskResult(task, healthResults)
                
                Log.info("Health check completed - Overall status: ${if (overallHealthy) "healthy" else "unhealthy"}")
                
            } catch (e: Exception) {
                Log.error("Health check failed: ${e.message}", e)
                
                task.status = TaskStatus.FAILED
                task.errorMessage = e.message
                task.completedAt = Instant.now()
                task.persistAndFlush<Task>()
                
                updateTaskResult(task, mapOf(
                    "error" to (e.message ?: "Unknown error"),
                    "overall" to mapOf("status" to "error")
                ))
            }
        }.replaceWithVoid()
    }

    private fun checkDatabaseHealth(): Map<String, Any> {
        return try {
            // Try a simple database query
            val count = VideoFile.count()
            mapOf(
                "status" to "healthy",
                "message" to "Database connection successful",
                "videoCount" to count
            )
        } catch (e: Exception) {
            mapOf(
                "status" to "unhealthy",
                "message" to "Database connection failed: ${e.message}"
            )
        }
    }

    private fun checkFileSystemHealth(): Map<String, Any> {
        return try {
            val tempFile = java.io.File.createTempFile("health_check", ".tmp")
            tempFile.writeText("test")
            val canRead = tempFile.readText() == "test"
            tempFile.delete()
            
            if (canRead) {
                mapOf(
                    "status" to "healthy",
                    "message" to "File system read/write operations successful"
                )
            } else {
                mapOf(
                    "status" to "unhealthy",
                    "message" to "File system read operation failed"
                )
            }
        } catch (e: Exception) {
            mapOf(
                "status" to "unhealthy",
                "message" to "File system access failed: ${e.message}"
            )
        }
    }

    private fun checkExternalToolsHealth(): Map<String, Any> {
        val toolResults = mutableMapOf<String, Map<String, String>>()
        
        // Check ffmpeg
        toolResults["ffmpeg"] = checkToolAvailability(ffmpegPath, "--version")
        
        // Check ffprobe
        toolResults["ffprobe"] = checkToolAvailability(ffprobePath, "--version")
        
        // Check whisper (optional)
        toolResults["whisper"] = checkToolAvailability(whisperPath, "--help")
        
        val allToolsHealthy = toolResults.values.all { it["status"] == "healthy" }
        
        return mapOf(
            "status" to if (allToolsHealthy) "healthy" else "unhealthy",
            "message" to if (allToolsHealthy) "All external tools available" else "Some external tools unavailable",
            "tools" to toolResults
        )
    }

    private fun checkToolAvailability(toolPath: String, testArg: String): Map<String, String> {
        return try {
            val process = ProcessBuilder(toolPath, testArg).start()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                mapOf(
                    "status" to "healthy",
                    "message" to "Tool available and responsive"
                )
            } else {
                mapOf(
                    "status" to "unhealthy",
                    "message" to "Tool returned non-zero exit code: $exitCode"
                )
            }
        } catch (e: Exception) {
            mapOf(
                "status" to "unhealthy",
                "message" to "Tool not available: ${e.message}"
            )
        }
    }

    private fun checkSystemResources(): Map<String, Any> {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val maxMemory = runtime.maxMemory()
            
            val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
            val isMemoryHealthy = memoryUsagePercent < 90
            
            mapOf(
                "status" to if (isMemoryHealthy) "healthy" else "warning",
                "message" to if (isMemoryHealthy) "System resources within normal limits" else "High memory usage detected",
                "memory" to mapOf(
                    "used" to usedMemory,
                    "total" to totalMemory,
                    "max" to maxMemory,
                    "usagePercent" to memoryUsagePercent
                )
            )
        } catch (e: Exception) {
            mapOf(
                "status" to "unhealthy",
                "message" to "Failed to check system resources: ${e.message}"
            )
        }
    }

    private fun executeBatchProcess(task: Task, params: Map<String, Any>): Uni<Void> {
        val operation = params["operation"] as? String ?: ""
        val fileIds = params["fileIds"] as? List<String> ?: emptyList()
        
        Log.info("Executing batch operation '$operation' on ${fileIds.size} files")
        
        return when (operation) {
            "sync_all" -> {
                // Batch sync multiple video files
                Uni.createFrom().voidItem() // Placeholder for batch sync implementation
            }
            "translate_all" -> {
                // Batch translate multiple subtitles
                Uni.createFrom().voidItem() // Placeholder for batch translation implementation
            }
            else -> {
                Log.warn("Unknown batch operation: $operation")
                Uni.createFrom().voidItem()
            }
        }
    }

    private fun updateTaskResult(task: Task, result: Map<String, Any>): Uni<Void> {
        return try {
            task.result = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { 
                    kotlinx.serialization.json.JsonPrimitive(it.value.toString()) 
                }))
            task.status = TaskStatus.COMPLETED
            task.completedAt = Instant.now()
            task.progress = 100
            
            task.persistAndFlush<Task>()
                .onItem().invoke { updatedTask ->
                    // Broadcast task update for real-time monitoring
                    taskUpdateBroadcaster.broadcastTaskUpdate(updatedTask)
                }
                .replaceWithVoid()
        } catch (e: Exception) {
            Log.error("Failed to update task result for task ${task.id}", e)
            task.status = TaskStatus.FAILED
            task.errorMessage = e.message
            task.persistAndFlush<Task>()
                .onItem().invoke { updatedTask ->
                    // Broadcast task update for real-time monitoring
                    taskUpdateBroadcaster.broadcastTaskUpdate(updatedTask)
                }
                .replaceWithVoid()
        }
    }

    fun registerRunningTask(taskId: UUID, context: JobExecutionContext) {
        runningTasks[taskId] = context
    }

    fun unregisterRunningTask(taskId: UUID) {
        runningTasks.remove(taskId)
    }

    /**
     * Perform cleanup files task
     */
    private fun performCleanupFiles(task: Task): Uni<Void> {
        return try {
            Log.info("Starting cleanup files task ${task.id}")
            
            val parameters = parseCleanupParams(task.parameters)
            val olderThanDays = parameters["olderThanDays"] as? Int ?: 30
            val includeSubtitles = parameters["includeSubtitles"] as? Boolean ?: false
            val includeTempFiles = parameters["includeTempFiles"] as? Boolean ?: true
            val dryRun = parameters["dryRun"] as? Boolean ?: false
            
            val results = mutableMapOf<String, Any>()
            var totalFilesDeleted = 0
            var totalSpaceFreed = 0L
            
            // Clean up temporary files
            if (includeTempFiles) {
                val tempCleanupResult = cleanupTempFiles(olderThanDays, dryRun)
                results["tempFiles"] = tempCleanupResult
                totalFilesDeleted += tempCleanupResult["filesDeleted"] as? Int ?: 0
                totalSpaceFreed += tempCleanupResult["spaceFreed"] as? Long ?: 0L
            }
            
            // Clean up old subtitle files
            if (includeSubtitles) {
                val subtitleCleanupResult = cleanupOldSubtitles(olderThanDays, dryRun)
                results["subtitleFiles"] = subtitleCleanupResult
                totalFilesDeleted += subtitleCleanupResult["filesDeleted"] as? Int ?: 0
                totalSpaceFreed += subtitleCleanupResult["spaceFreed"] as? Long ?: 0L
            }
            
            // Clean up orphaned files (files without database records)
            val orphanCleanupResult = cleanupOrphanedFiles(dryRun)
            results["orphanedFiles"] = orphanCleanupResult
            totalFilesDeleted += orphanCleanupResult["filesDeleted"] as? Int ?: 0
            totalSpaceFreed += orphanCleanupResult["spaceFreed"] as? Long ?: 0L
            
            results["summary"] = mapOf(
                "totalFilesDeleted" to totalFilesDeleted,
                "totalSpaceFreed" to totalSpaceFreed,
                "olderThanDays" to olderThanDays,
                "dryRun" to dryRun,
                "timestamp" to System.currentTimeMillis()
            )
            
            Log.info("Cleanup files task ${task.id} completed. Files deleted: $totalFilesDeleted, Space freed: ${totalSpaceFreed / 1024 / 1024} MB")
            updateTaskResult(task, results)
            
        } catch (e: Exception) {
            Log.error("Cleanup files task ${task.id} failed", e)
            updateTaskResult(task, mapOf(
                "error" to (e.message ?: "Unknown error"),
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    private fun parseCleanupParams(parametersJson: String?): Map<String, Any> {
        return if (parametersJson.isNullOrBlank()) {
            emptyMap()
        } else {
            try {
                val jsonObject = Json.parseToJsonElement(parametersJson).jsonObject
                jsonObject.mapValues { (_, value) ->
                    when {
                        value.jsonPrimitive.isString -> value.jsonPrimitive.content
                        else -> value.jsonPrimitive.content.toIntOrNull() 
                            ?: value.jsonPrimitive.content.toBooleanStrictOrNull() 
                            ?: value.jsonPrimitive.content
                    }
                }
            } catch (e: Exception) {
                Log.warn("Failed to parse cleanup parameters: $parametersJson", e)
                emptyMap()
            }
        }
    }

    private fun cleanupTempFiles(olderThanDays: Int, dryRun: Boolean): Map<String, Any> {
        val tempDirs = listOf(
            System.getProperty("java.io.tmpdir"),
            "/tmp/skiperr",
            "./temp",
            "./tmp"
        )
        
        var filesDeleted = 0
        var spaceFreed = 0L
        val deletedFiles = mutableListOf<String>()
        
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        
        tempDirs.forEach { tempDir ->
            try {
                val dir = java.io.File(tempDir)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoffTime && 
                            (file.name.contains("skiperr") || file.name.contains("subtitle") || file.name.contains("ffmpeg"))) {
                            
                            val fileSize = if (file.isFile) file.length() else 0L
                            
                            if (!dryRun) {
                                if (file.deleteRecursively()) {
                                    filesDeleted++
                                    spaceFreed += fileSize
                                    deletedFiles.add(file.absolutePath)
                                }
                            } else {
                                filesDeleted++
                                spaceFreed += fileSize
                                deletedFiles.add(file.absolutePath)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.warn("Error cleaning temp directory $tempDir", e)
            }
        }
        
        return mapOf(
            "filesDeleted" to filesDeleted,
            "spaceFreed" to spaceFreed,
            "deletedFiles" to deletedFiles.take(100), // Limit to first 100 for response size
            "dryRun" to dryRun
        )
    }

    private fun cleanupOldSubtitles(olderThanDays: Int, dryRun: Boolean): Map<String, Any> {
        // This would need to be implemented based on your subtitle storage strategy
        // For now, return empty result
        return mapOf(
            "filesDeleted" to 0,
            "spaceFreed" to 0L,
            "deletedFiles" to emptyList<String>(),
            "dryRun" to dryRun,
            "note" to "Subtitle cleanup not implemented - depends on storage strategy"
        )
    }

    private fun cleanupOrphanedFiles(dryRun: Boolean): Map<String, Any> {
        // This would need to scan the file system and compare with database records
        // For now, return empty result
        return mapOf(
            "filesDeleted" to 0,
            "spaceFreed" to 0L,
            "deletedFiles" to emptyList<String>(),
            "dryRun" to dryRun,
            "note" to "Orphaned file cleanup not implemented - requires file system scanning"
        )
    }

    /**
     * Perform database backup task
     */
    private fun performBackupDatabase(task: Task): Uni<Void> {
        return try {
            Log.info("Starting database backup task ${task.id}")
            
            val parameters = parseBackupParams(task.parameters)
            val backupType = parameters["backupType"] as? String ?: "full"
            val compressionEnabled = parameters["compressionEnabled"] as? Boolean ?: true
            val retentionDays = parameters["retentionDays"] as? Int ?: 7
            val backupLocation = parameters["backupLocation"] as? String ?: "./backups"
            
            val timestamp = System.currentTimeMillis()
            val backupFileName = "skiperr_backup_${backupType}_${timestamp}.sql${if (compressionEnabled) ".gz" else ""}"
            val backupPath = "$backupLocation/$backupFileName"
            
            // Ensure backup directory exists
            val backupDir = java.io.File(backupLocation)
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }
            
            val results = mutableMapOf<String, Any>()
            
            // Perform the backup based on database type
            val backupResult = when (getDatabaseType()) {
                "postgresql" -> performPostgreSQLBackup(backupPath, backupType, compressionEnabled)
                "mysql" -> performMySQLBackup(backupPath, backupType, compressionEnabled)
                "h2" -> performH2Backup(backupPath, compressionEnabled)
                else -> mapOf(
                    "success" to false,
                    "error" to "Unsupported database type: ${getDatabaseType()}"
                )
            }
            
            if (backupResult["success"] == true) {
                // Clean up old backups based on retention policy
                cleanupOldBackups(backupLocation, retentionDays)
                
                val backupFile = java.io.File(backupPath)
                results.putAll(mapOf(
                    "success" to true,
                    "backupPath" to backupPath,
                    "backupSize" to if (backupFile.exists()) backupFile.length() else 0L,
                    "backupType" to backupType,
                    "compressionEnabled" to compressionEnabled,
                    "timestamp" to timestamp,
                    "retentionDays" to retentionDays
                ))
                
                Log.info("Database backup task ${task.id} completed successfully. Backup saved to: $backupPath")
            } else {
                results.putAll(backupResult)
                Log.error("Database backup task ${task.id} failed: ${backupResult["error"]}")
            }
            
            updateTaskResult(task, results)
            
        } catch (e: Exception) {
            Log.error("Database backup task ${task.id} failed", e)
            updateTaskResult(task, mapOf(
                "success" to false,
                "error" to (e.message ?: "Unknown error"),
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    private fun parseBackupParams(parametersJson: String?): Map<String, Any> {
        return if (parametersJson.isNullOrBlank()) {
            emptyMap()
        } else {
            try {
                val jsonObject = Json.parseToJsonElement(parametersJson).jsonObject
                jsonObject.mapValues { (_, value) ->
                    when {
                        value.jsonPrimitive.isString -> value.jsonPrimitive.content
                        else -> value.jsonPrimitive.content.toIntOrNull() 
                            ?: value.jsonPrimitive.content.toBooleanStrictOrNull() 
                            ?: value.jsonPrimitive.content
                    }
                }
            } catch (e: Exception) {
                Log.warn("Failed to parse backup parameters: $parametersJson", e)
                emptyMap()
            }
        }
    }

    private fun getDatabaseType(): String {
        // This should be determined from your database configuration
        // For now, return a default based on common Quarkus configurations
        return try {
            val datasourceUrl = System.getProperty("quarkus.datasource.jdbc.url") 
                ?: System.getenv("QUARKUS_DATASOURCE_JDBC_URL") 
                ?: ""
            
            when {
                datasourceUrl.contains("postgresql") -> "postgresql"
                datasourceUrl.contains("mysql") -> "mysql"
                datasourceUrl.contains("h2") -> "h2"
                else -> "h2" // Default fallback
            }
        } catch (e: Exception) {
            Log.warn("Could not determine database type, defaulting to H2", e)
            "h2"
        }
    }

    private fun performPostgreSQLBackup(backupPath: String, backupType: String, compressionEnabled: Boolean): Map<String, Any> {
        return try {
            val command = mutableListOf("pg_dump")
            
            // Add connection parameters (these should come from configuration)
            val dbHost = System.getProperty("quarkus.datasource.jdbc.host") ?: "localhost"
            val dbPort = System.getProperty("quarkus.datasource.jdbc.port") ?: "5432"
            val dbName = System.getProperty("quarkus.datasource.jdbc.database") ?: "skiperr"
            val dbUser = System.getProperty("quarkus.datasource.username") ?: "skiperr"
            
            command.addAll(listOf("-h", dbHost, "-p", dbPort, "-U", dbUser, "-d", dbName))
            
            if (backupType == "schema") {
                command.add("--schema-only")
            } else if (backupType == "data") {
                command.add("--data-only")
            }
            
            if (compressionEnabled) {
                command.addAll(listOf("-Z", "9"))
            }
            
            command.addAll(listOf("-f", backupPath))
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.environment()["PGPASSWORD"] = System.getProperty("quarkus.datasource.password") ?: ""
            
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                mapOf("success" to true)
            } else {
                val errorOutput = process.errorStream.bufferedReader().readText()
                mapOf("success" to false, "error" to "pg_dump failed with exit code $exitCode: $errorOutput")
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "PostgreSQL backup failed: ${e.message}")
        }
    }

    private fun performMySQLBackup(backupPath: String, backupType: String, compressionEnabled: Boolean): Map<String, Any> {
        return try {
            val command = mutableListOf("mysqldump")
            
            // Add connection parameters
            val dbHost = System.getProperty("quarkus.datasource.jdbc.host") ?: "localhost"
            val dbPort = System.getProperty("quarkus.datasource.jdbc.port") ?: "3306"
            val dbName = System.getProperty("quarkus.datasource.jdbc.database") ?: "skiperr"
            val dbUser = System.getProperty("quarkus.datasource.username") ?: "skiperr"
            val dbPassword = System.getProperty("quarkus.datasource.password") ?: ""
            
            command.addAll(listOf("-h", dbHost, "-P", dbPort, "-u", dbUser, "-p$dbPassword"))
            
            if (backupType == "schema") {
                command.add("--no-data")
            } else if (backupType == "data") {
                command.add("--no-create-info")
            }
            
            command.add(dbName)
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            
            // Write output to file
            val outputFile = java.io.File(backupPath)
            outputFile.outputStream().use { fileOut ->
                if (compressionEnabled) {
                    java.util.zip.GZIPOutputStream(fileOut).use { gzipOut ->
                        process.inputStream.copyTo(gzipOut)
                    }
                } else {
                    process.inputStream.copyTo(fileOut)
                }
            }
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                mapOf("success" to true)
            } else {
                val errorOutput = process.errorStream.bufferedReader().readText()
                mapOf("success" to false, "error" to "mysqldump failed with exit code $exitCode: $errorOutput")
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "MySQL backup failed: ${e.message}")
        }
    }

    private fun performH2Backup(backupPath: String, compressionEnabled: Boolean): Map<String, Any> {
        return try {
            // For H2, we can use the BACKUP SQL command or copy the database files
            // This is a simplified implementation
            val backupSql = "BACKUP TO '$backupPath'"
            
            // Note: This would need to be executed through your database connection
            // For now, return a placeholder result
            mapOf(
                "success" to false,
                "error" to "H2 backup not fully implemented - requires database connection access"
            )
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "H2 backup failed: ${e.message}")
        }
    }

    private fun cleanupOldBackups(backupLocation: String, retentionDays: Int) {
        try {
            val backupDir = java.io.File(backupLocation)
            if (!backupDir.exists() || !backupDir.isDirectory) return
            
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
            
            backupDir.listFiles { file ->
                file.name.startsWith("skiperr_backup_") && file.lastModified() < cutoffTime
            }?.forEach { oldBackup ->
                try {
                    if (oldBackup.delete()) {
                        Log.info("Deleted old backup: ${oldBackup.name}")
                    }
                } catch (e: Exception) {
                    Log.warn("Failed to delete old backup: ${oldBackup.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.warn("Error during backup cleanup", e)
        }
    }
}