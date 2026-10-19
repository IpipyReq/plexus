package com.example.plex_tv_app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * Applies [palette] to the whole app: our own screens read it through [LocalPalette], and the
 * TV Material components (list items, buttons, cards) get a matching colour scheme.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlexTVAppTheme(palette: Palette, content: @Composable () -> Unit) {
    val p = palette
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent,
            secondary = p.accent, onSecondary = p.onAccent,
            background = p.background, onBackground = p.text,
            surface = p.background, onSurface = p.text,
            surfaceVariant = p.raised, onSurfaceVariant = p.muted,
            inverseSurface = p.text, inverseOnSurface = p.background,
            border = p.text, borderVariant = p.hairline,
            error = Color(0xFFFF6B6B),
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent,
            secondary = p.accent, onSecondary = p.onAccent,
            background = p.background, onBackground = p.text,
            surface = p.background, onSurface = p.text,
            surfaceVariant = p.raised, onSurfaceVariant = p.muted,
            inverseSurface = p.text, inverseOnSurface = p.background,
            border = p.text, borderVariant = p.hairline,
            error = Color(0xFFC62828),
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
