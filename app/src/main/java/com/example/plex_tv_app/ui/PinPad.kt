@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import com.example.plex_tv_app.ui.theme.palette
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.HomeUser

private const val PIN_LENGTH = 4

/**
 * Asks for a profile's 4-digit PIN. Works with the on-screen keypad (D-pad + OK)
 * or the number buttons on a remote/keyboard. Calls [onSubmit] once 4 digits are entered.
 */
@Composable
fun PinPad(
    user: HomeUser,
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var digits by remember { mutableStateOf("") }

    fun press(d: Char) {
        if (busy || digits.length >= PIN_LENGTH) return
        digits += d
        if (digits.length == PIN_LENGTH) onSubmit(digits)
    }
    fun delete() {
        if (!busy) digits = digits.dropLast(1)
    }

    // A wrong PIN comes back as a new error: clear the dots so they can try again.
    LaunchedEffect(error) { if (error != null) digits = "" }

    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = event.nativeKeyEvent.keyCode) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        press('0' + (code - AndroidKeyEvent.KEYCODE_0)); true
                    }
                    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
                        press('0' + (code - AndroidKeyEvent.KEYCODE_NUMPAD_0)); true
                    }
                    AndroidKeyEvent.KEYCODE_DEL -> {
                        delete(); true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar(user.thumb, user.title, size = 88.dp)
        Spacer(Modifier.height(16.dp))
        Text("Enter PIN for ${user.title}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < digits.length
                Box(
                    Modifier
                        .size(18.dp)
                        .border(2.dp, palette.text, CircleShape)
                        .background(if (filled) palette.text else Color.Transparent, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                busy -> "Checking…"
                error != null -> error
                else -> " "
            },
            color = if (error != null && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        val rows = listOf("123", "456", "789")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEachIndexed { c, d ->
                        KeyButton(
                            label = d.toString(),
                            onClick = { press(d) },
                            modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                KeyButton("Back", onClick = onCancel, small = true)
                KeyButton("0", onClick = { press('0') })
                KeyButton("⌫", onClick = { delete() })
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    CircleButton(onClick = onClick, size = 68.dp, modifier = modifier) {
        Text(label, fontSize = if (small) 16.sp else 26.sp)
    }
}
