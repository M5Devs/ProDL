package app.prodl.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Thin coroutine wrapper around youtubedl-android (yt-dlp + ffmpeg).
 *
 * The bundled payload has to be unpacked on first launch, so init runs off the
 * main thread. Every step is bounded and reports the step that stalled: a
 * health check can say "initialising: unpacking Python" or "failed: timed out
 * probing Python" but never "initialising" forever.
 */
class YtDlp(context: Context) {
    // Always the application context: init outlives the Activity that started
    // it and may run again after a configuration change.
    private val appContext: Context = context.applicationContext ?: context

    private val initMutex = Mutex()

    @Volatile
    private var initError: String? = null

    @Volatile
    private var initDone = false

    @Volatile
    private var cachedVersion: String? = null

    @Volatile
    private var ffmpegError: String? = null

    @Volatile
    private var lastStep: String = "idle"

    @Volatile
    var ffmpegPath: String? = null
        private set

    /** yt-dlp/Python failure, if any — surfaced by `/api/health`. */
    val initializationError: String? get() = initError

    /** FFmpeg failure, if any. Downloads still work without it. */
    val ffmpegInitializationError: String? get() = ffmpegError

    /** What init is doing right now, for the health note. */
    val step: String get() = lastStep

    val isReady: Boolean get() = initDone && initError == null

    fun initAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { attemptInit() }
    }

    /**
     * Run (or re-run) initialisation. Serialised: two callers can never unpack
     * the payload concurrently, and a completed init is a no-op.
     */
    suspend fun attemptInit(): Boolean = initMutex.withLock {
        if (initDone && initError == null) return@withLock true
        initError = null
        // Both engines unpack their own payload; run them side by side so
        // ffmpeg's 35 MB unzip does not delay Python.
        val pythonDone = coroutineScope {
            val ffmpegJob = launch { initFfmpeg() }
            val done = initPython()
            ffmpegJob.join()
            done
        }
        pythonDone
    }

    /**
     * Unpack ffmpeg. Optional: a broken ffmpeg must not take search or
     * download down with it, so it fails on its own and is reported separately.
     */
    private fun initFfmpeg() {
        lastStep = "unpacking ffmpeg"
        try {
            FFmpeg.getInstance().init(appContext)
            ffmpegPath = FfmpegRunner(appContext).binary()?.absolutePath
            ffmpegError = null
            Log.i(TAG, "ffmpeg ready: ${ffmpegPath ?: "binary not found yet"}")
        } catch (t: Throwable) {
            ffmpegError = describe(t)
            Log.e(TAG, "ffmpeg init failed", t)
        }
    }

    /**
     * Unpack the bundled Python + yt-dlp, then prove the interpreter answers.
     *
     * `YoutubeDL.init()` only unzips `libpython.zip.so` and copies the yt-dlp
     * script; it says nothing about whether Python can run. Waiting on that
     * proof without a deadline is how a device ends up stuck at
     * "initialising" with no error to report.
     */
    private suspend fun initPython(): Boolean {
        val payload = payloadProblem()
        if (payload != null) {
            initError = payload
            lastStep = "payload missing"
            Log.e(TAG, "yt-dlp payload problem: $payload")
            return false
        }

        lastStep = "unpacking Python"
        val unpack = withTimeoutOrNull(UNPACK_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().init(appContext) } }
        }
        if (unpack == null) {
            initError = "timed out after ${UNPACK_TIMEOUT_MS / 1000} s unpacking the bundled Python payload"
            lastStep = "unpack timed out"
            Log.e(TAG, initError!!)
            return false
        }
        unpack.exceptionOrNull()?.let { failure ->
            initError = "unpacking the bundled Python failed: ${describe(failure)}"
            lastStep = "unpack failed"
            Log.e(TAG, "yt-dlp unpack failed", failure)
            return false
        }

        lastStep = "probing Python"
        val probe = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = YoutubeDLRequest(emptyList()).apply { addOption("--version") }
                    YoutubeDL.getInstance().execute(request).out.trim()
                }
            }
        }
        if (probe == null) {
            initError = "bundled Python did not answer `yt-dlp --version` within " +
                "${PROBE_TIMEOUT_MS / 1000} s"
            lastStep = "probe timed out"
            Log.e(TAG, initError!!)
            return false
        }
        probe.exceptionOrNull()?.let { failure ->
            initError = "yt-dlp could not run: ${describe(failure)}"
            lastStep = "probe failed"
            Log.e(TAG, "yt-dlp probe failed", failure)
            return false
        }

        // The library only records a version after `updateYoutubeDL()`, so the
        // probe output is the authoritative one for a bundled install.
        cachedVersion = probe.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()
        initDone = true
        initError = null
        lastStep = "ready"
        Log.i(TAG, "yt-dlp ready: ${cachedVersion ?: "unknown version"}")
        return true
    }

    /**
     * youtubedl-android unzips `libpython.zip.so` out of nativeLibraryDir. When
     * that payload is missing (wrong packaging mode) init cannot succeed, and
     * naming the file beats a generic failure.
     */
    private fun payloadProblem(): String? {
        val nativeDir = runCatching { File(appContext.applicationInfo.nativeLibraryDir) }.getOrNull()
            ?: return "nativeLibraryDir unavailable"
        val python = File(nativeDir, "libpython.zip.so")
        if (!python.isFile || python.length() < 1_000_000) {
            val detail = if (python.isFile) "${python.length()} bytes" else "not found"
            return "bundled Python payload missing from the APK (libpython.zip.so: $detail)"
        }
        return null
    }

    /** One line of the failure plus the first frames: enough to act on. */
    private fun describe(t: Throwable): String {
        val head = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
        val frames = t.stackTrace.take(3).joinToString(" <- ") { frame ->
            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
        }
        return if (frames.isBlank()) head else "$head [$frames]"
    }

    /** Waits for init, running it if it never started or failed earlier. */
    suspend fun awaitReady() {
        if (!isReady) attemptInit()
        if (!isReady) throw YtDlpException(initError ?: "yt-dlp is not ready")
    }

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
        YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "ProDL.YtDlp"
        private const val UNPACK_TIMEOUT_MS = 180_000L
        private const val PROBE_TIMEOUT_MS = 30_000L
    }
}
