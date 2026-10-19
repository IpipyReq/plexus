package com.example.plex_tv_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.example.plex_tv_app.R
import com.example.plex_tv_app.ui.theme.PlexSerif
import kotlinx.coroutines.delay

/** Same colour as the system splash (res/values/colors.xml), so the hand-over is seamless. */
private val SplashBackground = Color(0xFF0E0F13)

/**
 * The launch splash: the Plexus mark where the system splash left it, the name rising in
 * underneath, then the whole thing fades away to reveal the app (which loads behind it).
 */
@Composable
fun LaunchSplash(onDone: () -> Unit) {
    val fade = remember { Animatable(1f) }
    val name = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        name.animateTo(1f, tween(450, delayMillis = 120))
        delay(650)
        fade.animateTo(0f, tween(380))
        onDone()
    }
    val rise = with(LocalDensity.current) { 12.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(SplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        // 288dp, like Android's splash icon, so the mark doesn't jump when the system splash ends.
        Image(painterResource(R.drawable.plexus_mark), contentDescription = null, modifier = Modifier.size(288.dp))
        Text(
            "Plexus",
            fontFamily = PlexSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            color = Color(0xFFF3EFE7),
            modifier = Modifier
                .offset(y = 118.dp)
                .graphicsLayer {
                    alpha = name.value
                    translationY = (1f - name.value) * rise
                },
        )
    }
}
