package gr.accio.services

import gr.accio.models.*
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.Serializable
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*

@ApplicationScoped
class SubtitleExtractionService {

    @ConfigProperty(name = "tools.ffprobe", defaultValue = "ffprobe")
    lateinit var ffprobePath: String

    @ConfigProperty(name = "tools.ffmpeg", defaultValue = "ffmpeg")
    lateinit var ffmpegPath: String

    @Serializable
    data class ExtractionResult(
        val success: Boolean,
        val message: String,
        val extractedSubtitles: List<SubtitleInfo> = emptyList()
    )

    @Serializable
    data class SubtitleInfo(
        val language: String,
        val filePath: String,
        val streamIndex: Int,
        val title: String? = null
    )

    /**
     * Extract all embedded subtitles from a video file
     */
    fun extractSubtitles(videoFile: VideoFile, task: Task): Uni<ExtractionResult> {
        return task.persist<Task>()
            .flatMap {
                task.progressPercentage = 10
                task.status = TaskStatus.RUNNING
                task.lastHeartbeat = Instant.now()
                task.persist<Task>()
            }
            .flatMap {
                Uni.createFrom().item {
                    try {
                        Log.info("Starting subtitle extraction for video: ${videoFile.path}")

                        val videoPath = Path.of(videoFile.path)
                        if (!Files.exists(videoPath)) {
                            return@item ExtractionResult(false, "Video file not found: ${videoFile.path}")
                        }

                        val subtitleStreams = probeSubtitleStreams(videoPath)
                        if (subtitleStreams.isEmpty()) {
                            return@item ExtractionResult(true, "No embedded subtitles found in video", emptyList())
                        }

                Log.info("Found ${subtitleStreams.size} subtitle streams in ${videoFile.title}")
                
                val extractedSubtitles = mutableListOf<SubtitleInfo>()
                val totalStreams = subtitleStreams.size
                
                subtitleStreams.forEachIndexed { index, streamInfo ->
                    try {
                        val outputPath = generateSubtitlePath(videoPath, streamInfo.language, streamInfo.streamIndex)
                        
                        // Extract the subtitle stream
                        val extractionSuccess = extractSubtitleStream(videoPath, streamInfo.streamIndex, outputPath)
                        
                        if (extractionSuccess) {
                            extractedSubtitles.add(SubtitleInfo(
                                language = streamInfo.language,
                                filePath = outputPath.toString(),
                                streamIndex = streamInfo.streamIndex,
                                title = streamInfo.title
                            ))
                            
                            // Subtitle record will be created reactively
                            
                            Log.info("Successfully extracted ${streamInfo.language} subtitle to: $outputPath")
                        } else {
                            Log.warn("Failed to extract subtitle stream ${streamInfo.streamIndex} (${streamInfo.language})")
                        }
                        
                        // Update progress
                        val progressPercentage = 10 + ((index + 1).toDouble() / totalStreams * 80).toInt()
                    } catch (e: Exception) {
                        Log.error("Error extracting subtitle stream ${streamInfo.streamIndex}: ${e.message}", e)
                    }
                }

                        ExtractionResult(
                            success = true,
                            message = "Extracted ${extractedSubtitles.size} of ${totalStreams} subtitle streams",
                            extractedSubtitles = extractedSubtitles
                        )
                    } catch (e: Exception) {
                        Log.error("Failed to extract subtitles from ${videoFile.path}: ${e.message}", e)
                        ExtractionResult(false, "Extraction failed: ${e.message}")
                    }
                }
            }
            .flatMap { result ->
                if (result.success) {
                    task.progressPercentage = 100
                    task.lastHeartbeat = Instant.now()
                    task.persist<Task>().map { result }
                } else {
                    task.status = TaskStatus.FAILED
                    task.errorMessage = result.message
                    task.completedAt = Instant.now()
                    task.persist<Task>().map { result }
                }
            }
    }

    @Serializable
    private data class SubtitleStreamInfo(
        val streamIndex: Int,
        val language: String,
        val title: String? = null
    )

