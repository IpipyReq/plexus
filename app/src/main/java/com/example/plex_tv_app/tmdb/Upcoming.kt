package com.example.plex_tv_app.tmdb

import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.plex.PlexDetails
import com.example.plex_tv_app.settings.AppSettings
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Where an episode you don't have yet stands. */
enum class EpisodeState {
    Upcoming, // hasn't aired
    Downloading, // in Sonarr's queue, coming down
    Queued, // in Sonarr's queue, waiting (or importing, paused)
    Problem, // in Sonarr's queue with a warning or failure
    Missing, // aired, Sonarr wants it but hasn't found it
    Unmonitored, // aired, Sonarr isn't set to get it
    NotInLibrary, // aired, not in Plex (no Sonarr to ask)
}

/** An episode that's coming, or out but not in Plex yet. */
data class UpcomingEpisode(
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String?,
    val airDate: LocalDate?,
    val still: String?, // a still from the episode, if there is one yet
    val state: EpisodeState = EpisodeState.Upcoming,
    val progress: Float? = null, // downloading: 0-1
    val statusText: String? = null, // queue detail: "Queued", "Importing", a problem message
    val sonarrId: Int? = null, // Sonarr's id for the episode, for Search again / Monitor
) {
    /** The date part of the top line: "Coming this Wednesday", "Aired 2 days ago". */
    fun whenText(today: LocalDate = LocalDate.now()): String {
        val date = airDate ?: return if (state == EpisodeState.Upcoming) "Coming soon" else "Aired"
        val days = ChronoUnit.DAYS.between(today, date)
        val short = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        return if (state == EpisodeState.Upcoming) when {
            days <= 0 -> "Coming today"
            days == 1L -> "Coming tomorrow"
            days < 7 -> "Coming this " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> "Coming $short"
        } else when {
            days >= 0 -> "Aired today"
            days == -1L -> "Aired yesterday"
            days > -7 -> "Aired ${-days} days ago"
            else -> "Aired $short"
        }
    }

    /** The small status tag on the card (null for episodes still to come). */
    fun stateLabel(): String? = when (state) {
        EpisodeState.Upcoming -> null
        EpisodeState.Downloading -> "Downloading ${((progress ?: 0f) * 100).toInt()}%"
        EpisodeState.Queued -> statusText ?: "Queued"
        EpisodeState.Problem -> "Download problem"
        EpisodeState.Missing -> "Not downloaded"
        EpisodeState.Unmonitored -> "Not monitored"
        EpisodeState.NotInLibrary -> "Not in library yet"
    }

    val inQueue get() = state == EpisodeState.Downloading || state == EpisodeState.Queued
}

/**
 * Episodes you don't have yet for a show: from Sonarr if you own the server and it tracks the
 * show (with download status), otherwise from TMDB. [fresh] skips the short cache (to update
 * download progress).
 */
object UpcomingEpisodes {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<UpcomingEpisode>>>()

    suspend fun forShow(d: PlexDetails, settings: AppSettings, lastSeason: Int?, owner: Boolean, fresh: Boolean = false): List<UpcomingEpisode> {
        val key = "${d.item.ratingKey}|$lastSeason|$owner"
        if (!fresh) cache[key]?.takeIf { System.currentTimeMillis() - it.first < 2 * 60_000 }?.let { return it.second }
        val found = runCatching {
            val sonarr = if (owner) settings.arr(ArrKind.Show) else null
            val tvdb = d.externalIds["tvdb"]?.toIntOrNull()
            val fromSonarr = if (sonarr != null && tvdb != null) {
                runCatching { sonarr.episodeStatuses(tvdb, lastSeason ?: 1) }.getOrNull()
            } else null
            fromSonarr ?: settings.tmdb()?.let { tmdb ->
                val id = d.externalIds["tmdb"]?.toIntOrNull() ?: tmdb.find(ArrKind.Show, d.item.title, d.item.year)?.id
                id?.let { tmdb.recentAndUpcomingEpisodes(it, lastSeason) }
            }
        }.getOrNull().orEmpty()
        cache[key] = System.currentTimeMillis() to found
        return found
    }
}
