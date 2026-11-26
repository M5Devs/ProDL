package app.prodl

import app.prodl.core.MediaTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mux/transcode decisions that used to be ffmpeg argument guesses. */
class MediaTargetsTest {

    @Test
    fun `avc plus aac is a straight copy into mp4`() {
        assertEquals(
            MediaTargets.MuxPlan.COPY_MP4,
            MediaTargets.muxPlan("video/avc", "audio/mp4a-latm"),
        )
        assertEquals(MediaTargets.Container.MP4, MediaTargets.containerOf(MediaTargets.MuxPlan.COPY_MP4))
    }

    @Test
    fun `vp9 plus opus is a straight copy into webm`() {
        assertEquals(
            MediaTargets.MuxPlan.COPY_WEBM,
            MediaTargets.muxPlan("video/x-vnd.on2.vp9", "audio/opus"),
        )
        assertEquals(MediaTargets.Container.WEBM, MediaTargets.containerOf(MediaTargets.MuxPlan.COPY_WEBM))
    }

    @Test
    fun `mp4 video with opus audio transcodes only the audio`() {
        assertEquals(
            MediaTargets.MuxPlan.AUDIO_TO_AAC_THEN_MP4,
            MediaTargets.muxPlan("video/avc", "audio/opus"),
        )
    }

    @Test
    fun `vp9 video with aac audio is still a plain mp4 copy`() {
        // VP9 is legal in MP4, so this pair never needs a re-encode.
        assertEquals(
            MediaTargets.MuxPlan.COPY_MP4,
            MediaTargets.muxPlan("video/x-vnd.on2.vp9", "audio/mp4a-latm"),
        )
        // An mpeg4-visual video with Opus audio keeps the video and re-encodes
        // only the audio: MP4 cannot hold Opus.
        assertEquals(
            MediaTargets.MuxPlan.AUDIO_TO_AAC_THEN_MP4,
            MediaTargets.muxPlan("video/mp4v-es", "audio/opus"),
        )
        // VP8 is not legal in MP4, so this pair needs a full re-encode.
        assertEquals(
            MediaTargets.MuxPlan.REENCODE_TO_MP4,
            MediaTargets.muxPlan("video/x-vnd.on2.vp8", "audio/mp4a-latm"),
        )
    }

    @Test
    fun `a missing track is never a copy`() {
        assertEquals(MediaTargets.MuxPlan.UNSUPPORTED, MediaTargets.muxPlan("video/avc", null))
        assertEquals(MediaTargets.MuxPlan.UNSUPPORTED, MediaTargets.muxPlan(null, "audio/mp4a-latm"))
    }

    @Test
    fun `resolutions read like a quality picker`() {
        assertEquals("1080p", MediaTargets.resolutionLabel(1080, 30))
        assertEquals("1080p60", MediaTargets.resolutionLabel(1080, 60))
        assertEquals("720p50", MediaTargets.resolutionLabel(720, 50))
        assertEquals("240p", MediaTargets.resolutionLabel(240, null))
        assertEquals("144p", MediaTargets.resolutionLabel(144, 15))
        assertEquals("1080p48", MediaTargets.resolutionLabel(1080, 48))
        assertNull(MediaTargets.resolutionLabel(0, 30))
    }

    @Test
    fun `codec names are human`() {
        assertEquals("H.264", MediaTargets.codecName("avc1.640028", null))
        assertEquals("H.265", MediaTargets.codecName("hev1.1.6.L93", null))
        assertEquals("VP9", MediaTargets.codecName("vp9", null))
        assertEquals("AV1", MediaTargets.codecName("av01.0.08M", null))
        assertEquals("AAC", MediaTargets.codecName(null, "audio/mp4a-latm"))
        assertEquals("Opus", MediaTargets.codecName("opus", null))
        assertNull(MediaTargets.codecName(null, null))
    }

    @Test
    fun `sizes are estimated from bitrate and rounded to two figures`() {
        // 10 min at 3 Mbps ≈ 225 MB
        assertEquals(230_000_000L, MediaTargets.estimateBytes(600.0, 3000))
        assertNull(MediaTargets.estimateBytes(null, 3000))
        assertNull(MediaTargets.estimateBytes(600.0, null))
        assertEquals("~450 MB", MediaTargets.humanSize(450_000_000))
        assertEquals("~1.2 GB", MediaTargets.humanSize(1_200_000_000))
    }

    @Test
    fun `supported targets are exactly what the native pipeline writes`() {
        assertTrue(MediaTargets.isSupported("mp3"))
        assertTrue(MediaTargets.isSupported("M4A"))
        assertTrue(MediaTargets.isSupported("wav"))
        assertTrue(MediaTargets.isSupported("mp4"))
        assertTrue(MediaTargets.isSupported("webm"))
        assertFalse(MediaTargets.isSupported("flac"))
        assertFalse(MediaTargets.isSupported("gif"))
        assertTrue(MediaTargets.unsupportedReason("opus")!!.contains("desktop"))
        assertNull(MediaTargets.unsupportedReason("mp3"))
    }

    @Test
    fun `stream labels pair resolution with the codec`() {
        assertEquals(
            "1080p60 · H.264",
            MediaTargets.streamLabel(1080, 60, "avc1.640028", null, null),
        )
        assertEquals(
            "AAC · 128 kbps",
            MediaTargets.streamLabel(null, null, null, "audio/mp4a-latm", 128),
        )
    }
}
