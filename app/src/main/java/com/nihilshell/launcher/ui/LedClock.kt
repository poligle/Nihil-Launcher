package com.nihilshell.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import com.nihilshell.launcher.core.LedFont
import com.nihilshell.launcher.core.LedFont.COLS
import com.nihilshell.launcher.core.LedFont.ROWS
import kotlin.math.min

/**
 * The home clock as a real LED panel: 25 x 7 LEDs, every one of them visible
 * (unlit ones faintly) on a dark module, digits in a 5 x 7 matrix. When the
 * minute changes the panel refreshes column by column, left to right.
 */
class LedClock(context: Context) : View(context) {

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Nihil.INK }
    private val unlit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    /** The module the LEDs sit on, so a busy wallpaper doesn't show between them. */
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0000000.toInt() }

    private var pitch = 0f
    private val maxPitch = context.dp(11f)

    private var current = FloatArray(COLS * ROWS)
    private var previous = FloatArray(COLS * ROWS)
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private var shown: String? = null

    private companion object {
        const val STAGGER = 12L
    }

    /** Shows [time] ("09:41", or " 9:41" with a blank first digit). */
    fun show(time: String, animate: Boolean) {
        if (time == shown) return
        shown = time
        contentDescription = time.trim()
        val next = LedFont.matrix(time)
        animator?.cancel()
        if (!animate || !isAttachedToWindow) {
            current = next
            progress = 1f
            invalidate()
            return
        }
        previous = current
        current = next
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Nihil.SLOW + STAGGER * COLS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        pitch = min(available / COLS.toFloat(), maxPitch)
        setMeasuredDimension((pitch * COLS).toInt(), (pitch * ROWS).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        // Half a pitch around the panel, drawn outside the view's bounds so the
        // LEDs stay aligned with the text below (the parent doesn't clip).
        val inset = pitch * 0.5f
        canvas.drawRoundRect(-inset, -inset, width + inset, height + inset, pitch * 1.1f, pitch * 1.1f, plate)
        val total = (Nihil.SLOW + STAGGER * COLS).toFloat()
        val elapsed = progress * total
        val offR = pitch * 0.17f
        for (col in 0 until COLS) {
            val local = ((elapsed - col * STAGGER) / Nihil.SLOW).coerceIn(0f, 1f)
            val t = EASE.getInterpolation(local)
            val x = (col + 0.5f) * pitch
            for (row in 0 until ROWS) {
                val y = (row + 0.5f) * pitch
                val k = row * COLS + col
                val level = previous[k] + (current[k] - previous[k]) * t
                canvas.drawCircle(x, y, offR, unlit)
                if (level > 0.01f) {
                    lit.alpha = (255 * level).toInt()
                    canvas.drawCircle(x, y, pitch * (0.30f + 0.12f * level), lit)
                }
            }
        }
    }
}
