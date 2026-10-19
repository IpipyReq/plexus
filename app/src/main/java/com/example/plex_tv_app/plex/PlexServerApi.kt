package com.example.plex_tv_app.plex

import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class PlexLibrary(val key: String, val title: String, val type: String)

data class PlexItem(
    val ratingKey: String,
    val title: String,
    val type: String, // "movie", "show", "season", "episode", ...
    val year: Int?,
    val thumb: String?,
    val index: Int?, // season or episode number
    val leafCount: Int?, // number of episodes (shows/seasons)
    val summary: String?,
    val art: String?, // wide background image
    val parentTitle: String?, // show name, for seasons
    val grandparentTitle: String?, // show name, for episodes
    val parentIndex: Int?, // season number, for episodes
    val viewOffset: Long?, // ms watched so far
    val duration: Long?, // ms
    val viewCount: Int?, // times watched (movies/episodes)
    val viewedLeafCount: Int?, // episodes watched (shows/seasons)
    val userRating: Float?, // your rating, 0-10
    val parentThumb: String?, // season poster (episodes) or show poster (seasons)
    val grandparentThumb: String?, // show poster, for episodes
    val contentRating: String?, // e.g. "PG-13", "TV-MA"
    val releaseDate: String?, // "yyyy-MM-dd"
    val audienceRating: Float?, // 0-10
    val genres: List<String>,
    val clearLogo: String?, // transparent title artwork, if the server has one
    val parentRatingKey: String?, // the season (episodes) or show (seasons)
    val grandparentRatingKey: String?, // the show, for episodes
    val titleSort: String?, // how Plex sorts the title (e.g. without "The")
    val addedAt: Long?, // when it was added to the library (seconds)
    val subtype: String? = null, // collections: what they hold, "movie" or "show"
) {
    /** Fully watched: a watched movie/episode, or a show/season with every episode watched. */
    val isWatched: Boolean
        get() = when (type) {
            "show", "season" -> (leafCount ?: 0) > 0 && viewedLeafCount == leafCount
            else -> (viewCount ?: 0) > 0
        }

    /** 0..1 how far through this item you are, or null if not started. */
    val progress: Float?
        get() {
            val offset = viewOffset ?: return null
            val total = duration ?: return null
            return if (offset > 0 && total > 0) (offset.toFloat() / total).coerceIn(0f, 1f) else null
        }
}

/** A row on the Home screen, e.g. "Continue Watching" or "Recently Added Movies". */
data class PlexHub(val id: String, val title: String, val items: List<PlexItem>) {
    /** Identifies this row when saving your custom row order. */
    val rowKey: String get() = "$id|$title"
}

/** One version of a movie/episode (most have one; some have e.g. 4K and 1080p). */
data class MediaVersion(
    val resolution: String?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val frameRate: String?,
    val audioCodec: String?,
    val audioChannels: Int?,
    val container: String?,
    val bitrateKbps: Int?,
    val files: List<MediaFile>,
)

data class MediaFile(val path: String?, val sizeBytes: Long?)

/** A published review (Plex gets these from Rotten Tomatoes). */
data class Review(
    val author: String,
    val text: String,
    val source: String?, // publication, e.g. "The Guardian"
    val fresh: Boolean?, // true = fresh, false = rotten, null = unknown
)

/** A collection in one of the libraries, e.g. "Spider-Man Collection" in Movies. */
data class PlexCollection(
    val ratingKey: String,
    val title: String,
    val thumb: String?,
    val count: Int?,
    val isTv: Boolean, // from a TV library (otherwise movies)
    val libraryTitle: String,
)

/** A skippable part of a video: the intro or the credits, in milliseconds. */
data class Marker(val type: String, val startMs: Long, val endMs: Long)

/** What the player needs to know about a movie or episode before it starts. */
data class PlaybackInfo(
    val partKey: String?, // the file on the server, e.g. "/library/parts/123/1690000/file.mkv"
    val durationMs: Long?,
    val markers: List<Marker>,
    val partId: Long? = null,
    val subtitles: List<SubtitleStream> = emptyList(),
    val audioLanguage: String? = null, // language tag of the audio track that will play, e.g. "ja"
    val audios: List<AudioStream> = emptyList(),
    val hasPreviews: Boolean = false, // the server has made preview thumbnails for this file
)

/** An audio track of a movie/episode, e.g. "English (AC3 5.1)". */
data class AudioStream(
    val id: Long,
    val title: String,
    val languageTag: String?,
    val languageCode: String?,
    val selected: Boolean, // the one the server plays by default
    val ordinal: Int, // nth audio track in the file (0-based), for matching the player's tracks
)

/**
 * A subtitle track of a movie/episode: either inside the video file ([key] null) or a separate
 * file next to it on the server ([key] = where to download it).
 */
