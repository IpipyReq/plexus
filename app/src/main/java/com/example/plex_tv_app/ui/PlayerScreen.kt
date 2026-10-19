@file:OptIn(UnstableApi::class)

package com.example.plex_tv_app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.plex_tv_app.plex.PreviewThumbnails
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.plex_tv_app.settings.Passthrough
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.AudioStream
import com.example.plex_tv_app.plex.PlaybackInfo
import com.example.plex_tv_app.plex.PlexItem
import com.example.plex_tv_app.plex.PlexServerApi
import com.example.plex_tv_app.plex.SubtitleStream
import com.example.plex_tv_app.settings.AppSettings
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.settings.SubtitleBackground
import com.example.plex_tv_app.settings.SubtitleColor
import com.example.plex_tv_app.settings.scale
import com.example.plex_tv_app.settings.RefreshRateMatch
import com.example.plex_tv_app.settings.SkipMode
import com.example.plex_tv_app.settings.StreamQuality
import com.example.plex_tv_app.ui.theme.PlexSerif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** What to play: a queue (one title, a shuffled collection...), where to start, and whether to resume. */
data class PlayRequest(val queue: List<PlexItem>, val index: Int = 0, val fromStart: Boolean = false)

private val Gold = Color(0xFFE5A00D)
private val Bone = Color(0xFFF3EFE7)
private val Mist = Color(0xFFB7BDC9)

