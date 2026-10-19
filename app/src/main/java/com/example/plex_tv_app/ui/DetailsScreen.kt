@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.plex_tv_app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexDetails
import com.example.plex_tv_app.plex.PlexHub
import com.example.plex_tv_app.plex.PlaybackInfo
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.plex.Review
import com.example.plex_tv_app.settings.CollectionRows
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.ui.theme.PlexSerif
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** What the details screen opens, and where to start (a season and/or episode for shows). */
data class DetailsTarget(
    val ratingKey: String, // the movie or show
    val seasonKey: String? = null,
    val episodeKey: String? = null,
)

private val SidePad = 56.dp

/** Tries to focus; true if it worked (copes with both older and newer Compose behaviour). */
private fun FocusRequester.tryFocus(): Boolean {
    val result = runCatching { requestFocus() }
    return result.isSuccess && (result.getOrNull() as Any?) != false
}

/**
 * A movie or show, full screen. The artwork fills the background; the title, details and
 * buttons sit on the left.
 * - Movies: cast underneath; scroll down for ratings, similar titles and collections.
 * - Shows: like a movie, with a row of seasons. A season (or episode) opens the episode view:
 *   season tabs and a sliding row of episodes; highlighting an episode swaps in its details.
 */
@Composable
fun DetailsScreen(
    api: PlexServerApi,
    target: DetailsTarget,
    reloadToken: Int,
    onOpen: (PlexItem) -> Unit,
    onPlay: (item: PlexItem, fromStart: Boolean) -> Unit,
    onSetWatched: (item: PlexItem, watched: Boolean) -> Unit,
    onLongPress: (item: PlexItem, restoreFocus: () -> Unit) -> Unit,
    onShuffle: (items: List<PlexItem>, name: String) -> Unit,
) {
    var details by remember { mutableStateOf<PlexDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target.ratingKey, reloadToken) {
        try {
            details = api.details(target.ratingKey)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (details == null) error = e.message ?: e.toString()
        }
    }

    // The built-in focus scrolling is off here; rows and sections glide into view themselves
    // (see SmoothScroll.kt), which also keeps the title from being cut off.
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.bleedLeft(LocalNavInset.current).fillMaxSize().background(palette.background))
        val d = details
        when {
            d != null -> {
                val logo by produceState<String?>(null, d.item.ratingKey) { value = api.logoUrlFor(d.item) }
                when (d.item.type) {
                    "collection" -> CollectionDetails(api, d, logo, reloadToken, onOpen, onPlay, onShuffle, onLongPress)
                    "show" -> {
                        DetailsBackdrop(api, d.item)
                        // Just the show: its own page. A season or episode: the episode view.
                        if (target.seasonKey == null && target.episodeKey == null) {
                            ShowOverview(api, d, logo, reloadToken, onOpen, onPlay, onSetWatched, onLongPress)
                        } else {
                            ShowDetails(api, d, logo, target, reloadToken, onOpen, onPlay, onSetWatched, onLongPress)
                        }
                    }
                    else -> {
                        DetailsBackdrop(api, d.item)
                        MovieDetails(api, d, logo, reloadToken, onOpen, onPlay, onSetWatched, onLongPress)
                    }
                }
            }
            error != null -> Message("Couldn't open this title. $error")
            else -> Message("Loading…")
        }
    }
    }
}

// ---------- Movies ----------

@Composable
private fun MovieDetails(
    api: PlexServerApi,
    d: PlexDetails,
    logo: String?,
    reloadToken: Int,
    onOpen: (PlexItem) -> Unit,
    onPlay: (PlexItem, Boolean) -> Unit,
    onSetWatched: (PlexItem, Boolean) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    val movie = d.item
    val play = remember { FocusRequester() }
    val focused = remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val splitCollections = LocalSettings.current.collectionRows == CollectionRows.Split
    val scroller = remember(scroll) { SectionScroller(scroll) }
    val related by produceState<List<PlexHub>>(emptyList(), movie.ratingKey, reloadToken, splitCollections) {
        value = runCatching { api.related(movie.ratingKey, d.collections, splitCollections) }.getOrDefault(emptyList())
    }
    // Whenever focus is back on the first screen, show all of it (not just the part with focus).
    var onFirstScreen by remember { mutableStateOf(true) }
    LaunchedEffect(onFirstScreen) {
        if (onFirstScreen && scroll.value != 0) {
            scroll.animateScrollTo(0, ScrollEase)
        }
    }
    val toTop = {}
    val settings = LocalSettings.current
    val playInfo by produceState<PlaybackInfo?>(null, movie.ratingKey, reloadToken) {
        value = runCatching { api.playbackInfo(movie) }.getOrNull()
    }
    var subtitlePickerOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        // The artwork dims further as you scroll down into the extra sections.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (scroll.value / 500f).coerceIn(0f, 1f) }
                .background(palette.background.copy(alpha = 0.88f))
        )
        Column(Modifier.fillMaxSize().verticalScroll(scroll).sectionContent(scroller)) {
            // First screen: title, details, buttons and cast.
            Column(
                Modifier
                    .height(screen)
                    .onFocusChanged { onFirstScreen = it.hasFocus }
                    .padding(top = 40.dp, bottom = 20.dp),
            ) {
                Column(Modifier.padding(horizontal = SidePad).width(640.dp)) {
                    TitleBlock(movie, logo, baseHeight = 80.dp)
                    Spacer(Modifier.height(12.dp))
                    Facts(
                        date = dateText(movie.releaseDate) ?: movie.year?.toString(),
                        length = movie.duration?.let(::lengthText),
                        item = movie,
                    )
                    d.tagline?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontFamily = PlexSerif, fontStyle = FontStyle.Italic, fontSize = 15.sp, color = palette.muted, maxLines = 1)
                    }
                    movie.summary?.let {
                        Spacer(Modifier.height(8.dp))
                        Summary(it, lines = 3)
                    }
                    Credits(d)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val offset = movie.viewOffset
                        val duration = movie.duration
                        if (movie.progress != null && offset != null && duration != null) {
                            PillButton(
                                "Resume", "▶", primary = true,
                                detail = "${lengthText(duration - offset)} left",
                                modifier = Modifier.focusRequester(play),
                                onFocused = toTop,
                            ) { onPlay(movie, false) }
                            PillButton("From the start", "↺", onFocused = toTop) { onPlay(movie, true) }
                        } else {
                            PillButton("Play", "▶", primary = true, modifier = Modifier.focusRequester(play), onFocused = toTop) {
                                onPlay(movie, true)
                            }
                        }
                        PillButton(
                            if (movie.isWatched) "Mark unwatched" else "Mark watched",
                            if (movie.isWatched) "○" else "✓",
                            onFocused = toTop,
                        ) { onSetWatched(movie, !movie.isWatched) }
                        LocalUpdateMetadata.current?.let { update ->
                            PillButton("Update metadata", "⟳", onFocused = toTop) { update(movie) }
                        }
                        playInfo?.let { inf ->
                            settings.subtitlePickVersion // update the label when a pick changes
                            val chosen = chooseSubtitle(settings, movie, inf.subtitles, inf.audioLanguage)
                            PillButton(subtitleShortName(chosen), "CC", onFocused = toTop) { subtitlePickerOpen = true }
                            if (subtitlePickerOpen) {
                                SubtitlePicker(
                                    subtitles = inf.subtitles,
                                    current = chosen,
                                    onPick = { rememberPick(settings, movie, it); subtitlePickerOpen = false },
                                    onClose = { subtitlePickerOpen = false },
                                )
                            }
                        }
                    }
                    RequestInitialFocus(play, focused)
                }

                Spacer(Modifier.weight(1f))
                if (d.cast.isNotEmpty()) {
                    SectionTitle("Cast")
                    CastRow(d)
                }
            }

            // Below the fold: ratings and reviews, then collections and similar titles.
            Extras(api, d, related, scroller, includeCast = false, onOpen = onOpen, onLongPress = onLongPress)
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** One score: critics, audience or your own. */
private data class RatingTile(val score: String, val label: String, val source: String)

