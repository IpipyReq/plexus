@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.arr.ArrTitle
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.ui.theme.PlexSerif
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A Search row of titles found by Radarr or Sonarr; OK on one opens the add panel. */
@Composable
fun ArrResultsRow(title: String, titles: List<ArrTitle>, cardScale: Float, modifier: Modifier, onAdded: (ArrTitle) -> Unit) {
    var adding by remember { mutableStateOf<ArrTitle?>(null) }
    val rowState = rememberLazyListState()
    Column(modifier) {
        RowHeading(title)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(cardGap(16.dp)),
        ) {
            itemsIndexed(titles, key = { i, t -> "${t.title}|${t.year}|$i" }) { i, t ->
                PosterAddCard(
                    poster = t.poster,
                    title = t.title,
                    year = t.year,
                    badge = if (t.added) "Added" else "+ Add",
                    accent = !t.added,
                    modifier = Modifier.width(118.dp * cardScale),
                    cardModifier = Modifier.glideIntoView(rowState, i),
                ) { adding = t }
            }
        }
    }
    adding?.let { t ->
        SidePanel(title = if (t.kind == ArrKind.Movie) "Add movie" else "Add show", onClose = { adding = null }) { first ->
            TitleHeader(t.poster, t.title, listOfNotNull(t.year?.toString(), seasonsText(t.seasons)), t.overview)
            if (t.kind == ArrKind.Movie) {
                com.example.plex_tv_app.tmdb.Release.fromRadarr(t.json).line(monitored = t.added)?.let { ReleaseLine(it) }
            }
            Spacer(Modifier.height(12.dp))
            ArrAddActions(t, first, onClose = { adding = null }, onAdded = { onAdded(t) })
        }
    }
}

@Composable
internal fun RowHeading(text: String, start: androidx.compose.ui.unit.Dp = 14.dp) {
    Text(
        text,
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = palette.text,
        modifier = Modifier.padding(start = start),
    )
}

internal fun seasonsText(n: Int?): String? = n?.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }

