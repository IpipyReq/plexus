@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package com.example.plex_tv_app.ui

import com.example.plex_tv_app.settings.EpisodeSelect
import com.example.plex_tv_app.settings.MovieSelect
import androidx.compose.runtime.ReadOnlyComposable
import com.example.plex_tv_app.ui.theme.palette
import android.widget.Toast
import android.text.format.DateFormat
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.example.plex_tv_app.plex.PlexHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.plex_tv_app.settings.ClockMode
import com.example.plex_tv_app.settings.LocalSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.plex_tv_app.settings.NavStyle
import com.example.plex_tv_app.ui.theme.PlexSerif
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.R
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexLibrary
import com.example.plex_tv_app.plex.PlexServerApi
import kotlinx.coroutines.CancellationException

private val NavCollapsed = 64.dp
private val NavExpanded = 280.dp
// Translucent when collapsed so Home's artwork shows through; nearly solid when open.
private val NavCollapsedBackground: Color @Composable @ReadOnlyComposable get() = palette.background.copy(alpha = 0.6f)
private val NavExpandedBackground: Color @Composable @ReadOnlyComposable get() = palette.background.copy(alpha = 0.95f)

/**
 * Opens the side nav, for pages whose layout fools the usual "Left at the start of a row"
 * check (e.g. a grid with a filter above it). Null where the nav isn't allowed.
 */
val LocalOpenNav = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The last thing highlighted on the page, so leaving the side nav can go straight back to it. */
class ReturnFocus {
    internal var target: FocusRequester? = null
}

val LocalReturnFocus = staticCompositionLocalOf<ReturnFocus?> { null }

/** Opens the list of everything a cast member is in (provided by Shell). */
val LocalOpenPerson = staticCompositionLocalOf<((com.example.plex_tv_app.plex.CastMember) -> Unit)?> { null }

/** Runs Plex's "Refresh Metadata" on a title (provided by Shell for the title pages' button). */
val LocalUpdateMetadata = staticCompositionLocalOf<((PlexItem) -> Unit)?> { null }

/** How much of the left edge the side nav's icon strip covers, for artwork that reaches under it. */
val LocalNavInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * Put on anything on a page that can be highlighted: while it's highlighted it becomes the
 * place Right/Back from the side nav returns to. (The open nav covers the first column, so
 * "the nearest thing to the right" would otherwise be the wrong card.)
 */
@Composable
fun Modifier.returnFocusHere(): Modifier {
    val memory = LocalReturnFocus.current ?: return this
    val self = remember { FocusRequester() }
    return this
        .focusRequester(self)
        .onFocusChanged { if (it.isFocused) memory.target = self }
}

/** Where the side navigation can take you. */
sealed interface Destination {
    data object Home : Destination
    data object Search : Destination
    data object Settings : Destination
    data object Profiles : Destination
    data object Collections : Destination
    data object Discover : Destination
    data class Library(val library: PlexLibrary) : Destination
}

/** A screen opened on top of a destination. Back closes it. */
private sealed interface Page {
    /** A grid of items, e.g. a folder of episodes. */
    data class Items(val title: String, val path: String, val focusKey: String? = null) : Page

    /** A movie or show's details screen. */
    data class Details(val target: DetailsTarget) : Page
}

/** What the hold-OK menu was opened on. */
private data class MenuTarget(
    val item: PlexItem,
    val restoreFocus: () -> Unit,
    val fromHome: Boolean,
    val rowKey: String?, // the Home row the card is in, if any
    val libraryKey: String? = null, // set when it's in a library's Recommended row
)

/** Row styles of a library's Recommended rows are saved under "lib:<library>|<row>", apart from Home's. */
private fun libraryStyleKey(libraryKey: String, rowKey: String) = "lib:$libraryKey|$rowKey"

