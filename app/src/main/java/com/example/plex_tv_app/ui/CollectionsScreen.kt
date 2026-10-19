@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexCollection
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.ui.theme.palette
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.settings.CardText
import kotlinx.coroutines.CancellationException

/** One card on the Collections page: a collection, or same-named movie + TV collections together. */
class CollectionGroup(val members: List<PlexCollection>) {
    val primary: PlexCollection get() = members.first()
    val isMixed: Boolean get() = members.any { it.isTv } && members.any { !it.isTv }

    /** "8 movies, 3 shows" for a combined card; null for a plain one. */
    val mixedDetail: String?
        get() {
            if (!isMixed) return null
            val movies = members.filter { !it.isTv }.sumOf { it.count ?: 0 }
            val shows = members.filter { it.isTv }.sumOf { it.count ?: 0 }
            return listOf(
                if (movies == 1) "1 movie" else "$movies movies",
                if (shows == 1) "1 show" else "$shows shows",
            ).joinToString(", ")
        }
}

/** Which collections the page is showing. */
enum class CollectionFilter(val label: String) { All("All"), Movies("Movies"), Tv("TV") }

/** Remembers the filter and where you were, so Back from a collection returns to the same spot. */
class CollectionsMemory {
    var filter by mutableStateOf(CollectionFilter.All)
    val grid = GridMemory()
}

/**
 * Every collection from the movie and TV libraries, as posters, with a small All / Movies / TV
 * filter at the top. Selecting one opens its contents.
 */
