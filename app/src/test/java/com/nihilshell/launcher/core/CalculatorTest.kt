package com.nihilshell.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.sqrt

class CalculatorTest {

    private fun eval(expression: String): Double = Calculator.evaluate(expression) ?: error("null for $expression")

    @Test
    fun precedenceAndAssociativity() {
        assertEquals(4.0, eval("2+2"), 0.0)
        assertEquals(14.0, eval("2+3*4"), 0.0)
        assertEquals(20.0, eval("(2+3)*4"), 0.0)
        assertEquals(-4.0, eval("-2^2"), 0.0)
        assertEquals(512.0, eval("2^3^2"), 0.0)
        assertEquals(1.0, eval("10 mod 3"), 0.0)
        assertEquals(2.5, eval("10/4"), 0.0)
    }

    @Test
    fun implicitProductsConstantsAndFunctions() {
        assertEquals(2 * PI, eval("2pi"), 1e-12)
        assertEquals(15.0, eval("3(4+1)"), 0.0)
        assertEquals(E, eval("e"), 1e-12)
        assertEquals(4.0, eval("sqrt(16)"), 0.0)
        assertEquals(sqrt(2.0), eval("sqrt(2)"), 1e-12)
        assertEquals(1.0, eval("sin(pi/2)"), 1e-12)
        assertEquals(3.0, eval("log(1000)"), 1e-12)
        assertEquals(120.0, eval("5!"), 0.0)
        assertEquals(1001.0, eval("1e3+1"), 0.0)
    }

    @Test
    fun localeAndSymbols() {
        assertEquals(3.0, eval("1,5*2"), 0.0)
        assertEquals(1.5, eval("2×3÷4"), 0.0)
        assertEquals(-1.0, eval("2−3"), 0.0)
    }

    @Test
    fun invalidInputIsNull() {
        assertNull(Calculator.evaluate("2+"))
        assertNull(Calculator.evaluate("whatsapp"))
        assertNull(Calculator.evaluate("7-zip"))
        assertNull(Calculator.evaluate("(1+2"))
        assertNull(Calculator.evaluate("0/0"))
        assertNull(Calculator.evaluate("3.5!"))
    }

    @Test
    fun format() {
        assertEquals("0.3", Calculator.format(0.1 + 0.2))
        assertEquals("-2.5", Calculator.format(-2.5))
        assertEquals("0.333333333333", Calculator.format(1 / 3.0))
        assertEquals("1000000", Calculator.format(1e6))
        assertEquals("1.000000e+20", Calculator.format(1e20))
        assertEquals("∞", Calculator.format(eval("1/0")))
        assertEquals("0", Calculator.format(0.0))
    }

    @Test
    fun percentagesAsTheDesktopReadsThem() {
        assertEquals(230.0, eval("200 + 15%"), 1e-9)
        assertEquals(170.0, eval("200 - 15%"), 1e-9)
        assertEquals(30.0, eval("200 * 15%"), 1e-9)
        assertEquals(0.15, eval("15%"), 1e-12)
        assertEquals(1.3, eval("(1 + 30%)"), 1e-12)
    }

    @Test
    fun moreFunctionsAndDegrees() {
        assertEquals(0.5, eval("sin(30°)"), 1e-12)
        assertEquals(4.0, eval("max(3; 4)"), 0.0)
        assertEquals(3.0, eval("min(3, 4, 5)"), 0.0)
        assertEquals(3.5, eval("max(3,5; 1)"), 0.0)
        assertEquals(5.0, eval("hypot(3; 4)"), 1e-12)
        assertEquals(6.0, eval("gcd(12; 18)"), 0.0)
        assertEquals(36.0, eval("lcm(12; 18)"), 0.0)
        assertEquals(3.0, eval("root(27; 3)"), 1e-12)
        assertEquals(-3.0, eval("root(-27; 3)"), 1e-12)
        assertEquals(3.0, eval("log(8; 2)"), 1e-12)
        assertEquals(3.0, eval("ceil(2.1)"), 0.0)
        assertEquals(2.0, eval("trunc(2.9)"), 0.0)
        assertEquals(2 * Math.PI, eval("tau"), 1e-12)
    }

    @Test
    fun basesAndConversions() {
        assertEquals(255.0, eval("0xff"), 0.0)
        assertEquals(10.0, eval("0b1010"), 0.0)
        assertEquals("0xff", Calculator.calculate("255 to hex")?.text)
        assertEquals("0b1010", Calculator.calculate("10 a bin")?.text)
        assertEquals("8.04672 km", Calculator.calculate("5 mi to km")?.text)
        assertEquals("212 °F", Calculator.calculate("100 °C to °F")?.text)
        assertEquals("37.7777777778 °C", Calculator.calculate("100 f to c")?.text)
        assertEquals("1.5 h", Calculator.calculate("90 min en h")?.text)
        assertEquals("1000 MB", Calculator.calculate("1 gb to mb")?.text)
        assertEquals("2.2 km", Calculator.calculate("(1 + 1,2) km to km")?.text)
        assertNull(Calculator.calculate("5 km to kg"))
        assertTrue(Calculator.isConversion("10 km to mi"))
        assertFalse(Calculator.isConversion("go to work"))
    }
}
