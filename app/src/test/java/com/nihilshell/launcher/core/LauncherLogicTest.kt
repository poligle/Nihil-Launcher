package com.nihilshell.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small pure pieces of the drawer, the clock and the icons. */
class LauncherLogicTest {

    @Test
    fun letters() {
        assertEquals("A", Search.letterOf("Ábaco"))
        assertEquals("Z", Search.letterOf("  zoom"))
        assertEquals("#", Search.letterOf("7-Zip"))
        assertEquals("#", Search.letterOf(""))
        assertEquals("N", Search.letterOf("Ñandú"))
    }

    @Test
    fun mathDetection() {
        assertTrue(Search.isMath("12*7"))
        assertTrue(Search.isMath("sqrt(2)"))
        assertTrue(Search.isMath("5!"))
        assertTrue(Search.isMath("(1+2)/3"))
        assertFalse(Search.isMath("2048"))
        assertFalse(Search.isMath("whatsapp"))
        assertFalse(Search.isMath("pi"))
    }

    @Test
    fun packageWords() {
        assertEquals(300, Search.packageScore("com.android.settings", "sett"))
        assertEquals(-1, Search.packageScore("com.android.settings", "and"))
        assertEquals(-1, Search.packageScore("com.whatsapp", "wh"))
        assertEquals(300, Search.packageScore("org.mozilla.firefox", "fire"))
    }

    @Test
    fun clockMatrix() {
        val m = LedFont.matrix("12:34")
        val cols = LedFont.COLS
        // Colon: column 12, rows 2 and 4.
        assertEquals(1f, m[2 * cols + 12], 0f)
        assertEquals(1f, m[4 * cols + 12], 0f)
        assertEquals(0f, m[3 * cols + 12], 0f)
        // "1": the top row lights only its middle column.
        assertEquals(listOf(0f, 0f, 1f, 0f, 0f), (0 until 5).map { m[it] })
        // The dark columns between slots never light.
        for (row in 0 until LedFont.ROWS) for (gap in intArrayOf(5, 11, 13, 19)) assertEquals(0f, m[row * cols + gap], 0f)
        // " 9:41" leaves the first digit dark.
        val short = LedFont.matrix(" 9:41")
        for (row in 0 until LedFont.ROWS) for (c in 0 until 5) assertEquals(0f, short[row * cols + c], 0f)
        assertEquals(LedFont.matrix("9:41").toList(), short.toList())
    }

    @Test
    fun layerStats() {
        // Half the circle covered with white, the rest transparent.
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(100) { if (it < 50) white else 0 }
        val stats = LayerStats.of(pixels, circleArea = 100.0)
        assertEquals(0.5, stats.coverage, 1e-9)
        assertEquals(1.0, stats.mean, 1e-9)
        assertEquals(1.0, stats.peak, 1e-9)
        // A dark glyph: low mean, which makes the renderer invert it.
        val darkStats = LayerStats.of(IntArray(10) { 0xFF101010.toInt() }, circleArea = 100.0)
        assertTrue(darkStats.mean < 0.1)
        assertEquals(0.1, darkStats.coverage, 1e-9)
    }
}
