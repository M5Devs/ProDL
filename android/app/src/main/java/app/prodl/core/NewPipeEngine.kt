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
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
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

    /**
     * Search with a YouTube-style content filter.
     *
     * The filter is part of the query handler in NewPipeExtractor (it maps to
     * YouTube's `sp=` search parameter), so it has to be applied before
     * `fetchPage()`: filtering afterwards would only see the mixed page.
     */
    suspend fun search(query: String, limit: Int, type: String = "all"): SearchResponse =
        withContext(Dispatchers.IO) {
            requireReady()
            val filter = contentFilter(type)
            val service = NewPipe.getService(ServiceList.YouTube.serviceId)
            val extractor = service.getSearchExtractor(query, listOf(filter), "")
            extractor.fetchPage()
            val items = mutableListOf<InfoItem>()
            var page = extractor.initialPage
            while (items.size < limit) {
                items += page.items
                val next = page.nextPage
                if (next == null) break
                page = extractor.getPage(next)
            }
            val results = items.take(limit).mapNotNull(::toSearchItem)
            SearchResponse(
                query = query,
                limit = limit,
                count = results.size,
                results = results,
                type = filter,
            )
        }

    /** `all|videos|channels|playlists` → a NewPipeExtractor content filter. */
    private fun contentFilter(type: String?): String = when (type?.trim()?.lowercase(Locale.US)) {
        "videos", "video" -> "videos"
        "channels", "channel" -> "channels"
        "playlists", "playlist" -> "playlists"
        else -> "all"
    }

    /**
     * Trending / Discover feeds. YouTube removed the plain "Trending" shelf in
     * July 2025, so the extractor exposes several kiosks (`live`,
     * `trending_music`, `trending_gaming`, `trending_movies_and_shows`,
     * `trending_podcasts_episodes`, plus the deprecated `Trending`). The UI asks
     * for one of these ids; [kiosks] reports which the library actually has.
     */
    suspend fun kiosk(id: String, limit: Int): List<SearchItem> = withContext(Dispatchers.IO) {
        requireReady()
        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val kioskList = service.kioskList
        if (id !in kioskList.availableKiosks) {
            throw YtDlpException("This feed ($id) is not available from YouTube right now")
        }
        val extractor = kioskList.getExtractorById(id, null)
        extractor.fetchPage()
        collect(extractor, limit)
    }

    /** Kiosk ids the extractor knows, in the order the Discover chips show them. */
    fun kiosks(): List<Kiosk> = runCatching {
        val available = NewPipe.getService(ServiceList.YouTube.serviceId)
            .kioskList.availableKiosks
        KIOSKS.filter { it.id in available }
    }.getOrDefault(KIOSKS)

    data class Kiosk(val id: String, val label: String)

    /**
     * Channel uploads, with the header the channel screen renders. The channel
     * extractor itself only carries metadata in v0.27 — the videos live behind
     * one of its tabs, so the "videos" tab is opened explicitly.
     */
    suspend fun channel(url: String, limit: Int): BrowseResult = withContext(Dispatchers.IO) {
        requireReady()
        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val extractor = service.getChannelExtractor(url)
        extractor.fetchPage()
        val tabs = runCatching { extractor.tabs }.getOrNull().orEmpty()
        val videosTab = tabs.firstOrNull {
            it.contentFilters.firstOrNull()?.equals("videos", ignoreCase = true) == true
        } ?: tabs.firstOrNull()
        val items = if (videosTab == null) {
            emptyList()
        } else {
            val tabExtractor = service.getChannelTabExtractor(videosTab)
            tabExtractor.fetchPage()
            collect(tabExtractor, limit)
        }
        BrowseResult(
            url = url,
            kind = "channel",
            title = runCatching { extractor.name }.getOrNull() ?: "Channel",
            subtitle = runCatching { extractor.subscriberCount }
                .getOrNull()?.takeIf { it >= 0 }?.let { "${formatCount(it)} subscribers" },
            banner = runCatching { extractor.banners }.getOrNull()?.best(),
            avatar = runCatching { extractor.avatars }.getOrNull()?.best(),
            description = runCatching { extractor.description }.getOrNull()?.takeIf { it.isNotBlank() },
            items = items,
        )
    }

    /** Playlist videos, with the header the playlist screen renders. */
    suspend fun playlist(url: String, limit: Int): BrowseResult = withContext(Dispatchers.IO) {
        requireReady()
        val service = NewPipe.getService(ServiceList.YouTube.serviceId)
        val extractor = service.getPlaylistExtractor(url)
        extractor.fetchPage()
        BrowseResult(
            url = url,
            kind = "playlist",
            title = runCatching { extractor.name }.getOrNull() ?: "Playlist",
            subtitle = listOfNotNull(
                runCatching { extractor.uploaderName }.getOrNull()?.takeIf { it.isNotBlank() },
                runCatching { extractor.streamCount }.getOrNull()
                    ?.takeIf { it >= 0 }?.let { "${formatCount(it)} videos" },
            ).joinToString(" · ").ifBlank { null },
            banner = runCatching { extractor.banners }.getOrNull()?.best(),
            avatar = runCatching { extractor.thumbnails }.getOrNull()?.best(),
            description = runCatching { extractor.description.content }.getOrNull()?.takeIf { it.isNotBlank() },
            items = collect(extractor, limit),
        )
    }

    /** Walk the extractor's pages until [limit] items or the end of the list. */
    private fun collect(extractor: ListExtractor<*>, limit: Int): List<SearchItem> {
        val items = mutableListOf<InfoItem>()
        var page: ListExtractor.InfoItemsPage<*> = extractor.initialPage
        while (items.size < limit) {
            items += page.items.filterIsInstance<InfoItem>()
            val next = page.nextPage ?: break
            page = extractor.getPage(next)
        }
        return items.take(limit).mapNotNull(::toSearchItem)
    }

    private fun toSearchItem(item: InfoItem): SearchItem? = when (item) {
        is StreamInfoItem -> NewPipeMapper.searchItem(
            url = item.url,
            name = item.name,
            thumbnail = item.thumbnails.maxByOrNull { it.height }?.url,
            duration = item.duration.takeIf { it > 0 }?.toDouble(),
            uploader = item.uploaderName,
            viewCount = item.viewCount,
        )

        is ChannelInfoItem -> NewPipeMapper.searchItem(
            url = item.url,
            name = item.name,
            thumbnail = item.thumbnails.maxByOrNull { it.height }?.url,
            duration = null,
            uploader = item.name,
            viewCount = null,
            kind = "channel",
            itemCount = item.subscriberCount.takeIf { it >= 0 },
        )

        is PlaylistInfoItem -> NewPipeMapper.searchItem(
            url = item.url,
            name = item.name,
            thumbnail = item.thumbnails.maxByOrNull { it.height }?.url,
            duration = null,
            uploader = item.uploaderName,
            viewCount = null,
            kind = "playlist",
            itemCount = item.streamCount.takeIf { it >= 0 },
        )

        else -> null
    }

    private fun List<Image>.best(): String? = maxByOrNull { it.height }?.url

    /** `1234567` → `1.2M`, for subscriber and video counts. */
    private fun formatCount(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
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
            // YouTube already returns these with the watch page: no second request.
            related = runCatching { extractor.relatedItems?.items }.getOrNull().orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .take(RELATED_LIMIT)
                .map { item ->
                    NewPipeMapper.relatedItem(
                        url = item.url,
                        name = item.name,
                        thumbnail = item.thumbnails.maxByOrNull { it.height }?.url,
                        duration = item.duration.takeIf { it > 0 }?.toDouble(),
                        uploader = item.uploaderName,
                    )
                },
            hlsUrl = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() },
            dashMpdUrl = runCatching { extractor.dashMpdUrl }.getOrNull()?.takeIf { it.isNotBlank() },
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
        /**
         * Order matters: `Trending` is what users expect first, but YouTube
         * retired it, so [kiosks] drops it when the extractor does not offer it
         * and the UI falls back to the next entry.
         */
        // Only feeds that actually return items on extractor 0.27.1: the plain
        // "Trending" kiosk throws (YouTube dropped the Now/Videos tabs it parses)
        // and the music/movies kiosks come back empty, so offering them would
        // just show an error or a blank screen.
        private val KIOSKS = listOf(
            Kiosk("live", "Live"),
            Kiosk("trending_gaming", "Gaming"),
            Kiosk("trending_podcasts_episodes", "Podcasts"),
        )

        private const val TAG = "ProDL.NewPipe"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val RELATED_LIMIT = 12
    }
}
