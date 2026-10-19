package com.example.plex_tv_app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import com.example.plex_tv_app.settings.LocalSettings
import com.example.plex_tv_app.update.AppRelease
import com.example.plex_tv_app.update.Updater
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Checked once per app start, so the prompt doesn't come back every time you open Settings. */
private var checkedThisRun = false

/**
 * On start (if Settings → About → "Check for updates automatically" is on): looks for a newer
 * version and offers it, unless you chose "Skip this version" for it.
 */
@Composable
fun AutoUpdateCheck() {
    val settings = LocalSettings.current
    var found by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        if (checkedThisRun || !settings.autoCheckUpdates) return@LaunchedEffect
        checkedThisRun = true
        kotlinx.coroutines.delay(4_000) // let Home settle first
        val r = runCatching { Updater.check() }.getOrNull() ?: return@LaunchedEffect
        if (r.version != settings.skippedUpdate) found = r
    }
    found?.let { r -> UpdatePanel(r, onClose = { found = null }) }
}

/**
 * "Check for updates" in Settings → About: checks now and shows the result, or the update panel.
 */
@Composable
fun CheckForUpdatesSetting() {
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppRelease?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ActionSetting(
        "Check for updates",
        status ?: "You're on version ${Updater.currentVersion}.",
        value = if (checking) "Checking…" else null,
    ) {
        if (checking) return@ActionSetting
        checking = true
        scope.launch {
            try {
                val r = Updater.check()
                if (r == null) status = "You're up to date (version ${Updater.currentVersion})." else found = r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't check: ${e.message ?: "no answer"}"
            } finally {
                checking = false
            }
        }
    }
    found?.let { r -> UpdatePanel(r, onClose = { found = null }) }
}

/** What's new, then download (with progress) and install. */
@Composable
private fun UpdatePanel(release: AppRelease, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }

    fun installOrAsk() {
        val apk = downloaded ?: return
        if (!Updater.canInstall(context)) {
            needsPermission = true
            Updater.openInstallPermission(context)
            return
        }
        needsPermission = false
        runCatching { Updater.install(context, apk) }.onFailure { problem = "Couldn't start the install: ${it.message}" }
    }

    // Back from the "install unknown apps" setting with it allowed: carry on.
    val lifecycle = (context as? androidx.activity.ComponentActivity)?.lifecycle
    LaunchedEffect(needsPermission) {
        if (!needsPermission || lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (Updater.canInstall(context)) installOrAsk()
        }
    }

    fun start() {
        if (progress != null && downloaded == null) return
        if (downloaded != null) { installOrAsk(); return }
        problem = null
        progress = 0f
        scope.launch {
            try {
                downloaded = Updater.download(context, release) { progress = it }
                installOrAsk()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                problem = "The download didn't finish: ${e.message ?: "no answer"}"
            }
        }
    }

    SidePanel(title = "Update available", onClose = onClose) { first ->
        Text("Plexus ${release.version}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Text("You have ${Updater.currentVersion}.", fontSize = 13.sp, color = palette.muted)
        release.notes?.let {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).verticalScroll(rememberScrollState())) {
                Text(it.take(1500), fontSize = 13.sp, lineHeight = 18.sp, color = palette.text)
            }
        }
        Spacer(Modifier.height(12.dp))
        val label = when {
            downloaded != null -> "Install"
            progress != null -> "Downloading ${((progress ?: 0f) * 100).toInt()}%"
            else -> "Update now"
        }
        PickerRow(label, false, first) { start() }
        progress?.takeIf { downloaded == null }?.let {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
                ProgressStrip(it, Color(0xFF3DDC84), Modifier.fillMaxWidth())
            }
        }
        PickerRow("Later", false, Modifier) { onClose() }
        PickerRow("Skip this version", false, Modifier) {
            settings.skippedUpdate = release.version
            onClose()
        }
        val message = problem ?: Updater.installMessage ?: if (needsPermission) {
            "Allow Plexus to install apps in the setting that just opened, then come back. " +
                "(On some TVs it's under Settings → Privacy → Security & restrictions → Unknown sources.)"
        } else null
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, lineHeight = 17.sp, color = palette.text)
        }
    }
}
