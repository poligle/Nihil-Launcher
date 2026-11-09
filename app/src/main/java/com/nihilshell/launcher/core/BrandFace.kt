package com.nihilshell.launcher.core

/**
 * The brand's own 5 x 7 dot face, only for "NIHIL": the logo's letters, as
 * Nihil-Shell draws them (tools/brand.py, settings/Logo.qml). The I is one
 * column, as in the logo, not the serifed I of the dot font.
 * Pure Kotlin, tested on the JVM.
 */
object BrandFace {
    const val ROWS = 7

    private val FACE = mapOf(
        'N' to arrayOf("#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"),
        'I' to arrayOf("#", "#", "#", "#", "#", "#", "#"),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    )

    /** The word's columns, left to right, each 7 LEDs top to bottom; one dark column between letters. */
    fun columns(word: String = "NIHIL"): List<BooleanArray> {
        val out = ArrayList<BooleanArray>()
        word.forEachIndexed { k, c ->
            val glyph = FACE[c] ?: return@forEachIndexed
            if (k > 0) out.add(BooleanArray(ROWS))
            for (x in glyph[0].indices) out.add(BooleanArray(ROWS) { y -> glyph[y][x] == '#' })
        }
        return out
    }
}
