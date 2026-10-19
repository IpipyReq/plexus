package com.example.plex_tv_app.plex

import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.tmdb.TmdbTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A row from Plex's discovery service. */
class DiscoverHub(val key: String, val title: String, val items: List<TmdbTitle>, val moreUrl: String? = null)

/**
 * Plex's online discovery service (what the official apps' Discover uses), signed in with
 * your Plex account, so no extra key is needed. Plex doesn't document it and could change it,
 * so this is forgiving: it tries the addresses the Plex apps use and keeps what it understands.
 */
class PlexDiscover(private val client: PlexClient) {
    private val discover = "https://discover.provider.plex.tv"
    private val metadata = "https://metadata.provider.plex.tv"

    private suspend fun get(url: String): JSONObject {
        val token = client.activeToken ?: throw PlexException(401, "Not signed in to Plex")
        val body = client.request("GET", url, token, timeoutMs = 8_000)
        log("${url.substringBefore('?')}: ${body.length} chars")
        // Off the main thread: these replies can be large.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val o = JSONObject(body)
            o.optJSONObject("MediaContainer") ?: o
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun image(path: String?, host: String): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        val token = client.activeToken ?: return null
        return "$host$path${if ('?' in path) "&" else "?"}X-Plex-Token=$token"
    }

    /** Newer Plex replies list pictures as Image: [{type: "coverPoster", url: …}] instead of thumb/art. */
    private fun picture(o: JSONObject, vararg types: String): String? {
        val images = o.optJSONArray("Image").objects()
        return types.firstNotNullOfOrNull { t -> images.firstOrNull { it.optString("type") == t }?.optString("url")?.takeIf { it.isNotBlank() } }
    }

    /** One of Plex's online titles, as a Discover card. Not movies or shows → null. */
    private fun parse(o: JSONObject, host: String): TmdbTitle? {
        val kind = when (o.optString("type")) {
            "movie" -> ArrKind.Movie
            "show" -> ArrKind.Show
            else -> return null
        }
        val ids = o.optJSONArray("Guid").objects().associate { g ->
            val id = g.optString("id")
            id.substringBefore("://", "") to id.substringAfter("://")
        }
        val plexId = o.optString("guid").takeIf { it.startsWith("plex://") }?.substringAfterLast('/')
            ?: o.optString("ratingKey").takeIf { it.isNotBlank() }
        val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
        return TmdbTitle(
            kind = kind,
            id = ids["tmdb"]?.toIntOrNull() ?: 0,
            title = title,
            year = o.optInt("year").takeIf { it > 0 },
            overview = o.optString("summary").takeIf { it.isNotBlank() },
            poster = image(o.optString("thumb").ifBlank { picture(o, "coverPoster", "poster", "thumb") }, host) ?: return null,
            backdrop = image(o.optString("art").ifBlank { picture(o, "background", "art", "coverArt") }, host),
            rating = o.optDouble("audienceRating", 0.0).toFloat().takeIf { it > 0f },
            plexId = plexId,
            tvdb = ids["tvdb"]?.toIntOrNull(),
        )
    }

    private suspend fun hubs(mc: JSONObject, host: String): List<DiscoverHub> = kotlinx.coroutines.coroutineScope {
        mc.optJSONArray("Hub").objects().take(16).map { h ->
            // Rows that only say where their titles are get fetched, all at the same time.
            async { hub(h, host) }
        }.awaitAll().filterNotNull()
    }

