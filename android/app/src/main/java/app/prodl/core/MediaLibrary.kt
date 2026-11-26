package app.prodl.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes finished downloads to the shared Downloads/ProDL folder so other
 * apps (and the user's file manager) can see them. On Android 10+ this goes
 * through MediaStore, so no storage permission is required.
 */
object MediaLibrary {
    suspend fun export(context: Context, source: File): Uri? = withContext(Dispatchers.IO) {
        if (!source.isFile) return@withContext null
        val mime = MimeTypes.guess(source.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, source, mime)
        } else {
            exportLegacy(source)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(context: Context, source: File, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProDL")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null
        val copied = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("cannot open $uri")
        }.isSuccess
        if (!copied) {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(source: File): Uri? {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProDL")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = MimeTypes.uniqueTarget(directory, source.name)
        return runCatching {
            source.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        }.getOrNull()
    }
}