/** Puts Home rows in your saved order; rows you haven't placed keep Plex's order, after the rest. */
internal fun orderRows(hubs: List<PlexHub>, order: List<String>): List<PlexHub> {
    val position = order.withIndex().associate { it.value to it.index }
    return hubs.withIndex()
        .sortedWith(compareBy({ position[it.value.rowKey] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/**
 * The main app frame: side navigation on the left, the selected page on the right,
 * a clock in the top-right corner, and the hold-OK menu floating on the right.
 */
@Composable
fun Shell(
    client: PlexClient,
    api: PlexServerApi,
    onSwitchUser: () -> Unit,
    onShowServers: () -> Unit,
    onSignOut: () -> Unit,
) {
    var destination by remember { mutableStateOf<Destination>(Destination.Home) }
    val drilldowns = remember { mutableStateListOf<Page>() }
    var libraries by remember { mutableStateOf<List<PlexLibrary>>(emptyList()) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped after changes like "Mark as Watched" so the visible page refreshes in place.
    var reloadToken by remember { mutableIntStateOf(0) }
    var cardScale by remember { mutableFloatStateOf(client.cardScale) }
    var rowOrder by remember { mutableStateOf(client.rowOrder) }
    var rowStyles by remember {
        mutableStateOf(
            client.rowStyles.mapNotNull { (key, name) ->
                runCatching { CardStyle.valueOf(name) }.getOrNull()?.let { key to it }
            }.toMap()
        )
    }
    var hubs by remember { mutableStateOf<List<PlexHub>?>(null) }
    var hubsError by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    LaunchedEffect(api, reloadToken) {
        try {
            libraries = api.libraries().filter { it.type == "movie" || it.type == "show" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Couldn't load libraries: ${e.message}")
        }
    }

    LaunchedEffect(api, reloadToken) {
        try {
            hubs = api.homeHubs()
            hubsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hubs == null) hubsError = e.message ?: e.toString()
        }
    }
    val orderedHubs = remember(hubs, rowOrder) { hubs?.let { orderRows(it, rowOrder) } }

    /** Re-fetch everything on screen: Home rows, library lists and the nav's libraries. */
    fun refresh() {
        api.clearCache()
        reloadToken++
    }

    // Auto-refresh: on the interval chosen in Settings while the app is open...
    val settings = LocalSettings.current
    // "Separate sizes for each profile" switched: use the card size for the new choice.
    LaunchedEffect(settings.sizesPerProfile) {
        client.sharedSizes = !settings.sizesPerProfile
        cardScale = client.cardScale
    }
    LaunchedEffect(api, settings.refreshInterval) {
        val minutes = settings.refreshInterval.minutes
        if (minutes <= 0) return@LaunchedEffect
        while (true) {
            delay(minutes * 60_000L)
            refresh()
        }
    }
    // ...and whenever you come back to the app after leaving it.
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        var awaySince = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> awaySince = android.os.SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_RESUME -> if (awaySince > 0) {
                    // Pages keep their place either way; the lists only re-fetch after 10+ minutes away.
                    val away = android.os.SystemClock.elapsedRealtime() - awaySince
                    awaySince = 0L
                    if (away > 10 * 60_000L) refresh()
                }
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    // Bumped to reopen the current page from scratch (selecting the page you're already on).
    var visit by remember { mutableIntStateOf(0) }

    fun go(to: Destination) {
        accountMenuOpen = false
        if (to == destination) {
            // Selecting the page you're on refreshes it and takes you back to its top.
            refresh()
            visit++
        }
        drilldowns.clear()
        destination = to
    }

    // Theme picker: shows Home underneath while open, then returns to Settings → Appearance.
    var themePickerOpen by remember { mutableStateOf(false) }
    // Side bar style picker: same idea, with Home and the side bar showing behind it.
    var navPickerOpen by remember { mutableStateOf(false) }
    val pickerOpen = themePickerOpen || navPickerOpen
    val inDetails = drilldowns.lastOrNull() is Page.Details && !pickerOpen
    // Title pages only get the side nav if Settings → Home & browsing → "Side bar on title pages" is on.
    val navBlocked = inDetails && !settings.navInDetails
    // Room the icon strip takes on the left (none if it's hidden or not allowed here).
    val navInset = if (navBlocked || settings.hideNavBar) 0.dp else NavCollapsed
    var settingsStart by remember { mutableStateOf<String?>(null) }
    // Settings reads its starting page once; forget it afterwards so a later visit starts at General.
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen && settingsStart != null) {
            delay(500)
            settingsStart = null
        }
    }

    // The side nav can only be reached one way: Left at the start of a row. Until then its
    // items can't take focus at all, so no other key press can wander into it.
    val currentNavItem = remember { FocusRequester() }
    val returnFocus = remember { ReturnFocus() }
    var navFocused by remember { mutableStateOf(false) }
    var navOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    /** Leave the side nav: back to what you had highlighted on the page. */
    fun returnToPage() {
        val back = returnFocus.target
        val restored = back != null && runCatching { back.requestFocus() }.let { r ->
            r.isSuccess && (r.getOrNull() as Any?) != false
        }
        if (!restored) focusManager.moveFocus(FocusDirection.Right)
    }

    // Lock the nav again once focus has really left it (Right, Back, or picking an item).
    // Moving between nav items briefly reports "no focus", so wait a couple of frames to be sure.
    LaunchedEffect(navFocused) {
        if (navFocused || !navOpen) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (!navFocused) navOpen = false
    }

    // Left presses, to tell a deliberate press at the start of a row from a run of quick presses
    // (or a held button) that briefly outruns the row's scrolling.
    val leftPresses = remember { longArrayOf(0L, 0L) } // [previous, latest] press times
    fun openNav() {
        // Mid-run (another Left within 300 ms): the row just hasn't caught up; don't open.
        if (leftPresses[1] - leftPresses[0] < 300) return
        navOpen = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(3) {
                if (runCatching { currentNavItem.requestFocus() }.isSuccess && navFocused) return@launch
                withFrameNanos { }
            }
            if (!navFocused) navOpen = false
        }
    }

    fun closeMenu() {
        val target = menu
        menu = null
        target?.restoreFocus?.invoke()
    }

    // The player, full screen over everything while something is playing.
    var playing by remember { mutableStateOf<PlayRequest?>(null) }

    /** Play (or resume) a movie or episode; shows and seasons play their next episode. */
    fun play(item: PlexItem, fromStart: Boolean) {
        scope.launch {
            val target = runCatching { api.playableFor(item) }.getOrNull()
            if (target == null) toast("Nothing to play in ${item.title}") else playing = PlayRequest(listOf(target), 0, fromStart)
        }
    }

    /** Play a list in a random order (Shuffle on a collection). Shows contribute their next episode. */
    fun shuffle(items: List<PlexItem>) {
        scope.launch {
            val queue = items.mapNotNull { runCatching { api.playableFor(it) }.getOrNull() }
            if (queue.isNotEmpty()) playing = PlayRequest(queue, 0, fromStart = true)
        }
    }

    fun setWatched(item: PlexItem, watched: Boolean) {
        scope.launch {
            try {
                api.setWatched(item, watched)
                api.clearCache()
                reloadToken++
                toast(if (watched) "Marked as watched" else "Marked as unwatched")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't update: ${e.message}")
            }
        }
    }

    /** Plex's "Refresh Metadata" for a title (hold-OK menu, and the button on title pages). */
    fun updateMetadata(item: PlexItem) {
        scope.launch {
            try {
                api.refreshMetadata(item)
                toast("Updating ${item.title}… this can take a minute")
                // The server works in the background; pick up the new details shortly after.
                delay(8_000)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(
                    if ((e as? com.example.plex_tv_app.plex.PlexException)?.code in setOf(401, 403))
                        "Only the server's owner (or admins) can update metadata"
                    else "Couldn't update metadata: ${e.message}"
                )
            }
        }
    }

    // Selecting a card: shows and seasons open their details; movies and episodes follow
    // Settings → Home & browsing → Selecting a movie / an episode.
    val open: (PlexItem) -> Unit = { item ->
        when (item.type) {
            "show", "collection" -> drilldowns.add(Page.Details(DetailsTarget(item.ratingKey)))
            "season" -> drilldowns.add(
                Page.Details(DetailsTarget(item.parentRatingKey ?: item.ratingKey, seasonKey = item.ratingKey))
            )
            "movie" -> when (settings.movieSelect) {
                MovieSelect.Play -> play(item, fromStart = false)
                MovieSelect.Details -> drilldowns.add(Page.Details(DetailsTarget(item.ratingKey)))
            }
            "episode" -> {
                val show = item.grandparentRatingKey
                if (settings.episodeSelect == EpisodeSelect.Season && show != null) {
                    // The show's page, on this episode's season, with the episode highlighted.
                    drilldowns.add(Page.Details(DetailsTarget(show, item.parentRatingKey, item.ratingKey)))
                } else {
                    play(item, fromStart = false)
                }
            }
            else -> play(item, fromStart = false)
        }
    }
    val longPress: (PlexItem, String?, () -> Unit) -> Unit = { item, rowKey, restore ->
        menu = MenuTarget(
            item = item,
            restoreFocus = restore,
            fromHome = destination == Destination.Home && drilldowns.isEmpty(),
            rowKey = rowKey,
        )
    }
    val gridLongPress: (PlexItem, () -> Unit) -> Unit = { item, restore -> longPress(item, null, restore) }

    // Each library's Recommended row order (only the ones changed this session; the rest come from saved settings).
    val libraryRowOrders = remember { mutableStateMapOf<String, List<String>>() }
    fun libraryRowOrder(key: String): List<String> = libraryRowOrders[key] ?: client.libraryRowOrder(key)
    fun setLibraryRowOrder(key: String, order: List<String>) {
        libraryRowOrders[key] = order
        client.setLibraryRowOrder(key, order)
    }
    fun libraryRowStyles(key: String): Map<String, CardStyle> {
        val prefix = "lib:$key|"
        return rowStyles.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    // Back: close a drilldown first, then return to Home, then leave the app.
    BackHandler(enabled = drilldowns.isNotEmpty() || destination != Destination.Home) {
        if (drilldowns.isNotEmpty()) drilldowns.removeAt(drilldowns.lastIndex) else go(Destination.Home)
    }
    // ...but if the side nav is open, Back just closes it and returns you to the page.
    BackHandler(enabled = navFocused && !accountMenuOpen) { returnToPage() }

    // Where you were on Home and in each grid, so Back returns you to the same spot.
    // Selecting the page you're already on (visit++) starts it fresh.
    val homeMemory = remember(api, visit) { HomeMemory() }
    val gridMemories = remember(api, visit) { mutableMapOf<String, GridMemory>() }
    val collectionsMemory = remember(api, visit) { CollectionsMemory() }
    val libraryMemories = remember(api, visit) { mutableMapOf<String, LibraryMemory>() }
    // Per-profile settings (text size) follow whoever's signed in.
    SideEffect { settings.useProfile(client.profileId) }
    val discoverMemory = remember(api) { DiscoverMemory() }
    val plexDiscover = remember(client) { com.example.plex_tv_app.plex.PlexDiscover(client) }
    val searchMemory = remember(api) { SearchMemory() } // the search survives going in and out of results
    fun gridMemory(path: String) = gridMemories.getOrPut(path) { GridMemory() }

    Box(Modifier.fillMaxSize()) {
        // Page content, offset by the collapsed nav's width (details screens use the full width).
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = navInset)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                        leftPresses[0] = leftPresses[1]
                        leftPresses[1] = android.os.SystemClock.uptimeMillis()
                    }
                    false // only watching
                }
                // Left: move as normal; if there's nothing further left (the start of a row),
                // open the side nav on the current page's item. Never on a details screen.
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                        return@onKeyEvent false
                    }
                    val moved = focusManager.moveFocus(FocusDirection.Left)
                    if (!moved && !navBlocked) openNav()
                    true
                },
        ) {
            val shown = if (pickerOpen) Destination.Home else destination
            // Home, libraries and details glide into view themselves (SmoothScroll.kt), so the
            // built-in focus scrolling is off here. Settings keeps the normal behaviour.
            val systemScroll = LocalBringIntoViewSpec.current
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides NoAutoScroll,
                LocalOpenNav provides (if (navBlocked) null else ({ openNav() })),
                LocalNavInset provides navInset,
                LocalOpenPerson provides { person -> drilldowns.add(Page.Items(person.name, api.actorPath(person))) },
                LocalUpdateMetadata provides (if (api.canManage) ({ item: PlexItem -> updateMetadata(item) }) else null),
                LocalReturnFocus provides returnFocus,
                LocalPlexDiscover provides plexDiscover,
            ) {
            key(shown, drilldowns.size, visit) {
                val top = drilldowns.lastOrNull()
                if (top is Page.Details && !pickerOpen) {
                    DetailsScreen(
                        api = api,
                        target = top.target,
                        reloadToken = reloadToken,
                        onOpen = open,
                        onShuffle = { items, _ -> shuffle(items) },
                        onPlay = ::play,
                        onSetWatched = ::setWatched,
                        onLongPress = gridLongPress,
                    )
                } else if (top is Page.Items && !pickerOpen) {
                    ItemsScreen(
                        api, top.title, top.path, reloadToken, cardScale, open, gridLongPress,
                        focusKey = top.focusKey, memory = gridMemory(top.path),
                    )
                } else {
                    when (val d = shown) {
                        Destination.Home -> HomeScreen(api, orderedHubs, hubsError, cardScale, rowStyles, open, longPress, homeMemory)
                        is Destination.Library -> LibraryScreen(
                            api = api,
                            library = d.library,
                            reloadToken = reloadToken,
                            cardScale = cardScale,
                            memory = libraryMemories.getOrPut(d.library.key) { LibraryMemory() },
                            onOpen = open,
                            onOpenCollection = { c -> drilldowns.add(Page.Details(DetailsTarget(c.ratingKey))) },
                            onOpenCategory = { c -> drilldowns.add(Page.Items("${d.library.title} · ${c.title}", c.path)) },
                            onLongPress = gridLongPress,
                            rowOrder = libraryRowOrder(d.library.key),
                            rowStyles = libraryRowStyles(d.library.key),
                            onRowLongPress = { item, rowKey, restore ->
                                menu = MenuTarget(item, restore, fromHome = false, rowKey = rowKey, libraryKey = d.library.key)
                            },
                        )
                        Destination.Search -> SearchScreen(api, client, cardScale, searchMemory, open, gridLongPress)
                        Destination.Discover -> DiscoverScreen(api, cardScale, discoverMemory, open)
                        Destination.Settings -> CompositionLocalProvider(LocalBringIntoViewSpec provides systemScroll) { SettingsScreen(
                            client = client,
                            api = api,
                            cardScale = cardScale,
                            onCardScale = {
                                cardScale = it
                                client.cardScale = it
                            },
                            onResetRows = {
                                rowOrder = emptyList()
                                client.rowOrder = emptyList()
                            },
                            onResetRowStyles = {
                                rowStyles = emptyMap()
                                client.rowStyles = emptyMap()
                            },
                            onRefresh = ::refresh,
                            onShowServers = onShowServers,
                            onSwitchUser = onSwitchUser,
                            onManageProfiles = { go(Destination.Profiles) },
                            onSignOut = onSignOut,
                            onChangeTheme = {
                                settingsStart = "Appearance"
                                themePickerOpen = true
                            },
                            onChangeNavStyle = {
                                settingsStart = "Appearance"
                                navPickerOpen = true
                            },
                            startCategory = settingsStart,
                        ) }
                        Destination.Profiles -> ManageProfilesScreen(client, onSwitchUser = onSwitchUser)
                        Destination.Collections -> CollectionsScreen(api, reloadToken, cardScale, collectionsMemory) { c ->
                            drilldowns.add(Page.Details(DetailsTarget(c.ratingKey)))
                        }
                    }
                }
            }
            }
        }

        Clock(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 36.dp))
        AutoUpdateCheck()

        if (!navBlocked) {
            SideNav(
                hidden = settings.hideNavBar,
                client = client,
                destination = destination,
                libraries = libraries,
                accountMenuOpen = accountMenuOpen,
                currentItem = currentNavItem,
                canFocus = { navOpen },
                onFocusChange = { navFocused = it },
                onExitRight = ::returnToPage,
                onAccount = { accountMenuOpen = true },
                onSelect = ::go,
                showDiscover = settings.showDiscover && settings.discoverReady && settings.canDownload(api.canManage),
            )
        }

        if (accountMenuOpen) {
            AccountMenu(
                client = client,
                api = api,
                onClose = {
                    accountMenuOpen = false
                    openNav()
                },
                onSwitchUser = onSwitchUser,
                onShowServers = onShowServers,
                onManageProfiles = { go(Destination.Profiles) },
                leftInset = if (settings.hideNavBar) 0.dp else NavCollapsed,
            )
        }

        if (themePickerOpen) {
            ThemePicker(onClose = { themePickerOpen = false })
        }
        if (navPickerOpen) {
            NavStylePicker(onClose = { navPickerOpen = false })
        }

        playing?.let { request ->
            key(request) {
                PlayerScreen(api, request, onExit = {
                    playing = null
                    // Progress and watched status changed: refresh what's on screen.
                    api.clearCache()
                    reloadToken++
                    // And put focus back on whatever started playback (Play button, episode...).
                    scope.launch {
                        withFrameNanos { }
                        returnFocus.target?.let { target -> runCatching { target.requestFocus() } }
                    }
                })
            }
        }

        val target = menu
        if (target != null) {
            key(target) {
                // The rows Customise Rows can reorder: Home's, or the library's Recommended rows.
                val libKey = target.libraryKey
                val menuRows = when {
                    target.fromHome -> orderedHubs
                    libKey != null -> libraryMemories[libKey]?.recommendedHubs?.let { orderRows(it, libraryRowOrder(libKey)) }
                    else -> null
                }
                ItemMenu(
                    api = api,
                    item = target.item,
                    rows = menuRows,
                    rowStyle = target.rowKey?.let { key ->
                        menuRows?.firstOrNull { it.rowKey == key }?.styleIn(
                            if (libKey != null) libraryRowStyles(libKey) else rowStyles
                        )
                    },
                    onRowStyle = { style ->
                        target.rowKey?.let { key ->
                            val stored = if (libKey != null) libraryStyleKey(libKey, key) else key
                            rowStyles = rowStyles + (stored to style)
                            client.rowStyles = rowStyles.mapValues { it.value.name }
                        }
                    },
                    cardScale = cardScale,
                    onCardScale = {
                        cardScale = it
                        client.cardScale = it
                    },
                    onMoveRow = { from, to ->
                        val keys = menuRows.orEmpty().map { it.rowKey }.toMutableList()
                        if (from in keys.indices && to in keys.indices) {
                            keys.add(to, keys.removeAt(from))
                            if (libKey != null) {
                                setLibraryRowOrder(libKey, keys)
                            } else {
                                rowOrder = keys
                                client.rowOrder = keys
                            }
                        }
                    },
                    onResetRows = {
                        if (libKey != null) {
                            setLibraryRowOrder(libKey, emptyList())
                        } else {
                            rowOrder = emptyList()
                            client.rowOrder = emptyList()
                        }
                    },
                    onPlay = { fromStart ->
                        closeMenu()
                        play(target.item, fromStart)
                    },
                    onSetWatched = { watched ->
                        closeMenu()
                        setWatched(target.item, watched)
                    },
                    onRate = { stars ->
                        closeMenu()
                        scope.launch {
                            try {
                                api.rate(target.item, stars?.times(2))
                                api.clearCache()
                                reloadToken++
                                toast(if (stars == null) "Rating cleared" else "Rated ${"★".repeat(stars)}")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toast("Couldn't rate: ${e.message}")
                            }
                        }
                    },
                    onRefresh = {
                        closeMenu()
                        refresh()
                        toast("Refreshing…")
                    },
                    // Opens the theme picker over Home; closing it comes back to where you were.
                    onAppearance = {
                        closeMenu()
                        themePickerOpen = true
                    },
                    onClose = ::closeMenu,
                    // Only for the server's owner: nobody else is allowed to, so they don't see it.
                    onUpdateMetadata = if (api.canManage && target.item.type in setOf("movie", "show", "season", "episode")) {
                        {
                            closeMenu()
                            updateMetadata(target.item)
                        }
                    } else null,
                    // Only on cards in a Continue Watching (or In Progress) row.
                    onRemoveFromContinueWatching = target.rowKey
                        ?.substringBefore('|')
                        ?.takeIf { id -> listOf("ondeck", "continue", "inprogress").any { id.contains(it, ignoreCase = true) } }
                        ?.let {
                            {
                                closeMenu()
                                val item = target.item
                                scope.launch {
                                    try {
                                        api.removeFromContinueWatching(item)
                                        api.clearCache()
                                        refresh()
                                        toast("Removed from Continue Watching")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toast("Couldn't remove it: ${e.message}")
                                    }
                                }
                            }
                        },
                    onGoToShow = when (target.item.type) {
                        "episode" -> target.item.grandparentRatingKey
                        "season" -> target.item.parentRatingKey
                        else -> null
                    }?.let { showKey ->
                        {
                            closeMenu()
                            drilldowns.add(Page.Details(DetailsTarget(showKey)))
                        }
                    },
                )
            }
        }
    }
}

