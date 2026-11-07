package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.nihilshell.launcher.system.Glyph
import kotlin.math.min

/**
 * The NIHIL frame on a phone: the screen is black and the wallpaper is a
 * rounded card set into it, like the desktop's frame. The status bar and the
 * gesture bar live on the frame.
 *
 * The drawer is the frame growing: as it opens, the card's bottom edge rises
 * until the whole screen is black.
 *
 * The Glyph light also lives here, on the card's edge, as on the desktop's
 * frame (Halo): a notification runs round it, a call or an alarm in red,
 * and the charger rises from the bottom.
 */
class FrameView(context: Context) : View(context) {

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Nihil.VOID }
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

    private val halo = Halo(this)

    private fun layoutGlyph() {
        if (width == 0 || height == 0) return
        halo.setRing(RectF(left.toFloat(), top.toFloat(), (width - right).toFloat(), (height - bottom).toFloat()), radius)
    }

    /** Runs the Glyph light round the card, as the desktop's frame does. Only on the home card. */
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