data class SubtitleStream(
    val id: Long,
    val title: String, // e.g. "English (SRT External)"
    val languageTag: String?, // e.g. "en", "pt-BR"
    val languageCode: String?, // e.g. "eng"
    val codec: String?, // e.g. "srt", "ass", "pgs"
    val key: String?,
    val forced: Boolean,
    val index: Int?, // position in the file, for matching the player's tracks
    val embeddedOrdinal: Int, // nth subtitle inside the file (0-based); -1 for external files
) {
    val external: Boolean get() = key != null
    val isImage: Boolean get() = codec?.lowercase() in setOf("pgs", "hdmv_pgs_subtitle", "vobsub", "dvd_subtitle", "dvb_subtitle")

    /** True when this is in [code] (a 2-letter code like "en", or a 3-letter one like "eng"). */
    fun isIn(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val want = code.lowercase().substringBefore('-')
        val tag = languageTag?.lowercase()?.substringBefore('-')
        if (tag != null && tag == want) return true
        val three = languageCode?.lowercase() ?: return false
        if (three == want) return true
        val wantThree = runCatching { java.util.Locale(want).isO3Language }.getOrNull()
        return three == wantThree || (three == "ger" && want == "de") || (three == "fre" && want == "fr") ||
            (three == "chi" && want == "zh") || (three == "dut" && want == "nl")
    }
}

/** A category (genre) in a library, and the path that lists its titles. */
data class PlexCategory(val title: String, val path: String)

/** Someone in the cast, with their character. */
data class CastMember(val name: String, val role: String?, val thumb: String?, val id: String? = null)

/** Everything the details screen shows about a movie or show. */
data class PlexDetails(
    val item: PlexItem,
    val tagline: String?,
    val studio: String?,
    val directors: List<String>,
    val writers: List<String>,
    val cast: List<CastMember>,
    val seasonCount: Int?,
    val criticRating: Float?, // 0-10
    val criticSource: String?, // e.g. "rottentomatoes://image.rating.ripe", "imdb://image.rating"
    val audienceSource: String?, // e.g. "rottentomatoes://image.rating.upright"
    val collections: List<String>, // collection names this title belongs to
    val reviews: List<Review>,
    val externalIds: Map<String, String> = emptyMap(), // "tmdb" → "603", "tvdb" → ..., "imdb" → ...
)

/** Everything that talks to one Plex Media Server, once we've found an address that works. */
private const val ACTOR_PREFIX = "actor:"

