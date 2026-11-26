package app.prodl.core

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode the audio track of any container to 16-bit interleaved PCM with
 * MediaExtractor + MediaCodec — the step that used to be `ffmpeg -i in -f s16le`.
 *
 * Used for the two targets Android cannot encode directly: MP3 (via LAME) and
 * WAV (raw PCM plus a header).
 */
class AudioDecoder {

    data class Decoded(val sampleRate: Int, val channels: Int, val frames: Long)

    /**
     * Decode [input] into [sink], honouring an optional [startMs]–[endMs] window.
     * [onProgress] receives 0.0–1.0 based on the timestamps actually decoded.
     */
    fun decode(
        input: File,
        startMs: Long? = null,
        endMs: Long? = null,
        onProgress: (Double) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        sink: PcmSink,
    ): Decoded {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = firstTrackOf(extractor, "audio/")
                ?: throw YtDlpException("This file has no audio track to decode")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw YtDlpException("The audio track has no MIME type")
            val startUs = (startMs ?: 0L) * 1000
            val endUs = endMs?.let { it * 1000 }
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // The extractor's own track format carries csd-0/csd-1, which the
            // decoder needs for AAC/Opus; only the PCM encoding is overridden.
            val inputFormat = format
            runCatching {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var opened = false
            var frames = 0L
            var inputDone = false
            var outputDone = false
            val durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            val windowUs = when {
                durationUs <= 0L -> 0L
                endUs != null && startUs > 0 -> endUs - startUs
                endUs != null -> endUs
                else -> durationUs - startUs
            }

            while (!outputDone) {
                if (shouldCancel()) throw YtDlpException("Conversion canceled")
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: ByteBuffer.allocate(0)
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        val pts = extractor.sampleTime
                        val pastEnd = endUs != null && pts >= 0 && pts > endUs
                        if (size < 0 || pastEnd) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sink.open(sampleRate, channels)
                        opened = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outIndex >= 0) {
                            val outBuffer = codec.getOutputBuffer(outIndex)
                            if (outBuffer != null && info.size > 0) {
                                if (!opened) {
                                    sink.open(sampleRate, channels)
                                    opened = true
                                }
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                val samples = ShortArray(info.size / 2)
                                outBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
                                sink.write(samples, samples.size)
                                frames += samples.size / channels
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            val positionUs = info.presentationTimeUs - startUs
                            if (windowUs > 0) onProgress((positionUs.toDouble() / windowUs).coerceIn(0.0, 1.0))
                        }
                    }
                }
            }
            if (!opened) sink.open(sampleRate, channels)
            return Decoded(sampleRate, channels, frames)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { sink.close() }
        }
    }

    private fun firstTrackOf(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun MediaFormat.getLongOrDefault(key: String, fallback: Long): Long =
        runCatching { getLong(key) }.getOrDefault(fallback)

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
