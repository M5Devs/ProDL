package app.prodl

import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import app.prodl.core.NewPipeDownloader
import org.junit.Assert.assertTrue

/**
 * Network smoke test for the extractor APIs the Compose UI relies on. Skipped
 * unless `PRODL_LIVE=1`, so CI stays hermetic:
 *
 * ```
 * PRODL_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*NewPipeApiTest'
 * ```
 *
 * It mirrors `NewPipeEngine`'s calls (search, kiosk ids, the channel "videos"
 * tab, playlist paging) because the engine itself needs an Android Context for
 * the cookie jar, which a JVM test cannot provide.
 */
class NewPipeApiTest {

    private val service = ServiceList.YouTube

    private fun initExtractor() {
        NewPipe.init(NewPipeDownloader(OkHttpClient()))
    }

    private fun StreamInfoItem.describe() = "$name ($url) [${duration}s]"

    @Test
    fun searchReturnsPlayableItems() {
        assumeTrue(System.getenv("PRODL_LIVE") == "1")
        initExtractor()
        val extractor = service.getSearchExtractor("lofi hip hop")
        extractor.fetchPage()
        val items = extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
        println("search: " + items.take(5).joinToString { it.describe() })
        assertTrue("search returned no stream items", items.isNotEmpty())
        assertTrue("item url should be absolute", items.first().url.startsWith("http"))
    }

    @Test
    fun everyKioskIdInTheUiResolves() {
        assumeTrue(System.getenv("PRODL_LIVE") == "1")
        initExtractor()
        // Exactly what NewPipeEngine.kiosks() offers the Discover chips.
        val ids = listOf("live", "trending_gaming", "trending_podcasts_episodes")
        println("availableKiosks=" + service.kioskList.availableKiosks)
        val failures = mutableListOf<String>()
        for (id in ids) {
            val result = runCatching {
                val extractor = service.kioskList.getExtractorById(id, null)
                extractor.fetchPage()
                val count = extractor.initialPage.items.size
                val types = extractor.initialPage.items.map { it.infoType }.distinct()
                val name = runCatching { extractor.name }.getOrElse { "?" }
                "name=$name items=$count types=$types"
            }
            println("kiosk $id -> ${result.getOrNull() ?: result.exceptionOrNull()}")
            if (result.isFailure) failures += "$id: ${result.exceptionOrNull()}"
        }
        assertTrue("kiosk failures: $failures", failures.isEmpty())
    }

    @Test
    fun trendingKioskWithMobileUserAgent() {
        assumeTrue(System.getenv("PRODL_LIVE") == "1")
        val mobile = object : org.schabi.newpipe.extractor.downloader.Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request):
                org.schabi.newpipe.extractor.downloader.Response {
                val builder = okhttp3.Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), request.dataToSend()?.let {
                        okhttp3.RequestBody.create(null, it)
                    })
                request.headers().forEach { (name, values) ->
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                )
                OkHttpClient().newCall(builder.build()).execute().use { response ->
                    return org.schabi.newpipe.extractor.downloader.Response(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        response.body?.string() ?: "",
                        response.request.url.toString(),
                    )
                }
            }
        }
        NewPipe.init(mobile)
        val result = runCatching {
            val extractor = service.kioskList.getExtractorById("Trending", null)
            extractor.fetchPage()
            val count = extractor.initialPage.items.size
            val name = runCatching { extractor.name }.getOrElse { "?(${it.message})" }
            "name=$name items=$count types=" +
                extractor.initialPage.items.map { it.infoType }.distinct()
        }
        println("Trending(mobile UA) -> ${result.getOrNull() ?: result.exceptionOrNull()}")
    }

    @Test
    fun watchPageExposesPlayableStreams() {
        assumeTrue(System.getenv("PRODL_LIVE") == "1")
        initExtractor()
        val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
            service,
            "https://www.youtube.com/watch?v=Jatwf_XOncE",
        )
        println(
            "stream ${info.name}: video=${info.videoStreams.size} " +
                "videoOnly=${info.videoOnlyStreams.size} audio=${info.audioStreams.size} " +
                "subtitles=${info.subtitles.size} hls=${info.hlsUrl != null} dash=${info.dashMpdUrl != null}",
        )
        // YouTube usually returns no progressive stream: video-only plus audio
        // plus a manifest is the normal shape, and that is what the app needs.
        assertTrue(
            "no video streams at all",
            info.videoStreams.isNotEmpty() || info.videoOnlyStreams.isNotEmpty(),
        )
        assertTrue("no audio streams", info.audioStreams.isNotEmpty())
        assertTrue("no HLS/DASH manifest", info.hlsUrl != null || info.dashMpdUrl != null)
        val playable = (info.videoStreams + info.videoOnlyStreams)
            .firstOrNull { !it.content.isNullOrBlank() }
        assertTrue("no direct stream url", playable != null)
    }

    @Test
    fun channelVideosTabAndPlaylistPage() {
        assumeTrue(System.getenv("PRODL_LIVE") == "1")
        initExtractor()

        val channel = service.getChannelExtractor("https://www.youtube.com/@Fireship")
        channel.fetchPage()
        val tabs = channel.tabs
        val videosTab = tabs.firstOrNull {
            it.contentFilters.firstOrNull()?.equals("videos", ignoreCase = true) == true
        } ?: tabs.first()
        val tabExtractor = service.getChannelTabExtractor(videosTab)
        tabExtractor.fetchPage()
        val uploads = tabExtractor.initialPage.items
        println("channel ${channel.name}: ${uploads.size} uploads, first=${uploads.firstOrNull()}")
        assertTrue("channel videos tab returned nothing", uploads.isNotEmpty())

        // Take a playlist straight off the channel so the test cannot rot with
        // a hardcoded id that YouTube eventually deletes.
        val playlistsTab = tabs.firstOrNull {
            it.contentFilters.firstOrNull()?.equals("playlists", ignoreCase = true) == true
        }
        assumeTrue("channel has no playlists tab", playlistsTab != null)
        val playlistsExtractor = service.getChannelTabExtractor(playlistsTab!!)
        playlistsExtractor.fetchPage()
        val playlistUrl = playlistsExtractor.initialPage.items
            .filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
            .firstOrNull()?.url
        assumeTrue("channel lists no playlists", playlistUrl != null)

        val playlist = service.getPlaylistExtractor(playlistUrl!!)
        playlist.fetchPage()
        println("playlist ${playlist.name}: ${playlist.initialPage.items.size} items")
        assertTrue("playlist returned nothing", playlist.initialPage.items.isNotEmpty())
    }
}
