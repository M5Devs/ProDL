package app.prodl.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MimeTypes
import app.prodl.core.DownloadRequest
import app.prodl.core.InfoResponse
import app.prodl.ui.VideoSurface
import app.prodl.ui.WatchViewModel
import app.prodl.ui.components.ErrorCard
import app.prodl.ui.components.LoadingBlock
import app.prodl.ui.components.RelatedRow
import app.prodl.ui.components.SectionTitle
import app.prodl.ui.components.StatusPill
import app.prodl.ui.formatCount
import app.prodl.ui.formatDuration
import app.prodl.ui.openFullscreen
import app.prodl.ui.playInSession
import app.prodl.ui.rememberMediaController
import app.prodl.ui.shareLink
import app.prodl.ui.theme.ProDLColors

/**
 * LibreTube-style watch screen: the Media3 surface pinned at the top, info and
 * actions under it, and the extractor's related list below. Tapping a related
 * video replaces the current one without leaving the screen.
 */
@Composable
fun WatchScreen(
    viewModel: WatchViewModel,
    url: String,
    onOpenVideo: (String) -> Unit,
    onStartDownload: (DownloadRequest) -> Unit,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberMediaController()
    val context = LocalContext.current
    var audioOnly by remember(url) { mutableStateOf(false) }
    var sheetInfo by remember(url) { mutableStateOf<InfoResponse?>(null) }
    var sheetAudioMode by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) { viewModel.load(url) }

    val info = state.info
    LaunchedEffect(info?.webpageUrl, audioOnly, controller) {
        val media = info ?: return@LaunchedEffect
        val session = controller ?: return@LaunchedEffect
        val target = if (audioOnly) media.audioSource() else media.videoSource()
        playInSession(
            controller = session,
            url = target.first ?: return@LaunchedEffect,
            title = media.title,
            artist = media.uploader,
            thumbnail = media.thumbnail,
            audioOnly = audioOnly,
            segments = media.sponsorblock.segments,
            mimeType = target.second,
        )
    }

    sheetInfo?.let { media ->
        FormatSheet(
            info = media,
            startInAudioMode = sheetAudioMode,
            onDismiss = { sheetInfo = null },
            onDownload = onStartDownload,
        )
    }

    when {
        state.loading -> LoadingBlock(Modifier.fillMaxSize(), "Extracting video…")

        state.error != null || info == null -> Column(Modifier.padding(16.dp)) {
            ErrorCard(
                message = state.error ?: "This video could not be extracted.",
                onRetry = { viewModel.load(url) },
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            item {
                Box {
                    if (audioOnly) {
                        AudioSurface(title = info.title, subtitle = info.uploader)
                    } else {
                        VideoSurface(
                            controller = controller,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black),
                        )
                    }
                    TextButton(
                        onClick = {
                            val target = if (audioOnly) info.audioSource() else info.videoSource()
                            openFullscreen(
                                context = context,
                                url = target.first.orEmpty(),
                                title = info.title,
                                thumbnail = info.thumbnail,
                                audioOnly = audioOnly,
                                segments = info.sponsorblock.segments,
                                mimeType = target.second,
                            )
                        },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen", tint = Color.White)
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = info.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ProDLColors.Text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = listOfNotNull(
                            info.uploader,
                            formatCount(info.viewCount)?.let { "$it views" },
                            formatDuration(info.duration),
                            info.uploadDate,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ProDLColors.TextDim,
                    )
                    if (info.isLive) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill("Live", ProDLColors.Danger)
                    }
                    if (info.ageLimit >= 18) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill("Age restricted", ProDLColors.Warning)
                    }
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            sheetAudioMode = false
                            sheetInfo = info
                        },
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                    AssistChip(
                        onClick = {
                            sheetAudioMode = true
                            sheetInfo = info
                        },
                        label = { Text("Audio only") },
                        leadingIcon = { Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp)) },
                    )
                    AssistChip(
                        onClick = { shareLink(context, info.webpageUrl, info.title) },
                        label = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) },
                    )
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { audioOnly = !audioOnly },
                        label = { Text(if (audioOnly) "Show video" else "Audio only playback") },
                    )
                }
            }

            if (info.sponsorblock.segments.isNotEmpty()) {
                item {
                    Text(
                        text = "SponsorBlock: skipping ${info.sponsorblock.segments.size} segment(s) — " +
                            info.sponsorblock.segments.map { it.category }.distinct().joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ProDLColors.Ready,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (!info.description.isNullOrBlank()) {
                item {
                    Text(
                        text = info.description ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProDLColors.TextDim,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionTitle("Related", Modifier.padding(horizontal = 12.dp)) }

            if (info.related.isEmpty()) {
                item {
                    Text(
                        "No related videos for this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProDLColors.TextDim,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items(info.related, key = { it.id + it.url }) { related ->
                RelatedRow(
                    title = related.title,
                    thumbnail = related.thumbnail,
                    subtitle = related.uploader,
                    duration = related.duration,
                    onClick = { onOpenVideo(related.url) },
                )
            }
        }
    }
}

/**
 * What to hand the player, with the MIME type ExoPlayer needs for manifests.
 * A progressive (muxed) stream plays as-is; when YouTube only ships separate
 * tracks the HLS manifest is the only URL that carries both.
 */
private fun InfoResponse.videoSource(): Pair<String?, String?> = when {
    !playbackUrl.isNullOrBlank() && playbackUrl == hlsUrl -> playbackUrl to MimeTypes.APPLICATION_M3U8
    !playbackUrl.isNullOrBlank() && playbackUrl == dashMpdUrl -> playbackUrl to MimeTypes.APPLICATION_MPD
    !playbackUrl.isNullOrBlank() -> playbackUrl to null
    else -> bestVideoUrl to null
}

private fun InfoResponse.audioSource(): Pair<String?, String?> =
    (bestAudioUrl ?: bestVideoUrl) to null

/** Placeholder shown instead of the video surface in audio-only mode. */
@Composable
private fun AudioSurface(title: String, subtitle: String?) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(ProDLColors.CardHigh),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                tint = ProDLColors.Indigo,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = ProDLColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
            }
        }
    }
}
