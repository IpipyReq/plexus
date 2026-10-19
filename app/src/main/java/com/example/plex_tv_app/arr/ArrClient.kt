package com.example.plex_tv_app.arr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "title:movie:theodyssey|2026": a loose match for titles with no shared id. */
fun titleKey(kind: String, title: String, year: Int?): String =
    "title:$kind:${title.lowercase().filter { it.isLetterOrDigit() }}|${year ?: ""}"

/** Which app a title belongs to. */
enum class ArrKind { Movie, Show }

/** A movie (from Radarr) or show (from Sonarr) found by a search. */
data class ArrTitle(
    val kind: ArrKind,
    val title: String,
    val year: Int?,
    val overview: String?,
    val poster: String?, // a small poster from TMDB/TVDB
    val added: Boolean, // already in Radarr/Sonarr
    val seasons: Int?, // shows: how many seasons
    internal val json: JSONObject, // the lookup result, sent back when adding
)

/** Something in Radarr/Sonarr's download queue. */
data class QueueItem(
    val kind: ArrKind,
    val id: Int,
    val title: String,
    val detail: String?, // "S2 · E6 · Episode name", or the movie's year
    val poster: String?,
    val state: com.example.plex_tv_app.tmdb.EpisodeState,
    val progress: Float?, // 0-1
    val statusText: String?, // "Queued", "Importing", a problem message…
    val timeLeft: String?,
    val sizeText: String?,
    val release: String?, // the release's name
)

/** A quality profile or root folder to choose from. */
data class ArrOption(val id: String, val label: String)

class ArrException(message: String) : Exception(message)

/**
 * Talks to Sonarr or Radarr on your network (their v3 API, with the API key from
 * Settings → General in each app). Only the server's owner sets these up.
 */
