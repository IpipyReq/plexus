package com.example.plex_tv_app.ui

import androidx.compose.runtime.ReadOnlyComposable
import com.example.plex_tv_app.ui.theme.palette
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import com.example.plex_tv_app.settings.CardText
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexHub
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.ui.theme.PlexSerif
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ---------- Look ----------

// Colours come from the current theme (Settings → Appearance).
/** The page colour the artwork fades into. */
private val ScreenDark: Color @Composable @ReadOnlyComposable get() = palette.background
private val Bone: Color @Composable @ReadOnlyComposable get() = palette.text // titles
private val Mist: Color @Composable @ReadOnlyComposable get() = palette.muted // secondary text
private val Faint: Color @Composable @ReadOnlyComposable get() = palette.hairline // hairlines, badge borders

/** The hero title when a title has no logo artwork: Plex Serif, kept modest. */
private val HeroTitle: TextStyle
    @Composable @ReadOnlyComposable get() = TextStyle(
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        color = palette.text,
        // A soft shadow lifts light text off busy artwork; dark text on light themes doesn't need it.
        shadow = if (palette.isDark) Shadow(Color(0x99000000), Offset(0f, 2f), 14f) else null,
    )
private val HeroTop = 28.dp // keeps the hero clear of the top edge
private val HeroBottom = 10.dp
private val SummaryLine = 21.dp // one line of description
private val FactSize = 13.sp // the small details line under the title
private val SummarySize = 15.sp // description: a touch bigger than the details

/** What the hero shows: the item, plus its logo artwork if the server has one. */
internal data class Hero(val item: PlexItem, val logoUrl: String?)

/**
 * Where you were on Home: the current row, each row's scroll position and highlighted card,
 * and what the hero showed. Kept by the Shell, so opening something and pressing Back
 * returns you to exactly the same spot.
 */
class HomeMemory {
    var selectedKey by mutableStateOf<String?>(null)
    internal val rowStates = mutableMapOf<String, LazyListState>()
    internal val requesters = mutableMapOf<String, FocusRequester>()
    internal val lastIndex = mutableStateMapOf<String, Int>()
    internal var focusedItem: PlexItem? = null
    internal var hero: Hero? = null
}

/** How much of the next row shows at the bottom of the screen. */
private val Peek = 48.dp

/** Space the side nav's icon strip covers; the artwork extends underneath it. */

// ---------- Layout maths ----------

/** Continue Watching and rows with episodes default to wide thumbnails, like the Plex app. */
fun PlexHub.isLandscape(): Boolean =
    items.any { it.type == "episode" } || id.contains("ondeck", ignoreCase = true)

/** The style a row uses: your choice from the hold menu, or a sensible default. */
fun PlexHub.styleIn(styles: Map<String, CardStyle>): CardStyle =
    styles[rowKey] ?: if (isLandscape()) CardStyle.Thumbnail else CardStyle.Poster

private fun cardWidth(style: CardStyle, scale: Float): Dp = style.baseWidth() * scale

/**
 * Total height of one row: heading (unless row titles are off) + card image + the text under it
 * (two lines, one, or none; see Settings → Home & browsing).
 */
private fun rowHeight(style: CardStyle, scale: Float, text: CardText = CardText.Full, titles: Boolean = true): Dp {
    val heading = if (titles) 22.dp else 0.dp
    val under = when (text) {
        CardText.Full -> 46.dp
        CardText.Title -> 28.dp
        CardText.None -> 0.dp
    }
    return cardWidth(style, scale) * style.heightRatio() + 40.dp + heading + under
}

// ---------- Screen ----------

/**
 * The landing page. One row sits in the lower part of the screen with the next row peeking
 * in underneath; the highlighted title takes over everything above it.
 */
@Composable
fun HomeScreen(
    api: PlexServerApi,
    hubs: List<PlexHub>?,
    error: String?,
    cardScale: Float,
    rowStyles: Map<String, CardStyle>,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, rowKey: String, restoreFocus: () -> Unit) -> Unit,
    memory: HomeMemory,
    topInset: Dp = 0.dp, // space taken by a header drawn on top (a library's name and tabs)
    entry: FocusRequester? = null, // also attached to the current row's card, for the page around it
    focusOnStart: Boolean = true, // take focus when first shown
    onFocusInfo: ((row: Int, column: Int) -> Unit)? = null, // which row/card is highlighted
) {
    when {
        error != null -> Message("Couldn't load Home. $error")
        hubs == null -> Message("Loading…")
        hubs.isEmpty() -> Message("Add movies or TV shows to a library on your server and they'll appear here.")
        else -> HeroHome(api, hubs, cardScale, rowStyles, onOpen, onLongPress, memory, topInset, entry, focusOnStart, onFocusInfo)
    }
}

