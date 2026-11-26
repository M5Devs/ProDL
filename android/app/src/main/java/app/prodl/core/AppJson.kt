package app.prodl.core

import kotlinx.serialization.json.Json

/** One JSON configuration for every encode/decode in the app. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = true
    encodeDefaults = true
    coerceInputValues = true
}

/** Raised for expected yt-dlp/ffmpeg failures; the message is user facing. */
class YtDlpException(message: String) : Exception(message)

/** Trim yt-dlp's multi-line stderr down to the actionable line. */
fun cleanError(raw: String): String {
    val lines = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("Traceback") && !it.startsWith("File \"") }
        .toList()
    val errorLine = lines.lastOrNull { it.contains("ERROR", ignoreCase = true) }
        ?.substringAfter("ERROR:", "")
        ?.trim()
        ?.ifEmpty { null }
        ?: lines.lastOrNull()
        ?: "Unknown error"

    return when {
        errorLine.contains("HTTP Error 403") ->
            "$errorLine — the site rejected this device. Try Settings → Link account to add cookies, or update yt-dlp."
        errorLine.contains("Sign in to confirm", ignoreCase = true) ->
            "$errorLine — sign in from Settings → Link account."
        errorLine.contains("Unsupported URL", ignoreCase = true) ->
            "$errorLine — this link is not supported by yt-dlp."
        errorLine.length > 400 -> errorLine.take(400) + "…"
        else -> errorLine
    }
}
