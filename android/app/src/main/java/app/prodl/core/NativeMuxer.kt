package app.prodl.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Track copy without re-encoding, using the platform MediaMuxer.
 *
 * This replaces `ffmpeg -c copy -map 0:v:0 -map 1:a:0`: join a video-only and an
 * audio-only file, or remux one file into another container that accepts its
 * codecs. Samples keep their original timestamps, so quality is untouched and
 * the cost is a single disk pass.
 */
class NativeMuxer {

    data class Result(val file: File, val durationMs: Long)

    /** What a file contains, used to decide whether a copy is possible. */
    data class TrackSummary(val videoMime: String?, val audioMime: String?, val durationMs: Long)

    fun summarise(file: File): TrackSummary {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoMime: String? = null
            var audioMime: String? = null
            var durationMs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                if (durationUs / 1000 > durationMs) durationMs = durationUs / 1000
                when {
                    mime.startsWith("video/") && videoMime == null -> videoMime = mime
                    mime.startsWith("audio/") && audioMime == null -> audioMime = mime
                }
            }
            return TrackSummary(videoMime, audioMime, durationMs)
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun durationMs(file: File): Long? = summarise(file).durationMs.takeIf { it > 0 }

    /**
     * Write [video]'s first video track and [audio]'s first audio track into
     * [output]. Pass the same file twice to remux a muxed source, or null for a
     * track that should not be included (audio extraction).
     */
    fun mux(
        video: File?,
        audio: File?,
        output: File,
        container: MediaTargets.Container,
        startMs: Long? = null,
        endMs: Long? = null,
        shouldCancel: () -> Boolean = { false },
    ): Result {
        require(video != null || audio != null) { "mux needs at least one input" }
        val outputFormat = when (container) {
            MediaTargets.Container.MP4 -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            MediaTargets.Container.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        }
        val sources = mutableListOf<Source>()
        val muxer = MediaMuxer(output.absolutePath, outputFormat)
        var started = false
        try {
            video?.let { addTrack(it, "video/", muxer, sources) }
            audio?.let { addTrack(it, "audio/", muxer, sources) }
            if (sources.isEmpty()) throw YtDlpException("No track to copy into the output")
            muxer.start()
            started = true

            val startUs = (startMs ?: 0L) * 1000
            val endUs = endMs?.let { it * 1000 }
            sources.forEach { source ->
                source.extractor.selectTrack(source.trackIndex)
                if (startUs > 0) source.extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                source.peek()
            }

            // Interleave by timestamp: writing one whole track first makes the
            // muxer buffer every sample in memory before the second track starts.
            val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var lastUs = 0L
            while (true) {
                if (shouldCancel()) throw YtDlpException("Muxing canceled")
                val next = sources.filter { !it.done }.minByOrNull { it.pendingUs ?: Long.MAX_VALUE }
                    ?: break
                val pts = next.pendingUs ?: break
                if (endUs != null && pts > endUs) {
                    next.done = true
                    continue
                }
                buffer.clear()
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.done = true
                    continue
                }
                if (pts >= startUs) {
                    info.set(0, size, pts - startUs, next.extractor.sampleFlags)
                    muxer.writeSampleData(next.muxerTrack, buffer, info)
                    lastUs = pts - startUs
                }
                next.extractor.advance()
                next.peek()
            }
            return Result(output, lastUs / 1000)
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            sources.forEach { runCatching { it.extractor.release() } }
        }
    }

    private fun addTrack(file: File, prefix: String, muxer: MediaMuxer, out: MutableList<Source>) {
        val extractor = MediaExtractor()
        var kept = false
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith(prefix)) continue
                out += Source(extractor, i, muxer.addTrack(format))
                kept = true
                return
            }
            throw YtDlpException("${file.name} has no ${prefix.trimEnd('/')} track")
        } finally {
            if (!kept) runCatching { extractor.release() }
        }
    }

    private class Source(val extractor: MediaExtractor, val trackIndex: Int, val muxerTrack: Int) {
        var done = false
        var pendingUs: Long? = null

        fun peek() {
            pendingUs = extractor.sampleTime.takeIf { it >= 0 }
            if (pendingUs == null) done = true
        }
    }

    companion object {
        private const val BUFFER_BYTES = 4 * 1024 * 1024
    }
}
