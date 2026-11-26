package app.prodl.core

import java.io.File

/** Extension → MIME mapping and filename hygiene for the formats ProDL produces. */
object MimeTypes {
    private val table = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "flv" to "video/x-flv",
        "ts" to "video/mp2t",
        "gif" to "image/gif",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "opus" to "audio/opus",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "weba" to "audio/webm",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "vtt" to "text/vtt",
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
    )

    val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts", "gif")
    val audioExtensions = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "weba")

    fun guess(filename: String): String = table[extensionOf(filename)] ?: "application/octet-stream"

    fun isVideo(filename: String): Boolean = extensionOf(filename) in videoExtensions

    fun isAudio(filename: String): Boolean = extensionOf(filename) in audioExtensions

    fun extensionOf(filename: String): String = filename.substringAfterLast('.', "").lowercase()

    /** Strip path separators and control characters so a title is safe as a filename. */
    fun safeName(raw: String, fallback: String = "download"): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        return cleaned.ifEmpty { fallback }.take(150)
    }

    /** Non-colliding target inside [dir]: "clip.mp4" → "clip (2).mp4". */
    fun uniqueTarget(dir: File, filename: String): File {
        var candidate = File(dir, filename)
        if (!candidate.exists()) return candidate
        val base = filename.substringBeforeLast('.', filename)
        val ext = filename.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }
}
