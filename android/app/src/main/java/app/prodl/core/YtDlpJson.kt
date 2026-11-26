package app.prodl.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Maps yt-dlp's `--dump-single-json` output onto the API_SPEC.md v1.1.0 models.
 * The shape is the same one the Python backend parses, so both targets agree.
 */
object YtDlpJson {

    fun info(url: String, json: JsonObject, cookiesUsed: Boolean = false): InfoResponse {
        val formats = parseFormats(json)
        return InfoResponse(
            title = json.str("title") ?: json.str("fulltitle") ?: "Untitled",
            duration = json.dbl("duration"),
            thumbnail = json.str("thumbnail") ?: lastThumbnail(json),
            uploader = json.str("uploader") ?: json.str("channel"),
            viewCount = json.long("view_count"),
            webpageUrl = json.str("webpage_url") ?: url,
            extractor = json.str("extractor") ?: json.str("extractor_key"),
            id = json.str("id"),
            channel = json.str("channel") ?: json.str("uploader"),
            likeCount = json.long("like_count"),
            uploadDate = json.str("upload_date"),
            description = json.str("description"),
            isLive = json.bool("is_live") ?: (json.str("live_status") == "is_live"),
            ageLimit = json.int("age_limit") ?: 0,
            formats = formats,
            subtitles = parseSubtitles(json),
            bestVideoUrl = bestUrl(formats, video = true),
            bestAudioUrl = bestUrl(formats, video = false),
            cookiesUsed = cookiesUsed,
        )
    }

    fun videoId(json: JsonObject): String? = json.str("id")

    fun title(json: JsonObject): String? = json.str("title") ?: json.str("fulltitle")

    fun search(query: String, limit: Int, json: JsonObject): SearchResponse {
        val entries: List<JsonObject> = when {
            json.arr("entries") != null -> json.arr("entries")!!.mapNotNull { it.obj }
            json.rawStr("id") != null -> listOf(json)
            else -> emptyList()
        }
        val results = entries.take(limit).map { entry ->
            val id = entry.str("id") ?: ""
            val url = entry.str("webpage_url")
                ?: entry.str("url")
                ?: entry.str("original_url")
                ?: if (id.isNotEmpty()) "https://www.youtube.com/watch?v=$id" else ""
            SearchItem(
                id = id,
                title = entry.str("title") ?: entry.str("fulltitle") ?: "Untitled",
                url = url,
                thumbnail = entry.str("thumbnail") ?: lastThumbnail(entry),
                duration = entry.dbl("duration"),
                uploader = entry.str("uploader") ?: entry.str("channel"),
                viewCount = entry.long("view_count"),
                isLive = entry.bool("is_live") ?: (entry.str("live_status") == "is_live"),
                extractor = entry.str("extractor") ?: entry.str("ie_key"),
            )
        }
        return SearchResponse(query = query, limit = limit, count = results.size, results = results)
    }

    fun parseFormats(json: JsonObject): List<VideoFormatDto> {
        val parsed = json.arr("formats")?.mapNotNull { element -> element.obj?.let { formatOf(it) } }.orEmpty()
        if (parsed.isNotEmpty()) return parsed
        // Direct links: yt-dlp's generic extractor puts the stream at the top
        // level instead of inside a `formats` array.
        return listOfNotNull(formatFromTopLevel(json))
    }

    private fun formatOf(format: JsonObject): VideoFormatDto? {
        val formatId = format.str("format_id") ?: return null
        val vcodec = format.str("vcodec")
        val acodec = format.str("acodec")
        val width = format.int("width")
        val height = format.int("height")
        val hasVideo = vcodec != null && vcodec != "none"
        val hasAudio = acodec != null && acodec != "none"
        val resolution = format.str("resolution")
            ?: when {
                width != null && height != null -> "${width}x$height"
                !hasVideo && hasAudio -> "audio only"
                height != null -> "${height}p"
                else -> null
            }
        return VideoFormatDto(
            formatId = formatId,
            ext = format.str("ext") ?: "",
            resolution = resolution,
            filesize = format.long("filesize") ?: format.long("filesize_approx"),
            note = format.str("format_note"),
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            vcodec = vcodec,
            acodec = acodec,
            fps = format.dbl("fps"),
            tbr = format.dbl("tbr"),
            abr = format.dbl("abr"),
            protocol = format.str("protocol"),
            directUrl = format.str("url"),
            width = width,
            height = height,
        )
    }

    private fun formatFromTopLevel(json: JsonObject): VideoFormatDto? {
        val url = json.str("url") ?: return null
        val ext = (json.str("ext") ?: url.substringBefore('?').substringAfterLast('.', "")).lowercase()
        val vcodec = json.str("vcodec")
        val acodec = json.str("acodec")
        val audioOnly = ext in MimeTypes.audioExtensions && ext !in MimeTypes.videoExtensions
        val width = json.int("width")
        val height = json.int("height")
        return VideoFormatDto(
            formatId = json.str("format_id") ?: "best",
            ext = ext,
            resolution = json.str("resolution")
                ?: when {
                    width != null && height != null -> "${width}x$height"
                    audioOnly -> "audio only"
                    else -> null
                },
            filesize = json.long("filesize") ?: json.long("filesize_approx"),
            note = json.str("format_note") ?: "direct",
            hasVideo = (vcodec != null && vcodec != "none") || !audioOnly,
            hasAudio = (acodec != null && acodec != "none") || audioOnly,
            vcodec = vcodec,
            acodec = acodec,
            fps = json.dbl("fps"),
            tbr = json.dbl("tbr"),
            abr = json.dbl("abr"),
            protocol = json.str("protocol"),
            directUrl = url,
            width = width,
            height = height,
        )
    }

    fun parseSubtitles(json: JsonObject): List<SubtitleDto> {
        val result = mutableListOf<SubtitleDto>()
        val seen = mutableSetOf<String>()
        json.child("subtitles")?.forEach { (lang, value) ->
            if (seen.add(lang)) {
                result += SubtitleDto(lang, languageName(value), isAuto = false, ext = subtitleExt(value))
            }
        }
        json.child("automatic_captions")?.forEach { (lang, value) ->
            if (seen.add(lang)) {
                result += SubtitleDto(lang, languageName(value), isAuto = true, ext = subtitleExt(value))
            }
        }
        return result.sortedWith(compareBy({ it.isAuto }, { it.lang }))
    }

    private fun languageName(value: JsonElement?): String? =
        (value as? JsonArray)?.firstOrNull()?.obj?.str("name")

    private fun subtitleExt(value: JsonElement?): String =
        (value as? JsonArray)?.firstOrNull()?.obj?.str("ext") ?: "vtt"

    private fun lastThumbnail(json: JsonObject): String? =
        json.arr("thumbnails")?.mapNotNull { it.obj?.str("url") }?.lastOrNull()

    fun bestUrl(formats: List<VideoFormatDto>, video: Boolean): String? {
        val candidates = formats.filter { it.directUrl != null && if (video) it.hasVideo else it.hasAudio }
        if (candidates.isEmpty()) return null
        return if (video) {
            candidates.maxWithOrNull(compareBy({ it.height ?: 0 }, { it.tbr ?: 0.0 }))?.directUrl
        } else {
            candidates.maxWithOrNull(compareBy({ it.abr ?: 0.0 }, { it.tbr ?: 0.0 }))?.directUrl
        }
    }
}
