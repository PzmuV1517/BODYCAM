package com.bodycam.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Landscape chest-mount display. Layout and colors follow MIL-STD-1472 conventions:
 * terse all-caps labels, green = normal, amber = caution, red = alert, no flashing,
 * gloved-size touch targets with haptic confirmation, red-only night mode.
 */
class MainActivity : Activity(), LinkService.Listener {

    private var service: LinkService? = null
    private var surfaceHolder: SurfaceHolder? = null

    private lateinit var root: FrameLayout
    private lateinit var aspect: AspectFrame
    private lateinit var overlay: OverlayView
    private lateinit var demoView: DemoView
    private lateinit var statusText: TextView
    private lateinit var contactText: TextView
    private lateinit var detButton: TextView
    private lateinit var vibButton: TextView
    private lateinit var nightButton: TextView
    private lateinit var menuButton: TextView
    private lateinit var menu: GloveMenu
    private var night = false
    private var videoOffset = 0f // 0 = left edge, 1 = right edge

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as LinkService.LocalBinder).service
            service = s
            s.listener = this@MainActivity
            surfaceHolder?.let { s.setSurface(it.surface) }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // screen never sleeps while open
        videoOffset = prefs().getFloat(KEY_OFFSET, 0f)
        applyOrientation()
        buildUi()
        hideSystemBars()
        if (android.os.Build.VERSION.SDK_INT >= 31) { // Bluetooth Wi-Fi recovery
            val perms = arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
            if (perms.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, 1)
        }
        val intent = Intent(this, LinkService::class.java)
        startForegroundService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        service?.listener = null
        service?.setSurface(null)
        unbindService(conn)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ------------------------------------------------------------------ palette
    private fun c(day: Int) = if (night) NIGHT_RED else day
    private val bgOff get() = if (night) Color.rgb(12, 0, 0) else Color.argb(170, 25, 25, 25)
    private val bgOn get() = if (night) Color.rgb(60, 0, 0) else Color.rgb(0, 90, 30)

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(onClick: () -> Unit) = TextView(this).apply {
        textSize = 18f
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        minWidth = dp(112)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat() }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onClick()
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true // second guard alongside the window flag
        }

        aspect = AspectFrame(this)
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceHolder = holder
                service?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceHolder = null
                service?.setSurface(null) // stops the decoder before the surface goes away
            }
        })
        overlay = OverlayView(this)
        demoView = DemoView(this).apply { visibility = View.GONE }
        aspect.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        aspect.addView(demoView, FrameLayout.LayoutParams(-1, -1))
        aspect.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(aspect, FrameLayout.LayoutParams(-1, -1, Gravity.START or Gravity.CENTER_VERTICAL))
        aspect.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyOffset() }

        statusText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        contactText = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            visibility = View.GONE
        }
        root.addView(contactText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        detButton = button { service?.let { it.aiEnabled = !it.aiEnabled; refresh() } }
        vibButton = button { service?.let { it.alertsEnabled = !it.alertsEnabled; refresh() } }
        nightButton = button { toggleNight() }
        menuButton = button { menu.open() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(10), dp(6))
            listOf(detButton, vibButton, nightButton, menuButton).forEach {
                addView(it, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(4) })
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(-2, -1, Gravity.END))

        menu = GloveMenu(this, menuHost)
        root.addView(menu, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        refresh()
        // Back closes the menu; otherwise it only hides the app (link and alerts keep running).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (menu.isOpen) menu.close() else moveTaskToBack(true)
            }
        }
    }

    private fun applyOffset() {
        val free = (root.width - aspect.width).coerceAtLeast(0)
        aspect.translationX = videoOffset * free
    }

    private fun refresh() {
        val s = service
        style(detButton, if (s?.aiEnabled != false) "DET ON" else "DET OFF", s?.aiEnabled != false)
        style(vibButton, if (s?.alertsEnabled != false) "VIB ON" else "VIB OFF", s?.alertsEnabled != false)
        style(nightButton, if (night) "NIGHT ON" else "NIGHT", night)
        style(menuButton, "MENU", false)
        demoView.visibility = if (s?.demo == true) View.VISIBLE else View.GONE
        val rot = if (s?.videoFlip == true) 180f else 0f // boxes and demo follow the video
        overlay.rotation = rot
        demoView.rotation = rot
        overlay.night = night
    }

    private fun style(v: TextView, label: String, on: Boolean) {
        v.text = label
        v.setTextColor(c(Color.WHITE))
        (v.background as GradientDrawable).setColor(if (on) bgOn else bgOff)
    }

    /** Red-only, minimum brightness: preserves night vision and light discipline. */
    private fun toggleNight() {
        night = !night
        window.attributes = window.attributes.apply {
            screenBrightness = if (night) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        refresh()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun prefs() = getSharedPreferences("bodycam", Context.MODE_PRIVATE)

    /** Fixed landscape. No auto-rotate: on a swing-down mount the phone is near flat and would flip. */
    private fun applyOrientation() {
        requestedOrientation = if (prefs().getBoolean(KEY_FLIP, false))
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    // ------------------------------------------------------------------ menu
    private val menuHost = object : GloveMenu.Host {
        override val service get() = this@MainActivity.service
        override val night get() = this@MainActivity.night
        override var videoOffset: Float
            get() = this@MainActivity.videoOffset
            set(v) {
                this@MainActivity.videoOffset = v
                prefs().edit().putFloat(KEY_OFFSET, v).apply()
            }
        override var flip: Boolean
            get() = prefs().getBoolean(KEY_FLIP, false)
            set(v) { prefs().edit().putBoolean(KEY_FLIP, v).apply() }
        override fun applyOffset() = this@MainActivity.applyOffset()
        override fun applyOrientation() = this@MainActivity.applyOrientation()
        override fun refreshMain() = refresh()
    }

    // ------------------------------------------------------------------ service callbacks
    override fun onState(state: LinkService.LinkState) {
        val net = service?.ssid?.ifBlank { "WI-FI" } ?: "WI-FI"
        val (text, color) = when (state.phase) {
            LinkService.Phase.LIVE -> {
                val pi = state.pi
                val det = when {
                    pi == null -> ""
                    !pi.hailoOk -> "  DET FAIL"
                    pi.aiEnabled -> "  DET ${pi.inferFps}"
                    else -> "  DET OFF"
                }
                val tag = if (state.demo) "DEMO" else "LINK UP"
                val dly = state.delayMs?.let { "  DLY ${it}MS" } ?: ""
                "$tag  ${state.fps}FPS$dly  ${state.rttMs}MS$det  ${pi?.tempC ?: 0}C" to GREEN
            }
            LinkService.Phase.STALLED -> "LINK DEGRADED  RESYNC" to AMBER
            LinkService.Phase.NO_PI -> "NO PI  SRCH $net" to AMBER
            LinkService.Phase.NO_WIFI -> "NO LINK  SRCH $net" to RED
        }
        if (menu.isOpen) menu.refresh()
        statusText.text = if (state.bleNote != null) "$text\n${state.bleNote}" else text
        statusText.setTextColor(c(color))
        overlay.stale = state.phase != LinkService.Phase.LIVE

        if (state.persons > 0) {
            contactText.text = "CONTACT ${state.persons}"
            contactText.setTextColor(if (night) Color.BLACK else Color.WHITE)
            contactText.setBackgroundColor(if (night) NIGHT_RED else Color.rgb(170, 0, 0))
            contactText.visibility = View.VISIBLE
        } else {
            contactText.visibility = View.GONE
        }
    }

    @Deprecated("pre-33 back handling")
    override fun onBackPressed() {
        if (::menu.isInitialized && menu.isOpen) menu.close() else moveTaskToBack(true)
    }

    override fun onBoxes(boxes: List<Box>) {
        overlay.boxes = boxes
    }

    override fun onVideoSize(w: Int, h: Int) {
        if (h > 0) aspect.ratio = w.toFloat() / h
    }

    companion object {
        private const val KEY_FLIP = "flip"
        private const val KEY_OFFSET = "video_offset"
        private val GREEN = Color.rgb(0, 220, 80)
        private val AMBER = Color.rgb(255, 176, 0)
        private val RED = Color.rgb(255, 40, 40)
        private val NIGHT_RED = Color.rgb(170, 0, 0)
    }
}
