package app.prodl.core

import java.io.File

/**
 * yt-dlp format selection and argument building, mirroring prodl-core so the
 * phone and the server produce the same files for the same request.
 */
object FormatSelector {
    const val DEFAULT_VIDEO = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
    const val DEFAULT_AUDIO = "bestaudio/best"

    val audioFormats = setOf("mp3", "m4a", "opus", "flac", "wav")
    val videoFormats = setOf("mp4", "webm", "gif")
    val sponsorBlockActions = setOf("cut", "mark", "skip")

    fun selector(request: DownloadRequest): String = when {
        request.audioOnly -> DEFAULT_AUDIO
        !request.formatId.isNullOrBlank() -> request.formatId!!.trim()
        !request.videoQuality.isNullOrBlank() -> heightSelector(request.videoQuality!!)
        else -> DEFAULT_VIDEO
    }

    /** "1080p" / "1080" / "720p60" → a height-capped selector. */
    fun heightSelector(quality: String): String {
        val height = Regex("""(\d{2,4})""").find(quality)?.groupValues?.get(1)?.toIntOrNull()
            ?: return DEFAULT_VIDEO
        return "bestvideo[height<=$height]+bestaudio/best[height<=$height]"
    }

    fun audioFormat(format: String): String =
        format.lowercase().let { if (it in audioFormats) it else "mp3" }

    fun audioQuality(quality: String): String =
        Regex("""(\d{2,3})""").find(quality)?.groupValues?.get(1) ?: "192"

    fun sponsorBlockAction(action: String): String =
        action.lowercase().let { if (it in sponsorBlockActions) it else "cut" }

    fun concurrentFragments(value: Int): Int = value.coerceIn(1, 16)

    fun buildArguments(
        request: DownloadRequest,
        workDir: File,
        cookieFile: File?,
        ffmpegPath: String?,
    ): List<Pair<String, String?>> {
        val args = mutableListOf<Pair<String, String?>>()
        fun add(key: String, value: String? = null) = args.add(key to value)

        add("-P", workDir.absolutePath)
        add("-o", "%(title).120B [%(id)s].%(ext)s")
        add("--newline")
        add("--no-mtime")
        add("--no-playlist")
        add("--retries", "3")
        add("--fragment-retries", "3")
        add("--concurrent-fragments", concurrentFragments(request.concurrentFragments).toString())
        add("--socket-timeout", "20")
        add("--no-check-certificates")

        if (!ffmpegPath.isNullOrBlank()) add("--ffmpeg-location", ffmpegPath)
        if (cookieFile != null) add("--cookies", cookieFile.absolutePath)
        if (!request.maxFilesize.isNullOrBlank()) add("--max-filesize", request.maxFilesize)

        add("-f", selector(request))

        if (request.audioOnly) {
            add("-x")
            add("--audio-format", audioFormat(request.audioFormat))
            add("--audio-quality", audioQuality(request.audioQuality))
        } else {
            add("--merge-output-format", "mp4")
        }

        if (request.subtitles) {
            add("--write-subs")
            add("--sub-langs", request.subtitleLangs.joinToString(",").ifBlank { "en" })
            if (request.autoSubtitles) add("--write-auto-subs")
            if (request.embedSubtitles) add("--embed-subs")
        }
        if (request.thumbnail) {
            add("--write-thumbnail")
            if (request.embedThumbnail) add("--embed-thumbnail")
        }
        if (request.sponsorblock) {
            val categories = request.sponsorblockCategories
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("sponsor", "intro", "outro") }
                .joinToString(",")
            if (sponsorBlockAction(request.sponsorblockAction) == "mark") {
                add("--sponsorblock-mark", categories)
            } else {
                add("--sponsorblock-remove", categories)
            }
        }
        return args
    }
}
