package com.example.plex_tv_app.ui

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.example.plex_tv_app.settings.AppSettings
import com.example.plex_tv_app.settings.NavSoundMode

/**
 * The soft "pop" sounds Android TV apps make as you move around and select things, using the TV's
 * own sound effects (Settings → General → Navigation sounds).
 */
object NavSounds {
    /** Set while the video player is open: moving around there shouldn't make sounds. */
    @Volatile var muted = false
    private var lastAt = 0L

    /** Called for every remote button press; plays the matching sound if wanted. */
    fun onKeyDown(context: Context, settings: AppSettings, keyCode: Int, repeat: Int) {
        val mode = settings.navSounds
        if (muted || mode == NavSoundMode.Off) return
        val effect = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (repeat == 0) AudioManager.FX_KEY_CLICK else return // not while holding OK
            else -> return
        }
        // Holding a direction repeats very fast; a pop every press would turn into a buzz.
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 70) return
        lastAt = now
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (mode == NavSoundMode.On) {
            audio.playSoundEffect(effect, -1f) // plays even if the TV's own system sounds are off
        } else {
            audio.playSoundEffect(effect) // only if the TV's system sounds are on
        }
    }
}
