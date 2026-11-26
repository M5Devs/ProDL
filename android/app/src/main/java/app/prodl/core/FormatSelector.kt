package app.prodl.core

import java.io.File

/**
 * yt-dlp format selection and argument building.
 *
 * The Android build has no ffmpeg, so the selector never asks yt-dlp for
 * something that needs it: no `video+audio` merges, no `--extract-audio`, no
 * embedding. yt-dlp fetches the best *single* file and [MediaPipeline] does the
 * post-processing with platform codecs afterwards.
 *
 * The trade is explicit: a site without a progressive stream yields its best
 * progressive quality rather than a merged 4K file. YouTube is not affected —
 * that path goes through NewPipeExtractor, which muxes natively at full quality.
 */
object FormatSelector {
    /** One file with both tracks; `[ext=mp4]` only expresses a preference. */
    const val DEFAULT_VIDEO = "best[ext=mp4]/best"
    const val DEFAULT_AUDIO = "bestaudio[ext=m4a]/bestaudio/best"

    val audioFormats = MediaTargets.audioFormats
    val videoFormats = MediaTargets.videoFormats
    val sponsorBlockActions = setOf("cut", "mark", "skip")

    /** Without ffmpeg only `mark` is possible: `cut` rewrites the container. */
    const val SPONSORBLOCK_WITHOUT_FFMPEG = "mark"

    fun selector(request: DownloadRequest): String = when {
        request.audioOnly -> DEFAULT_AUDIO
        !request.formatId.isNullOrBlank() -> request.formatId!!.trim()
        !request.videoQuality.isNullOrBlank() -> heightSelector(request.videoQuality!!)
        else -> DEFAULT_VIDEO
    }

    /** "1080p" / "1080" / "720p60" → a height-capped, single-file selector. */
    fun heightSelector(quality: String): String {
        val height = Regex("""(\d{2,4})""").find(quality)?.groupValues?.get(1)?.toIntOrNull()
            ?: return DEFAULT_VIDEO
        return "best[height<=$height][ext=mp4]/best[height<=$height]/best"
    }

    fun audioFormat(format: String): String =
        format.lowercase().let { if (it in audioFormats) it else "mp3" }

    fun audioQuality(quality: String): String =
        Regex("""(\d{2,3})""").find(quality)?.groupValues?.get(1) ?: "192"

    /**
     * Only `mark` is possible on this build: `cut` re-muxes the file, which
     * needed ffmpeg. Requests that ask for `cut`/`skip` degrade to `mark`
     * instead of silently producing an un-cut file.
     */
    fun sponsorBlockAction(action: String): String =
        when (action.lowercase()) {
            "cut", "skip", "mark" -> SPONSORBLOCK_WITHOUT_FFMPEG
            else -> SPONSORBLOCK_WITHOUT_FFMPEG
        }

    fun concurrentFragments(value: Int): Int = value.coerceIn(1, 16)

    fun buildArguments(
        request: DownloadRequest,
        workDir: File,
        cookieFile: File?,
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

        if (cookieFile != null) add("--cookies", cookieFile.absolutePath)
        if (!request.maxFilesize.isNullOrBlank()) add("--max-filesize", request.maxFilesize)

        add("-f", selector(request))

        // Subtitles and covers are written as sidecar files: embedding them
        // needs a muxer ffmpeg used to provide.
        if (request.subtitles) {
            add("--write-subs")
            add("--sub-langs", request.subtitleLangs.joinToString(",").ifBlank { "en" })
            if (request.autoSubtitles) add("--write-auto-subs")
        }
        if (request.thumbnail) add("--write-thumbnail")

        if (request.sponsorblock) {
            val categories = request.sponsorblockCategories
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("sponsor", "intro", "outro") }
                .joinToString(",")
            // `--sponsorblock-remove` re-muxes the file (needs ffmpeg); `mark`
            // only writes chapter metadata, which is all we can honour here.
            add("--sponsorblock-mark", categories)
        }
        return args
    }
}
