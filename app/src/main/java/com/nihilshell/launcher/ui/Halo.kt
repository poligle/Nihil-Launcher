package com.nihilshell.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.nihilshell.launcher.system.Glyph
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The Glyph light, as the desktop draws it (home/shell/frame/GlyphLight.qml):
 *   NOTIFY  a segment runs once round the ring, clockwise from the top-right
 *           corner, with a soft glow under it
 *   SIGNAL  the same in red, twice: a call or an alarm
 *   CHARGE  two lights climb from the middle of the bottom edge and meet at
 *           the top, leaving a fading trail, then it all goes out
 * The ring is a rounded rectangle: the card's edge on the home screen
 * (FrameView), the screen's own edge over other apps (HaloOverlay). Lengths
 * are the desktop's, as parts of the way round.
 */
class Halo(private val view: View) {
    private val density = view.resources.displayMetrics.density
    private val ring = Path()
    private val measure = PathMeasure()
    private val piece = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var perimeter = 0f
    /** Where the top-right corner starts, measured from the middle of the bottom edge. */
    private var corner = 0f

    private var animator: ValueAnimator? = null
    private var pulse = Glyph.Pulse.NOTIFY
    private var value = 0f

    val running: Boolean get() = animator != null

    /** The ring: [rect], with corners of [radius]. It starts at the middle of the bottom edge and goes clockwise. */
    fun setRing(rect: RectF, radius: Float) {
        val r = radius.coerceIn(0f, min(rect.width(), rect.height()) / 2)
        ring.reset()
        ring.moveTo(rect.centerX(), rect.bottom)
        ring.lineTo(rect.left + r, rect.bottom)
        ring.arcTo(RectF(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom), 90f, 90f)
        ring.lineTo(rect.left, rect.top + r)
        ring.arcTo(RectF(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r), 180f, 90f)
        ring.lineTo(rect.right - r, rect.top)
        ring.arcTo(RectF(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r), 270f, 90f)
        ring.lineTo(rect.right, rect.bottom - r)
        ring.arcTo(RectF(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom), 0f, 90f)
        ring.close()
        measure.setPath(ring, true)
        perimeter = measure.length
        val arc = (PI * r / 2).toFloat()
        corner = (rect.width() / 2 - r) + arc + (rect.height() - 2 * r) + arc + (rect.width() - 2 * r)
        view.invalidate()
    }

    /** Runs [kind] once; [onEnd] when it has gone out (not when another one replaces it). */
    fun play(kind: Glyph.Pulse, onEnd: (() -> Unit)? = null) {
        val old = animator
        animator = null
        old?.removeAllListeners()
        old?.cancel()
        pulse = kind
        val charge = kind == Glyph.Pulse.CHARGE
        val next = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (charge) RISE else SWEEP
            repeatCount = if (kind == Glyph.Pulse.SIGNAL) 1 else 0
            interpolator = if (charge) LinearInterpolator() else IN_OUT_CUBIC
            addUpdateListener {
                value = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    view.invalidate()
                    onEnd?.invoke()
                }
            })
        }
        animator = next
        next.start()
    }

    fun cancel() {
        animator?.cancel()
    }

    fun draw(canvas: Canvas) {
        if (animator == null || perimeter <= 0f) return
        if (pulse == Glyph.Pulse.CHARGE) drawRise(canvas) else drawSweep(canvas)
    }

    // A segment of 3.25 % of the way round (260 px of a laptop's frame),
    // entering at the corner and leaving there after a whole turn.
    private fun drawSweep(canvas: Canvas) {
        val tint = if (pulse == Glyph.Pulse.SIGNAL) Nihil.SIGNAL else Nihil.INK
        val segment = max(perimeter * 0.0325f, 48 * density)
        val head = value * (perimeter + segment)
        val from = corner + max(0f, head - segment)
        val to = corner + min(perimeter, head)
        stroke(canvas, from, to, withAlpha(tint, 0.22f), 7 * density)
        stroke(canvas, from, to, tint, 2 * density)
    }

    // 70 % climbing, eased as every NIHIL motion, then 30 % going out.
    private fun drawRise(canvas: Canvas) {
        val climb = EASE.getInterpolation((value / 0.7f).coerceAtMost(1f))
        val out = ((value - 0.7f) / 0.3f).coerceIn(0f, 1f)
        val fade = 1f - (1f - (1f - out).pow(3))
        val half = perimeter / 2
        val reach = climb * half
        if (reach <= 1f || fade <= 0f) return
        val long = half * 0.12f
        val short = max(half * 0.05f, 24 * density)
        for (side in intArrayOf(-1, 1)) {
            beam(canvas, side, reach, reach, 0.14f * fade, 2 * density)
            beam(canvas, side, reach, long, 0.26f * fade, 2 * density)
            beam(canvas, side, reach, short, 0.22f * fade, 7 * density)
            beam(canvas, side, reach, short, fade, 2 * density)
        }
    }

    /** The stretch of one side's route that ends at [reach] and is [length] long. */
    private fun beam(canvas: Canvas, side: Int, reach: Float, length: Float, alpha: Float, width: Float) {
        val tail = max(0f, reach - length)
        if (side < 0) stroke(canvas, tail, reach, withAlpha(Nihil.INK, alpha), width)
        else stroke(canvas, perimeter - reach, perimeter - tail, withAlpha(Nihil.INK, alpha), width)
    }

    /** The ring from [from] to [to], distances from the middle of the bottom edge: they wrap. */
    private fun stroke(canvas: Canvas, from: Float, to: Float, color: Int, width: Float) {
        var length = min(to - from, perimeter)
        if (length <= 0.5f) return
        var at = ((from % perimeter) + perimeter) % perimeter
        paint.color = color
        paint.strokeWidth = width
        while (length > 0.5f) {
            val end = min(at + length, perimeter)
            piece.reset()
            measure.getSegment(at, end, piece, true)
            canvas.drawPath(piece, paint)
            length -= end - at
            at = 0f
        }
    }

    private companion object {
        const val SWEEP = 1100L
        const val RISE = 1500L
        val IN_OUT_CUBIC = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    }
}