class ArrClient(val kind: ArrKind, baseUrl: String, private val apiKey: String) {
    companion object {
        /** Is Radarr/Sonarr answering at this address? (No API key needed.) */
        suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url.trimEnd('/') + "/ping").openConnection() as HttpURLConnection
                conn.connectTimeout = 1_500
                conn.readTimeout = 2_000
                try { conn.responseCode in 200..299 && conn.inputStream.bufferedReader().readText().contains("OK") } finally { conn.disconnect() }
            }.getOrDefault(false)
        }
    }

    private val base = baseUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 4_000): String =
        withContext(Dispatchers.IO) {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                // Never follow redirects: Android turns a redirected POST into a GET, which made a
                // failed add look like it worked.
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs + 8_000
                conn.setRequestProperty("X-Api-Key", apiKey)
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = conn.responseCode
                android.util.Log.i("Arr", "${appName()} $method $path → $status")
                if (status == 401) throw ArrException("The API key was refused.")
                if (status in 300..399) {
                    val to = conn.getHeaderField("Location").orEmpty()
                    throw ArrException("${appName()} redirected to $to. Check the address and port (does it use a URL base, like /${appName().lowercase()}?).")
                }
                if (status !in 200..299) {
                    val detail = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                    throw ArrException("${appName()} returned $status. ${detail.take(160)}")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

    private fun appName() = if (kind == ArrKind.Movie) "Radarr" else "Sonarr"

    /** Checks the address and key; returns the app's version. */
    suspend fun test(): String = JSONObject(call("GET", "/api/v3/system/status")).optString("version", "?")

    suspend fun qualityProfiles(): List<ArrOption> = JSONArray(call("GET", "/api/v3/qualityprofile")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { ArrOption(it.optInt("id").toString(), it.optString("name")) }
    }

    suspend fun rootFolders(): List<ArrOption> = JSONArray(call("GET", "/api/v3/rootfolder")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val path = o.optString("path")
            val free = o.optLong("freeSpace", -1).takeIf { it > 0 }?.let { "  ·  ${it / 1_073_741_824} GB free" } ?: ""
            ArrOption(path, path + free)
        }
    }

    /** Finds one movie by its TMDB id (for Discover). */
    suspend fun lookupTmdb(tmdbId: Int): ArrTitle? =
        parse(listOf(JSONObject(call("GET", "/api/v3/movie/lookup/tmdb?tmdbId=$tmdbId")))).firstOrNull()

    /** Finds one show by its TVDB id (for Discover). */
    suspend fun lookupTvdb(tvdbId: Int): ArrTitle? =
        JSONArray(call("GET", "/api/v3/series/lookup?term=tvdb:$tvdbId")).let { a ->
            parse((0 until a.length()).map { a.getJSONObject(it) }).firstOrNull()
        }

    /** Searches online (TMDB for movies, TVDB for shows) for titles to add. */
    suspend fun search(term: String): List<ArrTitle> {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
        val path = if (kind == ArrKind.Movie) "/api/v3/movie/lookup?term=$q" else "/api/v3/series/lookup?term=$q"
        val a = JSONArray(call("GET", path, timeoutMs = 3_000))
        return parse((0 until a.length()).map { a.getJSONObject(it) }.take(20))
    }

    private fun parse(list: List<JSONObject>): List<ArrTitle> = list.map { o ->
        val images = o.optJSONArray("images") ?: JSONArray()
        val poster = (0 until images.length()).map { images.getJSONObject(it) }
            .firstOrNull { it.optString("coverType") == "poster" }
            ?.let { it.optString("remoteUrl").ifBlank { it.optString("url") } }
            ?.takeIf { it.startsWith("http") }
            ?.let(::smallPoster)
        ArrTitle(
            kind = kind,
            title = o.optString("title"),
            year = o.optInt("year").takeIf { it > 0 },
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            poster = poster,
            added = o.optInt("id", 0) > 0,
            seasons = (o.optJSONArray("seasons"))?.let { s ->
                (0 until s.length()).count { s.getJSONObject(it).optInt("seasonNumber") > 0 }
            },
            json = o,
        )
    }

    /**
     * Adds a title and starts looking for it. [monitor] (shows only) is Sonarr's choice of which
     * episodes to get: "all", "future", "firstSeason", "latestSeason".
     */
    suspend fun add(title: ArrTitle, profileId: String, rootFolder: String, monitor: String = "all") {
        val body = JSONObject(title.json.toString()).apply {
            put("qualityProfileId", profileId.toInt())
            put("rootFolderPath", rootFolder)
            put("monitored", true)
            if (kind == ArrKind.Movie) {
                put("minimumAvailability", "released")
                put("addOptions", JSONObject().put("monitor", "movieOnly").put("searchForMovie", true))
            } else {
                put("seasonFolder", true)
                put("addOptions", JSONObject().put("monitor", monitor).put("searchForMissingEpisodes", true))
                // Sonarr v3 also needs a language profile (v4 doesn't have them).
                runCatching {
                    val langs = JSONArray(call("GET", "/api/v3/languageprofile"))
                    if (langs.length() > 0) put("languageProfileId", langs.getJSONObject(0).optInt("id"))
                }
            }
        }
        val reply = call("POST", if (kind == ArrKind.Movie) "/api/v3/movie" else "/api/v3/series", body, timeoutMs = 10_000)
        // Only call it added once Radarr/Sonarr confirms it: the reply must be the new entry, and it
        // must now be in their list.
        val created = runCatching { JSONObject(reply) }.getOrNull()
        if (created == null || created.optInt("id", 0) <= 0) {
            android.util.Log.w("Arr", "Add reply wasn't a new entry: ${reply.take(300)}")
            throw ArrException("${appName()} didn't confirm it was added. Its reply: ${reply.take(120)}")
        }
        if (!isTracked(title.copy(json = created))) {
            throw ArrException("${appName()} accepted it but doesn't list it. Check ${appName()}'s logs (System → Logs).")
        }
        // Don't rely on the add starting a search (not every version does): ask for one now.
        searchNow(created.optInt("id"))
    }

    /** Sonarr's id for a show, by its TVDB id (null if Sonarr doesn't track it). */
    suspend fun seriesId(tvdbId: Int): Int? {
        val series = JSONArray(call("GET", "/api/v3/series?tvdbId=$tvdbId"))
        return (0 until series.length()).map { series.getJSONObject(it) }.firstOrNull { it.optInt("tvdbId") == tvdbId }?.optInt("id")
    }

    /**
     * Sonarr: a show's episodes from [fromSeason] on that aren't downloaded: still to air,
     * downloading (from its queue), or aired but missing/unmonitored. Null if Sonarr doesn't track
     * the show.
     */
    suspend fun episodeStatuses(tvdbId: Int, fromSeason: Int): List<com.example.plex_tv_app.tmdb.UpcomingEpisode>? {
        val id = seriesId(tvdbId) ?: return null
        val eps = JSONArray(call("GET", "/api/v3/episode?seriesId=$id&includeImages=true", timeoutMs = 6_000))
        val queued = runCatching {
            JSONArray(call("GET", "/api/v3/queue/details?seriesId=$id&includeEpisode=false")).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.optInt("episodeId") }
            }
        }.getOrDefault(emptyMap())
        val now = java.time.Instant.now()
        val zone = java.time.ZoneId.systemDefault()
        return (0 until eps.length()).map { eps.getJSONObject(it) }.mapNotNull { e ->
            val season = e.optInt("seasonNumber")
            if (season < maxOf(fromSeason, 1) || e.optBoolean("hasFile")) return@mapNotNull null
            val airAt = e.optString("airDateUtc").takeIf { it.length >= 10 }?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val q = queued[e.optInt("id")]
            val (state, progress, text) = when {
                q != null -> queueState(q)
                airAt == null -> return@mapNotNull null // no date yet: nothing useful to show
                airAt.isAfter(now) -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Upcoming, null, null)
                !e.optBoolean("monitored") -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Unmonitored, null, null)
                else -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Missing, null, null)
            }
            val images = e.optJSONArray("images") ?: JSONArray()
            val still = (0 until images.length()).map { images.getJSONObject(it) }
                .firstOrNull { it.optString("coverType") == "screenshot" }?.optString("remoteUrl")?.takeIf { it.startsWith("http") }
            com.example.plex_tv_app.tmdb.UpcomingEpisode(
                season = season,
                number = e.optInt("episodeNumber"),
                title = e.optString("title").takeIf { it.isNotBlank() && it != "TBA" } ?: "Episode ${e.optInt("episodeNumber")}",
                overview = e.optString("overview").takeIf { it.isNotBlank() },
                airDate = airAt?.atZone(zone)?.toLocalDate(),
                still = still,
                state = state,
                progress = progress,
                statusText = text,
                sonarrId = e.optInt("id"),
            )
        }.sortedWith(compareBy({ it.season }, { it.number })).take(12)
    }

    /** What a queue entry is doing, its progress (0-1) and any problem message. */
    private fun queueState(q: JSONObject): Triple<com.example.plex_tv_app.tmdb.EpisodeState, Float?, String?> {
        val size = q.optDouble("size", 0.0)
        val left = q.optDouble("sizeleft", 0.0)
        val progress = if (size > 0) ((size - left) / size).toFloat().coerceIn(0f, 1f) else null
        val status = q.optString("status").lowercase()
        val tracked = q.optString("trackedDownloadState").lowercase()
        val warning = q.optString("trackedDownloadStatus").lowercase() == "warning" || status == "warning" ||
            status == "failed" || tracked.startsWith("failed")
        return when {
            warning -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Problem, progress, problemText(q))
            tracked == "importpending" || tracked == "importing" -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Queued, 1f, "Importing")
            status == "queued" || status == "delay" -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Queued, progress, "Queued")
            status == "paused" -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Queued, progress, "Paused")
            else -> Triple(com.example.plex_tv_app.tmdb.EpisodeState.Downloading, progress ?: 0f, null)
        }
    }

    private fun problemText(q: JSONObject): String? {
        val messages = q.optJSONArray("statusMessages") ?: JSONArray()
        val text = (0 until messages.length()).flatMap { i ->
            val m = messages.getJSONObject(i).optJSONArray("messages") ?: JSONArray()
            (0 until m.length()).map { m.optString(it) }
        }.filter { it.isNotBlank() }.joinToString(" ")
        return text.ifBlank { q.optString("errorMessage").takeIf { it.isNotBlank() } }
    }

    /** Radarr/Sonarr's download queue, for Discover. */
    suspend fun queue(): List<QueueItem> {
        val path = if (kind == ArrKind.Movie) "/api/v3/queue?pageSize=50&includeMovie=true"
        else "/api/v3/queue?pageSize=50&includeSeries=true&includeEpisode=true"
        val records = JSONObject(call("GET", path)).optJSONArray("records") ?: JSONArray()
        return (0 until records.length()).map { records.getJSONObject(it) }.map { q ->
            val owner = q.optJSONObject(if (kind == ArrKind.Movie) "movie" else "series") ?: JSONObject()
            val images = owner.optJSONArray("images") ?: JSONArray()
            val poster = (0 until images.length()).map { images.getJSONObject(it) }
                .firstOrNull { it.optString("coverType") == "poster" }
                ?.let { it.optString("remoteUrl").ifBlank { it.optString("url") } }
                ?.takeIf { it.startsWith("http") }?.let(::smallPoster)
            val ep = q.optJSONObject("episode")
            val (state, progress, text) = queueState(q)
            val size = q.optDouble("size", 0.0)
            QueueItem(
                kind = kind,
                id = q.optInt("id"),
                title = owner.optString("title").ifBlank { q.optString("title") },
                detail = if (ep != null) {
                    "S${ep.optInt("seasonNumber")} · E${ep.optInt("episodeNumber")}" +
                        (ep.optString("title").takeIf { it.isNotBlank() && it != "TBA" }?.let { " · $it" } ?: "")
                } else owner.optInt("year").takeIf { it > 0 }?.toString(),
                poster = poster,
                state = state,
                progress = progress,
                statusText = text,
                timeLeft = timeLeftText(q.optString("timeleft")),
                sizeText = if (size > 0) String.format(java.util.Locale.US, "%.1f GB", size / 1_073_741_824.0) else null,
                release = q.optString("title").takeIf { it.isNotBlank() },
            )
        }
    }

    /** "00:12:34" or "1.02:00:00" → "12 min left", "1 day left". */
    private fun timeLeftText(s: String): String? {
        if (s.isBlank()) return null
        val days = if ('.' in s.substringBefore(':')) s.substringBefore('.').toIntOrNull() ?: 0 else 0
        val hms = s.substringAfter('.', s).split(':').mapNotNull { it.toIntOrNull() }
        if (hms.size < 3) return null
        val (h, m) = hms[0] to hms[1]
        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""} left"
            h > 0 -> "${h}h ${m}m left"
            m > 0 -> "$m min left"
            else -> "Almost done"
        }
    }

    /** Sonarr: search for particular episodes now. */
    suspend fun searchEpisodes(ids: List<Int>) {
        call("POST", "/api/v3/command", JSONObject().put("name", "EpisodeSearch").put("episodeIds", JSONArray(ids)))
    }

    /** Sonarr: start (or stop) getting particular episodes. */
    suspend fun monitorEpisodes(ids: List<Int>, monitored: Boolean = true) {
        call("PUT", "/api/v3/episode/monitor", JSONObject().put("episodeIds", JSONArray(ids)).put("monitored", monitored))
    }

    /** Starts Radarr/Sonarr looking for a title it already tracks, by its id there. */
    suspend fun searchNow(id: Int) {
        val command = if (kind == ArrKind.Movie) {
            JSONObject().put("name", "MoviesSearch").put("movieIds", JSONArray().put(id))
        } else {
            JSONObject().put("name", "SeriesSearch").put("seriesId", id)
        }
        call("POST", "/api/v3/command", command)
    }

    /** Is this title already in Radarr/Sonarr's list? Checked by id, so it's exact. */
    suspend fun isTracked(title: ArrTitle): Boolean = trackedId(title) != null

    /** Its id in Radarr/Sonarr's list, or null if it isn't there. */
    suspend fun trackedId(title: ArrTitle): Int? {
        val o = title.json
        val (field, value, path) = if (kind == ArrKind.Movie) {
            Triple("tmdbId", o.optInt("tmdbId", 0), "/api/v3/movie?tmdbId=")
        } else {
            Triple("tvdbId", o.optInt("tvdbId", 0), "/api/v3/series?tvdbId=")
        }
        if (value <= 0) return null
        val a = JSONArray(call("GET", path + value))
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt(field) == value }?.optInt("id")
    }

    /**
     * Everything Radarr/Sonarr is tracking, as keys Discover can match ("tmdb:movie:603",
     * "tvdb:show:81189", "title:movie:theodyssey|2026"), so cards can say "Monitored".
     */
    suspend fun trackedKeys(): Set<String> {
        val body = call("GET", if (kind == ArrKind.Movie) "/api/v3/movie" else "/api/v3/series", timeoutMs = 8_000)
        return withContext(Dispatchers.Default) {
            val a = JSONArray(body)
            val k = if (kind == ArrKind.Movie) "movie" else "show"
            buildSet {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    o.optInt("tmdbId", 0).takeIf { it > 0 }?.let { add("tmdb:$k:$it") }
                    o.optInt("tvdbId", 0).takeIf { it > 0 }?.let { add("tvdb:$k:$it") }
                    val year = o.optInt("year", 0).takeIf { it > 0 }
                    add(titleKey(k, o.optString("title"), year))
                }
            }
        }
    }

    /** TMDB posters come full size; ask for a TV-sized one instead. */
    private fun smallPoster(url: String): String =
        url.replace("/t/p/original/", "/t/p/w342/")
}
