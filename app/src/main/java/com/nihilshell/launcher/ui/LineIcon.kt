package com.nihilshell.launcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.util.EnumMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * NIHIL line icons, drawn in code on a 24-unit grid: 1.6-unit strokes, round
 * caps, no fills except LED dots. The same family as the desktop's icons.
 */
class LineIcon(
    private val shape: Shape,
    color: Int = Nihil.INK,
    private val sizePx: Int = -1,
) : Drawable() {

    enum class Shape {
        SEARCH, CHEVRON, UP, DOWN, SETTINGS, GEAR, LOCK, IMAGE, TORCH, WIFI, BLUETOOTH,
        BATTERY, SOUND, ALARM, INFO, TRASH, HIDE, PLUS, MINUS, CHECK, CLOSE, CALC, BOLT,
        ENTER, WEB, HOME, BELL,
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.6f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    var color: Int
        get() = stroke.color
        set(value) {
            stroke.color = value
            fill.color = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.exactCenterX() - 12f * s, b.exactCenterY() - 12f * s)
        canvas.scale(s, s)
        canvas.drawPath(path(shape), stroke)
        for (d in dots(shape)) canvas.drawCircle(d[0], d[1], d[2], fill)
        canvas.restore()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val cache = EnumMap<Shape, Path>(Shape::class.java)

        private fun path(shape: Shape): Path = cache.getOrPut(shape) { Path().also { build(shape, it) } }

        private fun dots(shape: Shape): List<FloatArray> = when (shape) {
            Shape.LOCK -> listOf(floatArrayOf(12f, 15.5f, 1.1f))
            Shape.INFO -> listOf(floatArrayOf(12f, 7.8f, 1.1f))
            Shape.WIFI -> listOf(floatArrayOf(12f, 18.5f, 1.3f))
            Shape.IMAGE -> listOf(floatArrayOf(15.8f, 9.2f, 1.5f))
            Shape.TORCH -> listOf(floatArrayOf(12f, 14f, 1.1f))
            Shape.CALC -> listOf(
                floatArrayOf(8.6f, 13f, 1f), floatArrayOf(12f, 13f, 1f), floatArrayOf(15.4f, 13f, 1f),
                floatArrayOf(8.6f, 17f, 1f), floatArrayOf(12f, 17f, 1f), floatArrayOf(15.4f, 17f, 1f),
            )
            else -> emptyList()
        }

        private fun Path.line(vararg p: Float) {
            moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) {
                lineTo(p[i], p[i + 1])
                i += 2
            }
        }

        private fun Path.arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            addArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep)

        private fun build(shape: Shape, p: Path) {
            when (shape) {
                Shape.SEARCH -> {
                    p.addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW)
                    p.line(15.3f, 15.3f, 20f, 20f)
                }
                Shape.CHEVRON -> p.line(9f, 6f, 15f, 12f, 9f, 18f)
                Shape.UP -> p.line(6f, 15f, 12f, 9f, 18f, 15f)
                Shape.DOWN -> p.line(6f, 9f, 12f, 15f, 18f, 9f)
                Shape.SETTINGS -> {
                    // Three sliders.
                    p.line(4f, 7f, 7f, 7f); p.line(11f, 7f, 20f, 7f)
                    p.addCircle(9f, 7f, 2f, Path.Direction.CW)
                    p.line(4f, 12f, 13f, 12f); p.line(17f, 12f, 20f, 12f)
                    p.addCircle(15f, 12f, 2f, Path.Direction.CW)
                    p.line(4f, 17f, 5f, 17f); p.line(9f, 17f, 20f, 17f)
                    p.addCircle(7f, 17f, 2f, Path.Direction.CW)
                }
                Shape.GEAR -> {
                    val teeth = 8
                    for (i in 0 until teeth) {
                        val a = Math.toRadians(i * 360.0 / teeth)
                        val half = Math.toRadians(360.0 / teeth / 2)
                        val pts = listOf(
                            a - half + 0.12 to 6.4, a - 0.2 to 8.6, a + 0.2 to 8.6, a + half - 0.12 to 6.4,
                        )
                        pts.forEachIndexed { k, (t, r) ->
                            val x = 12f + (r * cos(t)).toFloat()
                            val y = 12f + (r * sin(t)).toFloat()
                            if (i == 0 && k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                    }
                    p.close()
                    p.addCircle(12f, 12f, 2.8f, Path.Direction.CW)
                }
                Shape.LOCK -> {
                    p.addRoundRect(RectF(5f, 11f, 19f, 20.5f), 2.2f, 2.2f, Path.Direction.CW)
                    p.moveTo(8f, 11f)
                    p.lineTo(8f, 8f)
                    p.arcTo(RectF(8f, 4f, 16f, 12f), 180f, 180f)
                    p.lineTo(16f, 11f)
                }
                Shape.IMAGE -> {
                    p.addRoundRect(RectF(3.5f, 5f, 20.5f, 19f), 2.5f, 2.5f, Path.Direction.CW)
                    p.line(3.5f, 17f, 9f, 11.5f, 13f, 15.5f, 15.5f, 13f, 20.5f, 18f)
                }
                Shape.TORCH -> {
                    p.line(7f, 3f, 17f, 3f, 17f, 6f, 14.5f, 10f, 14.5f, 21f, 9.5f, 21f, 9.5f, 10f, 7f, 6f)
                    p.close()
                    p.line(7f, 6f, 17f, 6f)
                }
                Shape.WIFI -> {
                    p.arc(12f, 18.5f, 4.5f, 225f, 90f)
                    p.arc(12f, 18.5f, 9f, 225f, 90f)
                    p.arc(12f, 18.5f, 13.5f, 230f, 80f)
                }
                Shape.BLUETOOTH -> p.line(7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f)
                Shape.BATTERY -> {
                    p.addRoundRect(RectF(3f, 7.5f, 19f, 16.5f), 2.2f, 2.2f, Path.Direction.CW)
                    p.line(21f, 10.5f, 21f, 13.5f)
                    p.line(6.5f, 12f, 11.5f, 12f)
                }
                Shape.SOUND -> {
                    p.line(4f, 9.5f, 7.5f, 9.5f, 12f, 5.5f, 12f, 18.5f, 7.5f, 14.5f, 4f, 14.5f)
                    p.close()
                    p.arc(12f, 12f, 4f, -40f, 80f)
                    p.arc(12f, 12f, 7.5f, -45f, 90f)
                }
                Shape.ALARM -> {
                    p.addCircle(12f, 13f, 7f, Path.Direction.CW)
                    p.line(12f, 9.5f, 12f, 13f, 14.5f, 14.5f)
                    p.line(4.5f, 6f, 7f, 3.5f)
                    p.line(19.5f, 6f, 17f, 3.5f)
                }
                Shape.INFO -> {
                    p.addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                    p.line(12f, 11f, 12f, 16.5f)
                }
                Shape.TRASH -> {
                    p.line(4.5f, 6.5f, 19.5f, 6.5f)
                    p.line(9.5f, 6.5f, 9.5f, 4f, 14.5f, 4f, 14.5f, 6.5f)
                    p.line(6.5f, 6.5f, 7.5f, 20f, 16.5f, 20f, 17.5f, 6.5f)
                    p.line(10f, 10f, 10f, 16.5f)
                    p.line(14f, 10f, 14f, 16.5f)
                }
                Shape.HIDE -> {
                    p.moveTo(2.5f, 12f)
                    p.quadTo(12f, 3f, 21.5f, 12f)
                    p.quadTo(12f, 21f, 2.5f, 12f)
                    p.addCircle(12f, 12f, 3f, Path.Direction.CW)
                    p.line(4f, 4f, 20f, 20f)
                }
                Shape.PLUS -> {
                    p.line(12f, 5f, 12f, 19f)
                    p.line(5f, 12f, 19f, 12f)
                }
                Shape.MINUS -> p.line(5f, 12f, 19f, 12f)
                Shape.CHECK -> p.line(5f, 12.5f, 10f, 17.5f, 19f, 7f)
                Shape.CLOSE -> {
                    p.line(6f, 6f, 18f, 18f)
                    p.line(18f, 6f, 6f, 18f)
                }
                Shape.CALC -> {
                    p.addRoundRect(RectF(5f, 3f, 19f, 21f), 2.5f, 2.5f, Path.Direction.CW)
                    p.addRect(RectF(8f, 6f, 16f, 9f), Path.Direction.CW)
                }
                Shape.BOLT -> {
                    p.line(13f, 2.5f, 5.5f, 13.5f, 11.5f, 13.5f, 10.5f, 21.5f, 18.5f, 10f, 12.5f, 10f)
                    p.close()
                }
                Shape.ENTER -> {
                    p.line(19f, 5f, 19f, 13f, 6f, 13f)
                    p.line(10f, 9f, 6f, 13f, 10f, 17f)
                }
                Shape.WEB -> {
                    p.addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                    p.addOval(RectF(8.2f, 3.5f, 15.8f, 20.5f), Path.Direction.CW)
                    p.line(3.5f, 12f, 20.5f, 12f)
                }
                Shape.HOME -> {
                    p.line(4f, 11f, 12f, 4f, 20f, 11f)
                    p.line(6.5f, 9f, 6.5f, 20f, 17.5f, 20f, 17.5f, 9f)
                }
                Shape.BELL -> {
                    p.moveTo(6f, 16.5f)
                    p.lineTo(6f, 11f)
                    p.cubicTo(6f, 7f, 8.7f, 4.5f, 12f, 4.5f)
                    p.cubicTo(15.3f, 4.5f, 18f, 7f, 18f, 11f)
                    p.lineTo(18f, 16.5f)
                    p.lineTo(19.5f, 18f)
                    p.lineTo(4.5f, 18f)
                    p.close()
                    p.line(10f, 20.5f, 14f, 20.5f)
                }
            }
        }
    }
}
