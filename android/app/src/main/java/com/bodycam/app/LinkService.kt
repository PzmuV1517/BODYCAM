package com.bodycam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import android.view.View

/**
 * Owns everything that must keep running with the screen off or the app in the background:
 * the Wi-Fi link, the UDP stream, person-alert vibration. The activity only attaches a Surface.
 */
class LinkService : Service() {

    enum class Phase { NO_WIFI, NO_PI, STALLED, LIVE }

    data class LinkState(
        val phase: Phase,
        val fps: Int,
        val rttMs: Int,
        val pi: PiStatus?,
        val persons: Int,
        val demo: Boolean,
        val bleNote: String?,
        val delayMs: Int?, // how old the picture on screen is (capture -> display)
    )

    interface Listener {
        fun onState(state: LinkState)
        fun onBoxes(boxes: List<Box>)
        fun onVideoSize(w: Int, h: Int)
    }

    inner class LocalBinder : Binder() {
        val service get() = this@LinkService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var connector: WifiConnector? = null
    private var ble: BleRescue? = null
    @Volatile private var bleNote: String? = null
    private var linkDownSince = 0L
    private var lastBleCheck = 0L
    @Volatile private var client: StreamClient? = null
    private var network: Network? = null
    private val decoderLock = Any()
    @Volatile private var decoder: VideoDecoder? = null

    var listener: Listener? = null

    /** Demo mode: Wi-Fi search stopped, scripted fake detections drive the UI and real vibration. */
    @Volatile var demo = false
        private set
    private var demoThread: Thread? = null
    private var surface: Surface? = null

    // link stats (written on rx thread, read on main)
    @Volatile private var lastStatusAt = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastBoxesAt = 0L
    @Volatile private var piStatus: PiStatus? = null
    @Volatile private var rttMs = 0
    @Volatile private var framesThisSecond = 0
    private var fps = 0
    private var fpsWindowStart = 0L

    // alert state (rx thread)
    private val recentHits = BooleanArray(5)
    private var hitIdx = 0
    private var present = false
    private var lastSeenAt = 0L
    private var lastAlertAt = 0L
    private var alertedCount = 0
    @Volatile private var persons = 0
    private val delays = ArrayDeque<Long>()
    @Volatile private var lastDelayAt = 0L
    private var fanWishReset: Runnable? = null

    /** Pi's fan startup signal setting as last reported (null = unknown / not connected). */
    val piFanIndicator: Boolean? get() = piStatus?.fanIndicator

    val piNightExposure: Boolean? get() = piStatus?.nightExposure
    private var nightWishReset: Runnable? = null

    // Pi-side settings: the wish rides in every HELLO for a few seconds, then back to "leave it".
    fun setFanIndicator(on: Boolean) {
        val c = client ?: return
        c.wantFan = if (on) 1 else 0
        fanWishReset?.let { main.removeCallbacks(it) }
        fanWishReset = Runnable { client?.wantFan = 255 }.also { main.postDelayed(it, 3000) }
    }

    private var ovrWishReset: Runnable? = null
    private var pendingOverride: Int? = null
    private var pendingOverrideAt = 0L

    /** Fan override as the user sees it: the value just requested (for quick repeated taps), else the Pi's. */
    val fanOverride: Int?
        get() = if (pendingOverride != null && SystemClock.elapsedRealtime() - pendingOverrideAt < 1500) pendingOverride
        else piStatus?.fanOverride
    val fanRpm: Int? get() = piStatus?.fanRpm

    /** 0 = back to the automatic quiet curve, 1..100 = fixed fan speed. */
    fun setFanOverride(pct: Int) {
        val c = client ?: return
        val v = pct.coerceIn(0, 100)
        pendingOverride = v
        pendingOverrideAt = SystemClock.elapsedRealtime()
        c.wantOverride = v
        ovrWishReset?.let { main.removeCallbacks(it) }
        ovrWishReset = Runnable { client?.wantOverride = 255 }.also { main.postDelayed(it, 3000) }
    }

    val piAfContinuous: Boolean? get() = piStatus?.afContinuous
    val piColorMode: Int? get() = piStatus?.colorMode
    private val wishResets = HashMap<String, Runnable>()

    /** Send a Pi-side wish for 3 s (every HELLO), then go back to "leave it". */
    private fun wish(key: String, set: (StreamClient) -> Unit, clear: (StreamClient) -> Unit) {
        val c = client ?: return
        set(c)
        wishResets.remove(key)?.let { main.removeCallbacks(it) }
        wishResets[key] = Runnable { client?.let(clear) }.also { main.postDelayed(it, 3000) }
    }

    fun setAfContinuous(on: Boolean) = wish("af", { it.wantAf = if (on) 1 else 0 }, { it.wantAf = 255 })
    fun setColorMode(mode: Int) = wish("color", { it.wantColor = mode }, { it.wantColor = 255 })
    fun refocus() { client?.refocus() }

    fun setNightExposure(on: Boolean) {
        val c = client ?: return
        c.wantNight = if (on) 1 else 0
        nightWishReset?.let { main.removeCallbacks(it) }
        nightWishReset = Runnable { client?.wantNight = 255 }.also { main.postDelayed(it, 3000) }
    }

    private fun onDisplayed(captureMs: Long, displayedAt: Long) {
        val offset = client?.clockOffsetMs ?: return
        val d = Proto.diffU32((displayedAt + offset) and 0xFFFFFFFFL, captureMs)
        if (d !in 0..10_000) return
        synchronized(delays) {
            delays.addLast(d)
            if (delays.size > 30) delays.removeFirst()
        }
        lastDelayAt = SystemClock.elapsedRealtime()
    }

    private fun delayMedian(): Int? {
        if (SystemClock.elapsedRealtime() - lastDelayAt > 1500) return null
        synchronized(delays) {
            if (delays.isEmpty()) return null
            return delays.sorted()[delays.size / 2].toInt()
        }
    }

    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI, true)
        set(v) {
            prefs.edit().putBoolean(KEY_AI, v).apply()
            client?.wantAi = v
        }

