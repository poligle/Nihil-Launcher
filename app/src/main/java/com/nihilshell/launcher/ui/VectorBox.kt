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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

class VectorBox(context: Context) : FrameLayout(context) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
    private var head = -1f
    private var flash = 0f
    private var flashColor = Nihil.INK
    private var shown = false

    var busy = false
        set(value) {
            field = value
            if (value && !round.isRunning) round.start()
            invalidate()
        }

    private val round = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            head = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (busy && shown) {
                    animation.start()
                } else {
                    head = -1f
                    invalidate()
                }
            }
        })
    }

    private val flare = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 520
        addUpdateListener {
            flash = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun lap() {
        if (!round.isRunning) round.start()
    }

    fun blink(color: Int) {
        flashColor = color
        flare.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = context.dp(2f)
        val radius = context.dp(14f)
        outline.reset()
        outline.addRoundRect(RectF(inset, inset, w - inset, h - inset), radius, radius, Path.Direction.CW)
        measure.setPath(outline, true)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shown = true
        if (busy) round.start()
    }

    override fun onDetachedFromWindow() {
        shown = false
        round.cancel()
        flare.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val length = measure.length
        if (length <= 0f) return
        val count = maxOf(8, (length / context.dp(7f)).toInt())
        val radius = context.dp(1.5f)
        val base = if (isPressed || busy) Nihil.MUTED else Nihil.OFF
        for (i in 0 until count) {
            measure.getPosTan(i * length / count, point, null)
            var color = base
            if (head >= 0f) {
                val behind = (head - i.toFloat() / count + 1f) % 1f
                if (behind < 0.3f) color = blend(color, Nihil.INK, 1f - behind / 0.3f)
            }
            if (flash > 0f) color = blend(color, flashColor, flash)
            dot.color = color
            canvas.drawCircle(point[0], point[1], radius, dot)
        }
    }
}
