package app.prodl.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * Slim-build stand-in for the yt-dlp wrapper (`-PprodlSlim=true`).
 *
 * The slim APK ships no CPython and no yt-dlp: only NewPipeExtractor plus the
 * native media pipeline. This class keeps the same surface as the real
 * [YtDlp] so `/api/health` reports a clearly failed `yt_dlp` component instead
 * of the app failing to link, and YouTube keeps working through NewPipe.
 */
@Suppress("UNUSED_PARAMETER")
class YtDlp(context: Context) {
    private val unavailable = "not included in this slim build (YouTube-only, no bundled Python)"

    val initializationError: String = unavailable

    val step: String get() = "unavailable"

    val isReady: Boolean get() = false

    fun initAsync(scope: CoroutineScope) = Unit

    suspend fun attemptInit(): Boolean = false

    suspend fun awaitReady(): Nothing = throw YtDlpException(unavailable)

    fun version(): String? = null

    suspend fun dumpJson(
        target: String,
        extra: List<Pair<String, String?>> = emptyList(),
    ): JsonObject = throw YtDlpException(unavailable)

    suspend fun download(
        url: String,
        arguments: List<Pair<String, String?>>,
        processId: String,
        onProgress: (Float, Long, String) -> Unit,
    ): YtDlpResult = throw YtDlpException(unavailable)

    fun cancel(processId: String): Boolean = false

    fun updateBinary(): Boolean = false
}
