package com.nihilshell.launcher.core

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import kotlin.math.roundToInt

/** Android side of [DotMatrix]: decode a photo to the screen and draw the LEDs. */
object DotRender {

    /** The photo at [uri], scaled and centre-cropped to exactly [width] x [height]. */
    fun decodeCover(resolver: ContentResolver, uri: Uri, width: Int, height: Int): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (scale, _, _) = DotMatrix.cover(info.size.width, info.size.height, width, height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
        val (scale, dx, dy) = DotMatrix.cover(decoded.width, decoded.height, width, height)
        val w = (decoded.width * scale).roundToInt().coerceAtLeast(width)
        val h = (decoded.height * scale).roundToInt().coerceAtLeast(height)
        val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
        return Bitmap.createBitmap(scaled, dx, dy, width, height)
    }

    fun grid(photo: Bitmap, pitch: Int): DotMatrix.Grid {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        return DotMatrix.grid(pixels, photo.width, photo.height, DotMatrix.Params(pitch))
    }

    fun render(grid: DotMatrix.Grid, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val off = grid.offGrey
        val offColor = Color.rgb(off, off, off)
        val offRadius = grid.offRadius
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val (x, y) = grid.center(col, row)
                paint.color = offColor
                canvas.drawCircle(x, y, offRadius, paint)
                val led = grid.lit(col, row) ?: continue
                paint.color = Color.rgb(led.grey, led.grey, led.grey)
                canvas.drawCircle(led.x, led.y, led.radius, paint)
            }
        }
        return bitmap
    }
}
