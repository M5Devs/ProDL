package app.prodl.core

import java.util.Locale

/**
 * What the single Discover input box was given. Pure, so the routing rules are
 * unit tested instead of being guessed in the UI layer.
 */
object InputParser {

    enum class Kind { SEARCH, VIDEO, CHANNEL, PLAYLIST, URL }

    /** True for anything the user clearly meant as a link rather than a query. */
    fun isUrl(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.contains(' ')) return false
        val lower = value.lowercase(Locale.US)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("www.") ||
            lower.startsWith("youtu.be/") ||
            lower.startsWith("m.youtube.com/") ||
            (lower.contains("youtube.com/") && lower.contains('.'))
    }

    /** Classify the input so the app can open the right screen. */
    fun classify(raw: String): Kind {
        val value = raw.trim()
        if (!isUrl(value)) return Kind.SEARCH
        val lower = value.lowercase(Locale.US)
        return when {
            lower.contains("/playlist") || lower.contains("list=") -> Kind.PLAYLIST
            lower.contains("/watch") || lower.contains("/shorts/") || lower.contains("/live/") ||
                lower.contains("youtu.be/") || lower.contains("v=") -> Kind.VIDEO
            lower.contains("/channel/") || lower.contains("/c/") || lower.contains("/user/") ||
                lower.contains("/@") -> Kind.CHANNEL
            else -> Kind.URL
        }
    }

    /** Normalise `youtu.be/ID`, `www.` and scheme-less input into an https URL. */
    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value
        val lower = value.lowercase(Locale.US)
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> value
            lower.startsWith("youtu.be/") || lower.startsWith("m.youtube.com/") ||
                lower.startsWith("www.") -> "https://$value"
            else -> value
        }
    }
}
