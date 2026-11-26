package app.prodl.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prodl.core.MediaLibrary
import app.prodl.core.TaskSnapshot
import app.prodl.core.TaskStatus
import app.prodl.playback.PlayerActivity
import app.prodl.ui.DownloadsViewModel
import app.prodl.ui.components.EmptyState
import app.prodl.ui.components.StatusPill
import app.prodl.ui.label
import app.prodl.ui.theme.ProDLColors
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live download and conversion cards. The list comes from TaskManager's
 * StateFlow, so the progress bars move without polling or a WebSocket.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    snackbar: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun exportThen(taskId: String, then: (Uri) -> Unit) {
        val file = viewModel.fileFor(taskId)
        if (file == null) {
            scope.launch { snackbar.showSnackbar("This file is no longer on disk") }
            return
        }
        scope.launch {
            val uri = MediaLibrary.export(context, file)
            if (uri == null) {
                snackbar.showSnackbar("Could not save to Downloads/ProDL")
            } else {
                then(uri)
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar("Storage permission is needed on this Android version") }
    }

    fun ensureStorage(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return granted
    }

    val active = remember(tasks) { tasks.filter { !it.status.isTerminal } }
    val finished = remember(tasks) { tasks.filter { it.status.isTerminal } }

    if (tasks.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyState(
                title = "No downloads yet",
                message = "Pick a video on Discover and tap Download. Progress shows up here in real time.",
                icon = Icons.Filled.DownloadDone,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active.isNotEmpty()) {
            item { Text("Active", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text) }
            items(active, key = { it.taskId }) { task ->
                TaskCard(
                    task = task,
                    onCancel = { viewModel.cancel(task.taskId) },
                    onDelete = { viewModel.remove(task.taskId) },
                    onPlay = {
                        val file = viewModel.fileFor(task.taskId)
                        if (file != null) {
                            PlayerActivity.start(
                                context = context,
                                url = file.toURI().toString(),
                                title = task.lastEvent?.title ?: file.name,
                                thumbnail = null,
                                audioOnly = false,
                                sponsorblockJson = null,
                            )
                        }
                    },
                    onSave = { if (ensureStorage()) exportThen(task.taskId) { _ -> } },
                    onShare = {
                        if (ensureStorage()) {
                            exportThen(task.taskId) { uri ->
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = context.contentResolver.getType(uri) ?: "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(send, "Share"))
                                }
                            }
                        }
                    },
                )
            }
        }

        if (finished.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Finished", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
            }
            items(finished, key = { it.taskId }) { task ->
                TaskCard(
                    task = task,
                    onCancel = null,
                    onDelete = { viewModel.remove(task.taskId) },
                    onPlay = {
                        val file = viewModel.fileFor(task.taskId)
                        if (file != null) {
                            PlayerActivity.start(
                                context = context,
                                url = file.toURI().toString(),
                                title = task.lastEvent?.title ?: file.name,
                                thumbnail = null,
                                audioOnly = false,
                                sponsorblockJson = null,
                            )
                        }
                    },
                    onSave = { if (ensureStorage()) exportThen(task.taskId) { _ -> } },
                    onShare = {
                        if (ensureStorage()) {
                            exportThen(task.taskId) { uri ->
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = context.contentResolver.getType(uri) ?: "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(send, "Share"))
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskSnapshot,
    onCancel: (() -> Unit)?,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val event = task.lastEvent
    val percent = (event?.percent ?: 0.0).coerceIn(0.0, 100.0)
    val title = event?.title
        ?: event?.filename
        ?: task.file?.filename
        ?: if (task.kind == "convert") "Conversion" else "Download"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ProDLColors.Card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = ProDLColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(task.status.label(), statusColor(task.status))
        }

        if (!task.status.isTerminal) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ProDLColors.Primary,
                trackColor = ProDLColors.CardHigh,
            )
        }

        if (task.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = task.error ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = ProDLColors.Danger,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = ProDLColors.Danger)
                }
            }
            if (task.status == TaskStatus.FINISHED) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = ProDLColors.Ready)
                }
                IconButton(onClick = onSave) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = "Save to Downloads", tint = ProDLColors.Text)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = ProDLColors.Text)
                }
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ProDLColors.TextDim)
            }
        }
    }
}

private fun subtitle(task: TaskSnapshot): String {
    val event = task.lastEvent
    val parts = buildList {
        if (task.kind == "convert") add("Convert")
        event?.percent?.takeIf { !task.status.isTerminal }?.let { add("${it.toInt()}%") }
        event?.speedStr?.let { add(it) }
        event?.eta?.takeIf { it > 0 }?.let { add("ETA ${it}s") }
        task.file?.let { add(formatBytes(it.filesize)) }
        event?.message?.takeIf { task.status.isTerminal || it.isNotBlank() }?.let { add(it) }
    }
    return parts.joinToString(" · ").ifBlank { "Working…" }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun statusColor(status: TaskStatus) = when (status) {
    TaskStatus.FINISHED -> ProDLColors.Ready
    TaskStatus.ERROR -> ProDLColors.Danger
    TaskStatus.CANCELED -> ProDLColors.TextDim
    TaskStatus.POSTPROCESSING, TaskStatus.CONVERTING -> ProDLColors.Warning
    else -> ProDLColors.Indigo
}
