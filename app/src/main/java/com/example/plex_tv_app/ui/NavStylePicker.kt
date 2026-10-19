@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.settings.NavStyle
import com.example.plex_tv_app.ui.theme.PlexSerif
import com.example.plex_tv_app.ui.theme.palette

private val CardShape = RoundedCornerShape(10.dp)

/**
 * The side bar style chooser, like the theme picker: Home stays visible with the side bar on
 * the left, and highlighting a style shows it there straight away. Up/Down shows it opened or
 * closed. OK keeps it; Back puts the old one back. Either way [onClose] returns to Settings.
 */
@Composable
fun NavStylePicker(onClose: () -> Unit) {
    val settings = LocalSettings.current
    val current = remember { settings.navStyle }
    // Start with the side bar opened (and the current style previewed) so it's clearly on show.
    LaunchedEffect(Unit) {
        settings.previewNavStyle = current
        settings.previewNavOpen = true
    }

    fun finish(keep: NavStyle?) {
        if (keep != null) settings.navStyle = keep
        settings.previewNavStyle = null
        settings.previewNavOpen = false
        onClose()
    }

    Dialog(
        onDismissRequest = { finish(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // No dimming: the side bar behind is the preview.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BackHandler { finish(null) }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to palette.background.copy(alpha = 0.75f),
                            1f to palette.background.copy(alpha = 0.95f),
                        )
                    )
                    .padding(top = 28.dp, bottom = 18.dp)
                    .onPreviewKeyEvent { e ->
                        // Up/Down: see the side bar opened or closed.
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                settings.previewNavOpen = !settings.previewNavOpen
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                val shown = settings.previewNavStyle ?: current
                Text(
                    "Choose a side bar style",
                    fontFamily = PlexSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = palette.text,
                    modifier = Modifier.padding(start = 300.dp),
                )
                Text(
                    "${shown.label}: ${shown.blurb}.  ▲▼ shows it ${if (settings.previewNavOpen) "closed" else "open"}" +
                        (if (!settings.previewNavOpen && settings.hideNavBar) " (normally hidden while closed: Settings → Home & browsing)" else "") +
                        ". OK keeps it, Back cancels.",
                    fontSize = 12.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 300.dp, top = 2.dp),
                )

                val styles = NavStyle.entries
                val first = remember { FocusRequester() }
                LazyRow(
                    // Starts clear of the side bar, so the preview stays visible.
                    contentPadding = PaddingValues(start = 300.dp, end = 56.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(styles) { style ->
                        StyleCard(
                            style = style,
                            isCurrent = style == current,
                            modifier = if (style == current) Modifier.focusRequester(first) else Modifier,
                            onFocused = { settings.previewNavStyle = style },
                            onClick = { finish(style) },
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    repeat(4) {
                        if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
                        withFrameNanos { }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleCard(style: NavStyle, isCurrent: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(150.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = CardShape),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface.copy(alpha = 0.6f),
            focusedContainerColor = palette.surface,
            contentColor = palette.text,
            focusedContentColor = palette.text,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(style.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.text, maxLines = 1, modifier = Modifier.weight(1f))
                if (isCurrent) Box(Modifier.size(6.dp).clip(CircleShape).background(palette.accent))
            }
            Spacer(Modifier.height(4.dp))
            Text(style.blurb, fontSize = 11.sp, color = palette.muted, maxLines = 3, lineHeight = 14.sp)
        }
    }
}
