package app.prodl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.prodl.core.SearchItem
import app.prodl.ui.formatCount
import app.prodl.ui.formatDuration
import app.prodl.ui.theme.ProDLColors
import coil3.compose.AsyncImage

/** 16:9 thumbnail with a soft placeholder so the feed never flashes empty. */
@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    badge: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(ProDLColors.CardHigh, ProDLColors.Card),
                )
            ),
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = ProDLColors.Text,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ProDLColors.Background.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** One video in a list: thumbnail, title, channel and the numbers. */
@Composable
fun VideoRow(
    item: SearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ProDLColors.Card),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            Thumbnail(
                url = item.thumbnail,
                badge = if (item.isLive) "LIVE" else formatDuration(item.duration),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = item.title,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = ProDLColors.Text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        item.uploader?.takeIf { it.isNotBlank() },
                        formatCount(item.viewCount)?.let { "$it views" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact horizontal row for related videos under the player. */
@Composable
fun RelatedRow(
    title: String,
    thumbnail: String?,
    subtitle: String?,
    duration: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Thumbnail(
            url = thumbnail,
            badge = formatDuration(duration),
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = ProDLColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ChannelRow(
    item: SearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ProDLColors.Card),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(ProDLColors.CardHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (item.thumbnail.isNullOrBlank()) {
                    Icon(Icons.Outlined.Person, null, tint = ProDLColors.TextDim)
                } else {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProDLColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatCount(item.itemCount)?.let { "$it subscribers" } ?: "Channel",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                )
            }
        }
    }
}

@Composable
fun PlaylistRow(
    item: SearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ProDLColors.Card),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Box {
                Thumbnail(url = item.thumbnail, modifier = Modifier.width(132.dp))
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ProDLColors.Background.copy(alpha = 0.7f))
                        .padding(6.dp),
                ) {
                    Icon(Icons.Filled.PlaylistPlay, null, tint = ProDLColors.Text)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ProDLColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        item.uploader?.takeIf { it.isNotBlank() },
                        formatCount(item.itemCount)?.let { "$it videos" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun ErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ProDLColors.Card),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, tint = ProDLColors.Danger)
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = ProDLColors.TextDim,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.SearchOff,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = ProDLColors.TextDim, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = ProDLColors.TextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = ProDLColors.Primary)
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = ProDLColors.Text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
