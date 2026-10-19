package com.example.plex_tv_app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.plex_tv_app.ui.theme.ThemeChoice
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Gives any screen access to the app's settings. */
val LocalSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings not provided") }

/** A choice with a human-readable name, shown in the settings pickers. */
interface Labeled {
    val label: String
}

enum class ClockMode(override val label: String) : Labeled {
    Hidden("Hidden"), System("Follow the TV's setting"), H12("12-hour"), H24("24-hour")
}

enum class NavSoundMode(override val label: String) : Labeled {
    System("Follow the TV's setting"), On("Always on"), Off("Off")
}

enum class RefreshInterval(val minutes: Int, override val label: String) : Labeled {
    Off(0, "Off"), M1(1, "Every minute"), M5(5, "Every 5 minutes"), M15(15, "Every 15 minutes"), M30(30, "Every 30 minutes")
}

/** What selecting a movie does. */
enum class MovieSelect(override val label: String) : Labeled {
    Details("Show its details"), Play("Play it")
}

/** What selecting an episode does. */
enum class EpisodeSelect(override val label: String) : Labeled {
    Season("Open its season"), Play("Play it")
}

/** How "More in X Collection" rows appear when a collection has both movies and shows. */
enum class CollectionRows(override val label: String) : Labeled {
    Combined("One row: More in X"), Split("Separate rows for movies and TV")
}

enum class StreamQuality(val kbps: Int, override val label: String) : Labeled {
    Original(0, "Original"),
    Q40(40_000, "40 Mbps, 4K"),
    Q20(20_000, "20 Mbps, 1080p"),
    Q12(12_000, "12 Mbps, 1080p"),
    Q8(8_000, "8 Mbps, 1080p"),
    Q4(4_000, "4 Mbps, 720p"),
    Q2(2_000, "2 Mbps, 720p"),
}

enum class RefreshRateMatch(override val label: String) : Labeled {
    Off("Off"), Seamless("Only when seamless"), Always("Always")
}

enum class Countdown(val seconds: Int, override val label: String) : Labeled {
    Off(0, "Off"), S5(5, "5 seconds"), S10(10, "10 seconds"), S15(15, "15 seconds"), S30(30, "30 seconds")
}

/** How the side nav looks (Settings → Appearance → Side bar). */
enum class NavStyle(override val label: String, val blurb: String) : Labeled {
    Whisper("Whisper", "Small, quiet icons; your page is bright with an underline"),
    GoldRail("Gold rail", "No strip; a thin accent bar marks your page, serif labels when open"),
    FilmStrip("Film strip", "A strip of film down the edge, each page a frame"),
    WhisperNeon("Whisper neon", "Whisper, with every page glowing in its own neon colour"),
    Classic("Classic", "The original strip with rounded highlights"),
}

/** What's written under each card. */
enum class DiscoverSource(override val label: String) : Labeled {
    Plex("Plex (uses your Plex account)"), Tmdb("TMDB (needs a free key)")
}

enum class CardText(override val label: String) : Labeled {
    Full("Title and details"), Title("Title only"), None("No text")
}

enum class SkipMode(override val label: String) : Labeled {
    Off("Off"), Button("Show a Skip button"), Auto("Skip automatically")
}

enum class Passthrough(override val label: String) : Labeled {
    Auto("Detect from TV"), Off("Off, convert to PCM"), Manual("Choose formats")
}

enum class SubtitleMode(override val label: String) : Labeled {
    Manual("Only when I turn them on"),
    Foreign("When audio isn't in my language"),
    Always("Always"),
    AlwaysEmbedded("Always, if they're inside the video"),
}

enum class SubtitleSize(override val label: String) : Labeled {
    Small("Small"), Normal("Normal"), Large("Large"), ExtraLarge("Extra large")
}

enum class SubtitleColor(override val label: String) : Labeled { White("White"), Yellow("Yellow") }

enum class SubtitleBackground(override val label: String) : Labeled {
    None("None"), Translucent("See-through"), Solid("Solid black")
}

