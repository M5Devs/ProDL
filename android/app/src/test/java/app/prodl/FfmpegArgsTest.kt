package app.prodl

import app.prodl.core.FfmpegArgs
import app.prodl.playback.SponsorSkip
import app.prodl.core.SponsorBlockSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FfmpegArgsTest {

    private val input = File("/data/prodl/input.mkv")
    private val output = File("/data/prodl/output.mp3")

    @Test
    fun `mp3 conversion strips video and sets a bitrate`() {
        val args = FfmpegArgs.build(input, output, "mp3", audioBitrate = "320k")
        assertTrue(args.containsAll(listOf("-vn", "-c:a", "libmp3lame", "-b:a", "320k")))
        assertEquals(output.absolutePath, args.last())
        assertTrue(args.contains("-progress"))
    }

    @Test
    fun `mp4 conversion uses h264 with faststart`() {
        val args = FfmpegArgs.build(input, File("/data/prodl/output.mp4"), "mp4", videoCrf = 28)
        assertTrue(args.containsAll(listOf("-c:v", "libx264", "-crf", "28", "-movflags", "+faststart")))
    }

    @Test
    fun `trimming seeks before input and limits duration`() {
        val args = FfmpegArgs.build(input, output, "m4a", start = 1.5, end = 10.0)
        val seekIndex = args.indexOf("-ss")
        val inputIndex = args.indexOf("-i")
        assertTrue(seekIndex in 0 until inputIndex)
        assertEquals("1.500", args[seekIndex + 1])
        assertEquals("8.500", args[args.indexOf("-t") + 1])
    }

    @Test
    fun `rejects unsupported formats and bad ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegArgs.build(input, output, "flv")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegArgs.build(input, output, "mp3", start = 10.0, end = 5.0)
        }
    }

    @Test
    fun `derives the output extension from the input stem`() {
        assertEquals("input.mp3", FfmpegArgs.outputName(input, "mp3"))
        assertEquals("/data/prodl/output.mp3", FfmpegArgs.build(input, output, "MP3").last())
    }

    @Test
    fun `parses ffmpeg progress timestamps`() {
        assertEquals(1.5, FfmpegArgs.parseOutTime("out_time=00:00:01.500000")!!, 0.001)
        assertEquals(3671.25, FfmpegArgs.parseOutTime("out_time=01:01:11.250000")!!, 0.001)
        assertNull(FfmpegArgs.parseOutTime("out_time=N/A"))
        assertNull(FfmpegArgs.parseOutTime("frame=42"))
    }
}

class SponsorSkipTest {

    private fun segment(start: Double, end: Double, action: String = "skip", uuid: String? = null) =
        SponsorBlockSegment(category = "sponsor", start = start, end = end, action = action, uuid = uuid)

    @Test
    fun `seeks to the end of a segment when inside it`() {
        val skipped = mutableSetOf<String>()
        val target = SponsorSkip.target(15_000, listOf(segment(10.0, 20.0)), skipped)
        assertEquals(20_000L, target)
        assertEquals(1, skipped.size)
    }

    @Test
    fun `does not re-skip an already handled segment`() {
        val skipped = mutableSetOf<String>()
        val segments = listOf(segment(10.0, 20.0))
        SponsorSkip.target(15_000, segments, skipped)
        assertNull(SponsorSkip.target(12_000, segments, skipped))
    }

    @Test
    fun `outside segments and marker actions are ignored`() {
        assertNull(SponsorSkip.target(5_000, listOf(segment(10.0, 20.0)), mutableSetOf()))
        assertNull(SponsorSkip.target(15_000, listOf(segment(10.0, 20.0, action = "poi")), mutableSetOf()))
        assertNull(SponsorSkip.target(15_000, emptyList(), mutableSetOf()))
    }

    @Test
    fun `uses the uuid when present so identical ranges stay distinct`() {
        val skipped = mutableSetOf<String>()
        val segments = listOf(segment(10.0, 20.0, uuid = "abc"))
        SponsorSkip.target(15_000, segments, skipped)
        assertTrue("abc" in skipped)
    }
}
