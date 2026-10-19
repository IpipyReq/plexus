@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.compose.runtime.ReadOnlyComposable
import com.example.plex_tv_app.ui.theme.palette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import com.example.plex_tv_app.settings.CardText
import com.example.plex_tv_app.settings.LocalSettings
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi

/** The theme's accent (Plex gold in the default theme): progress bars, markers, stars. */
val PlexGold: Color @Composable @ReadOnlyComposable get() = palette.accent

/** How a row draws its cards. */
enum class CardStyle(val label: String) {
    /** Tall poster of the movie or show (for episodes: the show's poster). */
    Poster("Show poster"),

    /** Tall poster of the season (for episodes: their season's poster). */
    SeasonPoster("Season poster"),

    /** Wide 16:9 image: the episode's own still, or the movie/show backdrop. */
    Thumbnail("Thumbnail"),
}

/** Width of one card at normal size, before the card-size setting is applied. */
fun CardStyle.baseWidth(): Dp = if (this == CardStyle.Thumbnail) 280.dp else 136.dp

/** Image height ÷ width. */
fun CardStyle.heightRatio(): Float = if (this == CardStyle.Thumbnail) 9f / 16f else 1.5f

/**
 * One movie/show/season/episode tile with its title underneath.
 *
 * @param standalone the card is shown outside its show (e.g. on Home), so episodes are
 *   labelled with the show name and "S1 E3" instead of just the episode title
 * @param modifier sizes the whole tile (e.g. a fixed width in a row)
 * @param cardModifier goes on the focusable card itself (e.g. a FocusRequester)
 * @param onFocused called when the remote highlights this card
 * @param onLongClick holding OK on the card; gets a function that puts focus back on this card
 */
@Composable
fun MediaCard(
    api: PlexServerApi,
    item: PlexItem,
    style: CardStyle,
    standalone: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    onLongClick: ((restoreFocus: () -> Unit) -> Unit)? = null,
) {
    val self = remember { FocusRequester() }
    // A highlighted card grows ~10% around its centre, so its bottom edge drops onto the title.
    // Slide the title down by the same amount while highlighted (no layout change, so rows don't jump).
    var focused by remember { mutableStateOf(false) }
    var cardHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val textShift by animateFloatAsState(
        targetValue = if (focused) cardHeight * 0.05f + with(density) { 3.dp.toPx() } else 0f,
        animationSpec = tween(180),
        label = "cardTextShift",
    )
    val settings = LocalSettings.current
    val textMode = LocalCardText.current ?: settings.cardText
    Column(modifier) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick?.let { handler -> { handler { runCatching { self.requestFocus() } } } },
            modifier = cardModifier
                .confettiAnchor()
                .returnFocusHere()
                .focusRequester(self)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                }
                .onSizeChanged { cardHeight = it.height }
                .fillMaxWidth()
                .aspectRatio(1f / style.heightRatio()),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Settings → Home → Spoilers: blur stills from episodes you haven't seen.
                val hide = LocalSettings.current.blurUnwatched && item.isUnwatchedEpisode &&
                    style == CardStyle.Thumbnail
                RemoteImage(
                    imageFor(api, item, style),
                    if (hide) Modifier.fillMaxSize().blur(24.dp) else Modifier.fillMaxSize(),
                )

                // Watched badge on movies and episodes.
                if (settings.showWatchedBadges && item.isWatched && (item.type == "movie" || item.type == "episode")) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(PlexGold),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", fontSize = 12.sp, color = palette.onAccent)
                    }
                }

                item.progress?.takeIf { settings.showProgressBars }?.let { progress ->
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(Color(0x99000000))
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(PlexGold))
                    }
                }
            }
        }
        // Settings → Home & browsing → Text under cards: title and details, title only, or nothing.
        if (textMode != CardText.None) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.graphicsLayer { translationY = textShift }) {
        // The highlighted card's title scrolls slowly if it's too long to fit.
        Text(
            cardTitle(item, standalone),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            overflow = overflowFor(focused),
            modifier = Modifier.scrollIfLong(focused),
        )
        cardSubtitle(item, standalone)?.takeIf { textMode == CardText.Full }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun imageFor(api: PlexServerApi, item: PlexItem, style: CardStyle): String? = when (style) {
    CardStyle.Thumbnail -> {
        val wide = if (item.type == "episode") item.thumb ?: item.art else item.art ?: item.thumb
        api.imageUrl(wide, 480, 270)
    }
    CardStyle.Poster -> {
        val poster = when (item.type) {
            "episode" -> item.grandparentThumb ?: item.parentThumb ?: item.thumb
            "season" -> item.parentThumb ?: item.thumb
            else -> item.thumb
        }
        api.imageUrl(poster, 300, 450)
    }
    CardStyle.SeasonPoster -> {
        val poster = when (item.type) {
            "episode" -> item.parentThumb ?: item.grandparentThumb ?: item.thumb
            else -> item.thumb
        }
        api.imageUrl(poster, 300, 450)
    }
}

private fun cardTitle(item: PlexItem, standalone: Boolean): String = when {
    item.type == "episode" && standalone -> item.grandparentTitle ?: item.title
    item.type == "episode" && item.index != null -> "${item.index}. ${item.title}"
    item.type == "season" && standalone -> item.parentTitle ?: item.title
    else -> item.title
}

private fun cardSubtitle(item: PlexItem, standalone: Boolean): String? = when (item.type) {
    "episode" -> if (standalone) {
        listOfNotNull(episodeCode(item), item.title).joinToString("  ")
    } else {
        item.duration?.let { "${it / 60_000} min" }
    }
    "season" -> if (standalone) item.title else item.leafCount?.let { "$it episodes" }
    else -> item.year?.toString()
}

/** Set by Home's rows to apply Settings → "Text under cards on Home"; elsewhere the general setting applies. */
val LocalCardText = androidx.compose.runtime.compositionLocalOf<CardText?> { null }

/** An episode you haven't started or finished: its image and description could spoil it. */
val PlexItem.isUnwatchedEpisode: Boolean
    get() = type == "episode" && !isWatched && progress == null

/** "S2 E5", or null if the numbers are unknown. */
fun episodeCode(item: PlexItem): String? {
    val season = item.parentIndex?.let { "S$it" }
    val episode = item.index?.let { "E$it" }
    return listOfNotNull(season, episode).joinToString(" ").ifBlank { null }
}
