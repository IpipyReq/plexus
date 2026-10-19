@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.arr.ArrClient
import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.arr.ArrTitle
import com.example.plex_tv_app.arr.ArrTracked
import com.example.plex_tv_app.plex.DiscoverHub
import com.example.plex_tv_app.plex.PlexDetails
import com.example.plex_tv_app.plex.PlexDiscover
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.settings.DiscoverSource
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.tmdb.TmdbClient
import com.example.plex_tv_app.tmdb.TmdbTitle
import com.example.plex_tv_app.tmdb.Releases
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Plex's discovery service, provided by the Shell (it needs the signed-in Plex account). */
val LocalPlexDiscover = staticCompositionLocalOf<PlexDiscover?> { null }

enum class DiscoverFilter(val label: String) { All("All"), Movies("Movies"), Shows("TV Shows") }

private fun DiscoverFilter.keep(t: TmdbTitle) = when (this) {
    DiscoverFilter.All -> true
    DiscoverFilter.Movies -> t.kind == ArrKind.Movie
    DiscoverFilter.Shows -> t.kind == ArrKind.Show
}

/** Discover's rows and choices, kept by the Shell so going in and out of a title doesn't reload them. */
class DiscoverMemory {
    val order = mutableStateListOf<Pair<String, String>>() // row key → title, top to bottom
    val rows = mutableStateMapOf<String, List<TmdbTitle>>()
    val plexHubs = mutableMapOf<String, DiscoverHub>() // for "See more" on Plex rows
    var library by mutableStateOf<Map<String, PlexItem>>(emptyMap())
    /** What Radarr/Sonarr already track (owner only), for the "Monitored" tag. */
    var tracked by mutableStateOf<Set<String>>(emptySet())
    /** Radarr and Sonarr's download queues (owner only), refreshed while Discover is open. */
    var queue by mutableStateOf<List<com.example.plex_tv_app.arr.QueueItem>>(emptyList())
    var loadedAt by mutableLongStateOf(0L)
    var loadedFrom: DiscoverSource? = null
    var error by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false) // everything's loaded and the first posters are in
    /** The row you're on; only rows near it are built, so the TV isn't decoding hundreds of posters. */
    var focusedRow by mutableIntStateOf(0)
    var attempt by mutableIntStateOf(0) // bumped by "Try again"
    var filter by mutableStateOf(DiscoverFilter.All)
    var query by mutableStateOf("")
    /** Titles added from here this session, shown as "Added" straight away. */
    val added = mutableStateMapOf<String, Boolean>()
}

private fun TmdbTitle.addedKey() = "${kind.name}:${plexId ?: id}"

/**
 * Discover: what's trending, popular and coming up (from Plex's discovery service or TMDB),
 * with whatever's already in your library marked. Filter to movies or TV, search, or open a
 * row in full with See more. OK on something you have opens it; anything else opens a panel
 * to add it through Radarr or Sonarr (server owner only).
 */
