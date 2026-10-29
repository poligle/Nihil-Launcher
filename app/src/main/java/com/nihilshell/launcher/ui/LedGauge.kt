package com.nihilshell.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.roundToInt

/**
 * A level as a row of LEDs (the battery on the home screen). Low and not
 * charging: the LEDs turn red, the only alarm colour. Charging: the next LED
 * breathes.
 */
class LedGauge(context: Context, private val count: Int = 10) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val size = context.dp(5f)
    private val gap = context.dp(3.5f)
    private var breath = 1f
    private var animator: ValueAnimator? = null

    var level = 0
        private set
    var charging = false
        private set

    fun set(level: Int, charging: Boolean) {
        if (level == this.level && charging == this.charging) return
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        contentDescription = "$level%"
        updateBreathing()
        invalidate()
    }

    private val lit: Int get() = if (level <= 0) 0 else (level / (100f / count)).roundToInt().coerceIn(1, count)

    val low: Boolean get() = !charging && level <= 15

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((count * size + (count - 1) * gap).roundToInt(), size.roundToInt())

    override fun onDraw(canvas: Canvas) {
        val r = size / 2
        val on = if (low) Nihil.SIGNAL else Nihil.INK
        for (i in 0 until count) {
            val cx = r + i * (size + gap)
            paint.color = when {
                i < lit -> on
                i == lit && charging -> withAlpha(Nihil.INK, 0.15f + 0.85f * breath)
                else -> 0x33FFFFFF
            }
            canvas.drawCircle(cx, r, if (i < lit || (i == lit && charging)) r else r * 0.62f, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBreathing()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateBreathing(isVisible)
    }

    private fun updateBreathing(visible: Boolean = isShown) {
        val want = charging && lit < count && visible && isAttachedToWindow
        if (want && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                interpolator = EASE
                addUpdateListener {
                    breath = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!want) {
            animator?.cancel()
            animator = null
            breath = 1f
        }
    }
}
