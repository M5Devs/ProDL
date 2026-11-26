package app.prodl.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The on-device replacement for the bundled ffmpeg binary.
 *
 * | job | how |
 * | --- | --- |
 * | join video + audio | MediaMuxer track copy (no re-encode) |
 * | transcode / trim | Media3 Transformer on the hardware codecs |
 * | MP3 | MediaCodec decode → jump3r (LAME) encode |
 * | WAV | MediaCodec decode → RIFF writer |
 * | duration | MediaMetadataRetriever |
 *
 * Public entry points are suspending and take a progress callback, so the task
 * manager can report through the same WebSocket/SSE pipeline as before.
 */
class MediaPipeline(private val context: Context) {

    private val muxer = NativeMuxer()
    private val decoder = AudioDecoder()
    private val converter = Media3Converter(context)

    /** Line shown on the health card; no binary, so nothing can be "missing". */
    val description: String = "native MediaMuxer + Media3 + MP3"

    val version: String =
        "MediaMuxer · Media3 1.11 · LAME 3.100 (jump3r) · ${MediaTargets.allFormats.joinToString("/")}"

    val available: Boolean = true

    val supportedFormats: Set<String> get() = MediaTargets.allFormats

    fun durationSeconds(file: File): Double? = with(android.media.MediaMetadataRetriever()) {
        try {
            setDataSource(file.absolutePath)
            extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0)?.takeIf { it > 0 }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { release() }
        }
    }

    // ── muxing ──────────────────────────────────────────────────────────────

    /**
     * Join a video-only and an audio-only file into a container that fits both
     * codecs. Falls back to re-encoding only when the pair cannot be copied.
     */
    suspend fun mux(
        video: File,
        audio: File,
        outputDir: File,
        onStage: (String) -> Unit = {},
        onProgress: (Double) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): File = withContext(Dispatchers.IO) {
        val videoSummary = muxer.summarise(video)
        val audioSummary = muxer.summarise(audio)
        val plan = MediaTargets.muxPlan(videoSummary.videoMime, audioSummary.audioMime)
        when (plan) {
            MediaTargets.MuxPlan.COPY_MP4, MediaTargets.MuxPlan.COPY_WEBM -> {
                val container = MediaTargets.containerOf(plan)!!
                onStage("Muxing video and audio")
                val out = File(outputDir, "muxed.${MediaTargets.extensionOf(container)}")
                val started = System.currentTimeMillis()
                muxer.mux(video, audio, out, container, shouldCancel = shouldCancel)
                onProgress(1.0)
                if (!out.isFile || out.length() == 0L) throw YtDlpException("Muxing produced an empty file")
                Log.i(TAG, "muxed ${out.name} in ${System.currentTimeMillis() - started} ms")
                out
            }

            MediaTargets.MuxPlan.AUDIO_TO_AAC_THEN_MP4 -> {
                onStage("Converting audio for MP4")
                val aac = convertAudio(audio, File(outputDir, "audio.m4a"), "m4a", 192) { onProgress(it * 0.7) }
                onStage("Muxing video and audio")
                val out = File(outputDir, "muxed.mp4")
                muxer.mux(video, aac, out, MediaTargets.Container.MP4, shouldCancel = shouldCancel)
                aac.delete()
                onProgress(1.0)
                if (!out.isFile || out.length() == 0L) throw YtDlpException("Muxing produced an empty file")
                out
            }

            MediaTargets.MuxPlan.REENCODE_TO_MP4 -> {
                onStage("Re-encoding to MP4")
                if (!Media3Converter.hasVideoEncoder()) {
                    throw YtDlpException(
                        "This device has no H.264 encoder, so ${videoSummary.videoMime} + " +
                            "${audioSummary.audioMime} cannot be joined. Choose an MP4 format."
                    )
                }
                mergeWithMedia3(video, audio, File(outputDir, "muxed.mp4"), onProgress)
            }

            MediaTargets.MuxPlan.UNSUPPORTED -> throw YtDlpException(
                "Cannot join ${videoSummary.videoMime} with ${audioSummary.audioMime} on this device. " +
                    "Choose an MP4 or WebM format."
            )
        }
    }

    /**
     * Media3 merges a background audio asset under a video asset: one
     * Composition, two EditedMediaItems. Used only when a plain copy is illegal.
     */
    private suspend fun mergeWithMedia3(
        video: File,
        audio: File,
        output: File,
        onProgress: (Double) -> Unit,
    ): File {
        // Transcode the audio to AAC first (cheap, no video work), then the
        // video+audio pass has a codec pair Transformer can always mux.
        val aac = File(output.parentFile, "bg.m4a")
        convertAudio(audio, aac, "m4a", 192) { onProgress(it * 0.4) }
        try {
            converter.transcode(
                input = video,
                output = output,
                videoMime = androidx.media3.common.MimeTypes.VIDEO_H264,
                audioMime = null, // video track only: audio is added in the copy below
                onProgress = { onProgress(0.5 + it * 0.3) },
            )
            val muxed = File(output.parentFile, "muxed-merged.mp4")
            muxer.mux(output, aac, muxed, MediaTargets.Container.MP4)
            output.delete()
            muxed.renameTo(output)
            return output
        } finally {
            aac.delete()
        }
    }

    // ── conversion ──────────────────────────────────────────────────────────

    /**
     * Convert [input] to [format] (`mp3`, `m4a`, `wav`, `mp4`, `webm`), with an
     * optional trim window in milliseconds.
     */
    suspend fun convert(
        input: File,
        output: File,
        format: String,
        audioBitrateKbps: Int = 192,
        startMs: Long? = null,
        endMs: Long? = null,
        onStage: (String) -> Unit = {},
        onProgress: (Double) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): File = withContext(Dispatchers.IO) {
        val target = MediaTargets.normalize(format)
        MediaTargets.unsupportedReason(target)?.let { throw YtDlpException(it) }
        val summary = muxer.summarise(input)

        when (target) {
            "mp3" -> {
                if (summary.audioMime == null) throw YtDlpException("This file has no audio track")
                onStage("Extracting audio (MP3)")
                decodeTo(input, Mp3Sink(output.outputStream(), audioBitrateKbps), startMs, endMs, onProgress, shouldCancel)
            }

            "wav" -> {
                if (summary.audioMime == null) throw YtDlpException("This file has no audio track")
                onStage("Extracting audio (WAV)")
                decodeTo(input, WavSink(output), startMs, endMs, onProgress, shouldCancel)
            }

            "m4a" -> {
                if (MediaTargets.mp4AudioFits(summary.audioMime)) {
                    onStage("Copying audio track")
                    muxer.mux(null, input, output, MediaTargets.Container.MP4, startMs, endMs, shouldCancel)
                } else {
                    convertAudio(input, output, "m4a", audioBitrateKbps, onProgress)
                }
            }

            "mp4" -> {
                val copyable = MediaTargets.mp4VideoFits(summary.videoMime) &&
                    (summary.audioMime == null || MediaTargets.mp4AudioFits(summary.audioMime))
                if (copyable) {
                    onStage("Copying tracks into MP4")
                    muxer.mux(input, input, output, MediaTargets.Container.MP4, startMs, endMs, shouldCancel)
                } else {
                    onStage("Transcoding to MP4")
                    if (!Media3Converter.hasVideoEncoder()) {
                        throw YtDlpException("This device has no H.264 encoder; MP4 output is unavailable")
                    }
                    converter.transcode(
                        input, output,
                        videoMime = androidx.media3.common.MimeTypes.VIDEO_H264,
                        audioMime = androidx.media3.common.MimeTypes.AUDIO_AAC,
                        startMs = startMs,
                        endMs = endMs,
                        onProgress = onProgress,
                    )
                }
            }

            "webm" -> {
                // Media3 has no WebM muxer and Android ships no VP9/Opus encoder,
                // so WebM is copy-only. Say so instead of silently writing MP4.
                val copyable = MediaTargets.webmVideoFits(summary.videoMime) &&
                    (summary.audioMime == null || MediaTargets.webmAudioFits(summary.audioMime))
                if (!copyable) {
                    throw YtDlpException(
                        "WEBM needs VP8/VP9 + Opus, and this device cannot encode those. " +
                            "Choose MP4, or MP3/M4A for audio."
                    )
                }
                onStage("Copying tracks into WebM")
                muxer.mux(input, input, output, MediaTargets.Container.WEBM, startMs, endMs, shouldCancel)
            }

            else -> throw YtDlpException(MediaTargets.unsupportedReason(target) ?: "Unsupported format")
        }

        if (!output.isFile || output.length() == 0L) {
            output.delete()
            throw YtDlpException("Conversion produced an empty file")
        }
        output
    }

    /** Audio-only transcode to AAC (`.m4a`). */
    suspend fun convertAudio(
        input: File,
        output: File,
        format: String = "m4a",
        bitrateKbps: Int = 192,
        onProgress: (Double) -> Unit = {},
    ): File {
        if (format == "mp3") {
            return convert(input, output, "mp3", bitrateKbps, onProgress = onProgress)
        }
        if (format == "wav") {
            return convert(input, output, "wav", bitrateKbps, onProgress = onProgress)
        }
        converter.transcode(
            input = input,
            output = output,
            videoMime = null,
            audioMime = if (Media3Converter.hasAacEncoder()) {
                androidx.media3.common.MimeTypes.AUDIO_AAC
            } else {
                throw YtDlpException("This device has no AAC encoder; use MP3 or WAV")
            },
            onProgress = onProgress,
        )
        return output
    }

    private fun decodeTo(
        input: File,
        sink: PcmSink,
        startMs: Long?,
        endMs: Long?,
        onProgress: (Double) -> Unit,
        shouldCancel: () -> Boolean,
    ) {
        val result = decoder.decode(
            input = input,
            startMs = startMs,
            endMs = endMs,
            onProgress = onProgress,
            shouldCancel = shouldCancel,
            sink = sink,
        )
        if (result.frames <= 0) throw YtDlpException("No audio samples were decoded")
    }

    companion object {
        private const val TAG = "ProDL.MediaPipeline"

        /** `"192k"` / `"192"` / `""` → 192. */
        fun bitrateKbps(value: String?): Int {
            val digits = value?.let { Regex("""(\d{2,3})""").find(it)?.groupValues?.get(1) } ?: return 192
            return digits.toIntOrNull()?.coerceIn(Mp3Writer.MIN_BITRATE, Mp3Writer.MAX_BITRATE) ?: 192
        }

        fun describe(t: Throwable): String =
            "${t.javaClass.simpleName}: ${t.message ?: "no message"}".also {
                Log.w(TAG, it, t)
            }

        fun targetExtension(format: String): String = MediaTargets.normalize(format).ifEmpty { "mp3" }

        fun locale(): Locale = Locale.US
    }
}
