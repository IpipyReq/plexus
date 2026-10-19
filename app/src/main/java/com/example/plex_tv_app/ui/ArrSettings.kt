package com.example.plex_tv_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.example.plex_tv_app.arr.ArrClient
import com.example.plex_tv_app.arr.ArrKind
import com.example.plex_tv_app.arr.ArrOption
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.settings.AppSettings
import com.example.plex_tv_app.settings.DiscoverSource
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException

/**
 * Settings → Server (owner only): TMDB for Discover and "More like this", and Radarr/Sonarr so
 * titles can be added. Radarr/Sonarr are looked for on the Plex server's PC automatically.
 */
@Composable
fun ArrSettings(s: AppSettings, api: PlexServerApi) {
    SettingsSection("Who can use these")
    ToggleSetting(
        "Let everyone on this TV use Discover and downloads",
        "Off: only you (the server's owner) get Discover, \"More like this\", adding titles and download status. " +
            "On: every profile on this TV can browse, add titles and search for episodes.",
        s.shareDiscover,
    ) { s.shareDiscover = it }
    TmdbBlock(s)
    SettingsSection("Adding movies and shows")
    SettingsNote(
        "Connect Radarr and Sonarr to add titles from Search, Discover and \"More like this\". " +
            "Works on your home network. Each API key is in that app under Settings → General. " +
            "Tip: the Google TV app on your phone has a keyboard, which makes typing keys easier."
    )
    val plexIp = remember(api.baseUrl) { lanAddress(api.baseUrl) }
    ArrBlock(s, ArrKind.Movie, plexIp)
    ArrBlock(s, ArrKind.Show, plexIp)
}

/** The Plex server's home-network address, if we're connected to it locally (e.g. "192.168.1.20"). */
private fun lanAddress(baseUrl: String): String? {
    val m = Regex("""(\d{1,3})[-.](\d{1,3})[-.](\d{1,3})[-.](\d{1,3})""").find(baseUrl) ?: return null
    val ip = m.groupValues.drop(1).joinToString(".")
    val private = ip.startsWith("192.168.") || ip.startsWith("10.") ||
        Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)
    return ip.takeIf { private }
}

@Composable
private fun TmdbBlock(s: AppSettings) {
    var editing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val key = s.tmdbKey
    LaunchedEffect(key) {
        val client = s.tmdb()
        if (client == null) {
            status = "Not set up"
            return@LaunchedEffect
        }
        status = "Checking…"
        status = try {
            client.test()
            "Working"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Not working: ${e.message ?: "no answer"}"
        }
    }

    SettingsSection("Discover and recommendations")
    EnumSetting(
        "Recommendations from",
        "Plex uses your Plex account, with nothing to set up. TMDB has more rows (genres, coming soon) but needs a free key from themoviedb.org.",
        DiscoverSource.entries,
        s.discoverSource,
    ) { s.discoverSource = it }
    if (s.discoverSource == DiscoverSource.Tmdb) {
        SettingsNote("Get a TMDB key at themoviedb.org: sign up, then Settings → API. Either the API key or the Read Access Token works.")
        ActionSetting("TMDB key", null, value = if (key.isBlank()) "Not set" else "••••" + key.takeLast(4)) { editing = true }
        InfoSetting("TMDB status", status ?: "")
    }
    if (s.discoverReady) {
        ToggleSetting("Discover in the side bar", "Show the Discover page in the side bar.", s.showDiscover) { s.showDiscover = it }
        ToggleSetting("More like this", "Show recommendations at the bottom of movie and show pages.", s.moreLikeThis) { s.moreLikeThis = it }
    }
    if (editing) {
        TextEntryPanel(
            title = "TMDB key",
            initial = key,
            hint = "API key or Read Access Token",
            keyboardType = KeyboardType.Password,
            onSave = { v ->
                editing = false
                s.tmdbKey = v.trim()
            },
            onClose = { editing = false },
        )
    }
}

private enum class ArrField { Host, Port, Key }