enum class BurnSubtitles(override val label: String) : Labeled {
    Auto("Automatic"), ImageOnly("Only image formats (PGS, VobSub)"), Always("Always")
}

enum class InsecureConnections(override val label: String) : Labeled {
    Never("Never"), SameNetwork("On my home network"), Always("Always")
}

/** Languages offered for audio and subtitles, as (ISO code, name). "" means the title's original. */
val Languages = listOf(
    "" to "Original language",
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "hi" to "Hindi",
    "nl" to "Dutch", "sv" to "Swedish", "ru" to "Russian", "ar" to "Arabic",
)

/**
 * Every app setting, saved on this TV. Each one is also Compose state, so screens that read a
 * setting update the moment it changes.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val all = mutableMapOf<String, Pref<*>>() // by key

    // Appearance
    var theme by enumPref("theme", ThemeChoice.System)

    /** A theme being tried out in the theme picker (not saved). Shown instead of [theme] while set. */
    var previewTheme by mutableStateOf<ThemeChoice?>(null)

    /** The side nav's look. */
    var navStyle by enumPref("nav_style", NavStyle.Whisper)
    /** A side nav look being tried in the picker (not saved), and whether to show it opened. */
    var previewNavStyle by mutableStateOf<NavStyle?>(null)
    var previewNavOpen by mutableStateOf(false)

    /** Size of all text in the app, including title logos (1.0 = normal). */
    // Text size is per profile ("text_scale_<profile id>"), or one for the whole TV
    // ("text_scale_all") when "Separate sizes for each profile" is off. The app says which profile
    // is in use with useProfile(); until then (and for a new profile) it's the default.
    private var textProfile = ""
    private var textScaleState by mutableStateOf(DEFAULT_TEXT_SCALE)
    private var sizesPerProfilePref by boolPref("sizes_per_profile", true)

    private fun textKey() = if (sizesPerProfilePref) "text_scale_$textProfile" else "text_scale_all"
    private fun spacingKey() = if (sizesPerProfilePref) "card_spacing_$textProfile" else "card_spacing_all"
    private var spacingState by mutableStateOf(DEFAULT_CARD_SPACING)

    /** On: each profile has its own card and text size. Off: one of each for everyone on this TV. */
    var sizesPerProfile: Boolean
        get() = sizesPerProfilePref
        set(value) {
            if (value == sizesPerProfilePref) return
            val current = textScaleState
            val currentSpacing = spacingState
            sizesPerProfilePref = value
            // Carry the size you're using over, so switching doesn't make the text jump.
            if (!prefs.contains(textKey())) prefs.edit().putFloat(textKey(), current).apply()
            textScaleState = prefs.getFloat(textKey(), DEFAULT_TEXT_SCALE)
            if (!prefs.contains(spacingKey())) prefs.edit().putFloat(spacingKey(), currentSpacing).apply()
            spacingState = prefs.getFloat(spacingKey(), DEFAULT_CARD_SPACING)
        }

    /** Switch the per-profile settings (text size) to this profile's. */
    fun useProfile(profileId: Long) {
        val key = profileId.toString()
        if (key == textProfile) return
        textProfile = key
        textScaleState = prefs.getFloat(textKey(), DEFAULT_TEXT_SCALE)
        spacingState = prefs.getFloat(spacingKey(), DEFAULT_CARD_SPACING)
    }

    /** The gap between cards, as a multiple of the usual gap (per profile, like text size). */
    var cardSpacing: Float
        get() = spacingState
        set(value) {
            spacingState = value
            prefs.edit().putFloat(spacingKey(), value).apply()
        }

    var textScale: Float
        get() = textScaleState
        set(value) {
            textScaleState = value
            prefs.edit().putFloat(textKey(), value).apply()
        }

    // General
    var clockMode by enumPref("clock_mode", ClockMode.System)
    var refreshInterval by enumPref("refresh_interval", RefreshInterval.M5)
    var navSounds by enumPref("nav_sounds", NavSoundMode.System)

    // Home & browsing
    var backdropMotion by boolPref("backdrop_motion", true)
    // Minimal look
    var homeCardText by enumPref("home_card_text", CardText.Full) // Home and library Recommended rows
    var cardText by enumPref("card_text", CardText.Full) // everywhere else (grids, search, title pages)
    var showRowTitles by boolPref("show_row_titles", true)
    var showHomeSummary by boolPref("show_home_summary", true)
    var peekNextRow by boolPref("peek_next_row", true)
    var showWatchedBadges by boolPref("show_watched_badges", true)
    var showProgressBars by boolPref("show_progress_bars", true)
    var hideNavBar by boolPref("hide_nav_bar", false) // no icon strip; Left at the start of a row still opens it
    var navInDetails by boolPref("nav_in_details", false) // the side nav on movie/show/episode pages too
    var blurUnwatched by boolPref("blur_unwatched", false)
    var hideUnwatchedSummaries by boolPref("hide_unwatched_summaries", false)
    var movieSelect by enumPref("movie_select", MovieSelect.Details)
    var episodeSelect by enumPref("episode_select", EpisodeSelect.Season)
    var collectionRows by enumPref("collection_rows", CollectionRows.Combined)
    var showCriticReviews by boolPref("show_critic_reviews", true)
    var showCommunityReviews by boolPref("show_community_reviews", true)

    // Playback
    var homeQuality by enumPref("home_quality", StreamQuality.Original)
    var remoteQuality by enumPref("remote_quality", StreamQuality.Q12)
    var autoAdjustQuality by boolPref("auto_adjust_quality", true)
    var allowDirectPlay by boolPref("allow_direct_play", true)
    var allowDirectStream by boolPref("allow_direct_stream", true)
    var refreshRateMatch by enumPref("refresh_rate_match", RefreshRateMatch.Seamless)
    var seekPreviews by boolPref("seek_previews", false) // pictures while seeking (needs the server's preview thumbnails)
    var autoplayNext by boolPref("autoplay_next", true)
    var postPlayCountdown by enumPref("post_play_countdown", Countdown.S10)
    var skipIntro by enumPref("skip_intro", SkipMode.Button)
    var skipCredits by enumPref("skip_credits", SkipMode.Button)

    // Audio
    var passthrough by enumPref("passthrough", Passthrough.Auto)
    var passAc3 by boolPref("pass_ac3", true)
    var passEac3 by boolPref("pass_eac3", true)
    var passDts by boolPref("pass_dts", false)
    var passDtsHd by boolPref("pass_dtshd", false)
    var passTrueHd by boolPref("pass_truehd", false)
    var boostVoices by boolPref("boost_voices", false)
    var audioLanguage by stringPref("audio_language", "")

    // Subtitles
    var subtitleMode by enumPref("subtitle_mode", SubtitleMode.Foreign)
    var subtitleLanguage by stringPref("subtitle_language", "en")
    var subtitleSize by enumPref("subtitle_size", SubtitleSize.Normal)
    var subtitleColor by enumPref("subtitle_color", SubtitleColor.White)
    var subtitleBackground by enumPref("subtitle_background", SubtitleBackground.None)
    var burnSubtitles by enumPref("burn_subtitles", BurnSubtitles.Auto)

    // Server & network
    var allowRelay by boolPref("allow_relay", true)
    var insecureConnections by enumPref("insecure_connections", InsecureConnections.SameNetwork)

    /** Puts every setting back to its default. */
    fun resetAll() {
        all.values.forEach { it.reset() }
        textScale = DEFAULT_TEXT_SCALE
        cardSpacing = DEFAULT_CARD_SPACING
    }

    /** Puts one page's settings back to their defaults (see [Group]). */
    fun reset(keys: List<String>) {
        keys.forEach { all[it]?.reset() }
        if ("text_scale" in keys) textScale = DEFAULT_TEXT_SCALE // per profile, so not in [all]
        if ("card_spacing" in keys) cardSpacing = DEFAULT_CARD_SPACING
    }

    /** Which saved keys belong to each Settings page, for its "Reset to defaults" button. */
    object Group {
        val Appearance = listOf("theme", "text_scale", "nav_style")
        val General = listOf("clock_mode", "refresh_interval", "nav_sounds")
        val Home = listOf(
            "card_spacing",
            "backdrop_motion", "blur_unwatched", "hide_unwatched_summaries", "movie_select", "episode_select",
            "collection_rows", "show_critic_reviews", "show_community_reviews", "hide_nav_bar", "nav_in_details",
            "home_card_text", "card_text", "show_row_titles", "show_home_summary", "peek_next_row",
            "show_watched_badges", "show_progress_bars",
        )
        val Playback = listOf(
            "home_quality", "remote_quality", "auto_adjust_quality", "allow_direct_play", "allow_direct_stream",
            "refresh_rate_match", "autoplay_next", "post_play_countdown", "skip_intro", "skip_credits",
            "seek_previews",
        )
        val Audio = listOf("passthrough", "pass_ac3", "pass_eac3", "pass_dts", "pass_dtshd", "pass_truehd", "boost_voices", "audio_language")
        val Subtitles = listOf("subtitle_mode", "subtitle_language", "subtitle_size", "subtitle_color", "subtitle_background", "burn_subtitles")
        val Server = listOf("allow_relay", "insecure_connections")
    }

    // Sonarr and Radarr (Settings → Server, owner only). Not part of any "reset to defaults".
    var sonarrHost by stringPref("sonarr_host", "")
    var sonarrPort by stringPref("sonarr_port", "8989")
    var sonarrKey by stringPref("sonarr_key", "")
    var sonarrProfile by stringPref("sonarr_profile", "")
    var sonarrRoot by stringPref("sonarr_root", "")
    var radarrHost by stringPref("radarr_host", "")
    var radarrPort by stringPref("radarr_port", "7878")
    var radarrKey by stringPref("radarr_key", "")
    var radarrProfile by stringPref("radarr_profile", "")
    var radarrRoot by stringPref("radarr_root", "")
    val sonarrUrl: String get() = arrUrl(sonarrHost, sonarrPort)
    val radarrUrl: String get() = arrUrl(radarrHost, radarrPort)

    // TMDB, for Discover and "More like this". Owner sets it up; everyone on this TV sees the results.
    var tmdbKey by stringPref("tmdb_key", "")
    var discoverSource by enumPref("discover_source", DiscoverSource.Plex)
    /** Is there somewhere to get Discover and "More like this" from? */
    val discoverReady: Boolean get() = discoverSource == DiscoverSource.Plex || tmdbKey.isNotBlank()
    var showDiscover by boolPref("show_discover", true)
    var moreLikeThis by boolPref("more_like_this", true)
    /** Off: only the server's owner gets Discover, adding titles and download status. On: everyone on this TV. */
    var shareDiscover by boolPref("share_discover", false)

    // In-app updates (Settings → About).
    var autoCheckUpdates by boolPref("auto_check_updates", true)
    var skippedUpdate by stringPref("skipped_update", "")
    /** May this profile use Discover, add titles, and see/act on downloads? */
    fun canDownload(owner: Boolean): Boolean = owner || shareDiscover
    /** Episodes still to air, at the end of a show's latest season (needs TMDB or Sonarr). */
    var upcomingEpisodes by boolPref("upcoming_episodes", true)

    fun tmdb(): com.example.plex_tv_app.tmdb.TmdbClient? =
        tmdbKey.trim().takeIf { it.isNotBlank() }?.let { com.example.plex_tv_app.tmdb.TmdbClient(it) }

    /** "192.168.1.20" + "7878" → "http://192.168.1.20:7878". Also copes with a full address typed in. */
    private fun arrUrl(host: String, port: String): String {
        val h = host.trim().trimEnd('/')
        if (h.isBlank()) return ""
        val scheme = if (h.startsWith("https://")) "https://" else "http://"
        val bare = h.removePrefix("https://").removePrefix("http://")
        return if (':' in bare || port.isBlank()) scheme + bare else "$scheme$bare:${port.trim()}"
    }

    /** Sonarr (shows) or Radarr (movies), once its address and key are filled in. */
    fun arr(kind: com.example.plex_tv_app.arr.ArrKind): com.example.plex_tv_app.arr.ArrClient? {
        val movie = kind == com.example.plex_tv_app.arr.ArrKind.Movie
        val url = if (movie) radarrUrl else sonarrUrl
        val key = if (movie) radarrKey else sonarrKey
        return if (url.isNotBlank() && key.isNotBlank()) com.example.plex_tv_app.arr.ArrClient(kind, url, key) else null
    }

    // Subtitle picks made for a particular show or movie (on its page or while watching).
    private val subtitlePicks = context.getSharedPreferences("subtitle_choices", Context.MODE_PRIVATE)
    /** Bumps whenever a pick changes, so screens showing the current subtitle update. */
    var subtitlePickVersion by mutableStateOf(0)
        private set

    /** The subtitle picked for a show/movie ([scope] = its ratingKey), or null if none was picked. */
    fun subtitlePick(scope: String): SubtitlePick? = subtitlePicks.getString(scope, null)?.let(SubtitlePick::decode)

    fun setSubtitlePick(scope: String, pick: SubtitlePick) {
        subtitlePicks.edit().putString(scope, pick.encode()).apply()
        subtitlePickVersion++
    }

    // ---- plumbing ----

    private fun boolPref(key: String, default: Boolean) =
        Pref(prefs, key, default, { p, k, d -> p.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) }).also { all[key] = it }

    private fun floatPref(key: String, default: Float) =
        Pref(prefs, key, default, { p, k, d -> p.getFloat(k, d) }, { e, k, v -> e.putFloat(k, v) }).also { all[key] = it }

    private fun stringPref(key: String, default: String) =
        Pref(prefs, key, default, { p, k, d -> p.getString(k, d) ?: d }, { e, k, v -> e.putString(k, v) }).also { all[key] = it }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E) =
        Pref(
            prefs, key, default,
            { p, k, d -> runCatching { enumValueOf<E>(p.getString(k, d.name) ?: d.name) }.getOrDefault(d) },
            { e, k, v -> e.putString(k, v.name) },
        ).also { register(key, it) }

    @PublishedApi
    internal fun register(key: String, pref: Pref<*>) {
        all[key] = pref
    }
}