@Composable
private fun HeroHome(
    api: PlexServerApi,
    hubs: List<PlexHub>,
    cardScale: Float,
    rowStyles: Map<String, CardStyle>,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, String, () -> Unit) -> Unit,
    memory: HomeMemory,
    topInset: Dp,
    entry: FocusRequester?,
    focusOnStart: Boolean,
    onFocusInfo: ((Int, Int) -> Unit)?,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Which row is "current", by key, so it survives reordering and refreshes.
    val selectedKey = memory.selectedKey?.takeIf { key -> hubs.any { it.rowKey == key } } ?: hubs.first().rowKey
    val selected = hubs.indexOfFirst { it.rowKey == selectedKey }.coerceAtLeast(0)
    val current = hubs[selected]
    val next = hubs.getOrNull(selected + 1)

    // Per-row memory: scroll position, last highlighted card, and a handle to focus it again.
    val rowStates = memory.rowStates
    val requesters = memory.requesters
    val lastIndex = memory.lastIndex
    fun stateOf(key: String) = rowStates.getOrPut(key) { LazyListState() }
    fun requesterOf(key: String) = requesters.getOrPut(key) { FocusRequester() }

    // The highlighted item, and what the hero shows. The hero settles briefly (so fast scrolling
    // stays smooth) and looks up the logo first, so the title and logo change together.
    var focusedItem by remember { mutableStateOf(memory.focusedItem ?: current.items.firstOrNull()) }
    var hero by remember { mutableStateOf(memory.hero) }
    LaunchedEffect(focusedItem) {
        val item = focusedItem ?: return@LaunchedEffect
        if (hero?.item?.ratingKey == item.ratingKey) return@LaunchedEffect // already showing (e.g. coming back)
        delay(160)
        hero = Hero(item, api.logoUrlFor(item)).also { memory.hero = it }
    }

    // Settings → Home & browsing → the minimal-look options.
    val settings = LocalSettings.current
    val cardText = settings.homeCardText
    val rowTitles = settings.showRowTitles
    val peek = settings.peekNextRow

    // Rows slide into place when you move between them.
    val slide = remember { Animatable(0f) }
    fun moveTo(index: Int) {
        if (index !in hubs.indices || index == selected) return
        val currentStyle = current.styleIn(rowStyles)
        val target = hubs[index]
        val startOffsetPx = with(density) {
            if (index > selected) rowHeight(target.styleIn(rowStyles), cardScale, cardText, rowTitles).toPx()
            else -rowHeight(currentStyle, cardScale, cardText, rowTitles).toPx()
        }
        memory.selectedKey = target.rowKey
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            slide.snapTo(startOffsetPx)
            slide.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        }
    }

    // Put focus on the current row's remembered card whenever the row changes (and on first show,
    // unless the page around us is keeping focus, e.g. on a tab).
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(selectedKey) {
        val skip = firstRun[0] && !focusOnStart
        firstRun[0] = false
        if (skip) return@LaunchedEffect
        repeat(4) {
            val result = runCatching { requesterOf(selectedKey).requestFocus() }
            if (result.isSuccess && (result.getOrNull() as Any?) != false) return@LaunchedEffect
            withFrameNanos { } // the row's cards may not be laid out yet
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val currentStyle = current.styleIn(rowStyles)
        val rowTop = maxHeight - (if (peek) Peek else 16.dp) - rowHeight(currentStyle, cardScale, cardText, rowTitles)
        val heroHeight by animateDpAsState(rowTop, tween(420, easing = FastOutSlowInEasing), label = "heroHeight")

        // Artwork + shading, reaching under the nav strip.
        Box(Modifier.bleedLeft(LocalNavInset.current).fillMaxSize().background(ScreenDark)) {
            Backdrop(api, hero?.item)
            Scrims()
        }

        // The highlighted title, sitting just above the current row.
        Box(
            modifier = Modifier
                .padding(start = 48.dp)
                .width(640.dp)
                .height(heroHeight)
                .padding(top = HeroTop + topInset, bottom = HeroBottom),
            contentAlignment = Alignment.BottomStart,
        ) {
            HeroInfo(hero, space = heroHeight - HeroTop - topInset - HeroBottom)
        }

        // Current row + the next one peeking in. They're clipped just above the current row's
        // spot, so a row arriving from above (going Up) slides out from under the description
        // instead of being drawn over it. (A little headroom keeps a highlighted, enlarged card whole.)
        val headroom = 16.dp
        Box(
            Modifier
                .offset { IntOffset(0, (rowTop - headroom).roundToPx()) }
                .fillMaxSize()
                .clipToBounds(),
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (headroom.toPx() + slide.value).roundToInt()) }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (next != null) {
                                runCatching { requesterOf(next.rowKey).requestFocus() }
                                moveTo(selected + 1)
                            }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (selected > 0) moveTo(selected - 1)
                            true
                        }
                        else -> false
                    }
                },
        ) {
            CompositionLocalProvider(LocalCardText provides cardText) {
            (if (peek) listOfNotNull(current, next) else listOf(current)).forEach { hub ->
                key(hub.rowKey) {
                    HubRow(
                        api = api,
                        hub = hub,
                        style = hub.styleIn(rowStyles),
                        cardScale = cardScale,
                        isCurrent = hub.rowKey == current.rowKey,
                        listState = stateOf(hub.rowKey),
                        requester = requesterOf(hub.rowKey),
                        extraEntry = if (hub.rowKey == current.rowKey) entry else null,
                        rememberedIndex = lastIndex[hub.rowKey] ?: 0,
                        onCardFocused = { i, item ->
                            lastIndex[hub.rowKey] = i
                            onFocusInfo?.invoke(hubs.indexOfFirst { it.rowKey == hub.rowKey }, i)
                            focusedItem = item
                            memory.focusedItem = item
                            // Focus can wander into the peeking row (e.g. Right at the end of a row).
                            if (hub.rowKey != selectedKey) moveTo(hubs.indexOfFirst { it.rowKey == hub.rowKey })
                        },
                        onOpen = onOpen,
                        onLongPress = onLongPress,
                        cardText = cardText,
                        showTitle = rowTitles,
                    )
                }
            }
            }
        }
        }
    }
}

