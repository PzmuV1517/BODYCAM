package com.bodycam.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.content.pm.ActivityInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), LinkService.Listener {

    private var service: LinkService? = null
    private var surfaceHolder: SurfaceHolder? = null

    private lateinit var aspect: AspectFrame
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var aiButton: TextView
    private lateinit var alertButton: TextView
    private lateinit var dimButton: TextView
    private lateinit var demoView: DemoView
    private var dimmed = false
    private var blink = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as LinkService.LocalBinder).service
            service = s
            s.listener = this@MainActivity
            surfaceHolder?.let { s.setSurface(it.surface) }
            refreshButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientation()
        buildUi()
        hideSystemBars()
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

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 17f
        isAllCaps = true
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        minWidth = dp(104)
        minHeight = dp(50) // glove-sized targets
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.argb(160, 30, 30, 30)) }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

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
        // Video hugs the left edge so the button column on the right doesn't cover it.
        root.addView(aspect, FrameLayout.LayoutParams(-1, -1, Gravity.START or Gravity.CENTER_VERTICAL))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        aiButton = button("AI") { service?.let { it.aiEnabled = !it.aiEnabled; refreshButtons() } }
        alertButton = button("Buzz") { service?.let { it.alertsEnabled = !it.alertsEnabled; refreshButtons() } }
        dimButton = button("Dim") { toggleDim() }
        val settings = button("⚙") { showSettings() }
        val exit = button("✕") { confirmExit() }
        // Landscape chest mount: one column of big buttons on the right edge, in the strip that
        // 16:9 video leaves free on modern ~20:9 phones (overlays the video on 16:9 phones).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(10), dp(6))
            listOf(aiButton, alertButton, dimButton, settings, exit).forEach {
                addView(it, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(4) })
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(-2, -1, Gravity.END))

        setContentView(root)
    }

    private fun refreshButtons() {
        val s = service ?: return
        style(aiButton, "AI ${if (s.aiEnabled) "on" else "off"}", s.aiEnabled)
        style(alertButton, "Buzz ${if (s.alertsEnabled) "on" else "off"}", s.alertsEnabled)
        style(dimButton, "Dim", dimmed)
        demoView.visibility = if (s.demo) View.VISIBLE else View.GONE
    }

    private fun style(v: TextView, label: String, on: Boolean) {
        v.text = label
        (v.background as GradientDrawable).setColor(if (on) Color.argb(200, 150, 20, 20) else Color.argb(160, 30, 30, 30))
    }

    /** Night/stealth: screen almost black so it doesn't light your face up. */
    private fun toggleDim() {
        dimmed = !dimmed
        window.attributes = window.attributes.apply {
            screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        refreshButtons()
    }

    private fun prefs() = getSharedPreferences("bodycam", Context.MODE_PRIVATE)

    /** Fixed landscape (no auto-rotate: on a swing-down mount the phone is near-flat and would flip). */
    private fun applyOrientation() {
        requestedOrientation = if (prefs().getBoolean(KEY_FLIP, false))
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** The link service survives the app being swiped away; this is the only way to fully stop it. */
    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setMessage("Disconnect from the BodyCam and stop alerts?")
            .setPositiveButton("Stop") { _, _ ->
                stopService(Intent(this, LinkService::class.java))
                finishAndRemoveTask()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val s = service ?: return
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }
        val ssid = field("Wi-Fi name (blank = use phone's current Wi-Fi)", s.ssid)
        val pass = field("Wi-Fi password", s.password, password = true)
        val host = field("Pi IP", s.host)
        val flip = CheckBox(this).apply {
            text = "Flip screen 180° (mount orientation)"
            isChecked = prefs().getBoolean(KEY_FLIP, false)
        }
        val demo = CheckBox(this).apply {
            text = "Demo mode (no Pi: fake scene, stops Wi-Fi search)"
            isChecked = s.demo
        }
        val note = TextView(this).apply {
            text = "Blank Wi-Fi name = use whatever Wi-Fi the phone is on (testing).\nDefault Pi IP on its own AP: ${LinkService.DEFAULT_HOST}"
            textSize = 12f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(ssid, pass, host, flip, demo, note).forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Connection")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putBoolean(KEY_FLIP, flip.isChecked).apply()
                applyOrientation()
                s.saveSettings(ssid.text.toString(), pass.text.toString(), host.text.toString())
                s.setDemo(demo.isChecked)
                refreshButtons()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Change Pi Wi-Fi…") { _, _ -> showChangePiWifi() }
            .show()
    }

    /** Rename the Pi's own network / change its password, over the live link. */
    private fun showChangePiWifi() {
        val s = service ?: return
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val ssid = field("New Wi-Fi name").apply { setText(s.ssid) }
        val pass = field("New password (8-63 chars)")
        val note = TextView(this).apply {
            text = "Must be connected to the Pi. It restarts its Wi-Fi (~5 s) and this phone reconnects with the new details automatically."
            textSize = 12f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(ssid, pass, note).forEach { addView(it) }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Change Pi Wi-Fi")
            .setView(form)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newSsid = ssid.text.toString().trim()
            val newPass = pass.text.toString()
            when {
                newSsid.isEmpty() || newSsid.toByteArray().size > 32 -> ssid.error = "1-32 characters"
                newPass.length !in 8..63 || !newPass.all { it.code in 32..126 } -> pass.error = "8-63 plain characters"
                else -> {
                    it.isEnabled = false
                    s.changePiWifi(newSsid, newPass) { ok, msg ->
                        Toast.makeText(this, if (ok) "Pi Wi-Fi changed to '$newSsid', reconnecting…" else "Failed: $msg", Toast.LENGTH_LONG).show()
                        if (ok) dialog.dismiss() else it.isEnabled = true
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ service callbacks
    override fun onState(state: LinkService.LinkState) {
        blink = !blink
        val (text, color) = when (state.phase) {
            LinkService.Phase.LIVE -> {
                val pi = state.pi
                val ai = when {
                    pi == null -> ""
                    !pi.hailoOk -> "  AI n/a"
                    pi.aiEnabled -> "  AI ${pi.inferFps}fps"
                    else -> "  AI off"
                }
                val people = if (state.persons > 0) "  ⚠ ${state.persons} PERSON" else ""
                (if (state.demo) "● DEMO " else "● LIVE ") + "${state.fps}fps ${state.rttMs}ms$ai ${pi?.tempC ?: 0}°C$people" to
                    if (state.persons > 0) Color.RED else Color.GREEN
            }
            LinkService.Phase.STALLED -> "◌ VIDEO STALLED, resyncing…" to Color.YELLOW
            LinkService.Phase.NO_PI -> (if (service?.ssid.isNullOrBlank()) "◌ Wi-Fi up, waiting for Pi…"
                else "◌ SEARCHING '${service?.ssid}'… (on other Wi-Fi)") to if (blink) Color.YELLOW else Color.GRAY
            LinkService.Phase.NO_WIFI -> "◌ SEARCHING '${service?.ssid}'…" to if (blink) Color.RED else Color.GRAY
        }
        statusText.text = text
        statusText.setTextColor(color)
        overlay.stale = state.phase != LinkService.Phase.LIVE
    }

    override fun onBoxes(boxes: List<Box>) {
        overlay.boxes = boxes
    }

    override fun onVideoSize(w: Int, h: Int) {
        if (h > 0) aspect.ratio = w.toFloat() / h
    }

    companion object {
        private const val KEY_FLIP = "flip"
    }
}