@Composable
fun DiscoverScreen(api: PlexServerApi, cardScale: Float, memory: DiscoverMemory, onOpen: (PlexItem) -> Unit) {
    val settings = LocalSettings.current
    val source = settings.discoverSource
    val plex = LocalPlexDiscover.current
    val tmdb = remember(settings.tmdbKey) { settings.tmdb() }
    if (!settings.canDownload(api.canManage)) {
        DiscoverNote("Discover is only for the server's owner. They can let everyone use it in Settings → Server.")
        return
    }

    LaunchedEffect(source, tmdb, memory.attempt) {
        val stale = System.currentTimeMillis() - memory.loadedAt > 30 * 60_000
        if (!stale && memory.loadedFrom == source && memory.rows.isNotEmpty()) return@LaunchedEffect
        if (memory.loadedFrom != source) {
            memory.order.clear(); memory.rows.clear(); memory.plexHubs.clear(); memory.focusedRow = 0
        }
        memory.ready = false
        memory.error = null
        memory.loadedFrom = source
        coroutineScope {
            launch { memory.library = runCatching { api.libraryByExternalId() }.getOrDefault(memory.library) }
            if (settings.canDownload(api.canManage)) launch { memory.tracked = ArrTracked.get(settings) }
            when (source) {
                DiscoverSource.Plex -> {
                    try {
                        val hubs = plex?.home() ?: emptyList()
                        memory.plexHubs.clear()
                        hubs.forEach { memory.plexHubs[it.key] = it; memory.rows[it.key] = it.items }
                        memory.order.clear()
                        memory.order.addAll(hubs.map { it.key to it.title })
                        memory.loadedAt = System.currentTimeMillis()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("PlexDiscover", "Discover failed", e)
                        memory.error = "Plex's discovery service didn't answer.\n\n${e.message ?: "No reply"}\n\n" +
                            "You can switch to TMDB in Settings → Server → Recommendations from."
                    }
                }
                DiscoverSource.Tmdb -> {
                    if (tmdb == null) return@coroutineScope
                    // Load every row, then show them together (rows popping in one by one make the page jump).
                    val results = TmdbClient.DiscoverRows.map { row ->
                        async {
                            try {
                                Result.success(row.load(tmdb, 1))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure<List<TmdbTitle>>(e)
                            }
                        }
                    }.awaitAll()
                    if (results.all { it.isFailure }) {
                        memory.error = "Discover couldn't load: ${results.first().exceptionOrNull()?.message ?: "no answer"}"
                    } else {
                        TmdbClient.DiscoverRows.zip(results).forEach { (row, r) -> r.getOrNull()?.let { memory.rows[row.key] = it } }
                        memory.order.clear()
                        memory.order.addAll(TmdbClient.DiscoverRows.map { it.key to it.title })
                        memory.loadedAt = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (settings.canDownload(api.canManage)) memory.tracked = ArrTracked.get(settings)
    }
    // The download queue, every 10 seconds while you're here.
    LaunchedEffect(Unit) {
        if (!settings.canDownload(api.canManage)) return@LaunchedEffect
        while (true) {
            memory.queue = coroutineScope {
                val movies = async { settings.arr(ArrKind.Movie)?.let { runCatching { it.queue() }.getOrNull() }.orEmpty() }
                val shows = async { settings.arr(ArrKind.Show)?.let { runCatching { it.queue() }.getOrNull() }.orEmpty() }
                movies.await() + shows.await()
            }
            kotlinx.coroutines.delay(10_000)
        }
    }

    // Fetch the first screenful of posters before showing the page, so it doesn't open on grey boxes.
    LaunchedEffect(memory.loadedAt) {
        if (memory.rows.isEmpty() || memory.loadedAt == 0L) return@LaunchedEffect
        val firstPosters = memory.order.mapNotNull { memory.rows[it.first] }.filter { it.isNotEmpty() }
            .take(3).flatMap { row -> row.take(8).mapNotNull { it.poster } }
        kotlinx.coroutines.withTimeoutOrNull(2_500) { prefetchImages(firstPosters) }
        memory.ready = true
    }

    var searchOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Pair<String, String>?>(null) } // row key → title

    // Pressing Up from the rows always lands on the filter you're using (never a neighbour, which
    // would switch filters as it's highlighted). Set just before focus moves up, cleared a frame later.
    val chipRequesters = remember { DiscoverFilter.entries.associateWith { FocusRequester() } }
    val enteringChips = remember { mutableStateOf(false) }
    val chipScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        // Title, filters and search.
        Text("Discover", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 48.dp))
        Spacer(Modifier.height(10.dp))
        // Filters and search on their own line, clear of the clock.
        Row(Modifier.padding(start = 48.dp, end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            DiscoverFilter.entries.forEach { f ->
                // Highlighting a filter applies it straight away.
                Chip(
                    f.label,
                    selected = memory.filter == f,
                    // Coming up from the rows, only the current filter can take focus, so it lands
                    // there directly (no other filter is highlighted on the way, which would switch it).
                    modifier = Modifier
                        .focusRequester(chipRequesters.getValue(f))
                        .focusProperties { canFocus = !enteringChips.value || memory.filter == f },
                    onFocused = {
                        enteringChips.value = false
                        if (memory.filter != f) {
                            memory.filter = f
                            memory.focusedRow = 0
                        }
                    },
                ) {}
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.width(12.dp))
            Chip(
                if (memory.query.isBlank()) "⌕  Search" else "⌕  ${memory.query}",
                selected = memory.query.isNotBlank(),
                modifier = Modifier.focusProperties { canFocus = !enteringChips.value },
            ) {
                searchOpen = true
            }
            if (memory.query.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Chip("✕  Clear", selected = false, modifier = Modifier.focusProperties { canFocus = !enteringChips.value }) { memory.query = "" }
            }
        }
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        enteringChips.value = true
                        chipScope.launch {
                            withFrameNanos { }
                            enteringChips.value = false
                        }
                    }
                    false // only watching; the move itself happens as normal
                },
        ) {
            when {
                source == DiscoverSource.Tmdb && tmdb == null ->
                    DiscoverNote("Add a TMDB key in Settings → Server, or switch Discover to Plex there.")
                memory.query.isNotBlank() -> DiscoverSearch(api, memory, cardScale, tmdb, plex, onOpen)
                memory.error != null -> DiscoverProblem(memory.error!!) {
                    memory.error = null
                    memory.loadedAt = 0L
                    memory.attempt++
                }
                memory.rows.isEmpty() || !memory.ready -> CentreSpinner()
                else -> DiscoverRows(api, memory, cardScale, onOpen) { key, title -> expanded = key to title }
            }
        }
    }

    if (searchOpen) {
        TextEntryPanel(
            title = "Search Discover",
            initial = memory.query,
            hint = "A movie or show",
            keyboardType = KeyboardType.Text,
            onSave = { v ->
                searchOpen = false
                memory.query = v.trim()
            },
            onClose = { searchOpen = false },
        )
    }

    expanded?.let { (key, title) ->
        val hub = memory.plexHubs[key]
        val row = TmdbClient.DiscoverRows.firstOrNull { it.key == key }
        ExpandedRow(
            title = title,
            api = api,
            memory = memory,
            cardScale = cardScale,
            load = {
                when {
                    source == DiscoverSource.Plex && hub != null && plex != null -> plex.more(hub)
                    row != null && tmdb != null -> coroutineScope {
                        // Five pages (about 100 titles), fetched together.
                        (1..5).map { p -> async { runCatching { row.load(tmdb, p) }.getOrDefault(emptyList()) } }
                            .awaitAll().flatten().distinctBy { "${it.kind}:${it.id}" }
                    }
                    else -> memory.rows[key].orEmpty()
                }
            },
            onOpen = { item -> expanded = null; onOpen(item) },
            onClose = { expanded = null },
        )
    }
}