@Composable
private fun HubRow(
    api: PlexServerApi,
    hub: PlexHub,
    style: CardStyle,
    cardScale: Float,
    isCurrent: Boolean,
    listState: LazyListState,
    requester: FocusRequester,
    extraEntry: FocusRequester?,
    rememberedIndex: Int,
    onCardFocused: (Int, PlexItem) -> Unit,
    onOpen: (PlexItem) -> Unit,
    onLongPress: (PlexItem, String, () -> Unit) -> Unit,
    cardText: CardText,
    showTitle: Boolean,
) {
    val alpha by animateFloatAsState(if (isCurrent) 1f else 0.5f, tween(300), label = "rowAlpha")
    Column(
        Modifier
            .fillMaxWidth()
            .height(rowHeight(style, cardScale, cardText, showTitle))
            .graphicsLayer { this.alpha = alpha },
    ) {
        if (showTitle) {
            Text(
                hub.title,
                fontFamily = PlexSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = Bone,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(cardGap(20.dp)),
        ) {
            // Keyed by item, so a refresh that shifts things along keeps your highlight on the same title.
            itemsIndexed(hub.items, key = { _, item -> item.ratingKey }) { i, item ->
                MediaCard(
                    api = api,
                    item = item,
                    style = style,
                    standalone = true,
                    onClick = { onOpen(item) },
                    onFocused = { onCardFocused(i, item) },
                    onLongClick = { restore -> onLongPress(item, hub.rowKey, restore) },
                    modifier = Modifier.width(cardWidth(style, cardScale)),
                    cardModifier = (
                        if (i == rememberedIndex) {
                            Modifier.focusRequester(requester).let { m -> if (extraEntry != null) m.focusRequester(extraEntry) else m }
                        } else Modifier
                    ).glideIntoView(listState, i),
                )
            }
        }
    }
}

// ---------- Hero ----------

/** The highlighted title's backdrop, cross-fading between titles and drifting very slowly. */
@Composable
private fun Backdrop(api: PlexServerApi, item: PlexItem?) {
    val url = item?.let { api.imageUrl(it.art ?: it.thumb, 1280, 720) }
    val motion = LocalSettings.current.backdropMotion
    val drift = rememberInfiniteTransition(label = "drift")
    val zoom by drift.animateFloat(
        initialValue = 1f,
        targetValue = if (motion) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    Crossfade(targetState = url, animationSpec = tween(700), label = "backdrop") { target ->
        RemoteImage(
            url = target,
            placeholder = Color.Transparent,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
            },
        )
    }
}

/** Shading so text reads over any artwork: dark from the left, and from the bottom where the rows sit. */
@Composable
private fun Scrims() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to ScreenDark.copy(alpha = 0.96f),
                0.38f to ScreenDark.copy(alpha = 0.78f),
                0.7f to ScreenDark.copy(alpha = 0.15f),
                1f to Color.Transparent,
            )
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to ScreenDark.copy(alpha = 0.55f),
                0.18f to Color.Transparent,
                0.45f to Color.Transparent,
                0.72f to ScreenDark.copy(alpha = 0.9f),
                1f to ScreenDark,
            )
        )
    )
}

