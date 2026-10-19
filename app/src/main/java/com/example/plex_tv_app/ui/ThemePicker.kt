@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.plex_tv_app.ui.theme.Palette
import com.example.plex_tv_app.ui.theme.PlexSerif
import com.example.plex_tv_app.ui.theme.ThemeChoice
import com.example.plex_tv_app.ui.theme.palette

private val CardShape = RoundedCornerShape(10.dp)

/**
 * The theme chooser. Home stays visible behind, dimmed slightly; a row of themes runs along
 * the bottom. Highlighting a theme previews it across the whole app straight away.
 * OK keeps it; Back puts the old one back. Either way [onClose] returns to Settings.
 */
@Composable
fun ThemePicker(onClose: () -> Unit) {
    val settings = LocalSettings.current
    val current = remember { settings.theme } // the theme in use when the picker opened
    val systemDark = isSystemInDarkTheme()
    val confetti = LocalConfetti.current

    fun finish(keep: ThemeChoice?) {
        if (keep != null) settings.theme = keep
        if (keep == ThemeChoice.Party) confetti?.burst(160) // surprise!
        settings.previewTheme = null
        onClose()
    }

    Dialog(
        onDismissRequest = { finish(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Dim Home ever so slightly, so the picker reads as on top without hiding the preview.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.18f) }
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
                    .padding(top = 28.dp, bottom = 18.dp),
            ) {
                val shown = settings.previewTheme ?: current
                Text(
                    "Choose a theme",
                    fontFamily = PlexSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = palette.text,
                    modifier = Modifier.padding(start = 56.dp),
                )
                Text(
                    "${shown.label}: ${shown.blurb}.  OK keeps it, Back cancels.",
                    fontSize = 12.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 56.dp, top = 2.dp),
                )

                val themes = ThemeChoice.entries
                val startIndex = themes.indexOf(current)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 1).coerceAtLeast(0))
                val first = remember { FocusRequester() }
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 56.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(themes) { choice ->
                        ThemeCard(
                            choice = choice,
                            colors = choice.resolve(systemDark),
                            isCurrent = choice == current,
                            modifier = if (choice == current) Modifier.focusRequester(first) else Modifier,
                            onFocused = { settings.previewTheme = choice },
                            onClick = { finish(choice) },
                        )
                    }
                }
                // Start on the theme you're using now.
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

/** One theme: a little sketch of the app in its colours, with its name underneath. */
@Composable
private fun ThemeCard(
    choice: ThemeChoice,
    colors: Palette,
    isCurrent: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(148.dp)
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
        Column(Modifier.padding(7.dp)) {
            Sketch(colors)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.text, maxLines = 1, modifier = Modifier.weight(1f))
                if (isCurrent) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(palette.accent))
                }
            }
        }
    }
}

/** A miniature Home screen in [p]'s colours: nav strip, title, details and a row of cards. */
@Composable
private fun Sketch(p: Palette) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(7.dp))
            .background(p.background)
    ) {
        Row(Modifier.fillMaxSize()) {
            // nav strip
            Column(
                Modifier.fillMaxHeight().width(15.dp).background(p.text.copy(alpha = 0.06f)).padding(top = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent))
                repeat(3) { Box(Modifier.size(width = 7.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(p.muted)) }
            }
            Column(Modifier.fillMaxHeight().padding(7.dp)) {
                Box(Modifier.size(width = 54.dp, height = 6.dp).clip(RoundedCornerShape(2.dp)).background(p.text))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(width = 38.dp, height = 3.dp).clip(RoundedCornerShape(1.dp)).background(p.muted))
                Spacer(Modifier.height(2.dp))
                Box(Modifier.size(width = 68.dp, height = 3.dp).clip(RoundedCornerShape(1.dp)).background(p.muted.copy(alpha = 0.6f)))
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier
                                .size(width = 18.dp, height = 27.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(p.raised),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            if (i == 0) Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(p.accent))
                        }
                    }
                }
            }
        }
    }
}
