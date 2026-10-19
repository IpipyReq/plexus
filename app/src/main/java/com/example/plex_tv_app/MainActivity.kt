package com.example.plex_tv_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.example.plex_tv_app.ui.ConfettiLayer
import com.example.plex_tv_app.ui.ConfettiState
import com.example.plex_tv_app.ui.LaunchSplash
import com.example.plex_tv_app.ui.NavSounds
import com.example.plex_tv_app.ui.LocalConfetti
import com.example.plex_tv_app.ui.theme.ThemeChoice
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.plex_tv_app.settings.AppSettings
import com.example.plex_tv_app.settings.LocalSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.ui.ProfilesScreen
import com.example.plex_tv_app.ui.ServersScreen
import com.example.plex_tv_app.ui.Shell
import com.example.plex_tv_app.ui.SignInScreen
import com.example.plex_tv_app.ui.theme.PlexTVAppTheme

/** The three stages of the app: link the TV, pick a profile, then browse. */
enum class Phase { Link, Profiles, Browse }

private fun startPhase(client: PlexClient): Phase = when {
    client.authToken == null -> Phase.Link
    client.autoSignIn && client.profileToken != null -> Phase.Browse
    else -> Phase.Profiles
}

/** The screen's density, with text scaled by Settings → Text size (read live, as Compose state). */
private class TextScaledDensity(private val base: Density, private val settings: AppSettings) : Density {
    override val density: Float get() = base.density
    override val fontScale: Float get() = base.fontScale * settings.textScale
}

/** Whether this run of the app has shown its launch splash (it lives as long as the app does). */
private var splashShownThisRun = false

class MainActivity : ComponentActivity() {
    // Android's installer replies here during an in-app update (MainActivity is singleTask).
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        com.example.plex_tv_app.update.handleInstallStatus(this, intent)
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = PlexClient(applicationContext)
        val settings = AppSettings(applicationContext)
        settings.useProfile(client.profileId) // text size is per profile
        client.sharedSizes = !settings.sizesPerProfile // card size: per profile, or one for the TV

        // Back with nothing left to go back to: send the app to the background (like Home) instead
        // of closing it, so opening it again is instant and lands where you were. Registered first,
        // so every screen's own Back handling still comes before it.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        // Opened by Android's installer during an in-app update (see Updater).
        com.example.plex_tv_app.update.handleInstallStatus(this, intent)

        setContent {
            CompositionLocalProvider(LocalSettings provides settings) {
                val shownTheme = settings.previewTheme ?: settings.theme
                val confetti = remember { ConfettiState() }
                // Only when the app is starting up, not when coming back to it.
                var splashing by remember { mutableStateOf(!splashShownThisRun) }
                // Settings → Appearance → Text size: scales every bit of text in the app. The same
                // Density object is kept and only its text scale changes, so nothing on screen is
                // rebuilt (a new one would reopen menus and reset pages mid-adjustment).
                val density = LocalDensity.current
                val scaled = remember(density) { TextScaledDensity(density, settings) }
                CompositionLocalProvider(LocalDensity provides scaled) {
                PlexTVAppTheme(shownTheme.resolve(systemDark = isSystemInDarkTheme())) {
                CompositionLocalProvider(LocalConfetti provides confetti) {
                // Birthday party theme: every OK press throws confetti.
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { e ->
                            val ok = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            if (splashing) return@onPreviewKeyEvent true // ignore presses until the app shows
                            if (e.type == KeyEventType.KeyDown) {
                                NavSounds.onKeyDown(this@MainActivity, settings, e.nativeKeyEvent.keyCode, e.nativeKeyEvent.repeatCount)
                            }
                            if (shownTheme == ThemeChoice.Party && ok &&
                                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0
                            ) {
                                confetti.burst()
                            }
                            false // never swallow the press
                        },
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                        var phase by remember { mutableStateOf(startPhase(client)) }
                        val signOut = {
                            client.signOut()
                            phase = Phase.Link
                        }
                        when (phase) {
                            Phase.Link -> SignInScreen(client = client, onSignedIn = { phase = Phase.Profiles })
                            Phase.Profiles -> ProfilesScreen(
                                client = client,
                                canGoBack = client.profileToken != null,
                                onBack = { phase = Phase.Browse },
                                onProfileChosen = { phase = Phase.Browse },
                                onSignOut = signOut,
                            )
                            Phase.Browse -> Browser(
                                client = client,
                                onSwitchUser = { phase = Phase.Profiles },
                                onSignOut = signOut,
                            )
                        }
                    }
                }
                if (splashing) LaunchSplash(onDone = {
                    splashing = false
                    splashShownThisRun = true
                })
                ConfettiLayer(confetti) // drawn over everything
                }
                }
                }
            }
        }
    }
}

/**
 * After a profile is chosen: connect to a server (automatically on launch), then show the
 * main app. The Account menu can bring the server list back up.
 */
@Composable
private fun Browser(client: PlexClient, onSwitchUser: () -> Unit, onSignOut: () -> Unit) {
    var api by remember { mutableStateOf<PlexServerApi?>(null) }
    var showServers by remember { mutableStateOf(false) }

    val connected = api
    if (connected == null || showServers) {
        ServersScreen(
            client = client,
            autoConnect = connected == null,
            canGoBack = connected != null,
            onBack = { showServers = false },
            onConnected = {
                api = it
                showServers = false
            },
            onSwitchUser = onSwitchUser,
            onSignOut = onSignOut,
        )
    } else {
        key(connected) {
            Shell(
                client = client,
                api = connected,
                onSwitchUser = onSwitchUser,
                onShowServers = { showServers = true },
                onSignOut = onSignOut,
            )
        }
    }
}
