package com.nihilshell.launcher.core

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.truncate

/**
 * The "=" mode of the drawer, nearer the desktop's (qalc):
 *   + - * / ^ and mod, parentheses, implicit products (2pi, 3(4+1)), postfix ! % and °;
 *   percentages as qalc takes them: 200 + 15% = 230, 200 - 15% = 170, 200 * 15% = 30;
 *   0x 0b 0o numbers; pi e tau phi;
 *   sqrt cbrt abs sign ln log log2 log10 exp, sin cos tan and their inverses and
 *   hyperbolics (radians, or degrees with °), round floor ceil trunc, and min max hypot
 *   gcd lcm root and log with a base, their arguments apart with ";" or ", ";
 *   conversions, "<expression> <unit> to <unit>" (also in, a, en, →): length, mass, time,
 *   temperature, data, speed, volume, area, energy, pressure, power and angle; and into
 *   hex, bin, oct or dec; and calculus (Symbolic): diff, integrate, solve, sum, product,
 *   limit.
 * Both "." and "," work as the decimal mark between digits; × ÷ − are read too.
 * Pure Kotlin, tested on the JVM.
 */
object Calculator {

    class Error(message: String) : Exception(message)

    /** A result: its value, and how it is shown and copied (with its unit, or in its base). */
    class Answer(val value: Double, val text: String)

    /** The value of [expression], or null when it can't be read. */
    fun evaluate(expression: String): Double? = calculate(expression)?.value

