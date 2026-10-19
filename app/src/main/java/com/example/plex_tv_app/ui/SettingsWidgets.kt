@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.compose.runtime.ReadOnlyComposable
import com.example.plex_tv_app.ui.theme.palette
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.settings.Labeled
import com.example.plex_tv_app.ui.theme.PlexSerif

// Shared palette for settings, matching Home.
internal val SettingsBone: Color @Composable @ReadOnlyComposable get() = palette.text
internal val SettingsMist: Color @Composable @ReadOnlyComposable get() = palette.muted
private val RowFocused: Color @Composable @ReadOnlyComposable get() = palette.focusTint
private val PanelColor: Color @Composable @ReadOnlyComposable get() = palette.surface.copy(alpha = 0.97f)

/** A group heading inside a settings page. */
@Composable
fun SettingsSection(title: String) {
    Text(
        title,
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = SettingsBone,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp),
    )
}

/** Small explanatory text under a page title or section. */
@Composable
fun SettingsNote(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = SettingsMist,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The basic settings row: a title, an optional description, and something on the right
 * (a value, a switch, a chevron). Everything else is built on this.
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.inChain().confettiAnchor().returnFocusHere().fillMaxWidth().padding(vertical = 2.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = RowFocused,
            contentColor = SettingsBone,
            focusedContentColor = SettingsBone,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = SettingsBone)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(description, fontSize = 13.sp, lineHeight = 18.sp, color = SettingsMist, maxLines = 3)
                }
            }
            Spacer(Modifier.width(20.dp))
            // Cap the right-hand side so a long value can't squeeze the title.
            Box(Modifier.widthIn(max = 280.dp)) { trailing() }
        }
    }
}

/** On/off setting. */
@Composable
fun ToggleSetting(title: String, description: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    SettingRow(title, description, modifier, onClick = { onChange(!checked) }) { ToggleIndicator(on = checked) }
}

/** A row that does something when selected (opens a page, runs an action). */
@Composable
fun ActionSetting(title: String, description: String? = null, value: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SettingRow(title, description, modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) {
                Text(value, fontSize = 14.sp, color = SettingsMist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
            }
            Text("›", fontSize = 22.sp, color = SettingsMist)
        }
    }
}

/**
 * A read-only fact. Still focusable so the remote can scroll past it.
 * Short values sit on the right; long ones (like an address) go on their own line underneath.
 */
@Composable
fun InfoSetting(title: String, value: String, modifier: Modifier = Modifier) {
    if (value.length > 28) {
        SettingRow(title, description = value, modifier = modifier)
    } else {
        SettingRow(title, modifier = modifier) {
            Text(value, fontSize = 14.sp, color = SettingsMist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The "Reset to defaults" row at the bottom of each page. Asks for a second press first. */
@Composable
fun ResetToDefaults(page: String, modifier: Modifier = Modifier, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    SettingsSection("Defaults")
    ActionSetting(
        "Reset $page to defaults",
        if (confirm) "Press OK again to confirm." else "Put everything on this page back the way it started.",
        modifier = modifier,
        value = when {
            done -> "Done"
            confirm -> "Confirm"
            else -> null
        },
    ) {
        if (confirm) {
            onReset()
            confirm = false
            done = true
        } else {
            confirm = true
            done = false
        }
    }
}

/** Pick one of several options. Shows the current choice; selecting the row opens a picker. */
@Composable
fun <T> ChoiceSetting(
    title: String,
    description: String?,
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: ""
    ActionSetting(title, description, value = current, modifier = modifier) { open = true }
    if (open) {
        ChoicePanel(title, options, selected, onDismiss = { open = false }) {
            onSelect(it)
            open = false
        }
    }
}

/** ChoiceSetting for enums with labels. */
@Composable
fun <E> EnumSetting(
    title: String,
    description: String?,
    values: List<E>,
    selected: E,
    modifier: Modifier = Modifier,
    onSelect: (E) -> Unit,
) where E : Enum<E>, E : Labeled {
    ChoiceSetting(title, description, values.map { it to it.label }, selected, modifier, onSelect)
}

/** The picker panel that slides over the right side of the screen. Back closes it. */
@Composable
private fun <T> ChoicePanel(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.35f) }
        BackHandler { onDismiss() }
        CompositionLocalProvider(LocalFocusChain provides null) {

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .padding(24.dp)
                    .width(400.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelColor)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(title, fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = SettingsBone)
                Spacer(Modifier.height(14.dp))
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    SettingRow(
                        title = label,
                        modifier = if (isSelected) Modifier.focusRequester(focus) else Modifier,
                        onClick = { onPick(value) },
                    ) {
                        if (isSelected) Text("✓", color = PlexGold, fontSize = 20.sp)
                    }
                }
            }
        }
        }
    }
}
