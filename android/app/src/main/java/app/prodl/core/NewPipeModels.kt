package app.prodl.core

import java.util.Locale

/**
 * One directly downloadable stream as NewPipeExtractor sees it. Kept free of
 * extractor types so the mapping and selection logic stays unit testable.
 */
data class NpStream(
    val itag: Int,
    val url: String,
    val ext: String,
    val mimeType: String? = null,
    val codec: String? = null,
    val videoOnly: Boolean = false,
    val height: Int? = null,
    val resolution: String? = null,
    val fps: Int? = null,
    val bitrateKbps: Int? = null,
) {
    val hasVideo: Boolean get() = height != null && height > 0
}

/**
 * Everything the extractor returned for one video, flattened. This is what the
 * stream cache stores, so a download after `/api/info` does not re-scrape.
 */
data class NpMedia(
    val id: String?,
    val title: String,
    val webpageUrl: String,
    val durationSeconds: Double? = null,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val viewCount: Long? = null,
    val likeCount: Long? = null,
    val uploadDate: String? = null,
    val description: String? = null,
    val isLive: Boolean = false,
    val ageLimit: Int = 0,
    val extractor: String = "YouTube (NewPipeExtractor)",
    val muxed: List<NpStream> = emptyList(),
    val videoOnly: List<NpStream> = emptyList(),
    val audio: List<NpStream> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    val related: List<RelatedItem> = emptyList(),
    val hlsUrl: String? = null,
    val dashMpdUrl: String? = null,
)

/**
 * One browsable list: a channel's uploads or a playlist's videos, with the
 * header metadata the Discover → Channel/Playlist screen shows.
 */
data class BrowseResult(
    val url: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val banner: String? = null,
    val avatar: String? = null,
    val description: String? = null,
    val items: List<SearchItem> = emptyList(),
)

/**
 * `format_id` values handed to clients for NewPipe streams. They stay strings
 * so the API shape is identical to yt-dlp's format ids; the `np:` prefix is
 * what tells the backend which engine can serve a download.
 */
object NpFormatId {
    const val PREFIX = "np:"
    const val AUTO = "np:auto"
    const val BEST_AUDIO = "np:audio-best"

    enum class Kind { AUTO, MUXED, VIDEO, AUDIO, BEST_AUDIO }

    data class Parsed(val kind: Kind, val itag: Int? = null)

    fun muxed(itag: Int): String = "np:muxed:$itag"

    fun video(itag: Int): String = "np:video:$itag"

    fun audio(itag: Int): String = "np:audio:$itag"

    fun parse(formatId: String?): Parsed? {
        val value = formatId?.trim().orEmpty()
        if (!value.startsWith(PREFIX)) return null
        val body = value.removePrefix(PREFIX)
        return when {
            body == "auto" || body.isEmpty() -> Parsed(Kind.AUTO)
            body == "audio-best" || body == "best-audio" -> Parsed(Kind.BEST_AUDIO)
            else -> {
                val kind = when (body.substringBefore(':')) {
                    "muxed" -> Kind.MUXED
                    "video" -> Kind.VIDEO
                    "audio" -> Kind.AUDIO
                    else -> return null
                }
                Parsed(kind, body.substringAfter(':', "").toIntOrNull())
            }
        }
    }
}

/** What a download has to fetch: one muxed file, or a video plus an audio track. */
data class NpSelection(
    val video: NpStream?,
    val audio: NpStream? = null,
    val title: String = "video",
    val muxed: Boolean = false,
) {
    /** ffmpeg has to join the two tracks, or re-encode audio for the wanted container. */
    val needsMux: Boolean get() = video != null && audio != null

    val audioOnly: Boolean get() = video == null && audio != null
}

object NewPipeMapper {

    /** `https://www.youtube.com/watch?v=ID` → `ID`. */
    fun videoIdOf(url: String): String? {
        val query = url.substringAfter("/watch?", "").substringBefore('#')
        if (query.isEmpty()) {
            return url.substringAfter("/shorts/", "").substringBefore('?').ifEmpty { null }
        }
        return query.split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")
            ?.takeIf { it.isNotEmpty() }
    }

    fun searchItem(
        url: String,
        name: String,
        thumbnail: String?,
        duration: Double?,
        uploader: String?,
        viewCount: Long?,
        kind: String = "video",
        itemCount: Long? = null,
    ): SearchItem = SearchItem(
        id = videoIdOf(url) ?: url,
        title = name,
        url = url,
        thumbnail = thumbnail,
        duration = duration,
        uploader = uploader,
        viewCount = viewCount?.takeIf { it >= 0 },
        isLive = false,
        extractor = "YouTube",
        kind = kind,
        itemCount = itemCount?.takeIf { it >= 0 },
    )