/**
 * A subtitle choice for a show or movie: off, or a particular track. For a show, other episodes
 * use a track in the same [language] (and of the same forced-ness).
 */
data class SubtitlePick(val off: Boolean, val streamId: Long? = null, val language: String? = null, val forced: Boolean = false) {
    fun encode(): String = if (off) "off" else "${streamId ?: ""}|${language ?: ""}|${if (forced) 1 else 0}"

    companion object {
        val Off = SubtitlePick(off = true)

        fun decode(s: String): SubtitlePick? {
            if (s == "off") return Off
            val parts = s.split("|")
            if (parts.size != 3) return null
            return SubtitlePick(false, parts[0].toLongOrNull(), parts[1].ifBlank { null }, parts[2] == "1")
        }
    }
}

/** The text size everyone starts with (and "Reset text size" goes back to). */
const val DEFAULT_TEXT_SCALE = 0.8f
const val MIN_TEXT_SCALE = 0.6f
/** Card spacing: 1.0 is the usual gap between cards. */
const val DEFAULT_CARD_SPACING = 1f
const val MIN_CARD_SPACING = 0.5f
const val MAX_CARD_SPACING = 2f
const val MAX_TEXT_SCALE = 1.4f

/** Subtitle text size as a multiple of normal. */
val SubtitleSize.scale: Float
    get() = when (this) {
        SubtitleSize.Small -> 0.8f
        SubtitleSize.Normal -> 1f
        SubtitleSize.Large -> 1.25f
        SubtitleSize.ExtraLarge -> 1.5f
    }

/** One saved setting that's also observable Compose state. */
class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val default: T,
    read: (SharedPreferences, String, T) -> T,
    private val write: (SharedPreferences.Editor, String, T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    private var state by mutableStateOf(read(prefs, key, default))

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state = value
        prefs.edit().also { write(it, key, value) }.apply()
    }

    fun reset() {
        state = default
        prefs.edit().remove(key).apply()
    }
}