    private suspend fun hub(h: JSONObject, host: String): DiscoverHub? {
        val title = h.optString("title").takeIf { it.isNotBlank() } ?: return null
        var items = h.optJSONArray("Metadata").objects().mapNotNull { parse(it, host) }
        val more = h.optString("hubKey").ifBlank { h.optString("key") }
        if (items.size < 6 && more.isNotBlank()) {
            val url = (if (more.startsWith("http")) more else host + more).let { it + (if ('?' in it) "&" else "?") + "count=30" }
            items = runCatching {
                get(url).optJSONArray("Metadata").objects().mapNotNull { parse(it, host) }
            }.getOrNull()?.takeIf { it.size > items.size } ?: items
        }
        items = items.distinctBy { it.plexId ?: it.title }
        if (items.size < 3) return null
        return DiscoverHub(
            h.optString("hubIdentifier").ifBlank { h.optString("key").ifBlank { title } },
            title,
            items,
            more.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else host + it },
        )
    }

    /** Every "hubKey" (and "/hubs…" key) in a provider's description of itself. */
    private fun hubKeys(o: Any?, into: MutableList<String>) {
        when (o) {
            is JSONObject -> o.keys().forEach { k ->
                val v = o.opt(k)
                if (v is String && (k == "hubKey" || (k == "key" && v.startsWith("/hubs")))) into += v
                else hubKeys(v, into)
            }
            is JSONArray -> (0 until o.length()).forEach { hubKeys(o.opt(it), into) }
        }
    }

    private fun log(message: String) = android.util.Log.i("PlexDiscover", message)

    /** Discover's rows: trending, popular and so on, whatever Plex is featuring. */
    suspend fun home(): List<DiscoverHub> {
        // Plex's Discover home first; stop as soon as one gives us rows.
        val tries = listOf(
            "$discover/hubs/sections/home",
            "$discover/hubs/promoted",
            "$discover/hubs",
        )
        val failures = mutableListOf<String>()
        for (url in tries) {
            val full = "$url?includeMeta=1&count=20"
            try {
                val rows = hubs(get(full), discover)
                log("$url → ${rows.size} rows: ${rows.joinToString { it.title }}")
                if (rows.size >= 2) return rows
                failures += "${url.removePrefix("https://")}: ${rows.size} rows"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$url failed: ${e.message}")
                failures += "${url.removePrefix("https://")}: ${(e as? PlexException)?.code ?: e.message}"
            }
        }
        throw PlexException(404, "tried ${failures.joinToString("; ")}")
    }

    private fun hostOf(url: String): String = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/")

    /** The rest of a row ("See more"). */
    suspend fun more(hub: DiscoverHub): List<TmdbTitle> {
        val url = hub.moreUrl ?: return hub.items
        val host = hostOf(url)
        val full = url + (if ('?' in url) "&" else "?") + "count=100"
        val found = get(full).optJSONArray("Metadata").objects().mapNotNull { parse(it, host) }
        return (hub.items + found).distinctBy { it.plexId ?: it.title }
    }

    /** Titles like this one, by Plex's own id for it (the end of its plex:// guid). */
    suspend fun moreLike(plexId: String): List<TmdbTitle> {
        val url = "$metadata/library/metadata/$plexId/related?includeMeta=1&count=30"
        val mc = try {
            get(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$url failed: ${e.message}")
            throw e
        }
        val fromHubs = hubs(mc, metadata).flatMap { it.items }
        val loose = mc.optJSONArray("Metadata").objects().mapNotNull { parse(it, metadata) }
        return (fromHubs + loose).filter { it.plexId != plexId }.distinctBy { it.plexId ?: it.title }.take(20)
    }

    /** TMDB/TVDB ids for one of Plex's online titles, so Radarr/Sonarr can find it. */
    suspend fun ids(plexId: String): Map<String, String> {
        val o = get("$metadata/library/metadata/$plexId?includeGuids=1").optJSONArray("Metadata").objects().firstOrNull()
            ?: return emptyMap()
        return o.optJSONArray("Guid").objects().associate { g ->
            val id = g.optString("id")
            id.substringBefore("://", "") to id.substringAfter("://")
        }
    }

    /** A search, for titles not found any other way. */
    suspend fun search(query: String): List<TmdbTitle> {
        val q = URLEncoder.encode(query, "UTF-8")
        val mc = get("$discover/library/search?query=$q&limit=20&searchTypes=movies,tv&searchProviders=discover&includeMetadata=1")
        return mc.optJSONArray("SearchResults").objects().flatMap { r ->
            r.optJSONArray("SearchResult").objects().mapNotNull { it.optJSONObject("Metadata")?.let { m -> parse(m, metadata) } }
        }
    }
}
