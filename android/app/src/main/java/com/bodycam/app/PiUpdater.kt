package com.bodycam.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Pi firmware update through the phone:
 *  1. download bodycam-pi-X.tar.gz from the latest GitHub release over whatever network really has
 *     internet (mobile data while on the Pi's Wi-Fi, or normal Wi-Fi),
 *  2. upload it to the Pi (TCP 5601, HMAC login with the Wi-Fi password, SHA-256 checked),
 *     right away if linked, otherwise automatically on the next link.
 * The Pi installs it in a separate process and rolls back if the new code does not start.
 */
object PiUpdater {
    private const val REPO = "PzmuV1517/BODYCAM"
    private const val PORT = 5601
    private const val TAG = "PiUpdater"

    enum class Phase { IDLE, CHECKING, LATEST, DOWNLOADING, READY, SENDING, INSTALLING, DONE, ERROR }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var progress = 0
        private set
    @Volatile var piVersion: String? = null
        private set
    @Volatile private var error: String? = null
    @Volatile private var installAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private var changed: () -> Unit = {}

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bodycam", Context.MODE_PRIVATE)
    private fun bundle(ctx: Context) = File(ctx.filesDir, "pi-bundle.tar.gz")
    private fun cachedVersion(ctx: Context) = prefs(ctx).getString("pi_bundle_version", null)?.takeIf { bundle(ctx).exists() }

    /** True when a downloaded bundle is waiting for the next link (installed automatically then). */
    fun pendingInstall(ctx: Context) = prefs(ctx).getBoolean("pi_bundle_pending", false) && cachedVersion(ctx) != null

    fun label(ctx: Context, linked: Boolean): String {
        val cached = cachedVersion(ctx)
        return when (phase) {
            Phase.IDLE -> if (pendingInstall(ctx)) "v$cached: GO TO PI" else piVersion?.let { "v$it CHECK" } ?: "CHECK"
            Phase.CHECKING -> "CHECKING"
            Phase.LATEST -> "v${piVersion ?: cached ?: "?"} LATEST"
            Phase.DOWNLOADING -> "DL $progress%"
            Phase.READY -> if (linked) "INSTALL v$cached" else "v$cached: GO TO PI"
            Phase.SENDING -> "SEND $progress%"
            Phase.INSTALLING -> "INSTALLING"
            Phase.DONE -> "v${piVersion ?: cached} DONE"
            Phase.ERROR -> error ?: "FAILED"
        }
    }

    private fun set(p: Phase, msg: String? = null) {
        phase = p
        if (msg != null) error = msg
        main.post { changed() }
    }

    /** Tile action (hold): ready bundle + link -> install; otherwise check GitHub and download. */
    fun tap(svc: LinkService, onChange: () -> Unit) {
        changed = onChange
        if (phase in listOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.SENDING, Phase.INSTALLING)) return
        if (cachedVersion(svc) != null && svc.piNetworkLinked && phase == Phase.READY) install(svc)
        else check(svc)
    }

    /** Called by the link service on every status tick: finishes a pending install once linked. */
    fun onLinkTick(svc: LinkService) {
        if (!svc.piNetworkLinked) return
        // 20 s: the Pi restarts the service and needs ~10 s to decide whether to roll back
        if (phase == Phase.INSTALLING && System.currentTimeMillis() - installAt > 20_000) queryVersion(svc, confirmAfterInstall = true)
        else if (pendingInstall(svc) && phase in listOf(Phase.IDLE, Phase.READY, Phase.ERROR)) install(svc)
        else if (piVersion == null && phase == Phase.IDLE) queryVersion(svc, confirmAfterInstall = false)
    }

    /** A network that can really reach the internet (validated): mobile data or normal Wi-Fi. */
    private fun internet(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val all = cm.allNetworks.filter {
            val c = cm.getNetworkCapabilities(it) ?: return@filter false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        return all.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
            ?: all.firstOrNull()
    }

    private fun check(svc: LinkService) {
        set(Phase.CHECKING)
        thread(name = "bodycam-piupdate") {
            try {
                if (svc.piNetworkLinked) queryVersionBlocking(svc)
                val net = internet(svc) ?: return@thread set(Phase.ERROR, "NO NET")
                val c = net.openConnection(URL("https://api.github.com/repos/$REPO/releases/latest")) as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode == 404) return@thread set(Phase.ERROR, "NO RELEASE")
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val version = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").startsWith("bodycam-pi-") && it.getString("name").endsWith(".tar.gz") }
                    ?: return@thread set(Phase.ERROR, "NO PI FILE")
                val installed = piVersion
                if (installed != null && !Updater.newer(version, installed)) return@thread set(Phase.LATEST)
                if (cachedVersion(svc) == version) {
                    prefs(svc).edit().putBoolean("pi_bundle_pending", true).apply()
                    return@thread set(Phase.READY)
                }
                download(svc, net, asset.getString("browser_download_url"), version)
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                set(Phase.ERROR, "NO NET")
            }
        }
    }

    private fun download(ctx: Context, net: Network, url: String, version: String) {
        progress = 0
        set(Phase.DOWNLOADING)
        val tmp = File(ctx.filesDir, "pi-bundle.part")
        val c = net.openConnection(URL(url)) as HttpURLConnection // follows the redirect to GitHub's CDN
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != progress) { progress = p; main.post { changed() } }
                    }
                }
            }
        }
        tmp.renameTo(bundle(ctx))
        prefs(ctx).edit().putString("pi_bundle_version", version).putBoolean("pi_bundle_pending", true).apply()
        set(Phase.READY) // installs now if linked (next status tick), otherwise on the next link
    }

    private fun connect(svc: LinkService): Socket {
        val s = Socket()
        svc.piNetwork?.bindSocket(s) // the Pi's Wi-Fi, even though it has no internet
        s.connect(InetSocketAddress(svc.host, PORT), 5000)
        s.soTimeout = 30_000
        return s
    }

    private fun queryVersionBlocking(svc: LinkService) {
        try {
            connect(svc).use { s ->
                val r = s.getInputStream().bufferedReader()
                r.readLine()
                piVersion = r.readLine()?.removePrefix("VERSION ")?.trim()?.takeIf { it != "?" }
            }
        } catch (e: Exception) {
            Log.w(TAG, "version query failed: $e")
        }
    }

    @Volatile private var querying = false
    @Volatile private var lastQuery = 0L

    private fun queryVersion(svc: LinkService, confirmAfterInstall: Boolean) {
        val now = System.currentTimeMillis()
        if (querying || (!confirmAfterInstall && now - lastQuery < 5000)) return
        querying = true
        lastQuery = now
        if (confirmAfterInstall) installAt = now - 15_000 // retry every ~5 s until the Pi answers
        thread(name = "bodycam-piupdate") {
            try { queryVersionBlocking(svc) } finally { querying = false }
            if (confirmAfterInstall) {
                val want = cachedVersion(svc)
                if (piVersion != null && piVersion == want) {
                    prefs(svc).edit().putBoolean("pi_bundle_pending", false).apply()
                    set(Phase.DONE)
                } else if (piVersion != null) {
                    // The Pi is back but on another version: it rolled back.
                    prefs(svc).edit().putBoolean("pi_bundle_pending", false).apply()
                    set(Phase.ERROR, "ROLLED BACK")
                }
            } else main.post { changed() }
        }
    }

    private fun install(svc: LinkService) {
        val file = bundle(svc)
        val version = cachedVersion(svc) ?: return
        progress = 0
        set(Phase.SENDING)
        prefs(svc).edit().putBoolean("pi_bundle_pending", false).apply() // one automatic attempt per download
        thread(name = "bodycam-piupdate") {
            try {
                val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                connect(svc).use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val out = s.getOutputStream()
                    r.readLine() // BODYCAM-UPDATE 1
                    piVersion = r.readLine()?.removePrefix("VERSION ")?.trim()
                    val nonce = r.readLine()?.removePrefix("NONCE ")?.trim() ?: return@thread set(Phase.ERROR, "BAD REPLY")
                    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(svc.password.toByteArray(), "HmacSHA256")) }
                    val proof = mac.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
                    out.write("AUTH $proof\n".toByteArray()); out.flush()
                    if (r.readLine() != "OK") return@thread set(Phase.ERROR, "LOGIN DENIED")
                    out.write("PUT ${file.length()} $sha $version\n".toByteArray())
                    file.inputStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            val p = (sent * 100 / file.length()).toInt()
                            if (p != progress) { progress = p; main.post { changed() } }
                        }
                    }
                    out.flush()
                    val reply = r.readLine() ?: ""
                    if (reply != "INSTALLING") return@thread set(Phase.ERROR, reply.removePrefix("ERROR ").uppercase().take(14))
                }
                installAt = System.currentTimeMillis()
                prefs(svc).edit().putBoolean("pi_bundle_pending", true).apply() // until the Pi reports the new version
                set(Phase.INSTALLING) // Pi restarts its video service; the link drops for a few seconds
            } catch (e: Exception) {
                Log.w(TAG, "upload failed", e)
                set(Phase.ERROR, "SEND FAILED")
            }
        }
    }
}
