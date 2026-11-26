package app.prodl.core

import java.util.Locale

/**
 * What this device can produce without ffmpeg, and how.
 *
 * Everything here is pure: no Android classes, so the decisions that used to be
 * `ffmpeg -c:v ... -c:a ...` guesses are unit tested on the JVM.
 *
 * - MP4 / M4A: MediaMuxer copies tracks when the codecs already fit, Media3
 *   Transformer re-encodes with the device's hardware codecs when they do not.
 * - WEBM: MediaMuxer can only *copy* VP8/VP9 + Opus/Vorbis; no Android device
 *   ships a VP9/Opus encoder, so transcoding to WEBM is refused, not faked.
 * - MP3: MediaCodec has no MP3 encoder. PCM is decoded with MediaCodec and
 *   encoded by jump3r (pure-Java LAME).
 * - WAV: PCM plus a 44-byte RIFF header, written here.
 */
object MediaTargets {
    /** Formats this pipeline can write. */
    val audioFormats = linkedSetOf("mp3", "m4a", "wav")
    val videoFormats = linkedSetOf("mp4", "webm")
    val allFormats: Set<String> = audioFormats + videoFormats

    /** Formats the desktop backend still supports through ffmpeg. */
    val desktopOnlyFormats = linkedSetOf("opus", "flac", "gif")

    fun normalize(format: String?): String = format?.trim()?.lowercase(Locale.US).orEmpty()

    fun isAudio(format: String): Boolean = normalize(format) in audioFormats

    fun isVideo(format: String): Boolean = normalize(format) in videoFormats

    fun isSupported(format: String?): Boolean = normalize(format) in allFormats

    /** Why a target is unavailable on-device, for a 400 the user can act on. */
    fun unsupportedReason(format: String): String? {
        val value = normalize(format)
        if (value in allFormats) return null
        return when (value) {
            "opus", "flac", "gif" ->
                "$value needs libopus/libflac/gif encoding, which only the desktop backend ships. " +
                    "Use mp3, m4a, wav, mp4 or webm."
            else -> "Unsupported target_format: $format"
        }
    }

    /** Container MediaMuxer writes, once the codecs are known to fit. */
    enum class Container { MP4, WEBM }

    /** How a mux request is satisfied. */
    enum class MuxPlan {
        /** Copy both tracks into MP4 (avc/hevc/av1 video + aac audio). */
        COPY_MP4,

        /** Copy both tracks into WebM (vp8/vp9/av1 video + opus/vorbis audio). */
        COPY_WEBM,

        /** Video is MP4-compatible, audio is not: transcode audio to AAC, then copy. */
        AUDIO_TO_AAC_THEN_MP4,

        /** Nothing matches: re-encode the whole mux through Media3 Transformer. */
        REENCODE_TO_MP4,

        /** Media3 cannot write WebM, so this pair cannot be produced at all. */
        UNSUPPORTED,
    }

    private val mp4Video = setOf("video/avc", "video/hevc", "video/av01", "video/mp4v-es", "video/x-vnd.on2.vp9")
    private val mp4Audio = setOf("audio/mp4a-latm", "audio/aac", "audio/mp4a", "audio/x-aac")
    private val webmVideo = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")
    private val webmAudio = setOf("audio/opus", "audio/vorbis", "audio/x-vorbis")

    fun mp4VideoFits(mime: String?): Boolean = mime?.lowercase(Locale.US) in mp4Video

    fun mp4AudioFits(mime: String?): Boolean = mime?.lowercase(Locale.US) in mp4Audio

    fun webmVideoFits(mime: String?): Boolean = mime?.lowercase(Locale.US) in webmVideo

    fun webmAudioFits(mime: String?): Boolean = mime?.lowercase(Locale.US) in webmAudio

    /**
     * Decide how to join a video-only and an audio-only file.
     * `null` mime means "not present in this source".
     */
    fun muxPlan(videoMime: String?, audioMime: String?): MuxPlan {
        if (videoMime == null || audioMime == null) return MuxPlan.UNSUPPORTED
        if (mp4VideoFits(videoMime) && mp4AudioFits(audioMime)) return MuxPlan.COPY_MP4
        if (webmVideoFits(videoMime) && webmAudioFits(audioMime)) return MuxPlan.COPY_WEBM
        if (mp4VideoFits(videoMime)) return MuxPlan.AUDIO_TO_AAC_THEN_MP4
        if (webmVideoFits(videoMime) && mp4AudioFits(audioMime)) return MuxPlan.REENCODE_TO_MP4
        return MuxPlan.UNSUPPORTED
    }

