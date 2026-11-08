package com.nihilshell.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.roundToInt

/**
 * A level as a row of LEDs (the battery on the home screen), as the desktop's
 * BatteryGauge and DotMeter: low and not charging, the LEDs turn red, the only
 * alarm colour; charging, a light scans from the level to the end, one LED
 * every 160 ms, each one fading in and out as the desktop's colours do.
 */
class LedGauge(context: Context, private val count: Int = 10) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val size = context.dp(5f)
    private val gap = context.dp(3.5f)
    /** How lit each LED is, 0 to 1, easing towards what it should be. */
    private val glow = FloatArray(count)
    private var head = -1
    private var lastFrame = 0L
    private var lastStep = 0L
    private var running = false

    var level = 0
        private set
    var charging = false
        private set

    fun set(level: Int, charging: Boolean) {
        if (level == this.level && charging == this.charging) return
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        contentDescription = "${this.level}%"
        if (!scanning) head = -1
        update()
        invalidate()
    }

    private val lit: Int get() = if (level <= 0) 0 else (level / (100f / count)).roundToInt().coerceIn(1, count)

    val low: Boolean get() = !charging && level <= 15

    private val scanning: Boolean get() = charging && lit < count

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((count * size + (count - 1) * gap).roundToInt(), size.roundToInt())

    override fun onDraw(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        if (running) {
            // A step every 160 ms: the head goes up one LED, and from the top back to the level.
            if (now - lastStep >= STEP) {
                lastStep = now
                head = if (head < lit || head >= count - 1) lit else head + 1
            }
        }
        val dt = if (lastFrame == 0L) 1000L else (now - lastFrame).coerceAtMost(1000L)
        lastFrame = now
        var moving = false
        for (i in 0 until count) {
            val target = if (i < lit || (running && i == head)) 1f else 0f
            val change = dt.toFloat() / Nihil.FAST
            glow[i] = if (glow[i] < target) minOf(target, glow[i] + change) else maxOf(target, glow[i] - change)
            if (glow[i] != target) moving = true
        }
        val r = size / 2
        val on = if (low) Nihil.SIGNAL else Nihil.INK
        for (i in 0 until count) {
            val cx = r + i * (size + gap)
            paint.color = blend(Nihil.OFF, on, glow[i])
            canvas.drawCircle(cx, r, r * (0.62f + 0.38f * glow[i]), paint)
        }
        if (running || moving) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        update(isVisible)
    }

    private fun update(visible: Boolean = isShown) {
        val want = scanning && visible && isAttachedToWindow
        if (want == running) return
        running = want
        if (!running) head = -1
        lastFrame = 0L
        invalidate()
    }

    private companion object {
        const val STEP = 160L
    }
}
