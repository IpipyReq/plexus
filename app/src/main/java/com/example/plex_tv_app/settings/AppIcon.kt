package com.example.plex_tv_app.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The look of the app on the TV's app list (Settings → Appearance → App icon). Each one is an
 * entry in AndroidManifest.xml with its own icon and banner; choosing one switches that entry on
 * and the rest off.
 */
enum class AppIcon(val alias: String, override val label: String) : Labeled {
    Dark("IconDark", "Dark"),
    DarkMark("IconDarkMark", "Dark, no text"),
    Light("IconLight", "Light"),
    LightMark("IconLightMark", "Light, no text"),
    Clear("IconClear", "No background"),
    ClearMark("IconClearMark", "No background, no text");

    private fun component(context: Context) = ComponentName(context.packageName, "${context.packageName}.$alias")

    companion object {
        /** The icon switched on right now. */
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == Dark // Dark is on in the manifest
                    else -> false
                }
            } ?: Dark
        }

        /**
         * Switches the TV's app list to [icon]. The new one is turned on before the old one is turned
         * off, so the app never disappears. The TV may take a moment to show the change.
         */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
