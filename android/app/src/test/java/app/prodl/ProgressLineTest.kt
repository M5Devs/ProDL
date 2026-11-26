package app.prodl

import app.prodl.core.ProgressLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressLineTest {

    @Test
    fun `parses a download progress line with speed and eta`() {
        val sample = ProgressLine.parse(
            "[download]  12.3% of ~ 10.00MiB at  1.20MiB/s ETA 00:05 (frag 3/10)"
        )
        assertNotNull(sample)
        assertEquals(12.3, sample!!.percent!!, 0.001)
        assertEquals(10L * 1024 * 1024, sample.totalBytes)
        assertEquals(12.3 / 100.0 * 10 * 1024 * 1024, sample.downloadedBytes!!.toDouble(), 1.0)
        assertEquals(1.2 * 1024 * 1024, sample.speed!!, 1.0)
        assertEquals(5L, sample.eta)
    }

    @Test
    fun `handles unknown speed and eta`() {
        val sample = ProgressLine.parse("[download]  44.0% of 3.50MiB at Unknown speed ETA Unknown")
        assertEquals(44.0, sample!!.percent!!, 0.001)
        assertNull(sample.speed)
        assertEquals(0L, sample.eta)
    }

    @Test
    fun `reports completion as postprocessing candidate`() {
        val sample = ProgressLine.parse("[download] 100% of 10.00MiB in 00:08")
        assertEquals(100.0, sample!!.percent!!, 0.001)
        assertEquals("Download finished", sample.message)
    }

    @Test
    fun `extracts destination filenames`() {
        assertEquals(
            "clip.f137.mp4",
            ProgressLine.parse("[download] Destination: clip.f137.mp4")!!.filename,
        )
        assertEquals(
            "clip.mkv",
            ProgressLine.parse("[Merger] Merging formats into \"clip.mkv\"")!!.filename,
        )
        assertEquals(
            "clip.mp3",
            ProgressLine.parse("[ExtractAudio] Destination: clip.mp3")!!.filename,
        )
    }

    @Test
    fun `ignores unrelated lines`() {
        assertNull(ProgressLine.parse(""))
        assertNull(ProgressLine.parse("[youtube] Extracting URL: https://example.com/v"))
    }

    @Test
    fun `formats human readable speeds`() {
        assertEquals("0B/s", ProgressLine.formatSpeed(0.0))
        assertEquals("512B/s", ProgressLine.formatSpeed(512.0))
        assertEquals("1.00KiB/s", ProgressLine.formatSpeed(1024.0))
        assertEquals("1.50MiB/s", ProgressLine.formatSpeed(1.5 * 1024 * 1024))
    }

    @Test
    fun `parses sizes out of arbitrary text`() {
        assertEquals(1024L, ProgressLine.parseSize("1.00KiB"))
        assertTrue(ProgressLine.parseSize("3.25GiB")!! > 3L * 1024 * 1024 * 1024)
    }
}
