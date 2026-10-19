package com.example.plex_tv_app.tmdb

import com.example.plex_tv_app.arr.ArrKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A movie or show to discover, from TMDB or Plex's discovery service. */
data class TmdbTitle(
    val kind: ArrKind,
    val id: Int, // TMDB id, 0 if unknown
    val title: String,
    val year: Int?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val rating: Float?, // 0-10
    val plexId: String? = null, // Plex's own id (the end of its plex:// guid), from Plex discovery
    val tvdb: Int? = null,
) {
    /** The keys PlexServerApi.libraryByExternalId() could know this title by. */
    fun libraryKeys(): List<String> {
        val k = if (kind == ArrKind.Movie) "movie" else "show"
        return listOfNotNull(
            id.takeIf { it > 0 }?.let { "tmdb:$k:$it" },
            plexId?.let { "plex:$k:$it" },
            tvdb?.let { "tvdb:$k:$it" },
            com.example.plex_tv_app.arr.titleKey(k, title, year),
        )
    }
}

/** A row on Discover: its name and how to load it. */
class TmdbRow(val key: String, val title: String, val kind: ArrKind?, val load: suspend (TmdbClient, Int) -> List<TmdbTitle>)

class TmdbException(message: String) : Exception(message)

/**
 * TMDB (themoviedb.org), for browsing what's out there: trending, popular, genres and
 * recommendations. Takes either kind of key from TMDB's API settings page: the short
 * "API key" or the long "API Read Access Token".
 */
class TmdbClient(private val key: String) {
    private val bearer = key.length > 40

    private suspend fun get(path: String, params: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val sep = if ('?' in path) "&" else "?"
        val auth = if (bearer) "" else "&api_key=$key"
        val url = "https://api.themoviedb.org/3$path${sep}language=en-US$auth$params"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer) conn.setRequestProperty("Authorization", "Bearer $key")
            when (val status = conn.responseCode) {
                401 -> throw TmdbException("TMDB didn't accept that key.")
                !in 200..299 -> throw TmdbException("TMDB returned $status.")
                else -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun path(kind: ArrKind) = if (kind == ArrKind.Movie) "movie" else "tv"

    private fun list(o: JSONObject, kind: ArrKind?): List<TmdbTitle> {
        val a = o.optJSONArray("results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { r ->
            val k = kind ?: when (r.optString("media_type")) {
                "movie" -> ArrKind.Movie
                "tv" -> ArrKind.Show
                else -> return@mapNotNull null
            }
            val movie = k == ArrKind.Movie
            val poster = r.optString("poster_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            TmdbTitle(
                kind = k,
                id = r.optInt("id"),
                title = r.optString(if (movie) "title" else "name"),
                year = r.optString(if (movie) "release_date" else "first_air_date").take(4).toIntOrNull(),
                overview = r.optString("overview").takeIf { it.isNotBlank() },
                poster = "https://image.tmdb.org/t/p/w342$poster",
                backdrop = r.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" },
                rating = r.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f },
            )
        }
    }

    /** Checks the key works. */
    suspend fun test() {
        get("/configuration")
    }

    private fun pg(page: Int) = if (page > 1) "&page=$page" else ""

    suspend fun trending(kind: ArrKind, page: Int = 1) = list(get("/trending/${path(kind)}/week", pg(page)), kind)
    suspend fun popular(kind: ArrKind, page: Int = 1) = list(get("/${path(kind)}/popular", pg(page)), kind)
    suspend fun topRated(kind: ArrKind, page: Int = 1) = list(get("/${path(kind)}/top_rated", pg(page)), kind)
    suspend fun upcomingMovies(page: Int = 1) = list(get("/movie/upcoming", pg(page)), ArrKind.Movie)
    suspend fun onTheAir(page: Int = 1) = list(get("/tv/on_the_air", pg(page)), ArrKind.Show)
    suspend fun genre(kind: ArrKind, genreId: Int, page: Int = 1) =
        list(get("/discover/${path(kind)}", "&sort_by=popularity.desc&vote_count.gte=150&with_genres=$genreId" + pg(page)), kind)

    /** When a movie reaches cinemas, digital stores and disc (the TV's region first). */
    suspend fun releaseDates(movieId: Int): Release = Release.fromTmdb(get("/movie/$movieId/release_dates"))

    /** Movies that came out to buy or rent in the last week, in the TV's region (US if there are too few). */
    suspend fun digitalThisWeek(page: Int = 1): List<TmdbTitle> {
        val today = java.time.LocalDate.now()
        suspend fun inRegion(region: String) = list(
            get(
                "/discover/movie",
                "&region=$region&with_release_type=4&sort_by=popularity.desc" +
                    "&release_date.gte=${today.minusDays(7)}&release_date.lte=$today" + pg(page),
            ),
            ArrKind.Movie,
        )
        val local = inRegion(deviceRegion())
        return if (local.size >= 6 || deviceRegion() == "US") local else (local + inRegion("US")).distinctBy { it.id }
    }