    fun relatedItem(url: String, name: String, thumbnail: String?, duration: Double?, uploader: String?): RelatedItem =
        RelatedItem(
            id = videoIdOf(url) ?: url,
            title = name,
            url = url,
            thumbnail = thumbnail,
            duration = duration,
            uploader = uploader,
        )

    /**
     * Format list for the inspector.
     *
     * `resolution` is a human label (`1080p60`, `720p`) because the old
     * `"video only · avc1"` note was identical for a dozen buttons; `note`
     * carries the codec/kind for the secondary line, and `filesize` an estimate
     * from bitrate × duration so the user can compare 720p with 1080p.
     */
    fun formats(media: NpMedia, durationSeconds: Double? = media.durationSeconds): List<VideoFormatDto> {
        val out = mutableListOf<VideoFormatDto>()
        media.muxed.forEach { stream ->
            out += dto(stream, NpFormatId.muxed(stream.itag), video = true, audio = true, durationSeconds)
        }
        media.videoOnly.sortedWith(
            compareByDescending<NpStream> { it.height ?: 0 }.thenByDescending { it.fps ?: 0 },
        ).forEach { stream ->
            out += dto(stream, NpFormatId.video(stream.itag), video = true, audio = false, durationSeconds)
        }
        media.audio.sortedByDescending { it.bitrateKbps ?: 0 }.forEach { stream ->
            out += dto(stream, NpFormatId.audio(stream.itag), video = false, audio = true, durationSeconds)
        }
        return out
    }

    private fun dto(
        stream: NpStream,
        formatId: String,
        video: Boolean,
        audio: Boolean,
        durationSeconds: Double?,
    ): VideoFormatDto {
        val codec = MediaTargets.codecName(stream.codec, stream.mimeType)
        val label = if (video) {
            MediaTargets.resolutionLabel(stream.height, stream.fps)
                ?: stream.resolution
                ?: "video"
        } else {
            "audio"
        }
        val size = MediaTargets.estimateBytes(durationSeconds, stream.bitrateKbps)
        return VideoFormatDto(
            formatId = formatId,
            ext = stream.ext,
            resolution = label,
            filesize = size,
            // Secondary line: "video only · H.264" / "audio · AAC 128 kbps".
            note = listOfNotNull(
                when {
                    video && audio -> "video + audio"
                    video -> "video only"
                    else -> "audio"
                },
                codec,
                if (!video) stream.bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" } else null,
            ).joinToString(" · "),
            hasVideo = video,
            hasAudio = audio,
            vcodec = if (video) stream.codec else null,
            acodec = if (audio) stream.codec else null,
            fps = stream.fps?.takeIf { it > 0 }?.toDouble(),
            tbr = stream.bitrateKbps?.toDouble(),
            abr = if (!video) stream.bitrateKbps?.toDouble() else null,
            protocol = "https",
            directUrl = stream.url,
            width = null,
            height = stream.height,
        )
    }

    fun info(
        media: NpMedia,
        sponsorBlock: SponsorBlockInfo = SponsorBlockInfo(),
        cookiesUsed: Boolean = false,
    ): InfoResponse {
        val muxed = bestMuxed(media)
        val video = bestVideo(media)
        val formats = formats(media, media.durationSeconds)
        return InfoResponse(
            title = media.title,
            duration = media.durationSeconds,
            thumbnail = media.thumbnail,
            uploader = media.uploader,
            viewCount = media.viewCount,
            webpageUrl = media.webpageUrl,
            extractor = media.extractor,
            id = media.id,
            channel = media.uploader,
            likeCount = media.likeCount,
            uploadDate = media.uploadDate,
            description = media.description,
            isLive = media.isLive,
            ageLimit = media.ageLimit,
            formats = formats,
            subtitles = media.subtitles,
            // Playback needs sound: prefer the progressive (muxed) stream and
            // only fall back to video-only when YouTube offers nothing muxed.
            bestVideoUrl = muxed?.url ?: video?.url ?: formats.firstOrNull { it.hasVideo }?.directUrl,
            bestAudioUrl = bestAudio(media)?.url ?: formats.firstOrNull { !it.hasVideo && it.directUrl != null }?.directUrl,
            // YouTube often ships no progressive stream at all, and a video-only
            // URL would play silently: the manifests carry both tracks.
            playbackUrl = muxed?.url ?: media.hlsUrl ?: media.dashMpdUrl ?: video?.url,
            hlsUrl = media.hlsUrl,
            dashMpdUrl = media.dashMpdUrl,
            sponsorblock = sponsorBlock,
            cookiesUsed = cookiesUsed,
            related = media.related,
        )
    }