    /** The value of [expression] and how to show it, or null when it can't be read. */
    fun calculate(expression: String): Answer? = try {
        Symbolic.calculate(expression)
            ?: convert(expression)
            ?: Parser(expression).parse().takeIf { !it.isNaN() }?.let { Answer(it, format(it)) }
    } catch (e: Error) {
        null
    } catch (e: Symbolic.Error) {
        null
    } catch (e: ArithmeticException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    /** True for "10 km to mi" or "255 to hex": a conversion, even without "=". */
    fun isConversion(raw: String): Boolean {
        val s = raw.trim().lowercase()
        if (TO_BASE.matches(s)) return true
        val m = CONVERSION.matchEntire(s) ?: return false
        return m.groupValues[1].any { it.isDigit() } && m.groupValues[2] in UNITS && m.groupValues[3] in UNITS
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

    // --- Conversions -----------------------------------------------------------------

    private const val UNIT = "[a-zµ°][a-z0-9µ°/²³]*"
    private val CONVERSION = Regex("^(.*?)\\s*($UNIT)\\s+(?:to|in|a|en|->|→)\\s+($UNIT)\\s*$")
    private val TO_BASE = Regex("^(.*\\S)\\s+(?:to|in|a|en|->|→)\\s+(hex|hexadecimal|bin|binary|binario|oct|octal|dec|decimal)\\s*$")

    private fun convert(raw: String): Answer? {
        val s = raw.trim().lowercase()
        TO_BASE.matchEntire(s)?.let { m ->
            val value = Parser(m.groupValues[1]).parse()
            if (value != floor(value) || abs(value) > 9.007199254740992e15) throw Error("not a whole number")
            val n = value.toLong()
            val base = when (m.groupValues[2]) {
                "hex", "hexadecimal" -> 16
                "bin", "binary", "binario" -> 2
                "oct", "octal" -> 8
                else -> 10
            }
            val prefix = when (base) {
                16 -> "0x"
                2 -> "0b"
                8 -> "0o"
                else -> ""
            }
            return Answer(value, (if (n < 0) "-" else "") + prefix + java.lang.Long.toString(abs(n), base))
        }
        val m = CONVERSION.matchEntire(s) ?: return null
        val from = UNITS[m.groupValues[2]] ?: return null
        val to = UNITS[m.groupValues[3]] ?: return null
        if (from.dimension != to.dimension) throw Error("${from.symbol} and ${to.symbol} measure different things")
        val amount = if (m.groupValues[1].isBlank()) 1.0 else Parser(m.groupValues[1]).parse()
        val value = to.fromBase(from.toBase(amount))
        return Answer(value, format(value) + " " + to.symbol)
    }

    /** A unit: how many of its dimension's base it is (and, for temperatures, from where). */
    private class Unit(val symbol: String, val dimension: String, val factor: Double, val offset: Double = 0.0) {
        fun toBase(x: Double) = (x + offset) * factor

        fun fromBase(x: Double) = x / factor - offset
    }

    private val UNITS: Map<String, Unit> = HashMap<String, Unit>().apply {
        fun add(dimension: String, factor: Double, symbol: String, vararg aliases: String, offset: Double = 0.0) {
            val unit = Unit(symbol, dimension, factor, offset)
            put(symbol.lowercase(), unit)
            aliases.forEach { put(it, unit) }
        }
        add("length", 1.0, "m", "metre", "metres", "meter", "meters", "metro", "metros")
        add("length", 1000.0, "km", "kilometre", "kilometres", "kilometer", "kilometers", "kilometro", "kilometros")
        add("length", 0.01, "cm", "centimetro", "centimetros")
        add("length", 0.001, "mm", "milimetro", "milimetros")
        add("length", 1e-6, "µm", "um")
        add("length", 1e-9, "nm")
        add("length", 1609.344, "mi", "mile", "miles", "milla", "millas")
        add("length", 0.9144, "yd", "yard", "yards", "yarda", "yardas")
        add("length", 0.3048, "ft", "foot", "feet", "pie", "pies")
        add("length", 0.0254, "in", "inch", "inches", "pulgada", "pulgadas")
        add("length", 1852.0, "nmi")
        add("mass", 1.0, "kg", "kilo", "kilos", "kilogram", "kilograms", "kilogramo", "kilogramos")
        add("mass", 0.001, "g", "gram", "grams", "gramo", "gramos")
        add("mass", 1e-6, "mg")
        add("mass", 1000.0, "t", "tonne", "tonnes", "tonelada", "toneladas")
        add("mass", 0.45359237, "lb", "lbs", "pound", "pounds", "libra", "libras")
        add("mass", 0.028349523125, "oz", "ounce", "ounces", "onza", "onzas")
        add("mass", 6.35029318, "st", "stone")
        add("time", 1.0, "s", "sec", "seg", "second", "seconds", "segundo", "segundos")
        add("time", 0.001, "ms")
        add("time", 60.0, "min", "minute", "minutes", "minuto", "minutos")
        add("time", 3600.0, "h", "hr", "hour", "hours", "hora", "horas")
        add("time", 86400.0, "d", "day", "days", "dia", "dias", "día", "días")
        add("time", 604800.0, "week", "weeks", "wk", "semana", "semanas")
        add("time", 31557600.0, "year", "years", "yr", "año", "años")
        add("temperature", 1.0, "°C", "c", "celsius", offset = 273.15)
        add("temperature", 5.0 / 9.0, "°F", "f", "fahrenheit", offset = 459.67)
        add("temperature", 1.0, "K", "kelvin")
        add("data", 1.0, "B", "byte", "bytes")
        add("data", 1e3, "kB")
        add("data", 1e6, "MB")
        add("data", 1e9, "GB")
        add("data", 1e12, "TB")
        add("data", 1e15, "PB")
        add("data", 1024.0, "KiB")
        add("data", 1048576.0, "MiB")
        add("data", 1073741824.0, "GiB")
        add("data", 1099511627776.0, "TiB")
        add("data", 0.125, "bit", "bits")
        add("data", 125.0, "kbit")
        add("data", 125000.0, "Mbit")
        add("data", 1.25e8, "Gbit")
        add("speed", 1.0, "m/s", "mps")
        add("speed", 1 / 3.6, "km/h", "kmh", "kph")
        add("speed", 0.44704, "mph")
        add("speed", 1852.0 / 3600.0, "kn", "knot", "knots", "nudo", "nudos")
        add("speed", 0.3048, "ft/s")
        add("volume", 1.0, "l", "litre", "litres", "liter", "liters", "litro", "litros")
        add("volume", 0.001, "ml")
        add("volume", 0.01, "cl")
        add("volume", 0.1, "dl")
        add("volume", 1000.0, "m³", "m3")
        add("volume", 0.001, "cm³", "cm3", "cc")
        add("volume", 3.785411784, "gal", "gallon", "gallons", "galon", "galones")
        add("volume", 0.946352946, "qt", "quart", "quarts")
        add("volume", 0.473176473, "pt", "pint", "pints", "pinta", "pintas")
        add("volume", 0.2365882365, "cup", "cups", "taza", "tazas")
        add("volume", 0.0295735295625, "floz")
        add("volume", 0.01478676478125, "tbsp", "cucharada", "cucharadas")
        add("volume", 0.00492892159375, "tsp", "cucharadita", "cucharaditas")
        add("area", 1.0, "m²", "m2")
        add("area", 1e6, "km²", "km2")
        add("area", 1e-4, "cm²", "cm2")
        add("area", 1e-6, "mm²", "mm2")
        add("area", 1e4, "ha", "hectare", "hectares", "hectarea", "hectareas", "hectárea", "hectáreas")
        add("area", 4046.8564224, "acre", "acres", "ac")
        add("area", 0.09290304, "ft²", "ft2")
        add("area", 0.00064516, "in²", "in2")
        add("area", 2589988.110336, "mi²", "mi2")
        add("energy", 1.0, "J", "joule", "joules", "julio", "julios")
        add("energy", 1000.0, "kJ")
        add("energy", 4.184, "cal")
        add("energy", 4184.0, "kcal")
        add("energy", 3600.0, "Wh")
        add("energy", 3.6e6, "kWh")
        add("pressure", 1.0, "Pa")
        add("pressure", 100.0, "hPa")
        add("pressure", 1000.0, "kPa")
        add("pressure", 1e5, "bar")
        add("pressure", 100.0, "mbar")
        add("pressure", 101325.0, "atm")
        add("pressure", 6894.757293168, "psi")
        add("pressure", 133.322387415, "mmHg")
        add("power", 1.0, "W", "watt", "watts", "vatio", "vatios")
        add("power", 1000.0, "kW")
        add("power", 1e6, "MW")
        add("power", 745.69987158227, "hp")
        add("power", 735.49875, "cv")
        add("angle", 1.0, "rad", "radian", "radians", "radian", "radianes")
        add("angle", PI / 180, "°", "deg", "degree", "degrees", "grado", "grados")
    }

    // --- Expressions -----------------------------------------------------------------

    private class Parser(source: String) {
        private val s = source
            .lowercase()
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace(DECIMAL_COMMA, ".")
            .replace(',', ';')
        private var i = 0
        /** Whether the factor just read ended in %, to add or take a share in + and -. */
        private var percent = false

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

        private fun word(w: String): Boolean {
            skip()
            if (!s.startsWith(w, i)) return false
            val end = i + w.length
            if (end < s.length && s[end].isLetterOrDigit()) return false
            i = end
            return true
        }

        // x + 15% is x and 15% of it, as qalc reads it; 15% alone is 0.15.
        private fun expression(): Double {
            var value = term().first
            while (true) {
                when {
                    eat('+') -> term().let { (t, share) -> value = if (share) value * (1 + t) else value + t }
                    eat('-') -> term().let { (t, share) -> value = if (share) value * (1 - t) else value - t }
                    else -> return value
                }
            }
        }

        /** The value, and whether it is a bare percentage. */
        private fun term(): Pair<Double, Boolean> {
            var value = factor()
            var bare = percent
            while (true) {
                value = when {
                    eat('*') -> value * factor()
                    eat('/') -> value / factor()
                    word("mod") -> value.mod(factor())
                    startsFactor() -> value * factor() // implicit product: 2pi, 3(4+1)
                    else -> return value to bare
                }
                bare = false
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
            if (!eat('^')) return base
            val power = base.pow(factor())
            percent = false
            return power
        }

        private fun postfix(): Double {
            var value = primary()
            percent = false
            while (true) {
                when {
                    eat('!') -> value = factorial(value)
                    eat('%') -> {
                        value /= 100
                        percent = true
                    }
                    eat('°') -> value *= PI / 180
                    else -> return value
                }
            }
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
            // 0xff, 0b1010, 0o17
            if (s[i] == '0' && i + 2 < s.length + 1 && i + 1 < s.length && s[i + 1] in "xbo") {
                val radix = when (s[i + 1]) {
                    'x' -> 16
                    'b' -> 2
                    else -> 8
                }
                var j = i + 2
                while (j < s.length && Character.digit(s[j], radix) >= 0) j++
                if (j > i + 2) {
                    val value = s.substring(i + 2, j).toLong(radix).toDouble()
                    i = j
                    return value
                }
            }
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
            while (i < s.length && s[i].isLetterOrDigit()) i++
            val id = s.substring(start, i)
            CONSTANTS[id]?.let { return it }
            val f = FUNCTIONS[id] ?: throw Error("unknown '$id'")
            if (!eat('(')) throw Error("missing ( after $id")
            val args = ArrayList<Double>()
            args += expression()
            while (eat(';')) args += expression()
            if (!eat(')')) throw Error("missing )")
            return f(args)
        }

        private fun factorial(n: Double): Double {
            if (n < 0 || n != floor(n) || n > 170) throw Error("bad factorial")
            var r = 1.0
            for (k in 2..n.toInt()) r *= k
            return r
        }
    }

    private val DECIMAL_COMMA = Regex("(?<=\\d),(?=\\d)")

    private val CONSTANTS = mapOf(
        "pi" to PI, "π" to PI, "e" to E, "tau" to 2 * PI, "τ" to 2 * PI,
        "phi" to (1 + sqrt(5.0)) / 2, "φ" to (1 + sqrt(5.0)) / 2,
    )

    private fun one(f: (Double) -> Double): (List<Double>) -> Double = { args ->
        if (args.size != 1) throw Error("one argument")
        f(args[0])
    }

    private fun whole(x: Double): Long {
        if (x != floor(x) || abs(x) > 9.007199254740992e15) throw Error("not a whole number")
        return abs(x.toLong())
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private val FUNCTIONS: Map<String, (List<Double>) -> Double> = mapOf(
        "sqrt" to one { sqrt(it) },
        "cbrt" to one { cbrt(it) },
        "abs" to one { abs(it) },
        "sign" to one { sign(it) },
        "ln" to one { ln(it) },
        "log2" to one { log2(it) },
        "log10" to one { log10(it) },
        "exp" to one { exp(it) },
        "sin" to one { sin(it) },
        "cos" to one { cos(it) },
        "tan" to one { tan(it) },
        "asin" to one { asin(it) },
        "acos" to one { acos(it) },
        "atan" to one { atan(it) },
        "sinh" to one { sinh(it) },
        "cosh" to one { cosh(it) },
        "tanh" to one { tanh(it) },
        "asinh" to one { asinh(it) },
        "acosh" to one { acosh(it) },
        "atanh" to one { atanh(it) },
        "round" to one { round(it) },
        "floor" to one { floor(it) },
        "ceil" to one { ceil(it) },
        "trunc" to one { truncate(it) },
        "log" to { args ->
            when (args.size) {
                1 -> log10(args[0])
                2 -> ln(args[0]) / ln(args[1])
                else -> throw Error("one or two arguments")
            }
        },
        "root" to { args ->
            if (args.size != 2) throw Error("two arguments")
            val (x, n) = args
            if (x < 0 && n == floor(n) && n.toLong() % 2 == 1L) -(-x).pow(1 / n) else x.pow(1 / n)
        },
        "min" to { args -> args.minOrNull() ?: throw Error("no arguments") },
        "max" to { args -> args.maxOrNull() ?: throw Error("no arguments") },
        "hypot" to { args ->
            if (args.size != 2) throw Error("two arguments")
            hypot(args[0], args[1])
        },
        "gcd" to { args -> args.map { whole(it) }.reduce { a, b -> gcd(a, b) }.toDouble() },
        "lcm" to { args -> args.map { whole(it) }.reduce { a, b -> if (a == 0L || b == 0L) 0 else a / gcd(a, b) * b }.toDouble() },
    )
}
