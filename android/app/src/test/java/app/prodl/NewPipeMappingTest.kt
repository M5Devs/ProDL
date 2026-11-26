package app.prodl

import app.prodl.core.MediaTargets
import app.prodl.core.NpFormatId
import app.prodl.core.NpMedia
import app.prodl.core.NpStream
import app.prodl.core.NewPipeMapper
import app.prodl.core.SubtitleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping and selection layer is the part of the NewPipe integration that
 * can be wrong without a network: format ids handed to clients, which stream a
 * `format_id` resolves to, and the container a mux lands in.
 */
class NewPipeMappingTest {

    private fun video(itag: Int, height: Int, ext: String = "mp4", fps: Int = 30) = NpStream(
        itag = itag,
        url = "https://media.example/v$itag",
        ext = ext,
        codec = "avc1.640028",
        videoOnly = true,
        height = height,
        resolution = "${height}p",
        fps = fps,
    )

    private fun audio(itag: Int, bitrate: Int, ext: String = "m4a") = NpStream(
        itag = itag,
        url = "https://media.example/a$itag",
        ext = ext,
        codec = if (ext == "m4a") "mp4a.40.2" else "opus",
        bitrateKbps = bitrate,
    )

    private val media = NpMedia(
        id = "abc123",
        title = "A Video: with / illegal characters?",
        webpageUrl = "https://www.youtube.com/watch?v=abc123",
        durationSeconds = 61.0,
        thumbnail = "https://img.example/1.jpg",
        uploader = "Someone",
        viewCount = 1234,
        muxed = listOf(video(18, height = 360, fps = 30)),
        videoOnly = listOf(video(137, 1080), video(136, 720)),
        audio = listOf(audio(140, 128), audio(251, 160, ext = "webm")),
        subtitles = listOf(SubtitleDto("en", "English", isAuto = false)),
    )

    @Test
    fun `format ids round trip`() {
        assertEquals(NpFormatId.Parsed(NpFormatId.Kind.MUXED, 18), NpFormatId.parse("np:muxed:18"))
        assertEquals(NpFormatId.Parsed(NpFormatId.Kind.VIDEO, 137), NpFormatId.parse("np:video:137"))
        assertEquals(NpFormatId.Parsed(NpFormatId.Kind.AUDIO, 140), NpFormatId.parse("np:audio:140"))
        assertEquals(NpFormatId.Parsed(NpFormatId.Kind.AUTO), NpFormatId.parse("np:auto"))
        assertEquals(NpFormatId.Parsed(NpFormatId.Kind.BEST_AUDIO), NpFormatId.parse("np:audio-best"))
    }

    @Test
    fun `yt-dlp selectors are not claimed by NewPipe`() {
        assertNull(NpFormatId.parse("137"))
        assertNull(NpFormatId.parse("bestvideo[height<=1080]+bestaudio"))
        assertNull(NpFormatId.parse(null))
    }

    @Test
    fun `formats expose muxed, video only and audio streams`() {
        val formats = NewPipeMapper.formats(media)
        assertEquals(1 + 2 + 2, formats.size)

        val muxed = formats.first { it.formatId == "np:muxed:18" }
        assertTrue(muxed.hasVideo && muxed.hasAudio)
        assertEquals("360p", muxed.resolution)
        assertEquals("https://media.example/v18", muxed.directUrl)

        val onlyVideo = formats.first { it.formatId == "np:video:137" }
        assertTrue(onlyVideo.hasVideo)
        assertTrue(!onlyVideo.hasAudio)
        assertEquals(1080, onlyVideo.height)

        val onlyAudio = formats.first { it.formatId == "np:audio:140" }
        assertTrue(onlyAudio.hasAudio && !onlyAudio.hasVideo)
        assertEquals("audio", onlyAudio.resolution)
    }

    @Test
    fun `info prefers the muxed stream for playback`() {
        val info = NewPipeMapper.info(media)
        // Playback needs sound, so the muxed stream wins; best_audio_url is the
        // highest-quality standalone track for audio-only clients.
        assertEquals("https://media.example/v18", info.bestVideoUrl)
        assertEquals("https://media.example/a251", info.bestAudioUrl)
        assertEquals("abc123", info.id)
        assertEquals(1, info.subtitles.size)
        assertEquals(61.0.toLong(), info.duration?.toLong())
    }

    @Test
    fun `auto selection takes the best muxed stream and needs no ffmpeg`() {
        val selection = NewPipeMapper.select(media, null, audioOnly = false)!!
        assertEquals(18, selection.video?.itag)
        assertNull(selection.audio)
        assertTrue(selection.muxed)
        assertTrue(!selection.needsMux)
    }

