@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexCategory
import com.example.plex_tv_app.plex.PlexCollection
import com.example.plex_tv_app.plex.PlexHub
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexLibrary
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.ui.theme.PlexSerif
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import kotlin.math.roundToInt

enum class LibraryTab(val label: String) {
    Recommended("Recommended"), Browse("Browse"), Collections("Collections"), Categories("Categories")
}

/** Browse sort orders, as Plex sort keys. */
enum class BrowseSort(val label: String, val key: String) {
    Title("Title", "titleSort:asc"),
    Added("Recently added", "addedAt:desc"),
    Released("Release date", "originallyAvailableAt:desc"),
    Rating("Rating", "audienceRating:desc"),
}

/** Everything about where you were in one library, kept by the Shell so Back returns there. */
class LibraryMemory {
    var tab by mutableStateOf(LibraryTab.Recommended)
    var sort by mutableStateOf(BrowseSort.Title)
    var unwatchedOnly by mutableStateOf(false)
    val browse = GridMemory()
    val collections = GridMemory()
    val categories = GridMemory()
    internal val recommended = HomeMemory() // Recommended uses Home's layout, so it remembers like Home
    /** Recommended's rows as loaded (in Plex's order), for the hold-OK menu's Customise Rows. */
    var recommendedHubs by mutableStateOf<List<PlexHub>?>(null)
}

/** Left margin of the page. Rows and grids include it as padding, so a highlighted card can grow into it. */
private val PageMargin = 48.dp

/** Which part of the page has focus, for explicit Up/Down/Left handling. */
private enum class LibZone { Tabs, Chips, Content }

/**
 * A library, laid out like Plex: the name with four tabs. Recommended (where it opens) shows
 * Plex's rows for this library; Browse is everything, sortable and filterable; Collections and
 * Categories are grids you can open.
 */