    private fun probeSubtitleStreams(videoPath: Path): List<SubtitleStreamInfo> {
        val probeOutput = runProcess(
            listOf(
                ffprobePath,
                "-v", "error",
                "-select_streams", "s", // Select subtitle streams
                "-show_entries", "stream=index:stream_tags=language:stream_tags=title", // Show index and language tag
                "-of", "compact=p=0:s=N", // Compact output format
                videoPath.toString()
            )
        ) ?: return emptyList()

        val streams = mutableListOf<SubtitleStreamInfo>()
        
        probeOutput.lines().forEach { line ->
            // Example line: stream|index=2|tag:language=eng|tag:title=English
            if (line.startsWith("stream|")) {
                try {
                    val parts = line.split("|")
                    var streamIndex: Int? = null
                    var language = "unknown"
                    var title: String? = null
                    
                    parts.forEach { part ->
                        when {
                            part.startsWith("index=") -> streamIndex = part.substringAfter("index=").toIntOrNull()
                            part.startsWith("tag:language=") -> language = part.substringAfter("tag:language=")
                            part.startsWith("tag:title=") -> title = part.substringAfter("tag:title=")
                        }
                    }
                    
                    streamIndex?.let { index ->
                        streams.add(SubtitleStreamInfo(index, language, title))
                    }
                } catch (e: Exception) {
                    Log.warn("Failed to parse subtitle stream info: $line", e)
                }
            }
        }
        
        return streams
    }

    private fun extractSubtitleStream(videoPath: Path, streamIndex: Int, outputPath: Path): Boolean {
        val ffmpegCommand = listOf(
            ffmpegPath,
            "-i", videoPath.toString(),
            "-map", "0:s:$streamIndex", // Map subtitle stream with found index
            "-c:s", "srt",               // Convert to SRT format
            "-n",                        // Do not overwrite existing files
            outputPath.toString()
        )

        Log.debug("Executing FFmpeg command: ${ffmpegCommand.joinToString(" ")}")
        val result = runProcess(ffmpegCommand)

        return result != null && Files.exists(outputPath) && Files.size(outputPath) > 0
    }

    private fun generateSubtitlePath(videoPath: Path, language: String, streamIndex: Int): Path {
        val baseName = videoPath.fileName.toString().substringBeforeLast('.')
        val parentDir = videoPath.parent
        
        // Create a unique filename with language and stream index
        val filename = "${baseName}.${language}.s${streamIndex}.srt"
        return parentDir.resolve(filename)
    }

    private fun createSubtitleRecord(videoFile: VideoFile, streamInfo: SubtitleStreamInfo, filePath: String): Uni<Subtitle> {
        val subtitle = Subtitle().apply {
            this.video = videoFile
            this.language = streamInfo.language
            this.filePath = filePath
            this.type = SubtitleType.EMBEDDED
            this.isGenerated = false
            this.syncStatus = SyncStatus.NOT_SYNCED
            this.extractedAt = Instant.now()
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        
        return subtitle.persist<Subtitle>()
            .onItem().invoke {
                Log.debug("Created subtitle record for ${streamInfo.language} subtitle: $filePath")
            }
            .onFailure().invoke { e ->
                Log.error("Failed to create subtitle record for $filePath: ${e.message}", e)
            }
    }

    private fun runProcess(command: List<String>): String? {
        var process: Process? = null
        val timeoutSeconds = 60L
        return try {
            val sanitizedCommand = command.map { it.trim() }
            val processBuilder = ProcessBuilder(sanitizedCommand)
            processBuilder.redirectErrorStream(true)
            process = processBuilder.start()
            
            val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                Log.warn("Process timed out after $timeoutSeconds seconds: ${sanitizedCommand.joinToString(" ")}")
                return null
            }
            
            val exitCode = process.exitValue()
            val output = process.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
            
            if (exitCode == 0) {
                output
            } else {
                Log.warn("Process failed with exit code $exitCode: ${sanitizedCommand.joinToString(" ")}")
                Log.warn("Process output: $output")
                null
            }
        } catch (e: Exception) {
            Log.error("Failed to execute process: ${command.joinToString(" ")}", e)
            null
        } finally {
            process?.destroyForcibly()
        }
    }
}