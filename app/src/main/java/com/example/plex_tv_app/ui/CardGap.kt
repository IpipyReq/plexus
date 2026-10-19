package com.example.plex_tv_app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.example.plex_tv_app.settings.LocalSettings

/** A gap between cards, scaled by the Card spacing setting (Customise / Home & browsing). */
@Composable
fun cardGap(base: Dp): Dp = base * LocalSettings.current.cardSpacing
