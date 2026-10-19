@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexException
import com.example.plex_tv_app.plex.PlexPin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SignInScreen(client: PlexClient, onSignedIn: () -> Unit) {
    var pin by remember { mutableStateOf<PlexPin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) } // bump to request a fresh code

    LaunchedEffect(attempt) {
        error = null
        pin = null
        try {
            val newPin = client.createPin()
            pin = newPin
            val deadline = System.currentTimeMillis() + newPin.expiresInSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                val token = client.checkPin(newPin.id)
                if (token != null) {
                    client.authToken = token
                    onSignedIn()
                    return@LaunchedEffect
                }
            }
            attempt++ // code expired without being used: get a new one
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            if (e.code == 404) attempt++ // code expired on Plex's side
            else error = e.message
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sign in to Plex", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(32.dp))

        val currentError = error
        val currentPin = pin
        when {
            currentError != null -> {
                Text("Couldn't reach Plex", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(currentError, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                val focus = remember { FocusRequester() }
                Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(focus)) {
                    Text("Try again")
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }

            currentPin == null -> Text("Getting a code…", style = MaterialTheme.typography.titleLarge)

            else -> {
                Text("On your phone or computer, go to", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "plex.tv/link",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(24.dp))
                Text("and enter this code:", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text(
                    currentPin.code.toCharArray().joinToString(" "),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "This screen will continue automatically once you've signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
