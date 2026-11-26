package app.prodl.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Task lifecycle shared with API_SPEC.md. Serialized names are part of the contract. */
@Serializable
enum class TaskStatus {
    @SerialName("queued") QUEUED,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("postprocessing") POSTPROCESSING,
    @SerialName("converting") CONVERTING,
    @SerialName("finished") FINISHED,
    @SerialName("error") ERROR,
    @SerialName("canceled") CANCELED;

    val isTerminal: Boolean
        get() = this == FINISHED || this == ERROR || this == CANCELED
}

/**
 * Engine or tool state. `state` is additive over API_SPEC v1.0.0 so a client can
 * tell "still unpacking" apart from "broken" without parsing `note`.
 */
@Serializable
data class ComponentStatus(
    val available: Boolean,
    val version: String? = null,
    val path: String? = null,
    val note: String? = null,
    val state: String? = null,
)

@Serializable
data class CookiesStatus(
    val configured: Boolean,
    @SerialName("cookie_count") val cookieCount: Int,
    @SerialName("updated_at") val updatedAt: String? = null,
    val storage: String = "local-file",
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("api_version") val apiVersion: String,
    val version: String,
    @SerialName("uptime_seconds") val uptimeSeconds: Double,
    val platform: String,
    val python: String,
    @SerialName("data_dir") val dataDir: String,
    @SerialName("download_dir") val downloadDir: String,
    @SerialName("download_dir_writable") val downloadDirWritable: Boolean,
    @SerialName("yt_dlp") val ytDlp: ComponentStatus,
    /** NewPipeExtractor (Android only): the primary YouTube engine. */
    val newpipe: ComponentStatus? = null,
    val ffmpeg: ComponentStatus,
    val ffprobe: ComponentStatus,
    @SerialName("js_runtime") val jsRuntime: ComponentStatus,
    val cookies: CookiesStatus,
    val features: Map<String, Boolean>,
)

@Serializable
data class SearchItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val uploader: String? = null,
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("is_live") val isLive: Boolean = false,
    val extractor: String? = null,
)

@Serializable
data class SearchResponse(
    val query: String,
    val limit: Int,
    val count: Int,
    val results: List<SearchItem>,
)

@Serializable
data class VideoFormatDto(
    @SerialName("format_id") val formatId: String,
    val ext: String,
    val resolution: String? = null,
    val filesize: Long? = null,
    val note: String? = null,
    @SerialName("has_video") val hasVideo: Boolean,
    @SerialName("has_audio") val hasAudio: Boolean,
    val vcodec: String? = null,
    val acodec: String? = null,
    val fps: Double? = null,
    val tbr: Double? = null,
    val abr: Double? = null,
    val protocol: String? = null,
    @SerialName("direct_url") val directUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SubtitleDto(
    val lang: String,
    @SerialName("lang_name") val langName: String? = null,
    @SerialName("is_auto") val isAuto: Boolean = false,
    val ext: String = "vtt",
)

@Serializable
data class SponsorBlockSegment(
    val category: String,
    val start: Double,
    val end: Double,
    val action: String = "skip",
    val uuid: String? = null,
)

@Serializable
data class SponsorBlockInfo(
    val requested: Boolean = false,
    @SerialName("video_id") val videoId: String? = null,
    val categories: List<String> = emptyList(),
    val segments: List<SponsorBlockSegment> = emptyList(),
    val error: String? = null,
)

@Serializable
data class InfoResponse(
    val title: String,
    val duration: Double? = null,
    val thumbnail: String? = null,
    val uploader: String? = null,
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("webpage_url") val webpageUrl: String,
    val extractor: String? = null,
    val id: String? = null,
    val channel: String? = null,
    @SerialName("like_count") val likeCount: Long? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    val description: String? = null,
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("age_limit") val ageLimit: Int = 0,
    val formats: List<VideoFormatDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    @SerialName("best_video_url") val bestVideoUrl: String? = null,
    @SerialName("best_audio_url") val bestAudioUrl: String? = null,
    val sponsorblock: SponsorBlockInfo = SponsorBlockInfo(),
    @SerialName("cookies_used") val cookiesUsed: Boolean = false,
)

@Serializable
data class FileRef(
    val filename: String,
    val filesize: Long,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("media_type") val mediaType: String? = null,
)

@Serializable
data class ProgressEvent(
    @SerialName("task_id") val taskId: String? = null,
    val seq: Long? = null,
    val status: TaskStatus,
    val percent: Double? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    val speed: Double? = null,
    @SerialName("speed_str") val speedStr: String? = null,
    val eta: Long? = null,
    val elapsed: Double? = null,
    val filename: String? = null,
    val title: String? = null,
    val message: String? = null,
    val error: String? = null,
    val timestamp: Double? = null,
    val file: FileRef? = null,
)

@Serializable
data class TaskAccepted(
    @SerialName("task_id") val taskId: String,
    val kind: String,
    val status: TaskStatus,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("websocket_url") val websocketUrl: String,
    @SerialName("sse_url") val sseUrl: String,
    @SerialName("task_url") val taskUrl: String,
    @SerialName("file_url") val fileUrl: String,
)

@Serializable
data class TaskSnapshot(
    @SerialName("task_id") val taskId: String,
    val kind: String,
    val status: TaskStatus,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("updated_at") val updatedAt: Double,
    val error: String? = null,
    val file: FileRef? = null,
    @SerialName("last_event") val lastEvent: ProgressEvent? = null,
)

@Serializable
data class ApiIndex(
    val name: String,
    val version: String,
    @SerialName("api_version") val apiVersion: String,
    val docs: String,
    val openapi: String,
    val endpoints: Map<String, String>,
)

@Serializable
data class ErrorResponse(val detail: String)

// ── requests ────────────────────────────────────────────────────────────────

@Serializable
data class DownloadRequest(
    val url: String,
    @SerialName("format_id") val formatId: String? = null,
    @SerialName("video_quality") val videoQuality: String? = null,
    @SerialName("audio_only") val audioOnly: Boolean = false,
    @SerialName("audio_format") val audioFormat: String = "mp3",
    @SerialName("audio_quality") val audioQuality: String = "192",
    val subtitles: Boolean = false,
    @SerialName("subtitle_langs") val subtitleLangs: List<String> = listOf("en"),
    @SerialName("embed_subtitles") val embedSubtitles: Boolean = false,
    @SerialName("auto_subtitles") val autoSubtitles: Boolean = true,
    val thumbnail: Boolean = false,
    @SerialName("embed_thumbnail") val embedThumbnail: Boolean = true,
    val sponsorblock: Boolean = false,
    @SerialName("sponsorblock_action") val sponsorblockAction: String = "cut",
    @SerialName("sponsorblock_categories") val sponsorblockCategories: List<String> =
        listOf("sponsor", "intro", "outro"),
    @SerialName("use_cookies") val useCookies: Boolean = true,
    @SerialName("max_filesize") val maxFilesize: String? = null,
    @SerialName("concurrent_fragments") val concurrentFragments: Int = 1,
)

@Serializable
data class ConvertRequest(
    @SerialName("task_id") val taskId: String? = null,
    val path: String? = null,
    @SerialName("target_format") val targetFormat: String = "mp3",
    @SerialName("audio_bitrate") val audioBitrate: String = "192k",
    @SerialName("video_crf") val videoCrf: Int = 23,
    val start: Double? = null,
    val end: Double? = null,
)

@Serializable
data class CookiesRequest(val content: String)
