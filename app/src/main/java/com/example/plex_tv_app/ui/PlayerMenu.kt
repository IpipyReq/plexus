package com.example.plex_tv_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.AudioStream
import com.example.plex_tv_app.plex.SubtitleStream
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.settings.SkipMode
import com.example.plex_tv_app.settings.StreamQuality
import com.example.plex_tv_app.settings.SubtitleSize
import com.example.plex_tv_app.ui.theme.palette

enum class PlayerMenuPage { Main, Quality, Audio, Subtitles, Options }

val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

fun speedLabel(speed: Float): String = if (speed == 1f) "Normal" else "${speed.toString().removeSuffix(".0")}×"

/**
 * The player's settings panel (Down while watching): quality, audio, subtitles and playback
 * options. Picking a quality, audio or subtitle track applies it and closes the panel; Back goes
 * up a page, then closes.
 */
@Composable
fun PlayerMenu(
    startPage: PlayerMenuPage,
    quality: StreamQuality,
    onQuality: (StreamQuality) -> Unit,
    audios: List<AudioStream>,
    audio: AudioStream?,
    onAudio: (AudioStream) -> Unit,
    subtitles: List<SubtitleStream>,
    subtitle: SubtitleStream?,
    onSubtitle: (SubtitleStream?) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    onClose: () -> Unit,
) {
    var page by remember { mutableStateOf(startPage) }
    // Pages opened directly (e.g. the captions button) close with Back instead of going to Main.
    val back: () -> Unit = { if (page == PlayerMenuPage.Main || startPage != PlayerMenuPage.Main) onClose() else page = PlayerMenuPage.Main }

    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.25f) }
        BackHandler { back() }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .padding(24.dp)
                    .width(420.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface.copy(alpha = 0.96f))
                    .padding(20.dp),
            ) {
                key(page) {
                    val first = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        repeat(4) {
                            withFrameNanos { }
                            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
                        }
                    }
                    val title = when (page) {
                        PlayerMenuPage.Main -> "Settings"
                        PlayerMenuPage.Quality -> "Quality"
                        PlayerMenuPage.Audio -> "Audio"
                        PlayerMenuPage.Subtitles -> "Subtitles"
                        PlayerMenuPage.Options -> "Playback options"
                    }
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        fun focusIf(yes: Boolean) = if (yes) Modifier.focusRequester(first) else Modifier
                        when (page) {
                            PlayerMenuPage.Main -> {
                                PickerRow("Quality", false, focusIf(true), value = quality.label) { page = PlayerMenuPage.Quality }
                                PickerRow("Audio", false, Modifier, value = audio?.title ?: "Default") { page = PlayerMenuPage.Audio }
                                PickerRow("Subtitles", false, Modifier, value = subtitleShortName(subtitle)) { page = PlayerMenuPage.Subtitles }
                                PickerRow("Playback options", false, Modifier, value = "›") { page = PlayerMenuPage.Options }
                            }

                            PlayerMenuPage.Quality -> StreamQuality.entries.forEach { q ->
                                PickerRow(q.label, q == quality, focusIf(q == quality)) { onQuality(q) }
                            }

                            PlayerMenuPage.Audio -> {
                                if (audios.isEmpty()) {
                                    PickerRow("Default", true, focusIf(true)) { onClose() }
                                }
                                audios.forEach { a ->
                                    val selected = a.id == audio?.id
                                    PickerRow(a.title, selected, focusIf(selected || (audio == null && a == audios.first()))) { onAudio(a) }
                                }
                            }

                            PlayerMenuPage.Subtitles -> (listOf<SubtitleStream?>(null) + subtitles).forEach { option ->
                                val selected = option?.id == subtitle?.id
                                PickerRow(option?.title ?: "Off", selected, focusIf(selected)) { onSubtitle(option) }
                            }

                            PlayerMenuPage.Options -> PlaybackOptions(speed, onSpeed, focusIf(true))
                        }
                    }
                }
            }
        }
    }
}

/** Each row steps to its next value on OK. Speed is for this session; the rest are saved settings. */
@Composable
private fun PlaybackOptions(speed: Float, onSpeed: (Float) -> Unit, firstModifier: Modifier) {
    val settings = LocalSettings.current
    fun <T> next(list: List<T>, current: T): T = list[(list.indexOf(current) + 1) % list.size]

    PickerRow("Speed", false, firstModifier, value = speedLabel(speed)) { onSpeed(next(PlaybackSpeeds, speed)) }
    PickerRow("Subtitle size", false, Modifier, value = settings.subtitleSize.label) {
        settings.subtitleSize = next(SubtitleSize.entries, settings.subtitleSize)
    }
    PickerRow("Skip intro", false, Modifier, value = settings.skipIntro.label) {
        settings.skipIntro = next(SkipMode.entries, settings.skipIntro)
    }
    PickerRow("Skip credits", false, Modifier, value = settings.skipCredits.label) {
        settings.skipCredits = next(SkipMode.entries, settings.skipCredits)
    }
    PickerRow("Play next episode automatically", false, Modifier, value = if (settings.autoplayNext) "On" else "Off") {
        settings.autoplayNext = !settings.autoplayNext
    }
    Spacer(Modifier.height(6.dp))
    Text("OK changes a value. Speed is just for now; the others are saved to Settings.", fontSize = 12.sp, color = palette.muted)
}