    fun bestMuxed(media: NpMedia): NpStream? = media.muxed.maxByOrNull { it.height ?: 0 }

    fun bestVideo(media: NpMedia): NpStream? =
        media.videoOnly.maxWithOrNull(compareBy({ it.height ?: 0 }, { it.fps ?: 0 }))

    fun bestAudio(media: NpMedia): NpStream? = media.audio.maxByOrNull { it.bitrateKbps ?: 0 }

    /**
     * Audio to mux with [video]. An MP4 video gets the best AAC track when one
     * exists: that pair copies into MP4, while Opus would force MKV.
     */
    fun bestAudioFor(media: NpMedia, video: NpStream): NpStream? {
        // An MP4 video pairs with AAC: that pair is a MediaMuxer copy, while
        // Opus in MP4 would need a re-encode.
        if (!isMp4Audio(video.ext)) return bestAudio(media)
        return media.audio.filter { isMp4Audio(it.ext) }.maxByOrNull { it.bitrateKbps ?: 0 }
            ?: bestAudio(media)
    }

    private fun isMp4Audio(ext: String): Boolean =
        ext.lowercase(Locale.US) in setOf("m4a", "aac", "mp4")

    /**
     * Resolve a client's `format_id` (or an audio-only request) into the streams
     * to fetch. Unknown ids fall back to the best available quality instead of
     * failing, so a stale page never dead-ends a download.
     */
    fun select(media: NpMedia, formatId: String?, audioOnly: Boolean): NpSelection? {
        val parsed = NpFormatId.parse(formatId)
        if (parsed == null && !formatId.isNullOrBlank()) return null

        if (audioOnly) {
            val audio = when (parsed?.kind) {
                NpFormatId.Kind.AUDIO -> media.audio.firstOrNull { it.itag == parsed.itag } ?: bestAudio(media)
                else -> bestAudio(media)
            } ?: return null
            return NpSelection(video = null, audio = audio, title = media.title)
        }

        val kind = parsed?.kind ?: NpFormatId.Kind.AUTO
        return when (kind) {
            NpFormatId.Kind.AUDIO, NpFormatId.Kind.BEST_AUDIO -> {
                val audio = when (kind) {
                    NpFormatId.Kind.AUDIO ->
                        media.audio.firstOrNull { it.itag == parsed?.itag } ?: bestAudio(media)
                    else -> bestAudio(media)
                } ?: return null
                NpSelection(video = null, audio = audio, title = media.title)
            }

            NpFormatId.Kind.MUXED -> {
                val stream = media.muxed.firstOrNull { it.itag == parsed?.itag }
                    ?: bestMuxed(media)?.takeIf { it.url.isNotEmpty() }
                if (stream != null) {
                    NpSelection(video = stream, title = media.title, muxed = true)
                } else {
                    auto(media)
                }
            }

            NpFormatId.Kind.VIDEO -> {
                // An unknown video id still means "a video": fall back to the
                // best video-only track, not to the smaller muxed one.
                val video = media.videoOnly.firstOrNull { it.itag == parsed?.itag }
                    ?: bestVideo(media)
                if (video == null) {
                    auto(media)
                } else {
                    val audio = bestAudioFor(media, video)
                    NpSelection(video = video, audio = audio, title = media.title, muxed = audio == null)
                }
            }

            NpFormatId.Kind.AUTO -> auto(media)
        }
    }

    private fun auto(media: NpMedia): NpSelection? {
        val muxed = bestMuxed(media)
        if (muxed != null) return NpSelection(video = muxed, title = media.title, muxed = true)
        val video = bestVideo(media) ?: return null
        // No progressive stream: fetch the audio track too and let ffmpeg join them.
        return NpSelection(video = video, audio = bestAudioFor(media, video), title = media.title)
    }

    /** A human file name that survives every Android file system. */
    fun fileName(title: String, ext: String, videoId: String?): String {
        val safe = MimeTypes.safeName(title)
        val suffix = videoId?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
        val trimmed = (safe + suffix).take(140).trim()
        return "$trimmed.${ext.lowercase(Locale.US)}"
    }
}