    /** Rotate only the video 180 degrees (camera mounted upside down). */
    var videoFlip: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_FLIP, false)
        set(v) {
            if (v == videoFlip) return
            prefs.edit().putBoolean(KEY_VIDEO_FLIP, v).apply()
            attachDecoder(if (demo) null else surface) // decoder applies the rotation
        }

    var notifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(v) { prefs.edit().putBoolean(KEY_NOTIFY, v).apply() }

    /** The Pi's Wi-Fi network (sockets to the Pi must be bound to it) and whether the Pi answers. */
    val piNetwork: Network? get() = network
    val piNetworkLinked: Boolean get() = !demo && client != null && SystemClock.elapsedRealtime() - lastStatusAt < 1500

    /** Set by the activity: true while the live view is on screen. */
    @Volatile var uiVisible = false

    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, true)
        set(v) {
            prefs.edit().putBoolean(KEY_ALERTS, v).apply()
        }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("bodycam", Context.MODE_PRIVATE)
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        startInForeground()

        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "bodycam").apply { setReferenceCounted(false); acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bodycam:link").apply { setReferenceCounted(false); acquire() }

        ble = BleRescue(this) { note -> bleNote = note }
        reconnect()
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?) = binder

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        demo = false
        demoThread?.interrupt()
        connector?.stop()
        client?.close()
        ble?.quit()
        setSurface(null)
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ settings
    val ssid get() = prefs.getString(KEY_SSID, DEFAULT_SSID)!!
    val password get() = prefs.getString(KEY_PASS, "")!!
    val host get() = prefs.getString(KEY_HOST, DEFAULT_HOST)!!

    fun saveSettings(ssid: String, password: String, host: String) {
        val h = host.trim().ifEmpty { DEFAULT_HOST }
        // Only touch the link if something changed: reconnecting drops the Pi link for a moment
        // and re-suggesting the Wi-Fi can make Android leave it.
        if (ssid.trim() == this.ssid && password == this.password && h == this.host) return
        prefs.edit().putString(KEY_SSID, ssid.trim()).putString(KEY_PASS, password)
            .putString(KEY_HOST, host.trim().ifEmpty { DEFAULT_HOST }).apply()
        reconnect()
    }

    /** (Re)start Wi-Fi acquisition with current settings. */
    fun reconnect() {
        if (demo) return
        connector?.stop()
        onNetwork(null)
        connector = WifiConnector(this, ssid, password, ::onNetwork).also { it.start() }
    }

    /**
     * Change the Pi's AP name/password. On success we adopt the new credentials ourselves and
     * reconnect (the Pi restarts its AP ~1 s after acking). [done] runs on the main thread.
     */
    fun changePiWifi(newSsid: String, newPass: String, done: (Boolean, String) -> Unit) {
        val c = client
        if (c == null || demo) {
            done(false, "Not connected to the Pi"); return
        }
        c.setPiWifi(newSsid, newPass) { ok, msg ->
            main.post {
                if (ok && ssid.isNotBlank()) saveSettings(newSsid, newPass, host)
                done(ok, msg)
            }
        }
    }

    private fun onNetwork(net: Network?) {
        network = net
        client?.close()
        client = null
        if (net == null && ssid.isNotBlank()) return // no Pi Wi-Fi: nothing to talk to
        client = StreamClient(host, Proto.PORT, net, rxCallbacks).also {
            it.wantAi = aiEnabled
            if (decoder != null) it.requestKeyframe()
        }
    }

    fun setDemo(on: Boolean) {
        if (on == demo) return
        demo = on
        if (on) {
            connector?.stop(); connector = null
            client?.close(); client = null
            network = null
            attachDecoder(null)
            demoThread = Thread({ demoLoop() }, "bodycam-demo").apply { isDaemon = true; start() }
        } else {
            demoThread?.interrupt(); demoThread = null
            persons = 0
            attachDecoder(surface)
            reconnect()
        }
    }

    private fun demoLoop() {
        var tick = 0
        try {
            while (demo) {
                val now = SystemClock.elapsedRealtime()
                rxCallbacks.onFrame(EMPTY, false, 0) // counts as a frame for fps/LIVE
                if (tick % 2 == 0 && aiEnabled) rxCallbacks.onDetections(DemoScene.at(now))
                if (tick % 3 == 0) rxCallbacks.onStatus(PiStatus(aiEnabled, true, 30, if (aiEnabled) 30 else 0, 48, null), 3)
                tick++
                Thread.sleep(33)
            }
        } catch (_: InterruptedException) {}
    }

    // ------------------------------------------------------------------ video surface
    fun setSurface(surface: Surface?) {
        this.surface = surface
        attachDecoder(if (demo) null else surface)
    }

    private fun attachDecoder(surface: Surface?) {
        synchronized(decoderLock) {
            decoder?.release()
            decoder = surface?.let {
                VideoDecoder(
                    it,
                    rotation = if (videoFlip) 180 else 0,
                    onNeedKeyframe = { client?.requestKeyframe() },
                    onVideoSize = { w, h -> main.post { listener?.onVideoSize(w, h) } },
                    onDisplayed = ::onDisplayed,
                )
            }
        }
    }

    // ------------------------------------------------------------------ rx callbacks
    private val rxCallbacks = object : StreamClient.Callbacks {
        override fun onFrame(data: ByteArray, key: Boolean, captureMs: Long) {
            lastFrameAt = SystemClock.elapsedRealtime()
            framesThisSecond++
            if (data.isNotEmpty()) decoder?.submit(data, key, captureMs)
        }

        override fun onDetections(boxes: List<Box>) {
            lastBoxesAt = SystemClock.elapsedRealtime()
            if (boxes.isNotEmpty()) lastBoxes = boxes
            persons = boxes.size
            main.post { listener?.onBoxes(boxes) }
            handleAlert(boxes.size)
        }

        override fun onStatus(status: PiStatus, rttMs: Int) {
            lastStatusAt = SystemClock.elapsedRealtime()
            piStatus = status
            this@LinkService.rttMs = rttMs
        }
    }

    /**
     * Vibrate when a person appears: needs 3 of the last 5 inferences to agree (kills one-frame
     * false positives), then stays quiet while they remain. Buzzes again if more people show up
     * or after they've been gone for 2 s.
     */
    private fun handleAlert(count: Int) {
        val now = SystemClock.elapsedRealtime()
        recentHits[hitIdx] = count > 0
        hitIdx = (hitIdx + 1) % recentHits.size
        val hits = recentHits.count { it }
        if (count > 0) lastSeenAt = now

        if (!present && hits >= 3) {
            present = true
            alertedCount = count
            alert(now, double = false)
        } else if (present && count > alertedCount && now - lastAlertAt > 3000) {
            alertedCount = count
            alert(now, double = true)
        } else if (present && now - lastSeenAt > 2000) {
            present = false
            alertedCount = 0
        }
    }

    /** BUZZ TEST tile: play the contact pattern regardless of settings. */
    fun testAlert() = vibrate(double = false)

    private fun alert(now: Long, double: Boolean) {
        if (!aiEnabled) return
        lastAlertAt = now
        if (alertsEnabled) vibrate(double)
        if (notifyEnabled) notifyContact(alertedCount, lastBoxes)
    }

    // ------------------------------------------------------------------ notifications
    @Volatile private var lastBoxes: List<Box> = emptyList()
    private var contactSeq = 0
    private var linkWasUp = false
    private var linkDownAt = 0L
    private var lostNotified = false

    /** NOTIFY TEST tile: a contact notification with the current picture. */
    fun testNotify() = notifyContact(maxOf(1, persons), lastBoxes)

    /** "CONTACT n" with a snapshot of the detection frame (from the Pi) and the boxes drawn on it. */
    private fun notifyContact(count: Int, boxes: List<Box>) {
        val at = System.currentTimeMillis()
        if (demo) {
            main.post { postContact(count, at, demoPicture(boxes)) }
            return
        }
        val c = client ?: return main.post { postContact(count, at, null) }.let { }
        c.requestSnapshot { jpg ->
            val bmp = jpg?.let { decodeWithBoxes(it, boxes) }
            main.post { postContact(count, at, bmp) }
        }
    }

    private fun decodeWithBoxes(jpg: ByteArray, boxes: List<Box>): android.graphics.Bitmap? {
        val src = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return null
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        drawBoxes(bmp, boxes)
        return if (videoFlip) {
            val m = android.graphics.Matrix().apply { postRotate(180f) }
            android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
        } else bmp
    }

    private fun demoPicture(boxes: List<Box>): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
        val v = DemoView(this)
        v.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 640, 360)
        v.draw(android.graphics.Canvas(bmp))
        drawBoxes(bmp, boxes)
        return bmp
    }

    private fun drawBoxes(bmp: android.graphics.Bitmap, boxes: List<Box>) {
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint().apply {
            color = android.graphics.Color.RED; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
        }
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        boxes.forEach { c.drawRect(it.x1 * w, it.y1 * h, it.x2 * w, it.y2 * h, p) }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun postContact(count: Int, at: Long, picture: android.graphics.Bitmap?) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
        val b = Notification.Builder(this, CONTACT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CONTACT $count")
            .setContentText("$time${if (picture == null) "  NO PICTURE" else ""}")
            .setWhen(at).setShowWhen(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openApp())
            .setAutoCancel(true)
        if (picture != null) {
            b.setLargeIcon(picture)
            b.setStyle(Notification.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.drawable.Icon?))
        }
        notify(100 + contactSeq++ % 20, b.build())
    }

    /** Background only: link down for 3 s -> LINK LOST (+ one long buzz); back up -> LINK RESTORED. */
    private fun watchLink(phase: Phase, now: Long) {
        val up = phase == Phase.LIVE || phase == Phase.STALLED
        if (up) {
            linkWasUp = true
            linkDownAt = 0L
            if (lostNotified) {
                lostNotified = false
                notify(LINK_NOTIFICATION, Notification.Builder(this, LINK_CHANNEL)
                    .setSmallIcon(android.R.drawable.presence_video_online)
                    .setContentTitle("LINK RESTORED")
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .setTimeoutAfter(30_000)
                    .build())
            }
            return
        }
        if (demo || !linkWasUp) return
        if (linkDownAt == 0L) linkDownAt = now
        if (!lostNotified && !uiVisible && now - linkDownAt > 3000) {
            lostNotified = true
            if (alertsEnabled) vibratePattern(longArrayOf(0, 600))
            notify(LINK_NOTIFICATION, Notification.Builder(this, LINK_CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_busy)
                .setContentTitle("LINK LOST")
                .setContentText(bleNote ?: "SEARCHING ${ssid.ifBlank { "WI-FI" }}")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openApp())
                .setOngoing(false)
                .build())
        }
    }

    private fun notify(id: Int, n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
    }

    private fun vibrate(double: Boolean) =
        vibratePattern(if (double) longArrayOf(0, 90, 70, 90, 70, 90) else longArrayOf(0, 120, 80, 120))

    private fun vibratePattern(timings: LongArray) {
        val effect = VibrationEffect.createWaveform(timings, -1)
        // Alarm usage so it still buzzes with the phone on silent / DND.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    // ------------------------------------------------------------------ status ticker
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - fpsWindowStart >= 1000) {
                fps = (framesThisSecond * 1000 / (now - fpsWindowStart).coerceAtLeast(1)).toInt()
                framesThisSecond = 0
                fpsWindowStart = now
            }
            val phase = when {
                demo -> Phase.LIVE
                client == null -> Phase.NO_WIFI
                now - lastStatusAt > 1500 -> Phase.NO_PI
                now - lastFrameAt > 1000 -> Phase.STALLED
                else -> Phase.LIVE
            }
            // Wi-Fi down for >6 s: ask the Pi over Bluetooth what's wrong (and restart its AP if needed).
            val down = !demo && (phase == Phase.NO_WIFI || phase == Phase.NO_PI)
            if (!down) { linkDownSince = 0L; bleNote = null }
            else if (linkDownSince == 0L) linkDownSince = now
            if (down && now - linkDownSince > 6000 && now - lastBleCheck > 20_000 && ble?.busy == false) {
                lastBleCheck = now
                bleNote = "BLE: CHECKING"
                ble?.check(password, ssid)
            }
            watchLink(phase, now)
            PiUpdater.onLinkTick(this@LinkService)
            if (now - lastBoxesAt > 500 && persons != 0) {
                persons = 0
                listener?.onBoxes(emptyList())
            }
            listener?.onState(LinkState(phase, fps, rttMs, if (phase != Phase.LIVE && phase != Phase.STALLED) null else piStatus, persons, demo, bleNote, if (demo) null else delayMedian()))
            main.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ foreground
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // Contacts and link loss: shown prominently but silent (the vibration is the audible-free cue).
        listOf(CONTACT_CHANNEL to "Contacts", LINK_CHANNEL to "Link lost").forEach { (id, name) ->
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false)
            })
        }
        // Silent, minimal: this is a stealth device.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "BodyCam link", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("BodyCam linked")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val CHANNEL = "link"
        private const val CONTACT_CHANNEL = "contacts"
        private const val LINK_CHANNEL = "link_lost"
        private const val LINK_NOTIFICATION = 50
        private const val KEY_NOTIFY = "notify"
        private const val KEY_AI = "ai"
        private const val KEY_ALERTS = "alerts"
        private const val KEY_VIDEO_FLIP = "video_flip"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASS = "pass"
        private const val KEY_HOST = "host"
        const val DEFAULT_SSID = "BODYCAM"
        const val DEFAULT_HOST = "10.42.0.1"
    }
}