@Composable
fun LibraryScreen(
    api: PlexServerApi,
    library: PlexLibrary,
    reloadToken: Int,
    cardScale: Float,
    memory: LibraryMemory,
    onOpen: (PlexItem) -> Unit,
    onOpenCollection: (PlexCollection) -> Unit,
    onOpenCategory: (PlexCategory) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
    rowOrder: List<String> = emptyList(), // Recommended's rows, in your order (see Customise Rows)
    rowStyles: Map<String, CardStyle> = emptyMap(), // Recommended's row styles
    onRowLongPress: ((PlexItem, rowKey: String, () -> Unit) -> Unit)? = null, // hold OK on a Recommended card
) {
    val openNav = LocalOpenNav.current

    // Focus bookkeeping: tabs, Browse's chips, and the content (with its row/column).
    var zone by remember { mutableStateOf(LibZone.Content) }
    var row by remember { mutableStateOf(0) }
    var column by remember { mutableStateOf(0) }
    var chipIndex by remember { mutableStateOf(0) }
    val tabFocus = remember { LibraryTab.entries.associateWith { FocusRequester() } }
    val chipEntry = remember { FocusRequester() } // the selected sort chip
    val contentEntry = remember { LibraryTab.entries.associateWith { FocusRequester() } }

    fun report(z: LibZone, r: Int = 0, c: Int = 0) {
        zone = z
        row = r
        column = c
    }
    fun tryFocus(r: FocusRequester) = runCatching { r.requestFocus() }.let { it.isSuccess && (it.getOrNull() as Any?) != false }

    val initialDone = remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val tab = memory.tab
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> when (zone) {
                        LibZone.Tabs -> true // nothing above the tabs
                        LibZone.Chips -> { tryFocus(tabFocus.getValue(tab)); true }
                        LibZone.Content -> if (row == 0) {
                            // Top row: up to Browse's sort chips, else to the selected tab.
                            if (tab == LibraryTab.Browse) tryFocus(chipEntry) else tryFocus(tabFocus.getValue(tab))
                            true
                        } else false
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> when (zone) {
                        LibZone.Tabs -> {
                            if (tab == LibraryTab.Browse) tryFocus(chipEntry) else tryFocus(contentEntry.getValue(tab))
                            true
                        }
                        LibZone.Chips -> { tryFocus(contentEntry.getValue(tab)); true }
                        LibZone.Content -> false
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        // The start of any row opens the side nav.
                        val atStart = when (zone) {
                            LibZone.Tabs -> tab == LibraryTab.entries.first()
                            LibZone.Chips -> chipIndex == 0
                            LibZone.Content -> column == 0
                        }
                        if (atStart) openNav?.invoke()
                        atStart
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> zone == LibZone.Tabs && tab == LibraryTab.entries.last()
                    else -> false
                }
            },
    ) {
        // Recommended looks like Home: artwork, the highlighted title's details, one row at a time.
        if (memory.tab == LibraryTab.Recommended) {
            RecommendedHome(
                api, library, reloadToken, cardScale, memory,
                entry = contentEntry.getValue(LibraryTab.Recommended),
                focusOnStart = !initialDone.value,
                onReport = { r, c ->
                    initialDone.value = true
                    report(LibZone.Content, r, c)
                },
                onOpen = onOpen,
                onLongPress = onLongPress,
                rowOrder = rowOrder,
                rowStyles = rowStyles,
                onRowLongPress = onRowLongPress,
            )
        }

        Column(Modifier.fillMaxSize().padding(top = 26.dp)) {
        Row(Modifier.padding(start = PageMargin), verticalAlignment = Alignment.CenterVertically) {
            Text(library.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Spacer(Modifier.width(28.dp))
            LibraryTabs(
                selected = memory.tab,
                requesters = tabFocus,
                onFocused = { t ->
                    report(LibZone.Tabs)
                    memory.tab = t
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        key(memory.tab) {
            when (memory.tab) {
                LibraryTab.Recommended -> Unit // drawn underneath, full screen
                LibraryTab.Browse -> BrowseTab(
                    api, library, reloadToken, cardScale, memory, chipEntry, contentEntry.getValue(LibraryTab.Browse),
                    initialDone,
                    onChip = { i -> report(LibZone.Chips); chipIndex = i },
                    onReport = { r, c -> report(LibZone.Content, r, c) },
                    onOpen, onLongPress,
                )
                LibraryTab.Collections -> CollectionsTab(
                    api, library, reloadToken, cardScale, memory, contentEntry.getValue(LibraryTab.Collections),
                    initialDone, onReport = { r, c -> report(LibZone.Content, r, c) }, onOpenCollection,
                )
                LibraryTab.Categories -> CategoriesTab(
                    api, library, reloadToken, memory, contentEntry.getValue(LibraryTab.Categories),
                    initialDone, onReport = { r, c -> report(LibZone.Content, r, c) }, onOpenCategory,
                )
            }
        }
        }
    }
}

// ---------- Tabs ----------

/** The tab row, with one gold underline gliding to the selected tab. Highlighting a tab opens it. */
@Composable
private fun LibraryTabs(
    selected: LibraryTab,
    requesters: Map<LibraryTab, FocusRequester>,
    onFocused: (LibraryTab) -> Unit,
) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<LibraryTab, Pair<Float, Float>>() }
    var rowX by remember { mutableFloatStateOf(0f) }
    Box(Modifier.onGloballyPositioned { rowX = it.positionInRoot().x }) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LibraryTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Surface(
                    onClick = { onFocused(tab) },
                    modifier = Modifier
                        .focusRequester(requesters.getValue(tab))
                        .confettiAnchor()
                        .returnFocusHere()
                        .onGloballyPositioned { bounds[tab] = it.positionInRoot().x to it.size.width.toFloat() }
                        .onFocusChanged { if (it.isFocused) onFocused(tab) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = palette.text,
                        contentColor = if (isSelected) palette.text else palette.muted,
                        focusedContentColor = palette.background,
                    ),
                ) {
                    Text(
                        tab.label,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        bounds[selected]?.let { (x, width) ->
            val line = with(density) { 20.dp.toPx() }
            val lineX by animateFloatAsState(
                x - rowX + (width - line) / 2f,
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "libraryTab",
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset(lineX.roundToInt(), 0) }
                    .padding(bottom = 2.dp)
                    .width(20.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(PlexGold)
            )
        }
    }
}

// ---------- Recommended ----------

/** Plex's Recommended rows for this library, shown with Home's layout under the library header. */
@Composable
private fun RecommendedHome(
    api: PlexServerApi,
    library: PlexLibrary,
    reloadToken: Int,
    cardScale: Float,
    memory: LibraryMemory,
    entry: FocusRequester,
    focusOnStart: Boolean,
    onReport: (row: Int, column: Int) -> Unit,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
    rowOrder: List<String>,
    rowStyles: Map<String, CardStyle>,
    onRowLongPress: ((PlexItem, String, () -> Unit) -> Unit)?,
) {
    var hubs by remember { mutableStateOf(memory.recommendedHubs) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(library.key, reloadToken) {
        try {
            hubs = api.libraryHubs(library.key)
            memory.recommendedHubs = hubs
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hubs == null) error = e.message ?: e.toString()
        }
    }
    val list = remember(hubs, rowOrder) { hubs?.let { orderRows(it, rowOrder) } }
    when {
        error != null -> Box(Modifier.padding(top = 90.dp)) { TabNote("Couldn't load recommendations. $error") }
        list == null -> Box(Modifier.padding(top = 90.dp)) { TabNote("Loading…") }
        list.isEmpty() -> Box(Modifier.padding(top = 90.dp)) { TabNote("Nothing to recommend yet. Try Browse.") }
        else -> HomeScreen(
            api = api,
            hubs = list,
            error = null,
            cardScale = cardScale,
            rowStyles = rowStyles,
            onOpen = onOpen,
            onLongPress = { item, rowKey, restore ->
                if (onRowLongPress != null) onRowLongPress(item, rowKey, restore) else onLongPress(item, restore)
            },
            memory = memory.recommended,
            topInset = 62.dp, // the library name and tabs
            entry = entry,
            focusOnStart = focusOnStart,
            onFocusInfo = onReport,
        )
    }
}

// ---------- Browse ----------

@Composable
private fun BrowseTab(
    api: PlexServerApi,
    library: PlexLibrary,
    reloadToken: Int,
    cardScale: Float,
    memory: LibraryMemory,
    chipEntry: FocusRequester,
    entry: FocusRequester,
    initialDone: androidx.compose.runtime.MutableState<Boolean>,
    onChip: (index: Int) -> Unit,
    onReport: (row: Int, column: Int) -> Unit,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    // The whole library loads once; sorting and filtering happen right here, so switching is
    // instant and the posters can glide to their new places.
    var all by remember { mutableStateOf<List<PlexItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(library.key, reloadToken) {
        try {
            all = api.items("/library/sections/${library.key}/all")
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (all == null) error = e.message ?: e.toString()
        }
    }
    val items = remember(all, memory.sort, memory.unwatchedOnly) {
        all?.let { sortAndFilter(it, memory.sort, memory.unwatchedOnly) }
    }

    fun changeSort(update: () -> Unit) {
        // Pin the grid to the top *before* the order changes. Otherwise the grid keeps its place by
        // following the first poster to wherever it moved, then has to scroll back up.
        memory.browse.state.requestScrollToItem(0)
        memory.browse.focusedKey = null
        update()
    }

    // Sort and filter chips. Highlighting one switches to it.
    Row(
        Modifier.padding(start = PageMargin - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BrowseSort.entries.forEachIndexed { i, sort ->
            val pick = { if (memory.sort != sort) changeSort { memory.sort = sort } }
            Chip(
                sort.label,
                selected = memory.sort == sort,
                modifier = if (memory.sort == sort) Modifier.focusRequester(chipEntry) else Modifier,
                onFocused = {
                    onChip(i)
                    pick()
                },
            ) { pick() }
        }
        Spacer(Modifier.width(18.dp))
        listOf(false to "All", true to "Unwatched").forEachIndexed { j, (unwatched, label) ->
            val pick = { if (memory.unwatchedOnly != unwatched) changeSort { memory.unwatchedOnly = unwatched } }
            Chip(
                label,
                selected = memory.unwatchedOnly == unwatched,
                onFocused = {
                    onChip(BrowseSort.entries.size + j)
                    pick()
                },
            ) { pick() }
        }
        Spacer(Modifier.width(18.dp))
        items?.let {
            val noun = if (library.type == "show") (if (it.size == 1) "show" else "shows") else (if (it.size == 1) "movie" else "movies")
            Text("${it.size} $noun", fontSize = 13.sp, color = palette.muted)
        }

    }

    val list = items
    when {
        error != null -> TabNote("Couldn't load this library. $error")
        list == null -> TabNote("Loading…")
        list.isEmpty() -> TabNote(if (memory.unwatchedOnly) "You've watched everything here." else "This library is empty.")
        else -> PosterGrid(
            count = list.size,
            key = { list[it].ratingKey },
            cardScale = cardScale,
            memory = memory.browse,
            entry = entry,
            initialDone = initialDone,
            resetKey = "${memory.sort}|${memory.unwatchedOnly}",
            onReport = onReport,
        ) { i, mod ->
            val item = list[i]
            MediaCard(
                api = api,
                item = item,
                style = CardStyle.Poster,
                standalone = false,
                onClick = { onOpen(item) },
                onLongClick = { restore -> onLongPress(item, restore) },
                onFocused = { memory.browse.focusedKey = item.ratingKey },
                cardModifier = mod,
            )
        }
    }
}

/** Browse's sort and Unwatched filter, done on the TV. Titles with no value for the sort go last. */
private fun sortAndFilter(items: List<PlexItem>, sort: BrowseSort, unwatchedOnly: Boolean): List<PlexItem> {
    val pool = if (unwatchedOnly) items.filterNot { it.isWatched } else items
    fun titleKey(i: PlexItem) = (i.titleSort ?: i.title).lowercase()
    return when (sort) {
        BrowseSort.Title -> pool.sortedBy(::titleKey)
        BrowseSort.Added -> pool.sortedWith(compareByDescending<PlexItem> { it.addedAt ?: Long.MIN_VALUE }.thenBy(::titleKey))
        BrowseSort.Released -> pool.sortedWith(compareByDescending<PlexItem> { it.releaseDate ?: "" }.thenBy(::titleKey))
        BrowseSort.Rating -> pool.sortedWith(compareByDescending<PlexItem> { it.audienceRating ?: -1f }.thenBy(::titleKey))
    }
}

// ---------- Collections ----------

@Composable
private fun CollectionsTab(
    api: PlexServerApi,
    library: PlexLibrary,
    reloadToken: Int,
    cardScale: Float,
    memory: LibraryMemory,
    entry: FocusRequester,
    initialDone: androidx.compose.runtime.MutableState<Boolean>,
    onReport: (row: Int, column: Int) -> Unit,
    onOpenCollection: (PlexCollection) -> Unit,
) {
    var collections by remember { mutableStateOf<List<PlexCollection>?>(null) }
    LaunchedEffect(library.key, reloadToken) {
        collections = runCatching { api.libraryCollections(library) }.getOrDefault(emptyList())
    }
    val list = collections
    when {
        list == null -> TabNote("Loading…")
        list.isEmpty() -> TabNote("No collections in ${library.title} yet.")
        else -> PosterGrid(
            count = list.size,
            key = { list[it].ratingKey },
            cardScale = cardScale,
            memory = memory.collections,
            entry = entry,
            initialDone = initialDone,
            resetKey = library.key,
            onReport = onReport,
        ) { i, mod ->
            val collection = list[i]
            CollectionCard(
                api = api,
                collection = collection,
                showKind = false,
                onClick = { onOpenCollection(collection) },
                cardModifier = mod.onFocusChanged { if (it.isFocused) memory.collections.focusedKey = collection.ratingKey },
            )
        }
    }
}

// ---------- Categories ----------

@Composable
private fun CategoriesTab(
    api: PlexServerApi,
    library: PlexLibrary,
    reloadToken: Int,
    memory: LibraryMemory,
    entry: FocusRequester,
    initialDone: androidx.compose.runtime.MutableState<Boolean>,
    onReport: (row: Int, column: Int) -> Unit,
    onOpenCategory: (PlexCategory) -> Unit,
) {
    var categories by remember { mutableStateOf<List<PlexCategory>?>(null) }
    LaunchedEffect(library.key, reloadToken) {
        categories = runCatching { api.libraryCategories(library.key) }.getOrDefault(emptyList())
    }
    val list = categories
    when {
        list == null -> TabNote("Loading…")
        list.isEmpty() -> TabNote("No categories in ${library.title}.")
        else -> PosterGrid(
            count = list.size,
            key = { list[it].path },
            cardScale = 1.4f, // tiles are wider than posters
            memory = memory.categories,
            entry = entry,
            initialDone = initialDone,
            resetKey = library.key,
            onReport = onReport,
        ) { i, mod ->
            val category = list[i]
            CategoryTile(category, mod.onFocusChanged { if (it.isFocused) memory.categories.focusedKey = category.path }) {
                onOpenCategory(category)
            }
        }
    }
}

/** A category tile: its name over a soft wash of colour picked from the name, so each looks different. */
@Composable
private fun CategoryTile(category: PlexCategory, modifier: Modifier, onClick: () -> Unit) {
    val tones = listOf(
        Color(0xFFE5A00D), Color(0xFF6FA8FF), Color(0xFFFF7A3D), Color(0xFF9CCB6B),
        Color(0xFFB48CFF), Color(0xFFFF8FAB), Color(0xFF2EC4B6), Color(0xFF88C0D0),
    )
    val tone = tones[abs(category.title.hashCode()) % tones.size]
    Surface(
        onClick = onClick,
        modifier = modifier.confettiAnchor().returnFocusHere().fillMaxWidth().height(86.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.raised,
            focusedContainerColor = palette.raised,
            contentColor = palette.text,
            focusedContentColor = palette.text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(2.dp, palette.text),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(tone.copy(alpha = 0.55f), tone.copy(alpha = 0.08f))))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                category.title,
                fontFamily = PlexSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------- Shared ----------

/**
 * A grid that remembers where you were, glides as you move, tells the page which row/column has
 * focus, and starts on the remembered (or first) item.
 */
@Composable
private fun PosterGrid(
    count: Int,
    key: (Int) -> String,
    cardScale: Float,
    memory: GridMemory,
    entry: FocusRequester,
    initialDone: androidx.compose.runtime.MutableState<Boolean>,
    resetKey: String,
    onReport: (row: Int, column: Int) -> Unit,
    cell: @Composable (index: Int, cardModifier: Modifier) -> Unit,
) {
    val state = memory.state
    val startIndex = memory.focusedKey?.let { k -> (0 until count).firstOrNull { key(it) == k } } ?: 0
    LaunchedEffect(startIndex) {
        if (startIndex == 0) return@LaunchedEffect
        withFrameNanos { }
        if (state.layoutInfo.visibleItemsInfo.none { it.index == startIndex }) state.scrollToItem(startIndex)
    }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(150.dp * cardScale),
        contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp, start = PageMargin, end = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(cardGap(22.dp)),
        verticalArrangement = Arrangement.spacedBy(cardGap(24.dp)),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(List(count) { it }, key = { _, i -> key(i) }) { _, i ->
            val mod = (if (i == startIndex) Modifier.focusRequester(entry) else Modifier)
                .gridItem(state, i)
                .onFocusChanged {
                    if (it.isFocused) {
                        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { v -> v.index == i }
                        onReport(info?.row ?: 1, info?.column ?: 1)
                    }
                }
            // Posters glide to their new spots when the order changes, and fade in/out with the filter.
            Box(Modifier.animateItem()) { cell(i, mod) }
            if (i == startIndex) RequestInitialFocus(entry, initialDone)
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .confettiAnchor()
            .returnFocusHere()
            .onFocusChanged { if (it.isFocused) onFocused() },
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
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TabNote(text: String) {
    Text(text, fontSize = 15.sp, color = palette.muted, modifier = Modifier.padding(start = PageMargin, top = 16.dp))
}
