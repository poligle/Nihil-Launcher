package com.nihilshell.launcher.core

/**
 * The 5 x 7 LED digits of the home clock, laid out on a 25 x 7 panel:
 * digit, digit, colon, digit, digit, with one dark column between slots.
 * Pure Kotlin, tested on the JVM.
 */
object LedFont {
    const val COLS = 25
    const val ROWS = 7

    private val SLOTS = intArrayOf(0, 6, 12, 14, 20)

    private val DIGITS = arrayOf(
        arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        arrayOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    )

    /**
     * LED levels (0 or 1), row by row, for a time written "HH:MM". A shorter
     * string is right-aligned (" 9:41"); anything that isn't a digit stays dark.
     */
    fun matrix(time: String): FloatArray {
        val out = FloatArray(COLS * ROWS)
        val chars = time.padStart(5).takeLast(5)
        for (slot in 0 until 5) {
            val c = chars[slot]
            val x0 = SLOTS[slot]
            if (slot == 2) {
                if (c == ':') {
                    out[2 * COLS + x0] = 1f
                    out[4 * COLS + x0] = 1f
                }
                continue
            }
            if (c !in '0'..'9') continue
            val glyph = DIGITS[c - '0']
            for (row in 0 until ROWS) {
                for (dx in 0 until 5) if (glyph[row][dx] == '#') out[row * COLS + x0 + dx] = 1f
            }
        }
        return out
    }
}
