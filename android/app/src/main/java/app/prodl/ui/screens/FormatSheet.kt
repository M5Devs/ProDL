package app.prodl.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.prodl.core.DownloadRequest
import app.prodl.core.InfoResponse
import app.prodl.core.MediaTargets
import app.prodl.core.VideoFormatDto
import app.prodl.ui.components.StatusPill
import app.prodl.ui.components.Thumbnail
import app.prodl.ui.formatCount
import app.prodl.ui.theme.ProDLColors

/**
 * Seal-style quality sheet: one clear row per resolution, an audio-only mode
 * and the post-processing switches. Formats whose ids start with `np:` come
 * from NewPipeExtractor, which downloads a single track — the switches that
 * need yt-dlp are disabled there instead of being silently ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(
    info: InfoResponse,
    startInAudioMode: Boolean = false,
    onDismiss: () -> Unit,
    onDownload: (DownloadRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = remember(info) { videoPresets(info) }
    val onDevice = remember(info) { info.formats.any { it.formatId.startsWith("np:") } }

    var audioOnly by remember { mutableStateOf(startInAudioMode || presets.isEmpty()) }
    var audioFormat by remember { mutableStateOf("mp3") }
    var audioBitrate by remember { mutableStateOf("192") }
    var selected by remember { mutableStateOf(presets.firstOrNull()?.formatId) }
    var embedThumbnail by remember { mutableStateOf(!onDevice) }
    var embedSubtitles by remember { mutableStateOf(false) }
    var cutSponsorBlock by remember { mutableStateOf(!onDevice) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ProDLColors.Card,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(url = info.thumbnail, modifier = Modifier.width(96.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = info.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ProDLColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOfNotNull(
                            info.uploader,
                            formatCount(info.viewCount)?.let { "$it views" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ProDLColors.TextDim,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Download",
                    style = MaterialTheme.typography.titleMedium,
                    color = ProDLColors.Text,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(if (audioOnly) "Audio" else "Video", ProDLColors.Indigo)
            }

            Spacer(Modifier.height(8.dp))
            ModeChipRow(
                audioOnly = audioOnly,
                hasVideo = presets.isNotEmpty(),
                onVideo = { audioOnly = false },
                onAudio = { audioOnly = true },
            )

            if (!audioOnly) {
                Spacer(Modifier.height(14.dp))
                presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        selected = preset.formatId == selected,
                        onClick = { selected = preset.formatId },
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Text("Format", style = MaterialTheme.typography.labelLarge, color = ProDLColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "m4a", "wav").forEach { format ->
                        FilterChip(
                            selected = audioFormat == format,
                            onClick = { audioFormat = format },
                            label = { Text(format.uppercase()) },
                        )
                    }
                }
                if (audioFormat != "wav") {
                    Spacer(Modifier.height(10.dp))
                    Text("Bitrate", style = MaterialTheme.typography.labelLarge, color = ProDLColors.TextDim)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("128", "192", "256", "320").forEach { kbps ->
                            FilterChip(
                                selected = audioBitrate == kbps,
                                onClick = { audioBitrate = kbps },
                                label = { Text("$kbps kbps") },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = ProDLColors.Outline)
            Spacer(Modifier.height(8.dp))

            SwitchRow(
                title = "Embed thumbnail",
                subtitle = "Write the cover art into the file",
                checked = embedThumbnail,
                enabled = !onDevice,
                onChange = { embedThumbnail = it },
            )
            SwitchRow(
                title = "Embed subtitles",
                subtitle = if (info.subtitles.isEmpty()) {
                    "No subtitle tracks on this video"
                } else {
                    info.subtitles.joinToString(", ") { it.lang }.take(60)
                },
                checked = embedSubtitles,
                enabled = !onDevice && info.subtitles.isNotEmpty(),
                onChange = { embedSubtitles = it },
            )
            SwitchRow(
                title = "Cut SponsorBlock segments",
                subtitle = if (info.sponsorblock.segments.isEmpty()) {
                    "No segments reported for this video"
                } else {
                    "${info.sponsorblock.segments.size} segments · playback skips them too"
                },
                checked = cutSponsorBlock,
                enabled = !onDevice && info.sponsorblock.segments.isNotEmpty(),
                onChange = { cutSponsorBlock = it },
            )

            if (onDevice) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "On-device YouTube downloads keep the original track, so the switches above are " +
                        "off. SponsorBlock still skips segments during playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onDownload(
                        DownloadRequest(
                            url = info.webpageUrl,
                            formatId = if (audioOnly) null else selected,
                            audioOnly = audioOnly,
                            audioFormat = audioFormat,
                            audioQuality = audioBitrate,
                            subtitles = embedSubtitles,
                            embedSubtitles = embedSubtitles,
                            thumbnail = embedThumbnail,
                            embedThumbnail = embedThumbnail,
                            sponsorblock = cutSponsorBlock,
                            sponsorblockAction = "cut",
                        )
                    )
                    onDismiss()
                },
                enabled = audioOnly || selected != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (audioOnly) "Download audio" else "Download video")
            }
        }
    }
}

private data class Preset(
    val formatId: String,
    val label: String,
    val detail: String,
    val size: String?,
    val height: Int,
)

/** Collapse the extractor's many itags into one row per resolution. */
private fun videoPresets(info: InfoResponse): List<Preset> = info.formats
    .filter { it.hasVideo && !it.directUrl.isNullOrBlank() }
    .groupBy { it.resolution ?: "video" }
    .map { (label, formats) ->
        val best = formats.maxByOrNull { (it.height ?: 0) * 1000.0 + (it.tbr ?: 0.0) } ?: formats.first()
        Preset(
            formatId = best.formatId,
            label = label,
            detail = listOfNotNull(
                if (best.hasAudio) "video + audio" else "video only",
                best.note?.split(" · ")?.getOrNull(1),
                if (best.ext.isNotBlank()) best.ext.uppercase() else null,
            ).joinToString(" · "),
            size = MediaTargets.humanSize(best.filesize),
            height = best.height ?: 0,
        )
    }
    .sortedByDescending { it.height }

@Composable
private fun PresetRow(preset: Preset, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ProDLColors.PrimaryDim else ProDLColors.CardHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) ProDLColors.Primary else ProDLColors.TextDim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.label, style = MaterialTheme.typography.titleSmall, color = ProDLColors.Text)
            Text(preset.detail, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
        }
        if (preset.size != null) {
            Text(preset.size, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ModeChipRow(audioOnly: Boolean, hasVideo: Boolean, onVideo: () -> Unit, onAudio: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !audioOnly,
            onClick = onVideo,
            enabled = hasVideo,
            label = { Text("Video") },
        )
        FilterChip(
            selected = audioOnly,
            onClick = onAudio,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Audio only")
                }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) ProDLColors.Text else ProDLColors.TextDim,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Small spinner used while the sheet's parent is still extracting. */
@Composable
fun SheetLoading() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = ProDLColors.Primary)
    }
}
