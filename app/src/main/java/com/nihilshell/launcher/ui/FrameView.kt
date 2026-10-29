package com.nihilshell.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.nihilshell.launcher.system.Glyph
import kotlin.math.exp
import kotlin.math.min

/**
 * The NIHIL frame on a phone: the screen is black and the wallpaper is a
 * rounded card set into it, like the desktop's frame. The status bar and the
 * gesture bar live on the frame.
 *
 * The drawer is the frame growing: as it opens, the card's bottom edge rises
 * until the whole screen is black.
 *
 * The Glyph light also lives here: LEDs along the frame, just outside the
 * card, that a notification lights up from the top (white), a call or an
 * alarm in red, and the charger from the bottom.
 */
class FrameView(context: Context) : View(context) {

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Nihil.VOID }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val card = RectF()

    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var radius = context.dp(24f)

    /** 0 = home (whole card), 1 = drawer open (no card). */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Thickness of the frame on each side, and the corner radius of the card. */
    fun setFrame(left: Int, top: Int, right: Int, bottom: Int, radius: Float) {
        if (left == this.left && top == this.top && right == this.right && bottom == this.bottom && radius == this.radius) return
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        this.radius = radius
        layoutGlyph()
        invalidate()
    }

    /** Height the drawer travels: the card's full height. */
    val travel: Float get() = (height - top - bottom).toFloat().coerceAtLeast(1f)

    /** Where the card's bottom edge is for the current [progress]. */
    val cardBottom: Float get() = height - bottom - progress * travel

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutGlyph()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        card.set(left.toFloat(), top.toFloat(), w - right, cardBottom)
        if (card.height() <= 0.5f) {
            canvas.drawRect(0f, 0f, w, h, black)
            return
        }
        val r = min(radius, card.height() / 2)
        shape.reset()
        shape.fillType = Path.FillType.EVEN_ODD
        shape.addRect(0f, 0f, w, h, Path.Direction.CW)
        shape.addRoundRect(card, r, r, Path.Direction.CW)
        canvas.drawPath(shape, black)
        drawGlyph(canvas)
    }

    // --- Glyph light ---------------------------------------------------------

    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    /** Distance of each LED from the top centre along the frame, either way round. */
    private var fromTop = FloatArray(0)
    private var half = 1f
    private var glyph: ValueAnimator? = null
    private var glyphTime = 0f
    private var glyphPulse = Glyph.Pulse.NOTIFY

    private fun layoutGlyph() {
        if (width == 0 || height == 0) return
        val out = context.dp(3f)
        val rect = RectF(left - out, top - out, width - right + out, height - bottom + out)
        val r = radius + out
        // Clockwise from the top centre, so the bottom centre is exactly half way.
        val path = Path().apply {
            moveTo(rect.centerX(), rect.top)
            lineTo(rect.right - r, rect.top)
            arcTo(RectF(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r), 270f, 90f)
            lineTo(rect.right, rect.bottom - r)
            arcTo(RectF(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom), 0f, 90f)
            lineTo(rect.left + r, rect.bottom)
            arcTo(RectF(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom), 90f, 90f)
            lineTo(rect.left, rect.top + r)
            arcTo(RectF(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r), 180f, 90f)
            close()
        }
        val measure = PathMeasure(path, true)
        val length = measure.length
        val count = (length / context.dp(7f)).toInt().coerceAtLeast(8)
        xs = FloatArray(count)
        ys = FloatArray(count)
        fromTop = FloatArray(count)
        val pos = FloatArray(2)
        for (i in 0 until count) {
            val d = i * length / count
            measure.getPosTan(d, pos, null)
            xs[i] = pos[0]
            ys[i] = pos[1]
            fromTop[i] = min(d, length - d)
        }
        half = length / 2
    }

    /** Runs the Glyph light once (twice for a call or an alarm). Only on the home card. */
    fun pulse(pulse: Glyph.Pulse) {
        if (progress > 0.01f || xs.isEmpty() || !isShown) return
        glyph?.cancel()
        glyphPulse = pulse
        val runs = if (pulse == Glyph.Pulse.SIGNAL) 2 else 1
        glyph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RUN
            repeatCount = runs - 1
            interpolator = LinearInterpolator()
            addUpdateListener {
                glyphTime = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    glyph = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun drawGlyph(canvas: Canvas) {
        if (glyph == null || progress > 0.01f) return
        val color = if (glyphPulse == Glyph.Pulse.SIGNAL) Nihil.SIGNAL else Nihil.INK
        // The head runs from the start to the opposite point in the first 70 %,
        // then the whole strip fades.
        val head = EASE.getInterpolation((glyphTime / 0.7f).coerceAtMost(1f)) * half * 1.02f
        val fade = 1f - ((glyphTime - 0.7f) / 0.3f).coerceIn(0f, 1f)
        val tail = half * 0.22f
        val base = context.dp(1.8f)
        for (i in xs.indices) {
            val d = if (glyphPulse == Glyph.Pulse.CHARGE) half - fromTop[i] else fromTop[i]
            val behind = head - d
            if (behind < 0f) continue
            val level = exp(-behind / tail) * 0.85f + 0.15f
            val b = (level * fade).coerceIn(0f, 1f)
            if (b < 0.02f) continue
            led.color = withAlpha(color, b)
            canvas.drawCircle(xs[i], ys[i], base * (0.7f + 0.5f * b), led)
        }
    }

    override fun onDetachedFromWindow() {
        glyph?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val RUN = 1500L
    }
}
