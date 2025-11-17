package gr.accio.services

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

@ApplicationScoped
class SubtitleParser {

    fun parseSubtitleFile(filePath: String): List<SubtitleEntry> {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Subtitle file not found: $filePath")
        }

        val extension = file.extension.lowercase()
        return when (extension) {
            "srt" -> parseSRT(file)
            "vtt" -> parseVTT(file)
            else -> throw IllegalArgumentException("Unsupported subtitle format: $extension")
        }
    }

    fun writeSubtitleFile(entries: List<SubtitleEntry>, outputPath: String, format: String = "srt") {
        val content = when (format.lowercase()) {
            "srt" -> generateSRT(entries)
            "vtt" -> generateVTT(entries)
            else -> throw IllegalArgumentException("Unsupported output format: $format")
        }

        Files.writeString(Path.of(outputPath), content)
    }

    private fun parseSRT(file: File): List<SubtitleEntry> {
        val lines = file.readLines()
        val entries = mutableListOf<SubtitleEntry>()
        var i = 0

        while (i < lines.size) {
            // Skip empty lines
            if (lines[i].isBlank()) {
                i++
                continue
            }

            // Parse sequence number
            val sequenceNumber = lines[i].toIntOrNull() ?: run {
                i++
                continue
            }
            i++

            // Parse timing
            if (i >= lines.size || !lines[i].contains("-->")) {
                i++
                continue
            }
            val timingLine = lines[i]
            val (startTime, endTime) = parseTimingLine(timingLine)
            i++

            // Parse text content
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                textLines.add(lines[i])
                i++
            }

            if (textLines.isNotEmpty()) {
                entries.add(
                    SubtitleEntry(
                        sequenceNumber = sequenceNumber,
                        startTime = startTime,
                        endTime = endTime,
                        text = textLines.joinToString("\n")
                    )
                )
            }
        }

        return entries
    }

    private fun parseVTT(file: File): List<SubtitleEntry> {
        val lines = file.readLines()
        val entries = mutableListOf<SubtitleEntry>()
        var i = 0

        // Skip WEBVTT header
        if (lines.isNotEmpty() && lines[0].startsWith("WEBVTT")) {
            i = 1
        }

        var sequenceNumber = 1

        while (i < lines.size) {
            // Skip empty lines and cue identifiers
            if (lines[i].isBlank() || !lines[i].contains("-->")) {
                i++
                continue
            }

            // Parse timing
            val timingLine = lines[i]
            val (startTime, endTime) = parseTimingLine(timingLine)
            i++

            // Parse text content
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                textLines.add(lines[i])
                i++
            }

            if (textLines.isNotEmpty()) {
                entries.add(
                    SubtitleEntry(
                        sequenceNumber = sequenceNumber++,
                        startTime = startTime,
                        endTime = endTime,
                        text = textLines.joinToString("\n")
                    )
                )
            }
        }

        return entries
    }

    private fun parseTimingLine(timingLine: String): Pair<LocalTime, LocalTime> {
        val parts = timingLine.split("-->").map { it.trim() }
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid timing line: $timingLine")
        }

        val startTime = parseTimeString(parts[0])
        val endTime = parseTimeString(parts[1])
        return Pair(startTime, endTime)
    }

    private fun parseTimeString(timeString: String): LocalTime {
        // Normalize both "00:00:00,000" → "00:00:00.000"
        val normalizedTime = timeString.replace(',', '.')

        return try {
            // Try parsing with milliseconds
            val parts = normalizedTime.split(':', '.')
            LocalTime(
                hour = parts[0].toInt(),
                minute = parts[1].toInt(),
                second = parts[2].toInt(),
                nanosecond = parts.getOrNull(3)?.padEnd(3, '0')?.toInt()?.times(1_000_000) ?: 0
            )
        } catch (e: Exception) {
            // Fallback: no milliseconds
            val parts = normalizedTime.split(':')
            LocalTime(
                hour = parts[0].toInt(),
                minute = parts[1].toInt(),
                second = parts[2].toInt(),
                nanosecond = 0
            )
        }
    }


    private fun generateSRT(entries: List<SubtitleEntry>): String {
        return entries.joinToString("\n\n") { entry ->
            val startTime = formatTimeForSRT(entry.startTime)
            val endTime = formatTimeForSRT(entry.endTime)
            "${entry.sequenceNumber}\n$startTime --> $endTime\n${entry.text}"
        }
    }

    private fun generateVTT(entries: List<SubtitleEntry>): String {
        val header = "WEBVTT\n\n"
        val content = entries.joinToString("\n\n") { entry ->
            val startTime = formatTimeForVTT(entry.startTime)
            val endTime = formatTimeForVTT(entry.endTime)
            "$startTime --> $endTime\n${entry.text}"
        }
        return header + content
    }

    private fun formatTimeForSRT(time: LocalTime): String =
        "%02d:%02d:%02d,%03d".format(time.hour, time.minute, time.second, time.nanosecond / 1_000_000)

    private fun formatTimeForVTT(time: LocalTime): String =
        "%02d:%02d:%02d.%03d".format(time.hour, time.minute, time.second, time.nanosecond / 1_000_000)

}

@Serializable
data class SubtitleEntry(
    val sequenceNumber: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val text: String
)