private fun ratingTiles(d: PlexDetails): List<RatingTile> {
    // Plex's badges look like "rottentomatoes://image.rating.ripe": a source, then a verdict.
    fun from(value: Float, image: String?, critics: Boolean): RatingTile {
        val img = image.orEmpty()
        val source = img.substringBefore("://")
        val verdict = img.substringAfterLast('.', "")
        val who = if (critics) "Critics" else "Audience"
        return when (source) {
            "rottentomatoes" -> RatingTile(
                "${(value * 10).roundToInt()}%",
                when (verdict) {
                    "ripe" -> "Critics, fresh"
                    "rotten" -> "Critics, rotten"
                    "upright" -> "Audience, liked it"
                    "spilled" -> "Audience, didn't like it"
                    else -> who
                },
                "Rotten Tomatoes",
            )
            "imdb" -> RatingTile(String.format(Locale.US, "%.1f", value), who, "IMDb")
            "themoviedb" -> RatingTile("${(value * 10).roundToInt()}%", who, "TMDB")
            else -> RatingTile(String.format(Locale.US, "%.1f", value), who, "Rating")
        }
    }
    return listOfNotNull(
        d.criticRating?.let { from(it, d.criticSource, critics = true) },
        d.item.audienceRating?.let { from(it, d.audienceSource, critics = false) },
        d.item.userRating?.let { r ->
            val stars = (r / 2).roundToInt().coerceIn(0, 5)
            RatingTile("★".repeat(stars) + "☆".repeat(5 - stars), "Your rating", "You")
        },
    )
}

/**
 * Everything below the first screen, shared by movies and shows: cast (shows only; movies
 * show it higher up), ratings with reviews beside them, then collections and similar titles.
 */