    @Test
    fun `video only selection pairs the best matching audio track`() {
        val selection = NewPipeMapper.select(media, "np:video:137", audioOnly = false)!!
        assertEquals(137, selection.video?.itag)
        // MP4 video + best AAC audio → a plain MP4 mux, no re-encode.
        assertEquals(140, selection.audio?.itag)
        assertTrue(selection.needsMux)
        // mp4 + m4a is a MediaMuxer copy; no re-encode, no MKV.
        assertEquals(
            MediaTargets.MuxPlan.COPY_MP4,
            MediaTargets.muxPlan("video/avc", "audio/mp4a-latm"),
        )
    }

    @Test
    fun `opus audio with mp4 video needs an audio-only transcode`() {
        val webmVideo = video(248, 1080, ext = "webm")
        val selection = NewPipeMapper.select(
            media.copy(videoOnly = listOf(webmVideo)),
            "np:video:248",
            audioOnly = false,
        )!!
        assertEquals(251, selection.audio?.itag)
        // WebM video keeps its Opus track: MediaMuxer copies both into WebM.
        assertEquals(
            MediaTargets.MuxPlan.COPY_WEBM,
            MediaTargets.muxPlan("video/x-vnd.on2.vp9", "audio/opus"),
        )
        // Opus inside MP4 is illegal, so only the audio gets re-encoded.
        assertEquals(
            MediaTargets.MuxPlan.AUDIO_TO_AAC_THEN_MP4,
            MediaTargets.muxPlan("video/avc", "audio/opus"),
        )
    }

    @Test
    fun `audio only requests pick the highest bitrate track`() {
        val selection = NewPipeMapper.select(media, null, audioOnly = true)!!
        assertNull(selection.video)
        assertEquals(251, selection.audio?.itag)
        assertTrue(selection.audioOnly)
    }

    @Test
    fun `unknown np itag still resolves to the best available stream`() {
        val selection = NewPipeMapper.select(media, "np:video:999", audioOnly = false)!!
        assertEquals(1080, selection.video?.height)
        assertTrue(selection.needsMux)
    }

    @Test
    fun `a yt-dlp format id is never served by the NewPipe path`() {
        assertNull(NewPipeMapper.select(media, "bestvideo+bestaudio", audioOnly = false))
    }

    @Test
    fun `muxed only library still downloads without ffmpeg`() {
        val muxedOnly = media.copy(videoOnly = emptyList(), audio = emptyList())
        val selection = NewPipeMapper.select(muxedOnly, "np:auto", audioOnly = false)!!
        assertTrue(selection.muxed)
        assertNull(selection.audio)
    }

    @Test
    fun `file names are safe and keep the video id`() {
        val name = NewPipeMapper.fileName("A Video: with / illegal characters?", "mp4", "abc123")
        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.contains("[abc123]"))
        assertTrue(name.none { it in "\\/:*?\"<>|" })
    }

    @Test
    fun `playback prefers a stream that carries sound`() {
        // Progressive first: it plays as-is.
        val progressive = NewPipeMapper.info(media)
        assertEquals("https://media.example/v18", progressive.playbackUrl)

        // YouTube frequently ships no progressive stream at all. The HLS
        // manifest then beats the video-only URL, which would be silent.
        val separated = NewPipeMapper.info(
            media.copy(
                muxed = emptyList(),
                hlsUrl = "https://manifest.example/hls.m3u8",
                dashMpdUrl = "https://manifest.example/dash.mpd",
            ),
        )
        assertEquals("https://manifest.example/hls.m3u8", separated.playbackUrl)
        assertEquals("https://manifest.example/hls.m3u8", separated.hlsUrl)
        assertTrue("video-only fallback still exposed", separated.bestVideoUrl!!.contains("/v137"))

        val dashOnly = NewPipeMapper.info(
            media.copy(muxed = emptyList(), dashMpdUrl = "https://manifest.example/dash.mpd"),
        )
        assertEquals("https://manifest.example/dash.mpd", dashOnly.playbackUrl)
    }

    @Test
    fun `video ids are read from every youtube url shape`() {
        assertEquals("dQw4w9WgXcQ", NewPipeMapper.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"))
        assertEquals("dQw4w9WgXcQ", NewPipeMapper.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("abc", NewPipeMapper.videoIdOf("https://www.youtube.com/shorts/abc?feature=share"))
        assertNull(NewPipeMapper.videoIdOf("https://example.com/video.mp4"))
    }
}
