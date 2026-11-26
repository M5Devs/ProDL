package app.prodl.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin coroutine wrapper around youtubedl-android (yt-dlp + ffmpeg).
 *
 * Initialisation extracts the bundled Python/ffmpeg binaries on first launch,
 * so it runs once off the main thread and every caller awaits [awaitReady].
 */
class YtDlp(private val context: Context) {
    private val ready = CompletableDeferred<Unit>()
    private val initStarted = AtomicBoolean(false)

    @Volatile
    private var initError: String? = null

    @Volatile
    private var cachedVersion: String? = null

    @Volatile
    var ffmpegPath: String? = null
        private set

    val initializationError: String? get() = initError

    fun initAsync(scope: CoroutineScope) {
        if (!initStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                FFmpeg.getInstance().init(context)
                ffmpegPath = FfmpegRunner(context).binary()?.absolutePath
                YoutubeDL.getInstance().init(context)
                cachedVersion = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
                Log.i(TAG, "yt-dlp ready: ${cachedVersion ?: "unknown version"}")
                ready.complete(Unit)
            } catch (t: Throwable) {
                initError = t.message ?: t::class.java.simpleName
                Log.e(TAG, "yt-dlp init failed", t)
                ready.completeExceptionally(t)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    fun version(): String? = cachedVersion

    /** Run yt-dlp with `--dump-single-json` and return the parsed object. */
    suspend fun dumpJson(
        target: String,
        extra: List<Pair<String, String?>> = emptyList(),
    ): JsonObject = withContext(Dispatchers.IO) {
        awaitReady()
        val request = YoutubeDLRequest(target)
        request.addOption("--dump-single-json")
        request.addOption("--no-warnings")
        request.addOption("--no-progress")
        request.addOption("--ignore-config")
        request.addOption("--socket-timeout", "20")
        extra.forEach { (key, value) -> if (value == null) request.addOption(key) else request.addOption(key, value) }

        val response = YoutubeDL.getInstance().execute(request)
        val text = response.out
        if (text.isBlank()) throw YtDlpException(cleanError(response.err.ifBlank { "yt-dlp returned no data" }))
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw YtDlpException("Unexpected yt-dlp output")
        AppJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
            ?: throw YtDlpException("Unexpected yt-dlp output")
    }

    /** Run a download with live progress. Cancellation goes through [cancel]. */
    suspend fun download(
        url: String,
        arguments: List<Pair<String, String?>>,
        processId: String,
        onProgress: (Float, Long, String) -> Unit,
    ): YoutubeDLResponse = withContext(Dispatchers.IO) {
        awaitReady()
        val request = YoutubeDLRequest(url)
        arguments.forEach { (key, value) -> if (value == null) request.addOption(key) else request.addOption(key, value) }
        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            onProgress(progress, eta, line)
        }
    }

    fun cancel(processId: String): Boolean =
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    fun updateBinary(): Boolean = runCatching {
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "ProDL.YtDlp"
    }
}