class PlexServerApi(
    private val client: PlexClient,
    val name: String,
    val baseUrl: String,
    private val token: String,
    val isLocal: Boolean = false, // connected over the home network (picks Home vs Remote quality)
    val canManage: Boolean = false, // this profile owns the server, so it can e.g. update metadata
) {
    // Remembers lists we've already loaded, so going Back is instant.
    private val cache = mutableMapOf<String, List<PlexItem>>()

    // Title logos by movie/show ratingKey (null = that title has no logo).
    private val logoCache = mutableMapOf<String, String?>()

    /**
     * The transparent title artwork ("clear logo") for a movie or show, as a PNG URL.
     * Episodes and seasons use their show's logo. Null when there isn't one.
     */
    suspend fun logoUrlFor(item: PlexItem): String? {
        val path = logoPathFor(item) ?: return null
        val encoded = URLEncoder.encode(path, "UTF-8")
        return "$baseUrl/photo/:/transcode?width=800&height=320&minSize=0&upscale=0&format=png" +
            "&url=$encoded&X-Plex-Token=$token"
    }

    private suspend fun logoPathFor(item: PlexItem): String? {
        if (item.type == "movie" || item.type == "show") item.clearLogo?.let { return it }
        val key = when (item.type) {
            "episode" -> item.grandparentRatingKey
            "season" -> item.parentRatingKey
            else -> item.ratingKey
        } ?: return null
        if (logoCache.containsKey(key)) return logoCache[key]
        return try {
            val meta = container(get("/library/metadata/$key")).optJSONArray("Metadata")?.optJSONObject(0)
            (meta?.let(::clearLogoOf)).also { logoCache[key] = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null // try again next time
        }
    }

    suspend fun libraries(): List<PlexLibrary> {
        val dirs = container(get("/library/sections")).optJSONArray("Directory") ?: JSONArray()
        return dirs.objects().map {
            PlexLibrary(key = it.optString("key"), title = it.optString("title"), type = it.optString("type"))
        }
    }

    /** The rows Plex recommends for the Home screen, keeping only ones with movies/TV in them. */
    suspend fun homeHubs(): List<PlexHub> {
        val hubs = container(get("/hubs?count=20")).optJSONArray("Hub") ?: JSONArray()
        return hubs.objects().mapNotNull { hub ->
            val items = (hub.optJSONArray("Metadata") ?: JSONArray()).objects()
                .map(::parseItem)
                .filter { it.type in VIDEO_TYPES }
                .distinctBy { it.ratingKey }
            val id = hub.optString("hubIdentifier")
            when {
                items.isEmpty() -> null
                // Plex's newer "Continue Watching" row mostly duplicates On Deck, so we drop it...
                id.contains("continue", ignoreCase = true) -> null
                // ...and show On Deck under the friendlier name instead.
                id.contains("ondeck", ignoreCase = true) -> PlexHub(id, "Continue Watching", items)
                else -> PlexHub(id, hub.optString("title"), items)
            }
        }
    }

    /** Full details for a movie or show (cast, crew, tagline...). */
    suspend fun details(ratingKey: String): PlexDetails {
        val o = container(get("/library/metadata/$ratingKey?includeReviews=1&includeGuids=1")).optJSONArray("Metadata")?.optJSONObject(0)
            ?: throw PlexException(404, "That title isn't on the server any more.")
        fun tags(name: String) = (o.optJSONArray(name) ?: JSONArray()).objects().mapNotNull { it.stringOrNull("tag") }
        return PlexDetails(
            item = parseItem(o),
            tagline = o.stringOrNull("tagline"),
            studio = o.stringOrNull("studio"),
            directors = tags("Director"),
            writers = tags("Writer"),
            cast = (o.optJSONArray("Role") ?: JSONArray()).objects().mapNotNull { r ->
                val name = r.stringOrNull("tag") ?: return@mapNotNull null
                CastMember(name, r.stringOrNull("role"), r.stringOrNull("thumb"), r.stringOrNull("id"))
            },
            seasonCount = o.intOrNull("childCount"),
            criticRating = o.floatOrNull("rating"),
            criticSource = o.stringOrNull("ratingImage"),
            audienceSource = o.stringOrNull("audienceRatingImage"),
            collections = tags("Collection"),
            reviews = (o.optJSONArray("Review") ?: JSONArray()).objects().mapNotNull { r ->
                val text = r.stringOrNull("text") ?: return@mapNotNull null
                val image = r.stringOrNull("image").orEmpty()
                Review(
                    author = r.stringOrNull("tag") ?: "Critic",
                    text = text,
                    source = r.stringOrNull("source"),
                    fresh = when {
                        image.endsWith("fresh") -> true
                        image.endsWith("rotten") -> false
                        else -> null
                    },
                )
            },
            externalIds = externalIds(o),
        )
    }

    private fun externalIds(o: JSONObject): Map<String, String> {
        val ids = (o.optJSONArray("Guid") ?: JSONArray()).objects().mapNotNull { g ->
            val id = g.optString("id")
            val scheme = id.substringBefore("://", "")
            if (scheme.isBlank()) null else scheme to id.substringAfter("://")
        }.toMap()
        // Plex's own id ("plex://movie/5d776…"), which its discovery service uses.
        val plex = o.optString("guid").takeIf { it.startsWith("plex://") }?.substringAfterLast('/')
        return if (plex != null) ids + ("plex" to plex) else ids
    }

    // Everything in the movie and TV libraries by TMDB/TVDB id, so Discover can tell what you
    // already have. Built on first use and kept for 10 minutes.
    private var guidIndex: Map<String, PlexItem>? = null
    private var guidIndexAt = 0L
    private val guidLock = kotlinx.coroutines.sync.Mutex()

    /** Keys look like "tmdb:movie:603" or "tmdb:show:1399". */
    suspend fun libraryByExternalId(): Map<String, PlexItem> = guidLock.withLock {
        val now = System.currentTimeMillis()
        guidIndex?.takeIf { now - guidIndexAt < 30 * 60_000 }?.let { return@withLock it }
        val index = HashMap<String, PlexItem>()
        for (lib in libraries().filter { it.type == "movie" || it.type == "show" }) {
            val body = runCatching { get("/library/sections/${lib.key}/all?includeGuids=1") }.getOrNull() ?: continue
            // A whole library is a lot of JSON: read it off the main thread so the screen keeps moving.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val items = runCatching { container(body).optJSONArray("Metadata") }.getOrNull() ?: return@withContext
                items.objects().forEach { o ->
                    val item = parseItem(o)
                    val kind = if (item.type == "show") "show" else "movie"
                    externalIds(o).forEach { (scheme, id) -> index["$scheme:$kind:$id"] = item }
                }
            }
        }
        guidIndex = index
        guidIndexAt = now
        index
    }

    /** A library's Recommended rows (Plex's hubs for that library). */
    suspend fun libraryHubs(sectionKey: String): List<PlexHub> {
        val hubs = container(get("/hubs/sections/$sectionKey?count=20")).optJSONArray("Hub") ?: JSONArray()
        return hubs.objects().mapNotNull { hub ->
            val items = (hub.optJSONArray("Metadata") ?: JSONArray()).objects()
                .map(::parseItem)
                .filter { it.type in VIDEO_TYPES || it.type == "collection" }
                .distinctBy { it.ratingKey }
            if (items.isEmpty()) null else PlexHub(hub.optString("hubIdentifier"), hub.optString("title"), items)
        }
    }

    /** The collections in one library. */
    suspend fun libraryCollections(library: PlexLibrary): List<PlexCollection> =
        (container(get("/library/sections/${library.key}/collections")).optJSONArray("Metadata") ?: JSONArray())
            .objects()
            .map { c ->
                PlexCollection(
                    ratingKey = c.optString("ratingKey"),
                    title = c.optString("title"),
                    thumb = c.stringOrNull("thumb"),
                    count = c.intOrNull("childCount"),
                    isTv = library.type == "show",
                    libraryTitle = library.title,
                )
            }
            .sortedBy { it.title.lowercase().removePrefix("the ") }

    /** A library's categories (genres), each with the path listing its titles. */
    suspend fun libraryCategories(sectionKey: String): List<PlexCategory> =
        (container(get("/library/sections/$sectionKey/genre")).optJSONArray("Directory") ?: JSONArray())
            .objects()
            .mapNotNull { g ->
                val title = g.stringOrNull("title") ?: return@mapNotNull null
                val path = g.stringOrNull("fastKey")
                    ?: g.stringOrNull("key")?.let { "/library/sections/$sectionKey/all?genre=$it" }
                    ?: return@mapNotNull null
                PlexCategory(title, path)
            }

    /** Every collection in every movie and TV library, sorted by name. */
    suspend fun allCollections(): List<PlexCollection> {
        val result = mutableListOf<PlexCollection>()
        for (library in libraries().filter { it.type == "movie" || it.type == "show" }) {
            val list = runCatching {
                (container(get("/library/sections/${library.key}/collections")).optJSONArray("Metadata") ?: JSONArray()).objects()
            }.getOrDefault(emptyList())
            list.forEach { c ->
                result += PlexCollection(
                    ratingKey = c.optString("ratingKey"),
                    title = c.optString("title"),
                    thumb = c.stringOrNull("thumb"),
                    count = c.intOrNull("childCount"),
                    isTv = library.type == "show",
                    libraryTitle = library.title,
                )
            }
        }
        return result.sortedBy { it.title.lowercase().removePrefix("the ") }
    }

    // Collections in each library section: (title, ratingKey). Loaded once per section.
    private val sectionCollections = mutableMapOf<String, List<Pair<String, String>>>()

    /**
     * Everything in collections called [name], across all movie and TV libraries, as one row.
     * (Plex keeps a movie library's "Spider-Man Collection" and a TV library's one separately.)
     */
    /** Everything in every collection called [name], across all movie and TV libraries. */
    suspend fun collectionItemsByName(name: String): List<PlexItem> {
        val items = mutableListOf<PlexItem>()
        for (library in libraries().filter { it.type == "movie" || it.type == "show" }) {
            val collections = sectionCollections.getOrPut(library.key) {
                runCatching {
                    (container(get("/library/sections/${library.key}/collections")).optJSONArray("Metadata") ?: JSONArray())
                        .objects().map { it.optString("title") to it.optString("ratingKey") }
                }.getOrDefault(emptyList())
            }
            for ((title, key) in collections) {
                if (!title.equals(name, ignoreCase = true)) continue
                items += (container(get("/library/collections/$key/children")).optJSONArray("Metadata") ?: JSONArray())
                    .objects().map(::parseItem)
            }
        }
        return items.filter { it.type in VIDEO_TYPES }.distinctBy { it.ratingKey }
    }

    suspend fun collectionRows(name: String, excluding: String, split: Boolean): List<PlexHub> {
        val unique = collectionItemsByName(name).filter { it.ratingKey != excluding }
        if (unique.isEmpty()) return emptyList()
        if (!split) return listOf(PlexHub("collection|$name", "More in $name", unique))
        // Split: Plex-style "More Movies in X" and "More TV Shows in X".
        val movies = unique.filter { it.type == "movie" }
        val shows = unique.filter { it.type != "movie" }
        return listOfNotNull(
            movies.takeIf { it.isNotEmpty() }?.let { PlexHub("collection|movies|$name", "More Movies in $name", it) },
            shows.takeIf { it.isNotEmpty() }?.let { PlexHub("collection|shows|$name", "More TV Shows in $name", it) },
        )
    }

    /**
     * The rows under a title's details: similar titles and its collections. Plex splits a
     * collection into "Movies in X" and "TV Shows in X"; those are merged into one "More in X" row.
     */
    suspend fun related(ratingKey: String, collections: List<String> = emptyList(), splitCollections: Boolean = false): List<PlexHub> {
        // Collections first, built from the libraries (see collectionRows), then Plex's suggestions
        // (minus any of Plex's own rows for the same collections).
        val collectionRows = collections.flatMap { name ->
            runCatching { collectionRows(name, excluding = ratingKey, split = splitCollections) }.getOrDefault(emptyList())
        }
        val suggestions = suggestionRows(ratingKey)
            .filterNot { row -> collections.any { name -> row.title.contains(name, ignoreCase = true) } }
        return collectionRows + suggestions
    }

    private suspend fun suggestionRows(ratingKey: String): List<PlexHub> {
        val hubs = container(get("/library/metadata/$ratingKey/related?count=24")).optJSONArray("Hub") ?: JSONArray()
        val rows = hubs.objects().mapNotNull { hub ->
            val items = (hub.optJSONArray("Metadata") ?: JSONArray()).objects()
                .map(::parseItem)
                .filter { it.type in VIDEO_TYPES && it.ratingKey != ratingKey }
                .distinctBy { it.ratingKey }
            if (items.isEmpty()) null else PlexHub(hub.optString("hubIdentifier"), hub.optString("title"), items)
        }
        val inCollection = Regex("^(?:more|movies|tv shows|shows|films)?\\s*in\\s+(.+)$", RegexOption.IGNORE_CASE)
        val merged = LinkedHashMap<String, PlexHub>()
        for (row in rows) {
            val name = inCollection.find(row.title.trim())?.groupValues?.get(1)?.trim()
            val key = name?.lowercase() ?: row.rowKey
            val existing = merged[key]
            merged[key] = when {
                existing != null -> existing.copy(items = (existing.items + row.items).distinctBy { it.ratingKey })
                name != null -> row.copy(title = "More in $name")
                else -> row
            }
        }
        return merged.values.toList()
    }

    /**
     * Searches every library. Results come back grouped the way Plex groups them
     * (Movies, TV Shows, Episodes, Collections...), most relevant first.
     */
    suspend fun search(query: String): List<PlexHub> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val hubs = container(get("/hubs/search?query=$q&limit=20&includeCollections=1")).optJSONArray("Hub") ?: JSONArray()
        return hubs.objects().mapNotNull { hub ->
            val items = (hub.optJSONArray("Metadata") ?: JSONArray()).objects()
                .map(::parseItem)
                .filter { it.type in VIDEO_TYPES || it.type == "collection" }
                .distinctBy { it.ratingKey }
            if (items.isEmpty()) return@mapNotNull null
            val title = when (hub.optString("type")) {
                "movie" -> "Movies"
                "show" -> "TV Shows"
                "season" -> "Seasons"
                "episode" -> "Episodes"
                "collection" -> "Collections"
                else -> hub.optString("title")
            }
            PlexHub("search|" + hub.optString("hubIdentifier"), title, items)
        }
    }

    // ---------- Playback ----------

    /** The file, length and intro/credits markers for a movie or episode. */
    suspend fun playbackInfo(item: PlexItem): PlaybackInfo {
        val meta = container(get("/library/metadata/${item.ratingKey}?includeMarkers=1"))
            .optJSONArray("Metadata")?.optJSONObject(0) ?: return PlaybackInfo(null, item.duration, emptyList())
        val part = meta.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
        val markers = (meta.optJSONArray("Marker") ?: JSONArray()).objects().mapNotNull { m ->
            val start = m.longOrNull("startTimeOffset") ?: return@mapNotNull null
            val end = m.longOrNull("endTimeOffset") ?: return@mapNotNull null
            Marker(m.optString("type"), start, end)
        }
        val streams = (part?.optJSONArray("Stream") ?: JSONArray()).objects()
        var ordinal = 0
        val subtitles = streams.filter { it.optInt("streamType") == 3 }.mapNotNull { st ->
            val id = st.longOrNull("id") ?: return@mapNotNull null
            val key = st.stringOrNull("key")
            val forced = st.opt("forced").let { it == true || it == 1 || it == "1" || it == "true" }
            val title = st.stringOrNull("extendedDisplayTitle") ?: st.stringOrNull("displayTitle")
                ?: st.stringOrNull("language") ?: "Unknown"
            SubtitleStream(
                id = id,
                title = if (forced && !title.contains("forced", ignoreCase = true)) "$title (Forced)" else title,
                languageTag = st.stringOrNull("languageTag"),
                languageCode = st.stringOrNull("languageCode"),
                codec = st.stringOrNull("codec"),
                key = key,
                forced = forced,
                index = st.intOrNull("index"),
                embeddedOrdinal = if (key == null) ordinal++ else -1,
            )
        }
        val audios = streams.filter { it.optInt("streamType") == 2 }
        val audio = audios.firstOrNull { it.opt("selected") == true } ?: audios.firstOrNull()
        val audioTracks = audios.mapIndexedNotNull { i, st ->
            AudioStream(
                id = st.longOrNull("id") ?: return@mapIndexedNotNull null,
                title = st.stringOrNull("extendedDisplayTitle") ?: st.stringOrNull("displayTitle")
                    ?: st.stringOrNull("language") ?: "Track ${i + 1}",
                languageTag = st.stringOrNull("languageTag"),
                languageCode = st.stringOrNull("languageCode"),
                selected = st === audio,
                ordinal = i,
            )
        }
        return PlaybackInfo(
            partKey = part?.stringOrNull("key"),
            durationMs = meta.longOrNull("duration") ?: item.duration,
            markers = markers,
            partId = part?.longOrNull("id"),
            hasPreviews = part?.stringOrNull("indexes")?.contains("sd") == true,
            subtitles = subtitles,
            audioLanguage = audio?.stringOrNull("languageTag") ?: audio?.stringOrNull("languageCode"),
            audios = audioTracks,
        )
    }

    // ---------- Preview thumbnails (server settings; owner only) ----------

    private fun JSONObject.settingIsOn(): Boolean = opt("value").let { it == true || it == 1 || it == "1" || it == "true" }

    private suspend fun settings(path: String): List<JSONObject> =
        (container(get(path)).optJSONArray("Setting") ?: JSONArray()).objects()

    /** Each movie/TV library, and whether the server makes preview thumbnails for it. Owner only. */
    suspend fun previewGeneration(): List<Pair<PlexLibrary, Boolean>> =
        libraries().filter { it.type == "movie" || it.type == "show" }.map { lib ->
            val on = runCatching {
                settings("/library/sections/${lib.key}/prefs").firstOrNull { it.optString("id") == "enableBIFGeneration" }?.settingIsOn()
            }.getOrNull() ?: false
            lib to on
        }

    /** Turns preview thumbnails on or off for one library. Owner only. */
    suspend fun setPreviewGeneration(libraryKey: String, on: Boolean) {
        client.request("PUT", "$baseUrl/library/sections/$libraryKey/prefs?enableBIFGeneration=${if (on) 1 else 0}", token)
    }

    /** When the server makes them: "never", "scheduled" (maintenance hours) or "asap". Owner only. */
    suspend fun previewSchedule(): String? =
        settings("/:/prefs").firstOrNull { it.optString("id") == "GenerateBIFBehavior" }?.stringOrNull("value")

    suspend fun setPreviewSchedule(value: String) {
        client.request("PUT", "$baseUrl/:/prefs?GenerateBIFBehavior=$value", token)
    }

    /**
     * Whether this server has preview thumbnails at all (for profiles that can't read its
     * settings): looks for them on the latest few movies and episodes.
     */
    suspend fun previewsAvailable(): Boolean = runCatching {
        libraries().filter { it.type == "movie" || it.type == "show" }.any { lib ->
            val type = if (lib.type == "movie") 1 else 4
            val meta = container(
                get("/library/sections/${lib.key}/all?type=$type&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=10")
            ).optJSONArray("Metadata") ?: JSONArray()
            meta.objects().any { m ->
                (m.optJSONArray("Media") ?: JSONArray()).objects().any { media ->
                    (media.optJSONArray("Part") ?: JSONArray()).objects().any { it.optString("indexes").contains("sd") }
                }
            }
        }
    }.getOrDefault(false)

    /** The file's preview thumbnails (see [PreviewThumbnails]), or null if there are none. */
    suspend fun previewThumbnails(partId: Long): PreviewThumbnails? = runCatching {
        PreviewThumbnails.parse(client.requestBytes("$baseUrl/library/parts/$partId/indexes/sd", token))
    }.getOrNull()

    /** Download address for a subtitle that's a separate file on the server. */
    fun subtitleUrl(key: String): String = "$baseUrl$key?X-Plex-Token=$token"

    /**
     * Tells the server which subtitle to use for this file (null = none). The server uses it when
     * converting the video, and remembers it, like the Plex apps do.
     */
    suspend fun selectSubtitle(partId: Long, streamId: Long?) {
        client.request("PUT", "$baseUrl/library/parts/$partId?subtitleStreamID=${streamId ?: 0}&allParts=1", token)
    }

    /** Tells the server which audio track to use for this file (used when it converts the video). */
    suspend fun selectAudio(partId: Long, streamId: Long) {
        client.request("PUT", "$baseUrl/library/parts/$partId?audioStreamID=$streamId&allParts=1", token)
    }

    /** The show's Continue Watching episode (what Plex's own apps play next), if there is one. */
    suspend fun onDeckFor(showKey: String): PlexItem? {
        val meta = container(get("/library/metadata/$showKey?includeOnDeck=1"))
            .optJSONArray("Metadata")?.optJSONObject(0) ?: return null
        val deck = meta.optJSONObject("OnDeck")?.let { o ->
            o.optJSONArray("Metadata")?.optJSONObject(0) ?: o.optJSONObject("Metadata")
        } ?: return null
        return parseItem(deck)
    }

    /** Plays the file exactly as it is (direct play). */
    fun directPlayUrl(partKey: String): String = "$baseUrl$partKey?X-Plex-Token=$token"

    /**
     * Has the server convert the video on the fly (HLS) when the TV can't play the file as-is,
     * or when a lower quality is wanted. [maxKbps] null = original quality.
     */
    fun transcodeUrl(
        item: PlexItem,
        session: String,
        maxKbps: Int?,
        allowDirectStream: Boolean,
        burnSubtitles: String,
        subtitleSize: Int = 100,
        autoAdjustQuality: Boolean = false, // let the server lower quality when the connection struggles
    ): String {
        val params = linkedMapOf(
            "hasMDE" to "1",
            "path" to "/library/metadata/${item.ratingKey}",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "hls",
            "fastSeek" to "1",
            "directPlay" to "0",
            "directStream" to if (allowDirectStream) "1" else "0",
            "directStreamAudio" to if (allowDirectStream) "1" else "0",
            "videoQuality" to "100",
            "maxVideoBitrate" to (maxKbps ?: 200_000).toString(),
            "subtitles" to burnSubtitles,
            "subtitleSize" to subtitleSize.toString(),
            "audioBoost" to "100",
            "location" to if (isLocal) "lan" else "wan",
            "autoAdjustQuality" to if (autoAdjustQuality) "1" else "0",
            "mediaBufferSize" to "102400",
            "session" to session,
            "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Identifier" to client.clientId,
            "X-Plex-Product" to PlexClient.PRODUCT_NAME,
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to "Google TV",
            "X-Plex-Token" to token,
        )
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "$baseUrl/video/:/transcode/universal/start.m3u8?$query"
    }

    /** Tells the server to stop converting (when the player closes). */
    suspend fun stopTranscode(session: String) {
        runCatching { get("/video/:/transcode/universal/stop?session=$session") }
    }

    /**
     * Reports playback progress, so Continue Watching, "x min left" and watched status stay in
     * sync. Plex marks a title watched by itself once you're near the end.
     */
    suspend fun reportTimeline(item: PlexItem, state: String, timeMs: Long, durationMs: Long) {
        runCatching {
            get(
                "/:/timeline?ratingKey=${item.ratingKey}&key=%2Flibrary%2Fmetadata%2F${item.ratingKey}" +
                    "&state=$state&time=$timeMs&duration=$durationMs&identifier=$LIBRARY_ID"
            )
        }
    }

    /** The episode after [episode]: the next in its season, else the first of the next season. */
    suspend fun nextEpisode(episode: PlexItem): PlexItem? {
        val seasonKey = episode.parentRatingKey ?: return null
        val inSeason = items("/library/metadata/$seasonKey/children")
        val i = inSeason.indexOfFirst { it.ratingKey == episode.ratingKey }
        if (i >= 0 && i < inSeason.lastIndex) return inSeason[i + 1]
        val showKey = episode.grandparentRatingKey ?: return null
        val seasons = items("/library/metadata/$showKey/children").filter { it.type == "season" }
        val s = seasons.indexOfFirst { it.ratingKey == seasonKey }
        val nextSeason = seasons.getOrNull(s + 1) ?: return null
        return items("/library/metadata/${nextSeason.ratingKey}/children").firstOrNull()
    }

    /** Turns a show or season into the episode to play: in progress, else first unwatched, else first. */
    suspend fun playableFor(item: PlexItem): PlexItem? = when (item.type) {
        "movie", "episode" -> item
        "season" -> items("/library/metadata/${item.ratingKey}/children").let { eps ->
            eps.firstOrNull { it.progress != null } ?: eps.firstOrNull { !it.isWatched } ?: eps.firstOrNull()
        }
        "show" -> runCatching { onDeckFor(item.ratingKey) }.getOrNull() ?: run {
            val seasons = items("/library/metadata/${item.ratingKey}/children").filter { it.type == "season" }
            val season = seasons.firstOrNull { (it.viewedLeafCount ?: 0) < (it.leafCount ?: 0) } ?: seasons.firstOrNull()
            season?.let { playableFor(it) }
        }
        else -> null
    }

    /** The Plex Media Server version, e.g. "1.41.2.9200". */
    suspend fun serverVersion(): String? = container(get("/identity")).stringOrNull("version")

    /** Forget loaded lists so the next visit fetches fresh data (e.g. after marking watched). */
    fun clearCache() {
        cache.clear()
        sectionCollections.clear()
    }

    /**
     * Asks the server to look up this title's details, artwork and ratings again from its online
     * sources (Plex's "Refresh Metadata"). A show includes all its seasons and episodes. The
     * server does it in the background; it needs an account that can manage the server.
     */
    suspend fun refreshMetadata(item: PlexItem) {
        client.request("PUT", "$baseUrl/library/metadata/${item.ratingKey}/refresh?force=1", token)
    }

    /** Marks a movie, episode, season or whole show as watched or unwatched. */
    suspend fun setWatched(item: PlexItem, watched: Boolean) {
        val action = if (watched) "scrobble" else "unscrobble"
        get("/:/$action?key=${item.ratingKey}&identifier=$LIBRARY_ID")
    }

    /** Sets your rating out of 10 (Plex shows 5 stars, so 1 star = 2). Null clears it. */
    suspend fun rate(item: PlexItem, outOfTen: Int?) {
        client.request("PUT", "$baseUrl/:/rate?key=${item.ratingKey}&identifier=$LIBRARY_ID&rating=${outOfTen ?: -1}", token)
    }

    /** Technical details of the file(s) behind a movie or episode. */
    suspend fun mediaVersions(item: PlexItem): List<MediaVersion> {
        val meta = container(get("/library/metadata/${item.ratingKey}"))
            .optJSONArray("Metadata")?.optJSONObject(0) ?: return emptyList()
        return (meta.optJSONArray("Media") ?: JSONArray()).objects().map { m ->
            MediaVersion(
                resolution = m.stringOrNull("videoResolution"),
                width = m.intOrNull("width"),
                height = m.intOrNull("height"),
                videoCodec = m.stringOrNull("videoCodec"),
                frameRate = m.stringOrNull("videoFrameRate"),
                audioCodec = m.stringOrNull("audioCodec"),
                audioChannels = m.intOrNull("audioChannels"),
                container = m.stringOrNull("container"),
                bitrateKbps = m.intOrNull("bitrate"),
                files = (m.optJSONArray("Part") ?: JSONArray()).objects().map {
                    MediaFile(path = it.stringOrNull("file"), sizeBytes = it.longOrNull("size"))
                },
            )
        }
    }

    /** Loads every item at [path], e.g. "/library/sections/1/all" or "/library/metadata/123/children". */
    suspend fun items(path: String): List<PlexItem> {
        cache[path]?.let { return it }
        if (path.startsWith(ACTOR_PREFIX)) return actorTitles(path.removePrefix(ACTOR_PREFIX)).also { cache[path] = it }
        val result = mutableListOf<PlexItem>()
        var start = 0
        while (true) {
            val mc = container(
                get(
                    path,
                    mapOf(
                        "X-Plex-Container-Start" to start.toString(),
                        "X-Plex-Container-Size" to PAGE_SIZE.toString(),
                    ),
                )
            )
            val page = (mc.optJSONArray("Metadata") ?: JSONArray()).objects().map(::parseItem)
            result += page
            start += page.size
            val total = mc.optInt("totalSize", result.size)
            if (page.isEmpty() || start >= total) break
        }
        return result.also { cache[path] = it }
    }

    /** Path for [items] listing everything someone is in (across all movie and TV libraries). */
    fun actorPath(person: CastMember): String = ACTOR_PREFIX + (person.id ?: "")

    /** The movies and shows on this server that an actor (by Plex's tag id) is in, newest first. */
    private suspend fun actorTitles(tagId: String): List<PlexItem> {
        if (tagId.isBlank()) return emptyList()
        return libraries().filter { it.type == "movie" || it.type == "show" }.flatMap { lib ->
            runCatching { items("/library/sections/${lib.key}/all?actor=$tagId") }.getOrDefault(emptyList())
        }.distinctBy { it.ratingKey }.sortedByDescending { it.year ?: 0 }
    }

    /** Takes a title off Continue Watching (Plex keeps its progress). */
    suspend fun removeFromContinueWatching(item: PlexItem) {
        client.request("PUT", "$baseUrl/actions/removeFromContinueWatching?ratingKey=${item.ratingKey}", token)
    }

    /** A resized image URL, so the TV downloads small posters instead of full-size artwork. */
    fun imageUrl(thumb: String?, width: Int, height: Int): String? {
        if (thumb.isNullOrBlank()) return null
        val encoded = URLEncoder.encode(thumb, "UTF-8")
        return "$baseUrl/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1" +
            "&url=$encoded&X-Plex-Token=$token"
    }

    private suspend fun get(path: String, headers: Map<String, String> = emptyMap()): String =
        client.request("GET", baseUrl + path, token, extraHeaders = headers)

    private fun container(body: String): JSONObject = JSONObject(body).getJSONObject("MediaContainer")

    private fun parseItem(o: JSONObject) = PlexItem(
        ratingKey = o.optString("ratingKey"),
        title = o.optString("title"),
        type = o.optString("type"),
        year = o.intOrNull("year"),
        thumb = o.stringOrNull("thumb"),
        index = o.intOrNull("index"),
        leafCount = o.intOrNull("leafCount"),
        summary = o.stringOrNull("summary"),
        art = o.stringOrNull("art"),
        parentTitle = o.stringOrNull("parentTitle"),
        grandparentTitle = o.stringOrNull("grandparentTitle"),
        parentIndex = o.intOrNull("parentIndex"),
        viewOffset = o.longOrNull("viewOffset"),
        duration = o.longOrNull("duration"),
        viewCount = o.intOrNull("viewCount"),
        viewedLeafCount = o.intOrNull("viewedLeafCount"),
        userRating = o.floatOrNull("userRating"),
        parentThumb = o.stringOrNull("parentThumb"),
        grandparentThumb = o.stringOrNull("grandparentThumb"),
        contentRating = o.stringOrNull("contentRating"),
        releaseDate = o.stringOrNull("originallyAvailableAt"),
        audienceRating = o.floatOrNull("audienceRating"),
        genres = (o.optJSONArray("Genre") ?: JSONArray()).objects().mapNotNull { it.stringOrNull("tag") },
        clearLogo = clearLogoOf(o),
        parentRatingKey = o.stringOrNull("parentRatingKey"),
        grandparentRatingKey = o.stringOrNull("grandparentRatingKey"),
        titleSort = o.stringOrNull("titleSort"),
        addedAt = o.longOrNull("addedAt"),
        subtype = o.stringOrNull("subtype"),
    )

    private fun clearLogoOf(o: JSONObject): String? =
        (o.optJSONArray("Image") ?: JSONArray()).objects()
            .firstOrNull { it.optString("type") == "clearLogo" }
            ?.stringOrNull("url")

    private companion object {
        const val PAGE_SIZE = 200
        val VIDEO_TYPES = setOf("movie", "show", "season", "episode")
        const val LIBRARY_ID = "com.plexapp.plugins.library"
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.floatOrNull(name: String): Float? =
    if (has(name) && !isNull(name)) optDouble(name).toFloat().takeUnless { it.isNaN() } else null
private fun JSONObject.longOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
private fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
