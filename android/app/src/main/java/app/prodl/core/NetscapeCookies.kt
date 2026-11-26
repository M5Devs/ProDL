package app.prodl.core

class CookieFormatException(message: String) : Exception(message)

data class CookieRow(
    val domain: String,
    val includeSubdomains: Boolean,
    val path: String,
    val secure: Boolean,
    val expiry: Long,
    val name: String,
    val value: String,
)

/**
 * Netscape cookie file handling. Pure string logic (unit tested on the JVM);
 * on-disk permissions live in [CookieStore].
 */
object NetscapeCookies {
    const val HEADER = "# Netscape HTTP Cookie File"
    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private const val FIELD_COUNT = 7

    fun parse(text: String): List<CookieRow> {
        val rows = mutableListOf<CookieRow>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) return@forEachIndexed
            val httpOnly = line.startsWith(HTTP_ONLY_PREFIX)
            if (line.startsWith("#") && !httpOnly) return@forEachIndexed

            val body = if (httpOnly) line.removePrefix(HTTP_ONLY_PREFIX) else line
            val fields = body.split('\t')
            val lineNumber = index + 1
            if (fields.size != FIELD_COUNT) {
                throw CookieFormatException(
                    "Invalid cookie on line $lineNumber: expected $FIELD_COUNT tab-separated fields, found ${fields.size}"
                )
            }
            val domain = fields[0].trim()
            val name = fields[5].trim()
            if (domain.isEmpty()) throw CookieFormatException("Invalid cookie on line $lineNumber: empty domain")
            if (name.isEmpty()) throw CookieFormatException("Invalid cookie on line $lineNumber: empty name")
            rows += CookieRow(
                domain = domain,
                includeSubdomains = fields[1].equals("TRUE", ignoreCase = true),
                path = fields[2].ifBlank { "/" },
                secure = fields[3].equals("TRUE", ignoreCase = true),
                expiry = fields[4].trim().toLongOrNull() ?: 0L,
                name = name,
                value = fields[6],
            )
        }
        if (rows.isEmpty()) {
            throw CookieFormatException("No cookies found: expected at least one Netscape cookie row")
        }
        return rows
    }

    fun serialize(rows: List<CookieRow>): String = buildString {
        appendLine(HEADER)
        appendLine("# Written on this device by ProDL. Never leaves the phone.")
        rows.forEach { row ->
            append(row.domain)
            append('\t')
            append(if (row.includeSubdomains) "TRUE" else "FALSE")
            append('\t')
            append(row.path)
            append('\t')
            append(if (row.secure) "TRUE" else "FALSE")
            append('\t')
            append(row.expiry)
            append('\t')
            append(row.name)
            append('\t')
            append(row.value)
            append('\n')
        }
    }

    /** Turn a WebView `Cookie` header ("a=1; b=2") into rows for [host]. */
    fun fromCookieHeader(host: String, header: String?, expiry: Long): List<CookieRow> {
        if (header.isNullOrBlank()) return emptyList()
        val domain = cookieDomainFor(host)
        return header.split(';').mapNotNull { pair ->
            val trimmed = pair.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1)
            if (name.isEmpty()) return@mapNotNull null
            CookieRow(
                domain = domain,
                includeSubdomains = true,
                path = "/",
                secure = true,
                expiry = expiry,
                name = name,
                value = value,
            )
        }
    }

    fun cookieDomainFor(host: String): String {
        val clean = host.removePrefix(".").lowercase()
        return when {
            clean.endsWith("youtube.com") -> ".youtube.com"
            clean.endsWith("google.com") -> ".google.com"
            clean.endsWith("googlevideo.com") -> ".googlevideo.com"
            else -> ".$clean"
        }
    }

    /** De-duplicate rows (domain+path+name) keeping the last occurrence. */
    fun dedupe(rows: List<CookieRow>): List<CookieRow> {
        val byKey = LinkedHashMap<String, CookieRow>()
        rows.forEach { byKey["${it.domain}\u0000${it.path}\u0000${it.name}"] = it }
        return byKey.values.toList()
    }
}
