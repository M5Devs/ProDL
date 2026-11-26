package app.prodl.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Defensive accessors for yt-dlp JSON: any key may be missing, null, or typed
 * differently between extractors and yt-dlp releases.
 */
internal val JsonElement?.obj: JsonObject?
    get() = this as? JsonObject

internal fun JsonObject.str(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.rawStr(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}

internal fun JsonObject.long(key: String): Long? = when (val element = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> element.contentOrNull?.toLongOrNull()
        ?: element.contentOrNull?.toDoubleOrNull()?.toLong()
    else -> null
}

internal fun JsonObject.dbl(key: String): Double? = when (val element = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> element.contentOrNull?.toDoubleOrNull()
    else -> null
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = when (val element = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> element.booleanOrNull
    else -> null
}

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject
