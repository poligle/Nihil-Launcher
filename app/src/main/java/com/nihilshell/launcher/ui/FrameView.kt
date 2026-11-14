package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.nihilshell.launcher.system.Glyph
import kotlin.math.min

class FrameView(context: Context) : View(context) {
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Nihil.VOID }
    private val shape = Path()
    private val card = RectF()
    private val at = IntArray(2)

    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var radius = context.dp(24f)

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

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

    val travel: Float get() = (height - top - bottom).toFloat().coerceAtLeast(1f)

    val cardBottom: Float get() = height - bottom - progress * travel

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutGlyph()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        card.set(left.toFloat(), top.toFloat(), w - right, cardBottom)
        if (card.height() <= 0.5f) {
            if (Glass.enabled) {
                shape.reset()
                shape.addRect(0f, 0f, w, h, Path.Direction.CW)
                card.set(0f, h + 4096f, w, h + 8192f)
                getLocationOnScreen(at)
                Glass.paint(canvas, context, shape, card, 0f, Nihil.VOID, true, at[0].toFloat(), at[1].toFloat(), true)
            } else {
                canvas.drawRect(0f, 0f, w, h, black)
            }
            return
        }
        val r = min(radius, card.height() / 2)
        shape.reset()
        shape.fillType = Path.FillType.EVEN_ODD
        shape.addRect(0f, 0f, w, h, Path.Direction.CW)
        shape.addRoundRect(card, r, r, Path.Direction.CW)
        if (Glass.enabled) {
            getLocationOnScreen(at)
            Glass.paint(canvas, context, shape, card, r, Nihil.VOID, true, at[0].toFloat(), at[1].toFloat(), true)
        } else {
            canvas.drawPath(shape, black)
        }
        drawGlyph(canvas)
    }

    private val halo = Halo(this)

    private fun layoutGlyph() {
        if (width == 0 || height == 0) return
        halo.setRing(RectF(left.toFloat(), top.toFloat(), (width - right).toFloat(), (height - bottom).toFloat()), radius)
    }

    fun pulse(pulse: Glyph.Pulse) {
        if (progress > 0.01f || !isShown) return
        halo.play(pulse)
    }

    private fun drawGlyph(canvas: Canvas) {
        if (progress <= 0.01f) halo.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        halo.cancel()
        super.onDetachedFromWindow()
    }
}