    /** Container for a plan, or null when the plan needs a round trip through MP4. */
    fun containerOf(plan: MuxPlan): Container? = when (plan) {
        MuxPlan.COPY_MP4, MuxPlan.AUDIO_TO_AAC_THEN_MP4 -> Container.MP4
        MuxPlan.COPY_WEBM -> Container.WEBM
        else -> null
    }

    /** Extension for a finished artifact. */
    fun extensionOf(container: Container): String = when (container) {
        Container.MP4 -> "mp4"
        Container.WEBM -> "webm"
    }

    fun mimeOf(format: String): String = when (normalize(format)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    // ── labels ───────────────────────────────────────────────────────────────

    /**
     * `1080p`, `1080p60`, `144p` — the primary label on a format button.
     *
     * Matches how YouTube names its own qualities: no suffix at 30 fps or
     * below, `50`/`60` for high frame rates, and the raw number for the rare
     * in-between (48 fps streams are labelled `1080p48`).
     */
    fun resolutionLabel(height: Int?, fps: Int?): String? {
        if (height == null || height <= 0) return null
        val name = "${height}p"
        return when {
            fps == null || fps <= 30 -> name
            fps >= 58 -> "${name}60"
            fps in 49..51 -> "${name}50"
            else -> "${name}${fps}"
        }
    }

    /** `H.264`, `VP9`, `AV1`, `AAC`, `Opus` from a codec string or mime type. */
    fun codecName(codec: String?, mime: String?): String? {
        val raw = (codec ?: mime ?: "").lowercase(Locale.US)
        return when {
            raw.isBlank() -> null
            "avc" in raw || "h264" in raw || "mp4a.avc" in raw -> "H.264"
            "hev1" in raw || "hvc1" in raw || "hevc" in raw -> "H.265"
            "av01" in raw || "av1" in raw -> "AV1"
            "vp9" in raw || "vp09" in raw -> "VP9"
            "vp8" in raw -> "VP8"
            "mp4a" in raw || "aac" in raw -> "AAC"
            "opus" in raw -> "Opus"
            "vorbis" in raw -> "Vorbis"
            "mpeg" in raw -> "MP3"
            else -> codec?.substringBefore('.')?.uppercase(Locale.US)?.take(8)
        }
    }

    /**
     * Rough download size for a stream, from its bitrate. YouTube does not
     * publish per-format sizes without a probe, and a "~" estimate is enough to
     * choose between 720p and 1080p.
     */
    fun estimateBytes(durationSeconds: Double?, bitrateKbps: Int?): Long? {
        val seconds = durationSeconds?.takeIf { it > 0 } ?: return null
        val kbps = bitrateKbps?.takeIf { it > 0 } ?: return null
        val bytes = seconds * kbps * 1000.0 / 8.0
        // Round to 2 significant figures so "~453 MB" reads as "~450 MB".
        if (bytes < 1_000) return 0L
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(bytes)) - 1)
        return (Math.round(bytes / magnitude) * magnitude).toLong()
    }

    /** `~45 MB`, `~1.2 GB`. */
    fun humanSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val mb = bytes / 1_000_000.0
        return when {
            mb >= 1000 -> String.format(Locale.US, "~%.1f GB", mb / 1000)
            mb >= 100 -> String.format(Locale.US, "~%.0f MB", mb)
            mb >= 10 -> String.format(Locale.US, "~%.0f MB", mb)
            mb >= 1 -> String.format(Locale.US, "~%.1f MB", mb)
            else -> String.format(Locale.US, "~%.0f KB", mb * 1000)
        }
    }

    /** `1080p60 · H.264` for an audio+video stream, `AAC · 128 kbps` for audio. */
    fun streamLabel(height: Int?, fps: Int?, codec: String?, mime: String?, bitrateKbps: Int?): String {
        val resolution = resolutionLabel(height, fps)
        val name = codecName(codec, mime)
        val bitrate = bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" }
        return listOfNotNull(resolution, name, bitrate).joinToString(" · ")
    }
}
