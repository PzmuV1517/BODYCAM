package com.bodycam.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Full-screen glove menu: big tiles, instant apply, no sliders or checkboxes.
 * INFO mode (or holding a tile) shows what a tile does instead of activating it.
 * Dangerous actions are hold-to-confirm.
 */
class GloveMenu(private val activity: Activity, private val host: Host) : FrameLayout(activity) {

    interface Host {
        val service: LinkService?
        val night: Boolean
        var videoOffset: Float
        var flip: Boolean
        fun applyOffset()
        fun applyOrientation()
        fun refreshMain()
    }

    private enum class State { OFF, ON, NEUTRAL, DISABLED }

    private class Spec(
        val name: String,
        val help: String,
        val value: () -> String,
        val state: () -> State,
        val holdMs: Long = 0, // > 0: hold-to-confirm
        val action: () -> Unit,
    )

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var page = 0
    private var infoMode = false
    private val tabs = mutableListOf<Tile>()
    private val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val info = TextView(activity)
    private val title = TextView(activity)
    private val infoTile: Tile
    private val tiles = mutableListOf<Tile>()

    private val svc get() = host.service

    // ------------------------------------------------------------------ pages
    private val pages: List<Pair<String, List<Spec?>>> by lazy {
        listOf(
            "DISPLAY" to listOf(
                Spec("FLIP 180", "Rotates the whole display 180 degrees to match the phone mount.",
                    { onOff(host.flip) }, { st(host.flip) }) {
                    host.flip = !host.flip; host.applyOrientation()
                },
                Spec("VIDEO FLIP", "Rotates only the video 180 degrees. Use when the camera is mounted upside down.",
                    { onOff(svc?.videoFlip == true) }, { st(svc?.videoFlip == true) }) {
                    svc?.let { it.videoFlip = !it.videoFlip }; host.refreshMain()
                },
                Spec("DEMO", "Simulated feed and contacts for training. Stops the network search.",
                    { onOff(svc?.demo == true) }, { st(svc?.demo == true) }) {
                    svc?.let { it.setDemo(!it.demo) }; host.refreshMain()
                },
                Spec("VIDEO", "Moves the video one step to the left.", { "<<" }, { State.NEUTRAL }) {
                    moveVideo(-0.1f)
                },
                Spec("VIDEO POS", "Current video position. Tap to put the video back on the left edge.",
                    { "${(host.videoOffset * 100).toInt()}%" }, { State.NEUTRAL }) {
                    moveVideo(-1f)
                },
                Spec("VIDEO", "Moves the video one step to the right.", { ">>" }, { State.NEUTRAL }) {
                    moveVideo(0.1f)
                },
            ),
            "PI" to listOf(
                Spec("NIGHT EXP", "Low light: the camera may lengthen exposure up to 1/15 s when the scene is dark. Brighter image, frame rate drops toward 15 FPS and delay rises about 30 MS only while dark. Stored on the Pi.",
                    { svc?.piNightExposure?.let { onOff(it) } ?: "NO LINK" },
                    { svc?.piNightExposure?.let { st(it) } ?: State.DISABLED }) {
                    svc?.piNightExposure?.let { svc?.setNightExposure(!it) }
                },
                Spec("FAN START", "Pi spins its fan in two short bursts when its Wi-Fi is up after power-on, so you can hear it is ready. Stored on the Pi.",
                    { svc?.piFanIndicator?.let { onOff(it) } ?: "NO LINK" },
                    { svc?.piFanIndicator?.let { st(it) } ?: State.DISABLED }) {
                    svc?.piFanIndicator?.let { svc?.setFanIndicator(!it) }
                },
                Spec("BUZZ TEST", "Plays the contact vibration so you can check you feel it through your kit.",
                    { "RUN" }, { State.NEUTRAL }) {
                    svc?.testAlert()
                },
                Spec("FAN -", "Lowers the fixed fan speed by 10%. Reaching 0% ends the override and the fan goes back to the automatic quiet curve.",
                    { "-10%" }, { if (svc?.fanOverride == null) State.DISABLED else State.NEUTRAL }) {
                    svc?.fanOverride?.let { if (it > 0) svc?.setFanOverride(it - 10) }
                },
                Spec("FAN", "Fan speed. AUTO = quiet automatic curve, a % = fixed speed you set. Tap to go back to AUTO. A fixed speed is cancelled at 80 C and on every restart. Shows the measured speed in RPM.",
                    { svc?.fanOverride?.let { o -> (if (o == 0) "AUTO" else "$o%") + (svc?.fanRpm?.let { " ${it}RPM" } ?: "") } ?: "NO LINK" },
                    { when (svc?.fanOverride) { null -> State.DISABLED; 0 -> State.NEUTRAL; else -> State.ON } }) {
                    svc?.setFanOverride(0)
                },
                Spec("FAN +", "Raises the fan speed by 10%. From AUTO the first tap sets a fixed 10%.",
                    { "+10%" }, { if (svc?.fanOverride == null) State.DISABLED else State.NEUTRAL }) {
                    svc?.fanOverride?.let { svc?.setFanOverride(minOf(100, it + 10)) }
                },
            ),
            "LINK" to listOf(
                Spec("SSID", "Network name of the Pi. Empty = use the Wi-Fi the phone is already on (bench test). Tap to edit.",
                    { svc?.ssid?.ifBlank { "PHONE WI-FI" } ?: "-" }, { State.NEUTRAL }) {
                    edit("SSID", listOf(Field("SSID", svc?.ssid ?: ""))) { v ->
                        svc?.let { it.saveSettings(v[0], it.password, it.host) }; null
                    }
                },
                Spec("PASSWORD", "WPA2 password of the Pi network. Tap to edit.",
                    { if (svc?.password.isNullOrEmpty()) "NONE" else "SET" }, { State.NEUTRAL }) {
                    edit("PASSWORD", listOf(Field("PASSWORD", svc?.password ?: "", secret = true))) { v ->
                        svc?.let { it.saveSettings(it.ssid, v[0], it.host) }; null
                    }
                },
                Spec("PI ADDRESS", "IP address of the Pi. Default ${LinkService.DEFAULT_HOST} on its own network. Tap to edit.",
                    { svc?.host ?: "-" }, { State.NEUTRAL }) {
                    edit("PI ADDRESS", listOf(Field("PI ADDRESS", svc?.host ?: ""))) { v ->
                        svc?.let { it.saveSettings(it.ssid, it.password, v[0]) }; null
                    }
                },
                Spec("PI WI-FI", "Changes the SSID and password broadcast by the Pi. The phone follows automatically. Requires an active link.",
                    { if (svc?.piFanIndicator != null) "CHANGE" else "NO LINK" },
                    { if (svc?.piFanIndicator != null) State.NEUTRAL else State.DISABLED }) {
                    changePiWifi()
                },
                Spec("RECONNECT", "Drops and rebuilds the link to the Pi.", { "RUN" }, { State.NEUTRAL }) {
                    svc?.reconnect()
                },
                Spec("SHUT DOWN", "Hold 2 seconds. Stops video, contact alerts and reconnection, and closes the app.",
                    { "HOLD" }, { State.NEUTRAL }, holdMs = 2000) {
                    activity.stopService(android.content.Intent(activity, LinkService::class.java))
                    activity.finishAndRemoveTask()
                },
            ),
        )
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"
    private fun st(b: Boolean) = if (b) State.ON else State.OFF

    private fun moveVideo(step: Float) {
        host.videoOffset = if (step <= -1f) 0f else (host.videoOffset + step).coerceIn(0f, 1f)
        host.applyOffset()
    }

    // ------------------------------------------------------------------ layout
    init {
        visibility = GONE
        isClickable = true // swallow touches meant for the video underneath
        setBackgroundColor(Color.argb(245, 0, 0, 0))

        title.textSize = 20f
        title.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        title.gravity = Gravity.CENTER_VERTICAL
        title.setPadding(dp(12), 0, 0, 0)
        infoTile = Tile(Spec("INFO", "", { "" }, { if (infoMode) State.ON else State.OFF }) {
            infoMode = !infoMode
            showInfo(if (infoMode) "INFO ON: TAP A TILE TO SEE WHAT IT DOES" else "")
        }, compact = true, amberWhenOn = true)
        val back = Tile(Spec("BACK", "", { "" }, { State.NEUTRAL }) { close() }, compact = true)
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, -1, 1f))
            addView(infoTile, LinearLayout.LayoutParams(dp(150), -1).apply { marginEnd = dp(8) })
            addView(back, LinearLayout.LayoutParams(dp(150), -1))
        }

        val tabCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        pages.forEachIndexed { i, (name, _) ->
            val t = Tile(Spec(name, "", { "" }, { if (page == i) State.ON else State.OFF }) { showPage(i) }, compact = true)
            tabs += t
            tabCol.addView(t, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(8) })
        }

        info.textSize = 14f
        info.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        info.setPadding(dp(8), dp(4), dp(8), 0)
        info.maxLines = 4
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(info, LinearLayout.LayoutParams(-1, dp(76)))
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabCol, LinearLayout.LayoutParams(dp(150), -1).apply { marginEnd = dp(10) })
            addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val rootCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            addView(top, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(10) })
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addView(rootCol, LayoutParams(-1, -1))
        showPage(0)
    }

    fun open() {
        infoMode = false
        showInfo("")
        visibility = VISIBLE
        refresh()
    }

    fun close() {
        visibility = GONE
        host.refreshMain()
    }

    val isOpen get() = visibility == VISIBLE

    private fun showPage(i: Int) {
        page = i
        title.text = "MENU / ${pages[i].first}"
        grid.removeAllViews()
        tiles.clear()
        val specs = pages[i].second
        for (r in 0 until 2) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val spec = specs.getOrNull(r * 3 + c)
                val v: View = if (spec != null) Tile(spec).also { tiles += it } else View(activity)
                row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (c < 2) marginEnd = dp(10)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (r == 0) bottomMargin = dp(10) })
        }
        refresh()
    }

    /** Re-read values (called on every link status tick while open). */
    fun refresh() {
        if (!isOpen && tiles.isNotEmpty()) return
        val red = host.night
        title.setTextColor(if (red) NIGHT else Color.WHITE)
        info.setTextColor(if (red) NIGHT else AMBER)
        (tabs + tiles + infoTile).forEach { it.invalidate() }
    }

    private fun showInfo(text: String) {
        info.text = text
    }

    // ------------------------------------------------------------------ tile
    private inner class Tile(
        val spec: Spec,
        val compact: Boolean = false,
        val amberWhenOn: Boolean = false,
    ) : View(activity) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
        private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private var pressed = false
        private var progress = 0f
        private var anim: ValueAnimator? = null
        private var longFired = false
        private val longPress = Runnable {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showInfo("${spec.name}: ${spec.help}")
        }

        override fun onDraw(c: Canvas) {
            val red = host.night
            val state = spec.state()
            val on = state == State.ON
            val disabled = state == State.DISABLED
            bg.color = when {
                disabled -> if (red) Color.rgb(8, 0, 0) else Color.rgb(18, 18, 18)
                on && amberWhenOn -> if (red) Color.rgb(70, 0, 0) else Color.rgb(110, 75, 0)
                on -> if (red) Color.rgb(70, 0, 0) else Color.rgb(0, 95, 35)
                else -> if (red) Color.rgb(20, 0, 0) else Color.rgb(32, 32, 32)
            }
            border.color = if (pressed) (if (red) NIGHT else Color.WHITE) else Color.TRANSPARENT
            val fg = when {
                disabled -> if (red) Color.rgb(80, 0, 0) else Color.rgb(100, 100, 100)
                red -> NIGHT
                else -> Color.WHITE
            }
            rect.set(2 * d, 2 * d, width - 2 * d, height - 2 * d)
            c.drawRoundRect(rect, 6 * d, 6 * d, bg)
            if (progress > 0f) { // hold-to-confirm fill
                fill.color = if (red) Color.rgb(120, 0, 0) else Color.rgb(170, 30, 30)
                c.drawRoundRect(RectF(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom), 6 * d, 6 * d, fill)
            }
            c.drawRoundRect(rect, 6 * d, 6 * d, border)
            nameP.color = fg
            valueP.color = fg
            if (compact) {
                nameP.textSize = 18 * d
                c.drawText(spec.name, width / 2f, height / 2f + nameP.textSize / 3, nameP)
            } else {
                nameP.textSize = 17 * d
                valueP.textSize = 26 * d
                val value = spec.value()
                c.drawText(spec.name, width / 2f, height * 0.38f, nameP)
                c.drawText(fit(value, valueP, width - 16 * d), width / 2f, height * 0.38f + valueP.textSize * 1.3f, valueP)
            }
        }

        private fun fit(s: String, p: Paint, max: Float): String {
            var t = s
            while (t.length > 3 && p.measureText(t) > max) t = t.dropLast(2) + "."
            return t
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    longFired = false
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (spec.holdMs > 0 && !infoMode && spec.state() != State.DISABLED) startHold()
                    else postDelayed(longPress, 600)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                    finishTouch()
                    if (inside && !longFired && spec.holdMs == 0L) tap()
                    if (inside && infoMode && spec.holdMs > 0) showInfo("${spec.name}: ${spec.help}")
                }
                MotionEvent.ACTION_CANCEL -> finishTouch()
            }
            return true
        }

        private fun finishTouch() {
            pressed = false
            removeCallbacks(longPress)
            anim?.cancel()
            anim = null
            progress = 0f
            invalidate()
        }

        private fun startHold() {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = spec.holdMs
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (anim === a && progress >= 1f) {
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            spec.action()
                        }
                    }
                })
                start()
            }
        }

        private fun tap() {
            if (infoMode && spec.help.isNotEmpty()) {
                showInfo("${spec.name}: ${spec.help}")
                return
            }
            if (spec.state() == State.DISABLED) {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
                showInfo("${spec.name}: NEEDS A LINK TO THE PI")
                return
            }
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            spec.action()
            this@GloveMenu.refresh()
        }
    }

    // ------------------------------------------------------------------ text entry
    private class Field(val label: String, val value: String, val secret: Boolean = false)

    /** Full-screen form with big text and big CANCEL / SAVE tiles. onSave returns an error or null. */
    private fun edit(titleText: String, fields: List<Field>, saveLabel: String = "SAVE", onSave: (List<String>) -> String?) {
        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val red = host.night
        val fg = if (red) NIGHT else Color.WHITE
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        col.addView(TextView(activity).apply {
            text = titleText; textSize = 20f; setTextColor(fg)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        val inputs = fields.map { f ->
            col.addView(TextView(activity).apply {
                text = f.label; textSize = 14f; setTextColor(if (red) NIGHT else Color.LTGRAY)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
            EditText(activity).apply {
                setText(f.value)
                textSize = 24f
                setTextColor(fg)
                isSingleLine = true
                typeface = Typeface.MONOSPACE
                inputType = if (f.secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                col.addView(this, LinearLayout.LayoutParams(-1, dp(64)))
            }
        }
        val err = TextView(activity).apply {
            textSize = 15f; setTextColor(if (red) NIGHT else Color.rgb(255, 70, 70))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        col.addView(err)
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun big(label: String, action: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 22f; gravity = Gravity.CENTER; setTextColor(fg)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = ColorDrawable(if (red) Color.rgb(20, 0, 0) else Color.rgb(32, 32, 32))
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); action() }
        }
        buttons.addView(big("CANCEL") { dlg.dismiss() }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })
        buttons.addView(big(saveLabel) {
            val error = onSave(inputs.map { it.text.toString() })
            if (error == null) { dlg.dismiss(); refresh() } else err.text = error
        }, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(buttons, LinearLayout.LayoutParams(-1, dp(80)).apply { topMargin = dp(8) })
        dlg.setContentView(col)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun changePiWifi() {
        val s = svc ?: return
        edit("PI WI-FI", listOf(Field("NEW SSID", s.ssid), Field("NEW PASSWORD (8-63)", "", secret = true)), "APPLY") { v ->
            val ssid = v[0].trim()
            val pass = v[1]
            when {
                ssid.isEmpty() || ssid.toByteArray().size > 32 -> "SSID: 1-32 CHARACTERS"
                pass.length !in 8..63 || !pass.all { it.code in 32..126 } -> "PASSWORD: 8-63 CHARACTERS"
                else -> {
                    s.changePiWifi(ssid, pass) { ok, msg ->
                        Toast.makeText(activity, if (ok) "PI WI-FI SET: $ssid. RECONNECTING" else "FAILED: ${msg.uppercase()}", Toast.LENGTH_LONG).show()
                    }
                    null
                }
            }
        }
    }

    companion object {
        private val NIGHT = Color.rgb(170, 0, 0)
        private val AMBER = Color.rgb(255, 176, 0)
    }
}
