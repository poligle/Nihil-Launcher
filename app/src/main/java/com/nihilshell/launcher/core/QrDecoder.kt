package com.nihilshell.launcher.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A QR reader on nothing but Kotlin, for the deck's pairing code: from a
 * picture's luminance (the camera's Y plane) to the text it holds.
 *
 * The steps every reader takes (ISO/IEC 18004, as ZXing takes them): a local
 * threshold; the three finder patterns; the grid sampled through a
 * perspective transform anchored on them and on the alignment pattern;
 * format and version; the data unmasked, read in its zigzag and split into
 * its blocks; Reed-Solomon; the segments. Versions 1 to 15 and numeric,
 * alphanumeric and byte segments: more than a pairing URI needs. Modules may
 * be dots, as the desktop draws them: the grid is sampled at their centres.
 */
object QrDecoder {

    /** The text in the QR code in [luminance] (one byte a pixel, row by row), or null. */
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width < 21 || height < 21 || luminance.size < width * height) return null
        // As it comes; then, for a grainy picture (little light), softened.
        return attempt(luminance, width, height) ?: attempt(soften(luminance, width, height), width, height)
    }

    /** A 3 × 3 box blur: the grain of a dark room goes, modules of three pixels and more stay. */
    internal fun soften(lum: ByteArray, w: Int, h: Int): ByteArray {
        val rows = IntArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val l = lum[o + max(x - 1, 0)].toInt() and 0xFF
                val c = lum[o + x].toInt() and 0xFF
                val r = lum[o + min(x + 1, w - 1)].toInt() and 0xFF
                rows[o + x] = l + c + r
            }
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val up = max(y - 1, 0) * w
            val down = min(y + 1, h - 1) * w
            val o = y * w
            for (x in 0 until w) out[o + x] = ((rows[up + x] + rows[o + x] + rows[down + x]) / 9).toByte()
        }
        return out
    }

    private fun attempt(luminance: ByteArray, width: Int, height: Int): String? {
        val image = binarize(luminance, width, height)
        val finders = Finders(image).find() ?: return null
        val (bottomLeft, topLeft, topRight) = order(finders)
        val module = moduleSize(image, topLeft, topRight, bottomLeft)
        if (module.isNaN() || module < 1f) return null
        val across = (distance(topLeft, topRight) / module).roundToInt()
        val down = (distance(topLeft, bottomLeft) / module).roundToInt()
        var estimate = (across + down) / 2 + 7
        when (estimate and 3) {
            0 -> estimate++
            2 -> estimate--
            3 -> estimate += 2
        }
        for (dimension in intArrayOf(estimate, estimate - 4, estimate + 4)) {
            if (dimension < 21 || dimension > 17 + 4 * MAX_VERSION) continue
            read(image, bottomLeft, topLeft, topRight, dimension, allowRetry = true)?.let { return it }
        }
        return null
    }

    // --- The picture as black and white --------------------------------------

    /** A black-and-white picture: true is dark. */
    internal class Bits(val width: Int, val height: Int) {
        val bits = BooleanArray(width * height)

        operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]

        operator fun set(x: Int, y: Int, value: Boolean) {
            bits[y * width + x] = value
        }
    }

    private const val BLOCK = 8

    /**
     * Each 8 × 8 block against the mean of the 5 × 5 blocks around it, so a
     * shadow or a glare across the code doesn't matter (ZXing's hybrid
     * binarizer). A flat block is taken for background.
     */
    internal fun binarize(lum: ByteArray, w: Int, h: Int): Bits {
        val out = Bits(w, h)
        if (w < 5 * BLOCK || h < 5 * BLOCK) {
            var sum = 0L
            for (i in 0 until w * h) sum += lum[i].toInt() and 0xFF
            val threshold = (sum / (w * h)).toInt()
            for (i in 0 until w * h) out.bits[i] = (lum[i].toInt() and 0xFF) < threshold
            return out
        }
        val subW = (w + BLOCK - 1) / BLOCK
        val subH = (h + BLOCK - 1) / BLOCK
        val points = IntArray(subW * subH)
        for (by in 0 until subH) {
            val y0 = min(by * BLOCK, h - BLOCK)
            for (bx in 0 until subW) {
                val x0 = min(bx * BLOCK, w - BLOCK)
                var sum = 0
                var lo = 255
                var hi = 0
                for (y in y0 until y0 + BLOCK) {
                    var o = y * w + x0
                    repeat(BLOCK) {
                        val v = lum[o++].toInt() and 0xFF
                        sum += v
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
                var average = sum shr 6
                if (hi - lo <= 24) {
                    average = lo / 2
                    if (by > 0 && bx > 0) {
                        val around = (points[(by - 1) * subW + bx] + 2 * points[by * subW + bx - 1] + points[(by - 1) * subW + bx - 1]) / 4
                        if (lo < around) average = around
                    }
                }
                points[by * subW + bx] = average
            }
        }
        for (by in 0 until subH) {
            val y0 = min(by * BLOCK, h - BLOCK)
            val cy = by.coerceIn(2, subH - 3)
            for (bx in 0 until subW) {
                val x0 = min(bx * BLOCK, w - BLOCK)
                val cx = bx.coerceIn(2, subW - 3)
                var sum = 0
                for (dy in -2..2) for (dx in -2..2) sum += points[(cy + dy) * subW + cx + dx]
                val threshold = sum / 25
                for (y in y0 until y0 + BLOCK) {
                    var o = y * w + x0
                    repeat(BLOCK) {
                        out.bits[o] = (lum[o].toInt() and 0xFF) <= threshold
                        o++
                    }
                }
            }
        }
        return out
    }

    // --- The finder patterns ---------------------------------------------------

    internal class Point(var x: Float, var y: Float, var size: Float, var count: Int = 1)

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Dark, light, dark three times as wide, light, dark: 1:1:3:1:1, across and down. */
    private fun crossesPattern(c: IntArray): Boolean {
        var total = 0
        for (v in c) {
            if (v == 0) return false
            total += v
        }
        if (total < 7) return false
        val m = total / 7f
        val slack = m / 2f
        return abs(m - c[0]) < slack && abs(m - c[1]) < slack && abs(3 * m - c[2]) < 3 * slack &&
            abs(m - c[3]) < slack && abs(m - c[4]) < slack
    }

    private fun centreFromEnd(c: IntArray, end: Int): Float = (end - c[4] - c[3]) - c[2] / 2f

    private class Finders(private val image: Bits) {
        private val found = ArrayList<Point>()

        fun find(): List<Point>? {
            val c = IntArray(5)
            var skip = max(3, (3 * image.height) / (4 * 97))
            var i = skip - 1
            while (i < image.height) {
                c.fill(0)
                var state = 0
                var j = 0
                while (j < image.width) {
                    if (image[j, i]) {
                        if (state and 1 == 1) state++
                        c[state]++
                    } else if (state and 1 == 0) {
                        if (state == 4) {
                            if (crossesPattern(c) && centre(c, i, j)) {
                                skip = 2
                                state = 0
                                c.fill(0)
                            } else {
                                c[0] = c[2]; c[1] = c[3]; c[2] = c[4]; c[3] = 1; c[4] = 0
                                state = 3
                            }
                        } else {
                            state++
                            c[state]++
                        }
                    } else {
                        c[state]++
                    }
                    j++
                }
                if (state == 4 && crossesPattern(c)) centre(c, i, image.width)
                i += skip
            }
            return best()
        }

        /** Three of them that make a right isosceles triangle, of one module size. */
        private fun best(): List<Point>? {
            val seen = found.filter { it.count >= 2 }.let { if (it.size >= 3) it else found }
            if (seen.size < 3) return null
            val top = seen.sortedByDescending { it.count }.take(12)
            var best: List<Point>? = null
            var bestScore = Float.MAX_VALUE
            for (a in top.indices) for (b in a + 1 until top.size) for (d in b + 1 until top.size) {
                val three = listOf(top[a], top[b], top[d])
                val smallest = three.minOf { it.size }
                if (three.maxOf { it.size } > 1.4f * smallest) continue
                val sides = floatArrayOf(distance(three[0], three[1]), distance(three[1], three[2]), distance(three[0], three[2]))
                sides.sort()
                val (short, middle, long) = Triple(sides[0], sides[1], sides[2])
                if (short < 10 * smallest) continue
                val score = abs(long * long - (short * short + middle * middle)) / (long * long) + abs(short - middle) / middle
                if (score < bestScore) {
                    bestScore = score
                    best = three
                }
            }
            return best?.takeIf { bestScore < 0.5f }
        }

        private fun centre(c: IntArray, i: Int, j: Int): Boolean {
            val total = c.sum()
            val firstX = centreFromEnd(c, j)
            val y = crossDown(i, firstX.toInt(), c[2], total) ?: return false
            val x = crossAcross(firstX.toInt(), y.toInt(), c[2], total) ?: return false
            val size = total / 7f
            for (p in found) {
                if (abs(y - p.y) <= size && abs(x - p.x) <= size) {
                    val gap = abs(size - p.size)
                    if (gap <= 1f || gap <= p.size) {
                        val n = p.count + 1
                        p.x = (p.count * p.x + x) / n
                        p.y = (p.count * p.y + y) / n
                        p.size = (p.count * p.size + size) / n
                        p.count = n
                        return true
                    }
                }
            }
            found += Point(x, y, size)
            return true
        }

        private fun crossDown(startI: Int, x: Int, most: Int, original: Int): Float? {
            if (x < 0 || x >= image.width) return null
            val c = IntArray(5)
            val h = image.height
            var i = startI
            while (i >= 0 && image[x, i]) { c[2]++; i-- }
            if (i < 0) return null
            while (i >= 0 && !image[x, i] && c[1] <= most) { c[1]++; i-- }
            if (i < 0 || c[1] > most) return null
            while (i >= 0 && image[x, i] && c[0] <= most) { c[0]++; i-- }
            if (c[0] > most) return null
            i = startI + 1
            while (i < h && image[x, i]) { c[2]++; i++ }
            if (i == h) return null
            while (i < h && !image[x, i] && c[3] < most) { c[3]++; i++ }
            if (i == h || c[3] >= most) return null
            while (i < h && image[x, i] && c[4] < most) { c[4]++; i++ }
            if (c[4] >= most) return null
            if (5 * abs(c.sum() - original) >= 2 * original) return null
            return if (crossesPattern(c)) centreFromEnd(c, i) else null
        }

        private fun crossAcross(startJ: Int, y: Int, most: Int, original: Int): Float? {
            if (y < 0 || y >= image.height) return null
            val c = IntArray(5)
            val w = image.width
            var j = startJ
            while (j >= 0 && image[j, y]) { c[2]++; j-- }
            if (j < 0) return null
            while (j >= 0 && !image[j, y] && c[1] <= most) { c[1]++; j-- }
            if (j < 0 || c[1] > most) return null
            while (j >= 0 && image[j, y] && c[0] <= most) { c[0]++; j-- }
            if (c[0] > most) return null
            j = startJ + 1
            while (j < w && image[j, y]) { c[2]++; j++ }
            if (j == w) return null
            while (j < w && !image[j, y] && c[3] < most) { c[3]++; j++ }
            if (j == w || c[3] >= most) return null
            while (j < w && image[j, y] && c[4] < most) { c[4]++; j++ }
            if (c[4] >= most) return null
            if (5 * abs(c.sum() - original) >= original) return null
            return if (crossesPattern(c)) centreFromEnd(c, j) else null
        }
    }

    /**
     * A module's size measured along the lines between the finders, where the
     * code's own axes are: across a finder diagonally (a code turned 45°) it
     * would come out 1.4 times too big (ZXing's calculateModuleSize).
     */
    private fun moduleSize(image: Bits, topLeft: Point, topRight: Point, bottomLeft: Point): Float {
        val across = moduleAlong(image, topLeft, topRight)
        val down = moduleAlong(image, topLeft, bottomLeft)
        return when {
            across.isNaN() -> down
            down.isNaN() -> across
            else -> (across + down) / 2f
        }
    }

    private fun moduleAlong(image: Bits, p: Point, q: Point): Float {
        val one = runBothWays(image, p.x, p.y, q.x, q.y)
        val two = runBothWays(image, q.x, q.y, p.x, p.y)
        return when {
            one.isNaN() -> two / 7f
            two.isNaN() -> one / 7f
            else -> (one + two) / 14f
        }
    }

    /** A finder's width (7 modules) on the line towards another: its centre to each outer edge. */
    private fun runBothWays(image: Bits, fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
        var result = run(image, fromX.toInt(), fromY.toInt(), toX.toInt(), toY.toInt())
        var scale = 1f
        var otherX = fromX - (toX - fromX)
        if (otherX < 0) {
            scale = fromX / (fromX - otherX)
            otherX = 0f
        } else if (otherX >= image.width) {
            scale = (image.width - 1 - fromX) / (otherX - fromX)
            otherX = image.width - 1f
        }
        var otherY = fromY - (toY - fromY) * scale
        scale = 1f
        if (otherY < 0) {
            scale = fromY / (fromY - otherY)
            otherY = 0f
        } else if (otherY >= image.height) {
            scale = (image.height - 1 - fromY) / (otherY - fromY)
            otherY = image.height - 1f
        }
        otherX = fromX + (otherX - fromX) * scale
        result += run(image, fromX.toInt(), fromY.toInt(), otherX.toInt(), otherY.toInt())
        return result - 1f
    }

    /** From a finder's centre towards a point: how far to the end of its outer dark ring, or NaN. */
    private fun run(image: Bits, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val steep = abs(y1 - y0) > abs(x1 - x0)
        val fromX = if (steep) y0 else x0
        val fromY = if (steep) x0 else y0
        val toX = if (steep) y1 else x1
        val toY = if (steep) x1 else y1
        val dx = abs(toX - fromX)
        val dy = abs(toY - fromY)
        var error = -dx / 2
        val xStep = if (fromX < toX) 1 else -1
        val yStep = if (fromY < toY) 1 else -1
        var state = 0
        var x = fromX
        var y = fromY
        while (x != toX + xStep) {
            val realX = if (steep) y else x
            val realY = if (steep) x else y
            if (realX !in 0 until image.width || realY !in 0 until image.height) break
            if ((state == 1) == image[realX, realY]) {
                if (state == 2) return length(x - fromX, y - fromY)
                state++
            }
            error += dy
            if (error > 0) {
                if (y == toY) break
                y += yStep
                error -= dx
            }
            x += xStep
        }
        return if (state == 2) length(toX + xStep - fromX, toY - fromY) else Float.NaN
    }

    private fun length(dx: Int, dy: Int): Float = sqrt((dx * dx + dy * dy).toFloat())

    /** Bottom left, top left (the right angle) and top right, turning the right way. */
    private fun order(p: List<Point>): Triple<Point, Point, Point> {
        val d01 = distance(p[0], p[1])
        val d12 = distance(p[1], p[2])
        val d02 = distance(p[0], p[2])
        var a: Point
        val b: Point
        var c: Point
        when {
            d12 >= d01 && d12 >= d02 -> { b = p[0]; a = p[1]; c = p[2] }
            d02 >= d12 && d02 >= d01 -> { b = p[1]; a = p[0]; c = p[2] }
            else -> { b = p[2]; a = p[0]; c = p[1] }
        }
        if ((c.x - b.x) * (a.y - b.y) - (c.y - b.y) * (a.x - b.x) < 0) {
            val t = a
            a = c
            c = t
        }
        return Triple(a, b, c)
    }

    // --- The grid --------------------------------------------------------------

    private fun read(image: Bits, bottomLeft: Point, topLeft: Point, topRight: Point, dimension: Int, allowRetry: Boolean): String? {
        val version = (dimension - 17) / 4
        // One module across and one down, as the finders lie: rotation and tilt included.
        val span = (dimension - 7).toFloat()
        val ux = (topRight.x - topLeft.x) / span
        val uy = (topRight.y - topLeft.y) / span
        val vx = (bottomLeft.x - topLeft.x) / span
        val vy = (bottomLeft.y - topLeft.y) / span
        var cornerX = topRight.x - topLeft.x + bottomLeft.x
        var cornerY = topRight.y - topLeft.y + bottomLeft.y
        var source = dimension - 3.5f
        if (version >= 2) {
            val towards = 1f - 3f / span
            val ex = topLeft.x + towards * (cornerX - topLeft.x)
            val ey = topLeft.y + towards * (cornerY - topLeft.y)
            alignment(image, ex, ey, ux, uy, vx, vy)?.let { (x, y) ->
                cornerX = x
                cornerY = y
                source = dimension - 6.5f
            }
        }
        val transform = Perspective.quadToQuad(
            3.5f, 3.5f, dimension - 3.5f, 3.5f, source, source, 3.5f, dimension - 3.5f,
            topLeft.x, topLeft.y, topRight.x, topRight.y, cornerX, cornerY, bottomLeft.x, bottomLeft.y,
        )
        val grid = sample(image, transform, dimension) ?: return null
        if (version >= 7 && allowRetry) {
            val printed = readVersion(grid, dimension)
            if (printed != null && printed != version) {
                return if (printed <= MAX_VERSION) read(image, bottomLeft, topLeft, topRight, 17 + 4 * printed, allowRetry = false) else null
            }
        }
        return decodeGrid(grid, dimension)
    }

    /** The alignment pattern near where it should be: a dark module, a light ring, a dark ring. */
    private fun alignment(image: Bits, ex: Float, ey: Float, ux: Float, uy: Float, vx: Float, vy: Float): Pair<Float, Float>? {
        fun dark(x: Float, y: Float): Int {
            val px = x.roundToInt()
            val py = y.roundToInt()
            return if (px in 0 until image.width && py in 0 until image.height && image[px, py]) 1 else 0
        }
        // Every place within five modules that looks like it, scored out of 17.
        val steps = 21
        val scores = IntArray(steps * steps)
        var bestScore = 0
        for (ia in 0 until steps) for (ib in 0 until steps) {
            val a = -5f + 0.5f * ia
            val b = -5f + 0.5f * ib
            val x = ex + a * ux + b * vx
            val y = ey + a * uy + b * vy
            var score = dark(x, y)
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                score += 1 - dark(x + dx * ux + dy * vx, y + dx * uy + dy * vy)
                score += dark(x + 2 * (dx * ux + dy * vx), y + 2 * (dx * uy + dy * vy))
            }
            scores[ia * steps + ib] = score
            if (score > bestScore) bestScore = score
        }
        if (bestScore < 16) return null
        // Half a module off still scores as well where the samples fall on an edge:
        // the middle of the best ones around the first is the centre.
        val first = scores.indexOfFirst { it == bestScore }
        var sumA = 0f
        var sumB = 0f
        var n = 0
        for (ia in 0 until steps) for (ib in 0 until steps) {
            if (scores[ia * steps + ib] != bestScore) continue
            if (abs(ia - first / steps) > 3 || abs(ib - first % steps) > 3) continue
            sumA += -5f + 0.5f * ia
            sumB += -5f + 0.5f * ib
            n++
        }
        val a = sumA / n
        val b = sumB / n
        return (ex + a * ux + b * vx) to (ey + a * uy + b * vy)
    }

    private fun sample(image: Bits, transform: Perspective, dimension: Int): Bits? {
        val grid = Bits(dimension, dimension)
        val point = FloatArray(2)
        for (y in 0 until dimension) for (x in 0 until dimension) {
            transform.map(x + 0.5f, y + 0.5f, point)
            var px = point[0].toInt()
            var py = point[1].toInt()
            if (px < -1 || py < -1 || px > image.width || py > image.height) return null
            px = px.coerceIn(0, image.width - 1)
            py = py.coerceIn(0, image.height - 1)
            grid[x, y] = image[px, py]
        }
        return grid
    }

    internal class Perspective(
        private val a11: Float, private val a21: Float, private val a31: Float,
        private val a12: Float, private val a22: Float, private val a32: Float,
        private val a13: Float, private val a23: Float, private val a33: Float,
    ) {
        fun map(x: Float, y: Float, out: FloatArray) {
            val d = a13 * x + a23 * y + a33
            out[0] = (a11 * x + a21 * y + a31) / d
            out[1] = (a12 * x + a22 * y + a32) / d
        }

        fun times(o: Perspective) = Perspective(
            a11 * o.a11 + a21 * o.a12 + a31 * o.a13,
            a11 * o.a21 + a21 * o.a22 + a31 * o.a23,
            a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
            a12 * o.a11 + a22 * o.a12 + a32 * o.a13,
            a12 * o.a21 + a22 * o.a22 + a32 * o.a23,
            a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
            a13 * o.a11 + a23 * o.a12 + a33 * o.a13,
            a13 * o.a21 + a23 * o.a22 + a33 * o.a23,
            a13 * o.a31 + a23 * o.a32 + a33 * o.a33,
        )

        fun adjoint() = Perspective(
            a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
            a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
            a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21,
        )

        companion object {
            fun quadToQuad(
                x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
                p0: Float, q0: Float, p1: Float, q1: Float, p2: Float, q2: Float, p3: Float, q3: Float,
            ): Perspective = squareToQuad(p0, q0, p1, q1, p2, q2, p3, q3).times(squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3).adjoint())

            fun squareToQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Perspective {
                val dx3 = x0 - x1 + x2 - x3
                val dy3 = y0 - y1 + y2 - y3
                if (dx3 == 0f && dy3 == 0f) return Perspective(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0f, 0f, 1f)
                val dx1 = x1 - x2
                val dx2 = x3 - x2
                val dy1 = y1 - y2
                val dy2 = y3 - y2
                val denominator = dx1 * dy2 - dx2 * dy1
                val a13 = (dx3 * dy2 - dx2 * dy3) / denominator
                val a23 = (dx1 * dy3 - dx3 * dy1) / denominator
                return Perspective(x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0, y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0, a13, a23, 1f)
            }
        }
    }

    // --- Format and version ------------------------------------------------------

    const val MAX_VERSION = 15

    private fun bch(value: Int, poly: Int): Int {
        val degree = 31 - Integer.numberOfLeadingZeros(poly)
        var v = value shl degree
        while (31 - Integer.numberOfLeadingZeros(v) >= degree) {
            v = v xor (poly shl (31 - Integer.numberOfLeadingZeros(v) - degree))
        }
        return v
    }

    /** The 32 format words, masked as printed: index = level bits << 3 | mask. */
    internal val FORMATS = IntArray(32) { ((it shl 10) or bch(it, 0x537)) xor 0x5412 }

    /** The version words, 7 to 40. */
    internal fun versionWord(version: Int): Int = (version shl 12) or bch(version, 0x1F25)

    private fun closest(bits: Int, words: IntArray): Int {
        var best = -1
        var bestDistance = 4
        for (i in words.indices) {
            val d = Integer.bitCount(bits xor words[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun readFormat(grid: Bits, dimension: Int): Int {
        var first = 0
        fun bit(x: Int, y: Int, value: Int) = (value shl 1) or (if (grid[x, y]) 1 else 0)
        for (i in 0..5) first = bit(i, 8, first)
        first = bit(7, 8, first)
        first = bit(8, 8, first)
        first = bit(8, 7, first)
        for (j in 5 downTo 0) first = bit(8, j, first)
        var second = 0
        for (j in dimension - 1 downTo dimension - 7) second = bit(8, j, second)
        for (i in dimension - 8 until dimension) second = bit(i, 8, second)
        val one = closest(first, FORMATS)
        return if (one >= 0) one else closest(second, FORMATS)
    }

    private fun readVersion(grid: Bits, dimension: Int): Int? {
        val words = IntArray(MAX_VERSION - 6) { versionWord(it + 7) }
        var bits = 0
        for (j in 5 downTo 0) for (i in dimension - 9 downTo dimension - 11) bits = (bits shl 1) or (if (grid[i, j]) 1 else 0)
        var found = closest(bits, words)
        if (found < 0) {
            bits = 0
            for (i in 5 downTo 0) for (j in dimension - 9 downTo dimension - 11) bits = (bits shl 1) or (if (grid[i, j]) 1 else 0)
            found = closest(bits, words)
        }
        return if (found >= 0) found + 7 else null
    }

    // --- The data --------------------------------------------------------------

    private val ALIGNMENTS = arrayOf(
        intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        intArrayOf(6, 30, 54), intArrayOf(6, 32, 58), intArrayOf(6, 34, 62), intArrayOf(6, 26, 46, 66), intArrayOf(6, 26, 48, 70),
    )

    /**
     * Error correction per version and level (L, M, Q, H): the EC codewords
     * of each block, then (blocks, data codewords) for each group.
     */
    internal val BLOCKS = arrayOf(
        arrayOf(intArrayOf()),
        arrayOf(intArrayOf(7, 1, 19), intArrayOf(10, 1, 16), intArrayOf(13, 1, 13), intArrayOf(17, 1, 9)),
        arrayOf(intArrayOf(10, 1, 34), intArrayOf(16, 1, 28), intArrayOf(22, 1, 22), intArrayOf(28, 1, 16)),
        arrayOf(intArrayOf(15, 1, 55), intArrayOf(26, 1, 44), intArrayOf(18, 2, 17), intArrayOf(22, 2, 13)),
        arrayOf(intArrayOf(20, 1, 80), intArrayOf(18, 2, 32), intArrayOf(26, 2, 24), intArrayOf(16, 4, 9)),
        arrayOf(intArrayOf(26, 1, 108), intArrayOf(24, 2, 43), intArrayOf(18, 2, 15, 2, 16), intArrayOf(22, 2, 11, 2, 12)),
        arrayOf(intArrayOf(18, 2, 68), intArrayOf(16, 4, 27), intArrayOf(24, 4, 19), intArrayOf(28, 4, 15)),
        arrayOf(intArrayOf(20, 2, 78), intArrayOf(18, 4, 31), intArrayOf(18, 2, 14, 4, 15), intArrayOf(26, 4, 13, 1, 14)),
        arrayOf(intArrayOf(24, 2, 97), intArrayOf(22, 2, 38, 2, 39), intArrayOf(22, 4, 18, 2, 19), intArrayOf(26, 4, 14, 2, 15)),
        arrayOf(intArrayOf(30, 2, 116), intArrayOf(22, 3, 36, 2, 37), intArrayOf(20, 4, 16, 4, 17), intArrayOf(24, 4, 12, 4, 13)),
        arrayOf(intArrayOf(18, 2, 68, 2, 69), intArrayOf(26, 4, 43, 1, 44), intArrayOf(24, 6, 19, 2, 20), intArrayOf(28, 6, 15, 2, 16)),
        arrayOf(intArrayOf(20, 4, 81), intArrayOf(30, 1, 50, 4, 51), intArrayOf(28, 4, 22, 4, 23), intArrayOf(24, 3, 12, 8, 13)),
        arrayOf(intArrayOf(24, 2, 92, 2, 93), intArrayOf(22, 6, 36, 2, 37), intArrayOf(26, 4, 20, 6, 21), intArrayOf(28, 7, 14, 4, 15)),
        arrayOf(intArrayOf(26, 4, 107), intArrayOf(22, 8, 37, 1, 38), intArrayOf(24, 8, 20, 4, 21), intArrayOf(22, 12, 11, 4, 12)),
        arrayOf(intArrayOf(30, 3, 115, 1, 116), intArrayOf(24, 4, 40, 5, 41), intArrayOf(20, 11, 16, 5, 17), intArrayOf(24, 11, 12, 5, 13)),
        arrayOf(intArrayOf(22, 5, 87, 1, 88), intArrayOf(24, 5, 41, 5, 42), intArrayOf(30, 5, 24, 7, 25), intArrayOf(24, 11, 12, 7, 13)),
    )

    /** The codewords a version holds: its modules less the patterns, in bytes. */
    internal fun codewords(version: Int): Int {
        var modules = (16 * version + 128) * version + 64
        if (version >= 2) {
            val count = version / 7 + 2
            modules -= (25 * count - 10) * count - 55
            if (version >= 7) modules -= 36
        }
        return modules / 8
    }

    /** True for the modules that hold no data: finders, timing, alignment, format, version. */
    private fun functions(version: Int, dimension: Int): Bits {
        val f = Bits(dimension, dimension)
        fun region(left: Int, top: Int, width: Int, height: Int) {
            for (y in top until top + height) for (x in left until left + width) f[x, y] = true
        }
        region(0, 0, 9, 9)
        region(dimension - 8, 0, 8, 9)
        region(0, dimension - 8, 9, 8)
        val centres = ALIGNMENTS[version]
        val last = centres.size - 1
        for (i in centres.indices) for (j in centres.indices) {
            if ((i == 0 && (j == 0 || j == last)) || (i == last && j == 0)) continue
            region(centres[i] - 2, centres[j] - 2, 5, 5)
        }
        region(6, 9, 1, dimension - 17)
        region(9, 6, dimension - 17, 1)
        if (version > 6) {
            region(dimension - 11, 0, 3, 6)
            region(0, dimension - 11, 6, 3)
        }
        return f
    }

    private fun masked(mask: Int, i: Int, j: Int): Boolean = when (mask) {
        0 -> (i + j) % 2 == 0
        1 -> i % 2 == 0
        2 -> j % 3 == 0
        3 -> (i + j) % 3 == 0
        4 -> (i / 2 + j / 3) % 2 == 0
        5 -> (i * j) % 2 + (i * j) % 3 == 0
        6 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0
        else -> ((i + j) % 2 + (i * j) % 3) % 2 == 0
    }

    private fun decodeGrid(grid: Bits, dimension: Int): String? {
        val version = (dimension - 17) / 4
        if (version !in 1..MAX_VERSION) return null
        val format = readFormat(grid, dimension)
        if (format < 0) return null
        val level = when (format shr 3) {
            0 -> 1 // M
            1 -> 0 // L
            2 -> 3 // H
            else -> 2 // Q
        }
        val mask = format and 7
        val function = functions(version, dimension)
        // Unmasked, read two columns at a time, up and down, from the right.
        val raw = IntArray(codewords(version))
        var count = 0
        var byte = 0
        var bits = 0
        var up = true
        var j = dimension - 1
        while (j > 0) {
            if (j == 6) j--
            for (k in 0 until dimension) {
                val i = if (up) dimension - 1 - k else k
                for (col in 0..1) {
                    val x = j - col
                    if (function[x, i]) continue
                    byte = (byte shl 1) or (if (grid[x, i] != masked(mask, i, x)) 1 else 0)
                    if (++bits == 8) {
                        if (count < raw.size) raw[count++] = byte
                        byte = 0
                        bits = 0
                    }
                }
            }
            up = !up
            j -= 2
        }
        if (count != raw.size) return null
        val data = blocks(raw, BLOCKS[version][level]) ?: return null
        return segments(data, version)
    }

    /** The blocks undone from their interleaving, each corrected; their data codewords in order. */
    private fun blocks(raw: IntArray, spec: IntArray): IntArray? {
        val ec = spec[0]
        val sizes = ArrayList<Int>()
        var g = 1
        while (g < spec.size) {
            repeat(spec[g]) { sizes += spec[g + 1] }
            g += 2
        }
        val total = sizes.sumOf { it + ec }
        if (total != raw.size) return null
        val blocks = sizes.map { IntArray(it + ec) }
        val shortest = sizes.minOrNull() ?: return null
        var at = 0
        for (i in 0 until shortest) for (b in blocks.indices) blocks[b][i] = raw[at++]
        for (b in blocks.indices) if (sizes[b] > shortest) blocks[b][shortest] = raw[at++]
        for (i in 0 until ec) for (b in blocks.indices) blocks[b][sizes[b] + i] = raw[at++]
        val out = ArrayList<Int>()
        for (b in blocks.indices) {
            if (!ReedSolomon.correct(blocks[b], ec)) return null
            for (i in 0 until sizes[b]) out += blocks[b][i]
        }
        return out.toIntArray()
    }

    // --- The segments -----------------------------------------------------------

    private class BitReader(private val bytes: IntArray) {
        private var byte = 0
        private var bit = 0

        fun available(): Int = 8 * (bytes.size - byte) - bit

        fun read(n: Int): Int {
            var result = 0
            repeat(n) {
                result = (result shl 1) or ((bytes[byte] shr (7 - bit)) and 1)
                if (++bit == 8) {
                    bit = 0
                    byte++
                }
            }
            return result
        }
    }

    private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    private fun segments(data: IntArray, version: Int): String? {
        val bits = BitReader(data)
        val text = StringBuilder()
        val size = if (version <= 9) 0 else if (version <= 26) 1 else 2
        while (bits.available() >= 4) {
            when (bits.read(4)) {
                0 -> break
                1 -> {
                    var n = bits.read(intArrayOf(10, 12, 14)[size])
                    while (n >= 3) {
                        if (bits.available() < 10) return null
                        val v = bits.read(10)
                        if (v >= 1000) return null
                        text.append(v.toString().padStart(3, '0'))
                        n -= 3
                    }
                    if (n == 2) {
                        val v = bits.read(7)
                        if (v >= 100) return null
                        text.append(v.toString().padStart(2, '0'))
                    } else if (n == 1) {
                        val v = bits.read(4)
                        if (v >= 10) return null
                        text.append(v)
                    }
                }
                2 -> {
                    var n = bits.read(intArrayOf(9, 11, 13)[size])
                    while (n >= 2) {
                        if (bits.available() < 11) return null
                        val v = bits.read(11)
                        if (v >= 45 * 45) return null
                        text.append(ALPHANUMERIC[v / 45]).append(ALPHANUMERIC[v % 45])
                        n -= 2
                    }
                    if (n == 1) {
                        val v = bits.read(6)
                        if (v >= 45) return null
                        text.append(ALPHANUMERIC[v])
                    }
                }
                4 -> {
                    val n = bits.read(if (size == 0) 8 else 16)
                    if (bits.available() < 8 * n) return null
                    val bytes = ByteArray(n) { bits.read(8).toByte() }
                    val utf8 = String(bytes, Charsets.UTF_8)
                    text.append(if ('\uFFFD' in utf8) String(bytes, Charsets.ISO_8859_1) else utf8)
                }
                7 -> {
                    val first = bits.read(8)
                    when {
                        first and 0x80 == 0 -> Unit
                        first and 0xC0 == 0x80 -> bits.read(8)
                        first and 0xE0 == 0xC0 -> bits.read(16)
                        else -> return null
                    }
                }
                else -> return null
            }
        }
        return text.toString()
    }
}

/** Reed-Solomon over GF(256) with the QR polynomial (x⁸ + x⁴ + x³ + x² + 1), syndromes from α⁰. */
internal object ReedSolomon {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun div(a: Int, b: Int): Int = if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255]

    private fun power(a: Int, e: Int): Int = if (e == 0) 1 else if (a == 0) 0 else EXP[(LOG[a] * e) % 255]

    /** The received block at α^i: its codewords as coefficients, the first the highest power. */
    private fun syndrome(block: IntArray, i: Int): Int {
        val x = EXP[i % 255]
        var acc = 0
        for (c in block) acc = mul(acc, x) xor c
        return acc
    }

    /** Corrects [block] (data then [ec] error correction codewords) in place; false when it can't. */
    fun correct(block: IntArray, ec: Int): Boolean {
        val n = block.size
        val s = IntArray(ec) { syndrome(block, it) }
        if (s.all { it == 0 }) return true
        // Berlekamp-Massey: the error locator.
        var c = IntArray(ec + 1).also { it[0] = 1 }
        var b = IntArray(ec + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var last = 1
        for (k in 0 until ec) {
            var d = s[k]
            for (i in 1..l) d = d xor mul(c[i], s[k - i])
            if (d == 0) {
                m++
                continue
            }
            val coefficient = div(d, last)
            val previous = c.copyOf()
            for (i in 0..ec - m) if (b[i] != 0) c[i + m] = c[i + m] xor mul(coefficient, b[i])
            if (2 * l <= k) {
                l = k + 1 - l
                b = previous
                last = d
                m = 1
            } else {
                m++
            }
        }
        if (l == 0 || 2 * l > ec) return false
        // Chien: where the locator has its roots.
        val degrees = ArrayList<Int>()
        for (d in 0 until n) {
            val inverse = EXP[(255 - d % 255) % 255]
            var v = 0
            var p = 1
            for (i in 0..l) {
                v = v xor mul(c[i], p)
                p = mul(p, inverse)
            }
            if (v == 0) degrees += d
        }
        if (degrees.size != l) return false
        // Forney: how wrong each one is.
        val omega = IntArray(ec)
        for (i in 0 until ec) {
            var v = 0
            for (j in 0..min(i, l)) v = v xor mul(c[j], s[i - j])
            omega[i] = v
        }
        for (d in degrees) {
            val x = EXP[d % 255]
            val inverse = div(1, x)
            var numerator = 0
            var p = 1
            for (i in 0 until ec) {
                numerator = numerator xor mul(omega[i], p)
                p = mul(p, inverse)
            }
            var denominator = 0
            var i = 1
            while (i <= l) {
                denominator = denominator xor mul(c[i], power(inverse, i - 1))
                i += 2
            }
            if (denominator == 0) return false
            val index = n - 1 - d
            block[index] = block[index] xor mul(x, div(numerator, denominator))
        }
        for (i in 0 until ec) if (syndrome(block, i) != 0) return false
        return true
    }
}
