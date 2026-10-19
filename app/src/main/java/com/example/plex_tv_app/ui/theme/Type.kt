package com.example.plex_tv_app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography
import com.example.plex_tv_app.R

/** IBM Plex Serif, bundled in res/font. Used for titles and headings. */
val PlexSerif = FontFamily(
    Font(R.font.ibm_plex_serif_light, FontWeight.Light),
    Font(R.font.ibm_plex_serif_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_serif_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_serif_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_serif_bold, FontWeight.Bold),
)

private val base = Typography()

/** Headings in Plex Serif; small text (card labels, details, buttons) stays in the TV's sans. */
@OptIn(ExperimentalTvMaterial3Api::class)
val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = PlexSerif),
    displayMedium = base.displayMedium.copy(fontFamily = PlexSerif),
    displaySmall = base.displaySmall.copy(fontFamily = PlexSerif),
    headlineLarge = base.headlineLarge.copy(fontFamily = PlexSerif),
    headlineMedium = base.headlineMedium.copy(fontFamily = PlexSerif),
    headlineSmall = base.headlineSmall.copy(fontFamily = PlexSerif),
    titleLarge = base.titleLarge.copy(fontFamily = PlexSerif),
    titleMedium = base.titleMedium,
    titleSmall = base.titleSmall,
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge,
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall,
)
