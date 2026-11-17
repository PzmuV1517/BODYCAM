package com.bodycam.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Self-update from the latest GitHub release of the repo: finds the .apk asset, downloads it and
 * hands it to Android's package installer (user confirms). Releases must be signed with the same
 * key as the installed app (tools/release-app.sh builds them on the same machine).
 */
object Updater {
    private const val REPO = "PzmuV1517/BODYCAM"
    private const val TAG = "Updater"

    enum class Phase { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, INSTALLING, NEEDS_PERMISSION, ERROR }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var remoteVersion: String? = null
        private set
    @Volatile var progress = 0
        private set
    @Volatile var error: String? = null
    private var apkUrl: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Short text for the UPDATE tile. */
    fun label(ctx: Context): String = when (phase) {
        Phase.IDLE -> "v${installedVersion(ctx)} CHECK"
        Phase.CHECKING -> "CHECKING"
        Phase.LATEST -> "v${installedVersion(ctx)} LATEST"
        Phase.AVAILABLE -> "GET v$remoteVersion"
        Phase.DOWNLOADING -> "$progress%"
        Phase.INSTALLING -> "INSTALLING"
        Phase.NEEDS_PERMISSION -> "ALLOW, TAP"
        Phase.ERROR -> error ?: "ERROR"
    }

    /** One tap: check, or download + install when a newer release is known. */
    fun tap(ctx: Context, changed: () -> Unit) {
        when (phase) {
            Phase.CHECKING, Phase.DOWNLOADING, Phase.INSTALLING -> return
            Phase.AVAILABLE, Phase.NEEDS_PERMISSION -> install(ctx.applicationContext, changed)
            else -> check(ctx.applicationContext, changed)
        }
    }

    private fun set(p: Phase, changed: () -> Unit) {
        phase = p
        main.post(changed)
    }

    private fun check(ctx: Context, changed: () -> Unit) {
        set(Phase.CHECKING, changed)
        thread(name = "bodycam-update") {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                if (c.responseCode == 404) { error = "NO RELEASE"; set(Phase.ERROR, changed); return@thread }
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                if (apk == null) { error = "NO APK"; set(Phase.ERROR, changed); return@thread }
                remoteVersion = tag
                apkUrl = apk.getString("browser_download_url")
                set(if (newer(tag, installedVersion(ctx))) Phase.AVAILABLE else Phase.LATEST, changed)
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                error = "NO NET"
                set(Phase.ERROR, changed)
            }
        }
    }

    /** "1.10" > "1.9": compare dot-separated numbers. */
    fun newer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun install(ctx: Context, changed: () -> Unit) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // One-time Android setting: allow BodyCam to install updates.
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            set(Phase.NEEDS_PERMISSION, changed)
            return
        }
        val url = apkUrl ?: return check(ctx, changed)
        progress = 0
        set(Phase.DOWNLOADING, changed)
        thread(name = "bodycam-update") {
            try {
                val file = File(ctx.cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection // follows GitHub's redirect to the CDN
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != progress) { progress = p; main.post(changed) }
                            }
                        }
                    }
                }
                set(Phase.INSTALLING, changed)
                val installer = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(ctx.packageName)
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("bodycam.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateReceiver::class.java), flags)
                    s.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                error = "FAILED"
                set(Phase.ERROR, changed)
            }
        }
    }

    internal fun installResult(ok: Boolean, message: String?) {
        if (!ok) {
            error = "FAILED"
            Log.w(TAG, "install result: $message")
            phase = Phase.ERROR
        }
    }
}

/** Receives the package installer's result; shows Android's confirm screen when it asks for it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installResult(true, null)
            else -> Updater.installResult(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
