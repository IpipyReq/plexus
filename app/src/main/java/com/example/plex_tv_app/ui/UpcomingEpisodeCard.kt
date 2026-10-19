@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.tmdb.EpisodeState
import com.example.plex_tv_app.tmdb.UpcomingEpisode
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The colour of an episode's status tag (fixed, so they read the same on every theme). */
internal fun EpisodeState.colour(): Color = when (this) {
    EpisodeState.Upcoming -> Color(0xFF5AA0FF)
    EpisodeState.Downloading -> Color(0xFF3DDC84)
    EpisodeState.Queued -> Color(0xFF8FD6B0)
    EpisodeState.Problem -> Color(0xFFFF5A5A)
    EpisodeState.Missing -> Color(0xFFFFB020)
    EpisodeState.Unmonitored, EpisodeState.NotInLibrary -> Color(0xFFB8B8C0)
}

/**
 * An episode you don't have yet, after the latest season's last episode: "EPISODE 6 · COMING
 * THIS WEDNESDAY" (or "AIRED 2 DAYS AGO") and its name over a still, with a small status tag
 * (Downloading 45%, Not downloaded…) and a progress bar while downloading. OK opens Search
 * again / Monitor and search, when Sonarr can do something about it ([owner] only).
 */
@Composable
internal fun UpcomingEpisodeCard(
    episode: UpcomingEpisode,
    fallbackImage: String?,
    differentSeason: Boolean,
    owner: Boolean,
    modifier: Modifier,
    cardModifier: Modifier,
    onFocused: () -> Unit,
    onChanged: () -> Unit,
) {
    val prefs = LocalSettings.current
    var panel by remember { mutableStateOf(false) }
    val label = (if (differentSeason) "Season ${episode.season} · Episode ${episode.number}" else "Episode ${episode.number}") +
        " · " + episode.whenText()
    val canAct = owner && episode.sonarrId != null && episode.state != EpisodeState.Upcoming
    Surface(
        onClick = { if (canAct) panel = true },
        modifier = cardModifier
            .then(modifier)
            .aspectRatio(16f / 9f)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(containerColor = palette.raised, focusedContainerColor = palette.raised),
    ) {
        Box(Modifier.fillMaxSize()) {
            val image = episode.still ?: fallbackImage
            if (image != null) {
                RemoteImage(
                    image,
                    // No still yet: the show's artwork, blurred, so it reads as "not here yet".
                    if (episode.still == null || prefs.blurUnwatched) Modifier.fillMaxSize().blur(6.dp) else Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x66000000), 1f to Color(0xE6000000))
                )
            )
            episode.stateLabel()?.let { text ->
                Text(
                    text,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(episode.state.colour())
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    label.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    color = Color(0xCCFFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    episode.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (episode.inQueue && episode.progress != null) ProgressStrip(episode.progress, episode.state.colour(), Modifier.align(Alignment.BottomStart))
        }
    }
    if (panel) EpisodeActionsPanel(episode, onClose = { panel = false }, onChanged = onChanged)
}

/** A thin bar along the bottom edge: how much has downloaded. */
@Composable
internal fun ProgressStrip(progress: Float, colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x55000000))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(colour))
    }
}

/** What Sonarr can do about an episode you don't have: search again, or start getting it. */
@Composable
private fun EpisodeActionsPanel(episode: UpcomingEpisode, onClose: () -> Unit, onChanged: () -> Unit) {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val id = episode.sonarrId ?: return

    fun act(monitorFirst: Boolean) {
        val sonarr = settings.arr(ArrKind.Show) ?: return
        if (busy) return
        busy = true
        status = if (monitorFirst) "Monitoring and searching…" else "Asking Sonarr to search…"
        scope.launch {
            status = try {
                if (monitorFirst) sonarr.monitorEpisodes(listOf(id))
                sonarr.searchEpisodes(listOf(id))
                onChanged()
                "Sonarr is searching now. Its Activity page shows what it finds."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't do that: ${e.message ?: "no answer from Sonarr"}"
            } finally {
                busy = false
            }
        }
    }

    SidePanel(title = "Season ${episode.season} · Episode ${episode.number}", onClose = onClose) { first ->
        Text(episode.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(episode.whenText(), episode.stateLabel()).joinToString("  ·  "),
            fontSize = 13.sp,
            color = episode.state.colour(),
        )
        episode.statusText?.takeIf { episode.state == EpisodeState.Problem }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = palette.muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        when (episode.state) {
            EpisodeState.Unmonitored -> PickerRow("Monitor and search", false, first) { act(monitorFirst = true) }
            EpisodeState.Missing, EpisodeState.Problem -> PickerRow("Search again", false, first) { act(monitorFirst = false) }
            else -> PickerRow("Close", false, first) { onClose() }
        }
        if (episode.inQueue) {
            Spacer(Modifier.height(8.dp))
            Text("It's in Sonarr's download queue and will appear here once it's done.", fontSize = 13.sp, lineHeight = 17.sp, color = palette.muted)
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, lineHeight = 17.sp, color = palette.text)
        }
    }
}