/** A small clock in the corner. Settings → General → Clock picks its format or hides it. */
@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode = LocalSettings.current.clockMode
    if (mode == ClockMode.Hidden) return
    val format = remember(mode) {
        when (mode) {
            ClockMode.H12 -> SimpleDateFormat("h:mm a", Locale.getDefault())
            ClockMode.H24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
            else -> DateFormat.getTimeFormat(context)
        }
    }
    var now by remember(mode) { mutableStateOf(format.format(Date())) }
    LaunchedEffect(mode) {
        while (true) {
            now = format.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000) // wake at the next minute
        }
    }
    Text(
        now,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun SideNav(
    hidden: Boolean, // no icon strip while closed; it only appears when opened
    client: PlexClient,
    destination: Destination,
    libraries: List<PlexLibrary>,
    accountMenuOpen: Boolean,
    currentItem: FocusRequester,
    canFocus: () -> Boolean,
    onFocusChange: (Boolean) -> Unit,
    onExitRight: () -> Unit,
    onAccount: () -> Unit,
    onSelect: (Destination) -> Unit,
    showDiscover: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    // [currentItem] goes on the item for the page you're on (see Shell's Left handling).
    fun mark(isCurrent: Boolean) =
        (if (isCurrent) Modifier.focusRequester(currentItem) else Modifier)
            .focusProperties { this.canFocus = canFocus() }

    // The nav's items in order. Up/Down step through these and nothing else: when the nav is
    // open it covers part of the page, and Android would otherwise jump to a card underneath.
    val navChain = remember { FocusChain() }

    // Settings → Appearance → Side bar style (or the one being tried in the picker, shown open on request).
    val settings = LocalSettings.current
    val style = settings.previewNavStyle ?: settings.navStyle
    val open = expanded || settings.previewNavOpen
    val previewing = settings.previewNavStyle != null // in the style picker: always on show
    val shownWidth by animateDpAsState(
        when {
            open -> NavExpanded
            hidden && !previewing -> 0.dp
            else -> NavCollapsed
        },
        label = "navShownWidth",
    )
    val bg = palette.background
    val film = androidx.compose.ui.graphics.lerp(bg, Color.Black, 0.55f)
    val holes = palette.text.copy(alpha = 0.13f)
    val look = when (style) {
        NavStyle.Classic -> Modifier.background(if (open) NavExpandedBackground else NavCollapsedBackground)
        NavStyle.FilmStrip -> Modifier
            .background(film.copy(alpha = if (open) 0.97f else 0.9f))
            .drawBehind {
                // Sprocket holes down both edges.
                val hole = androidx.compose.ui.geometry.Size(5.dp.toPx(), 9.dp.toPx())
                val step = 20.dp.toPx()
                var y = 6.dp.toPx()
                while (y < size.height) {
                    drawRoundRect(holes, topLeft = androidx.compose.ui.geometry.Offset(3.dp.toPx(), y), size = hole,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
                    drawRoundRect(holes, topLeft = androidx.compose.ui.geometry.Offset(size.width - 8.dp.toPx(), y), size = hole,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
                    y += step
                }
            }
        // Whisper, Whisper neon and Gold rail: nothing behind the icons; opening fades in a shade.
        else -> if (open) Modifier.drawBehind {
            val reach = size.width * 1.4f
            drawRect(
                Brush.horizontalGradient(
                    0f to bg.copy(alpha = 0.97f),
                    0.6f to bg.copy(alpha = 0.88f),
                    1f to Color.Transparent,
                    startX = 0f,
                    endX = reach,
                ),
                size = androidx.compose.ui.geometry.Size(reach, size.height),
            )
        } else Modifier
    }
    // Pages get their own neon colour in Whisper neon (on every theme).
    var neonIndex = 0
    fun nextNeon(): Color = NeonColours[neonIndex++ % NeonColours.size]

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(shownWidth)
            .then(if (shownWidth < 2.dp) Modifier.clipToBounds() else Modifier) // hidden: nothing pokes out
            .then(look)
            .onFocusChanged {
                expanded = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> return@onPreviewKeyEvent true // nothing to the left
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onExitRight()
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                val rows = navChain.rows
                val target = navChain.focusedIndex + step
                if (target in rows.indices) runCatching { rows[target].requestFocus() }
                true // top and bottom: stay put
            }
            .padding(vertical = 20.dp, horizontal = if (style == NavStyle.FilmStrip) 12.dp else 10.dp),
    ) {
    CompositionLocalProvider(LocalFocusChain provides navChain) {
        val name = client.profileTitle ?: "Account"
        val accountSelected = accountMenuOpen || destination == Destination.Profiles
        val whisper = style == NavStyle.Whisper || style == NavStyle.WhisperNeon
        NavItem(
            label = name,
            selected = accountSelected,
            expanded = open,
            onClick = onAccount,
            modifier = mark(accountSelected),
            style = style,
            neon = nextNeon(),
        ) { Avatar(client.profileThumb, name, size = if (whisper) 22.dp else 26.dp) }

        Spacer(Modifier.height(10.dp))
        NavItem("Search", destination == Destination.Search, open, { onSelect(Destination.Search) }, mark(destination == Destination.Search), style, nextNeon()) {
            NavIcon(R.drawable.ic_nav_search, whisper)
        }
        NavItem("Home", destination == Destination.Home, open, { onSelect(Destination.Home) }, mark(destination == Destination.Home), style, nextNeon()) {
            NavIcon(R.drawable.ic_nav_home, whisper)
        }
        if (showDiscover) {
            NavItem("Discover", destination == Destination.Discover, open, { onSelect(Destination.Discover) }, mark(destination == Destination.Discover), style, nextNeon()) {
                NavIcon(R.drawable.ic_nav_discover, whisper)
            }
        }
        NavItem("Collections", destination == Destination.Collections, open, { onSelect(Destination.Collections) }, mark(destination == Destination.Collections), style, nextNeon()) {
            NavIcon(R.drawable.ic_nav_collections, whisper)
        }

        // Libraries section
        when (style) {
            NavStyle.Classic -> {
                Box(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.hairline)
                )
                Box(Modifier.height(18.dp).padding(start = 10.dp)) {
                    if (open) {
                        Text("LIBRARIES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            NavStyle.FilmStrip -> Spacer(Modifier.height(14.dp))
            else -> Box(Modifier.fillMaxWidth().height(30.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (open) {
                    Text("LIBRARIES", fontSize = 10.sp, letterSpacing = 1.sp, color = palette.muted, maxLines = 1)
                } else if (style == NavStyle.GoldRail) {
                    Box(Modifier.padding(start = 12.dp).size(4.dp).clip(CircleShape).background(palette.muted))
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            libraries.forEach { library ->
                val target = Destination.Library(library)
                NavItem(library.title, destination == target, open, { onSelect(target) }, mark(destination == target), style, nextNeon()) {
                    NavIcon(if (library.type == "show") R.drawable.ic_nav_tv else R.drawable.ic_nav_movies, whisper)
                }
            }
        }

        NavItem("Settings", destination == Destination.Settings, open, { onSelect(Destination.Settings) }, mark(destination == Destination.Settings), style, NeonSettings) {
            NavIcon(R.drawable.ic_nav_settings, whisper)
        }
    }
    }
}

/** Whisper neon's colours, one per page in order (they stay neon on every theme). */
private val NeonColours = listOf(
    Color(0xFFFF5A78), Color(0xFF50DCFF), Color(0xFFFFBE28), Color(0xFFAA78FF),
    Color(0xFF50FFAA), Color(0xFFFF783C), Color(0xFFFFE65A), Color(0xFF5AA0FF),
)
private val NeonSettings = Color(0xFFC8C8D2)
private val NeonHot = Color(0xFFFFF7EA) // the page you're on: white-hot

/** One side nav item, drawn in the chosen [style]. Highlighting it always fills it brightly. */
@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NavStyle = NavStyle.Classic,
    neon: Color = Color.White,
    icon: @Composable () -> Unit,
) {
    val accent = palette.accent
    val text = palette.text
    val whisper = style == NavStyle.Whisper || style == NavStyle.WhisperNeon
    val restColour = when (style) {
        NavStyle.Classic -> if (selected) text else text.copy(alpha = 0.7f)
        NavStyle.GoldRail -> if (selected) accent else text.copy(alpha = if (expanded) 0.9f else 0.75f)
        NavStyle.FilmStrip -> if (selected) accent else text.copy(alpha = 0.8f)
        NavStyle.Whisper -> if (selected) text else palette.muted
        NavStyle.WhisperNeon -> if (selected) NeonHot else neon.copy(alpha = 0.8f)
    }
    val colors = when (style) {
        NavStyle.Classic -> ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.focusTint else Color.Transparent,
            contentColor = restColour,
            focusedContainerColor = text,
            focusedContentColor = palette.background,
        )
        NavStyle.FilmStrip -> ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.16f) else text.copy(alpha = 0.05f),
            contentColor = restColour,
            focusedContainerColor = text,
            focusedContentColor = palette.background,
        )
        // The minimal looks keep the highlight soft so the icons stay the star.
        else -> ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = restColour,
            focusedContainerColor = text.copy(alpha = 0.14f),
            focusedContentColor = if (style == NavStyle.WhisperNeon) (if (selected) NeonHot else neon) else if (selected && style == NavStyle.GoldRail) accent else text,
        )
    }
    val shape = if (style == NavStyle.FilmStrip) RoundedCornerShape(3.dp) else RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .inChain()
            .confettiAnchor()
            .fillMaxWidth()
            .height(44.dp)
            .padding(vertical = if (style == NavStyle.FilmStrip) 3.dp else 2.dp)
            .then(
                if (style == NavStyle.GoldRail && selected) Modifier.drawBehind {
                    // The gold rail: a thin bar at the screen's edge beside the current page.
                    drawRoundRect(
                        accent,
                        topLeft = androidx.compose.ui.geometry.Offset(-8.dp.toPx(), size.height / 2 - 12.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), 24.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    )
                } else Modifier
            ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = if (style == NavStyle.FilmStrip) ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else text.copy(alpha = 0.18f)),
                shape = shape,
            ),
        ) else ClickableSurfaceDefaults.border(),
        colors = colors,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (style == NavStyle.FilmStrip) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .then(
                        if (style == NavStyle.WhisperNeon) Modifier.drawBehind {
                            // Neon glow behind the icon (strongest on the current page).
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(neon.copy(alpha = if (selected) 0.55f else 0.22f), Color.Transparent),
                                    center = center,
                                    radius = size.minDimension * 0.75f,
                                ),
                                radius = size.minDimension * 0.75f,
                            )
                        } else Modifier
                    )
                    .then(
                        if (whisper && selected) Modifier.drawBehind {
                            // Underline under the current page's icon.
                            val y = size.height - 1.dp.toPx()
                            val c = if (style == NavStyle.WhisperNeon) neon else accent
                            if (style == NavStyle.WhisperNeon) {
                                drawLine(c.copy(alpha = 0.45f), Offset(center.x - 9.dp.toPx(), y), Offset(center.x + 9.dp.toPx(), y), strokeWidth = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            }
                            drawLine(if (style == NavStyle.WhisperNeon) NeonHot else c, Offset(center.x - 7.dp.toPx(), y), Offset(center.x + 7.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) { icon() }
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = if (style == NavStyle.GoldRail) PlexSerif else null,
                    fontSize = if (style == NavStyle.GoldRail) 17.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NavIcon(resource: Int, small: Boolean = false) {
    Icon(painter = painterResource(resource), contentDescription = null, modifier = Modifier.size(if (small) 18.dp else 20.dp))
}

/** The panel that slides out from the Account button. Back or moving away closes it. */
@Composable
private fun AccountMenu(
    client: PlexClient,
    api: PlexServerApi,
    onClose: () -> Unit,
    onSwitchUser: () -> Unit,
    onShowServers: () -> Unit,
    onManageProfiles: () -> Unit,
    leftInset: androidx.compose.ui.unit.Dp, // the nav's icon strip, if shown
) {
    // Up/Down step through the three options explicitly. Left to Android, Down would look for
    // something below on the page behind and get lost; this keeps it inside the menu.
    val items = remember { List(3) { FocusRequester() } }
    var focusedItem by remember { mutableStateOf(0) }
    val first = items[0]
    LaunchedEffect(Unit) {
        repeat(5) { // the panel may need a frame to appear
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // Its own layer (like the hold-OK menu), so the remote can't wander into the nav or the page
    // behind; Back or Left closes it.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    androidx.compose.runtime.SideEffect { window?.setDimAmount(0f) }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
    Column(
        modifier = Modifier
            .padding(start = leftInset + 8.dp, top = 24.dp)
            .width(360.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        items.getOrNull(focusedItem + 1)?.let { runCatching { it.requestFocus() } }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        items.getOrNull(focusedItem - 1)?.let { runCatching { it.requestFocus() } }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true // nothing to the right
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onClose(); true } // back to the nav
                    else -> false
                }
            }
            .padding(16.dp),
    ) {
        val name = client.profileTitle ?: "Account"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(client.profileThumb, name, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Watching on ${api.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ListItem(
            selected = false,
            onClick = {
                onClose()
                onSwitchUser()
            },
            modifier = Modifier.focusRequester(items[0]).onFocusChanged { if (it.isFocused) focusedItem = 0 },
            headlineContent = { Text("Switch user") },
        )
        ListItem(
            selected = false,
            onClick = {
                onClose()
                onShowServers()
            },
            modifier = Modifier.focusRequester(items[1]).onFocusChanged { if (it.isFocused) focusedItem = 1 },
            headlineContent = { Text("Servers") },
            supportingContent = { Text("Connected to ${api.name}") },
        )
        ListItem(
            selected = false,
            onClick = onManageProfiles,
            modifier = Modifier.focusRequester(items[2]).onFocusChanged { if (it.isFocused) focusedItem = 2 },
            headlineContent = { Text("Manage profiles") },
        )
    }
    }
    }
}
