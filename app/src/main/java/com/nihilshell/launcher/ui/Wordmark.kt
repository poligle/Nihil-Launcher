package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.nihilshell.launcher.core.BrandFace

/**
 * NIHIL as the logo and the desktop's Preferences have it (Nihil-Shell's
 * settings/Logo.qml): lit LEDs for the letters, faint ones around them.
 * [size] is its height in pixels.
 */
class Wordmark(context: Context, private val size: Int) : View(context) {
    private val columns = BrandFace.columns()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "NIHIL"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((columns.size * size / BrandFace.ROWS.toFloat()).toInt() + 1, size)
    }

    override fun onDraw(canvas: Canvas) {
        val pitch = size / BrandFace.ROWS.toFloat()
        columns.forEachIndexed { x, column ->
            for (y in 0 until BrandFace.ROWS) {
                val lit = column[y]
                paint.color = if (lit) Nihil.INK else Nihil.OFF
                canvas.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, (if (lit) 0.36f else 0.12f) * pitch, paint)
            }
        }
    }
}
