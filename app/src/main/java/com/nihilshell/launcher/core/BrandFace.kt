package com.nihilshell.launcher.core

object BrandFace {
    const val ROWS = 7

    private val FACE = mapOf(
        'N' to arrayOf("#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"),
        'I' to arrayOf("#", "#", "#", "#", "#", "#", "#"),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    )

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