/**
 * The title block. [space] is the room between the top of the screen and the current row; the
 * logo/title shrinks first, then the description loses lines, so nothing runs off the top.
 */
@Composable
private fun HeroInfo(hero: Hero?, space: Dp) {
    AnimatedContent(
        targetState = hero,
        contentKey = { it?.item?.ratingKey },
        transitionSpec = {
            (fadeIn(tween(380, delayMillis = 80)) + slideInVertically(tween(380)) { it / 14 }) togetherWith
                fadeOut(tween(160))
        },
        // Grow upwards from the row if there's a lot to show, rather than squashing the description.
        modifier = Modifier.wrapContentHeight(align = Alignment.Bottom, unbounded = true),
        label = "hero",
    ) { shown ->
        if (shown == null) return@AnimatedContent
        val item = shown.item
        val isEpisode = item.type == "episode"
        val hideSummary = LocalSettings.current.hideUnwatchedSummaries
        val showSummary = LocalSettings.current.showHomeSummary
        val hasEpisodeLine = isEpisode || item.type == "season"

        // Fixed parts: the details line plus gaps (~40dp), and the episode line if any (~30dp).
        val fixed = 40.dp + (if (hasEpisodeLine) 30.dp else 0.dp) + (if (item.progress != null) 15.dp else 0.dp)
        val wantedSummary = if (item.summary != null && showSummary) SummaryLine * 3 else 0.dp
        // The title (logo) is sized by Settings → Text size; it only shrinks if there isn't room.
        val textScale = LocalSettings.current.textScale
        val titleHeight = (space - fixed - wantedSummary).coerceIn(40.dp * textScale, 76.dp * textScale)
        val summaryLines = ((space - fixed - titleHeight) / SummaryLine).toInt().coerceIn(0, 3)

        Column {
            val logo = shown.logoUrl
            if (logo != null) {
                RemoteImage(
                    url = logo,
                    placeholder = Color.Transparent,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    transparent = true,
                    modifier = Modifier.width(340.dp * textScale).height(titleHeight),
                )
            } else {
                val title = when (item.type) {
                    "episode" -> item.grandparentTitle ?: item.title
                    "season" -> item.parentTitle ?: item.title
                    else -> item.title
                }
                Text(
                    title,
                    style = HeroTitle,
                    maxLines = if (titleHeight >= 76.dp) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (hasEpisodeLine) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    episodeCode(item)?.takeIf { isEpisode }?.let {
                        Text(it, color = PlexGold, fontWeight = FontWeight.Bold, fontSize = SummarySize)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        item.title,
                        color = Bone,
                        fontFamily = PlexSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = SummarySize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Facts(item)

            item.summary?.takeIf { showSummary && summaryLines > 0 && !(hideSummary && item.isUnwatchedEpisode) }?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    color = Bone.copy(alpha = 0.85f),
                    fontSize = SummarySize,
                    lineHeight = 21.sp,
                    maxLines = summaryLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(560.dp),
                )
            }

            val progress = item.progress
            val duration = item.duration
            val offset = item.viewOffset
            if (progress != null && duration != null && offset != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(160.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Faint)
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(PlexGold))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${runtimeText(duration - offset)} left", color = Mist, fontSize = FactSize)
                }
            }
        }
    }
}

/** Release date, runtime, age rating, audience score and genres, spaced apart rather than strung together. */
@Composable
private fun Facts(item: PlexItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        releaseText(item)?.let { Text(it, color = Mist, fontSize = FactSize) }
        if (item.type == "movie" || item.type == "episode") {
            item.duration?.let { Text(runtimeText(it), color = Mist, fontSize = FactSize) }
        }
        item.contentRating?.let {
            Text(
                it,
                color = Bone,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .border(1.dp, Faint, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        item.audienceRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("★", color = PlexGold, fontSize = FactSize)
                Spacer(Modifier.width(4.dp))
                Text(String.format(Locale.US, "%.1f", it), color = Bone, fontSize = FactSize)
            }
        }
        if (item.genres.isNotEmpty()) {
            Text(item.genres.take(2).joinToString(", "), color = Mist, fontSize = FactSize)
        }
    }
}

// ---------- Helpers ----------

/** Lets a full-screen layer extend [amount] further left than its parent, under the nav strip. */
internal fun Modifier.bleedLeft(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-extra, 0) }
}

private fun releaseText(item: PlexItem): String? {
    val date = item.releaseDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    return date?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        ?: item.year?.toString()
}

private fun runtimeText(ms: Long): String {
    val minutes = (ms / 60_000).toInt().coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
}

@Composable
internal fun Message(text: String) {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
