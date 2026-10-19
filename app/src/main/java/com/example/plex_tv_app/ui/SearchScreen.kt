@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexHub
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.ui.theme.PlexSerif
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.arr.ArrTitle
import com.example.plex_tv_app.settings.LocalSettings

/** What you've typed, kept by the Shell so Back from a result returns to the same search. */
class SearchMemory {
    var query by mutableStateOf("")
}

private val KeyRows = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")
private val KeyWidth = 44.dp
private val KeyHeight = 40.dp
private val KeyGap = 6.dp

/**
 * Search: an on-screen keyboard on the left (a physical keyboard works too), results on the
 * right grouped into rows like Plex (Movies, TV Shows, Episodes, Collections), updating as you type.
 */
@Composable
fun SearchScreen(
    api: PlexServerApi,
    client: PlexClient,
    cardScale: Float,
    memory: SearchMemory,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    var results by remember { mutableStateOf<List<PlexHub>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(client.recentSearches) }
    val query = memory.query
    val settings = LocalSettings.current
    var arrMovies by remember { mutableStateOf<List<ArrTitle>>(emptyList()) }
    var arrShows by remember { mutableStateOf<List<ArrTitle>>(emptyList()) }

    // Owner only: also ask Radarr and Sonarr, so things not in the library yet can be added.
    // If they can't be reached (e.g. away from home), the rows just don't appear.
    LaunchedEffect(query) {
        if (query.isBlank() || !settings.canDownload(api.canManage)) {
            arrMovies = emptyList(); arrShows = emptyList()
            return@LaunchedEffect
        }
        delay(600)
        coroutineScope {
            val movies = async { settings.arr(ArrKind.Movie)?.let { c -> runCatching { c.search(query) }.getOrNull() }.orEmpty() }
            val shows = async { settings.arr(ArrKind.Show)?.let { c -> runCatching { c.search(query) }.getOrNull() }.orEmpty() }
            arrMovies = movies.await()
            arrShows = shows.await()
        }
    }
    fun markAdded(t: ArrTitle) {
        val done = t.copy(added = true)
        if (t.kind == ArrKind.Movie) arrMovies = arrMovies.map { if (it === t) done else it }
        else arrShows = arrShows.map { if (it === t) done else it }
    }
    val arrRows: @Composable (Modifier) -> Unit = { section ->
        if (arrMovies.isNotEmpty()) {
            ArrResultsRow("Add movies · Radarr", arrMovies, cardScale, section, ::markAdded)
            Spacer(Modifier.height(10.dp))
        }
        if (arrShows.isNotEmpty()) {
            ArrResultsRow("Add shows · Sonarr", arrShows, cardScale, section, ::markAdded)
            Spacer(Modifier.height(10.dp))
        }
    }
    val hasArr = arrMovies.isNotEmpty() || arrShows.isNotEmpty()

    // Search as you type, after a short pause so every key press doesn't hit the server.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            searching = false
            error = null
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        try {
            results = api.search(query)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            searching = false
        }
    }

    fun type(c: Char) {
        if (memory.query.length < 60) memory.query += c
    }
    fun backspace() {
        memory.query = memory.query.dropLast(1)
    }
    fun openResult(item: PlexItem) {
        val q = memory.query.trim()
        if (q.isNotEmpty()) {
            recent = (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(8)
            client.recentSearches = recent
        }
        onOpen(item)
    }

    val firstKey = remember { FocusRequester() }
    val initialDone = remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 0.dp, top = 28.dp)
            // Typing on a real keyboard (or a remote with a keyboard) goes straight into the search.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    code == AndroidKeyEvent.KEYCODE_DEL -> { backspace(); true }
                    code == AndroidKeyEvent.KEYCODE_SPACE -> { type(' '); true }
                    else -> {
                        val ch = e.nativeKeyEvent.unicodeChar.toChar()
                        if (ch.isLetterOrDigit()) { type(ch.lowercaseChar()); true } else false
                    }
                }
            },
    ) {
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxSize()) {
            // ---- Keyboard ----
            Column(Modifier.width(KeyWidth * 6 + KeyGap * 5)) {
                QueryBox(query, searching)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                    KeyRows.forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                            row.forEachIndexed { c, ch ->
                                Key(
                                    label = ch.uppercase(),
                                    width = KeyWidth,
                                    modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                                ) { type(ch) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        Key("Space", width = KeyWidth * 3 + KeyGap * 2, small = true) { type(' ') }
                        Key("⌫", width = KeyWidth * 2 + KeyGap) { backspace() }
                        Key("Clear", width = KeyWidth, small = true) { memory.query = "" }
                    }
                }
                RequestInitialFocus(firstKey, initialDone)
            }

            Spacer(Modifier.width(18.dp))

            // ---- Results ----
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val list = results
                when {
                    query.isBlank() -> RecentSearches(recent) { memory.query = it }
                    error != null -> Note("Search didn't work: $error")
                    list == null && !hasArr -> Note("Searching…")
                    list.orEmpty().isEmpty() && !hasArr && !searching -> Note("Nothing found for \"${query.trim()}\". Try fewer letters, or another title.")
                    else -> SearchResults(api, list.orEmpty(), cardScale, ::openResult, onLongPress, arrRows)
                }
            }
        }
    }
}