@Composable
private fun ArrBlock(s: AppSettings, kind: ArrKind, plexIp: String?) {
    val movie = kind == ArrKind.Movie
    val name = if (movie) "Radarr" else "Sonarr"
    val defaultPort = if (movie) "7878" else "8989"
    var editing by remember { mutableStateOf<ArrField?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<List<ArrOption>>(emptyList()) }
    var roots by remember { mutableStateOf<List<ArrOption>>(emptyList()) }
    val host = if (movie) s.radarrHost else s.sonarrHost
    val port = if (movie) s.radarrPort else s.sonarrPort
    val key = if (movie) s.radarrKey else s.sonarrKey
    val url = if (movie) s.radarrUrl else s.sonarrUrl

    // No address yet: try the Plex server's PC (Radarr and Sonarr usually live there too).
    LaunchedEffect(Unit) {
        if (host.isBlank() && plexIp != null && ArrClient.ping("http://$plexIp:${port.ifBlank { defaultPort }}")) {
            if (movie) s.radarrHost = plexIp else s.sonarrHost = plexIp
            found = true
        }
    }

    // Check the connection (and load its choices) whenever the address or key changes.
    LaunchedEffect(url, key) {
        val client = s.arr(kind)
        if (client == null) {
            profiles = emptyList(); roots = emptyList()
            status = when {
                url.isBlank() -> "Not set up"
                found -> "Found on your Plex PC. Add its API key to finish."
                else -> "Needs its API key"
            }
            return@LaunchedEffect
        }
        status = "Checking…"
        status = try {
            val version = client.test()
            profiles = client.qualityProfiles()
            roots = client.rootFolders()
            // Pick sensible defaults the first time.
            if (movie) {
                if (s.radarrProfile.isBlank()) profiles.firstOrNull()?.let { s.radarrProfile = it.id }
                if (s.radarrRoot.isBlank()) roots.firstOrNull()?.let { s.radarrRoot = it.id }
            } else {
                if (s.sonarrProfile.isBlank()) profiles.firstOrNull()?.let { s.sonarrProfile = it.id }
                if (s.sonarrRoot.isBlank()) roots.firstOrNull()?.let { s.sonarrRoot = it.id }
            }
            "Connected (version $version)"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Can't connect: ${e.message ?: "no answer"}"
        }
    }

    ActionSetting(
        "$name IP address",
        if (plexIp != null) "Usually the same PC as Plex ($plexIp)." else "The PC $name runs on, e.g. 192.168.1.20.",
        value = host.ifBlank { "Not set" },
    ) { editing = ArrField.Host }
    ActionSetting("$name port", "$name's usual port is $defaultPort.", value = port.ifBlank { defaultPort }) { editing = ArrField.Port }
    ActionSetting("$name API key", null, value = if (key.isBlank()) "Not set" else "••••" + key.takeLast(4)) {
        editing = ArrField.Key
    }
    InfoSetting("$name status", status ?: "")
    if (profiles.isNotEmpty()) {
        ChoiceSetting(
            "$name quality",
            "The quality profile new ${if (movie) "movies" else "shows"} are added with.",
            profiles.map { it.id to it.label },
            if (movie) s.radarrProfile else s.sonarrProfile,
        ) { if (movie) s.radarrProfile = it else s.sonarrProfile = it }
    }
    if (roots.isNotEmpty()) {
        ChoiceSetting(
            "$name folder",
            "Where new ${if (movie) "movies" else "shows"} are saved.",
            roots.map { it.id to it.label },
            if (movie) s.radarrRoot else s.sonarrRoot,
        ) { if (movie) s.radarrRoot = it else s.sonarrRoot = it }
    }

    editing?.let { field ->
        TextEntryPanel(
            title = when (field) {
                ArrField.Host -> "$name IP address"
                ArrField.Port -> "$name port"
                ArrField.Key -> "$name API key"
            },
            initial = when (field) {
                ArrField.Host -> host.ifBlank { plexIp.orEmpty() }
                ArrField.Port -> port.ifBlank { defaultPort }
                ArrField.Key -> key
            },
            hint = when (field) {
                ArrField.Host -> plexIp ?: "192.168.1.20"
                ArrField.Port -> defaultPort
                ArrField.Key -> "32 letters and numbers"
            },
            keyboardType = when (field) {
                ArrField.Host -> KeyboardType.Decimal
                ArrField.Port -> KeyboardType.Number
                ArrField.Key -> KeyboardType.Password
            },
            resetLabel = if (field == ArrField.Port && port != defaultPort) "Use the usual port ($defaultPort)" else null,
            onReset = { if (movie) s.radarrPort = defaultPort else s.sonarrPort = defaultPort; editing = null },
            onSave = { value ->
                editing = null
                val v = value.trim()
                when (field) {
                    ArrField.Host -> {
                        // Typed "192.168.1.20:7878"? Split it into address and port.
                        val bare = v.removePrefix("http://").removePrefix("https://").trimEnd('/')
                        val h = bare.substringBefore(':')
                        val p = bare.substringAfter(':', "").filter { it.isDigit() }
                        if (movie) s.radarrHost = h else s.sonarrHost = h
                        if (p.isNotEmpty()) { if (movie) s.radarrPort = p else s.sonarrPort = p }
                    }
                    ArrField.Port -> {
                        val p = v.filter { it.isDigit() }.ifBlank { defaultPort }
                        if (movie) s.radarrPort = p else s.sonarrPort = p
                    }
                    ArrField.Key -> if (movie) s.radarrKey = v else s.sonarrKey = v
                }
            },
            onClose = { editing = null },
        )
    }
}

/** Typing a value with the TV's keyboard (it opens when you select the box). */
@Composable
internal fun TextEntryPanel(
    title: String,
    initial: String,
    hint: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    resetLabel: String? = null,
    onReset: () -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    val keyboard = LocalSoftwareKeyboardController.current
    SidePanel(title = title, onClose = onClose) { first ->
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.text.copy(alpha = 0.06f))
                .border(2.dp, if (focused) palette.text else palette.text.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(200) },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onSave(text)
                }),
                modifier = first
                    .fillMaxWidth()
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) keyboard?.show()
                    },
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(hint, fontSize = 16.sp, color = palette.muted)
                    inner()
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        PickerRow("Save", false, Modifier) { onSave(text) }
        if (resetLabel != null) PickerRow(resetLabel, false, Modifier) { onReset() }
    }
}
