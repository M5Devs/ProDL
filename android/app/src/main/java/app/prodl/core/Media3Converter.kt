package app.prodl.core

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hardware-accelerated transcode, audio extraction and trimming via Media3
 * Transformer. No bundled binary: the work happens in the device's MediaCodec
 * encoders, so this is faster than ffmpeg on phones and adds no APK weight.
 *
 * Transformer always writes an MP4 container, which is why `.mp4` and `.m4a`
 * are the only targets it serves here; WEBM has no muxer in Media3 and MP3 has
 * no platform encoder (see [Mp3Writer]).
 */
class Media3Converter(private val context: Context) {

    /**
     * Transcode [input] into [output]. [videoMime] null means "drop the video
     * track" (audio extraction); [audioMime] null means "drop the audio".
     */
    suspend fun transcode(
        input: File,
        output: File,
        videoMime: String? = MimeTypes.VIDEO_H264,
        audioMime: String? = MimeTypes.AUDIO_AAC,
        startMs: Long? = null,
        endMs: Long? = null,
        onProgress: (Double) -> Unit = {},
    ) {
        val mediaItem = MediaItem.Builder()
            .setUri(input.toUriCompat())
            .apply {
                if (startMs != null || endMs != null) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .apply {
                                if (startMs != null) setStartPositionMs(startMs)
                                if (endMs != null) setEndPositionMs(endMs)
                            }
                            .build(),
                    )
                }
            }
            .build()

        val item = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(videoMime == null)
            .setRemoveAudio(audioMime == null)
            .build()

        // Transformer needs a Looper for its internal playback thread; the
        // caller runs on Dispatchers.IO, so build and start it on the main one.
        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(context)
                .apply {
                    videoMime?.let { setVideoMimeType(it) }
                    audioMime?.let { setAudioMimeType(it) }
                }
                .build()

            suspendCancellableCoroutine<Unit> { continuation ->
                val holder = ProgressHolder()
                var poll: Job? = null

                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        poll?.cancel()
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        poll?.cancel()
                        if (continuation.isActive) {
                            continuation.resumeWithException(describeFailure(exception))
                        }
                    }
                })

                continuation.invokeOnCancellation {
                    poll?.cancel()
                    transformer.cancel()
                }

                // Progress is a percentage integer, polled while the export
                // thread works. The scope is cancelled with the continuation.
                poll = CoroutineScope(Dispatchers.Main).launch {
                    while (continuation.isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress.coerceIn(0, 100) / 100.0)
                        }
                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    transformer.start(item, output.absolutePath)
                } catch (t: Throwable) {
                    poll?.cancel()
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
            }
        }
        if (!output.isFile || output.length() == 0L) {
            throw YtDlpException("The converter produced an empty file")
        }
    }

    /**
     * ExportException carries the failing codec's name and reason; a bare
     * "export failed" would leave the user with nothing to act on.
     */
    private fun describeFailure(exception: ExportException): YtDlpException {
        val reason = exception.message ?: exception.errorCodeName
        val codec = exception.cause?.message?.takeIf { it.isNotBlank() }
        val text = if (codec != null) "$reason ($codec)" else reason
        return YtDlpException("Hardware conversion failed: ${text.take(300)}")
    }

    private fun File.toUriCompat() = android.net.Uri.fromFile(this)

    companion object {
        private const val PROGRESS_POLL_MS = 400L

        /** Mime types this device can encode, probed once. */
        fun encoderMimes(): Set<String> = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { it.isEncoder }
                .flatMap { it.supportedTypes.toList() }
                .toSet()
        }.getOrDefault(emptySet())

        fun canEncode(mime: String): Boolean = mime in encoderMimes()

        fun hasVideoEncoder(): Boolean = canEncode(MimeTypes.VIDEO_H264) || canEncode(MimeTypes.VIDEO_H265)

        fun hasAacEncoder(): Boolean = canEncode(MimeTypes.AUDIO_AAC)

        val isCancellation: (Throwable) -> Boolean = { it is CancellationException }
    }
}
