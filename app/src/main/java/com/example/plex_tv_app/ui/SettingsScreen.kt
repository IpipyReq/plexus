@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import com.example.plex_tv_app.ui.theme.palette
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ScrollState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.IntOffset
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.settings.Labeled
import com.example.plex_tv_app.plex.PlexLibrary
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.settings.AppIcon
import com.example.plex_tv_app.settings.AppSettings
import com.example.plex_tv_app.settings.CardText
import com.example.plex_tv_app.settings.NavSoundMode
import com.example.plex_tv_app.settings.BurnSubtitles
import com.example.plex_tv_app.settings.ClockMode
import com.example.plex_tv_app.settings.CollectionRows
import com.example.plex_tv_app.settings.Countdown
import com.example.plex_tv_app.settings.InsecureConnections
import com.example.plex_tv_app.settings.EpisodeSelect
import com.example.plex_tv_app.settings.Languages
import com.example.plex_tv_app.settings.MovieSelect
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.settings.Passthrough
import com.example.plex_tv_app.settings.RefreshInterval
import com.example.plex_tv_app.settings.RefreshRateMatch
import com.example.plex_tv_app.settings.SkipMode
import com.example.plex_tv_app.settings.StreamQuality
import com.example.plex_tv_app.settings.SubtitleBackground
import com.example.plex_tv_app.settings.SubtitleColor
import com.example.plex_tv_app.settings.SubtitleMode
import com.example.plex_tv_app.settings.SubtitleSize
import com.example.plex_tv_app.ui.theme.PlexSerif
import kotlinx.coroutines.CancellationException

private enum class Edge { Top, Bottom }

private enum class Category(val title: String, val blurb: String) {
    General("General", "Signing in, the clock, and keeping things up to date."),
    Appearance("Appearance", "Colours and themes for the whole app."),
    Home("Home & browsing", "How Home and your libraries look."),
    Playback("Playback", "Video quality, frame rate and what happens between episodes."),
    Audio("Audio", "Surround sound and preferred language."),
    Subtitles("Subtitles", "When subtitles appear and how they look."),
    Server("Server", "Which server you're watching from and how the app connects to it."),
    Account("Account", "Profiles and signing out."),
    About("About", "Version details and maintenance."),
}

/**
 * Settings, laid out like the Plex app: categories on the left, the highlighted category's
 * settings on the right.
 *
 * Navigation is explicit rather than left to Android's "nearest thing" guess:
 * - Categories: Up/Down move through the list and wrap around at either end.
 * - Settings: Up/Down walk the page's rows top to bottom; past the last row you continue into
 *   the next category, before the first row into the previous one. The first and last pages stop.
 * - Left from a setting returns to its category.
 */
