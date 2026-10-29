package com.nihilshell.launcher.core

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A photo as an LED matrix, the same algorithm as the desktop wallpapers
 * (theme/dotmatrix.py): the image, already cover-cropped to the screen, is
 * split into cells of [Params.pitch] pixels. Each cell gets an unlit LED, and a
 * lit one whose size and brightness grow with the cell's luminance. The black
 * point is raised so the sky stays off, and isolated peaks (stars) light their
 * cell even when its average is dark. Pure Kotlin, tested on the JVM.
 */
object DotMatrix {

    class Params(
        val pitch: Int,
        val gamma: Float = 1.25f,
        val black: Float = 0.10f,
        val star: Float = 0.55f,
        val starGain: Float = 0.9f,
        val offLevel: Float = 0.07f,
    )

    /** One LED: centre, radius and grey level (0-255). */
    class Led(val x: Float, val y: Float, val radius: Float, val grey: Int)

    class Grid(
        val cols: Int,
        val rows: Int,
        val originX: Int,
        val originY: Int,
        val params: Params,
        /** Brightness 0..1 of every cell, row by row. */
        val levels: FloatArray,
    ) {
        /** Grey of the unlit LEDs and their radius. */
        val offGrey: Int get() = (255 * params.offLevel).roundToInt()
        val offRadius: Float get() = 0.16f * params.pitch

        fun center(col: Int, row: Int): Pair<Float, Float> = Pair(
            originX + col * params.pitch + params.pitch / 2f,
            originY + row * params.pitch + params.pitch / 2f,
        )

        /** The lit LED of a cell, or null when it stays off. */
        fun lit(col: Int, row: Int): Led? {
            val v = levels[row * cols + col]
            if (v <= 0.02f) return null
            val grey = (30 + 206 * v).roundToInt()
            if (grey <= offGrey) return null
            val (x, y) = center(col, row)
            val radius = 0.44f * params.pitch * (0.25f + 0.75f * sqrt(v))
            return Led(x, y, radius, grey)
        }
    }

    /** [pixels]: ARGB, [width] x [height], already cropped to the screen. */
    fun grid(pixels: IntArray, width: Int, height: Int, params: Params): Grid {
        require(pixels.size >= width * height) { "not enough pixels" }
        val pitch = params.pitch.coerceAtLeast(2)

        // Luminance, normalised between the 2nd and 99.7th percentiles.
        val lum = FloatArray(width * height)
        val histogram = IntArray(BINS)
        for (k in 0 until width * height) {
            val c = pixels[k]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lum[k] = l
            histogram[(l * (BINS - 1)).roundToInt().coerceIn(0, BINS - 1)]++
        }
        val low = percentile(histogram, width * height, 0.02f)
        val high = percentile(histogram, width * height, 0.997f)
        val span = max(high - low, 1e-6f)

        val cols = width / pitch
        val rows = height / pitch
        val ox = (width - cols * pitch) / 2
        val oy = (height - rows * pitch) / 2
        val levels = FloatArray(cols * rows)
        val area = (pitch * pitch).toFloat()

        for (j in 0 until rows) {
            for (i in 0 until cols) {
                var sum = 0f
                var peak = 0f
                for (y in oy + j * pitch until oy + (j + 1) * pitch) {
                    val row = y * width
                    for (x in ox + i * pitch until ox + (i + 1) * pitch) {
                        val v = ((lum[row + x] - low) / span).coerceIn(0f, 1f)
                        sum += v
                        if (v > peak) peak = v
                    }
                }
                val mean = sum / area
                var level = ((mean - params.black) / (1f - params.black)).coerceIn(0f, 1f).pow(params.gamma)
                val star = ((peak - params.star) / (1f - params.star)).coerceIn(0f, 1f)
                level = max(level, params.starGain * star)
                levels[j * cols + i] = level
            }
        }
        return Grid(cols, rows, ox, oy, params, levels)
    }

    private const val BINS = 1024

    /** The value below which a fraction [q] of the pixels lie, from a histogram. */
    fun percentile(histogram: IntArray, total: Int, q: Float): Float {
        val target = q * total
        var seen = 0
        for (bin in histogram.indices) {
            seen += histogram[bin]
            if (seen >= target) return bin / (histogram.size - 1f)
        }
        return 1f
    }

    /** Scale and offsets that make [srcW] x [srcH] cover [dstW] x [dstH], centred. */
    fun cover(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Triple<Float, Int, Int> {
        val scale = max(dstW / srcW.toFloat(), dstH / srcH.toFloat())
        val w = (srcW * scale).roundToInt().coerceAtLeast(dstW)
        val h = (srcH * scale).roundToInt().coerceAtLeast(dstH)
        return Triple(scale, (w - dstW) / 2, (h - dstH) / 2)
    }
}
