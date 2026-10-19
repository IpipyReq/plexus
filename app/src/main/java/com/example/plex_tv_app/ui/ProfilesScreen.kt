@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import com.example.plex_tv_app.ui.theme.palette
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.HomeUser
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Who's watching?" — the row of Plex Home profiles, PIN entry for locked ones,
 * and the auto sign-in toggle.
 */
@Composable
fun ProfilesScreen(
    client: PlexClient,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onProfileChosen: () -> Unit,
    onSignOut: () -> Unit,
) {
    var users by remember { mutableStateOf<List<HomeUser>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var autoSignIn by remember { mutableStateOf(client.autoSignIn) }

    var pinUser by remember { mutableStateOf<HomeUser?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        loadError = null
        try {
            // Profiles hidden on this TV (Manage profiles) stay off the picker; the owner always shows.
            users = client.homeUsers().filter { it.admin || it.id !in client.hiddenProfiles }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            if (e.code == 401) onSignOut() else loadError = e.message
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
        }
    }

    fun choose(user: HomeUser, pin: String?) {
        if (busy) return
        busy = true
        switchError = null
        scope.launch {
            try {
                val token = client.switchProfile(user, pin)
                client.saveProfile(user, token)
                client.autoSignIn = autoSignIn
                onProfileChosen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlexException) {
                if (pin != null && e.code in listOf(400, 401, 403, 422)) {
                    pinError = "Incorrect PIN. Try again."
                } else {
                    pinUser = null
                    switchError = e.message
                }
            } catch (e: Exception) {
                pinUser = null
                switchError = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    // PIN entry takes over the screen for locked profiles.
    val locked = pinUser
    if (locked != null) {
        BackHandler { pinUser = null }
        PinPad(
            user = locked,
            error = pinError,
            busy = busy,
            onSubmit = { pin -> choose(locked, pin) },
            onCancel = { pinUser = null },
        )
        return
    }

    BackHandler(enabled = canGoBack) { onBack() }

    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Who's watching?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(40.dp))

        val list = users
        val error = loadError
        when {
            error != null -> {
                Text("Couldn't load profiles: $error", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { reload++ }) { Text("Try again") }
                    // Accounts without Plex Home can still browse with the main account.
                    OutlinedButton(onClick = onProfileChosen) { Text("Continue with main account") }
                }
            }
            list == null -> Text("Loading profiles…")
            else -> {
                // Highlight the profile used last time, or the first one.
                val startIndex = list.indexOfFirst { it.id == client.profileId }.coerceAtLeast(0)
                val startFocus = remember { FocusRequester() }
                val focused = remember { mutableStateOf(false) }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 58.dp, vertical = 16.dp),
                ) {
                    itemsIndexed(list, key = { _, u -> u.id }) { i, user ->
                        ProfileTile(
                            user = user,
                            modifier = if (i == startIndex) Modifier.focusRequester(startFocus) else Modifier,
                            onClick = {
                                if (user.protected) {
                                    pinError = null
                                    pinUser = user
                                } else {
                                    choose(user, null)
                                }
                            },
                        )
                        if (i == startIndex) RequestInitialFocus(startFocus, focused)
                    }
                }
            }
        }

        switchError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Text("Signing in…", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(36.dp))
        ListItem(
            selected = false,
            onClick = {
                autoSignIn = !autoSignIn
                client.autoSignIn = autoSignIn
            },
            modifier = Modifier.width(620.dp),
            headlineContent = { Text("Sign in automatically") },
            supportingContent = {
                Text("Skip this screen next time and open straight into the last profile used.")
            },
            trailingContent = { ToggleIndicator(on = autoSignIn) },
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onSignOut) { Text("Sign out of Plex") }
    }
}

@Composable
private fun ProfileTile(user: HomeUser, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
        CircleButton(onClick = onClick, size = 128.dp, modifier = modifier) {
            Avatar(user.thumb, user.title, size = 128.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            user.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val tag = when {
            user.protected -> "🔒 PIN"
            user.restricted -> "Managed"
            else -> " "
        }
        Text(tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A switch-style pill that shows on/off. The whole row around it is what gets selected. */
@Composable
fun ToggleIndicator(on: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) PlexGold else palette.text.copy(alpha = 0.3f))
            .padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) palette.onAccent else palette.text))
    }
}
