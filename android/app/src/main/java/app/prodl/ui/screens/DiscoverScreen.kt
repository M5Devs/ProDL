package app.prodl.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prodl.core.InputParser
import app.prodl.ui.DiscoverViewModel
import app.prodl.ui.components.ChannelRow
import app.prodl.ui.components.EmptyState
import app.prodl.ui.components.ErrorCard
import app.prodl.ui.components.LoadingBlock
import app.prodl.ui.components.PlaylistRow
import app.prodl.ui.components.VideoRow
import app.prodl.ui.theme.ProDLColors

private val TYPES = listOf("all" to "All", "videos" to "Videos", "channels" to "Channels", "playlists" to "Playlists")

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenVideo: (String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.openUrl, state.openKind) {
        val url = state.openUrl ?: return@LaunchedEffect
        when (state.openKind) {
            InputParser.Kind.VIDEO -> onOpenVideo(url)
            InputParser.Kind.CHANNEL -> onOpenBrowse(url, "channel")
            InputParser.Kind.PLAYLIST -> onOpenBrowse(url, "playlist")
            else -> onOpenVideo(url)
        }
        viewModel.consumeOpen()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text("Search YouTube or paste a link") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    state.searching -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ProDLColors.Primary,
                    )

                    state.query.isNotEmpty() -> IconButton(onClick = viewModel::clearSearch) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ProDLColors.Primary,
                unfocusedBorderColor = ProDLColors.Outline,
                focusedContainerColor = ProDLColors.Card,
                unfocusedContainerColor = ProDLColors.Card,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    viewModel.submit(state.query)
                },
            ),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TYPES) { (value, label) ->
                FilterChip(
                    selected = state.type == value,
                    onClick = { viewModel.selectType(value) },
                    label = { Text(label) },
                )
            }
        }

        if (state.query.isBlank() && state.kiosks.size > 1) {
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.kiosks) { kiosk ->
                    FilterChip(
                        selected = state.kiosk == kiosk.id,
                        onClick = { viewModel.selectKiosk(kiosk.id) },
                        label = { Text(kiosk.label) },
                    )
                }
            }
        }

        when {
            state.loading || state.searching -> LoadingBlock(
                modifier = Modifier.fillMaxSize(),
                label = if (state.searching) "Searching…" else "Loading ${kioskLabel(state.kiosk)}…",
            )

            state.error != null -> Column(Modifier.padding(16.dp)) {
                ErrorCard(
                    message = state.error ?: "",
                    onRetry = {
                        if (state.query.isBlank()) viewModel.loadFeed() else viewModel.search(state.query)
                    },
                )
            }

            state.items.isEmpty() -> EmptyState(
                title = if (state.query.isBlank()) "This feed is empty" else "No results for \u201C${state.query}\u201D",
                message = state.note
                    ?: if (state.query.isBlank()) {
                        "Pull a different feed above, or search for something specific."
                    } else {
                        "Check the spelling, or try the All filter."
                    },
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.note != null) {
                    item {
                        Text(
                            text = state.note ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = ProDLColors.TextDim,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                items(state.items, key = { it.kind + it.id + it.url }) { item ->
                    when (item.kind) {
                        "channel" -> ChannelRow(item, onClick = { onOpenBrowse(item.url, "channel") })
                        "playlist" -> PlaylistRow(item, onClick = { onOpenBrowse(item.url, "playlist") })
                        else -> VideoRow(item, onClick = { onOpenVideo(item.url) })
                    }
                }
            }
        }
    }
}

private fun kioskLabel(id: String): String = when (id) {
    "live" -> "Live"
    "trending_gaming" -> "Gaming"
    "trending_podcasts_episodes" -> "Podcasts"
    else -> "Discover"
}

/** Small header used by the browse screens. */
@Composable
fun BrowseHeader(title: String, subtitle: String?, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = ProDLColors.Text)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
            }
        }
        trailing?.invoke()
    }
}