/** The rows, filtered; only those near the one you're on are built. */
@Composable
private fun DiscoverRows(
    api: PlexServerApi,
    memory: DiscoverMemory,
    cardScale: Float,
    onOpen: (PlexItem) -> Unit,
    onSeeMore: (String, String) -> Unit,
) {
    val firstCard = remember { FocusRequester() }
    val focusDone = remember { mutableStateOf(false) }
    val filter = memory.filter
    key(filter) {
        val scroll = rememberScrollState()
        val scroller = remember(scroll) { SectionScroller(scroll) }
        val shown = memory.order.mapNotNull { (k, title) ->
            val titles = memory.rows[k].orEmpty().filter(filter::keep)
            if (titles.size < 3) null else Triple(k, title, titles)
        }
        if (shown.isEmpty()) {
            DiscoverNote("Nothing to show for ${filter.label}.")
        } else Column(Modifier.fillMaxSize().verticalScroll(scroll).sectionContent(scroller).padding(start = 34.dp)) {
            val queue = memory.queue.filter {
                when (filter) {
                    DiscoverFilter.All -> true
                    DiscoverFilter.Movies -> it.kind == ArrKind.Movie
                    DiscoverFilter.Shows -> it.kind == ArrKind.Show
                }
            }
            if (queue.isNotEmpty()) {
                QueueRow(queue, cardScale, Modifier.section(scroller))
                Spacer(Modifier.height(10.dp))
            }
            // Only the rows up to a few below the one you're on; more appear as you go down.
            shown.take(memory.focusedRow + 4).forEachIndexed { index, (k, title, titles) ->
                TmdbRowView(
                    title = title,
                    titles = titles,
                    cardScale = cardScale,
                    library = memory.library,
                    added = memory.added,
                    canManage = LocalSettings.current.canDownload(api.canManage),
                    modifier = Modifier.section(scroller).onFocusChanged {
                        if (it.hasFocus && memory.focusedRow != index) memory.focusedRow = index
                    },
                    startPad = 14.dp,
                    firstCard = if (index == 0) firstCard else null,
                    onSeeMore = { onSeeMore(k, title) },
                    tracked = memory.tracked,
                    onOpen = onOpen,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (memory.loadedFrom == DiscoverSource.Tmdb) {
                Spacer(Modifier.height(30.dp))
                Text(
                    "Information from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    fontSize = 11.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    RequestInitialFocus(firstCard, focusDone)
}

/** Search results: a Movies row and a TV Shows row (as the filter allows). */
@Composable
private fun DiscoverSearch(
    api: PlexServerApi,
    memory: DiscoverMemory,
    cardScale: Float,
    tmdb: TmdbClient?,
    plex: PlexDiscover?,
    onOpen: (PlexItem) -> Unit,
) {
    val settings = LocalSettings.current
    val query = memory.query
    var problem by remember(query) { mutableStateOf<String?>(null) }
    val results by produceState<List<TmdbTitle>?>(null, query, settings.discoverSource) {
        value = null
        value = try {
            when (settings.discoverSource) {
                DiscoverSource.Tmdb -> tmdb?.search(query).orEmpty()
                DiscoverSource.Plex -> plex?.search(query).orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: "no answer"
            emptyList()
        }
    }
    val list = results
    when {
        list == null -> CentreSpinner()
        problem != null -> DiscoverNote("Search didn't work: $problem")
        else -> {
            val movies = list.filter { it.kind == ArrKind.Movie && memory.filter.keep(it) }
            val shows = list.filter { it.kind == ArrKind.Show && memory.filter.keep(it) }
            if (movies.isEmpty() && shows.isEmpty()) {
                DiscoverNote("Nothing found for \"$query\".")
            } else {
            val firstCard = remember(query) { FocusRequester() }
            val focusDone = remember(query) { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 34.dp)) {
                listOf("Movies" to movies, "TV Shows" to shows).filter { it.second.isNotEmpty() }.forEachIndexed { i, (title, titles) ->
                    TmdbRowView(
                        title = title,
                        titles = titles,
                        cardScale = cardScale,
                        library = memory.library,
                        added = memory.added,
                        canManage = LocalSettings.current.canDownload(api.canManage),
                        modifier = Modifier,
                        startPad = 14.dp,
                        firstCard = if (i == 0) firstCard else null,
                        tracked = memory.tracked,
                        onOpen = onOpen,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(40.dp))
            }
            RequestInitialFocus(firstCard, focusDone)
            }
        }
    }
}

/** A whole row as a grid ("See more"), on its own layer; Back closes it. */
@Composable
private fun ExpandedRow(
    title: String,
    api: PlexServerApi,
    memory: DiscoverMemory,
    cardScale: Float,
    load: suspend () -> List<TmdbTitle>,
    onOpen: (PlexItem) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BackHandler { onClose() }
        val titles by produceState<List<TmdbTitle>?>(null) {
            value = runCatching { load() }.getOrDefault(emptyList())
        }
        var adding by remember { mutableStateOf<TmdbTitle?>(null) }
        Column(Modifier.fillMaxSize().background(palette.background).padding(top = 28.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 48.dp))
            val list = titles?.filter(memory.filter::keep)
            when {
                list == null -> CentreSpinner()
                list.isEmpty() -> DiscoverNote("Nothing more to show.")
                else -> {
                    val gridState = rememberLazyGridState()
                    val first = remember { FocusRequester() }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(140.dp * cardScale),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp, start = 48.dp, end = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(cardGap(22.dp)),
                        verticalArrangement = Arrangement.spacedBy(cardGap(22.dp)),
                    ) {
                        gridItemsIndexed(list, key = { i, t -> "${t.kind}:${t.plexId ?: t.id}:$i" }) { i, t ->
                            val keys = t.libraryKeys()
                            val owned = keys.firstNotNullOfOrNull { memory.library[it] }
                            val wasAdded = memory.added[t.addedKey()] == true
                            val monitored = !wasAdded && owned == null && keys.any { it in memory.tracked }
                            PosterAddCard(
                                poster = t.poster,
                                title = t.title,
                                year = t.year,
                                badge = when {
                                    owned != null -> "In library"
                                    wasAdded -> "Added"
                                    monitored -> "Monitored"
                                    else -> null
                                },
                                accent = wasAdded || monitored,
                                release = if (owned == null) releaseState(t) else null,
                                modifier = Modifier,
                                cardModifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).gridItem(gridState, i),
                            ) {
                                if (owned != null) onOpen(owned) else adding = t
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        repeat(6) {
                            withFrameNanos { }
                            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
                        }
                    }
                }
            }
        }
        adding?.let { t ->
            TmdbPanel(t, LocalSettings.current.canDownload(api.canManage), onClose = { adding = null }, onAdded = { memory.added[t.addedKey()] = true })
        }
    }
}

/** A large spinner in the middle of the space. */
@Composable
private fun CentreSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner(64)
            Spacer(Modifier.height(16.dp))
            Text("Loading…", fontSize = 15.sp, color = palette.muted)
        }
    }
}

/** A filter or search button at the top. */
@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp).onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.text.copy(alpha = 0.2f) else palette.text.copy(alpha = 0.06f),
            contentColor = palette.text,
            focusedContainerColor = palette.text,
            focusedContentColor = palette.background,
        ),
    ) {
        // Only as wide as its label (filling would stretch the first button across the row).
        Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** What went wrong, in full, with a Try again button (which also gives the page something to highlight). */
@Composable
private fun DiscoverProblem(text: String, onRetry: () -> Unit) {
    val retry = remember { FocusRequester() }
    Column(Modifier.padding(start = 48.dp, top = 12.dp, end = 72.dp).verticalScroll(rememberScrollState())) {
        Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = palette.muted)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(220.dp)) {
            PickerRow("Try again", false, Modifier.focusRequester(retry)) { onRetry() }
        }
    }
    LaunchedEffect(Unit) {
        repeat(4) {
            withFrameNanos { }
            if (runCatching { retry.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun DiscoverNote(text: String) {
    Text(text, fontSize = 15.sp, color = palette.muted, modifier = Modifier.padding(start = 48.dp, top = 12.dp, end = 48.dp))
}

/** One row of titles, with an optional See more at the end. Ones you have open in Plex; the rest open the add panel. */
@Composable
internal fun TmdbRowView(
    title: String,
    titles: List<TmdbTitle>,
    cardScale: Float,
    library: Map<String, PlexItem>,
    added: MutableMap<String, Boolean>,
    canManage: Boolean,
    modifier: Modifier,
    startPad: Dp,
    firstCard: FocusRequester? = null,
    onSeeMore: (() -> Unit)? = null,
    tracked: Set<String> = emptySet(),
    onOpen: (PlexItem) -> Unit,
) {
    var adding by remember { mutableStateOf<TmdbTitle?>(null) }
    val rowState = rememberLazyListState()
    Column(modifier) {
        RowHeading(title, start = startPad)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(start = startPad, top = 10.dp, bottom = 12.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
        ) {
            itemsIndexed(titles, key = { i, t -> "${t.kind}:${t.plexId ?: t.id}:$i" }) { i, t ->
                val keys = t.libraryKeys()
                val owned = keys.firstNotNullOfOrNull { library[it] }
                val wasAdded = added[t.addedKey()] == true
                val monitored = !wasAdded && owned == null && keys.any { it in tracked }
                PosterAddCard(
                    poster = t.poster,
                    title = t.title,
                    year = t.year,
                    badge = when {
                        owned != null -> "In library"
                        wasAdded -> "Added"
                        monitored -> "Monitored"
                        else -> null
                    },
                    accent = wasAdded || monitored,
                    release = if (owned == null) releaseState(t) else null,
                    modifier = Modifier.width(118.dp * cardScale),
                    cardModifier = (if (i == 0 && firstCard != null) Modifier.focusRequester(firstCard) else Modifier)
                        .glideIntoView(rowState, i),
                ) {
                    if (owned != null) onOpen(owned) else adding = t
                }
            }
            if (onSeeMore != null) {
                item(key = "see_more") {
                    SeeMoreCard(Modifier.width(118.dp * cardScale), Modifier.glideIntoView(rowState, titles.size), onSeeMore)
                }
            }
        }
    }
    adding?.let { t ->
        TmdbPanel(t, canManage, onClose = { adding = null }, onAdded = { added[t.addedKey()] = true })
    }
}

/** The corner tag for a recent movie: out digitally, in cinemas, or coming soon. */
@Composable
private fun releaseState(t: TmdbTitle): com.example.plex_tv_app.tmdb.ReleaseState? {
    if (!Releases.worthChecking(t)) return null
    val settings = LocalSettings.current
    val release by produceState(Releases.cached(t), t.id) { value = Releases.get(t, settings) }
    return release?.state()
}

/** The tile at the end of a row that opens it in full. */
@Composable
private fun SeeMoreCard(modifier: Modifier, cardModifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = cardModifier.returnFocusHere().fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = palette.text.copy(alpha = 0.06f),
                contentColor = palette.text,
                focusedContainerColor = palette.text,
                focusedContentColor = palette.background,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("›", fontSize = 34.sp)
                    Text("See more", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Finds a discovered title in Radarr/Sonarr: by id where we have one, else by name and year. */
private suspend fun findInArr(t: TmdbTitle, client: ArrClient, tmdb: TmdbClient?, plex: PlexDiscover?): ArrTitle? {
    val plexIds = if ((t.id <= 0 || (t.kind == ArrKind.Show && t.tvdb == null)) && t.plexId != null) {
        runCatching { plex?.ids(t.plexId) }.getOrNull().orEmpty()
    } else emptyMap()
    val tmdbId = t.id.takeIf { it > 0 } ?: plexIds["tmdb"]?.toIntOrNull()
    val byId = runCatching {
        if (t.kind == ArrKind.Movie) {
            tmdbId?.let { client.lookupTmdb(it) }
        } else {
            val tvdb = t.tvdb ?: plexIds["tvdb"]?.toIntOrNull() ?: tmdbId?.let { id -> tmdb?.tvdbId(id) }
            tvdb?.let { client.lookupTvdb(it) }
        }
    }.getOrNull()
    val found = byId ?: run {
        // Last resort: search by name, and only accept the same title and year.
        val results = client.search(listOfNotNull(t.title, t.year?.toString()).joinToString(" "))
        results.firstOrNull { it.title.equals(t.title, ignoreCase = true) && (t.year == null || it.year == t.year) }
            ?: results.firstOrNull { t.year != null && it.year == t.year }
    } ?: return null
    // Lookups don't always say whether it's already there, so ask Radarr/Sonarr's own list.
    return if (!found.added && runCatching { client.isTracked(found) }.getOrDefault(false)) found.copy(added = true) else found
}

/** Details of a title you don't have, and (for the server owner) the buttons to add it. */
@Composable
private fun TmdbPanel(t: TmdbTitle, canManage: Boolean, onClose: () -> Unit, onAdded: () -> Unit) {
    val settings = LocalSettings.current
    val plex = LocalPlexDiscover.current
    val movie = t.kind == ArrKind.Movie
    val app = if (movie) "Radarr" else "Sonarr"
    SidePanel(title = if (movie) "Movie" else "Show", onClose = onClose) { first ->
        TitleHeader(
            t.poster,
            t.title,
            listOfNotNull(t.year?.toString(), t.rating?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }),
            t.overview,
        )
        var monitored by remember { mutableStateOf(false) }
        if (t.kind == ArrKind.Movie && Releases.worthChecking(t)) {
            val release by produceState(Releases.cached(t), t.id) { value = Releases.get(t, settings) }
            release?.line(monitored)?.let { ReleaseLine(it) }
        }
        Spacer(Modifier.height(12.dp))
        val client = remember { settings.arr(t.kind) }
        when {
            !canManage -> {
                PickerRow("Close", false, first) { onClose() }
                Spacer(Modifier.height(8.dp))
                Text("Not in the library yet. Only the server's owner can add titles (they can allow everyone in Settings → Server).", fontSize = 13.sp, color = palette.muted)
            }
            client == null -> {
                PickerRow("Close", false, first) { onClose() }
                Spacer(Modifier.height(8.dp))
                Text("Connect $app in Settings → Server to add this.", fontSize = 13.sp, color = palette.muted)
            }
            else -> {
                val tmdb = remember { settings.tmdb() }
                var problem by remember { mutableStateOf<String?>(null) }
                val found by produceState<ArrTitle?>(null, t) {
                    value = try {
                        findInArr(t, client, tmdb, plex)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem = "Couldn't reach $app"
                        null
                    }
                    if (value == null && problem == null) problem = "$app couldn't find this one"
                }
                LaunchedEffect(found) { monitored = found?.added == true }
                ArrAddActions(found, first, onClose = onClose, onAdded = { monitored = true; onAdded() }, kind = t.kind, waiting = problem)
            }
        }
    }
}

/**
 * "More like this" on a movie or show's page: recommendations for it (from Plex's discovery
 * service or TMDB), with what you already have marked.
 */
@Composable
internal fun MoreLikeThis(api: PlexServerApi, d: PlexDetails, modifier: Modifier, startPad: Dp, onOpen: (PlexItem) -> Unit) {
    val settings = LocalSettings.current
    if (!settings.moreLikeThis || !settings.discoverReady || !settings.canDownload(api.canManage)) return
    val plex = LocalPlexDiscover.current
    val source = settings.discoverSource
    val tmdb = remember(settings.tmdbKey) { settings.tmdb() }
    val kind = when (d.item.type) {
        "movie" -> ArrKind.Movie
        "show" -> ArrKind.Show
        else -> return
    }
    val titles by produceState(emptyList<TmdbTitle>(), d.item.ratingKey, source, tmdb) {
        value = runCatching {
            when (source) {
                DiscoverSource.Plex -> d.externalIds["plex"]?.let { plex?.moreLike(it) }.orEmpty()
                    .filter { it.kind == kind }
                DiscoverSource.Tmdb -> {
                    val t = tmdb ?: return@runCatching emptyList()
                    val id = d.externalIds["tmdb"]?.toIntOrNull() ?: t.find(kind, d.item.title, d.item.year)?.id
                    if (id == null) emptyList() else t.moreLike(kind, id).take(20)
                }
            }
        }.getOrDefault(emptyList())
    }
    val library by produceState(emptyMap<String, PlexItem>(), api) {
        value = runCatching { api.libraryByExternalId() }.getOrDefault(emptyMap())
    }
    val tracked by produceState(emptySet<String>(), api) {
        if (settings.canDownload(api.canManage)) value = ArrTracked.get(settings)
    }
    val added = remember { mutableStateMapOf<String, Boolean>() }
    if (titles.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        TmdbRowView(
            title = "More like this",
            titles = titles,
            cardScale = 1f,
            library = library,
            added = added,
            canManage = LocalSettings.current.canDownload(api.canManage),
            modifier = modifier,
            startPad = startPad,
            tracked = tracked,
            onOpen = onOpen,
        )
        Spacer(Modifier.height(14.dp))
    }
}

/** "Downloading": what Radarr and Sonarr are fetching, with progress. */
@Composable
private fun QueueRow(items: List<com.example.plex_tv_app.arr.QueueItem>, cardScale: Float, modifier: Modifier) {
    var open by remember { mutableStateOf<com.example.plex_tv_app.arr.QueueItem?>(null) }
    val rowState = rememberLazyListState()
    Column(modifier) {
        RowHeading("Downloading", start = 14.dp)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
        ) {
            itemsIndexed(items, key = { _, q -> "${q.kind}:${q.id}" }) { i, q ->
                QueueCard(q, Modifier.width(118.dp * cardScale), Modifier.glideIntoView(rowState, i)) { open = q }
            }
        }
    }
    open?.let { q ->
        // Keep the panel's numbers live as the queue refreshes.
        val live = items.firstOrNull { it.kind == q.kind && it.id == q.id } ?: q
        QueuePanel(live) { open = null }
    }
}

private fun com.example.plex_tv_app.arr.QueueItem.line(): String = when (state) {
    com.example.plex_tv_app.tmdb.EpisodeState.Downloading ->
        listOfNotNull("${((progress ?: 0f) * 100).toInt()}%", timeLeft).joinToString(" · ")
    com.example.plex_tv_app.tmdb.EpisodeState.Problem -> "Problem"
    else -> statusText ?: "Queued"
}

@Composable
private fun QueueCard(q: com.example.plex_tv_app.arr.QueueItem, modifier: Modifier, cardModifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = cardModifier.returnFocusHere().fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(containerColor = palette.raised, focusedContainerColor = palette.raised),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (q.poster != null) RemoteImage(q.poster, Modifier.fillMaxSize())
                else Text(q.title, fontSize = 13.sp, color = palette.muted, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                ProgressStrip(q.progress ?: 0f, q.state.colour(), Modifier.align(Alignment.BottomStart).height(4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(q.title, fontSize = 12.sp, color = palette.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(q.line(), fontSize = 11.sp, color = q.state.colour(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun QueuePanel(q: com.example.plex_tv_app.arr.QueueItem, onClose: () -> Unit) {
    SidePanel(title = if (q.kind == ArrKind.Movie) "Downloading movie" else "Downloading episode", onClose = onClose) { first ->
        TitleHeader(q.poster, q.title, listOfNotNull(q.detail), null)
        Spacer(Modifier.height(12.dp))
        Text(
            when (q.state) {
                com.example.plex_tv_app.tmdb.EpisodeState.Downloading -> "Downloading: " + q.line()
                com.example.plex_tv_app.tmdb.EpisodeState.Problem -> "There's a problem with this download."
                else -> q.statusText ?: "Waiting to download"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = q.state.colour(),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            ProgressStrip(q.progress ?: 0f, q.state.colour(), Modifier.fillMaxSize())
        }
        q.statusText?.takeIf { q.state == com.example.plex_tv_app.tmdb.EpisodeState.Problem }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = palette.muted, maxLines = 6)
        }
        listOfNotNull(q.sizeText?.let { "Size: $it" }, q.release).forEach {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = palette.muted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        PickerRow("Close", false, first) { onClose() }
    }
}
