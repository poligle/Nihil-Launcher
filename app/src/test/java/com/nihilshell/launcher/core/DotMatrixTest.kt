package com.nihilshell.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DotMatrixTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun coverFillsAndCentres() {
        // A 4:3 photo on a 9:19.5 screen is scaled by height and cropped at the sides.
        val (scale, dx, dy) = DotMatrix.cover(4000, 3000, 1080, 2340)
        assertEquals(0.78f, scale, 1e-4f)
        assertEquals((3120 - 1080) / 2, dx)
        assertEquals(0, dy)
    }

    @Test
    fun percentileOfHistogram() {
        val h = IntArray(11)
        h[0] = 50
        h[10] = 50
        assertEquals(0f, DotMatrix.percentile(h, 100, 0.02f), 0f)
        assertEquals(1f, DotMatrix.percentile(h, 100, 0.997f), 0f)
    }

    @Test
    fun blackStaysOffAndGradientRises() {
        val w = 200
        val h = 40
        val black = IntArray(w * h) { grey(0) }
        val dark = DotMatrix.grid(black, w, h, DotMatrix.Params(20))
        assertTrue(dark.levels.all { it == 0f })
        assertNull(dark.lit(0, 0))

        val ramp = IntArray(w * h) { k -> grey((k % w) * 255 / (w - 1)) }
        val grid = DotMatrix.grid(ramp, w, h, DotMatrix.Params(20))
        assertEquals(10, grid.cols)
        assertEquals(2, grid.rows)
        for (c in 1 until grid.cols) assertTrue(grid.levels[c] >= grid.levels[c - 1])
        assertEquals(0f, grid.levels[0], 0.05f)
        assertTrue(grid.levels[grid.cols - 1] > 0.8f)
        assertNotNull(grid.lit(grid.cols - 1, 0))
    }

    @Test
    fun aStarLightsItsCell() {
        val w = 100
        val h = 100
        val px = IntArray(w * h) { grey(10) }
        // A little brighter background so the percentiles have a range, and one star.
        for (k in 0 until w) px[k] = grey(40)
        px[55 * w + 55] = grey(255)
        val grid = DotMatrix.grid(px, w, h, DotMatrix.Params(10))
        val star = grid.levels[5 * grid.cols + 5]
        val sky = grid.levels[8 * grid.cols + 8]
        assertTrue("star $star", star > 0.5f)
        assertEquals(0f, sky, 0f)
    }

    @Test
    fun ledsGrowWithLevel() {
        val grid = DotMatrix.Grid(2, 1, 0, 0, DotMatrix.Params(20), floatArrayOf(0.25f, 1f))
        val small = grid.lit(0, 0)!!
        val big = grid.lit(1, 0)!!
        assertTrue(big.radius > small.radius)
        assertTrue(big.grey > small.grey)
        assertEquals(30f, big.x, 0f)
        assertEquals(10f, big.y, 0f)
    }
}
