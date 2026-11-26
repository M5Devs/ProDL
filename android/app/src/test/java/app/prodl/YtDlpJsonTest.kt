package app.prodl

import app.prodl.core.AppJson
import app.prodl.core.YtDlpJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJsonTest {

    private fun json(text: String): JsonObject = AppJson.parseToJsonElement(text) as JsonObject

    private val video = json(
        """
        {
          "id": "dQw4w9WgXcQ",
          "title": "Sample clip",
          "description": "hello",
          "duration": 213.0,
          "uploader": "Uploader",
          "channel": "Channel",
          "view_count": 1000,
          "like_count": 42,
          "upload_date": "20091025",
          "age_limit": 0,
          "is_live": false,
          "webpage_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
          "extractor": "youtube",
          "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
          "formats": [
            {"format_id": "140", "ext": "m4a", "acodec": "mp4a.40.2", "vcodec": "none",
             "abr": 129.5, "filesize": 3456789, "url": "https://media.example/140",
             "protocol": "https", "resolution": "audio only", "format_note": "medium"},
            {"format_id": "137", "ext": "mp4", "vcodec": "avc1.640028", "acodec": "none",
             "height": 1080, "width": 1920, "fps": 30, "tbr": 4200.5,
             "filesize_approx": 98765432, "url": "https://media.example/137", "protocol": "https"},
            {"format_id": "22", "ext": "mp4", "vcodec": "avc1.64001F", "acodec": "mp4a.40.2",
             "height": 720, "width": 1280, "fps": 30, "url": "https://media.example/22", "protocol": "https"}
          ],
          "subtitles": {"en": [{"ext": "vtt", "name": "English", "url": "https://x/en.vtt"}]},
          "automatic_captions": {
            "en": [{"ext": "vtt", "name": "English (auto)"}],
            "de": [{"ext": "vtt", "name": "German (auto)"}]
          }
        }
        """.trimIndent()
    )

    @Test
    fun `maps metadata onto the v1 schema`() {
        val info = YtDlpJson.info("https://www.youtube.com/watch?v=dQw4w9WgXcQ", video)
        assertEquals("Sample clip", info.title)
        assertEquals(213.0, info.duration!!, 0.001)
        assertEquals(1000L, info.viewCount)
        assertEquals(42L, info.likeCount)
        assertEquals("Channel", info.channel)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", info.webpageUrl)
        assertEquals("youtube", info.extractor)
    }

    @Test
    fun `classifies formats by codec`() {
        val info = YtDlpJson.info("u", video)
        val audio = info.formats.first { it.formatId == "140" }
        val videoOnly = info.formats.first { it.formatId == "137" }
        val muxed = info.formats.first { it.formatId == "22" }

        assertTrue(audio.hasAudio)
        assertTrue(!audio.hasVideo)
        assertEquals("audio only", audio.resolution)
        assertTrue(videoOnly.hasVideo)
        assertTrue(!videoOnly.hasAudio)
        assertEquals(1080, videoOnly.height)
        assertEquals(98765432L, videoOnly.filesize)
        assertTrue(muxed.hasVideo && muxed.hasAudio)
    }

    @Test
    fun `picks the highest video and best audio direct urls`() {
        val info = YtDlpJson.info("u", video)
        assertEquals("https://media.example/137", info.bestVideoUrl)
        assertEquals("https://media.example/140", info.bestAudioUrl)
    }

    @Test
    fun `subtitles are ordered manual first and de-duplicated`() {
        val info = YtDlpJson.info("u", video)
        assertEquals(listOf("en", "de"), info.subtitles.map { it.lang })
        assertEquals(false, info.subtitles.first().isAuto)
        assertEquals(true, info.subtitles.last().isAuto)
        assertEquals("English", info.subtitles.first().langName)
    }

    @Test
    fun `direct media links are synthesised into a format entry`() {
        val direct = json(
            """
            {"id": "abc", "title": "clip.mp4", "ext": "mp4", "url": "https://cdn.example/clip.mp4",
             "protocol": "https", "width": 640, "height": 360, "filesize": 12345}
            """.trimIndent()
        )
        val info = YtDlpJson.info("https://cdn.example/clip.mp4", direct)
        assertEquals(1, info.formats.size)
        assertTrue(info.formats.single().hasVideo)
        assertEquals("https://cdn.example/clip.mp4", info.bestVideoUrl)
        assertEquals("640x360", info.formats.single().resolution)
    }

    @Test
    fun `search results are mapped from entries`() {
        val results = YtDlpJson.search(
            "lofi",
            2,
            json(
                """
                {"entries": [
                  {"id": "a", "title": "A", "url": "https://youtu.be/a", "duration": 10.5,
                   "uploader": "u", "view_count": 5, "live_status": "not_live", "ie_key": "Youtube"},
                  {"id": "b", "title": "B", "url": "https://youtu.be/b", "live_status": "is_live"}
                ]}
                """.trimIndent()
            ),
        )
        assertEquals(2, results.count)
        assertEquals("lofi", results.query)
        assertEquals("A", results.results[0].title)
        assertEquals(10.5, results.results[0].duration!!, 0.001)
        assertTrue(results.results[1].isLive)
        assertNull(results.results[0].thumbnail)
    }

    @Test
    fun `missing optional fields never throw`() {
        val info = YtDlpJson.info("u", json("""{"id": "x"}"""))
        assertEquals("Untitled", info.title)
        assertEquals("u", info.webpageUrl)
        assertTrue(info.formats.isEmpty())
    }
}
