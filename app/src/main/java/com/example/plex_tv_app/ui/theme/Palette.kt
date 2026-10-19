package com.example.plex_tv_app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The handful of colours every screen is drawn from. Each theme is one of these.
 *
 * @param background the page colour; artwork on Home fades into it
 * @param surface floating panels (menus, pickers)
 * @param raised placeholders and fields, a step up from the background
 * @param text titles and body text
 * @param muted secondary text
 * @param accent highlights: progress, the selected marker, stars
 */
data class Palette(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val isDark: Boolean = true,
) {
    /** Thin lines and badge borders. */
    val hairline: Color get() = text.copy(alpha = 0.2f)

    /** Background of a highlighted row. */
    val focusTint: Color get() = text.copy(alpha = 0.14f)

    /** Text drawn on top of [accent]. */
    val onAccent: Color get() = if (accent.luminance() > 0.5f) Color(0xFF111111) else Color.White
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Every theme on offer. "System" and "Birthday party" follow the TV's light/dark setting. */
enum class ThemeChoice(val label: String, val blurb: String, val palette: Palette?) {
    System("System", "Light or dark, following the TV", null),
    Dark(
        "Dark", "Cinema blue-black with Plex gold",
        Palette(Color(0xFF080B12), Color(0xFF141822), Color(0xFF232733), Color(0xFFF3EFE7), Color(0xFFA7AFBF), Color(0xFFE5A00D)),
    ),
    Light(
        "Light", "Soft daylight grey",
        Palette(Color(0xFFF4F2EE), Color(0xFFFFFFFF), Color(0xFFE3E0DA), Color(0xFF15171C), Color(0xFF5C6370), Color(0xFFB87400), isDark = false),
    ),
    Oled(
        "OLED Black", "True black, for OLED screens",
        Palette(Color(0xFF000000), Color(0xFF0B0B0B), Color(0xFF171717), Color(0xFFFFFFFF), Color(0xFF9A9A9A), Color(0xFFE5A00D)),
    ),
    Midnight(
        "Midnight", "Deep navy with ice blue",
        Palette(Color(0xFF0A1022), Color(0xFF121A33), Color(0xFF1E2847), Color(0xFFE8EEFF), Color(0xFF8F9BC2), Color(0xFF6FA8FF)),
    ),
    Frost(
        "Frost", "Cool slate and pale cyan",
        Palette(Color(0xFF1E232D), Color(0xFF262C38), Color(0xFF323A49), Color(0xFFECEFF4), Color(0xFFA3ACBE), Color(0xFF88C0D0)),
    ),
    Lagoon(
        "Lagoon", "Dark teal with bright aqua",
        Palette(Color(0xFF061416), Color(0xFF0D2023), Color(0xFF153034), Color(0xFFE6F6F5), Color(0xFF8DB3B0), Color(0xFF2EC4B6)),
    ),
    Forest(
        "Forest", "Pine green and moss",
        Palette(Color(0xFF0B1410), Color(0xFF13201A), Color(0xFF1E3027), Color(0xFFEAF2EA), Color(0xFF94AA9A), Color(0xFF9CCB6B)),
    ),
    Ember(
        "Ember", "Warm charcoal and orange",
        Palette(Color(0xFF140E0B), Color(0xFF201713), Color(0xFF30231C), Color(0xFFF6EDE6), Color(0xFFB49F92), Color(0xFFFF7A3D)),
    ),
    Rose(
        "Rosé", "Plum with soft pink",
        Palette(Color(0xFF170D12), Color(0xFF24141C), Color(0xFF35202A), Color(0xFFFBECEF), Color(0xFFC09AA6), Color(0xFFFF8FAB)),
    ),
    Amethyst(
        "Amethyst", "Violet night with lavender",
        Palette(Color(0xFF120D1C), Color(0xFF1C1529), Color(0xFF2A2140), Color(0xFFF0EAFB), Color(0xFFA79BBF), Color(0xFFB48CFF)),
    ),
    Paper(
        "Paper", "Warm cream with terracotta",
        Palette(Color(0xFFF1E9DA), Color(0xFFFAF4E8), Color(0xFFE2D6C0), Color(0xFF2B2219), Color(0xFF6E6152), Color(0xFFB5542D), isDark = false),
    ),
    HighContrast(
        "High contrast", "Black, white and yellow for easy reading",
        Palette(Color(0xFF000000), Color(0xFF000000), Color(0xFF1C1C1C), Color(0xFFFFFFFF), Color(0xFFE6E6E6), Color(0xFFFFE500)),
    ),
    // Looks exactly like System; the surprise is the confetti on every press.
    Party("Birthday party", "Surprise", null);

    /** This theme's colours. System (and Birthday party) follow the TV: Dark or Light. */
    fun resolve(systemDark: Boolean): Palette =
        palette ?: if (systemDark) Dark.palette!! else Light.palette!!
}

/** The palette in use (or being previewed). */
val LocalPalette = staticCompositionLocalOf { ThemeChoice.Dark.palette!! }

/** Shorthand for the current palette inside composables. */
val palette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current
