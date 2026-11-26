package app.prodl.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * SponsorBlock segment lookup (`https://sponsor.ajay.app/api/skipSegments`).
 * Failures are reported in the payload instead of failing the request, exactly
 * like the Python backend.
 */
class SponsorBlockClient(
    private val api: String,
    private val defaultCategories: List<String>,
) {
    private val client = HttpClient(CIO) {
        expectSuccess = false
    }

    suspend fun segments(videoId: String, categories: List<String>?): SponsorBlockInfo {
        val selected = (categories ?: defaultCategories).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val base = SponsorBlockInfo(requested = true, videoId = videoId, categories = selected)
        if (videoId.isBlank()) return base.copy(error = "No video id")

        return try {
            val response = client.get(api) {
                parameter("videoID", videoId)
                parameter("categories", encodeCategories(selected))
            }
            if (response.status.value == 404) {
                base
            } else if (response.status.value !in 200..299) {
                base.copy(error = "SponsorBlock returned HTTP ${response.status.value}")
            } else {
                val payload = AppJson.parseToJsonElement(response.bodyAsText()) as? JsonArray
                    ?: return base.copy(error = "Unexpected SponsorBlock payload")
                base.copy(segments = payload.mapNotNull { element -> segmentOf(element as? JsonObject) })
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            base.copy(error = t.message ?: t::class.java.simpleName)
        }
    }

    fun close() = client.close()

    private fun encodeCategories(categories: List<String>): String =
        buildJsonArray { categories.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun segmentOf(element: JsonObject?): SponsorBlockSegment? {
        val segment = element?.get("segment") as? JsonArray ?: return null
        if (segment.size < 2) return null
        val start = (segment[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val end = (segment[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        return SponsorBlockSegment(
            category = (element["category"] as? JsonPrimitive)?.contentOrNull ?: "sponsor",
            start = start,
            end = end,
            action = (element["actionType"] as? JsonPrimitive)?.contentOrNull ?: "skip",
            uuid = (element["UUID"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}
