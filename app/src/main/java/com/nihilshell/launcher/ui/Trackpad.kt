package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The deck's trackpad: this screen as the computer's touchpad. Every finger goes as it is,
 * with its time ([onFrame]), and the computer's own touchpad driver makes of them what its
 * touchpad does. Under the fingers, LEDs light up, as the desktop's dots. A swipe stays here,
 * from the screen's edges too; the dots under the workspaces leave it. Its size goes in
 * millimetres (the screen's dpi), so a finger moves the pointer as far as on a laptop's.
 */
class Trackpad(context: Context) : View(context) {

    /** At [time] (ms), [count] fingers touching: their ids, and where, in units ([RESOLUTION] a mm). */
    var onFrame: ((time: Long, count: Int, ids: IntArray, xs: IntArray, ys: IntArray) -> Unit)? = null

    /** Its size changed: [widthUnits] and [heightUnits] say the new one. */
    var onSize: (() -> Unit)? = null

    private val unitsPerPx: Float = run {
        val metrics = resources.displayMetrics
        val measured = (metrics.xdpi + metrics.ydpi) / 2f
        // Some phones say a dpi that isn't their screen's: then the density's.
        val dpi = if (measured > metrics.densityDpi * 0.6f && measured < metrics.densityDpi * 1.6f) measured else metrics.densityDpi.toFloat()
        RESOLUTION * 25.4f / dpi
    }

    val widthUnits: Int get() = (width * unitsPerPx).roundToInt()
    val heightUnits: Int get() = (height * unitsPerPx).roundToInt()

    private val ids = IntArray(MAX)
    private val xs = IntArray(MAX)
    private val ys = IntArray(MAX)

    // The LEDs: a dot every [pitch], lit around each finger, fading once it goes.
    private class Glow(var x: Float, var y: Float, var released: Long = -1L)

    private val glows = HashMap<Int, Glow>()
    private val pitch = context.dp(12f)
    private val radius = context.dp(1.3f)
    private val reach = context.dp(64f)
    private var columns = 0
    private var rows = 0
    private var left = 0f
    private var top = 0f
    private var grid = FloatArray(0)
    private var light = FloatArray(0)
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Nihil.OFF
        strokeCap = Paint.Cap.ROUND
        strokeWidth = radius * 2
    }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        columns = (w / pitch).toInt().coerceAtLeast(1)
        rows = (h / pitch).toInt().coerceAtLeast(1)
        left = (w - (columns - 1) * pitch) / 2f
        top = (h - (rows - 1) * pitch) / 2f
        grid = FloatArray(columns * rows * 2)
        for (r in 0 until rows) for (c in 0 until columns) {
            val i = (r * columns + c) * 2
            grid[i] = left + c * pitch
            grid[i + 1] = top + r * pitch
        }
        light = FloatArray(columns * rows)
        // Swipes from the screen's edges are the computer's here, not Android's back.
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        onSize?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        // Between two frames the screen may have taken more samples: each goes at its own time.
        if (action == MotionEvent.ACTION_MOVE) {
            for (h in 0 until event.historySize) {
                var n = 0
                for (i in 0 until min(event.pointerCount, MAX)) {
                    ids[n] = event.getPointerId(i)
                    xs[n] = units(event.getHistoricalX(i, h), width)
                    ys[n] = units(event.getHistoricalY(i, h), height)
                    n++
                }
                onFrame?.invoke(event.getHistoricalEventTime(h), n, ids, xs, ys)
            }
        }
        // The finger going up is no longer there; with the last one, none is.
        val up = when (action) {
            MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ALL
            else -> NONE
        }
        var n = 0
        if (up != ALL) {
            for (i in 0 until min(event.pointerCount, MAX)) {
                if (i == up) continue
                ids[n] = event.getPointerId(i)
                xs[n] = units(event.getX(i), width)
                ys[n] = units(event.getY(i), height)
                n++
            }
        }
        onFrame?.invoke(event.eventTime, n, ids, xs, ys)
        glow(event, up)
        return true
    }

    private fun units(px: Float, size: Int): Int = (px.coerceIn(0f, size.toFloat()) * unitsPerPx).roundToInt()

    private fun glow(event: MotionEvent, up: Int) {
        val now = SystemClock.uptimeMillis()
        val touching = HashSet<Int>()
        if (up != ALL) {
            for (i in 0 until event.pointerCount) {
                if (i == up) continue
                val id = event.getPointerId(i)
                touching += id
                val glow = glows[id]
                if (glow == null || glow.released >= 0) {
                    glows[id] = Glow(event.getX(i), event.getY(i))
                } else {
                    glow.x = event.getX(i)
                    glow.y = event.getY(i)
                }
            }
        }
        for ((id, glow) in glows) if (id !in touching && glow.released < 0) glow.released = now
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (grid.isEmpty()) return
        canvas.drawPoints(grid, off)
        if (glows.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        light.fill(0f)
        var fading = false
        val each = glows.values.iterator()
        while (each.hasNext()) {
            val glow = each.next()
            val strength = if (glow.released < 0) 1f else 1f - (now - glow.released) / FADE.toFloat()
            if (strength <= 0f) {
                each.remove()
                continue
            }
            if (glow.released >= 0) fading = true
            val c0 = ((glow.x - reach - left) / pitch).toInt().coerceIn(0, columns - 1)
            val c1 = ((glow.x + reach - left) / pitch).toInt().coerceIn(0, columns - 1)
            val r0 = ((glow.y - reach - top) / pitch).toInt().coerceIn(0, rows - 1)
            val r1 = ((glow.y + reach - top) / pitch).toInt().coerceIn(0, rows - 1)
            for (r in r0..r1) for (c in c0..c1) {
                val i = r * columns + c
                val dx = grid[i * 2] - glow.x
                val dy = grid[i * 2 + 1] - glow.y
                val d = (dx * dx + dy * dy) / (reach * reach)
                if (d >= 1f) continue
                val v = (1f - d) * (1f - d) * strength
                if (v > light[i]) light[i] = v
            }
        }
        for (i in light.indices) {
            val v = light[i]
            if (v < 0.04f) continue
            lit.color = blend(Nihil.OFF, Nihil.INK, v)
            canvas.drawCircle(grid[i * 2], grid[i * 2 + 1], radius * (1f + 0.5f * v), lit)
        }
        if (fading) postInvalidateOnAnimation()
    }

    companion object {
        /** Units a millimetre, as the frames carry them. */
        const val RESOLUTION = 20
        private const val MAX = 10
        private const val ALL = -2
        private const val NONE = -1
        private const val FADE = 360L
    }
}
