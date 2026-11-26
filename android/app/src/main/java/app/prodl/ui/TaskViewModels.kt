package app.prodl.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.prodl.core.ConvertRequest
import app.prodl.core.DownloadRequest
import app.prodl.core.MediaTargets
import app.prodl.core.TaskSnapshot
import app.prodl.core.TaskStatus
import app.prodl.server.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ── Downloads ───────────────────────────────────────────────────────────────

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    private val manager get() = server?.tasks

    /**
     * Live list straight from [app.prodl.server.TaskManager]. Its `revision`
     * StateFlow is bumped on every progress event, so this list updates in real
     * time with no polling and no WebSocket.
     */
    val tasks: StateFlow<List<TaskSnapshot>> = (manager?.revision ?: MutableStateFlow(0L))
        .map { manager?.all()?.map { task -> task.snapshot() }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), emptyList())

    fun fileFor(taskId: String): File? = manager?.get(taskId)?.filePath

    fun cancel(taskId: String) {
        val task: Task = manager?.get(taskId) ?: return
        server?.media?.cancel(task)
    }

    fun remove(taskId: String) {
        manager?.remove(taskId)
    }

    fun startDownload(request: DownloadRequest): Result<String> = runCatching {
        val media = server?.media ?: error("The engine is not ready yet")
        media.startDownload(request).id
    }

    fun startConvert(request: ConvertRequest, upload: File? = null): Result<String> = runCatching {
        val media = server?.media ?: error("The engine is not ready yet")
        media.startConvert(request, upload).id
    }

    /** Copy a picked document into the app's uploads dir so the pipeline can read it. */
    suspend fun stageDocument(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val uploads = server?.config?.uploadsDir ?: error("Storage is not ready")
        uploads.mkdirs()
        val name = displayName(context, uri)
        val target = File(uploads, "${System.currentTimeMillis()}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected file")
        target
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "input"
        return raw.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "input" }
    }
}

// ── Convert ─────────────────────────────────────────────────────────────────

data class ConvertState(
    val inputName: String? = null,
    val staged: File? = null,
    val format: String = "mp3",
    val bitrate: String = "192k",
    val start: String = "",
    val end: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class ConvertViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ConvertState())
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    fun setFormat(format: String) = _state.update { it.copy(format = format) }

    fun setBitrate(bitrate: String) = _state.update { it.copy(bitrate = bitrate) }

    fun setStart(value: String) = _state.update { it.copy(start = value) }

    fun setEnd(value: String) = _state.update { it.copy(end = value) }

    fun pick(context: Context, uri: Uri, downloads: DownloadsViewModel) {
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            runCatching { downloads.stageDocument(context, uri) }
                .onSuccess { file ->
                    _state.update {
                        it.copy(staged = file, inputName = file.name.substringAfter('-'), busy = false)
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(busy = false, error = failure.userMessage()) }
                }
        }
    }

    fun convert(downloads: DownloadsViewModel) {
        val staged = _state.value.staged ?: return
        val format = MediaTargets.normalize(_state.value.format)
        _state.update { it.copy(busy = true, error = null, message = null) }
        val request = ConvertRequest(
            path = staged.absolutePath,
            targetFormat = format,
            audioBitrate = _state.value.bitrate,
            start = seconds(_state.value.start),
            end = seconds(_state.value.end),
        )
        downloads.startConvert(request, upload = staged)
            .onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        message = "Converting to $format — track it in Downloads",
                        staged = null,
                        inputName = null,
                        start = "",
                        end = "",
                    )
                }
            }
            .onFailure { failure -> _state.update { it.copy(busy = false, error = failure.userMessage()) } }
    }

    /** `83`, `1:23`, `1:23.5`, `01:02:03` → seconds. */
    internal fun seconds(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching {
            if (text.contains(':')) {
                text.split(':').map { it.trim() }.fold(0.0) { total, part -> total * 60 + part.toDouble() }
            } else {
                text.toDouble()
            }
        }.getOrNull()
    }
}

// ── Shared formatting helpers ───────────────────────────────────────────────

fun TaskStatus.label(): String = when (this) {
    TaskStatus.QUEUED -> "Queued"
    TaskStatus.DOWNLOADING -> "Downloading"
    TaskStatus.POSTPROCESSING -> "Processing"
    TaskStatus.CONVERTING -> "Converting"
    TaskStatus.FINISHED -> "Ready"
    TaskStatus.ERROR -> "Failed"
    TaskStatus.CANCELED -> "Canceled"
}

fun formatDuration(seconds: Double?): String? {
    if (seconds == null || seconds <= 0) return null
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

fun formatCount(value: Long?): String? {
    if (value == null || value < 0) return null
    return when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}
