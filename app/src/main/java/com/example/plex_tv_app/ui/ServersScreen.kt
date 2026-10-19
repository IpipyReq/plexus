@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexException
import com.example.plex_tv_app.plex.PlexServer
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.settings.LocalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The list of Plex servers on this profile.
 *
 * @param autoConnect connect straight to the last-used server (or your own first server)
 *   instead of waiting for a choice. Used on launch; the Account menu opens it without this.
 */
@Composable
fun ServersScreen(
    client: PlexClient,
    autoConnect: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onConnected: (PlexServerApi) -> Unit,
    onSwitchUser: () -> Unit,
    onSignOut: () -> Unit,
) {
    var servers by remember { mutableStateOf<List<PlexServer>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectingTo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current

    BackHandler(enabled = canGoBack) { onBack() }

    fun open(server: PlexServer) {
        if (connectingTo != null) return
        connectingTo = server.name
        error = null
        scope.launch {
            try {
                val api = client.connect(server, settings.allowRelay, settings.insecureConnections)
                client.lastServerId = server.id
                onConnected(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                connectingTo = null
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val list = client.fetchServers()
            servers = list
            if (autoConnect) {
                val pick = list.firstOrNull { it.id == client.lastServerId }
                    ?: list.firstOrNull { it.owned }
                    ?: list.firstOrNull()
                if (pick != null) open(pick)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            if (e.code == 401) onSwitchUser() // profile token no longer valid: pick the profile again
            else error = e.message
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    // While auto-connecting on launch, just show a simple splash.
    val connecting = connectingTo
    if (autoConnect && error == null && (servers == null || connecting != null)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (connecting != null) "Connecting to $connecting…" else "Finding your server…", style = MaterialTheme.typography.headlineSmall)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Servers",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            val name = client.profileTitle
            if (name != null) {
                Avatar(client.profileThumb, name, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(24.dp))
            }
            OutlinedButton(onClick = onSwitchUser) { Text("Switch user") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
        Spacer(Modifier.height(16.dp))
        connecting?.let { Text("Connecting to $it…", color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))

        val list = servers
        when {
            list == null -> if (error == null) Text("Loading…")
            list.isEmpty() -> Text("No servers found on this profile.")
            else -> {
                val first = remember { FocusRequester() }
                val focused = remember { mutableStateOf(false) }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(list) { i, server ->
                        ListItem(
                            selected = server.id == client.lastServerId,
                            onClick = { open(server) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            headlineContent = { Text(server.name) },
                            supportingContent = {
                                val kind = if (server.owned) "Your server" else "Shared with you"
                                val local = server.connections.count { it.local }
                                val remote = server.connections.count { !it.local && !it.relay }
                                Text("$kind · $local local, $remote remote connection(s)")
                            },
                        )
                        if (i == 0) RequestInitialFocus(first, focused)
                    }
                }
            }
        }
    }
}