@Composable
fun SettingsScreen(
    client: PlexClient,
    api: PlexServerApi,
    cardScale: Float,
    onCardScale: (Float) -> Unit,
    onResetRows: () -> Unit,
    onResetRowStyles: () -> Unit,
    onRefresh: () -> Unit,
    onShowServers: () -> Unit,
    onSwitchUser: () -> Unit,
    onManageProfiles: () -> Unit,
    onSignOut: () -> Unit,
    onChangeTheme: () -> Unit,
    onChangeNavStyle: () -> Unit = {},
    startCategory: String? = null, // e.g. "Appearance" when coming back from the theme picker
) {
    val settings = LocalSettings.current
    val categories = Category.entries
    val start = remember { categories.firstOrNull { it.name == startCategory } ?: Category.General }
    var category by remember { mutableStateOf(start) }
    val categoryFocus = remember { categories.associateWith { FocusRequester() } }
    val initialFocusDone = remember { mutableStateOf(false) }

    val chain = remember { FocusChain() }
    val sliderFocused = remember { mutableStateOf(false) } // a slider (card/text size) is highlighted
    var landAt by remember { mutableStateOf<Edge?>(null) } // where to land on the page just opened
    var holdFocus by remember { mutableStateOf(false) } // the pane keeps focus while pages swap
    val pane = remember { FocusRequester() }
    // The category highlight slides only for the big moves (page-to-page, wrap-around);
    // ordinary steps through the list snap instantly so it feels quick.
    var slideHighlight by remember { mutableStateOf(false) }

    // Moving off the end of one page into the next: hold focus on the pane while the new page
    // lays out, then land on its first (or last) row. No detour through the category list.
    LaunchedEffect(category, landAt) {
        val target = landAt ?: return@LaunchedEffect
        repeat(5) {
            withFrameNanos { } // wait for the new rows to be measured
            val rows = chain.rows
            if (rows.isNotEmpty()) {
                runCatching { (if (target == Edge.Top) rows.first() else rows.last()).requestFocus() }
                landAt = null
                holdFocus = false
                return@LaunchedEffect
            }
        }
        landAt = null
        holdFocus = false
    }

    fun continueInto(index: Int, edge: Edge) {
        val next = categories.getOrNull(index) ?: return // first/last page: stay put
        holdFocus = true
        runCatching { pane.requestFocus() }
        slideHighlight = true
        category = next
        landAt = edge
    }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize()) {
            CategoryList(
                categories = categories,
                selected = category,
                requesters = categoryFocus,
                slide = slideHighlight,
                onStep = { wrapped -> slideHighlight = wrapped },
                onFocused = { category = it },
                modifier = Modifier.width(230.dp),
            )
            RequestInitialFocus(categoryFocus.getValue(start), initialFocusDone)

            Spacer(Modifier.width(28.dp))
            Box(Modifier.width(1.dp).fillMaxHeight().padding(bottom = 32.dp).background(palette.hairline))
            Spacer(Modifier.width(20.dp))

            CompositionLocalProvider(LocalFocusChain provides chain, LocalSliderFocus provides sliderFocused) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.nativeKeyEvent.keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                    // On a slider, Left turns it down; anywhere else it goes to the categories.
                                    if (sliderFocused.value) return@onPreviewKeyEvent false
                                    runCatching { categoryFocus.getValue(category).requestFocus() }
                                    true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (landAt == null) {
                                        val rows = chain.rows
                                        val i = chain.focusedIndex
                                        if (i in 0 until rows.lastIndex) runCatching { rows[i + 1].requestFocus() }
                                        else continueInto(category.ordinal + 1, Edge.Top)
                                    }
                                    true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                    if (landAt == null) {
                                        val rows = chain.rows
                                        val i = chain.focusedIndex
                                        if (i > 0) runCatching { rows[i - 1].requestFocus() }
                                        else continueInto(category.ordinal - 1, Edge.Bottom)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        // Only focusable for the instant pages swap, so focus never falls out of the pane.
                        .focusRequester(pane)
                        .focusProperties { canFocus = holdFocus }
                        .focusable(),
                ) {
                    key(category) {
                        // Arriving from below (Up past the top of the next page)? Start this page
                        // scrolled to the bottom, so there's no visible scroll down to its last row.
                        val scroll = remember { ScrollState(if (landAt == Edge.Bottom) Int.MAX_VALUE else 0) }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(bottom = 48.dp),
                        ) {
                            Text(
                                category.title,
                                fontFamily = PlexSerif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 24.sp,
                                color = SettingsBone,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                            SettingsNote(category.blurb)
                            Spacer(Modifier.height(4.dp))

                            when (category) {
                                Category.General -> GeneralPage(client, settings, onRefresh)
                                Category.Appearance -> AppearancePage(settings, onChangeTheme, onChangeNavStyle)
                                Category.Home -> HomePage(settings, cardScale, onCardScale, onResetRows, onResetRowStyles)
                                Category.Playback -> PlaybackPage(settings, api)
                                Category.Audio -> AudioPage(settings)
                                Category.Subtitles -> SubtitlesPage(settings)
                                Category.Server -> ServerPage(api, settings, onShowServers)
                                Category.Account -> AccountPage(client, onSwitchUser, onManageProfiles, onSignOut)
                                Category.About -> AboutPage(client, api, settings)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CategoryHeight = 48.dp
private val CategoryGap = 4.dp

/**
 * The category list. One highlight slides smoothly between categories rather than each row
 * lighting up on its own: bright while you're moving through the list, a quiet marker with a
 * gold edge once you've moved into the settings.
 */
@Composable
private fun CategoryList(
    categories: List<Category>,
    selected: Category,
    requesters: Map<Category, FocusRequester>,
    slide: Boolean,
    onStep: (wrapped: Boolean) -> Unit,
    onFocused: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    var listFocused by remember { mutableStateOf(false) }
    val index = categories.indexOf(selected)

    // Highlight position: slides for big moves, snaps for ordinary steps.
    val step = (CategoryHeight + CategoryGap).value
    val pillY = remember { Animatable(index * step) }
    LaunchedEffect(index) {
        val target = index * step
        if (slide) pillY.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        else pillY.snapTo(target)
    }
    val pillColor by animateColorAsState(
        if (listFocused) SettingsBone else palette.text.copy(alpha = 0.09f),
        tween(120),
        label = "pillColor",
    )
    val markerAlpha by animateFloatAsState(if (listFocused) 0f else 1f, tween(120), label = "marker")

    Box(
        modifier
            .onFocusChanged { listFocused = it.hasFocus }
            // Up/Down wrap around inside the list and never escape to the side nav.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> return@onPreviewKeyEvent false
                }
                val nextIndex = index + direction
                onStep(nextIndex !in categories.indices) // wrapping round is a "big move"
                val next = categories[nextIndex.mod(categories.size)]
                runCatching { requesters.getValue(next).requestFocus() }
                true
            },
    ) {
        // The sliding highlight.
        Box(
            Modifier
                .offset { IntOffset(0, pillY.value.dp.roundToPx()) }
                .fillMaxWidth()
                .height(CategoryHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(pillColor),
        ) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(18.dp)
                    .graphicsLayer { alpha = markerAlpha }
                    .clip(RoundedCornerShape(2.dp))
                    .background(PlexGold),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(CategoryGap)) {
            categories.forEach { c ->
                val isSelected = c == selected
                val textColor = when {
                    isSelected && listFocused -> palette.background
                    isSelected -> SettingsBone
                    else -> SettingsMist
                }
                Surface(
                    onClick = { onFocused(c) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CategoryHeight)
                        .confettiAnchor()
                        .returnFocusHere()
                        .focusRequester(requesters.getValue(c))
                        .onFocusChanged { if (it.isFocused) onFocused(c) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    // Transparent in every state: the sliding highlight does the showing.
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        pressedContainerColor = Color.Transparent,
                        contentColor = textColor,
                        focusedContentColor = textColor,
                        pressedContentColor = textColor,
                    ),
                ) {
                    Box(Modifier.fillMaxSize().padding(start = 22.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            c.title,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Pages ----------

@Composable
private fun GeneralPage(client: PlexClient, s: AppSettings, onRefresh: () -> Unit) {
    var autoSignIn by remember { mutableStateOf(client.autoSignIn) }
    var refreshed by remember { mutableStateOf(false) }

    SettingsSection("Signing in")
    ToggleSetting(
        "Sign in automatically",
        "Open straight into the last profile used, skipping the profile picker and PIN.",
        autoSignIn,
    ) {
        autoSignIn = it
        client.autoSignIn = it
    }

    SettingsSection("Sounds")
    EnumSetting(
        "Navigation sounds",
        "The soft clicks as you move around and select things. \"Follow the TV's setting\" uses the TV's system sounds option.",
        NavSoundMode.entries,
        s.navSounds,
    ) { s.navSounds = it }

    SettingsSection("Clock")
    EnumSetting("Clock", "The time in the top-right corner.", ClockMode.entries, s.clockMode) { s.clockMode = it }

    SettingsSection("Keeping up to date")
    EnumSetting(
        "Auto-refresh",
        "Reload Home and your libraries in the background. The app also refreshes when you come back to it.",
        RefreshInterval.entries,
        s.refreshInterval,
    ) { s.refreshInterval = it }
    ActionSetting("Refresh now", value = if (refreshed) "Refreshing" else null) {
        refreshed = true
        onRefresh()
    }

    ResetToDefaults("General") {
        s.reset(AppSettings.Group.General)
        autoSignIn = false
        client.autoSignIn = false
    }
}

@Composable
private fun AppearancePage(s: AppSettings, onChangeTheme: () -> Unit, onChangeNavStyle: () -> Unit) {
    SettingsSection("Theme")
    ActionSetting(
        "Change theme",
        "Preview themes over your Home screen and pick one.",
        value = s.theme.label,
        onClick = onChangeTheme,
    )
    ActionSetting(
        "Side bar style",
        "Preview side bar looks over your Home screen and pick one.",
        value = s.navStyle.label,
        onClick = onChangeNavStyle,
    )

    SettingsSection("Text")
    Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        TextSizeSlider(s.textScale, { s.textScale = it }, Modifier.inChain())
    }
    SettingsNote("Changes all text in the app, including descriptions, episode details, these settings and title logos.")

    SettingsSection("App icon")
    val context = LocalContext.current
    var icon by remember { mutableStateOf(AppIcon.current(context)) }
    EnumSetting(
        "Icon on the TV's app list",
        "Changes how Plexus looks on your home screen. The TV may take a moment to update, " +
            "and Plexus may move to the end of your apps row.",
        AppIcon.entries,
        icon,
    ) {
        if (it != icon) {
            AppIcon.apply(context, it)
            icon = it
        }
    }
    ResetToDefaults("Appearance") {
        s.reset(AppSettings.Group.Appearance)
        if (icon != AppIcon.Dark) {
            AppIcon.apply(context, AppIcon.Dark)
            icon = AppIcon.Dark
        }
    }
}

@Composable
private fun HomePage(
    s: AppSettings,
    cardScale: Float,
    onCardScale: (Float) -> Unit,
    onResetRows: () -> Unit,
    onResetRowStyles: () -> Unit,
) {
    var rowsReset by remember { mutableStateOf(false) }
    var stylesReset by remember { mutableStateOf(false) }

    SettingsSection("Layout")
    Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) { SizeSlider(cardScale, onCardScale, Modifier.inChain()) }
    ToggleSetting(
        "Hide the side bar",
        "Hide the strip of icons on the left so pages use the whole screen. Left at the start of a row still opens it.",
        s.hideNavBar,
    ) { s.hideNavBar = it }
    ToggleSetting(
        "Side bar on title pages",
        "Also open the side bar from movie, show, episode and collection pages. Follows the setting above for whether its icons show.",
        s.navInDetails,
    ) { s.navInDetails = it }
    ActionSetting(
        "Reset row order",
        "Put Home's rows back in the order your server suggests.",
        value = if (rowsReset) "Done" else null,
    ) {
        onResetRows()
        rowsReset = true
    }
    ActionSetting(
        "Reset row styles",
        "Go back to wide images for Continue Watching and episodes, posters for everything else.",
        value = if (stylesReset) "Done" else null,
    ) {
        onResetRowStyles()
        stylesReset = true
    }
    ToggleSetting(
        "Background motion",
        "Let the artwork behind Home drift very slowly.",
        s.backdropMotion,
    ) { s.backdropMotion = it }

    SettingsSection("Minimal look")
    EnumSetting(
        "Text under cards on Home",
        "Home and library Recommended rows. The highlighted title's name and episode are already shown above the row.",
        CardText.entries,
        s.homeCardText,
    ) { s.homeCardText = it }
    EnumSetting(
        "Text under cards elsewhere",
        "Library grids, collections, search and the rows on title pages.",
        CardText.entries,
        s.cardText,
    ) { s.cardText = it }
    ToggleSetting("Row titles", "The name above each row on Home, like \"Continue Watching\".", s.showRowTitles) { s.showRowTitles = it }
    ToggleSetting("Description on Home", "The summary of the highlighted title, above the row.", s.showHomeSummary) { s.showHomeSummary = it }
    ToggleSetting("Peek at the next row", "Show the top of the next row along the bottom of Home.", s.peekNextRow) { s.peekNextRow = it }
    ToggleSetting("Watched ticks", "The ✓ on movies and episodes you've seen.", s.showWatchedBadges) { s.showWatchedBadges = it }
    ToggleSetting("Progress bars", "How far you've got, along the bottom of cards you've started.", s.showProgressBars) { s.showProgressBars = it }

    SettingsSection("Selecting a title")
    EnumSetting(
        "Selecting a movie",
        "What happens when you press OK on a movie.",
        MovieSelect.entries,
        s.movieSelect,
    ) { s.movieSelect = it }
    EnumSetting(
        "Selecting an episode",
        "What happens when you press OK on an episode, for example in Continue Watching.",
        EpisodeSelect.entries,
        s.episodeSelect,
    ) { s.episodeSelect = it }

    SettingsSection("Details pages")
    EnumSetting(
        "Collections",
        "When a collection has movies and TV shows, show them together or in separate rows.",
        CollectionRows.entries,
        s.collectionRows,
    ) { s.collectionRows = it }
    ToggleSetting(
        "Critic reviews",
        "Published reviews (from Rotten Tomatoes) beside the ratings.",
        s.showCriticReviews,
    ) { s.showCriticReviews = it }
    // Community reviews (written by Plex users) come from Plex's online service, which isn't in
    // the published API docs. Hidden until that's wired up; the saved setting is kept.
    // ToggleSetting(
    //     "Community reviews",
    //     "Reviews written by Plex users.",
    //     s.showCommunityReviews,
    // ) { s.showCommunityReviews = it }

    SettingsSection("Spoilers")
    ToggleSetting(
        "Blur unwatched episode images",
        "Hide episode stills until you've watched them.",
        s.blurUnwatched,
    ) { s.blurUnwatched = it }
    ToggleSetting(
        "Hide unwatched episode descriptions",
        "Don't show what happens in episodes you haven't seen.",
        s.hideUnwatchedSummaries,
    ) { s.hideUnwatchedSummaries = it }

    SettingsSection("Upcoming episodes")
    ToggleSetting(
        "Show upcoming episodes",
        "For shows still airing, episodes that haven't come out yet appear after the latest one, with their name, date and description. " +
            "Needs a TMDB key, or Sonarr tracking the show (Settings → Server).",
        s.upcomingEpisodes,
    ) { s.upcomingEpisodes = it }

    ResetToDefaults("Home & browsing") {
        s.reset(AppSettings.Group.Home)
        onCardScale(DEFAULT_CARD_SCALE)
        onResetRows()
        onResetRowStyles()
        rowsReset = true
        stylesReset = true
    }
}

@Composable
private fun PlaybackPage(s: AppSettings, api: PlexServerApi) {
    // Seek previews need the server's preview thumbnails; profiles that can't turn those on only
    // see the option once the server actually has them.
    val previewsOffered by produceState(api.canManage, api) {
        if (!api.canManage) value = api.previewsAvailable()
    }
    SettingsSection("Quality")
    EnumSetting(
        "Home streaming",
        "When the server is on the same network as this TV.",
        StreamQuality.entries,
        s.homeQuality,
    ) { s.homeQuality = it }
    EnumSetting(
        "Remote streaming",
        "When watching from a server outside your home.",
        StreamQuality.entries,
        s.remoteQuality,
    ) { s.remoteQuality = it }
    ToggleSetting(
        "Adjust quality automatically",
        "Lower the quality when the connection can't keep up, instead of buffering.",
        s.autoAdjustQuality,
    ) { s.autoAdjustQuality = it }

    SettingsSection("Direct play")
    ToggleSetting(
        "Allow direct play",
        "Play files exactly as they are when the TV supports them. Best quality, least work for the server.",
        s.allowDirectPlay,
    ) { s.allowDirectPlay = it }
    ToggleSetting(
        "Allow direct stream",
        "Repackage files the TV can't open as-is, without re-encoding the video.",
        s.allowDirectStream,
    ) { s.allowDirectStream = it }

    SettingsSection("Display")
    EnumSetting(
        "Match frame rate",
        "Switch the TV's refresh rate to match the video, for smoother motion.",
        RefreshRateMatch.entries,
        s.refreshRateMatch,
    ) { s.refreshRateMatch = it }
    if (previewsOffered) {
        ToggleSetting(
            "Preview pictures while seeking",
            if (api.canManage) {
                "Show a picture of the spot you're skipping to. Uses the server's preview thumbnails " +
                    "(Settings → Server → Preview thumbnails); videos without them just seek as usual."
            } else {
                "Show a picture of the spot you're skipping to. Videos without previews just seek as usual."
            },
            s.seekPreviews,
        ) { s.seekPreviews = it }
    }

    SettingsSection("Between episodes")
    ToggleSetting("Autoplay next episode", null, s.autoplayNext) { s.autoplayNext = it }
    EnumSetting(
        "Countdown before next episode",
        "How long the next episode waits before starting.",
        Countdown.entries,
        s.postPlayCountdown,
    ) { s.postPlayCountdown = it }
    EnumSetting("Skip intro", "For shows where your server has detected the intro.", SkipMode.entries, s.skipIntro) {
        s.skipIntro = it
    }
    EnumSetting("Skip credits", "For titles where your server has detected the credits.", SkipMode.entries, s.skipCredits) {
        s.skipCredits = it
    }

    ResetToDefaults("Playback") { s.reset(AppSettings.Group.Playback) }
}

@Composable
private fun AudioPage(s: AppSettings) {
    SettingsSection("Surround sound")
    EnumSetting(
        "Passthrough",
        "Send surround sound untouched to your soundbar or receiver.",
        Passthrough.entries,
        s.passthrough,
    ) { s.passthrough = it }
    if (s.passthrough == Passthrough.Manual) {
        ToggleSetting("Dolby Digital (AC3)", null, s.passAc3) { s.passAc3 = it }
        ToggleSetting("Dolby Digital Plus (E-AC3)", "Includes Dolby Atmos on streaming-style files.", s.passEac3) { s.passEac3 = it }
        ToggleSetting("DTS", null, s.passDts) { s.passDts = it }
        ToggleSetting("DTS-HD", null, s.passDtsHd) { s.passDtsHd = it }
        ToggleSetting("Dolby TrueHD", "Includes Dolby Atmos on Blu-ray rips.", s.passTrueHd) { s.passTrueHd = it }
    }

    SettingsSection("Sound")
    ToggleSetting(
        "Boost voices",
        "Make dialogue easier to hear over music and effects.",
        s.boostVoices,
    ) { s.boostVoices = it }
    ChoiceSetting(
        "Preferred audio language",
        "Used when a title has more than one audio track.",
        Languages,
        s.audioLanguage,
    ) { s.audioLanguage = it }

    ResetToDefaults("Audio") { s.reset(AppSettings.Group.Audio) }
}

@Composable
private fun SubtitlesPage(s: AppSettings) {
    SettingsSection("When to show them")
    EnumSetting("Show subtitles", null, SubtitleMode.entries, s.subtitleMode) { s.subtitleMode = it }
    ChoiceSetting(
        "Preferred subtitle language",
        null,
        Languages.drop(1), // "Original language" doesn't apply to subtitles
        s.subtitleLanguage,
    ) { s.subtitleLanguage = it }

    SettingsSection("Appearance")
    EnumSetting("Size", null, SubtitleSize.entries, s.subtitleSize) { s.subtitleSize = it }
    EnumSetting("Colour", null, SubtitleColor.entries, s.subtitleColor) { s.subtitleColor = it }
    EnumSetting("Background", null, SubtitleBackground.entries, s.subtitleBackground) { s.subtitleBackground = it }

    SettingsSection("Compatibility")
    EnumSetting(
        "Burn in subtitles",
        "Draw subtitles into the video on the server. Needed for some formats; costs the server more work.",
        BurnSubtitles.entries,
        s.burnSubtitles,
    ) { s.burnSubtitles = it }

    ResetToDefaults("Subtitles") { s.reset(AppSettings.Group.Subtitles) }
}

@Composable
private fun ServerPage(api: PlexServerApi, s: AppSettings, onShowServers: () -> Unit) {
    SettingsSection("Current server")
    ActionSetting("Server", "Choose a different server.", value = api.name, onClick = onShowServers)
    InfoSetting("Address", api.baseUrl.removePrefix("https://").removePrefix("http://"))

    if (api.canManage) PreviewThumbnailSettings(api)
    if (api.canManage) ArrSettings(s, api)

    SettingsSection("Connecting")
    SettingsNote("These apply the next time the app connects to a server.")
    ToggleSetting(
        "Allow relay",
        "If the TV can't reach your server directly, connect through Plex's relay. Slower, but it works almost anywhere.",
        s.allowRelay,
    ) { s.allowRelay = it }
    EnumSetting(
        "Allow unencrypted connections",
        "Use plain HTTP when a secure connection isn't available.",
        InsecureConnections.entries,
        s.insecureConnections,
    ) { s.insecureConnections = it }

    ResetToDefaults("Server") { s.reset(AppSettings.Group.Server) }
}

/** When the server makes preview thumbnails (Plex's "Generate video preview thumbnails" task). */
private enum class PreviewSchedule(val value: String, override val label: String) : Labeled {
    Never("never", "Never"),
    Scheduled("scheduled", "During scheduled maintenance"),
    Asap("asap", "As soon as videos are added"),
}

/**
 * Owner only: the server's preview thumbnail settings, per library, plus when it makes them.
 * Read from and saved straight to the server.
 */
@Composable
private fun PreviewThumbnailSettings(api: PlexServerApi) {
    val scope = rememberCoroutineScope()
    var libraries by remember { mutableStateOf<List<Pair<PlexLibrary, Boolean>>?>(null) }
    var schedule by remember { mutableStateOf<PreviewSchedule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api) {
        libraries = runCatching { api.previewGeneration() }.getOrElse { error = "Couldn't read the server's settings."; emptyList() }
        schedule = runCatching { api.previewSchedule() }.getOrNull()
            ?.let { v -> PreviewSchedule.entries.firstOrNull { it.value == v } }
    }

    SettingsSection("Preview thumbnails")
    SettingsNote(
        "Small pictures of each video, shown while seeking. The server makes them in the background " +
            "(a big library can take a few nights) and they use roughly 1–3% extra disk space."
    )
    val list = libraries
    when {
        list == null -> SettingsNote("Loading…")
        error != null -> SettingsNote(error!!)
        else -> list.forEachIndexed { i, (lib, on) ->
            ToggleSetting("Make previews for ${lib.title}", null, on) { wanted ->
                libraries = list.toMutableList().also { it[i] = lib to wanted }
                scope.launch {
                    runCatching { api.setPreviewGeneration(lib.key, wanted) }.onFailure {
                        libraries = list // couldn't save: put the switch back
                    }
                }
            }
        }
    }
    schedule?.let { current ->
        EnumSetting(
            "When to make them",
            "Scheduled uses the server's maintenance hours (usually overnight). As soon as added is quicker but works the server hard.",
            PreviewSchedule.entries,
            current,
        ) { picked ->
            val before = schedule
            schedule = picked
            scope.launch { runCatching { api.setPreviewSchedule(picked.value) }.onFailure { schedule = before } }
        }
    }
}

@Composable
private fun AccountPage(
    client: PlexClient,
    onSwitchUser: () -> Unit,
    onManageProfiles: () -> Unit,
    onSignOut: () -> Unit,
) {
    SettingsSection("Profile")
    ActionSetting("Switch user", "Watching as someone else in your Plex Home.", value = client.profileTitle, onClick = onSwitchUser)
    ActionSetting("Manage profiles", "See everyone in your Plex Home.", onClick = onManageProfiles)

    SettingsSection("This TV")
    ActionSetting("Sign out of Plex", "Unlink this TV from your Plex account. You'll need a new code to sign in again.", onClick = onSignOut)
}

@Composable
private fun AboutPage(client: PlexClient, api: PlexServerApi, s: AppSettings) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
    }
    var serverVersion by remember { mutableStateOf("Checking…") }
    LaunchedEffect(api) {
        serverVersion = try {
            api.serverVersion() ?: "Unknown"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't reach the server"
        }
    }
    var cacheCleared by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var didReset by remember { mutableStateOf(false) }

    SettingsSection("This app")
    InfoSetting("App", "Plexus")
    InfoSetting("Version", appVersion)
    InfoSetting("Device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
    InfoSetting("Android", Build.VERSION.RELEASE)
    InfoSetting("Device ID", client.clientId.take(8))

    SettingsSection("Server")
    InfoSetting(api.name, serverVersion)

    SettingsSection("Maintenance")
    ActionSetting(
        "Clear image cache",
        "Free up memory and reload posters and artwork from the server.",
        value = if (cacheCleared) "Cleared" else null,
    ) {
        clearImageCache()
        cacheCleared = true
    }
    ActionSetting(
        "Reset all settings",
        if (confirmReset) "Press OK again to reset everything on this page and the others." else "Put every setting here back to its default. Your sign-in stays.",
        value = when {
            didReset -> "Done"
            confirmReset -> "Confirm"
            else -> null
        },
    ) {
        if (confirmReset) {
            s.resetAll()
            confirmReset = false
            didReset = true
        } else {
            confirmReset = true
            didReset = false
        }
    }
}
