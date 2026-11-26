package app.prodl.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prodl.core.MediaTargets
import app.prodl.ui.ConvertViewModel
import app.prodl.ui.DownloadsViewModel
import app.prodl.ui.components.StatusPill
import app.prodl.ui.label
import app.prodl.ui.theme.ProDLColors
import kotlinx.coroutines.launch

/** Pick any device media file and transcode it with the on-device pipeline. */
@Composable
fun ConvertScreen(
    viewModel: ConvertViewModel,
    downloads: DownloadsViewModel,
    snackbar: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tasks by downloads.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pick(context, uri, downloads)
    }

    val converts = remember(tasks) { tasks.filter { it.kind == "convert" } }
    val isAudio = MediaTargets.isAudio(state.format)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ProDLColors.Card)
                .padding(16.dp),
        ) {
            Text("Source", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.inputName ?: "Choose a downloaded file or any media on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = ProDLColors.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.inputName == null) "Pick a file" else "Pick a different file")
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ProDLColors.Card)
                .padding(16.dp),
        ) {
            Text("Output", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("mp3", "m4a", "wav", "mp4", "webm").forEach { format ->
                    FilterChip(
                        selected = state.format == format,
                        onClick = { viewModel.setFormat(format) },
                        label = { Text(format.uppercase()) },
                    )
                }
            }
            if (isAudio) {
                Spacer(Modifier.height(12.dp))
                Text("Bitrate", style = MaterialTheme.typography.labelLarge, color = ProDLColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("128k", "192k", "256k", "320k").forEach { bitrate ->
                        FilterChip(
                            selected = state.bitrate == bitrate,
                            onClick = { viewModel.setBitrate(bitrate) },
                            label = { Text(bitrate) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentCut, null, tint = ProDLColors.TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trim (optional)", style = MaterialTheme.typography.labelLarge, color = ProDLColors.TextDim)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.start,
                    onValueChange = viewModel::setStart,
                    label = { Text("Start") },
                    placeholder = { Text("0:00") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.end,
                    onValueChange = viewModel::setEnd,
                    label = { Text("End") },
                    placeholder = { Text("0:30") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.convert(downloads)
                    scope.launch { snackbar.showSnackbar("Conversion started — track it below") }
                },
                enabled = state.staged != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Convert to ${state.format.uppercase()}")
            }

            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = ProDLColors.Danger)
            }
            state.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = ProDLColors.Ready)
            }
        }

        if (converts.isNotEmpty()) {
            Text("Recent conversions", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
            converts.take(6).forEach { task ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ProDLColors.Card)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = task.file?.filename ?: task.lastEvent?.title ?: "Conversion",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = ProDLColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = task.error ?: task.lastEvent?.message ?: "Working…",
                            style = MaterialTheme.typography.bodySmall,
                            color = ProDLColors.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(task.status.label(), ProDLColors.Indigo)
                }
            }
            TextButton(onClick = { converts.forEach { downloads.remove(it.taskId) } }) {
                Text("Clear finished", color = ProDLColors.TextDim)
            }
        }
    }
}
