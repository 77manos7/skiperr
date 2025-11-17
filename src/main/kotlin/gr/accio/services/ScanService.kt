package gr.accio.services

import gr.accio.models.VideoFile
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import org.apache.tika.Tika
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension

@ApplicationScoped
class ScanService {

    private val ffprobeCmd = "ffprobe"
    private val ffmpegCmd = "ffmpeg"
    private val commandTimeoutSeconds = 60L

    private val libraryPaths: List<String> = System.getenv("LIBRARY_PATHS")?.split(",")?.map { it.trim() } ?: emptyList()

    private val tika = Tika()

    fun scanLibrary(): Uni<List<VideoFile>> {
        return scanLibrary(libraryPaths)
    }

    fun scanLibrary(paths: List<String>): Uni<List<VideoFile>> {
        if (paths.isEmpty()) {
            println("No library paths provided. Skipping scan.")
            return Uni.createFrom().item(emptyList())
        }

        val videoFilesUnis = paths.mapNotNull { path ->
            val libraryRootPath = try {
                Paths.get(path)
            } catch (e: Exception) {
                System.err.println("Invalid library path '$path': ${e.message}. Skipping.")
                return@mapNotNull null
            }

            if (!libraryRootPath.exists() || !Files.isDirectory(libraryRootPath)) {
                System.err.println("Library path '$path' does not exist or is not a directory. Skipping.")
                return@mapNotNull null
            }

            println("Scanning library path: $path")
            
            try {
                val files = Files.walk(libraryRootPath).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && isVideoFile(it) }
                        .map { filePath -> filePath }
                        .toList()
                }
                
                println("Found ${files.size} video files in $path")
                
                Multi.createFrom().iterable(files)
                    .onItem().transformToUni { filePath ->
                        scanVideoFile(filePath)
                    }
                    .merge()
                    .collect().asList()
            } catch (e: Exception) {
                System.err.println("Error scanning library path '$path': ${e.message}")
                Uni.createFrom().item<List<VideoFile>>(emptyList())
            }
        }

        if (videoFilesUnis.isEmpty()) {
            return Uni.createFrom().item(emptyList())
        }

        return Uni.combine().all().unis(videoFilesUnis).combinedWith { arrays ->
            arrays.flatMap { it as List<VideoFile> }
        }.flatMap { foundFiles ->
            if (foundFiles.isEmpty()) {
                Uni.createFrom().item(emptyList())
            } else {
                Multi.createFrom().iterable(foundFiles)
                    .onItem().transformToUniAndMerge { vf ->
                        VideoFile.persist(vf)
                    }
                    .collect().asList()
                    .map { 
                        println("Scan complete. Found ${foundFiles.size} video files.")
                        foundFiles 
                    }
            }
        }
    }

    fun listAll(): Uni<List<VideoFile>> {
        return VideoFile.listAll()
    }

    /**
     * Determines if a file is a video using Apache Tika.
     */
    fun isVideoFile(path: Path): Boolean {
        if (!path.exists() || !Files.isReadable(path)) {
            return false
        }
        try {
            val mediaType = tika.detect(path.toFile())
            return mediaType != null && mediaType.startsWith("video/")
        } catch (e: IOException) {
            System.err.println("Error detecting media type with Tika for $path: ${e.message}")
            return false
        }
    }

    private fun scanVideoFile(path: Path): Uni<VideoFile> {
        val ffprobeJson =
            runProcess(listOf(ffprobeCmd, "-v", "quiet", "-print_format", "json", "-show_streams", path.toString()))
                ?: return Uni.createFrom().failure(RuntimeException("Failed to probe video file: $path"))

        val hasEngSubtitle =
            ffprobeJson.contains("\"tags\": {\"language\": \"eng\"") || ffprobeJson.contains("\"language\":\"en\"")

        return VideoFile.findByPath(path.toString())
            .map { existingVf ->
                val vf = existingVf ?: VideoFile()
                
                vf.path = path.toString()
                vf.type = tika.detect(path.toFile()) ?: "unknown"
                vf.title = path.fileName.toString().substringBeforeLast('.') // Use nameWithoutExtension for cleaner title
                vf.hasEmbeddedEnglish = hasEngSubtitle
                vf.hasGreekSubtitle = checkGreekSubtitleExists(path)
                vf.lastChecked = java.time.Instant.now()

                if (vf.id == null) {
                    vf.generatedGreek = false
                    vf.synced = false
                }

                val targetEngSubtitlePath = getSubtitleFile(path, "eng")
                if (hasEngSubtitle && targetEngSubtitlePath != null && !Files.exists(targetEngSubtitlePath)) {
                    extractSubtitle(path, "eng", targetEngSubtitlePath)
                }

                vf
            }
    }

    private fun checkGreekSubtitleExists(path: Path): Boolean {
        val folder = path.parent ?: return false
        val baseName = path.nameWithoutExtension
        return folder.resolve("$baseName.gr.srt").exists() || folder.resolve("$baseName.el.srt").exists()
    }

    /**
     * Constructs the expected subtitle file path.
     * @param videoPath The path to the video file.
     * @param lang The two-letter language code (e.g., "eng", "el").
     * @return The Path object for the expected subtitle file.
     */
    private fun getSubtitleFile(videoPath: Path, lang: String): Path? {
        val parent = videoPath.parent ?: return null
        val baseName = videoPath.nameWithoutExtension
        return parent.resolve("$baseName.$lang.srt")
    }

    /**
     * Extracts a subtitle stream from a video file using ffmpeg.
     *
     * @param videoPath The path to the video file.
     * @param lang The two-letter language code of the subtitle to extract (e.g., "eng", "el").
     * @param outputPath The path where the SRT file should be saved.
     * @return True if extraction was successful, false otherwise.
     */
    private fun extractSubtitle(videoPath: Path, lang: String, outputPath: Path): Boolean {
        // First, find the stream index for the desired language
        val probeOutput = runProcess(
            listOf(
                ffprobeCmd,
                "-v", "error",
                "-select_streams", "s", // Select subtitle streams
                "-show_entries", "stream=index:stream_tags=language:stream_tags=title", // Show index and language tag
                "-of", "compact=p=0:s=N", // Compact output format
                videoPath.toString()
            )
        ) ?: run {
            System.err.println("Failed to probe subtitle streams for ${videoPath.fileName}")
            return false
        }

        var streamIndex: String? = null
        probeOutput.lines().forEach { line ->
            // Example line: stream|index=0|tag:language=eng|tag:title=English
            if (line.contains("tag:language=$lang")) {
                streamIndex = line.substringAfter("index=").substringBefore("|").trim()
                return@forEach // Found it, exit loop
            }
        }

        if (streamIndex == null) {
            System.err.println("No '$lang' subtitle stream found in ${videoPath.fileName}.")
            return false
        }

        // Now, use ffmpeg to extract the stream
        val ffmpegCommand = listOf(
            ffmpegCmd,
            "-i", videoPath.toString(),
            "-map", "0:s:$streamIndex", // Map stream 0 (input), subtitle stream with found index
            "-c:s", "srt",               // Convert to SRT format
            "-n",                        // Do not overwrite existing files
            outputPath.toString()
        )

        println("Executing FFmpeg command: ${ffmpegCommand.joinToString(" ")}")
        val result = runProcess(ffmpegCommand)

        if (result != null && outputPath.exists() && Files.size(outputPath) > 0) {
            return true
        } else {
            System.err.println("FFmpeg extraction failed or produced an empty file for $videoPath (lang: $lang). Output: $result")
            // Optionally, log the ffmpeg stderr for debugging if runProcess was modified to capture it
            return false
        }
    }

    /**
     * Helper function to run an external process.
     *
     * @param command The command and its arguments as a list of strings.
     * @return The standard output of the command, or null if an error occurred or command timed out.
     */
    private fun runProcess(command: List<String>): String? {
        var process: Process? = null
        return try {
            val sanitizedCommand = command.map { it.trim() }
            val processBuilder = ProcessBuilder(sanitizedCommand)
            processBuilder.redirectErrorStream(true)
            process = processBuilder.start()

            val finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                System.err.println("Command timed out after $commandTimeoutSeconds seconds: ${sanitizedCommand.joinToString(" ")}")
                return null
            }

            val exitCode = process.exitValue()
            
            val output = process.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }

            if (exitCode != 0) {
                System.err.println("Command failed with exit code $exitCode: ${sanitizedCommand.joinToString(" ")}")
                System.err.println("Command Output: \n$output")
                return null
            }

            output
        } catch (e: IOException) {
            System.err.println("IOException when running command '${command.joinToString(" ")}': ${e.message}")
            null
        } catch (e: SecurityException) {
            System.err.println("SecurityException when running command '${command.joinToString(" ")}': ${e.message}")
            null
        } finally {
            process?.destroyForcibly()
        }
    }
}