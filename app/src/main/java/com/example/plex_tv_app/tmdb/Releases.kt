package com.example.plex_tv_app.tmdb

import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.settings.AppSettings
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The TV's region (e.g. "AU"), for release dates. US when it isn't set. */
fun deviceRegion(): String = Locale.getDefault().country.uppercase().ifBlank { "US" }

private fun shortDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
private fun longDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
private fun date(s: String?): LocalDate? = s?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/** What a recent movie's corner tag shows. */
enum class ReleaseState { Digital, Cinemas, Soon }

/** When a movie reaches cinemas, digital stores and disc, in the TV's region where known. */
data class Release(val theatrical: LocalDate?, val digital: LocalDate?, val physical: LocalDate?) {
    val known get() = theatrical != null || digital != null || physical != null

    /** Out to buy or rent, so there's something to download. */
    fun isOut(today: LocalDate = LocalDate.now()): Boolean {
        val home = listOfNotNull(digital, physical).minOrNull()
        if (home != null) return !home.isAfter(today)
        // No home release date known: assume it's out once it's been in cinemas a good while.
        return theatrical?.plusDays(120)?.isBefore(today) ?: false
    }

    /** Out digitally, in cinemas now, or not out yet (null if nothing's known). */
    fun state(today: LocalDate = LocalDate.now()): ReleaseState? = when {
        !known -> null
        isOut(today) -> ReleaseState.Digital
        theatrical != null && !theatrical.isAfter(today) -> ReleaseState.Cinemas
        else -> ReleaseState.Soon
    }

    /** The short card tag: "In cinemas", "Digital 12 Nov", "Cinemas 5 Dec". Null once it's out. */
    fun tag(today: LocalDate = LocalDate.now()): String? = when {
        !known || isOut(today) -> null
        digital != null && theatrical != null && !theatrical.isAfter(today) -> "Digital ${shortDate(digital)}"
        theatrical != null && !theatrical.isAfter(today) -> "In cinemas"
        theatrical != null -> "Cinemas ${shortDate(theatrical)}"
        digital != null -> "Digital ${shortDate(digital)}"
        else -> null
    }

    /** The line in the add panel. [monitored]: Radarr already tracks it. */
    fun line(monitored: Boolean, today: LocalDate = LocalDate.now()): String? {
        if (!known) return null
        if (isOut(today)) {
            val home = listOfNotNull(digital, physical).minOrNull()
            return if (home != null) "Out digitally since ${longDate(home)}" else null
        }
        val parts = mutableListOf<String>()
        when {
            theatrical != null && !theatrical.isAfter(today) -> parts += "In cinemas now"
            theatrical != null -> parts += "In cinemas ${longDate(theatrical)}"
        }
        parts += if (digital != null) "Digital release expected ${longDate(digital)}" else "No digital release date yet"
        val after = digital ?: physical
        if (monitored) parts += if (after != null) "Radarr will grab it after ${shortDate(after)}" else "Radarr will grab it once it's out"
        return parts.joinToString("  ·  ")
    }

    companion object {
        /** From TMDB's /movie/{id}/release_dates: the TV's region first, US to fill gaps. */
        fun fromTmdb(o: JSONObject, region: String = deviceRegion()): Release {
            val results = o.optJSONArray("results")
            val byCountry = (0 until (results?.length() ?: 0)).associate { i ->
                val c = results!!.getJSONObject(i)
                val dates = c.optJSONArray("release_dates")
                c.optString("iso_3166_1") to (0 until (dates?.length() ?: 0)).mapNotNull { j ->
                    val d = dates!!.getJSONObject(j)
                    date(d.optString("release_date"))?.let { d.optInt("type") to it }
                }
            }
            fun pick(types: Set<Int>): LocalDate? {
                for (country in listOf(region, "US")) {
                    byCountry[country]?.filter { it.first in types }?.minOfOrNull { it.second }?.let { return it }
                }
                return null
            }
            return Release(theatrical = pick(setOf(3, 2)), digital = pick(setOf(4)), physical = pick(setOf(5)))
        }

        /** From a Radarr movie (its lookup or its list): inCinemas, digitalRelease, physicalRelease. */
        fun fromRadarr(o: JSONObject) = Release(
            theatrical = date(o.optString("inCinemas")),
            digital = date(o.optString("digitalRelease")),
            physical = date(o.optString("physicalRelease")),
        )
    }
}

/**
 * Release dates for Discover's movie cards, fetched as cards appear (a few at a time) and
 * remembered. Only recent movies are checked; anything older is long since out.
 */
object Releases {
    private val cache = ConcurrentHashMap<Int, Release>()
    private val gate = Semaphore(4)
    private val unknown = Release(null, null, null)

    fun worthChecking(t: TmdbTitle): Boolean =
        t.kind == ArrKind.Movie && t.id > 0 && (t.year == null || t.year >= LocalDate.now().year - 1)

    fun cached(t: TmdbTitle): Release? = cache[t.id]

    suspend fun get(t: TmdbTitle, settings: AppSettings): Release? {
        if (!worthChecking(t)) return null
        cache[t.id]?.let { return it.takeIf { r -> r.known } }
        val found = gate.withPermit {
            cache[t.id] ?: runCatching {
                val tmdb = settings.tmdb()
                when {
                    tmdb != null -> tmdb.releaseDates(t.id)
                    else -> settings.arr(ArrKind.Movie)?.lookupTmdb(t.id)?.let { Release.fromRadarr(it.json) }
                }
            }.getOrNull() ?: unknown
        }
        cache[t.id] = found
        return found.takeIf { it.known }
    }
}
