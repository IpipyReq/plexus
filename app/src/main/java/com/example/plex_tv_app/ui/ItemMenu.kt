@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import com.example.plex_tv_app.ui.theme.palette
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.MediaVersion
import com.example.plex_tv_app.plex.PlexHub
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.settings.MAX_TEXT_SCALE
import com.example.plex_tv_app.settings.MIN_TEXT_SCALE
import com.example.plex_tv_app.plex.PlexServerApi
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.roundToInt

/** The card size a new profile starts with (and "Reset card size" goes back to). */
const val DEFAULT_CARD_SCALE = 0.8f
const val MIN_CARD_SCALE = 0.6f
const val MAX_CARD_SCALE = 1.6f

private val OkKeys = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

private enum class MenuPage { Main, Rate, Info, Customise, RowStyle }

/**
 * The floating menu on the right that opens when you hold OK on a card.
 *
 * @param rows the Home rows in their current order, or null when not opened from Home
 *   (then Customise only offers the card size).
 * @param rowStyle how the row this card sits in is drawn, or null when not opened from a Home row.
 */
@Composable
fun ItemMenu(
    api: PlexServerApi,
    item: PlexItem,
    rows: List<PlexHub>?,
    rowStyle: CardStyle?,
    onRowStyle: (CardStyle) -> Unit,
    cardScale: Float,
    onCardScale: (Float) -> Unit,
    onMoveRow: (from: Int, to: Int) -> Unit,
    onResetRows: () -> Unit,
    onPlay: (fromStart: Boolean) -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onRate: (stars: Int?) -> Unit,
    onRefresh: () -> Unit,
    onAppearance: () -> Unit,
    onClose: () -> Unit,
    onGoToShow: (() -> Unit)? = null, // episodes and seasons: open the show's page
    onUpdateMetadata: (() -> Unit)? = null, // movies, shows, seasons, episodes: re-fetch from online sources
    onRemoveFromContinueWatching: (() -> Unit)? = null, // only on a Continue Watching card
) {
    var page by remember { mutableStateOf(MenuPage.Main) }

    // The menu opens while OK is still held down. Swallow that held press (and its release)
    // so letting go doesn't activate whatever the menu just highlighted.
    var waitingForRelease by remember { mutableStateOf(true) }

    val back: () -> Unit = {
        if (page == MenuPage.Main) onClose() else page = MenuPage.Main
    }

    // A Dialog is its own layer: the remote can't move focus to the page or nav behind it,
    // so only Back (or picking an action) closes the menu.
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // No dimming, so Customise changes stay visible behind the menu.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BackHandler { back() }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(420.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface.copy(alpha = 0.96f))
                    .onPreviewKeyEvent { e ->
                        if (!waitingForRelease || e.nativeKeyEvent.keyCode !in OkKeys) return@onPreviewKeyEvent false
                        when {
                            e.type == KeyEventType.KeyUp -> {
                                waitingForRelease = false
                                true // the release of the original hold
                            }
                            e.nativeKeyEvent.repeatCount > 0 -> true // still holding
                            else -> {
                                waitingForRelease = false // a brand-new press: let it through
                                false
                            }
                        }
                    }
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                key(page) {
                    when (page) {
                        MenuPage.Main -> MainPage(item, rows != null, rowStyle, onPlay, onSetWatched, onRefresh, onAppearance, onGoToShow, onUpdateMetadata, onRemoveFromContinueWatching) { page = it }
                        MenuPage.RowStyle -> RowStylePage(rowStyle, onRowStyle) { page = MenuPage.Main }
                        MenuPage.Rate -> RatePage(item, onRate)
                        MenuPage.Info -> InfoPage(api, item) { page = MenuPage.Main }
                        MenuPage.Customise -> CustomisePage(
                            rows = rows,
                            cardScale = cardScale,
                            onCardScale = onCardScale,
                            onMoveRow = onMoveRow,
                            onResetRows = onResetRows,
                            onDone = onClose,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Pages ----------

@Composable
private fun MainPage(
    item: PlexItem,
    onHome: Boolean,
    rowStyle: CardStyle?,
    onPlay: (fromStart: Boolean) -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAppearance: () -> Unit,
    onGoToShow: (() -> Unit)?,
    onUpdateMetadata: (() -> Unit)?,
    onRemoveFromContinueWatching: (() -> Unit)?,
    goTo: (MenuPage) -> Unit,
) {
    MenuHeader(item)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val offset = item.viewOffset
    if (item.progress != null && offset != null) {
        MenuAction("Resume", "From ${formatTime(offset)}", Modifier.focusRequester(first)) { onPlay(false) }
        MenuAction("Restart", "From the beginning") { onPlay(true) }
    } else {
        MenuAction("Watch", modifier = Modifier.focusRequester(first)) { onPlay(true) }
    }
    onGoToShow?.let { MenuAction("Go to Show", item.grandparentTitle ?: item.parentTitle, onClick = it) }
    MenuAction("Rate", item.userRating?.let { starsText((it / 2).roundToInt()) }) { goTo(MenuPage.Rate) }
    MenuAction(if (item.isWatched) "Mark as Unwatched" else "Mark as Watched") { onSetWatched(!item.isWatched) }
    onRemoveFromContinueWatching?.let { MenuAction("Remove from Continue Watching", "Keeps your progress", onClick = it) }
    if (item.type == "movie" || item.type == "episode") {
        MenuAction("File Info") { goTo(MenuPage.Info) }
    }
    if (rowStyle != null) {
        MenuAction("Row style", rowStyle.label) { goTo(MenuPage.RowStyle) }
    }
    MenuAction("Refresh", "Reload from your server", onClick = onRefresh)
    onUpdateMetadata?.let { MenuAction("Update metadata", "Re-fetch details and artwork", onClick = it) }
    MenuAction(if (onHome) "Customise Rows" else "Customise", "Card and text size" + if (onHome) ", row order" else "") {
        goTo(MenuPage.Customise)
    }
    MenuAction("Appearance", "Change theme", onClick = onAppearance)
}

/** Pick how this row's cards look. Applies immediately so you can see it behind the menu. */
@Composable
private fun RowStylePage(current: CardStyle?, onPick: (CardStyle) -> Unit, onBack: () -> Unit) {
    Text("Row style", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Changes this row only. You'll see it behind the menu straight away.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    CardStyle.entries.forEach { style ->
        ListItem(
            selected = style == current,
            onClick = { onPick(style) },
            modifier = if (style == (current ?: CardStyle.Poster)) Modifier.focusRequester(first) else Modifier,
            headlineContent = { Text(style.label) },
            supportingContent = { Text(styleHint(style)) },
            trailingContent = { if (style == current) Text("✓", color = PlexGold, fontSize = 20.sp) },
        )
    }
    Spacer(Modifier.height(8.dp))
    MenuAction("Done", onClick = onBack)
}

private fun styleHint(style: CardStyle) = when (style) {
    CardStyle.Poster -> "Tall posters. Episodes use their show's poster."
    CardStyle.SeasonPoster -> "Tall posters. Episodes use their season's poster."
    CardStyle.Thumbnail -> "Wide images. Episodes show a still from the episode."
}

@Composable
private fun RatePage(item: PlexItem, onRate: (Int?) -> Unit) {
    MenuHeader(item, subtitle = "Rate")
    val current = item.userRating?.let { (it / 2).roundToInt() } ?: 0
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (stars in 1..5) {
            CircleButton(
                onClick = { onRate(stars) },
                size = 56.dp,
                modifier = if (stars == maxOf(current, 1)) Modifier.focusRequester(first) else Modifier,
            ) {
                Text(if (stars <= current) "★" else "☆", fontSize = 28.sp, color = PlexGold)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    if (current > 0) MenuAction("Clear rating") { onRate(null) }
}

@Composable
private fun InfoPage(api: PlexServerApi, item: PlexItem, onBack: () -> Unit) {
    MenuHeader(item, subtitle = "File info")
    var versions by remember { mutableStateOf<List<MediaVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.ratingKey) {
        try {
            versions = api.mediaVersions(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    val list = versions
    when {
        error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
        list == null -> Text("Loading…")
        list.isEmpty() -> Text("No file information available.")
        else -> list.forEachIndexed { i, v ->
            if (list.size > 1) {
                Text("Version ${i + 1}", style = MaterialTheme.typography.titleSmall, color = PlexGold)
                Spacer(Modifier.height(4.dp))
            }
            val video = listOfNotNull(
                v.resolution?.let { if (it.all(Char::isDigit)) "${it}p" else it.uppercase() },
                v.videoCodec?.uppercase(),
                v.frameRate,
            ).joinToString(" · ")
            val audio = listOfNotNull(v.audioCodec?.uppercase(), v.audioChannels?.let(::channelsText)).joinToString(" · ")
            InfoRow("Video", video.ifBlank { null })
            InfoRow("Size", if (v.width != null && v.height != null) "${v.width}×${v.height}" else null)
            InfoRow("Audio", audio.ifBlank { null })
            InfoRow("Container", v.container?.uppercase())
            InfoRow("Bitrate", v.bitrateKbps?.let { String.format(Locale.US, "%.1f Mbps", it / 1000f) })
            v.files.forEach { f ->
                InfoRow("File size", f.sizeBytes?.let(::sizeText))
                InfoRow("File", f.path, maxLines = 4)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // Something focusable so the remote stays in the menu.
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    MenuAction("Back", modifier = Modifier.focusRequester(back), onClick = onBack)
}

@Composable
private fun CustomisePage(
    rows: List<PlexHub>?,
    cardScale: Float,
    onCardScale: (Float) -> Unit,
    onMoveRow: (Int, Int) -> Unit,
    onResetRows: () -> Unit,
    onDone: () -> Unit,
) {
    Text(if (rows != null) "Customise Rows" else "Customise", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Changes show up behind this menu as you make them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SizeSlider(cardScale, onCardScale, Modifier.focusRequester(first))
    Spacer(Modifier.height(10.dp))
    val settings = LocalSettings.current
    TextSizeSlider(settings.textScale, { settings.textScale = it })

    if (rows != null) {
        Spacer(Modifier.height(20.dp))
        Text("ROW ORDER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Select a row, move it with ▲ ▼, then press OK to drop it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        var movingKey by remember { mutableStateOf<String?>(null) }
        BackHandler(enabled = movingKey != null) { movingKey = null }

        rows.forEachIndexed { index, hub ->
            key(hub.rowKey) {
                val moving = movingKey == hub.rowKey
                // The row being moved keeps focus but changes place, so scroll the menu after it.
                val inView = remember { BringIntoViewRequester() }
                LaunchedEffect(index, moving) {
                    if (!moving) return@LaunchedEffect
                    withFrameNanos { } // let it settle in its new spot first
                    inView.bringIntoView()
                }
                ListItem(
                    selected = moving,
                    onClick = { movingKey = if (moving) null else hub.rowKey },
                    modifier = Modifier.bringIntoViewRequester(inView).onPreviewKeyEvent { e ->
                        if (!moving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                if (index > 0) onMoveRow(index, index - 1); true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (index < rows.lastIndex) onMoveRow(index, index + 1); true
                            }
                            else -> false
                        }
                    },
                    headlineContent = { Text(hub.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (moving) MoveHint else null,
                    trailingContent = { Text(if (moving) "⇅" else "≡", fontSize = 20.sp) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MenuAction("Reset to Plex's order", onClick = onResetRows)
    }
    MenuAction("Reset card size") { onCardScale(DEFAULT_CARD_SCALE) }
    MenuAction("Reset text size") { settings.textScale = 1f }
    MenuAction("Done", onClick = onDone)
}

// ---------- Building blocks ----------

private val MoveHint: @Composable () -> Unit = { Text("▲ ▼ to move · OK to drop") }

@Composable
private fun MenuHeader(item: PlexItem, subtitle: String? = null) {
    val title = if (item.type == "episode") item.grandparentTitle ?: item.title else item.title
    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val detail = subtitle ?: when (item.type) {
        "episode" -> listOfNotNull(item.parentIndex?.let { "S$it" }, item.index?.let { "E$it" }, item.title).joinToString(" · ")
        "season" -> listOfNotNull(item.parentTitle, item.title).joinToString(" · ")
        else -> item.year?.toString()
    }
    detail?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun MenuAction(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(title) },
        trailingContent = { if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall) },
    )
}

@Composable
private fun InfoRow(label: String, value: String?, maxLines: Int = 1) {
    if (value == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Set by a highlighted slider, so pages with their own Left handling (Settings: Left goes to the
 * category list) let Left/Right through to adjust it instead.
 */
val LocalSliderFocus = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/** The card size slider. */
@Composable
internal fun SizeSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) =
    ScaleSlider("Card size", value, MIN_CARD_SCALE, MAX_CARD_SCALE, onChange, modifier)

/** The text size slider (all text in the app, and title logos). */
@Composable
internal fun TextSizeSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) =
    ScaleSlider("Text size", value, MIN_TEXT_SCALE, MAX_TEXT_SCALE, onChange, modifier)

/** A remote-friendly slider in 10% steps: highlight it, then Left/Right change the value. */
@Composable
internal fun ScaleSlider(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val sliderFocus = LocalSliderFocus.current
    androidx.compose.runtime.DisposableEffect(sliderFocus) {
        onDispose { if (focused) sliderFocus?.value = false } // page changed while highlighted
    }
    fun step(by: Int) {
        val steps = (value * 10).roundToInt() + by
        onChange((steps / 10f).coerceIn(min, max))
    }
    Surface(
        onClick = { },
        modifier = modifier
            .confettiAnchor()
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                sliderFocus?.value = it.isFocused
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { step(+1); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.text.copy(alpha = 0.08f),
            focusedContainerColor = palette.text.copy(alpha = 0.2f),
            contentColor = palette.text,
            focusedContentColor = palette.text,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.text.copy(alpha = 0.25f))
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(PlexGold))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (focused) "◀  ▶  to resize" else "Highlight to adjust",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

// ---------- Formatting ----------

private fun starsText(stars: Int) = "★".repeat(stars.coerceIn(0, 5)) + "☆".repeat(5 - stars.coerceIn(0, 5))

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private fun channelsText(channels: Int) = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

private fun sizeText(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
}