@Composable
fun CollectionsScreen(
    api: PlexServerApi,
    reloadToken: Int,
    cardScale: Float,
    memory: CollectionsMemory,
    onOpen: (PlexCollection) -> Unit,
) {
    var all by remember { mutableStateOf<List<PlexCollection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api, reloadToken) {
        try {
            all = api.allCollections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (all == null) error = e.message ?: e.toString()
        }
    }

    // Explicit Up/Down between the filter and the grid: Up from the top row goes to the filter
    // that's selected; Down from the filter returns to the poster you were on.
    val chipFocus = remember { CollectionFilter.entries.associateWith { FocusRequester() } }
    val gridEntry = remember { FocusRequester() }
    var inChips by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableStateOf(-1) }
    var focusedColumn by remember { mutableStateOf(-1) }
    val openNav = LocalOpenNav.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 28.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (inChips) return@onPreviewKeyEvent true // nothing above the filter
                        if (focusedRow == 0) {
                            runCatching { chipFocus.getValue(memory.filter).requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!inChips) return@onPreviewKeyEvent false
                        runCatching { gridEntry.requestFocus() }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        // The start of a row (first filter, or first column of posters) opens the side nav.
                        val atStart = if (inChips) memory.filter == CollectionFilter.entries.first() else focusedColumn == 0
                        if (atStart) openNav?.invoke()
                        atStart
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                        inChips && memory.filter == CollectionFilter.entries.last() // and the last
                    else -> false
                }
            },
    ) {
        Text("Collections", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 48.dp))
        Spacer(Modifier.height(12.dp))

        // All / Movies / TV. Highlighting one switches straight away.
        Row(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CollectionFilter.entries.forEach { f ->
                FilterChip(
                    f.label,
                    selected = memory.filter == f,
                    modifier = Modifier.focusRequester(chipFocus.getValue(f)),
                    onFocused = { inChips = true },
                ) {
                    if (memory.filter != f) {
                        memory.filter = f
                        memory.grid.focusedKey = null
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // In All, a movie collection and a TV collection with the same name become one card.
        val list: List<CollectionGroup>? = all?.let { collections ->
            when (memory.filter) {
                CollectionFilter.All -> collections
                    .groupBy { it.title.trim().lowercase() }
                    .values
                    .map { group -> CollectionGroup(group.sortedBy { it.isTv }) } // movie collection first
                CollectionFilter.Movies -> collections.filter { !it.isTv }.map { CollectionGroup(listOf(it)) }
                CollectionFilter.Tv -> collections.filter { it.isTv }.map { CollectionGroup(listOf(it)) }
            }
        }
        val currentError = error
        when {
            currentError != null -> Text(currentError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 48.dp))
            list == null -> Text("Loading…", modifier = Modifier.padding(start = 48.dp, top = 12.dp))
            list.isEmpty() -> Text(
                if (memory.filter == CollectionFilter.All) "No collections yet. Create them in Plex and they'll show up here."
                else "No ${memory.filter.label} collections.",
                modifier = Modifier.padding(start = 48.dp, top = 12.dp),
                color = palette.muted,
            )
            else -> {
                val gridState = memory.grid.state
                val startIndex = memory.grid.focusedKey?.let { k -> list.indexOfFirst { it.primary.ratingKey == k } }?.takeIf { it >= 0 } ?: 0
                val first = remember { FocusRequester() }
                // Only on arrival: switching filters keeps focus on the filter.
                val focused = remember { mutableStateOf(false) }
                LaunchedEffect(memory.filter) {
                    if (memory.grid.focusedKey == null) gridState.scrollToItem(0)
                }
                LaunchedEffect(startIndex) {
                    if (startIndex == 0) return@LaunchedEffect
                    withFrameNanos { }
                    if (gridState.layoutInfo.visibleItemsInfo.none { it.index == startIndex }) gridState.scrollToItem(startIndex)
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(150.dp * cardScale),
                    // The page margin is padding inside the grid, so highlighted posters aren't clipped.
                    contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp, start = 48.dp, end = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(cardGap(24.dp)),
                    verticalArrangement = Arrangement.spacedBy(cardGap(26.dp)),
                ) {
                    itemsIndexed(list, key = { _, g -> g.primary.ratingKey }) { i, group ->
                        val collection = group.primary
                        CollectionCard(
                            api = api,
                            collection = collection,
                            showKind = memory.filter == CollectionFilter.All && !group.isMixed,
                            detailOverride = group.mixedDetail,
                            onClick = { onOpen(collection) },
                            cardModifier = (if (i == startIndex) Modifier.focusRequester(first).focusRequester(gridEntry) else Modifier)
                                .gridItem(gridState, i)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        inChips = false
                                        memory.grid.focusedKey = collection.ratingKey
                                        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info -> info.index == i }
                                        focusedRow = info?.row ?: -1
                                        focusedColumn = info?.column ?: -1
                                    }
                                },
                        )
                        if (i == startIndex) RequestInitialFocus(first, focused)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CollectionCard(
    api: PlexServerApi,
    collection: PlexCollection,
    showKind: Boolean,
    onClick: () -> Unit,
    cardModifier: Modifier,
    detailOverride: String? = null,
) {
    // Same as MediaCard: slide the text down while the poster is enlarged, so it isn't covered.
    var focused by remember { mutableStateOf(false) }
    var cardHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val textShift by animateFloatAsState(
        if (focused) cardHeight * 0.05f + with(density) { 3.dp.toPx() } else 0f,
        tween(180),
        label = "collectionTextShift",
    )
    Column {
        Card(
            onClick = onClick,
            modifier = cardModifier
                .confettiAnchor()
                .returnFocusHere()
                .onFocusChanged { focused = it.isFocused }
                .onSizeChanged { cardHeight = it.height }
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            RemoteImage(api.imageUrl(collection.thumb, 300, 450), Modifier.fillMaxSize())
        }
        val textMode = LocalSettings.current.cardText // Settings → Home & browsing → Text under cards
        if (textMode != CardText.None) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.graphicsLayer { translationY = textShift }) {
        Text(
            collection.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            overflow = overflowFor(focused),
            modifier = Modifier.scrollIfLong(focused),
        )
        val count = collection.count?.let { n ->
            val noun = if (collection.isTv) (if (n == 1) "show" else "shows") else (if (n == 1) "movie" else "movies")
            "$n $noun"
        }
        val detail = detailOverride ?: listOfNotNull(count, collection.libraryTitle.takeIf { showKind }).joinToString("  ")
        if (detail.isNotBlank() && textMode == CardText.Full) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 1,
                softWrap = false,
                overflow = overflowFor(focused),
                modifier = Modifier.scrollIfLong(focused),
            )
        }
        }
        }
    }
}

/** A small pill for the All / Movies / TV filter. */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        modifier = modifier
            .confettiAnchor()
            .returnFocusHere()
            .onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                    onSelect()
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.text.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = palette.text,
            contentColor = if (selected) palette.text else palette.muted,
            focusedContentColor = palette.background,
        ),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
