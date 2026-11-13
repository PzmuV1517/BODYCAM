package com.bodycam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout

/** Keeps its children at the video's aspect ratio, letterboxed inside the parent. */
class AspectFrame(context: Context) : FrameLayout(context) {
    var ratio = 16f / 9f
        set(v) {
            if (v > 0 && v != field) { field = v; requestLayout() }
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        var cw = w
        var ch = (w / ratio).toInt()
        if (ch > h) { ch = h; cw = (h * ratio).toInt() }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY),
        )
    }
}

/** Draws person boxes over the video, and a red edge when anyone is detected. */
class OverlayView(context: Context) : View(context) {
    var boxes: List<Box> = emptyList()
        set(v) { field = v; invalidate() }
    var stale = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var night = false
        set(v) {
            if (v == field) return
            field = v
            val col = if (v) Color.rgb(170, 0, 0) else Color.RED
            boxPaint.color = col; edgePaint.color = col; textPaint.color = col
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val boxPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; isAntiAlias = true }
    private val edgePaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6 * d }
    private val textPaint = Paint().apply { color = Color.RED; textSize = 13 * d; isAntiAlias = true; isFakeBoldText = true }
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (stale) canvas.drawRect(0f, 0f, w, h, dimPaint)
        if (boxes.isEmpty()) return
        canvas.drawRect(0f, 0f, w, h, edgePaint)
        for (b in boxes) {
            canvas.drawRect(b.x1 * w, b.y1 * h, b.x2 * w, b.y2 * h, boxPaint)
            canvas.drawText("${(b.score * 100).toInt()}%", b.x1 * w + 3 * d, b.y1 * h - 4 * d, textPaint)
        }
    }
}
