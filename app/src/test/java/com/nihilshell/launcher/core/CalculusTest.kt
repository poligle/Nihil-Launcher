package com.nihilshell.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The calculator's calculus: derivatives, integrals, equations, series and limits. */
class CalculusTest {
    private fun text(expression: String): String? = Calculator.calculate(expression)?.text

    private fun value(expression: String): Double = Calculator.calculate(expression)?.value ?: error("null for $expression")

    @Test
    fun derivatives() {
        assertEquals("2x", text("diff(x^2)"))
        assertEquals("cos(x)", text("diff(sin(x))"))
        assertEquals("e^x", text("diff(e^x)"))
        assertEquals("sin(x) + x*cos(x)", text("diff(x*sin(x))"))
        assertEquals("1/x", text("deriva(ln(x))"))
        assertEquals("6t", text("diff(3t^2; t)"))
        assertEquals(12.0, value("diff(x^3; 2)"), 1e-9)
        assertEquals(-1.0, value("diff(cos(x); x; pi/2)"), 1e-9)
    }

    @Test
    fun integrals() {
        assertEquals("x^3/3 + C", text("integrate(x^2)"))
        assertEquals("-cos(x) + C", text("integrate(sin(x))"))
        assertEquals("ln(abs(x)) + C", text("integral(1/x)"))
        assertEquals("e^x + C", text("integrate(e^x)"))
        assertEquals(9.0, value("integrate(x^2; 0; 3)"), 1e-9)
        assertEquals(2.0, value("integrate(sin(x); 0; pi)"), 1e-9)
        assertEquals(1.0, value("integrate(e^(-x); 0; inf)"), 1e-6)
        assertEquals(Math.sqrt(Math.PI), value("integra(e^(-x^2); -inf; inf)"), 1e-6)
    }

    @Test
    fun equations() {
        assertEquals("x = -2, x = 2", text("solve(x^2 = 4)"))
        assertEquals("x = -0.5", text("resuelve(2x + 1)"))
        assertEquals("x = 0", text("solve(x^2)"))
        assertNull(text("solve(x^2 = -1)"))
    }

    @Test
    fun seriesAndLimits() {
        assertEquals(385.0, value("sum(i^2; i; 1; 10)"), 0.0)
        assertEquals(120.0, value("product(k; k; 1; 5)"), 0.0)
        assertEquals(1.0, value("limit(sin(x)/x; x; 0)"), 1e-9)
        assertEquals(Math.E, value("limit((1 + 1/n)^n; n; inf)"), 1e-6)
    }

    @Test
    fun theRestStillWorks() {
        assertEquals(4.0, value("2+2"), 0.0)
        assertEquals("0xff", text("255 to hex"))
        assertNull(text("diff(x*y)"))
    }
}
