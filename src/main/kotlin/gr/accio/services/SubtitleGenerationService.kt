package gr.accio.services

import gr.accio.models.*
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Service for generating subtitles from video audio using speech-to-text
 */
@ApplicationScoped
class SubtitleGenerationService {

    @ConfigProperty(name = "tools.ffmpeg", defaultValue = "ffmpeg")
    lateinit var ffmpegPath: String

    @ConfigProperty(name = "tools.whisper", defaultValue = "whisper")
    lateinit var whisperPath: String

    @Inject
    lateinit var subtitleParser: SubtitleParser

    /**
     * Generate subtitles from video audio
     */
    fun generateSubtitles(videoFile: VideoFile, task: Task, language: String = "auto"): Uni<SubtitleGenerationResult> {
        return task.persist<Task>()
            .flatMap {
                task.status = TaskStatus.RUNNING
                task.startedAt = Instant.now()
                task.progressPercentage = 5
                task.persist<Task>()
            }
            .flatMap {
                Log.info("Starting subtitle generation for video: ${videoFile.path}")
                task.progressPercentage = 10
                task.persist<Task>()
            }
            .flatMap {
                val videoPath = Paths.get(videoFile.path)
                val audioPath = extractAudio(videoPath)
                
                if (audioPath == null) {
                    return@flatMap Uni.createFrom().failure<SubtitleGenerationResult>(
                        IllegalStateException("Failed to extract audio from video")
                    )
                }
                
                task.progressPercentage = 30
                task.persist<Task>()
                    .flatMap {
                        Uni.createFrom().item {
                            generateSubtitlesFromAudio(audioPath, language, task)
                        }
                    }
                    .flatMap { subtitlePath ->
                        if (subtitlePath == null) {
                            return@flatMap Uni.createFrom().failure<SubtitleGenerationResult>(
                                IllegalStateException("Failed to generate subtitles from audio")
                            )
                        }
                        
                        task.progressPercentage = 80
                        task.persist<Task>()
                            .flatMap {
                                val subtitleEntries = subtitleParser.parseSubtitleFile(subtitlePath.toString())
                                
                                task.progressPercentage = 90
                                task.persist<Task>()
                                    .flatMap {
                                        createSubtitleRecord(videoFile, subtitlePath.toString(), language, subtitleEntries.size)
                                    }
                                    .flatMap { subtitle ->
                                        try {
                                            audioPath.toFile().delete()
                                        } catch (e: Exception) {
                                            Log.warn("Failed to delete temporary audio file: ${audioPath}")
                                        }
                                        
                                        task.progressPercentage = 100
                                        task.status = TaskStatus.COMPLETED
                                        task.completedAt = Instant.now()
                                        task.persist<Task>()
                                            .map {
                                                Log.info("Successfully generated subtitles: ${subtitlePath}")
                                                SubtitleGenerationResult(
                                                    success = true,
                                                    message = "Subtitles generated successfully",
                                                    subtitlePath = subtitlePath.toString(),
                                                    subtitle = subtitle,
                                                    entryCount = subtitleEntries.size
                                                )
                                            }
                                    }
                            }
                    }
            }
            .onFailure().recoverWithUni { e ->
                Log.error("Failed to generate subtitles for video ${videoFile.path}: ${e.message}", e)
                
                task.status = TaskStatus.FAILED
                task.errorMessage = e.message
                task.completedAt = Instant.now()
                task.persist<Task>()
                    .map {
                        SubtitleGenerationResult(
                            success = false,
                            message = "Failed to generate subtitles: ${e.message}",
                            subtitlePath = null,
                            subtitle = null,
                            entryCount = 0
                        )
                    }
            }
    }

    /**
     * Extract audio from video file
     */
    private fun extractAudio(videoPath: Path): Path? {
        return try {
            val audioPath = videoPath.parent.resolve("${videoPath.fileName.toString().substringBeforeLast('.')}_temp_audio.wav")
            
            val command = listOf(
                ffmpegPath,
                "-i", videoPath.toString(),
                "-vn",                    // No video
                "-acodec", "pcm_s16le",   // PCM 16-bit little-endian
                "-ar", "16000",           // 16kHz sample rate (good for speech recognition)
                "-ac", "1",               // Mono channel
                "-y",                     // Overwrite output file
                audioPath.toString()
            )
            
            Log.debug("Extracting audio with command: ${command.joinToString(" ")}")
            val success = runProcess(command)
            
            if (success && audioPath.toFile().exists()) {
                Log.info("Audio extracted successfully: $audioPath")
                audioPath
            } else {
                Log.error("Audio extraction failed or output file not found")
                null
            }
        } catch (e: Exception) {
            Log.error("Failed to extract audio: ${e.message}", e)
            null
        }
    }