    /**
     * A show's recent and coming episodes: those still to air, plus any that aired in the last
     * 60 days, from [fromSeason] (the latest season you have) and the season airing next.
     */
    suspend fun recentAndUpcomingEpisodes(tvId: Int, fromSeason: Int?): List<UpcomingEpisode> {
        val show = get("/tv/$tvId")
        val next = show.optJSONObject("next_episode_to_air")?.optInt("season_number", -1)?.takeIf { it > 0 }
        val last = show.optJSONObject("last_episode_to_air")?.optInt("season_number", -1)?.takeIf { it > 0 }
        val seasons = listOfNotNull(fromSeason?.takeIf { it > 0 }, last, next).distinct().filter { s -> fromSeason == null || s >= fromSeason }
        if (seasons.isEmpty()) return emptyList()
        val today = java.time.LocalDate.now()
        return seasons.flatMap { season ->
            val eps = runCatching { get("/tv/$tvId/season/$season").optJSONArray("episodes") }.getOrNull()
            (0 until (eps?.length() ?: 0)).map { eps!!.getJSONObject(it) }.mapNotNull { e ->
                val air = e.optString("air_date").takeIf { it.length >= 10 }?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
                    ?: return@mapNotNull null
                val aired = !air.isAfter(today)
                if (aired && air.isBefore(today.minusDays(60))) return@mapNotNull null
                UpcomingEpisode(
                    season = e.optInt("season_number", season),
                    number = e.optInt("episode_number"),
                    title = e.optString("name").ifBlank { "Episode ${e.optInt("episode_number")}" },
                    overview = e.optString("overview").takeIf { it.isNotBlank() },
                    airDate = air,
                    still = e.optString("still_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w300$it" },
                    state = if (aired && air.isBefore(today)) EpisodeState.NotInLibrary else EpisodeState.Upcoming,
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    /** Movies and shows matching what you typed. */
    suspend fun search(query: String): List<TmdbTitle> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return list(get("/search/multi", "&include_adult=false&query=$q"), null) +
            list(get("/search/multi", "&include_adult=false&query=$q&page=2"), null)
    }

    /** Titles like this one: TMDB's recommendations, or "similar" if it has none. */
    suspend fun moreLike(kind: ArrKind, id: Int): List<TmdbTitle> =
        list(get("/${path(kind)}/$id/recommendations"), kind).ifEmpty { list(get("/${path(kind)}/$id/similar"), kind) }

    /** Finds a title by name, e.g. when the Plex item has no TMDB id. */
    suspend fun find(kind: ArrKind, title: String, year: Int?): TmdbTitle? {
        val q = URLEncoder.encode(title, "UTF-8")
        val y = year?.let { if (kind == ArrKind.Movie) "&year=$it" else "&first_air_date_year=$it" }.orEmpty()
        return list(get("/search/${path(kind)}", "&query=$q$y"), kind).firstOrNull()
    }

    /** Sonarr works with TVDB ids, so shows need theirs looked up. */
    suspend fun tvdbId(showId: Int): Int? = get("/tv/$showId/external_ids").optInt("tvdb_id").takeIf { it > 0 }

    companion object {
        private val m = ArrKind.Movie
        private val s = ArrKind.Show

        /** What Discover shows, top to bottom. */
        val DiscoverRows = listOf(
            TmdbRow("trending_movies", "Trending movies", m) { c, p -> c.trending(m, p) },
            TmdbRow("trending_shows", "Trending shows", s) { c, p -> c.trending(s, p) },
            TmdbRow("digital_week", "Out digitally this week", m) { c, p -> c.digitalThisWeek(p) },
            TmdbRow("upcoming", "Coming soon to cinemas", m) { c, p -> c.upcomingMovies(p) },
            TmdbRow("popular_movies", "Popular movies", m) { c, p -> c.popular(m, p) },
            TmdbRow("popular_shows", "Popular shows", s) { c, p -> c.popular(s, p) },
            TmdbRow("on_the_air", "New episodes this week", s) { c, p -> c.onTheAir(p) },
            TmdbRow("top_movies", "Top rated movies", m) { c, p -> c.topRated(m, p) },
            TmdbRow("top_shows", "Top rated shows", s) { c, p -> c.topRated(s, p) },
            TmdbRow("g_action", "Action movies", m) { c, p -> c.genre(m, 28, p) },
            TmdbRow("g_comedy", "Comedy movies", m) { c, p -> c.genre(m, 35, p) },
            TmdbRow("g_scifi", "Science fiction movies", m) { c, p -> c.genre(m, 878, p) },
            TmdbRow("g_thriller", "Thrillers", m) { c, p -> c.genre(m, 53, p) },
            TmdbRow("g_horror", "Horror movies", m) { c, p -> c.genre(m, 27, p) },
            TmdbRow("g_animation", "Animated movies", m) { c, p -> c.genre(m, 16, p) },
            TmdbRow("g_family", "Family movies", m) { c, p -> c.genre(m, 10751, p) },
            TmdbRow("g_doco", "Documentaries", m) { c, p -> c.genre(m, 99, p) },
            TmdbRow("g_tv_drama", "Drama series", s) { c, p -> c.genre(s, 18, p) },
            TmdbRow("g_tv_crime", "Crime series", s) { c, p -> c.genre(s, 80, p) },
            TmdbRow("g_tv_comedy", "Comedy series", s) { c, p -> c.genre(s, 35, p) },
            TmdbRow("g_tv_scifi", "Sci-fi and fantasy series", s) { c, p -> c.genre(s, 10765, p) },
            TmdbRow("g_tv_animation", "Animated series", s) { c, p -> c.genre(s, 16, p) },
            TmdbRow("g_tv_reality", "Reality TV", s) { c, p -> c.genre(s, 10764, p) },
        )
    }
}