@Composable
private fun Extras(
    api: PlexServerApi,
    d: PlexDetails,
    related: List<PlexHub>,
    scroller: SectionScroller,
    includeCast: Boolean,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    val prefs = LocalSettings.current
    if (includeCast && d.cast.isNotEmpty()) {
        Column(Modifier.section(scroller)) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Cast")
            CastRow(d)
        }
        Spacer(Modifier.height(16.dp))
    }

    // Ratings stacked on the left, reviews alongside them.
    val tiles = ratingTiles(d)
    val reviews = buildList {
        if (prefs.showCriticReviews) addAll(d.reviews)
        // Community (Plex user) reviews would be added here when enabled; see the note in PlexServerApi.
    }
    if (tiles.isNotEmpty() || reviews.isNotEmpty()) {
        Column(Modifier.section(scroller)) {
            SectionTitle(if (reviews.isNotEmpty()) "Ratings and reviews" else "Ratings")
            Row(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
                if (tiles.isNotEmpty()) {
                    Column(
                        Modifier.padding(start = SidePad),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        tiles.forEach { RatingCard(it) }
                    }
                }
                if (reviews.isNotEmpty()) {
                    val reviewsState = rememberLazyListState()
                    LazyRow(
                        state = reviewsState,
                        contentPadding = PaddingValues(start = if (tiles.isEmpty()) SidePad else 16.dp, end = SidePad),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        itemsIndexed(reviews) { i, review ->
                            ReviewCard(review, Modifier.glideIntoView(reviewsState, i))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    related.forEach { row ->
        val rowState = rememberLazyListState()
        Column(Modifier.section(scroller)) {
            SectionTitle(row.title)
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = SidePad, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
            ) {
                itemsIndexed(row.items, key = { _, item -> item.ratingKey }) { i, item ->
                    MediaCard(
                        api = api,
                        item = item,
                        style = CardStyle.Poster,
                        standalone = true,
                        onClick = { onOpen(item) },
                        onLongClick = { restore -> onLongPress(item, restore) },
                        modifier = Modifier.width(118.dp),
                        cardModifier = Modifier.glideIntoView(rowState, i),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    MoreLikeThis(api, d, Modifier.section(scroller), SidePad, onOpen)
    Spacer(Modifier.height(40.dp))
}

@Composable
private fun CastRow(d: PlexDetails) {
    val state = rememberLazyListState()
    val openPerson = LocalOpenPerson.current // selecting someone lists what else they're in
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = SidePad, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(d.cast.take(30)) { i, person ->
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircleButton(onClick = { openPerson?.invoke(person) }, size = 68.dp, modifier = Modifier.glideIntoView(state, i)) {
                    Avatar(person.thumb, person.name, size = 68.dp)
                }
                Spacer(Modifier.height(8.dp))
                Text(person.name, fontSize = 12.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                person.role?.let {
                    Text(it, fontSize = 11.sp, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A published review: who wrote it, where, fresh or rotten, and the quote. */
@Composable
private fun ReviewCard(review: Review, modifier: Modifier = Modifier) {
    Surface(
        onClick = { },
        modifier = modifier.confettiAnchor().width(340.dp).height(156.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.text.copy(alpha = 0.07f),
            focusedContainerColor = palette.text.copy(alpha = 0.16f),
            contentColor = palette.text,
            focusedContentColor = palette.text,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                review.fresh?.let { fresh ->
                    Text(
                        if (fresh) "Fresh" else "Rotten",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (fresh) Color(0xFFFA4F3A) else Color(0xFF8BC34A),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(review.author, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            review.source?.let { Text(it, fontSize = 11.sp, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(6.dp))
            Text(review.text, fontSize = 13.sp, lineHeight = 18.sp, color = palette.text.copy(alpha = 0.85f), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RatingCard(tile: RatingTile) {
    Surface(
        onClick = { },
        modifier = Modifier.confettiAnchor().width(196.dp).height(73.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.text.copy(alpha = 0.07f),
            focusedContainerColor = palette.text.copy(alpha = 0.16f),
            contentColor = palette.text,
            focusedContentColor = palette.text,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tile.score,
                fontFamily = PlexSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tile.source == "You") 15.sp else 22.sp,
                color = if (tile.source == "You") PlexGold else palette.text,
                modifier = Modifier.width(if (tile.source == "You") 88.dp else 64.dp),
            )
            Column {
                Text(tile.label, fontSize = 12.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tile.source, fontSize = 11.sp, color = palette.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = palette.text,
        modifier = Modifier.padding(start = SidePad),
    )
}

// ---------- Show overview ----------

/** The season last highlighted on each show's page, so coming Back lands on it again. */
private val lastSeasonFocus = mutableMapOf<String, String>()

/**
 * A show's own page, laid out like a movie's: title, details, buttons and a row of season
 * posters (picking one opens that season's episodes). Scroll down for cast, ratings and more.
 */
@Composable
private fun ShowOverview(
    api: PlexServerApi,
    d: PlexDetails,
    logo: String?,
    reloadToken: Int,
    onOpen: (PlexItem) -> Unit,
    onPlay: (PlexItem, Boolean) -> Unit,
    onSetWatched: (PlexItem, Boolean) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    val show = d.item
    val settings = LocalSettings.current
    val play = remember { FocusRequester() }
    val focused = remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scroller = remember(scroll) { SectionScroller(scroll) }
    val splitCollections = settings.collectionRows == CollectionRows.Split
    val related by produceState<List<PlexHub>>(emptyList(), show.ratingKey, reloadToken, splitCollections) {
        value = runCatching { api.related(show.ratingKey, d.collections, splitCollections) }.getOrDefault(emptyList())
    }
    val seasons by produceState<List<PlexItem>?>(null, show.ratingKey, reloadToken) {
        value = runCatching {
            api.items("/library/metadata/${show.ratingKey}/children").filter { it.type == "season" }
        }.getOrDefault(emptyList())
    }
    // What Play starts: the show's Continue Watching episode.
    val upNext by produceState<PlexItem?>(null, show.ratingKey, reloadToken) {
        value = runCatching { api.playableFor(show) }.getOrNull()
    }
    val upNextInfo by produceState<PlaybackInfo?>(null, upNext?.ratingKey) {
        value = upNext?.let { ep -> runCatching { api.playbackInfo(ep) }.getOrNull() }
    }
    var subtitlePickerOpen by remember { mutableStateOf(false) }

    var onFirstScreen by remember { mutableStateOf(true) }
    LaunchedEffect(onFirstScreen) {
        if (onFirstScreen && scroll.value != 0) scroll.animateScrollTo(0, ScrollEase)
    }

    // Coming back from a season: highlight that season again instead of Play.
    val seasonsState = rememberLazyListState()
    val seasonFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun seasonRequester(key: String) = seasonFocus.getOrPut(key) { FocusRequester() }
    LaunchedEffect(seasons) {
        val list = seasons ?: return@LaunchedEffect
        val key = lastSeasonFocus[show.ratingKey] ?: return@LaunchedEffect
        val index = list.indexOfFirst { it.ratingKey == key }
        if (index < 0) return@LaunchedEffect
        seasonsState.scrollToItem((index - 1).coerceAtLeast(0))
        repeat(5) {
            withFrameNanos { }
            if (runCatching { seasonRequester(key).requestFocus() }.isSuccess) {
                focused.value = true
                lastSeasonFocus.remove(show.ratingKey) // used up: a fresh visit starts on Play
                return@LaunchedEffect
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (scroll.value / 500f).coerceIn(0f, 1f) }
                .background(palette.background.copy(alpha = 0.88f))
        )
        Column(Modifier.fillMaxSize().verticalScroll(scroll).sectionContent(scroller)) {
            // First screen: title, details, buttons and seasons.
            Column(
                Modifier
                    .height(screen)
                    .onFocusChanged { onFirstScreen = it.hasFocus }
                    .padding(top = 36.dp, bottom = 12.dp),
            ) {
                Column(Modifier.padding(horizontal = SidePad).width(640.dp)) {
                    TitleBlock(show, logo, baseHeight = 64.dp)
                    Spacer(Modifier.height(10.dp))
                    Facts(
                        date = show.year?.toString(),
                        length = d.seasonCount?.let { if (it == 1) "1 season" else "$it seasons" },
                        item = show,
                    )
                    show.summary?.let {
                        Spacer(Modifier.height(8.dp))
                        Summary(it, lines = 3, size = 14)
                    }
                    Credits(d)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val next = upNext
                        val label = when {
                            next == null -> "Play"
                            next.progress != null -> "Resume ${episodeCode(next) ?: ""}".trim()
                            else -> "Play ${episodeCode(next) ?: ""}".trim()
                        }
                        PillButton(label, "▶", primary = true, compact = true, modifier = Modifier.focusRequester(play)) {
                            next?.let { onPlay(it, false) }
                        }
                        PillButton(
                            if (show.isWatched) "Mark unwatched" else "Mark watched",
                            if (show.isWatched) "○" else "✓",
                            compact = true,
                        ) { onSetWatched(show, !show.isWatched) }
                        LocalUpdateMetadata.current?.let { update ->
                            PillButton("Update metadata", "⟳", compact = true) { update(show) }
                        }
                        val subsInfo = upNextInfo
                        val episode = upNext
                        if (subsInfo != null && episode != null) {
                            settings.subtitlePickVersion // update the label when a pick changes
                            val chosen = chooseSubtitle(settings, episode, subsInfo.subtitles, subsInfo.audioLanguage)
                            PillButton(subtitleShortName(chosen), "CC", compact = true) { subtitlePickerOpen = true }
                            if (subtitlePickerOpen) {
                                SubtitlePicker(
                                    subtitles = subsInfo.subtitles,
                                    current = chosen,
                                    onPick = { rememberPick(settings, episode, it); subtitlePickerOpen = false },
                                    onClose = { subtitlePickerOpen = false },
                                )
                            }
                        }
                    }
                    val returning = remember { lastSeasonFocus.containsKey(show.ratingKey) }
                    if (!returning) RequestInitialFocus(play, focused)
                }

                Spacer(Modifier.weight(1f))
                val list = seasons.orEmpty()
                if (list.isNotEmpty()) {
                    SectionTitle(if (list.size == 1) "Season" else "Seasons")
                    LazyRow(
                        state = seasonsState,
                        contentPadding = PaddingValues(horizontal = SidePad, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
                    ) {
                        itemsIndexed(list, key = { _, s -> s.ratingKey }) { i, season ->
                            MediaCard(
                                api = api,
                                item = season,
                                style = CardStyle.SeasonPoster, // the season's own poster
                                standalone = true,
                                onClick = {
                                    lastSeasonFocus[show.ratingKey] = season.ratingKey
                                    onOpen(season)
                                },
                                onLongClick = { restore -> onLongPress(season, restore) },
                                modifier = Modifier.width(104.dp),
                                cardModifier = Modifier
                                    .focusRequester(seasonRequester(season.ratingKey))
                                    .glideIntoView(seasonsState, i),
                            )
                        }
                    }
                }
            }

            // Below the fold: cast, ratings and reviews, then collections and similar shows.
            Extras(api, d, related, scroller, includeCast = true, onOpen = onOpen, onLongPress = onLongPress)
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------- Collections ----------

/**
 * A collection: its artwork (or its first title's), name, size and years, a description,
 * Play and Shuffle, and everything in it along the bottom.
 */
@Composable
private fun CollectionDetails(
    api: PlexServerApi,
    d: PlexDetails,
    logo: String?,
    reloadToken: Int,
    onOpen: (PlexItem) -> Unit,
    onPlay: (PlexItem, Boolean) -> Unit,
    onShuffle: (List<PlexItem>, String) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    val collection = d.item
    // Everything in collections with this name across the movie and TV libraries (Plex keeps a
    // movie library's and a TV library's same-named collections separate); else just this one.
    val items by produceState<List<PlexItem>?>(null, collection.ratingKey, reloadToken) {
        value = runCatching { api.collectionItemsByName(collection.title) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { api.items("/library/collections/${collection.ratingKey}/children") }.getOrDefault(emptyList())
    }
    val list = items.orEmpty()
    val shows = list.filter { it.type != "movie" }
    val movies = list.filter { it.type == "movie" }
    // Both kinds: a row each, the kind of collection you opened (movie or TV) first. One kind: a single row.
    val showRow = "TV Shows in this collection" to shows
    val movieRow = "Movies in this collection" to movies
    val rows = when {
        shows.isNotEmpty() && movies.isNotEmpty() ->
            if (collection.subtype == "show") listOf(showRow, movieRow) else listOf(movieRow, showRow)
        list.isNotEmpty() -> listOf("In this collection" to list)
        else -> emptyList()
    }

    // Collections often have no artwork of their own; borrow the first title's.
    val backdropItem = if (collection.art != null) collection else list.firstOrNull { it.art != null } ?: collection
    DetailsBackdrop(api, backdropItem)

    // "Play" picks up where you are: something in progress, else the first unwatched title.
    // The kind you opened (movies or TV) is looked at first, same as the row order.
    val entryFirst = if (collection.subtype == "show") shows + movies else movies + shows
    val next = entryFirst.firstOrNull { it.progress != null } ?: entryFirst.firstOrNull { !it.isWatched } ?: entryFirst.firstOrNull()
    val play = remember { FocusRequester() }
    val focused = remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scroller = remember(scroll) { SectionScroller(scroll) }
    var onFirstScreen by remember { mutableStateOf(true) }
    LaunchedEffect(onFirstScreen) {
        if (onFirstScreen && scroll.value != 0) scroll.animateScrollTo(0, ScrollEase)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (scroll.value / 500f).coerceIn(0f, 1f) }
                .background(palette.background.copy(alpha = 0.88f))
        )
        Column(Modifier.fillMaxSize().verticalScroll(scroll).sectionContent(scroller)) {
            // First screen: title, details, buttons and the first row.
            Column(
                Modifier
                    .height(screen)
                    .onFocusChanged { onFirstScreen = it.hasFocus }
                    .padding(top = 44.dp, bottom = 10.dp),
            ) {
                Column(
                    Modifier.weight(1f).padding(horizontal = SidePad).width(620.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    TitleBlock(collection, logo, baseHeight = 70.dp)
                    Spacer(Modifier.height(10.dp))
                    val years = list.mapNotNull { it.year }
                    val span = when {
                        years.isEmpty() -> null
                        years.min() == years.max() -> years.min().toString()
                        else -> "${years.min()} to ${years.max()}"
                    }
                    val size = listOfNotNull(
                        movies.size.takeIf { it > 0 }?.let { if (it == 1) "1 movie" else "$it movies" },
                        shows.size.takeIf { it > 0 }?.let { if (it == 1) "1 show" else "$it shows" },
                    ).joinToString(", ").ifBlank { null }
                    Facts(date = span, length = size, item = collection, showGenres = false)
                    collection.summary?.let {
                        Spacer(Modifier.height(8.dp))
                        Summary(it, lines = 3)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val label = when {
                            next == null -> "Play"
                            next.progress != null -> "Resume ${next.title}"
                            else -> "Play ${next.title}"
                        }
                        PillButton(label, "▶", primary = true, modifier = Modifier.focusRequester(play)) {
                            next?.let { onPlay(it, false) }
                        }
                        PillButton("Shuffle", "⇄") { if (list.isNotEmpty()) onShuffle(list.shuffled(), collection.title) }
                    }
                    RequestInitialFocus(play, focused)
                }
                Spacer(Modifier.height(16.dp))
                rows.firstOrNull()?.let { (title, row) -> CollectionRow(api, title, row, onOpen, onLongPress) }
            }
            // Second row (when the collection has both movies and shows) below the fold.
            rows.drop(1).forEach { (title, row) ->
                Column(Modifier.section(scroller).padding(top = 8.dp)) {
                    CollectionRow(api, title, row, onOpen, onLongPress)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CollectionRow(
    api: PlexServerApi,
    title: String,
    items: List<PlexItem>,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    val rowState = rememberLazyListState()
    SectionTitle(title)
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = SidePad, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
    ) {
        itemsIndexed(items, key = { _, it -> it.ratingKey }) { i, item ->
            MediaCard(
                api = api,
                item = item,
                style = CardStyle.Poster,
                standalone = true,
                onClick = { onOpen(item) },
                onLongClick = { restore -> onLongPress(item, restore) },
                modifier = Modifier.width(118.dp),
                cardModifier = Modifier.glideIntoView(rowState, i),
            )
        }
    }
}

// ---------- Shows ----------

/** Which part of the show screen has focus; Up/Down move between these in order. */
private enum class Zone { Buttons, Tabs, Episodes }

/** Where to land in a season reached by running off the end of the previous/next one. */
private enum class Land { First, Last }

@Composable
private fun ShowDetails(
    api: PlexServerApi,
    d: PlexDetails,
    logo: String?,
    target: DetailsTarget,
    reloadToken: Int,
    onOpen: (PlexItem) -> Unit,
    onPlay: (PlexItem, Boolean) -> Unit,
    onSetWatched: (PlexItem, Boolean) -> Unit,
    onLongPress: (PlexItem, () -> Unit) -> Unit,
) {
    val show = d.item
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val openNav = LocalOpenNav.current // null unless the side nav is allowed on title pages

    var seasons by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var selectedKey by remember { mutableStateOf(target.seasonKey) }
    var upNext by remember { mutableStateOf<PlexItem?>(null) }
    // Episodes on show, tagged with their season so the row only changes once they've arrived.
    var shown by remember { mutableStateOf<Pair<String, List<PlexItem>>?>(null) }
    var focusedEpisode by remember { mutableStateOf<PlexItem?>(null) }

    // Focus bookkeeping for explicit Up/Down/Left/Right.
    var zone by remember { mutableStateOf(Zone.Buttons) }
    val playButton = remember { FocusRequester() }
    val watchedButton = remember { FocusRequester() }
    var lastButton by remember { mutableStateOf(playButton) }
    val tabFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun tabRequester(key: String) = tabFocus.getOrPut(key) { FocusRequester() }
    val entryFocus = remember { mutableMapOf<String, FocusRequester>() } // per season: the episode to enter on
    fun entryRequester(key: String) = entryFocus.getOrPut(key) { FocusRequester() }
    val entryIndex = remember { mutableStateMapOf<String, Int>() } // per season: last highlighted episode
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    var focusedIndex by remember { mutableStateOf(-1) }
    var landAt by remember { mutableStateOf<Land?>(null) }
    var holdFocus by remember { mutableStateOf(false) }
    val holder = remember { FocusRequester() }
    val tabsState = rememberLazyListState()
    val initialDone = remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var onFirstScreen by remember { mutableStateOf(true) }
    LaunchedEffect(onFirstScreen) {
        if (onFirstScreen && scroll.value != 0) {
            scroll.animateScrollTo(0, ScrollEase)
        }
    }
    val splitCollections = LocalSettings.current.collectionRows == CollectionRows.Split
    val scroller = remember(scroll) { SectionScroller(scroll) }
    val related by produceState<List<PlexHub>>(emptyList(), show.ratingKey, reloadToken, splitCollections) {
        value = runCatching { api.related(show.ratingKey, d.collections, splitCollections) }.getOrDefault(emptyList())
    }
    // Episodes still to air (Settings → Home & browsing → Upcoming episodes), shown after the
    // latest season's last episode.
    val appSettings = LocalSettings.current
    var upcomingTick by remember { mutableStateOf(0) } // bumped to re-check (download progress, after Search again)
    val lastSeasonNumber = seasons.lastOrNull()?.index
    val upcoming by produceState(emptyList<com.example.plex_tv_app.tmdb.UpcomingEpisode>(), show.ratingKey, appSettings.upcomingEpisodes, lastSeasonNumber, upcomingTick) {
        value = if (appSettings.upcomingEpisodes && seasons.isNotEmpty()) {
            com.example.plex_tv_app.tmdb.UpcomingEpisodes.forShow(d, appSettings, lastSeasonNumber, appSettings.canDownload(api.canManage), fresh = upcomingTick > 0)
        } else emptyList()
    }
    // While something's downloading, keep its progress up to date.
    LaunchedEffect(upcoming, upcomingTick) {
        if (upcoming.any { it.inQueue }) {
            kotlinx.coroutines.delay(10_000)
            upcomingTick++
        }
    }
    fun upcomingIn(seasonKey: String, episodes: List<PlexItem>): List<com.example.plex_tv_app.tmdb.UpcomingEpisode> =
        if (upcoming.isEmpty() || seasonKey != seasons.lastOrNull()?.ratingKey) emptyList()
        else upcoming.filter { u -> episodes.none { it.parentIndex == u.season && it.index == u.number } }

    // Seasons, and the episode to "Play" next: the show's Continue Watching episode (as in the
    // Plex apps), else the first one in progress or not yet watched.
    LaunchedEffect(show.ratingKey, reloadToken) {
        runCatching {
            val all = api.items("/library/metadata/${show.ratingKey}/children").filter { it.type == "season" }
            seasons = all
            val deck = runCatching { api.onDeckFor(show.ratingKey) }.getOrNull()
            val deckSeason = deck?.parentRatingKey?.let { key -> all.firstOrNull { it.ratingKey == key } }
            val current = deckSeason
                ?: all.firstOrNull { (it.viewedLeafCount ?: 0) < (it.leafCount ?: 0) } ?: all.firstOrNull()
            if (selectedKey == null) selectedKey = current?.ratingKey
            upNext = deck ?: current?.let { season ->
                val eps = api.items("/library/metadata/${season.ratingKey}/children")
                eps.firstOrNull { it.progress != null } ?: eps.firstOrNull { !it.isWatched } ?: eps.firstOrNull()
            }
        }
    }
    // Subtitles for the episode Play will start (a pick here applies to the whole show).
    val upNextInfo by produceState<PlaybackInfo?>(null, upNext?.ratingKey) {
        value = upNext?.let { ep -> runCatching { api.playbackInfo(ep) }.getOrNull() }
    }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    val subsButton = remember { FocusRequester() }
    val metadataButton = remember { FocusRequester() }
    // Episodes for the selected season (a short pause when flicking across tabs keeps it smooth).
    LaunchedEffect(selectedKey, reloadToken) {
        val key = selectedKey ?: return@LaunchedEffect
        if (shown != null && landAt == null) delay(140)
        runCatching { shown = key to api.items("/library/metadata/$key/children") }
    }
    // Keep the selected tab in view as the season changes.
    LaunchedEffect(selectedKey, seasons) {
        val index = seasons.indexOfFirst { it.ratingKey == selectedKey }
        if (index < 0) return@LaunchedEffect
        val visible = tabsState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= tabsState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) tabsState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    fun seasonIndex() = seasons.indexOfFirst { it.ratingKey == selectedKey }

    fun focusButtons() {
        lastButton.tryFocus() || playButton.tryFocus()
    }

    fun focusEpisodes() {
        val key = shown?.first ?: return
        if (entryRequester(key).tryFocus()) return
        scope.launch { // the entry episode may have scrolled away
            rowStates[key]?.scrollToItem((entryIndex[key] ?: 0).coerceAtLeast(0))
            withFrameNanos { }
            entryRequester(key).tryFocus()
        }
    }

    fun focusSelectedTab() {
        val key = selectedKey ?: return
        if (tabRequester(key).tryFocus()) return
        scope.launch { // the tab may be scrolled out of view
            tabsState.scrollToItem((seasonIndex() - 1).coerceAtLeast(0))
            withFrameNanos { }
            tabRequester(key).tryFocus()
        }
    }

    /** Run off the end of a season into the next/previous one. The first and last seasons stop. */
    fun crossSeason(step: Int) {
        val next = seasons.getOrNull(seasonIndex() + step) ?: return
        landAt = if (step > 0) Land.First else Land.Last
        holdFocus = true
        holder.tryFocus() // hold focus here while the next season's episodes load
        selectedKey = next.ratingKey
    }

    // After crossing seasons (or opening on a particular episode), focus the right episode.
    LaunchedEffect(shown?.first) {
        val key = shown?.first ?: return@LaunchedEffect
        val wantInitialEpisode = !initialDone.value && target.episodeKey != null && key == (target.seasonKey ?: selectedKey)
        if (landAt == null && !wantInitialEpisode) {
            if (!initialDone.value) {
                repeat(5) { if (playButton.tryFocus()) { initialDone.value = true; return@LaunchedEffect }; withFrameNanos { } }
            }
            return@LaunchedEffect
        }
        repeat(6) {
            withFrameNanos { }
            if (entryRequester(key).tryFocus()) {
                initialDone.value = true
                landAt = null
                holdFocus = false
                return@LaunchedEffect
            }
        }
        landAt = null
        holdFocus = false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val screen = maxHeight
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = (scroll.value / 500f).coerceIn(0f, 1f) }
            .background(palette.background.copy(alpha = 0.88f))
    )
    Column(Modifier.fillMaxSize().verticalScroll(scroll).sectionContent(scroller)) {
    Column(
        Modifier
            .height(screen)
            .onFocusChanged { onFirstScreen = it.hasFocus }
            .padding(top = 44.dp, bottom = 10.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || landAt != null) return@onPreviewKeyEvent landAt != null
                val hasTabs = seasons.size > 1
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        when (zone) {
                            Zone.Buttons -> if (hasTabs) focusSelectedTab() else focusEpisodes()
                            Zone.Tabs -> focusEpisodes()
                            Zone.Episodes -> return@onPreviewKeyEvent false // carry on down into cast, ratings...
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        when (zone) {
                            Zone.Episodes -> if (hasTabs) focusSelectedTab() else focusButtons()
                            Zone.Tabs -> focusButtons()
                            Zone.Buttons -> Unit
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> when (zone) {
                        Zone.Episodes -> {
                            val count = shown?.let { it.second.size + upcomingIn(it.first, it.second).size } ?: 0
                            if (focusedIndex == count - 1) { crossSeason(+1); true } else false
                        }
                        Zone.Tabs -> seasonIndex() == seasons.lastIndex // stop at the last season
                        // Stop at the last button (CC if there is one, else Update metadata).
                        Zone.Buttons -> lastButton == subsButton || (lastButton == metadataButton && upNextInfo == null)
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> when (zone) {
                        // The very start of a row: the side nav, if it's allowed on title pages.
                        Zone.Episodes -> if (focusedIndex == 0) {
                            if (seasonIndex() <= 0) openNav?.invoke() else crossSeason(-1)
                            true
                        } else false
                        Zone.Tabs -> if (seasonIndex() == 0) { openNav?.invoke(); true } else false
                        Zone.Buttons -> if (lastButton == playButton) { openNav?.invoke(); true } else false
                    }
                    else -> false
                }
            },
    ) {
        // Title, details and buttons
        Column(
            Modifier.weight(1f).padding(horizontal = SidePad).width(600.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            TitleBlock(show, logo, baseHeight = 60.dp)
            Spacer(Modifier.height(8.dp))
            // Show info, or the highlighted episode's, cross-fading in a fixed space so nothing jumps.
            Box(Modifier.height(88.dp)) {
                AnimatedContent(
                    targetState = focusedEpisode,
                    contentKey = { it?.ratingKey },
                    transitionSpec = {
                        (fadeIn(tween(240, delayMillis = 50)) + slideInVertically(tween(240)) { it / 10 }) togetherWith fadeOut(tween(120))
                    },
                    label = "showInfo",
                ) { episode ->
                    if (episode == null) {
                        Column {
                            Facts(
                                date = show.year?.toString(),
                                length = d.seasonCount?.let { if (it == 1) "1 season" else "$it seasons" },
                                item = show,
                            )
                            show.summary?.let {
                                Spacer(Modifier.height(6.dp))
                                Summary(it, lines = 2, size = 14)
                            }
                        }
                    } else {
                        EpisodeInfo(episode)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val next = upNext
                val label = when {
                    next == null -> "Play"
                    next.progress != null -> "Resume ${episodeCode(next) ?: ""}".trim()
                    else -> "Play ${episodeCode(next) ?: ""}".trim()
                }
                PillButton(
                    label, "▶", primary = true, compact = true,
                    modifier = Modifier.focusRequester(playButton),
                    onFocused = {
                        zone = Zone.Buttons
                        lastButton = playButton
                        focusedEpisode = null
                    },
                ) { next?.let { onPlay(it, false) } }
                PillButton(
                    if (show.isWatched) "Mark unwatched" else "Mark watched",
                    if (show.isWatched) "○" else "✓",
                    compact = true,
                    modifier = Modifier.focusRequester(watchedButton),
                    onFocused = {
                        zone = Zone.Buttons
                        lastButton = watchedButton
                        focusedEpisode = null
                    },
                ) { onSetWatched(show, !show.isWatched) }
                LocalUpdateMetadata.current?.let { update ->
                    PillButton(
                        "Update metadata", "⟳",
                        compact = true,
                        modifier = Modifier.focusRequester(metadataButton),
                        onFocused = {
                            zone = Zone.Buttons
                            lastButton = metadataButton
                            focusedEpisode = null
                        },
                    ) { update(show) }
                }
                val subsInfo = upNextInfo
                val episode = upNext
                if (subsInfo != null && episode != null) {
                    val settings = LocalSettings.current
                    settings.subtitlePickVersion // update the label when a pick changes
                    val chosen = chooseSubtitle(settings, episode, subsInfo.subtitles, subsInfo.audioLanguage)
                    PillButton(
                        subtitleShortName(chosen), "CC",
                        compact = true,
                        modifier = Modifier.focusRequester(subsButton),
                        onFocused = {
                            zone = Zone.Buttons
                            lastButton = subsButton
                            focusedEpisode = null
                        },
                    ) { subtitlePickerOpen = true }
                    if (subtitlePickerOpen) {
                        SubtitlePicker(
                            subtitles = subsInfo.subtitles,
                            current = chosen,
                            onPick = { rememberPick(settings, episode, it); subtitlePickerOpen = false },
                            onClose = { subtitlePickerOpen = false },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Season tabs, with one gold underline that glides to the selected season.
        if (seasons.size > 1) {
            val tabBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() } // x and width, in px
            var rowX by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxWidth().onGloballyPositioned { rowX = it.positionInRoot().x }) {
                LazyRow(
                    state = tabsState,
                    contentPadding = PaddingValues(horizontal = SidePad - 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(seasons, key = { _, it -> it.ratingKey }) { tabIndex, season ->
                        SeasonTab(
                            season = season,
                            selected = season.ratingKey == selectedKey,
                            modifier = Modifier
                                .focusRequester(tabRequester(season.ratingKey))
                                .glideIntoView(tabsState, tabIndex)
                                .onGloballyPositioned { c ->
                                    tabBounds[season.ratingKey] = c.positionInRoot().x to c.size.width.toFloat()
                                },
                            onFocused = {
                                zone = Zone.Tabs
                                focusedEpisode = null
                                selectedKey = season.ratingKey
                            },
                        )
                    }
                }
                tabBounds[selectedKey]?.let { (x, width) ->
                    val line = with(density) { 20.dp.toPx() }
                    val lineX by animateFloatAsState(
                        targetValue = x - rowX + (width - line) / 2f,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                        label = "seasonUnderline",
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset { IntOffset(lineX.roundToInt(), 0) }
                            .padding(bottom = 3.dp)
                            .width(20.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(PlexGold)
                    )
                }
            }
        }

        // Episodes: slide in from the side when the season changes.
        val current = shown
        Box(
            Modifier
                .fillMaxWidth()
                .height(178.dp)
                // Only focusable for the moment between seasons, so focus never falls out.
                .focusRequester(holder)
                .focusProperties { canFocus = holdFocus }
                .focusable(),
        ) {
            if (current != null) {
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.first },
                    transitionSpec = {
                        val forward = landAt != Land.Last
                        (fadeIn(tween(260)) + slideInHorizontally(tween(300)) { if (forward) it / 10 else -it / 10 }) togetherWith
                            fadeOut(tween(140))
                    },
                    label = "episodes",
                ) { (seasonKey, episodes) ->
                    val startIndex = remember(seasonKey) {
                        when (landAt) {
                            Land.First -> 0
                            Land.Last -> episodes.lastIndex
                            null -> {
                                val wanted = if (seasonKey == target.seasonKey) target.episodeKey ?: upNext?.ratingKey else upNext?.ratingKey
                                episodes.indexOfFirst { it.ratingKey == wanted }.coerceAtLeast(0)
                            }
                        }.coerceAtLeast(0)
                    }
                    // Arriving from a neighbouring season lands on its first/last episode;
                    // otherwise come back to the episode you last had highlighted here.
                    val enterAt = if (landAt != null) startIndex else entryIndex[seasonKey] ?: startIndex
                    val listState = remember(seasonKey) {
                        LazyListState((startIndex - 1).coerceAtLeast(0)).also { rowStates[seasonKey] = it }
                    }
                    val coming = upcomingIn(seasonKey, episodes)
                    val comingImage = remember(show.ratingKey) { api.imageUrl(show.art ?: show.thumb, 480, 270) }
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = SidePad, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
                    ) {
                        itemsIndexed(episodes, key = { _, ep -> ep.ratingKey }) { i, ep ->
                            MediaCard(
                                api = api,
                                item = ep,
                                style = CardStyle.Thumbnail,
                                standalone = false,
                                onClick = { onPlay(ep, false) },
                                onFocused = {
                                    zone = Zone.Episodes
                                    focusedIndex = i
                                    entryIndex[seasonKey] = i
                                    focusedEpisode = ep
                                },
                                onLongClick = { restore -> onLongPress(ep, restore) },
                                modifier = Modifier.width(206.dp),
                                cardModifier = (if (i == enterAt) Modifier.focusRequester(entryRequester(seasonKey)) else Modifier)
                                    .glideIntoView(listState, i),
                            )
                        }
                        // Episodes still to come, after the last one you have.
                        itemsIndexed(coming, key = { _, u -> "upcoming:${u.season}:${u.number}" }) { j, u ->
                            UpcomingEpisodeCard(
                                episode = u,
                                fallbackImage = comingImage,
                                differentSeason = episodes.firstOrNull()?.parentIndex?.let { it != u.season } ?: false,
                                owner = appSettings.canDownload(api.canManage),
                                onChanged = { upcomingTick++ },
                                modifier = Modifier.width(206.dp),
                                cardModifier = Modifier.glideIntoView(listState, episodes.size + j),
                                onFocused = {
                                    zone = Zone.Episodes
                                    focusedIndex = episodes.size + j
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    // Below the fold: cast, ratings and reviews, collections and similar titles.
    Extras(api, d, related, scroller, includeCast = true, onOpen = onOpen, onLongPress = onLongPress)
    }
    }
}

@Composable
private fun EpisodeInfo(episode: PlexItem) {
    val hide = LocalSettings.current.hideUnwatchedSummaries && episode.isUnwatchedEpisode
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            episodeCode(episode)?.let {
                Text(it, color = PlexGold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                episode.title,
                fontFamily = PlexSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Facts(date = dateText(episode.releaseDate), length = episode.duration?.let(::lengthText), item = episode, showGenres = false)
        Spacer(Modifier.height(6.dp))
        when {
            hide -> Text("Description hidden until you've watched it.", fontSize = 13.sp, fontStyle = FontStyle.Italic, color = palette.muted)
            episode.summary != null -> Summary(episode.summary, lines = 2, size = 14)
        }
    }
}

/** A season tab: plain text, a bright pill when highlighted. (The gold underline is drawn by the row.) */
@Composable
private fun SeasonTab(season: PlexItem, selected: Boolean, modifier: Modifier, onFocused: () -> Unit) {
    Surface(
        onClick = onFocused,
        modifier = modifier
            .confettiAnchor()
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = palette.text,
            contentColor = if (selected) palette.text else palette.muted,
            focusedContentColor = palette.background,
        ),
    ) {
        Text(
            season.title,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// ---------- Shared pieces ----------

/** The title's logo artwork if it has one, otherwise its name in Plex Serif. */
@Composable
private fun TitleBlock(item: PlexItem, logo: String?, baseHeight: Dp) {
    // Title logos follow Settings → Text size like the rest of the text.
    val textScale = LocalSettings.current.textScale
    val height = baseHeight * textScale
    if (logo != null) {
        RemoteImage(
            url = logo,
            placeholder = Color.Transparent,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            transparent = true,
            modifier = Modifier.width(400.dp * textScale).height(height),
        )
    } else {
        Text(
            item.title,
            fontFamily = PlexSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (baseHeight < 70.dp) 28.sp else 34.sp,
            lineHeight = if (baseHeight < 70.dp) 32.sp else 38.sp,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Date, length, age rating, audience score and genres, spaced apart. */
@Composable
private fun Facts(date: String?, length: String?, item: PlexItem, showGenres: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        date?.let { Text(it, fontSize = 13.sp, color = palette.muted) }
        length?.let { Text(it, fontSize = 13.sp, color = palette.muted) }
        item.contentRating?.let {
            Text(
                it,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                modifier = Modifier
                    .border(1.dp, palette.hairline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        item.audienceRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("★", color = PlexGold, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(String.format(Locale.US, "%.1f", it), color = palette.text, fontSize = 13.sp)
            }
        }
        if (showGenres && item.genres.isNotEmpty()) {
            Text(item.genres.take(3).joinToString(", "), fontSize = 13.sp, color = palette.muted)
        }
    }
}

@Composable
private fun Summary(text: String, lines: Int, size: Int = 15) {
    Text(
        text,
        fontSize = size.sp,
        lineHeight = 21.sp,
        color = palette.text.copy(alpha = 0.85f),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Credits(d: PlexDetails) {
    val lines = listOfNotNull(
        d.directors.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.take(2).joinToString(" and ")}" },
        d.cast.takeIf { it.isNotEmpty() }?.let { cast -> "Starring ${cast.take(3).joinToString(", ") { it.name }}" },
    )
    if (lines.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    lines.forEach { Text(it, fontSize = 13.sp, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A rounded button. The primary one (Play) uses the theme's accent; all go bright when highlighted. */
@Composable
private fun PillButton(
    label: String,
    glyph: String? = null,
    primary: Boolean = false,
    compact: Boolean = false,
    detail: String? = null,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .confettiAnchor()
            .returnFocusHere()
            .height(if (compact) 36.dp else 42.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) palette.accent else palette.text.copy(alpha = 0.12f),
            contentColor = if (primary) palette.onAccent else palette.text,
            focusedContainerColor = palette.text,
            focusedContentColor = palette.background,
        ),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = if (compact) 16.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                Text(glyph, fontSize = if (compact) 12.sp else 13.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) {
                Spacer(Modifier.width(10.dp))
                Text(detail, fontSize = 12.sp, modifier = Modifier.graphicsLayer { alpha = 0.75f })
            }
        }
    }
}

/** Full-screen artwork, slowly drifting (if allowed), shaded so the text on the left reads. */
@Composable
private fun DetailsBackdrop(api: PlexServerApi, item: PlexItem) {
    val motion = LocalSettings.current.backdropMotion
    val drift = rememberInfiniteTransition(label = "detailsDrift")
    val zoom by drift.animateFloat(
        initialValue = 1f,
        targetValue = if (motion) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    val url = api.imageUrl(item.art ?: item.thumb, 1280, 720)
    // Reaches under the side nav's icon strip, when that's shown on title pages.
    val bleed = LocalNavInset.current
    Crossfade(targetState = url, animationSpec = tween(600), label = "detailsBackdrop", modifier = Modifier.bleedLeft(bleed)) { target ->
        RemoteImage(
            url = target,
            placeholder = Color.Transparent,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
            },
        )
    }
    val bg = palette.background
    Box(
        Modifier.bleedLeft(bleed).fillMaxSize().background(
            Brush.horizontalGradient(
                0f to bg.copy(alpha = 0.97f),
                0.42f to bg.copy(alpha = 0.82f),
                0.75f to bg.copy(alpha = 0.2f),
                1f to Color.Transparent,
            )
        )
    )
    Box(
        Modifier.bleedLeft(bleed).fillMaxSize().background(
            Brush.verticalGradient(
                0f to bg.copy(alpha = 0.5f),
                0.2f to Color.Transparent,
                0.5f to Color.Transparent,
                0.78f to bg.copy(alpha = 0.92f),
                1f to bg,
            )
        )
    )
}

private fun dateText(date: String?): String? =
    date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        ?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))

private fun lengthText(ms: Long): String {
    val minutes = (ms / 60_000).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
}