/** The text you've typed, with a blinking caret. */
@Composable
private fun QueryBox(query: String, searching: Boolean) {
    val blink = rememberInfiniteTransition(label = "caret")
    val caret by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caretAlpha")
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.raised)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (query.isEmpty()) {
            Text("Movies, shows, people…", fontSize = 15.sp, color = palette.muted, modifier = Modifier.weight(1f))
        } else {
            Text(query, fontSize = 17.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.width(2.dp).height(20.dp).graphicsLayer { alpha = caret }.background(PlexGold))
            Spacer(Modifier.weight(1f))
        }
        if (searching) Text("…", fontSize = 15.sp, color = palette.muted)
    }
}

/** One key on the on-screen keyboard. */
@Composable
private fun Key(
    label: String,
    width: Dp,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    onPress: () -> Unit,
) {
    Surface(
        onClick = onPress,
        modifier = modifier.confettiAnchor().returnFocusHere().width(width).height(KeyHeight),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.text.copy(alpha = 0.07f),
            focusedContainerColor = palette.text,
            contentColor = palette.text,
            focusedContentColor = palette.background,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = if (small) 13.sp else 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Results, one row per kind, gliding into view as you move down. */
@Composable
private fun SearchResults(
    api: PlexServerApi,
    hubs: List<PlexHub>,
    cardScale: Float,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
    extraRows: @Composable (Modifier) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val scroller = remember(scroll) { SectionScroller(scroll) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).sectionContent(scroller)) {
        hubs.forEach { hub ->
            val wide = hub.items.all { it.type == "episode" }
            val style = if (wide) CardStyle.Thumbnail else CardStyle.Poster
            val rowState = rememberLazyListState()
            Column(Modifier.section(scroller)) {
                Text(
                    hub.title,
                    fontFamily = PlexSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = palette.text,
                    modifier = Modifier.padding(start = 14.dp),
                )
                LazyRow(
                    state = rowState,
                    // Room on the left so a highlighted card can grow without being clipped.
                    contentPadding = PaddingValues(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(hub.items, key = { _, it -> it.ratingKey }) { i, item ->
                        MediaCard(
                            api = api,
                            item = item,
                            style = style,
                            standalone = true,
                            onClick = { onOpen(item) },
                            onLongClick = { restore -> onLongPress(item, restore) },
                            modifier = Modifier.width((if (wide) 220.dp else 118.dp) * cardScale),
                            cardModifier = Modifier.glideIntoView(rowState, i),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        extraRows(Modifier.section(scroller))
        Spacer(Modifier.height(40.dp))
    }
}

/** Before you type: your recent searches, one press away. */
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit) {
    if (recent.isEmpty()) {
        Note("Type a title, actor or collection. Results appear as you type.")
        return
    }
    Column {
        Text("Recent searches", fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = palette.text)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            recent.forEach { q ->
                Surface(
                    onClick = { onPick(q) },
                    modifier = Modifier.confettiAnchor().returnFocusHere(),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = palette.text.copy(alpha = 0.07f),
                        focusedContainerColor = palette.text,
                        contentColor = palette.text,
                        focusedContentColor = palette.background,
                    ),
                ) {
                    Text(q, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 15.sp, color = palette.muted, modifier = Modifier.padding(top = 4.dp, end = 48.dp))
}