/** A poster with a small corner label ("+ Add", "Added", "In library"), title and year under it. */
@Composable
internal fun PosterAddCard(
    poster: String?,
    title: String,
    year: Int?,
    badge: String?,
    accent: Boolean,
    modifier: Modifier,
    cardModifier: Modifier,
    release: com.example.plex_tv_app.tmdb.ReleaseState? = null, // the coloured corner tag
    onClick: () -> Unit,
) {
    val settings = LocalSettings.current
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = cardModifier.confettiAnchor().returnFocusHere().fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(containerColor = palette.raised, focusedContainerColor = palette.raised),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (poster != null) {
                    RemoteImage(poster, Modifier.fillMaxSize())
                } else {
                    Text(title, fontSize = 13.sp, color = palette.muted, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                }
                release?.let { ReleaseCorner(it, Modifier.align(Alignment.TopEnd)) }
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (accent) palette.background else palette.text,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (accent) palette.accent else palette.background.copy(alpha = 0.78f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        if (settings.cardText != com.example.plex_tv_app.settings.CardText.None) {
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 12.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            year?.let { Text(it.toString(), fontSize = 11.sp, color = palette.muted) }
        }
    }
}

/** Poster, title, a line of facts and the description, at the top of an add panel. */
@Composable
internal fun TitleHeader(poster: String?, title: String, facts: List<String>, overview: String?) {
    Row {
        if (poster != null) {
            RemoteImage(poster, Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), fontSize = 12.sp, color = palette.muted)
            overview?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = palette.muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The add buttons: movies just get added; shows ask which episodes to get. [first] goes on the
 * first button, which is always there so the panel's starting highlight never disappears.
 * A null [t] means it's still being looked up ([waiting] says what's happening).
 */
@Composable
internal fun ArrAddActions(
    t: ArrTitle?,
    first: Modifier,
    onClose: () -> Unit,
    onAdded: () -> Unit,
    kind: ArrKind = t?.kind ?: ArrKind.Movie,
    waiting: String? = null,
) {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val movie = kind == ArrKind.Movie
    val app = if (movie) "Radarr" else "Sonarr"
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun add(monitor: String) {
        val title = t ?: return
        if (busy || done || title.added) return
        val client = settings.arr(kind) ?: return
        val profile = if (movie) settings.radarrProfile else settings.sonarrProfile
        val root = if (movie) settings.radarrRoot else settings.sonarrRoot
        if (profile.isBlank() || root.isBlank()) {
            status = "Choose a quality and folder for $app in Settings → Server first."
            return
        }
        busy = true
        status = "Adding…"
        scope.launch {
            status = try {
                client.add(title, profile, root, monitor)
                com.example.plex_tv_app.arr.ArrTracked.forget()
                done = true
                onAdded()
                "Added, and $app has confirmed it's monitoring it. It's searching now; it'll appear in your library once it's downloaded" +
                    if (movie) " (or once it's released)." else "."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't add it: ${e.message ?: "no answer from $app"}"
            } finally {
                busy = false
            }
        }
    }

    val firstLabel = when {
        t == null -> waiting ?: "Checking $app…"
        done -> "Done"
        t.added -> "Already monitored in $app"
        movie -> "Add and search now"
        else -> "All seasons"
    }
    PickerRow(firstLabel, done || t?.added == true, first) {
        when {
            t == null -> Unit
            done || t.added -> onClose()
            else -> add("all")
        }
    }
    if (t != null && !movie && !done && !t.added) {
        PickerRow("Future episodes only", false, Modifier) { add("future") }
        PickerRow("First season only", false, Modifier) { add("firstSeason") }
        PickerRow("Latest season only", false, Modifier) { add("latestSeason") }
    }
    if (t != null && (t.added || done)) {
        PickerRow("Search again", false, Modifier) {
            val client = settings.arr(kind) ?: return@PickerRow
            if (busy) return@PickerRow
            busy = true
            status = "Asking $app to search…"
            scope.launch {
                status = try {
                    val id = client.trackedId(t) ?: throw IllegalStateException("$app doesn't list it")
                    client.searchNow(id)
                    "$app is searching now. Check its Activity page to see what it finds."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Couldn't start a search: ${e.message ?: "no answer from $app"}"
                } finally {
                    busy = false
                }
            }
        }
    }
    if (t?.added == true && !done) {
        Spacer(Modifier.height(8.dp))
        Text("$app is already monitoring this. If it isn't in Plex yet, it hasn't been released or is still downloading.", fontSize = 13.sp, lineHeight = 17.sp, color = palette.muted)
    }
    status?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 13.sp, lineHeight = 17.sp, color = palette.text)
    }
}

/** "In cinemas now · Digital release expected 12 Nov 2026", under a title's description. */
@Composable
internal fun ReleaseLine(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = palette.accent)
}

/**
 * The small coloured tag in a poster's top-right corner: green ↓ out digitally, amber film
 * in cinemas now, blue clock coming soon. Same colours on every theme.
 */
@Composable
internal fun ReleaseCorner(state: com.example.plex_tv_app.tmdb.ReleaseState, modifier: Modifier = Modifier) {
    val (colour, icon) = when (state) {
        com.example.plex_tv_app.tmdb.ReleaseState.Digital -> androidx.compose.ui.graphics.Color(0xFF3DDC84) to com.example.plex_tv_app.R.drawable.ic_release_digital
        com.example.plex_tv_app.tmdb.ReleaseState.Cinemas -> androidx.compose.ui.graphics.Color(0xFFFFB020) to com.example.plex_tv_app.R.drawable.ic_release_cinema
        com.example.plex_tv_app.tmdb.ReleaseState.Soon -> androidx.compose.ui.graphics.Color(0xFF5AA0FF) to com.example.plex_tv_app.R.drawable.ic_release_soon
    }
    Box(
        modifier
            .size(18.dp)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
            .background(colour),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = when (state) {
                com.example.plex_tv_app.tmdb.ReleaseState.Digital -> "Out digitally"
                com.example.plex_tv_app.tmdb.ReleaseState.Cinemas -> "In cinemas"
                com.example.plex_tv_app.tmdb.ReleaseState.Soon -> "Coming soon"
            },
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF111111)),
            modifier = Modifier.size(11.dp),
        )
    }
}
