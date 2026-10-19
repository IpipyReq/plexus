package com.example.plex_tv_app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi
import kotlinx.coroutines.CancellationException

/** Where you were in a grid (scroll position and highlighted item), kept while you open things. */
class GridMemory {
    val state = LazyGridState()
    var focusedKey: String? = null
}

/** A grid of posters: a whole library, a show's seasons, or a season's episodes. */
@Composable
fun ItemsScreen(
    api: PlexServerApi,
    title: String,
    path: String,
    reloadToken: Int,
    cardScale: Float,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, restoreFocus: () -> Unit) -> Unit,
    focusKey: String? = null, // start on this item instead of the first
    memory: GridMemory? = null, // remembers where you were, for coming back with Back
) {
    var items by remember { mutableStateOf<List<PlexItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Re-runs when reloadToken changes (e.g. after marking something watched).
    LaunchedEffect(path, reloadToken) {
        try {
            items = api.items(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 28.dp)) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            items?.let {
                Text(
                    "${it.size} items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val list = items
        val currentError = error
        when {
            currentError != null -> Text(currentError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 48.dp))
            list == null -> Text("Loading…", modifier = Modifier.padding(start = 48.dp))
            list.isEmpty() -> Text("Nothing here yet.", modifier = Modifier.padding(start = 48.dp))
            else -> {
                val landscape = list.first().type == "episode"
                val first = remember { FocusRequester() }
                val focused = remember { mutableStateOf(false) }
                val wanted = focusKey ?: memory?.focusedKey
                val startIndex = wanted?.let { key -> list.indexOfFirst { it.ratingKey == key } }?.takeIf { it >= 0 } ?: 0
                val gridState = memory?.state ?: rememberLazyGridState()
                // Bring the chosen item into view if it isn't already; it takes focus as soon as it appears.
                LaunchedEffect(startIndex) {
                    if (startIndex == 0) return@LaunchedEffect
                    withFrameNanos { }
                    if (gridState.layoutInfo.visibleItemsInfo.none { it.index == startIndex }) gridState.scrollToItem(startIndex)
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive((if (landscape) 260.dp else 150.dp) * cardScale),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp, start = 48.dp, end = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    itemsIndexed(list, key = { _, item -> item.ratingKey }) { i, item ->
                        MediaCard(
                            api = api,
                            item = item,
                            style = if (landscape) CardStyle.Thumbnail else CardStyle.Poster,
                            standalone = false,
                            onClick = { onOpen(item) },
                            onLongClick = { restore -> onLongPress(item, restore) },
                            onFocused = { memory?.focusedKey = item.ratingKey },
                            cardModifier = (if (i == startIndex) Modifier.focusRequester(first) else Modifier)
                                .gridItem(gridState, i),
                        )
                        if (i == startIndex) RequestInitialFocus(first, focused)
                    }
                }
            }
        }
    }
}
