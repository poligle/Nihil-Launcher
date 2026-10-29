package com.nihilshell.launcher.core

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The "=" mode of the drawer: a small recursive-descent calculator.
 *   + - * / ^ %  parentheses, unary minus, postfix !, implicit products (2pi, 3(4+1)),
 *   pi e, sqrt cbrt abs ln log log2 exp sin cos tan asin acos atan round floor (radians).
 * Both "." and "," work as the decimal mark; × and ÷ are accepted too.
 * Pure Kotlin, tested on the JVM.
 */
object Calculator {

    class Error(message: String) : Exception(message)

    /** The value of [expression], or null when it can't be read. */
    fun evaluate(expression: String): Double? = try {
        Parser(expression).parse().takeIf { !it.isNaN() }
    } catch (e: Error) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    /** Up to 12 significant digits, no trailing zeros, "." as the decimal mark. */
    fun format(value: Double): String {
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()
        return if (abs(value) >= 1e15 || abs(value) < 1e-9) {
            String.format(java.util.Locale.ROOT, "%.6e", value)
        } else {
            rounded.toPlainString()
        }
    }

    private class Parser(source: String) {
        private val s = source
            .replace(',', '.')
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .lowercase()
        private var i = 0

        fun parse(): Double {
            val value = expression()
            skip()
            if (i < s.length) throw Error("unexpected '${s[i]}'")
            return value
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun eat(c: Char): Boolean {
            skip()
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                value = when {
                    eat('+') -> value + term()
                    eat('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                value = when {
                    eat('*') -> value * factor()
                    eat('/') -> value / factor()
                    eat('%') -> value.mod(factor())
                    startsFactor() -> value * factor() // implicit product: 2pi, 3(4+1)
                    else -> return value
                }
            }
        }

        private fun startsFactor(): Boolean {
            skip()
            return i < s.length && (s[i] == '(' || s[i].isLetter())
        }

        // Right-associative power binds tighter than a leading minus: -2^2 = -4.
        private fun factor(): Double {
            if (eat('-')) return -factor()
            if (eat('+')) return factor()
            val base = postfix()
            return if (eat('^')) base.pow(factor()) else base
        }

        private fun postfix(): Double {
            var value = primary()
            while (eat('!')) value = factorial(value)
            return value
        }

        private fun primary(): Double {
            skip()
            if (eat('(')) {
                val value = expression()
                if (!eat(')')) throw Error("missing )")
                return value
            }
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) return number()
            if (i < s.length && s[i].isLetter()) return name()
            throw Error(if (i < s.length) "unexpected '${s[i]}'" else "unexpected end")
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && s[i] == 'e' && i + 1 < s.length &&
                (s[i + 1].isDigit() || ((s[i + 1] == '-' || s[i + 1] == '+') && i + 2 < s.length && s[i + 2].isDigit()))
            ) {
                i += 2
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDoubleOrNull() ?: throw Error("bad number")
        }

        private fun name(): Double {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit())) i++
            val id = s.substring(start, i)
            return when (id) {
                "pi", "π" -> PI
                "e" -> E
                else -> {
                    val f = FUNCTIONS[id] ?: throw Error("unknown '$id'")
                    if (!eat('(')) throw Error("missing ( after $id")
                    val arg = expression()
                    if (!eat(')')) throw Error("missing )")
                    f(arg)
                }
            }
        }

        private fun factorial(n: Double): Double {
            if (n < 0 || n != floor(n) || n > 170) throw Error("bad factorial")
            var r = 1.0
            for (k in 2..n.toInt()) r *= k
            return r
        }
    }

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x: Double -> sqrt(x) },
        "cbrt" to { x: Double -> cbrt(x) },
        "abs" to { x: Double -> abs(x) },
        "ln" to { x: Double -> ln(x) },
        "log" to { x: Double -> log10(x) },
        "log2" to { x: Double -> log2(x) },
        "exp" to { x: Double -> exp(x) },
        "sin" to { x: Double -> sin(x) },
        "cos" to { x: Double -> cos(x) },
        "tan" to { x: Double -> tan(x) },
        "asin" to { x: Double -> asin(x) },
        "acos" to { x: Double -> acos(x) },
        "atan" to { x: Double -> atan(x) },
        "round" to { x: Double -> round(x) },
        "floor" to { x: Double -> floor(x) },
    )
}
