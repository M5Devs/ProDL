package app.prodl.core

import java.util.Locale

/** One parsed yt-dlp output line; every field is optional. */
data class ProgressSample(
    val percent: Double? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speed: Double? = null,
    val eta: Long? = null,
    val filename: String? = null,
    val message: String? = null,
)

/**
 * Parses the lines yt-dlp writes to stdout:
 *
 *   [download]  12.3% of ~ 10.00MiB at  1.20MiB/s ETA 00:05
 *   [download] 100% of 10.00MiB in 00:08
 *   [download] Destination: clip.f137.mp4
 *   [Merger] Merging formats into "clip.mkv"
 *   [ExtractAudio] Destination: clip.mp3
 *
 * Free of Android types so it can be unit tested on the JVM.
 */
object ProgressLine {
    private val SIZE = Regex("""([\d.]+)\s*([KMGTP]?i?B)""", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("""([\d.]+)%""")
    private val SPEED = Regex("""at\s+([\d.]+)\s*([KMGTP]?i?B)/s""", RegexOption.IGNORE_CASE)
    private val TOTAL = Regex("""of\s+~?\s*([\d.]+)\s*([KMGTP]?i?B)""", RegexOption.IGNORE_CASE)
    private val ETA = Regex("""ETA\s+(\d+):(\d+)""")
    private val ETA_UNKNOWN = Regex("""ETA\s+Unknown""", RegexOption.IGNORE_CASE)
    private val DESTINATION = Regex("""^\[[\w:]+]\s+Destination:\s+(.+)$""")
    private val MERGER = Regex("""^\[Merger]\s+Merging formats into\s+"(.+)"$""")

    fun parse(line: String): ProgressSample? {
        val text = line.trim()
        if (text.isEmpty()) return null

        DESTINATION.find(text)?.let { match ->
            return ProgressSample(filename = match.groupValues[1].trim(), message = "Saving media")
        }
        MERGER.find(text)?.let { match ->
            return ProgressSample(filename = match.groupValues[1].trim(), message = "Merging formats")
        }
        if (text.startsWith("[FixupM3u8]") || text.startsWith("[FixupTimestamp]")) {
            return ProgressSample(message = "Fixing container")
        }
        if (text.startsWith("[ExtractAudio]")) {
            val destination = text.substringAfter("Destination:", "").trim()
            return ProgressSample(filename = destination.ifEmpty { null }, message = "Extracting audio")
        }
        if (text.startsWith("[SponsorBlock]")) {
            return ProgressSample(message = text.removePrefix("[SponsorBlock]").trim())
        }
        if (text.startsWith("[Metadata]") || text.startsWith("[ThumbnailsConvertor]")) {
            return ProgressSample(message = "Embedding metadata")
        }
        if (!text.contains('%')) return null

        val percent = PERCENT.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val totalBytes = TOTAL.find(text)?.let { sizeOf(it) }
        val downloadedBytes =
            if (percent != null && totalBytes != null) (totalBytes * percent / 100.0).toLong() else null
        val speed = SPEED.find(text)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@let null
            value * multiplier(match.groupValues[2])
        }
        val eta = ETA.find(text)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            minutes * 60 + seconds
        } ?: if (ETA_UNKNOWN.containsMatchIn(text)) 0L else null

        return ProgressSample(
            percent = percent,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speed = speed,
            eta = eta,
            message = if (percent != null && percent >= 100.0) "Download finished" else "Downloading",
        )
    }

    fun parseSize(text: String): Long? {
        val match = SIZE.find(text) ?: return null
        return sizeOf(match)
    }

    fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0) return "0B/s"
        val units = listOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s")
        var value = bytesPerSecond
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${value.toLong()}${units[unit]}"
        } else {
            String.format(Locale.US, "%.2f%s", value, units[unit])
        }
    }

    private fun sizeOf(match: MatchResult): Long? {
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return (value * multiplier(match.groupValues[2])).toLong()
    }

    private fun multiplier(unit: String): Double = when (unit.lowercase()) {
        "kib", "kb" -> 1024.0
        "mib", "mb" -> 1024.0 * 1024
        "gib", "gb" -> 1024.0 * 1024 * 1024
        "tib", "tb" -> 1024.0 * 1024 * 1024 * 1024
        "pib", "pb" -> 1024.0 * 1024 * 1024 * 1024 * 1024
        else -> 1.0
    }
}
