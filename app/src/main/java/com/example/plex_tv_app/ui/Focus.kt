package com.example.plex_tv_app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.focus.FocusRequester

/**
 * Puts the remote's focus on [requester] the first time a screen shows its content,
 * so you don't have to press a D-pad key before anything is highlighted.
 */
@Composable
fun RequestInitialFocus(requester: FocusRequester, done: MutableState<Boolean>) {
    if (!done.value) {
        LaunchedEffect(Unit) {
            done.value = runCatching { requester.requestFocus() }.isSuccess
        }
    }
}
