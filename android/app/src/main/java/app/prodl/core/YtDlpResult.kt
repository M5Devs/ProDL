package app.prodl.core

/**
 * Outcome of one yt-dlp run, independent of the youtubedl-android types so the
 * slim build (no bundled Python) can ship a stub with the same signature.
 */
data class YtDlpResult(
    val exitCode: Int,
    val out: String,
    val err: String,
)

/** The user cancelled a running engine process; not an error to report. */
class EngineCanceledException(message: String = "canceled") : Exception(message)
