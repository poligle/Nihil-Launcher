package com.nihilshell.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun normalizeDropsAccentsAndCase() {
        assertEquals("camara", Fuzzy.normalize("Cámara"))
        assertEquals("pinguino", Fuzzy.normalize("PINGÜINO"))
        assertEquals("espana", Fuzzy.normalize("España"))
    }

    @Test
    fun scoreOrder() {
        val exact = Fuzzy.score("maps", "maps")
        val prefix = Fuzzy.score("mapsme", "maps")
        val word = Fuzzy.score("google maps", "maps")
        val inside = Fuzzy.score("roadmaps", "maps")
        val letters = Fuzzy.score("my apps", "maps")
        assertTrue(exact > prefix)
        assertTrue(prefix > word)
        assertTrue(word > inside)
        assertTrue(inside > letters)
        assertTrue(letters >= 0)
        assertEquals(-1, Fuzzy.score("camera", "maps"))
    }

    @Test
    fun wordsSplitOnPunctuation() {
        assertTrue(Fuzzy.score("nihil·settings", "set") >= 500)
        assertTrue(Fuzzy.score("open-camera", "cam") >= 500)
    }

    @Test
    fun filterKeepsOrderForEmptyQueryAndRanksOtherwise() {
        val apps = listOf("Calendar", "Camera", "Calculator", "Clock")
        assertEquals(apps, Fuzzy.filter(apps, "  ") { Fuzzy.normalize(it) })
        assertEquals(listOf("Camera", "Calendar", "Calculator"), Fuzzy.filter(apps, "ca") { Fuzzy.normalize(it) })
        assertEquals(listOf("Cámara"), Fuzzy.filter(listOf("Cámara", "Clock"), "CAMARA") { Fuzzy.normalize(it) })
    }
}
