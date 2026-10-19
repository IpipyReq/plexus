package com.example.plex_tv_app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.plex_tv_app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer version on GitHub. */
data class AppRelease(val version: String, val notes: String?, val apkUrl: String, val size: Long)

/**
 * In-app updates from a public GitHub repo's releases (BuildConfig.UPDATE_REPO): each release is
 * tagged with its version (e.g. "v1.2.0") and has the signed APK attached. Plexus downloads it
 * and hands it to Android's installer, which asks you to confirm. Settings and sign-in are kept.
 */
object Updater {
    val currentVersion: String = BuildConfig.VERSION_NAME

    /** What the installer last said (shown in the update panel). */
    var installMessage by mutableStateOf<String?>(null)

    /** "v1.10.2" → [1, 10, 2], for comparing versions. */
    private fun parts(v: String) = Regex("""\d+""").findAll(v).map { it.value.toInt() }.toList()

    fun isNewer(version: String, than: String = currentVersion): Boolean {
        val a = parts(version)
        val b = parts(than)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The latest release, if it's newer than this one and has an APK. Null if you're up to date. */
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Plexus/$currentVersion")
            when (val status = conn.responseCode) {
                // GitHub says 404 both when there are no releases and when the repo is private.
                404 -> throw IllegalStateException("No releases found. The repo (${BuildConfig.UPDATE_REPO}) needs to be public and have a release.")
                !in 200..299 -> throw IllegalStateException("GitHub returned $status.")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = o.optString("tag_name").ifBlank { o.optString("name") }
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            if (!isNewer(version)) return@withContext null
            AppRelease(
                version = version.removePrefix("v").removePrefix("V"),
                notes = o.optString("body").takeIf { it.isNotBlank() },
                apkUrl = apk.optString("browser_download_url"),
                size = apk.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the new APK into the app's cache, reporting progress 0-1. */
    suspend fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "plexus-update.apk")
        var url = URL(release.apkUrl)
        // GitHub redirects downloads to its file servers; follow up to a few hops.
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Plexus/$currentVersion")
            val status = conn.responseCode
            if (status in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (status !in 200..299) throw IllegalStateException("The download returned $status.")
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** Has this TV allowed Plexus to install apps? (Asked once, the first time you update.) */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the TV's setting to allow Plexus to install apps. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // Some TVs don't have that screen; the general security settings has it instead.
            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Hands the APK to Android's installer. It shows its own "Update this app?" screen. */
    fun install(context: Context, apk: File) {
        installMessage = null
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("plexus.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer reports back to Plexus's own screen (not a background receiver): only a
            // visible screen may open the "Update this app?" confirmation, and after Play Protect's
            // "Install anyway" a background one is silently blocked.
            val reply = Intent(context, com.example.plex_tv_app.MainActivity::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val options = if (Build.VERSION.SDK_INT >= 34) {
                android.app.ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            val callback = PendingIntent.getActivity(
                context,
                sessionId,
                reply,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                options,
            )
            session.commit(callback.intentSender)
        }
    }
}

/** The installer's replies arrive at MainActivity with this action. */
const val ACTION_INSTALL_STATUS = "com.example.plex_tv_app.INSTALL_STATUS"

/**
 * Called by MainActivity (a visible screen) with the installer's reply: shows its confirm
 * screen, or says what went wrong. Returns false if [intent] isn't an installer reply.
 */
fun handleInstallStatus(activity: android.app.Activity, intent: Intent?): Boolean {
    if (intent?.action != ACTION_INSTALL_STATUS) return false
    InstallStatus.handle(activity, intent)
    return true
}

private object InstallStatus {
    fun handle(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Updater.installMessage = "Android didn't send its confirmation screen. Try Install again."
                } else {
                    runCatching { context.startActivity(confirm) }.onFailure {
                        Updater.installMessage = "Couldn't open Android's install screen: ${it.message}"
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installMessage = "Updated."
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.installMessage = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update is signed with a different key from the installed app, so Android won't install it over the top. " +
                            "Uninstall Plexus once, then install the new version."
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough space on the TV for the update."
                    else -> "The update didn't install. $detail".trim()
                }
            }
        }
    }
}
