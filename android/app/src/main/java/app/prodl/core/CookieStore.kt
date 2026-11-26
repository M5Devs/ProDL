package app.prodl.core

import android.os.Build
import android.system.Os
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The local cookie store. Written with 0600 permissions inside the app sandbox
 * and only ever read by yt-dlp; it is never returned by the API.
 */
class CookieStore(private val file: File) {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)

    fun status(): CookiesStatus {
        if (!file.isFile) return CookiesStatus(configured = false, cookieCount = 0)
        val rows = runCatching { NetscapeCookies.parse(file.readText()) }.getOrDefault(emptyList())
        val updated = timestampFormat.format(Instant.ofEpochMilli(file.lastModified()))
        return CookiesStatus(configured = rows.isNotEmpty(), cookieCount = rows.size, updatedAt = updated)
    }

    @Throws(CookieFormatException::class)
    fun save(text: String): CookiesStatus {
        val rows = NetscapeCookies.parse(text)
        file.parentFile?.mkdirs()
        file.writeText(NetscapeCookies.serialize(rows))
        restrictToOwner(file)
        return status()
    }

    fun clear(): CookiesStatus {
        if (file.exists()) file.delete()
        return status()
    }

    fun fileOrNull(): File? = if (file.isFile && file.length() > 0L) file else null

    private fun restrictToOwner(target: File) {
        runCatching {
            target.setReadable(false, false)
            target.setWritable(false, false)
        }
        runCatching {
            Os.chmod(target.absolutePath, 0x180) // 0600
        }
        runCatching {
            target.setReadable(true, true)
            target.setWritable(true, true)
        }
    }
}
