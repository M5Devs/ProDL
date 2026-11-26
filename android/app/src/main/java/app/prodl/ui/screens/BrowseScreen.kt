package app.prodl.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prodl.ui.BrowseViewModel
import app.prodl.ui.components.EmptyState
import app.prodl.ui.components.ErrorCard
import app.prodl.ui.components.LoadingBlock
import app.prodl.ui.components.VideoRow
import app.prodl.ui.theme.ProDLColors
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay

/** Channel uploads or playlist videos, reached from a Discover card or a link. */
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    url: String,
    kind: String,
    onOpenVideo: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(url, kind) { viewModel.load(url, kind) }

    val result = state.result
    when {
        state.loading -> LoadingBlock(Modifier.fillMaxSize(), "Loading ${if (kind == "playlist") "playlist" else "channel"}…")

        state.error != null || result == null -> Column(Modifier.padding(16.dp)) {
            ErrorCard(
                message = state.error ?: "Could not load this list.",
                onRetry = { viewModel.load(url, kind) },
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(ProDLColors.CardHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        val art = result.avatar ?: result.banner
                        if (art.isNullOrBlank()) {
                            Icon(
                                Icons.Filled.PlaylistPlay,
                                contentDescription = null,
                                tint = ProDLColors.TextDim,
                            )
                        } else {
                            AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = ProDLColors.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!result.subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = result.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = ProDLColors.TextDim,
                            )
                        }
                    }
                }
            }

            if (!result.description.isNullOrBlank()) {
                item {
                    Text(
                        text = result.description ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProDLColors.TextDim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (result.items.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing here",
                        message = "This list returned no videos. It may be private, or the extractor could not read it.",
                    )
                }
            }

            items(result.items, key = { it.id + it.url }) { item ->
                VideoRow(item, onClick = { onOpenVideo(item.url) })
            }
        }
    }
}
