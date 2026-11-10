package com.nihilshell.launcher.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.nihilshell.launcher.ui.Nihil

/**
 * App icons in the NIHIL language.
 *
 *  MONO   the app's themed icon (Android 13) in ink. Apps without one get an
 *         "auto-mono" icon: the foreground layer alone, in greys, inverted
 *         when it's dark so it still reads on black. Icons that are a full
 *         picture fall back to GREY.
 *  GREY   the whole icon without colour, slightly dimmed.
 *  COLOR  the icon as the app ships it.
 *
 * Work-profile apps get a small LED as a badge.
 */
object IconRenderer {

    fun render(icon: Drawable, size: Int, style: IconStyle, badge: Boolean = false, paused: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // A paused work profile: its apps half lit, as LEDs switched off.
        if (paused) canvas.saveLayerAlpha(0f, 0f, size.toFloat(), size.toFloat(), 115)
        val drawn = when (style) {
            IconStyle.MONO -> drawThemed(canvas, icon, size) || drawAutoMono(canvas, icon, size)
            IconStyle.COLOR -> {
                drawPlain(canvas, icon, size, null)
                true
            }
            IconStyle.GREY -> false
        }
        if (!drawn) drawPlain(canvas, icon, size, greyFilter())
        if (paused) canvas.restore()
        if (badge) drawBadge(canvas, size)
        return bitmap
    }

    /**
     * A web's icon: its site's own on the surface the deck gives a web, in greys (in mono,
     * inverted when it is dark, so it reads on black), or its letter without one.
     */
    fun renderWeb(favicon: Bitmap?, label: String, size: Int, style: IconStyle, face: Typeface): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = (size / 46f).coerceAtLeast(1f)
        val box = RectF(edge, edge, size - edge, size - edge)
        val radius = size * 0.3f
        paint.color = Nihil.SURFACE
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = edge
        paint.color = Nihil.LINE
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.style = Paint.Style.FILL
        if (favicon != null) {
            val inner = size * 0.56f
            val at = (size - inner) / 2
            val draw = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (style != IconStyle.COLOR) draw.colorFilter = ColorMatrixColorFilter(webMatrix(favicon, style))
            canvas.drawBitmap(favicon, null, RectF(at, at, at + inner, at + inner), draw)
        } else {
            paint.color = Nihil.DIM
            paint.typeface = face
            paint.textSize = size * 0.42f
            paint.textAlign = Paint.Align.CENTER
            val letter = label.trim().take(1).uppercase()
            canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return bitmap
    }

    /** Greys; in mono, a dark icon inverted, as the apps' auto-mono does. */
    private fun webMatrix(favicon: Bitmap, style: IconStyle): ColorMatrix {
        val matrix = ColorMatrix(LUMINANCE)
        if (style == IconStyle.MONO) {
            val small = Bitmap.createScaledBitmap(favicon, 24, 24, true)
            val pixels = IntArray(24 * 24)
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            if (LayerStats.of(pixels, circleArea = 24.0 * 24).mean < 0.40) matrix.postConcat(ColorMatrix(INVERT))
        } else {
            matrix.postConcat(ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
        }
        return matrix
    }

    /** Android 13 themed icon: the monochrome layer, tinted ink. */
    private fun drawThemed(canvas: Canvas, icon: Drawable, size: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val mono = (icon as? AdaptiveIconDrawable)?.monochrome?.mutate() ?: return false
        layerBounds(mono, size)
        mono.colorFilter = PorterDuffColorFilter(Nihil.INK, PorterDuff.Mode.SRC_IN)
        mono.draw(canvas)
        return true
    }

    /** The foreground layer alone, in greys; false when it is a full picture. */
    private fun drawAutoMono(canvas: Canvas, icon: Drawable, size: Int): Boolean {
        val foreground = (icon as? AdaptiveIconDrawable)?.foreground?.mutate() ?: return false

        // Paint the layer, clipped to the icon's circle, and measure it.
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        val clip = Path().apply { addCircle(size / 2f, size / 2f, size * 0.48f, Path.Direction.CW) }
        c.clipPath(clip)
        layerBounds(foreground, size)
        foreground.draw(c)

        val pixels = IntArray(size * size)
        layer.getPixels(pixels, 0, size, 0, 0, size, size)
        val stats = LayerStats.of(pixels, circleArea = Math.PI * (size * 0.48) * (size * 0.48))
        if (stats.coverage > 0.72 || stats.coverage < 0.01) {
            layer.recycle()
            return false
        }

        val matrix = ColorMatrix(LUMINANCE)
        var peak = stats.peak
        if (stats.mean < 0.40) {
            matrix.postConcat(ColorMatrix(INVERT))
            peak = 1.0 - stats.low
        }
        val gain = (0.95 / peak.coerceAtLeast(0.2)).coerceIn(1.0, 2.5).toFloat()
        matrix.postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(layer, 0f, 0f, paint)
        layer.recycle()
        return true
    }

    private fun drawPlain(canvas: Canvas, icon: Drawable, size: Int, filter: ColorMatrixColorFilter?) {
        val d = icon.mutate()
        d.setBounds(0, 0, size, size)
        d.colorFilter = filter
        d.draw(canvas)
        d.colorFilter = null
    }

    private fun drawBadge(canvas: Canvas, size: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size * 0.13f
        val cx = size - r * 1.25f
        val cy = size - r * 1.25f
        paint.color = Nihil.VOID
        canvas.drawCircle(cx, cy, r * 1.45f, paint)
        paint.color = Nihil.INK
        canvas.drawCircle(cx, cy, r, paint)
    }

    /** Adaptive layers are 108 units for a 72-unit icon: 25 % more on every side. */
    private fun layerBounds(layer: Drawable, size: Int) {
        val extra = size / 4
        layer.setBounds(-extra, -extra, size + extra, size + extra)
    }

    private fun greyFilter(): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
        return ColorMatrixColorFilter(m)
    }

    private val LUMINANCE = floatArrayOf(
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private val INVERT = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * Luminance statistics of a layer, weighted by alpha: how much of the icon it
 * covers, its mean, and its brightest and darkest visible levels (0..1).
 * Pure Kotlin, tested on the JVM.
 */
class LayerStats(val coverage: Double, val mean: Double, val peak: Double, val low: Double) {
    companion object {
        fun of(pixels: IntArray, circleArea: Double): LayerStats {
            var weight = 0.0
            var sum = 0.0
            var peak = 0.0
            var low = 1.0
            for (c in pixels) {
                val a = (c ushr 24) / 255.0
                if (a <= 0.0) continue
                // Bitmap.getPixels returns non-premultiplied ARGB: the channels are the colour.
                val r = (c shr 16 and 0xFF) / 255.0
                val g = (c shr 8 and 0xFF) / 255.0
                val b = (c and 0xFF) / 255.0
                val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
                weight += a
                sum += a * l
                if (a > 0.5) {
                    if (l > peak) peak = l
                    if (l < low) low = l
                }
            }
            if (weight <= 0.0) return LayerStats(0.0, 0.0, 0.0, 0.0)
            return LayerStats(
                coverage = (weight / circleArea).coerceAtMost(1.0),
                mean = sum / weight,
                peak = if (peak > 0.0) peak else sum / weight,
                low = if (low < 1.0) low else sum / weight,
            )
        }
    }
}
