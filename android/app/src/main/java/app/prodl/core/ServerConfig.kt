package app.prodl.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * All on-device paths and limits, derived from the app sandbox so nothing is
 * hardcoded and nothing needs a storage permission.
 */
class ServerConfig(
    val dataDir: File,
    val downloadDir: File,
    val uploadsDir: File,
    val cookiesFile: File,
    val searchLimit: Int = 10,
    val searchLimitMax: Int = 50,
    val maxUploadBytes: Long = 512L * 1024 * 1024,
    val maxCookieBytes: Long = 2L * 1024 * 1024,
    val sponsorBlockApi: String = "https://sponsor.ajay.app/api/skipSegments",
    val sponsorBlockCategories: List<String> = listOf("sponsor", "intro", "outro"),
    val apiVersion: String = "1.0.0",
    /** Matches the SPA's file:// default so the UI works even without injection. */
    val preferredPort: Int = 8000,
    val taskTtlSeconds: Long = 3600,
    val maxTasks: Int = 200,
) {
    fun ensureDirectories(): Boolean {
        dataDir.mkdirs()
        uploadsDir.mkdirs()
        downloadDir.mkdirs()
        return downloadDir.isDirectory && downloadDir.canWrite()
    }

    companion object {
        fun default(context: Context): ServerConfig {
            val data = File(context.filesDir, "prodl")
            val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(data, "downloads")
            return ServerConfig(
                dataDir = data,
                downloadDir = File(external, "ProDL"),
                uploadsDir = File(data, "uploads"),
                cookiesFile = File(data, "cookies.txt"),
            )
        }
    }
}
