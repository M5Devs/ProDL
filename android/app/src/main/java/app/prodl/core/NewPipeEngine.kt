package app.prodl.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Primary YouTube engine: NewPipeExtractor (GPL-3.0), the same library NewPipe
 * and LibreTube use. Pure Java — no Python, no native payload, so it is ready
 * as soon as the process starts no matter what the bundled yt-dlp is doing.
 *
 * YouTube only on purpose: yt-dlp still owns the 1000+ other sites, and keeping
 * one engine per job means a broken one never takes the other down.
 */
class NewPipeEngine(context: Context) {

    enum class State { IDLE, INITIALISING, READY, FAILED }

    private val appContext: Context = context.applicationContext ?: context

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Media transfers are large and slow: no call deadline, no read deadline. */
    private val downloadClient: OkHttpClient = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val started = AtomicBoolean(false)
    private val initLock = Any()

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var error: String? = null
        private set

    val isReady: Boolean get() = state == State.READY

    /**
     * Streams cached from `/api/info` so the download that follows does not
     * scrape the page a second time. Bounded and time limited: URLs expire.
     */
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 12
    }

    private class Cached(val media: NpMedia, val fetchedAt: Long)

    fun initialiseAsync(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { initialise() }
    }

    /** Idempotent and cheap: NewPipe.init only wires up service instances. */
    fun initialise() {
        if (state == State.READY) return
        // NewPipe.init writes static state, so concurrent retries (two requests
        // arriving while an init is in flight) must not interleave.
        synchronized(initLock) {
            if (state == State.READY) return
            state = State.INITIALISING
            try {
                NewPipe.init(NewPipeDownloader(client))
                // Touch the service now so a broken extractor surfaces here
                // rather than on the first user request.
                NewPipe.getService(ServiceList.YouTube.serviceId)
                state = State.READY
                error = null
                Log.i(TAG, "NewPipeExtractor ready")
            } catch (t: Throwable) {
                state = State.FAILED
                error = t.message ?: t::class.java.simpleName
                Log.e(TAG, "NewPipeExtractor init failed", t)
            }
        }
    }

    /** Re-run init after a failure (called when a request needs the engine again). */
    fun retryIfFailed() {
        if (state == State.FAILED) initialise()
    }

    /** True when the extractor recognises this URL as a YouTube video. */
    fun supports(url: String): Boolean {
        if (!isReady) return false
        if (!url.contains("youtu", ignoreCase = true)) return false
        return runCatching {
            val service = NewPipe.getServiceByUrl(url)
            service.serviceId == ServiceList.YouTube.serviceId
        }.getOrDefault(false)
    }

    suspend fun search(query: String, limit: Int): SearchResponse = withContext(Dispatchers.IO) {
        requireReady()
        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        val items = mutableListOf<StreamInfoItem>()
        var page = extractor.initialPage
        while (items.size < limit) {
            items += page.items.filterIsInstance<StreamInfoItem>()
            val next = page.nextPage
            if (next == null) break
            page = extractor.getPage(next)
        }
        val results = items.take(limit).map { item ->
            NewPipeMapper.searchItem(
                url = item.url,
                name = item.name,
                thumbnail = item.thumbnails.maxByOrNull { it.height }?.url,
                duration = item.duration.takeIf { it > 0 }?.toDouble(),
                uploader = item.uploaderName,
                viewCount = item.viewCount,
            )
        }
        SearchResponse(query = query, limit = limit, count = results.size, results = results)
    }

    suspend fun info(url: String): InfoResponse = withContext(Dispatchers.IO) {
        val media = media(url)
        NewPipeMapper.info(media)
    }

    /** Extract + flatten one video; cached briefly for the download that follows. */
    suspend fun media(url: String): NpMedia = withContext(Dispatchers.IO) {
        requireReady()
        cached(url)?.let { return@withContext it }

        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()

        val thumbnails = runCatching { extractor.thumbnails }.getOrNull().orEmpty()
        val description = runCatching { extractor.description.content }.getOrNull()
        val media = NpMedia(
            id = runCatching { extractor.id }.getOrNull() ?: NewPipeMapper.videoIdOf(url),
            title = runCatching { extractor.name }.getOrNull() ?: "Untitled",
            webpageUrl = url,
            durationSeconds = runCatching { extractor.length }.getOrNull()?.takeIf { it > 0 }?.toDouble(),
            thumbnail = thumbnails.maxByOrNull { it.height }?.url,
            uploader = runCatching { extractor.uploaderName }.getOrNull(),
            viewCount = runCatching { extractor.viewCount }.getOrNull()?.takeIf { it >= 0 },
            likeCount = runCatching { extractor.likeCount }.getOrNull()?.takeIf { it >= 0 },
            uploadDate = runCatching { extractor.textualUploadDate }.getOrNull(),
            description = description?.take(4000),
            isLive = runCatching { extractor.streamType }.getOrNull()
                ?.let { it == StreamType.LIVE_STREAM || it == StreamType.AUDIO_LIVE_STREAM } ?: false,
            ageLimit = runCatching { extractor.ageLimit }.getOrNull() ?: 0,
            muxed = runCatching { extractor.videoStreams }.getOrNull().orEmpty()
                .mapNotNull(::videoOf),
            videoOnly = runCatching { extractor.videoOnlyStreams }.getOrNull().orEmpty()
                .mapNotNull(::videoOf),
            audio = runCatching { extractor.audioStreams }.getOrNull().orEmpty()
                .mapNotNull(::audioOf),
            subtitles = runCatching { extractor.subtitlesDefault }.getOrNull().orEmpty()
                .map(::subtitleOf).distinctBy { it.lang },
        )
        if (media.muxed.isEmpty() && media.videoOnly.isEmpty() && media.audio.isEmpty()) {
            throw YtDlpException("NewPipeExtractor found no playable streams for this video")
        }
        synchronized(cacheLock) { cache[url] = Cached(media, System.currentTimeMillis()) }
        media
    }

    /**
     * Download one stream to [dest] with byte progress. Returns the number of
     * bytes written. Cancellation closes the HTTP call, not just the coroutine.
     */
    suspend fun fetch(
        stream: NpStream,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val request = HttpRequest.Builder()
            .url(stream.url)
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Referer", "https://www.youtube.com/")
            .build()
        val call = downloadClient.newCall(request)

        suspendCancellableCoroutine<Long> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            continuation.resumeWithException(
                                YtDlpException("Media server answered HTTP ${response.code} for this stream")
                            )
                            return@suspendCancellableCoroutine
                        }
                        val body = response.body
                            ?: throw YtDlpException("Empty response body from the media server")
                        val total = body.contentLength().takeIf { it > 0 } ?: 0L
                        var written = 0L
                        var lastReport = 0L
                        dest.outputStream().buffered().use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    if (continuation.isCancelled) throw CancellationException("canceled")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    written += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport >= 400) {
                                        lastReport = now
                                        onProgress(written, total)
                                    }
                                }
                            }
                        }
                        onProgress(written, total)
                        continuation.resume(written)
                    }
            } catch (t: Throwable) {
                if (!continuation.isCompleted) continuation.resumeWithException(t)
            }
        }
    }

    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    private fun cached(url: String): NpMedia? = synchronized(cacheLock) {
        val entry = cache[url] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAt > CACHE_TTL_MS) {
            cache.remove(url)
            return null
        }
        entry.media
    }

    private fun requireReady() {
        if (state == State.FAILED) initialise()
        if (state != State.READY) {
            throw YtDlpException(error ?: "NewPipeExtractor is not ready yet")
        }
    }

    // `url`/`isVideoOnly`/`resolution` are deprecated in favour of the
    // multi-manifest API, but they are still the only direct stream URLs the
    // YouTube extractor fills in, and Media3 plays them as-is.
    @Suppress("DEPRECATION")
    private fun videoOf(stream: VideoStream): NpStream? {
        val url = stream.url?.takeIf { it.isNotBlank() } ?: return null
        return NpStream(
            itag = stream.itag,
            url = url,
            ext = stream.format?.suffix ?: "mp4",
            mimeType = stream.format?.mimeType,
            codec = stream.codec,
            videoOnly = stream.isVideoOnly,
            height = stream.height.takeIf { it > 0 },
            resolution = stream.resolution?.takeIf { it.isNotBlank() && it != VideoStream.RESOLUTION_UNKNOWN },
            fps = stream.fps.takeIf { it > 0 },
            bitrateKbps = stream.bitrate.takeIf { it > 0 }?.div(1000),
        )
    }

    @Suppress("DEPRECATION")
    private fun audioOf(stream: AudioStream): NpStream? {
        val url = stream.url?.takeIf { it.isNotBlank() } ?: return null
        return NpStream(
            itag = stream.itag,
            url = url,
            ext = stream.format?.suffix ?: "m4a",
            mimeType = stream.format?.mimeType,
            codec = stream.codec,
            videoOnly = false,
            bitrateKbps = (stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate)
                .takeIf { it > 0 }?.div(1000),
        )
    }

    private fun subtitleOf(stream: SubtitlesStream): SubtitleDto = SubtitleDto(
        lang = stream.languageTag.ifBlank { stream.locale?.toLanguageTag() ?: "und" }
            .lowercase(Locale.US),
        langName = stream.displayLanguageName ?: stream.locale?.displayLanguage,
        isAuto = stream.isAutoGenerated,
        ext = stream.extension?.lowercase(Locale.US) ?: "vtt",
    )

    companion object {
        private const val TAG = "ProDL.NewPipe"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
