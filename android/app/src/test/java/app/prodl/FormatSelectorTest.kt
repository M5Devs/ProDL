package app.prodl

import app.prodl.core.DownloadRequest
import app.prodl.core.FormatSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FormatSelectorTest {

    private val workDir = File("/tmp/prodl-test")

    @Test
    fun `audio only uses the best audio selector`() {
        val args = FormatSelector.buildArguments(
            DownloadRequest(url = "https://example.com/v", audioOnly = true, audioFormat = "m4a", audioQuality = "128"),
            workDir,
            cookieFile = null,
            ffmpegPath = "/data/ffmpeg",
        )
        assertEquals("bestaudio/best", valueOf(args, "-f"))
        assertTrue(args.any { it.first == "-x" })
        assertEquals("m4a", valueOf(args, "--audio-format"))
        assertEquals("128", valueOf(args, "--audio-quality"))
        assertEquals("/data/ffmpeg", valueOf(args, "--ffmpeg-location"))
        assertFalse(args.any { it.first == "--merge-output-format" })
    }

    @Test
    fun `explicit format id wins over quality`() {
        val args = FormatSelector.buildArguments(
            DownloadRequest(url = "u", formatId = "137+140", videoQuality = "720p"),
            workDir,
            cookieFile = null,
            ffmpegPath = null,
        )
        assertEquals("137+140", valueOf(args, "-f"))
        assertEquals("mp4", valueOf(args, "--merge-output-format"))
    }

    @Test
    fun `quality string becomes a height cap`() {
        assertEquals("bestvideo[height<=1080]+bestaudio/best[height<=1080]", FormatSelector.heightSelector("1080p60"))
        assertEquals(FormatSelector.DEFAULT_VIDEO, FormatSelector.heightSelector("highest"))
    }

    @Test
    fun `cookies are only passed when a file exists`() {
        val cookieFile = File("/tmp/prodl-test/cookies.txt")
        val withCookies = FormatSelector.buildArguments(
            DownloadRequest(url = "u"), workDir, cookieFile, null,
        )
        val withoutCookies = FormatSelector.buildArguments(
            DownloadRequest(url = "u"), workDir, null, null,
        )
        assertEquals(cookieFile.absolutePath, valueOf(withCookies, "--cookies"))
        assertEquals(null, valueOf(withoutCookies, "--cookies"))
    }

    @Test
    fun `sponsorblock cut maps to remove and mark maps to mark`() {
        val cut = FormatSelector.buildArguments(
            DownloadRequest(url = "u", sponsorblock = true, sponsorblockAction = "cut"),
            workDir, null, null,
        )
        val mark = FormatSelector.buildArguments(
            DownloadRequest(url = "u", sponsorblock = true, sponsorblockAction = "mark"),
            workDir, null, null,
        )
        assertEquals("sponsor,intro,outro", valueOf(cut, "--sponsorblock-remove"))
        assertEquals("sponsor,intro,outro", valueOf(mark, "--sponsorblock-mark"))
    }

    @Test
    fun `concurrent fragments are clamped`() {
        assertEquals(1, FormatSelector.concurrentFragments(0))
        assertEquals(16, FormatSelector.concurrentFragments(99))
        assertEquals(4, FormatSelector.concurrentFragments(4))
    }

    @Test
    fun `subtitles and thumbnails add their flags`() {
        val args = FormatSelector.buildArguments(
            DownloadRequest(
                url = "u",
                subtitles = true,
                subtitleLangs = listOf("en", "de"),
                embedSubtitles = true,
                thumbnail = true,
            ),
            workDir, null, null,
        )
        assertTrue(args.any { it.first == "--write-subs" })
        assertEquals("en,de", valueOf(args, "--sub-langs"))
        assertTrue(args.any { it.first == "--write-auto-subs" })
        assertTrue(args.any { it.first == "--embed-subs" })
        assertTrue(args.any { it.first == "--write-thumbnail" })
    }

    private fun valueOf(args: List<Pair<String, String?>>, key: String): String? =
        args.lastOrNull { it.first == key }?.second
}