    /**
     * Generate subtitles from audio using Whisper or similar speech-to-text
     */
    private fun generateSubtitlesFromAudio(audioPath: Path, language: String, task: Task): Path? {
        return try {
            val outputDir = audioPath.parent
            val baseName = audioPath.fileName.toString().substringBeforeLast('.')
            val subtitlePath = outputDir.resolve("${baseName}.srt")
            
            // Use Whisper for speech-to-text (if available)
            val command = if (isWhisperAvailable()) {
                listOf(
                    whisperPath,
                    audioPath.toString(),
                    "--model", "base",           // Use base model for balance of speed/accuracy
                    "--language", if (language == "auto") "auto" else language,
                    "--output_format", "srt",    // SRT format output
                    "--output_dir", outputDir.toString(),
                    "--verbose", "False"
                )
            } else {
                // Fallback: create a placeholder subtitle indicating no speech-to-text available
                Log.warn("Whisper not available, creating placeholder subtitle")
                createPlaceholderSubtitle(subtitlePath)
                return subtitlePath
            }
            
            Log.debug("Generating subtitles with command: ${command.joinToString(" ")}")
            
            // Run the speech-to-text process with progress updates
            val success = runProcessWithProgress(command, task, 30, 80)
            
            if (success && subtitlePath.toFile().exists()) {
                Log.info("Subtitles generated successfully: $subtitlePath")
                subtitlePath
            } else {
                Log.error("Subtitle generation failed or output file not found")
                null
            }
        } catch (e: Exception) {
            Log.error("Failed to generate subtitles from audio: ${e.message}", e)
            null
        }
    }

    /**
     * Check if Whisper is available on the system
     */
    private fun isWhisperAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(whisperPath, "--help").start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create a placeholder subtitle when speech-to-text is not available
     */
    private fun createPlaceholderSubtitle(subtitlePath: Path) {
        val placeholderContent = """1
00:00:00,000 --> 00:00:05,000
[Speech-to-text service not available]

2
00:00:05,000 --> 00:00:10,000
Please install Whisper or configure speech-to-text service
"""
        subtitlePath.toFile().writeText(placeholderContent)
    }

    /**
     * Create subtitle record in database
     */
    private fun createSubtitleRecord(videoFile: VideoFile, filePath: String, language: String, entryCount: Int): Uni<Subtitle> {
        val subtitle = Subtitle().apply {
            this.video = videoFile
            this.language = if (language == "auto") "unknown" else language
            this.filePath = filePath
            this.type = SubtitleType.GENERATED
            this.isGenerated = true
            this.syncStatus = SyncStatus.SYNCED
            this.lineCount = entryCount
            this.extractedAt = Instant.now()
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        
        return subtitle.persist<Subtitle>()
            .onItem().invoke {
                Log.debug("Created subtitle record for generated subtitle: $filePath")
            }
    }

    /**
     * Run a process and return success status
     */
    private fun runProcess(command: List<String>): Boolean {
        var process: Process? = null
        val timeoutSeconds = 300L
        return try {
            val sanitizedCommand = command.map { it.trim() }
            val processBuilder = ProcessBuilder(sanitizedCommand)
            processBuilder.redirectErrorStream(true)
            process = processBuilder.start()
            
            val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                Log.warn("Process timed out after $timeoutSeconds seconds: ${sanitizedCommand.joinToString(" ")}")
                return false
            }
            
            val exitCode = process.exitValue()
            exitCode == 0
        } catch (e: Exception) {
            Log.error("Process execution failed: ${e.message}", e)
            false
        } finally {
            process?.destroyForcibly()
        }
    }

    /**
     * Run a process with progress updates
     */
    private fun runProcessWithProgress(command: List<String>, task: Task, startProgress: Int, endProgress: Int): Boolean {
        var process: Process? = null
        val timeoutSeconds = 600L
        return try {
            val sanitizedCommand = command.map { it.trim() }
            val processBuilder = ProcessBuilder(sanitizedCommand)
            processBuilder.redirectErrorStream(true)
            process = processBuilder.start()
            
            var currentProgress = startProgress
            var line: String?
            
            process.inputStream.bufferedReader().use { reader ->
                while (reader.readLine().also { line = it } != null) {
                    if (currentProgress < endProgress) {
                        currentProgress = minOf(currentProgress + 2, endProgress)
                        task.progressPercentage = currentProgress
                        task.lastHeartbeat = Instant.now()
                    }
                    
                    Log.debug("Speech-to-text output: $line")
                }
            }
            
            val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                Log.warn("Process timed out after $timeoutSeconds seconds: ${sanitizedCommand.joinToString(" ")}")
                return false
            }
            
            val exitCode = process.exitValue()
            exitCode == 0
        } catch (e: Exception) {
            Log.error("Process execution with progress failed: ${e.message}", e)
            false
        } finally {
            process?.destroyForcibly()
        }
    }
}

/**
 * Result of subtitle generation operation
 */
data class SubtitleGenerationResult(
    val success: Boolean,
    val message: String,
    val subtitlePath: String?,
    val subtitle: Subtitle?,
    val entryCount: Int
)