package com.bodycam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Scripted fake scene, a pure function of time so the service (detections/alerts) and the view
 * (drawing) agree without sharing state. 14 s loop: empty, one person walks in, a second joins.
 */
object DemoScene {
    private const val LOOP = 14_000L

    fun at(nowMs: Long): List<Box> {
        val t = (nowMs % LOOP) / 1000f
        val out = ArrayList<Box>(2)
        if (t in 3f..12f) out += person(0.05f + (t - 3f) / 9f * 0.7f, 0.42f, 0.30f, nowMs)
        if (t in 7f..12.5f) out += person(0.88f - (t - 7f) / 5.5f * 0.35f, 0.38f, 0.36f, nowMs + 777)
        return out
    }

    private fun person(x: Float, top: Float, h: Float, seed: Long): Box {
        val w = h * 0.32f
        val jitter = sin(seed / 90.0).toFloat() * 0.004f
        val score = 0.72f + 0.15f * sin(seed / 400.0).toFloat()
        return Box(x + jitter, top, x + w + jitter, top + h, score)
    }
}

/** Draws the fake scene in place of the video (a Surface used by MediaCodec can't also be canvas-drawn). */
class DemoView(context: Context) : View(context) {
    private val sky = Paint()
    private val ground = Paint().apply { color = Color.rgb(28, 36, 28) }
    private val tree = Paint().apply { color = Color.rgb(18, 26, 18); isAntiAlias = true }
    private val body = Paint().apply { color = Color.rgb(12, 14, 12); isAntiAlias = true }
    private val label = Paint().apply {
        color = Color.argb(110, 255, 255, 255); isAntiAlias = true; isFakeBoldText = true
        textSize = 16 * resources.displayMetrics.density; textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, Color.rgb(70, 82, 70), Color.rgb(40, 50, 40), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h * 0.7f, sky)
        c.drawRect(0f, h * 0.7f, w, h, ground)
        for (i in 0 until 7) { // treeline
            val x = w * (0.07f + i * 0.145f)
            c.drawRect(x - w * 0.012f, h * 0.3f, x + w * 0.012f, h * 0.72f, tree)
            c.drawCircle(x, h * 0.28f, w * 0.05f, tree)
        }
        for (b in DemoScene.at(SystemClock.elapsedRealtime())) { // silhouettes
            val bw = (b.x2 - b.x1) * w
            val cx = (b.x1 + b.x2) / 2 * w
            c.drawCircle(cx, b.y1 * h + bw * 0.28f, bw * 0.28f, body)
            c.drawRoundRect(cx - bw * 0.5f, b.y1 * h + bw * 0.6f, cx + bw * 0.5f, b.y2 * h, bw * 0.2f, bw * 0.2f, body)
        }
        c.drawText("DEMO — no camera", w / 2, h * 0.1f, label)
        postInvalidateOnAnimation()
    }
}