/**
 * The video player. Plays the file as it is when the TV can (best quality); otherwise, or if
 * that fails, asks Plex to convert it on the fly. Remote controls:
 * OK / Play-Pause pauses and resumes, Left/Right jump back 10s / forward 30s (hold to keep going),
 * Up/Down show the controls, Back stops.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(api: PlexServerApi, request: PlayRequest, onExit: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()

    var queue by remember { mutableStateOf(request.queue) }
    var index by remember { mutableIntStateOf(request.index) }
    var fromStart by remember { mutableStateOf(request.fromStart) }
    val item = queue[index]

    var info by remember { mutableStateOf<PlaybackInfo?>(null) }
    var converting by remember { mutableStateOf(false) } // Plex is converting (transcoding) this one
    var session by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var barDismissed by remember { mutableStateOf(false) } // Back hid the bar (even while paused)
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var upNext by remember { mutableStateOf<PlexItem?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var skippedIntroFor by remember { mutableStateOf<String?>(null) }
    var creditsUpNextFor by remember { mutableStateOf<String?>(null) } // Up Next already offered over these credits
    var subtitle by remember { mutableStateOf<SubtitleStream?>(null) } // the one showing (null = off)
    var menuPage by remember { mutableStateOf<PlayerMenuPage?>(null) } // settings panel, when open
    var audio by remember { mutableStateOf<AudioStream?>(null) } // the audio track playing
    var qualityOverride by remember { mutableStateOf<StreamQuality?>(null) } // picked while watching
    var speed by remember { mutableStateOf(1f) }
    var textApplied by remember { mutableStateOf(false) } // subtitle track chosen for the loaded video
    var directFailed by remember { mutableStateOf(false) } // the TV couldn't play this file as-is
    // Settings → Playback → Preview pictures while seeking.
    var previews by remember { mutableStateOf<PreviewThumbnails?>(null) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) } // where you're seeking to, before it jumps there
    val scrubCommit = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }

    val player = remember {
        // Start after ~1 s of buffer instead of the default 2.5 s; re-buffer target 2 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context, renderersFor(context, settings)).setLoadControl(loadControl).build().apply {
            playWhenReady = true
            // Settings → Playback → Match frame rate.
            videoChangeFrameRateStrategy = if (settings.refreshRateMatch == RefreshRateMatch.Off) {
                C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            } else {
                C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            }
        }
    }

    fun current(): PlexItem = queue[index]

    fun currentQuality(): StreamQuality =
        qualityOverride ?: if (api.isLocal) settings.homeQuality else settings.remoteQuality

    /**
     * Start (or restart) the current title: direct play if allowed and wanted, else Plex converts
     * it. A subtitle the TV can't draw itself (or Settings say to burn in) also means converting,
     * with the server drawing the subtitle into the picture.
     */
    fun start(convert: Boolean, positionMs: Long) {
        val inf = info ?: return
        val sub = subtitle
        val quality = currentQuality()
        val direct = !convert && !directFailed && !needsBurnIn(settings, sub) &&
            settings.allowDirectPlay && quality == StreamQuality.Original && inf.partKey != null
        val oldSession = session.takeIf { converting }
        converting = !direct
        if (!direct) session = UUID.randomUUID().toString()
        textApplied = false
        val media = if (direct) {
            val builder = MediaItem.Builder().setUri(api.directPlayUrl(inf.partKey!!)).setMediaMetadata(metadataFor(current()))
            // A subtitle that's a separate file is loaded alongside the video.
            if (sub != null && sub.external) {
                sidecarMimeType(sub)?.let { mime ->
                    builder.setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(api.subtitleUrl(sub.key!!)))
                                .setMimeType(mime)
                                .setId("plex-${sub.id}")
                                .setLabel("plex-${sub.id}")
                                .setLanguage(sub.languageTag)
                                .build()
                        )
                    )
                }
            }
            builder.build()
        } else {
            val url = api.transcodeUrl(
                current(), session, quality.kbps.takeIf { it > 0 }, settings.allowDirectStream,
                burnSubtitles = if (sub != null) "burn" else "auto",
                subtitleSize = (settings.subtitleSize.scale * 100).roundToInt(),
                autoAdjustQuality = settings.autoAdjustQuality,
            )
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).setMediaMetadata(metadataFor(current())).build()
        }
        val partId = inf.partId
        val audioTrack = audio
        scope.launch {
            oldSession?.let { launch { api.stopTranscode(it) } }
            // The server converts with whichever audio and subtitle are selected on it, so tell it first.
            if (!direct && partId != null) {
                runCatching { api.selectSubtitle(partId, sub?.id) }
                audioTrack?.let { a -> runCatching { api.selectAudio(partId, a.id) } }
            }
            player.setMediaItem(media, positionMs)
            player.prepare()
            player.play()
        }
    }

    /** Turn the TV-drawn subtitle on (the chosen track) or off. Burned-in ones need nothing here. */
    fun applyText() {
        val sub = subtitle
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        fun labelOf(g: Tracks.Group): String = g.mediaTrackGroup.getFormat(0).let { "${it.id} ${it.label}" }
        val target = when {
            converting || sub == null -> null
            sub.external -> groups.firstOrNull { labelOf(it).contains("plex-${sub.id}") }
            else -> groups.filterNot { labelOf(it).contains("plex-") }.getOrNull(sub.embeddedOrdinal)
        }
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (target == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = params.build()
    }

    /**
     * Playing the file as-is: switch to the chosen audio track,
     * and turn the TV-drawn subtitle on or off. When the server converts, it already did both.
     */
    fun applyTracks() {
        val a = audio
        // Several tracks: pick ours explicitly (the file's own default may differ from the server's).
        if (!converting && a != null && (info?.audios?.size ?: 0) > 1) {
            val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(a.ordinal)
            if (group != null) {
                if (!group.isTrackSupported(0)) { // the TV can't play this one: have the server convert it
                    directFailed = true
                    start(convert = true, positionMs = player.currentPosition)
                    return
                }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
            }
        }
        applyText()
    }

    /** A subtitle picked while watching: remembered for this show/movie, and switched to now. */
    fun pickSubtitle(stream: SubtitleStream?) {
        menuPage = null
        if (stream?.id == subtitle?.id) return
        rememberPick(settings, current(), stream)
        subtitle = stream
        val instant = !converting && !needsBurnIn(settings, stream) && (stream == null || !stream.external)
        if (instant) applyText() // a track inside the file: switch straight away
        else start(convert = false, positionMs = player.currentPosition)
    }

    fun pickAudio(track: AudioStream) {
        menuPage = null
        if (track.id == audio?.id) return
        audio = track
        if (converting) {
            start(convert = false, positionMs = player.currentPosition)
        } else {
            val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(track.ordinal)
            if (group == null || !group.isTrackSupported(0)) {
                directFailed = group != null
                start(convert = group != null, positionMs = player.currentPosition)
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
            }
        }
    }

    fun pickQuality(q: StreamQuality) {
        menuPage = null
        if (q == currentQuality()) return
        qualityOverride = q
        start(convert = false, positionMs = player.currentPosition)
    }

    fun report(state: String) {
        val it = current()
        val d = duration.takeIf { d -> d > 0 } ?: info?.durationMs ?: return
        val t = player.currentPosition
        scope.launch { api.reportTimeline(it, state, t, d) }
    }

    fun showControls() {
        controlsVisible = true
        barDismissed = false
        lastInteraction = System.currentTimeMillis()
    }

    fun playItem(next: PlexItem, startAtBeginning: Boolean) {
        upNext = null
        if (queue.getOrNull(index + 1)?.ratingKey == next.ratingKey) {
            index += 1 // the next one in the queue (keeps the rest of the queue)
        } else {
            queue = listOf(next)
            index = 0
        }
        fromStart = startAtBeginning
    }

    /** End of a title: next in the queue, else the next episode (with a countdown), else leave. */
    fun finished() {
        // Up Next was already counting down over the credits: go straight to it.
        upNext?.let {
            report("stopped")
            playItem(it, true)
            return
        }
        report("stopped")
        if (index < queue.lastIndex) {
            index += 1
            fromStart = true
            return
        }
        val done = current()
        if (done.type != "episode" || !settings.autoplayNext) {
            onExit()
            return
        }
        scope.launch {
            val next = runCatching { api.nextEpisode(done) }.getOrNull()
            when {
                next == null -> onExit()
                settings.postPlayCountdown.seconds == 0 -> playItem(next, true)
                else -> {
                    player.pause()
                    countdown = settings.postPlayCountdown.seconds
                    upNext = next
                }
            }
        }
    }

    /** "Next" (voice or remote): the next title in the queue, else the next episode. */
    fun skipToNext() {
        if (upNext != null) {
            upNext?.let { playItem(it, true) }
            return
        }
        report("stopped")
        if (index < queue.lastIndex) {
            index += 1
            fromStart = true
            return
        }
        val done = current()
        if (done.type != "episode") return
        scope.launch {
            runCatching { api.nextEpisode(done) }.getOrNull()?.let { playItem(it, true) }
        }
    }

    // Voice and remote control: "Hey Google, pause / resume / skip forward 30 seconds / next
    // episode", the remote's media buttons, and the Google Home app's TV remote all go through
    // Android's media session, which drives this player.
    val nextAction = remember { arrayOfNulls<() -> Unit>(1) }
    nextAction[0] = ::skipToNext
    DisposableEffect(player) {
        val sessionPlayer = object : ForwardingPlayer(player) {
            @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    super.isCommandAvailable(command)

            @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
            override fun seekToNext() { nextAction[0]?.invoke() }
            @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
            override fun seekToNextMediaItem() { nextAction[0]?.invoke() }
        }
        val session = runCatching {
            MediaSession.Builder(context, sessionPlayer).setId("plexus-player").build()
        }.getOrNull()
        onDispose { session?.release() }
    }

    // Load and start each title in turn.
    LaunchedEffect(item.ratingKey, index) {
        error = null
        info = null
        position = 0L // don't let the last title's position trip this one's intro/credits
        buffering = true
        skippedIntroFor = null
        directFailed = false
        val inf = runCatching { api.playbackInfo(item) }.getOrElse {
            error = "Couldn't load this from the server."
            return@LaunchedEffect
        }
        info = inf
        previews = null
        scrubTarget = null
        // Preview pictures load in the background; seeking works as normal until they arrive.
        val part = inf.partId
        if (settings.seekPreviews && inf.hasPreviews && part != null) {
            launch { previews = api.previewThumbnails(part) }
        }
        // Audio: Settings → Audio → language if this file has it, else the server's default.
        val wanted = settings.audioLanguage
        audio = inf.audios.firstOrNull { wanted.isNotBlank() && languageMatches(it.languageTag, it.languageCode, wanted) }
            ?: inf.audios.firstOrNull { it.selected } ?: inf.audios.firstOrNull()
        val audioLang = audio?.let { it.languageTag ?: it.languageCode } ?: inf.audioLanguage
        subtitle = chooseSubtitle(settings, item, inf.subtitles, audioLang)
        start(convert = false, positionMs = if (fromStart) 0L else (item.viewOffset ?: 0L))
        showControls()
    }

    // Player events: fall back to Plex converting when the TV can't play the file (no picture,
    // no sound or an error), keep state for the controls, and move on at the end.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (!converting) {
                    directFailed = true
                    start(convert = true, positionMs = player.currentPosition)
                } else {
                    error = "This video couldn't be played (${e.errorCodeName})."
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!textApplied && !tracks.isEmpty) {
                    textApplied = true
                    applyTracks()
                }
                if (converting) return
                val audioMissing = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                val videoMissing = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                if (audioMissing || videoMissing) {
                    directFailed = true
                    start(convert = true, positionMs = player.currentPosition)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_ENDED) finished()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (info != null && upNext == null) report(if (playing) "playing" else "paused")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Leaving: tell Plex where you stopped, stop any conversion, and free the player.
    DisposableEffect(Unit) {
        onDispose {
            val it = current()
            val t = player.currentPosition
            val d = player.duration.takeIf { d -> d > 0 } ?: info?.durationMs ?: 0L
            val wasConverting = converting
            val s = session
            CoroutineScope(Dispatchers.IO).launch {
                withContext(NonCancellable) {
                    if (d > 0) api.reportTimeline(it, "stopped", t, d)
                    if (wasConverting) api.stopTranscode(s)
                }
            }
            player.release()
        }
    }

    // Clock for the controls; progress every 10s while playing.
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: info?.durationMs ?: 0L
            if (isPlaying && controlsVisible && System.currentTimeMillis() - lastInteraction > 4_000) controlsVisible = false
            tick++
            if (tick % 40 == 0 && isPlaying) report("playing")
            delay(250)
        }
    }

    // Up Next countdown.
    LaunchedEffect(upNext) {
        val next = upNext ?: return@LaunchedEffect
        while (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
        report("stopped")
        playItem(next, true)
    }

    // Intro and credits: skip button or automatic skip, per Settings → Playback.
    val intro = info?.markers?.firstOrNull { it.type == "intro" && position in it.startMs until it.endMs }
    val credits = info?.markers?.firstOrNull { it.type == "credits" && position in it.startMs until it.endMs }
    LaunchedEffect(intro?.startMs) {
        if (intro != null && settings.skipIntro == SkipMode.Auto && skippedIntroFor != item.ratingKey) {
            skippedIntroFor = item.ratingKey
            player.seekTo(intro.endMs)
        }
    }
    LaunchedEffect(credits?.startMs) {
        if (credits == null || upNext != null) return@LaunchedEffect
        if (settings.skipCredits == SkipMode.Auto) {
            finished()
            return@LaunchedEffect
        }
        // Up Next appears as the credits start (like the Plex and Netflix apps), counting down
        // while they keep playing. Back hides it and lets the credits play on.
        val done = current()
        if (done.type != "episode" || !settings.autoplayNext || creditsUpNextFor == done.ratingKey) return@LaunchedEffect
        creditsUpNextFor = done.ratingKey
        val next = queue.getOrNull(index + 1) ?: runCatching { api.nextEpisode(done) }.getOrNull() ?: return@LaunchedEffect
        if (current().ratingKey != done.ratingKey) return@LaunchedEffect // moved on meanwhile
        countdown = settings.postPlayCountdown.seconds.coerceAtLeast(5)
        upNext = next
    }
    val skipLabel = when {
        intro != null && settings.skipIntro == SkipMode.Button -> "Skip intro"
        credits != null && settings.skipCredits == SkipMode.Button -> if (current().type == "episode") "Skip credits" else "Skip credits"
        else -> null
    }
    fun skip() {
        when {
            intro != null -> player.seekTo(intro.endMs)
            credits != null -> finished()
        }
    }

    fun seekBy(ms: Long) {
        val end = (duration - 1_000).coerceAtLeast(0L)
        if (previews != null) {
            // With preview pictures: move a marker (showing the picture there) and only jump once
            // you stop pressing, so holding Left/Right glides through the pictures smoothly.
            val target = ((scrubTarget ?: player.currentPosition) + ms).coerceIn(0L, end)
            scrubTarget = target
            scrubCommit[0]?.cancel()
            scrubCommit[0] = scope.launch {
                delay(650)
                player.seekTo(target)
                position = target
                scrubTarget = null
            }
            showControls()
            return
        }
        val target = (player.currentPosition + ms).coerceIn(0L, end)
        player.seekTo(target)
        position = target
        showControls()
    }

    val barShowing = (controlsVisible || !isPlaying) && !barDismissed
    // Back: first hides the bar (back to just the video), then leaves.
    BackHandler {
        when {
            // Up Next over the credits (still playing): Back hides it and the credits carry on.
            upNext != null && player.isPlaying -> upNext = null
            upNext != null -> {
                upNext = null
                onExit()
            }
            barShowing && error == null -> {
                controlsVisible = false
                barDismissed = true
            }
            else -> onExit()
        }
    }

    // No navigation sounds while watching (seeking, pausing...).
    DisposableEffect(Unit) {
        NavSounds.muted = true
        onDispose { NavSounds.muted = false }
    }

    val focus = remember { FocusRequester() }
    // Focus the player at the start and whenever the subtitle panel closes.
    LaunchedEffect(menuPage == null) { if (menuPage == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    // Settings → Audio → Boost voices: lift the speech frequencies (and dip the bass a little).
    // Has no effect on surround sound passed through untouched to a soundbar/receiver.
    val boostVoices = settings.boostVoices
    DisposableEffect(player, boostVoices) {
        var eq: android.media.audiofx.Equalizer? = null
        fun attach(sessionId: Int) {
            eq?.release()
            eq = null
            if (!boostVoices || sessionId == C.AUDIO_SESSION_ID_UNSET) return
            eq = runCatching { voiceEqualizer(sessionId) }.getOrNull()
        }
        val listener = object : Player.Listener {
            @androidx.annotation.OptIn(markerClass = [UnstableApi::class]) // overriding an "unstable" Media3 callback
            override fun onAudioSessionIdChanged(audioSessionId: Int) = attach(audioSessionId)
        }
        player.addListener(listener)
        attach(player.audioSessionId)
        onDispose {
            player.removeListener(listener)
            eq?.release()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val held = e.nativeKeyEvent.repeatCount
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        if (held > 0) return@onPreviewKeyEvent true
                        when {
                            upNext != null -> upNext?.let { playItem(it, true) }
                            skipLabel != null && !controlsVisible -> skip()
                            player.isPlaying -> player.pause()
                            else -> player.play()
                        }
                        showControls()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); showControls(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showControls(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        seekBy(-10_000L * (1 + held / 8)) // hold to go faster
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        seekBy(30_000L * (1 + held / 8))
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { showControls(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> {
                        if (held == 0 && upNext == null && info != null) menuPage = PlayerMenuPage.Main
                        showControls()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> {
                        if (held == 0 && upNext == null && info != null) menuPage = PlayerMenuPage.Subtitles
                        showControls()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // The picture.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { view -> view.subtitleView?.let { styleSubtitles(it, settings) } },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Box(Modifier.align(Alignment.Center)) { Spinner(40) }
        }
        error?.let { message ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Bone, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("Press Back to return.", color = Mist, fontSize = 14.sp)
            }
        }

        // Controls
        AnimatedVisibility(
            visible = barShowing,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val shownPosition = scrubTarget ?: position
            val frame = scrubTarget?.let { t -> previews?.frameAt(t) }
            val preview = remember(frame) {
                frame?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
            }
            Controls(
                current(), shownPosition, duration, player.bufferedPosition, isPlaying, converting, subtitleShortName(subtitle),
                preview = if (scrubTarget != null) preview else null,
            )
        }

        // Skip intro / credits
        AnimatedVisibility(
            visible = skipLabel != null && upNext == null,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = if (barShowing) 140.dp else 56.dp),
        ) {
            Text(
                skipLabel ?: "",
                color = Color(0xFF111111),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Bone)
                    .padding(horizontal = 22.dp, vertical = 11.dp),
            )
        }

        // Up next
        upNext?.let { next ->
            UpNextCard(api, next, countdown, Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 56.dp), watching = isPlaying)
        }
    }

    menuPage?.let { page ->
        PlayerMenu(
            startPage = page,
            quality = currentQuality(),
            onQuality = ::pickQuality,
            audios = info?.audios.orEmpty(),
            audio = audio,
            onAudio = ::pickAudio,
            subtitles = info?.subtitles.orEmpty(),
            subtitle = subtitle,
            onSubtitle = ::pickSubtitle,
            speed = speed,
            onSpeed = { speed = it },
            onClose = { menuPage = null },
        )
    }
}

/**
 * Settings → Audio → Passthrough: which surround formats go to the TV/soundbar untouched.
 * Detect (default) asks the TV. Off decodes everything to plain sound (the server converts what
 * the TV can't decode). Manual sends only the formats you ticked.
 */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun renderersFor(context: android.content.Context, settings: AppSettings): DefaultRenderersFactory {
    val mode = settings.passthrough
    if (mode == Passthrough.Auto) return DefaultRenderersFactory(context)
    val encodings = buildList {
        add(C.ENCODING_PCM_16BIT)
        if (mode == Passthrough.Manual) {
            if (settings.passAc3) add(C.ENCODING_AC3)
            if (settings.passEac3) { add(C.ENCODING_E_AC3); add(C.ENCODING_E_AC3_JOC) }
            if (settings.passDts) add(C.ENCODING_DTS)
            if (settings.passDtsHd) add(C.ENCODING_DTS_HD)
            if (settings.passTrueHd) add(C.ENCODING_DOLBY_TRUEHD)
        }
    }.toIntArray()
    val capabilities = AudioCapabilities(encodings, 8)
    return object : DefaultRenderersFactory(context) {
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class]) // overriding an "unstable" Media3 method
        override fun buildAudioSink(
            context: android.content.Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(capabilities)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}

/** An equaliser on the player's sound that makes dialogue stand out: speech bands up, deep bass down. */
private fun voiceEqualizer(sessionId: Int): android.media.audiofx.Equalizer {
    val eq = android.media.audiofx.Equalizer(0, sessionId)
    val (min, max) = eq.bandLevelRange.let { it[0] to it[1] } // millibels
    for (band in 0 until eq.numberOfBands) {
        val centreHz = eq.getCenterFreq(band.toShort()) / 1000 // milliHertz -> Hz
        val level = when {
            centreHz in 1_000..4_000 -> (max * 0.6).toInt() // the clarity of speech
            centreHz in 500 until 1_000 -> (max * 0.3).toInt()
            centreHz < 150 -> (min * 0.3).toInt() // rumble and music bass
            else -> 0
        }.coerceIn(min.toInt(), max.toInt())
        eq.setBandLevel(band.toShort(), level.toShort())
    }
    eq.enabled = true
    return eq
}

/** What the TV (and Google Home) show as "now playing". */
private fun metadataFor(item: PlexItem): MediaMetadata = MediaMetadata.Builder()
    .setTitle(if (item.type == "episode") listOfNotNull(episodeCode(item), item.title).joinToString(" · ") else item.title)
    .setArtist(if (item.type == "episode") item.grandparentTitle else item.year?.toString())
    .setDisplayTitle(item.title)
    .build()

/** True when a track's language ([tag] like "en", or 3-letter [code] like "eng") is [want] (e.g. "en"). */
private fun languageMatches(tag: String?, code: String?, want: String): Boolean =
    SubtitleStream(0, "", tag, code, null, null, false, null, -1).isIn(want)

/** Settings → Subtitles: size, colour and background of subtitles the TV draws. */
private fun styleSubtitles(view: SubtitleView, settings: AppSettings) {
    val text = if (settings.subtitleColor == SubtitleColor.Yellow) 0xFFFFE14D.toInt() else android.graphics.Color.WHITE
    val background = when (settings.subtitleBackground) {
        SubtitleBackground.None -> android.graphics.Color.TRANSPARENT
        SubtitleBackground.Translucent -> 0x99000000.toInt()
        SubtitleBackground.Solid -> android.graphics.Color.BLACK
    }
    val edge = if (settings.subtitleBackground == SubtitleBackground.None) CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else CaptionStyleCompat.EDGE_TYPE_NONE
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setStyle(CaptionStyleCompat(text, background, android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, null))
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleSize.scale)
}

@Composable
private fun Controls(
    item: PlexItem,
    position: Long,
    duration: Long,
    buffered: Long,
    isPlaying: Boolean,
    converting: Boolean,
    subtitleName: String,
    preview: ImageBitmap? = null, // the picture at the spot you're seeking to
) {
    Box(Modifier.fillMaxSize()) {
        // Shading top and bottom so the text reads over any picture.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
        )

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 56.dp, vertical = 40.dp)) {
            val isEpisode = item.type == "episode"
            Text(
                if (isEpisode) item.grandparentTitle ?: item.title else item.title,
                fontFamily = PlexSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isEpisode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    episodeCode(item)?.let {
                        Text(it, color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(item.title, color = Bone, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))

            // Progress: watched (gold), buffered (light), the rest (faint).
            val total = duration.coerceAtLeast(1L)
            // The seek preview floats above the bar, over the spot you're seeking to.
            if (preview != null) {
                val fraction = (position.toFloat() / total).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 38.dp) // line up with the bar (after the play/pause icon)
                        .layout { measurable, constraints ->
                            val thumb = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(constraints.maxWidth, 0) { // takes no room: drawn above the bar
                                val x = (fraction * constraints.maxWidth - thumb.width / 2f)
                                    .coerceIn(0f, (constraints.maxWidth - thumb.width).toFloat().coerceAtLeast(0f))
                                thumb.place(x.roundToInt(), -thumb.height - 10.dp.roundToPx())
                            }
                        },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Image(
                            preview,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .width(240.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(clock(position), color = Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play/pause state sits at the left edge of the track.
                Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                    Text(if (isPlaying) "❚❚" else "▶", color = Bone, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x40FFFFFF))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((buffered.toFloat() / total).coerceIn(0f, 1f)).background(Color(0x59FFFFFF)))
                    Box(Modifier.fillMaxHeight().fillMaxWidth((position.toFloat() / total).coerceIn(0f, 1f)).background(Gold))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(position), color = Bone, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                if (duration > 0) {
                    val ends = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                        .format(Date(System.currentTimeMillis() + (duration - position)))
                    Text("Ends at $ends", color = Mist, fontSize = 13.sp)
                    Spacer(Modifier.width(18.dp))
                    Text("-${clock(duration - position)}", color = Bone, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                (if (converting) "Converted by your server" else "Playing original") +
                    "     ◀ 10s   ▶ 30s   OK to pause   ▼ Settings   Subtitles: $subtitleName",
                color = Mist,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun UpNextCard(api: PlexServerApi, next: PlexItem, countdown: Int, modifier: Modifier, watching: Boolean = false) {
    Row(
        modifier
            .width(460.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6101218))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            api.imageUrl(next.thumb ?: next.art, 320, 180),
            Modifier.width(170.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Up next in $countdown", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(episodeCode(next), next.title).joinToString("  "),
                color = Bone,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(if (watching) "OK to play now, Back to keep watching" else "OK to play now, Back to stop", color = Mist, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun Spinner(sizeDp: Int) {
    val spin = rememberInfiniteTransition(label = "playerSpinner")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(sizeDp.dp)) {
        drawArc(Gold, angle, 270f, useCenter = false, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 1:02:03 or 12:34. */
private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
