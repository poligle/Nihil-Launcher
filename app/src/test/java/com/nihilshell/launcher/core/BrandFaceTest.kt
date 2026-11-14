package com.nihilshell.launcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandFaceTest {
    @Test
    fun nihilIsTwentyOneColumnsAsTheLogo() {
        assertEquals(21, BrandFace.columns().size)
    }

    @Test
    fun theIIsOneColumnLitTopToBottom() {
        val columns = BrandFace.columns()
        assertArrayEquals(BooleanArray(7) { false }, columns[5])
        assertArrayEquals(BooleanArray(7) { true }, columns[6])
        assertArrayEquals(BooleanArray(7) { false }, columns[7])
    }
}
