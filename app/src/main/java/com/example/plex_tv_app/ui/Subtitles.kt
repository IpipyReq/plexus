@file:OptIn(UnstableApi::class)

package com.example.plex_tv_app.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.SubtitleStream
import com.example.plex_tv_app.settings.AppSettings
import com.example.plex_tv_app.settings.BurnSubtitles
import com.example.plex_tv_app.settings.SubtitleMode
import com.example.plex_tv_app.settings.SubtitlePick
import com.example.plex_tv_app.ui.theme.palette
import java.util.Locale

// ---------- Choosing a subtitle ----------

/** Subtitle picks are remembered per show (so every episode follows) or per movie. */
fun subtitleScope(item: PlexItem): String =
    if (item.type == "episode") item.grandparentRatingKey ?: item.ratingKey else item.ratingKey

/** Turns a pick for this show/movie into a track of this particular file (null = none). */
private fun SubtitlePick.resolve(subs: List<SubtitleStream>): SubtitleStream? {
    if (off) return null
    subs.firstOrNull { it.id == streamId }?.let { return it }
    val inLanguage = subs.filter { it.isIn(language) }
    return inLanguage.firstOrNull { it.forced == forced } ?: inLanguage.firstOrNull()
}

/**
 * The subtitle to show when [item] starts: what you picked for this show or movie, otherwise
 * Settings → Subtitles (show them always / only for foreign audio / only when turned on).
 * Forced subtitles (just the foreign-language bits) are used whenever full ones aren't wanted.
 */
fun chooseSubtitle(settings: AppSettings, item: PlexItem, subs: List<SubtitleStream>, audioLanguage: String?): SubtitleStream? {
    if (subs.isEmpty()) return null
    settings.subtitlePick(subtitleScope(item))?.let { pick ->
        if (pick.off) return null
        pick.resolve(subs)?.let { return it }
    }
    val language = settings.subtitleLanguage.ifBlank { Locale.getDefault().language }
    val inLanguage = subs.filter { it.isIn(language) }
    val full = inLanguage.firstOrNull { !it.forced }
    val forced = inLanguage.firstOrNull { it.forced }
    val audioIsMine = audioLanguage == null || audioLanguageMatches(audioLanguage, language)
    return when (settings.subtitleMode) {
        SubtitleMode.Always -> full ?: forced
        SubtitleMode.Foreign -> if (audioIsMine) forced else full ?: forced
        SubtitleMode.Manual -> forced
        // Only a track inside the video file that the TV draws itself: never a separate
        // subtitle file, and never one that would make the server convert the video.
        SubtitleMode.AlwaysEmbedded -> {
            val playable = inLanguage.filter { !it.external && !needsBurnIn(settings, it) }
            playable.firstOrNull { !it.forced } ?: playable.firstOrNull()
        }
    }
}

private fun audioLanguageMatches(audio: String, language: String): Boolean =
    SubtitleStream(0, "", audio.takeIf { it.length != 3 }, audio.takeIf { it.length == 3 }, null, null, false, null, -1).isIn(language)

/** Remembers a pick made for [item]'s show or movie. */
fun rememberPick(settings: AppSettings, item: PlexItem, stream: SubtitleStream?) {
    val pick = if (stream == null) SubtitlePick.Off
    else SubtitlePick(off = false, streamId = stream.id, language = stream.languageTag ?: stream.languageCode, forced = stream.forced)
    settings.setSubtitlePick(subtitleScope(item), pick)
}

/** The player's format for a separate subtitle file, or null if the TV can't show it itself. */
fun sidecarMimeType(stream: SubtitleStream): String? = when (stream.codec?.lowercase()) {
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ttml" -> MimeTypes.APPLICATION_TTML
    else -> null
}

private val EmbeddedOnTv = setOf(
    "srt", "subrip", "ass", "ssa", "pgs", "hdmv_pgs_subtitle", "vobsub", "dvd_subtitle",
    "dvb_subtitle", "mov_text", "tx3g", "webvtt", "vtt", "eia_608", "cea_608", "ttml",
)

/**
 * Whether this subtitle needs the server to draw it into the picture (which means converting the
 * video): when the TV can't show that format, or Settings → Subtitles → Burn in says so.
 */
fun needsBurnIn(settings: AppSettings, stream: SubtitleStream?): Boolean {
    if (stream == null) return false
    return when (settings.burnSubtitles) {
        BurnSubtitles.Always -> true
        BurnSubtitles.ImageOnly -> stream.isImage || !tvCanShow(stream)
        BurnSubtitles.Auto -> !tvCanShow(stream)
    }
}

private fun tvCanShow(stream: SubtitleStream): Boolean =
    if (stream.external) sidecarMimeType(stream) != null else stream.codec?.lowercase() in EmbeddedOnTv

/** Short name for a subtitle on buttons, e.g. "English" or "Off". */
fun subtitleShortName(stream: SubtitleStream?): String {
    if (stream == null) return "Off"
    val tag = stream.languageTag ?: stream.languageCode
    val name = tag?.let { t ->
        runCatching { Locale.forLanguageTag(t).getDisplayLanguage(Locale.getDefault()) }.getOrNull()?.takeIf { it.isNotBlank() && it != t }
    } ?: stream.title.substringBefore(" (")
    return if (stream.forced) "$name (Forced)" else name
}

// ---------- The picker ----------

/**
 * A panel on the right listing Off and every subtitle. Picking one closes it; Back closes it
 * without changing anything.
 */
@Composable
fun SubtitlePicker(
    subtitles: List<SubtitleStream>,
    current: SubtitleStream?,
    onPick: (SubtitleStream?) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.25f) }
        BackHandler { onClose() }

        val currentFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(4) {
                withFrameNanos { }
                if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .padding(24.dp)
                    .width(400.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface.copy(alpha = 0.96f))
                    .padding(20.dp),
            ) {
                Text("Subtitles", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val options = listOf<SubtitleStream?>(null) + subtitles
                    options.forEach { option ->
                        val selected = option?.id == current?.id
                        PickerRow(
                            title = option?.title ?: "Off",
                            selected = selected,
                            modifier = if (selected) Modifier.focusRequester(currentFocus) else Modifier,
                        ) { onPick(option) }
                    }
                    if (subtitles.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("This video has no subtitles.", fontSize = 13.sp, color = palette.muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PickerRow(
    title: String,
    selected: Boolean,
    modifier: Modifier,
    value: String? = null, // shown on the right instead of the tick, e.g. the current setting
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.text.copy(alpha = 0.06f),
            contentColor = palette.text,
            focusedContainerColor = palette.text,
            focusedContentColor = palette.background,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(value, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            } else if (selected) {
                Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
