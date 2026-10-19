package com.example.plex_tv_app.plex

import android.content.Context
import android.os.Build
import android.util.Xml
import com.example.plex_tv_app.settings.InsecureConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.StringReader
import java.net.URL
import java.util.UUID
import org.xmlpull.v1.XmlPullParser

data class PlexPin(val id: Long, val code: String, val expiresInSeconds: Int)

data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)

data class PlexServer(
    val id: String, // Plex's machine identifier for the server
    val name: String,
    val owned: Boolean,
    val accessToken: String?,
    val connections: List<PlexConnection>,
)

/** A profile in your Plex Home (you, family members, managed users). */
data class HomeUser(
    val id: Long,
    val title: String,
    val thumb: String?,
    val protected: Boolean, // has a PIN
    val admin: Boolean,
    val restricted: Boolean, // managed/kid account
)

class PlexException(val code: Int, message: String) : Exception(message)

/**
 * Talks to plex.tv: PIN sign-in, and listing the servers your account can reach.
 * Uses only what's built into Android (HttpURLConnection + org.json), so no extra libraries.
 */
class PlexClient(context: Context) {
    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)

    /** A random ID that identifies this app install to Plex. Created once, then reused. */
    val clientId: String = prefs.getString(KEY_CLIENT_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_CLIENT_ID, it).apply() }

    /** The token for the Plex account that linked this TV (the Home owner). */
    var authToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, value)
            editor.apply()
        }

    /** The token for the profile currently in use, after choosing who's watching. */
    var profileToken: String?
        get() = prefs.getString(KEY_PROFILE_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_PROFILE_TOKEN, value).apply()

    val profileId: Long get() = prefs.getLong(KEY_PROFILE_ID, -1L)
    val profileTitle: String? get() = prefs.getString(KEY_PROFILE_TITLE, null)
    val profileThumb: String? get() = prefs.getString(KEY_PROFILE_THUMB, null)

    /** When on, launching the app skips the profile picker and opens as the last profile. */
    /** Profiles hidden from this TV's profile picker (Manage profiles). The owner is never hidden. */
    var hiddenProfiles: Set<Long>
        get() = prefs.getString("hidden_profiles", null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putString("hidden_profiles", value.joinToString(",")).apply()

    var autoSignIn: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SIGN_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SIGN_IN, value).apply()

    /** The server opened last time, so the app can connect to it automatically. */
    var lastServerId: String?
        get() = prefs.getString(KEY_LAST_SERVER, null)
        set(value) = prefs.edit().putString(KEY_LAST_SERVER, value).apply()

    /** Card size on Home and in libraries, saved per profile. Starts at 80%. */
    var cardScale: Float
        get() = prefs.getFloat("card_scale_$profileId", com.example.plex_tv_app.ui.DEFAULT_CARD_SCALE)
        set(value) = prefs.edit().putFloat("card_scale_$profileId", value).apply()

    /** Your custom order of Home rows (by PlexHub.rowKey), saved per profile. Empty = Plex's order. */
    var rowOrder: List<String>
        get() = prefs.getString("row_order_$profileId", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = prefs.edit().putString("row_order_$profileId", value.joinToString("\n")).apply()

    /** Your custom order of a library's Recommended rows, saved per profile. Empty = Plex's order. */
    fun libraryRowOrder(libraryKey: String): List<String> =
        prefs.getString("row_order_${profileId}_lib_$libraryKey", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    fun setLibraryRowOrder(libraryKey: String, order: List<String>) =
        prefs.edit().putString("row_order_${profileId}_lib_$libraryKey", order.joinToString("\n")).apply()

    /** How each Home row draws its cards (rowKey -> style name), saved per profile. */
    var rowStyles: Map<String, String>
        get() = prefs.getString("row_styles_$profileId", null).orEmpty()
            .split('\n')
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        set(value) = prefs.edit()
            .putString("row_styles_$profileId", value.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()

    /** Your last few searches, newest first, saved per profile. */
    var recentSearches: List<String>
        get() = prefs.getString("recent_searches_$profileId", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString("recent_searches_$profileId", value.take(8).joinToString("\n")).apply()

    /** The token used for browsing: the chosen profile's, or the account's if none was chosen. */
    val activeToken: String? get() = profileToken ?: authToken

    fun saveProfile(user: HomeUser, token: String) {
        prefs.edit()
            .putString(KEY_PROFILE_TOKEN, token)
            .putLong(KEY_PROFILE_ID, user.id)
            .putString(KEY_PROFILE_TITLE, user.title)
            .putString(KEY_PROFILE_THUMB, user.thumb)
            .apply()
    }

    /** Signs this TV out completely (keeps the install ID and the auto sign-in preference). */
    fun signOut() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_PROFILE_TOKEN)
            .remove(KEY_PROFILE_ID)
            .remove(KEY_PROFILE_TITLE)
            .remove(KEY_PROFILE_THUMB)
            .apply()
    }

    /** Lists the profiles in this account's Plex Home. */
    suspend fun homeUsers(): List<HomeUser> {
        val token = authToken ?: throw PlexException(401, "Not signed in")
        val xml = request("GET", "https://plex.tv/api/home/users", token, extraHeaders = XML)
        val users = mutableListOf<HomeUser>()
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "User") {
                fun attr(name: String): String? = parser.getAttributeValue(null, name)
                users += HomeUser(
                    id = attr("id")?.toLongOrNull() ?: continue,
                    title = attr("title")?.takeIf { it.isNotBlank() } ?: attr("username") ?: "Profile",
                    thumb = attr("thumb")?.takeIf { it.isNotBlank() },
                    protected = attr("protected") == "1",
                    admin = attr("admin") == "1",
                    restricted = attr("restricted") == "1",
                )
            }
        }
        // Account owner first, like the official apps.
        return users.sortedByDescending { it.admin }
    }

    /**
     * Adds a managed profile (no Plex account or email needed) to your Plex Home. Owner only.
     * Which libraries it can see is set on plex.tv (Settings → Manage Library Access).
     */
    suspend fun addManagedProfile(name: String) {
        val token = authToken ?: throw PlexException(401, "Not signed in")
        val title = java.net.URLEncoder.encode(name.trim(), "UTF-8")
        request("POST", "https://plex.tv/api/home/users?title=$title", token, extraHeaders = XML)
    }

    /** Removes someone from your Plex Home (a managed profile is deleted). Owner only. */
    suspend fun removeProfile(user: HomeUser) {
        val token = authToken ?: throw PlexException(401, "Not signed in")
        request("DELETE", "https://plex.tv/api/home/users/${user.id}", token, extraHeaders = XML)
    }

    /**
     * Switches to [user] and returns that profile's token.
     * Throws PlexException with code 401/403 if the PIN is wrong.
     */
    suspend fun switchProfile(user: HomeUser, pin: String?): String {
        val token = authToken ?: throw PlexException(401, "Not signed in")
        val query = if (pin != null) "?pin=$pin" else ""
        val xml = request("POST", "https://plex.tv/api/home/users/${user.id}/switch$query", token, extraHeaders = XML)
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val t = parser.getAttributeValue(null, "authenticationToken")
                    ?: parser.getAttributeValue(null, "authToken")
                if (!t.isNullOrBlank()) return t
            }
        }
        throw PlexException(0, "Plex didn't return a token for ${user.title}.")
    }

    /** Step 1: ask plex.tv for a short code to show on screen. */
    suspend fun createPin(): PlexPin {
        val json = JSONObject(request("POST", "https://plex.tv/api/v2/pins?strong=false"))
        return PlexPin(
            id = json.getLong("id"),
            code = json.getString("code"),
            expiresInSeconds = json.optInt("expiresIn", 900),
        )
    }

    /** Step 2: poll this. Returns the token once the code was entered at plex.tv/link, else null. */
    suspend fun checkPin(id: Long): String? {
        val json = JSONObject(request("GET", "https://plex.tv/api/v2/pins/$id"))
        if (json.isNull("authToken")) return null
        return json.getString("authToken").takeIf { it.isNotBlank() }
    }

    /** Lists the Plex Media Servers this account can access (yours and shared ones). */
    suspend fun fetchServers(): List<PlexServer> {
        val token = activeToken ?: throw PlexException(401, "Not signed in")
        val array = JSONArray(
            request("GET", "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token)
        )
        return (0 until array.length())
            .map { array.getJSONObject(it) }
            .filter { it.optString("provides").split(",").contains("server") }
            .map { res ->
                val conns = res.optJSONArray("connections") ?: JSONArray()
                PlexServer(
                    id = res.optString("clientIdentifier"),
                    name = res.optString("name", "Unnamed server"),
                    owned = res.optBoolean("owned"),
                    accessToken = if (res.isNull("accessToken")) null else res.optString("accessToken"),
                    connections = (0 until conns.length()).map { i ->
                        val c = conns.getJSONObject(i)
                        PlexConnection(
                            uri = c.optString("uri"),
                            local = c.optBoolean("local"),
                            relay = c.optBoolean("relay"),
                        )
                    },
                )
            }
    }

    /**
     * Picks a working address for [server]: local network first, then direct remote, then Plex relay.
     */
    suspend fun connect(
        server: PlexServer,
        allowRelay: Boolean = true,
        insecure: InsecureConnections = InsecureConnections.SameNetwork,
    ): PlexServerApi {
        val token = server.accessToken ?: activeToken ?: throw PlexException(401, "Not signed in")
        // Respect Settings → Server: relay and plain-HTTP addresses can be turned off.
        val allowed = server.connections.filter { c ->
            val plainHttp = c.uri.startsWith("http://")
            when {
                c.relay && !allowRelay -> false
                !plainHttp -> true
                insecure == InsecureConnections.Always -> true
                insecure == InsecureConnections.SameNetwork -> c.local
                else -> false
            }
        }
        val ordered = allowed.sortedBy {
            when {
                it.local && !it.relay -> 0
                !it.relay -> 1
                else -> 2
            }
        }
        for (connection in ordered) {
            val base = connection.uri.trimEnd('/')
            try {
                request("GET", "$base/identity", token, timeoutMs = 4_000)
                return PlexServerApi(this, server.name, base, token, isLocal = connection.local, canManage = server.owned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This address didn't answer; try the next one.
            }
        }
        val skipped = server.connections.size - allowed.size
        val note = if (skipped > 0) " ($skipped more blocked by your Server settings)" else ""
        throw PlexException(0, "Couldn't reach ${server.name} on any of its ${allowed.size} addresses$note.")
    }

    internal suspend fun request(
        method: String,
        url: String,
        token: String? = null,
        timeoutMs: Int = 10_000,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs + 5_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("X-Plex-Product", PRODUCT_NAME)
                conn.setRequestProperty("X-Plex-Version", "0.1")
                conn.setRequestProperty("X-Plex-Client-Identifier", clientId)
                conn.setRequestProperty("X-Plex-Platform", "Android")
                conn.setRequestProperty("X-Plex-Device", "Google TV")
                conn.setRequestProperty("X-Plex-Device-Name", Build.MODEL)
                if (token != null) conn.setRequestProperty("X-Plex-Token", token)
                extraHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                if (method == "POST" || method == "PUT") {
                    conn.doOutput = true
                    conn.outputStream.close() // empty body
                }
                val status = conn.responseCode
                if (status !in 200..299) {
                    throw PlexException(status, "Plex returned HTTP $status for $method $url")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

    /** A plain GET that returns the raw bytes (for files like preview thumbnails). */
    internal suspend fun requestBytes(url: String, token: String?, timeoutMs: Int = 20_000): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs + 10_000
                conn.setRequestProperty("X-Plex-Product", PRODUCT_NAME)
                conn.setRequestProperty("X-Plex-Client-Identifier", clientId)
                if (token != null) conn.setRequestProperty("X-Plex-Token", token)
                val status = conn.responseCode
                if (status !in 200..299) throw PlexException(status, "Plex returned HTTP $status for GET $url")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        const val PRODUCT_NAME = "Plexus"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_PROFILE_TOKEN = "profile_token"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_PROFILE_TITLE = "profile_title"
        private const val KEY_PROFILE_THUMB = "profile_thumb"
        private const val KEY_AUTO_SIGN_IN = "auto_sign_in"
        private const val KEY_LAST_SERVER = "last_server"
        private val XML = mapOf("Accept" to "application/xml")
    }
}
