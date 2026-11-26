package app.prodl

import app.prodl.core.DownloadRequest
import app.prodl.core.FormatSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Android build has no ffmpeg, so yt-dlp is never asked to merge tracks,
 * extract audio or embed anything: those arguments would all fail at the last
 * step of a long download.
 */
class FormatSelectorTest {

    private val workDir = File("/tmp/prodl-test")

    private fun args(request: DownloadRequest, cookie: File? = null) =
        FormatSelector.buildArguments(request, workDir, cookie)

    private fun valueOf(args: List<Pair<String, String?>>, key: String) =
        args.firstOrNull { it.first == key }?.second

    @Test
    fun `audio only asks for one audio file and no ffmpeg post-processing`() {
        val parsed = args(
            DownloadRequest(url = "https://example.com/v", audioOnly = true, audioFormat = "m4a", audioQuality = "128"),
        )
        assertEquals("bestaudio[ext=m4a]/bestaudio/best", valueOf(parsed, "-f"))
        assertFalse(parsed.any { it.first == "-x" })
        assertFalse(parsed.any { it.first == "--audio-format" })
        assertFalse(parsed.any { it.first == "--audio-quality" })
        assertFalse(parsed.any { it.first == "--ffmpeg-location" })
        assertFalse(parsed.any { it.first == "--merge-output-format" })
    }

    @Test
    fun `default video is a single progressive file`() {
        val parsed = args(DownloadRequest(url = "u"))
        assertEquals("best[ext=mp4]/best", valueOf(parsed, "-f"))
        assertFalse("no + merge without ffmpeg", valueOf(parsed, "-f")!!.contains("+"))
        assertFalse(parsed.any { it.first == "--merge-output-format" })
    }

    @Test
    fun `explicit format id is passed through untouched`() {
        val parsed = args(DownloadRequest(url = "u", formatId = "137", videoQuality = "720p"))
        assertEquals("137", valueOf(parsed, "-f"))
    }

    @Test
    fun `quality becomes a height cap on a single file`() {
        assertEquals(
            "best[height<=720][ext=mp4]/best[height<=720]/best",
            FormatSelector.heightSelector("720p"),
        )
        assertEquals(
            "best[height<=1080][ext=mp4]/best[height<=1080]/best",
            FormatSelector.heightSelector("1080p60"),
        )
        assertEquals(FormatSelector.DEFAULT_VIDEO, FormatSelector.heightSelector("auto"))
    }

    @Test
    fun `subtitles and thumbnails are sidecar files, never embedded`() {
        val parsed = args(
            DownloadRequest(
                url = "u",
                subtitles = true,
                subtitleLangs = listOf("en", "de"),
                embedSubtitles = true,
                thumbnail = true,
                embedThumbnail = true,
            ),
        )
        assertEquals("en,de", valueOf(parsed, "--sub-langs"))
        assertTrue(parsed.any { it.first == "--write-subs" })
        assertTrue(parsed.any { it.first == "--write-thumbnail" })
        assertFalse(parsed.any { it.first == "--embed-subs" })
        assertFalse(parsed.any { it.first == "--embed-thumbnail" })
    }

    @Test
    fun `sponsorblock is only ever marked, never cut`() {
        val cut = args(
            DownloadRequest(
                url = "u",
                sponsorblock = true,
                sponsorblockAction = "cut",
                sponsorblockCategories = listOf("Sponsor", "intro"),
            ),
        )
        assertEquals("sponsor,intro", valueOf(cut, "--sponsorblock-mark"))
        assertFalse(cut.any { it.first == "--sponsorblock-remove" })
        assertEquals("mark", FormatSelector.sponsorBlockAction("cut"))
        assertEquals("mark", FormatSelector.sponsorBlockAction("bogus"))
    }

    @Test
    fun `cookies and limits survive`() {
        val cookie = File("/tmp/cookies.txt")
        val parsed = args(DownloadRequest(url = "u", maxFilesize = "50M"), cookie)
        assertEquals("/tmp/cookies.txt", valueOf(parsed, "--cookies"))
        assertEquals("50M", valueOf(parsed, "--max-filesize"))
        assertNull(valueOf(parsed, "--playlist"))
    }

    @Test
    fun `audio format falls back to mp3 for formats the phone cannot write`() {
        assertEquals("mp3", FormatSelector.audioFormat("opus"))
        assertEquals("mp3", FormatSelector.audioFormat("flac"))
        assertEquals("m4a", FormatSelector.audioFormat("m4a"))
    }
}
