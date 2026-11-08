package com.nihilshell.launcher.core

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The calculator's calculus, as the desktop's qalc offers it:
 *   diff(f)                derivative, symbolic: diff(x^2) = 2x
 *   diff(f; a)             its value at a (diff(f; x; a) names the variable)
 *   integrate(f)           antiderivative, symbolic where the rules reach: x^3/3 + C
 *   integrate(f; a; b)     definite integral, numeric; a or b may be inf
 *   solve(f = g)           the real solutions, numeric; "= 0" may be left out
 *   sum(f; i; a; b), product(f; i; a; b), limit(f; x; a)
 * With Spanish names too (deriva, integra, resuelve, suma, producto, límite).
 * The variable is the one letter in f, or the one named. Pure Kotlin.
 */
object Symbolic {

    // --- Expressions as trees ---------------------------------------------------------

    sealed class Node {
        data class Num(val v: Double) : Node()
        data class Const(val name: String, val v: Double) : Node()
        data class Var(val name: String) : Node()
        data class Add(val a: Node, val b: Node) : Node()
        data class Sub(val a: Node, val b: Node) : Node()
        data class Mul(val a: Node, val b: Node) : Node()
        data class Div(val a: Node, val b: Node) : Node()
        data class Pow(val a: Node, val b: Node) : Node()
        data class Neg(val a: Node) : Node()
        data class Fn(val name: String, val a: Node) : Node()
    }

    private val ZERO = Node.Num(0.0)
    private val ONE = Node.Num(1.0)
    private val TWO = Node.Num(2.0)

    private val CONSTANTS = mapOf("pi" to PI, "π" to PI, "e" to E, "tau" to 2 * PI, "τ" to 2 * PI, "phi" to (1 + sqrt(5.0)) / 2, "φ" to (1 + sqrt(5.0)) / 2)

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to ::sqrt, "cbrt" to ::cbrt, "abs" to { x: Double -> abs(x) }, "sign" to { x: Double -> sign(x) },
        "ln" to { x: Double -> ln(x) }, "log" to { x: Double -> log10(x) }, "log10" to { x: Double -> log10(x) },
        "log2" to { x: Double -> log2(x) }, "exp" to { x: Double -> exp(x) },
        "sin" to { x: Double -> sin(x) }, "cos" to { x: Double -> cos(x) }, "tan" to { x: Double -> tan(x) },
        "asin" to { x: Double -> asin(x) }, "acos" to { x: Double -> acos(x) }, "atan" to { x: Double -> atan(x) },
        "sinh" to { x: Double -> sinh(x) }, "cosh" to { x: Double -> cosh(x) }, "tanh" to { x: Double -> tanh(x) },
    )

    class Error(message: String) : Exception(message)

    /** A tree from the calculator's grammar, with one-letter variables. */
    fun parse(text: String): Node = TreeParser(normalize(text)).parse()

    private val DECIMAL_COMMA = Regex("(?<=\\d),(?=\\d)")

    private fun normalize(text: String) = text.lowercase()
        .replace('×', '*').replace('÷', '/').replace('−', '-')
        .replace(DECIMAL_COMMA, ".").replace(',', ';')
        .replace("∞", "inf")

    private class TreeParser(private val s: String) {
        private var i = 0

        fun parse(): Node {
            val n = expression()
            skip()
            if (i < s.length) throw Error("unexpected '${s[i]}'")
            return n
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

        private fun expression(): Node {
            var n = term()
            while (true) {
                n = when {
                    eat('+') -> Node.Add(n, term())
                    eat('-') -> Node.Sub(n, term())
                    else -> return n
                }
            }
        }

        private fun term(): Node {
            var n = factor()
            while (true) {
                skip()
                n = when {
                    eat('*') -> Node.Mul(n, factor())
                    eat('/') -> Node.Div(n, factor())
                    i < s.length && (s[i] == '(' || s[i].isLetter()) -> Node.Mul(n, factor())
                    else -> return n
                }
            }
        }

        private fun factor(): Node {
            if (eat('-')) return Node.Neg(factor())
            if (eat('+')) return factor()
            val base = primary()
            return if (eat('^')) Node.Pow(base, factor()) else base
        }

        private fun primary(): Node {
            skip()
            if (eat('(')) {
                val n = expression()
                if (!eat(')')) throw Error("missing )")
                return n
            }
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i + 1 < s.length && s[i] == 'e' && (s[i + 1].isDigit() || s[i + 1] == '-' && i + 2 < s.length && s[i + 2].isDigit())) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                return Node.Num(s.substring(start, i).toDoubleOrNull() ?: throw Error("bad number"))
            }
            if (i < s.length && s[i].isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetterOrDigit()) i++
                val id = s.substring(start, i)
                CONSTANTS[id]?.let { return Node.Const(id.replace("π", "pi"), it) }
                if (id == "inf") return Node.Num(Double.POSITIVE_INFINITY)
                if (id in FUNCTIONS) {
                    if (!eat('(')) throw Error("missing ( after $id")
                    val arg = expression()
                    if (!eat(')')) throw Error("missing )")
                    return Node.Fn(if (id == "log10") "log" else id, arg)
                }
                if (id.length == 1) return Node.Var(id)
                throw Error("unknown '$id'")
            }
            throw Error(if (i < s.length) "unexpected '${s[i]}'" else "unexpected end")
        }
    }

    // --- Values -----------------------------------------------------------------------

    fun eval(n: Node, env: Map<String, Double>): Double = when (n) {
        is Node.Num -> n.v
        is Node.Const -> n.v
        is Node.Var -> env[n.name] ?: throw Error("no value for ${n.name}")
        is Node.Add -> eval(n.a, env) + eval(n.b, env)
        is Node.Sub -> eval(n.a, env) - eval(n.b, env)
        is Node.Mul -> eval(n.a, env) * eval(n.b, env)
        is Node.Div -> eval(n.a, env) / eval(n.b, env)
        is Node.Pow -> power(eval(n.a, env), eval(n.b, env))
        is Node.Neg -> -eval(n.a, env)
        is Node.Fn -> (FUNCTIONS[n.name] ?: throw Error("unknown ${n.name}"))(eval(n.a, env))
    }

    /** x^y, with odd roots of negatives: (-8)^(1/3) = -2. */
    private fun power(x: Double, y: Double): Double {
        if (x < 0 && y != floor(y)) {
            val inverse = 1 / y
            if (abs(inverse - Math.rint(inverse)) < 1e-9 && Math.rint(inverse).toLong() % 2 != 0L) return -(-x).pow(y)
        }
        return x.pow(y)
    }

    private fun variables(n: Node, out: MutableSet<String> = sortedSetOf()): Set<String> {
        when (n) {
            is Node.Var -> out += n.name
            is Node.Add -> { variables(n.a, out); variables(n.b, out) }
            is Node.Sub -> { variables(n.a, out); variables(n.b, out) }
            is Node.Mul -> { variables(n.a, out); variables(n.b, out) }
            is Node.Div -> { variables(n.a, out); variables(n.b, out) }
            is Node.Pow -> { variables(n.a, out); variables(n.b, out) }
            is Node.Neg -> variables(n.a, out)
            is Node.Fn -> variables(n.a, out)
            else -> Unit
        }
        return out
    }

    private fun has(n: Node, v: String) = v in variables(n)

    // --- Derivatives --------------------------------------------------------------------

    fun derivative(n: Node, v: String): Node = simplify(d(n, v))

    private fun d(n: Node, v: String): Node = when (n) {
        is Node.Num, is Node.Const -> ZERO
        is Node.Var -> if (n.name == v) ONE else ZERO
        is Node.Add -> Node.Add(d(n.a, v), d(n.b, v))
        is Node.Sub -> Node.Sub(d(n.a, v), d(n.b, v))
        is Node.Mul -> Node.Add(Node.Mul(d(n.a, v), n.b), Node.Mul(n.a, d(n.b, v)))
        is Node.Div -> Node.Div(Node.Sub(Node.Mul(d(n.a, v), n.b), Node.Mul(n.a, d(n.b, v))), Node.Pow(n.b, TWO))
        is Node.Neg -> Node.Neg(d(n.a, v))
        is Node.Pow -> when {
            !has(n.b, v) -> Node.Mul(Node.Mul(n.b, Node.Pow(n.a, Node.Sub(n.b, ONE))), d(n.a, v))
            !has(n.a, v) -> Node.Mul(Node.Mul(n, Node.Fn("ln", n.a)), d(n.b, v))
            else -> Node.Mul(n, Node.Add(Node.Mul(d(n.b, v), Node.Fn("ln", n.a)), Node.Div(Node.Mul(n.b, d(n.a, v)), n.a)))
        }
        is Node.Fn -> {
            val u = n.a
            val inner = d(u, v)
            val outer: Node = when (n.name) {
                "sin" -> Node.Fn("cos", u)
                "cos" -> Node.Neg(Node.Fn("sin", u))
                "tan" -> Node.Div(ONE, Node.Pow(Node.Fn("cos", u), TWO))
                "exp" -> Node.Fn("exp", u)
                "ln" -> Node.Div(ONE, u)
                "log" -> Node.Div(ONE, Node.Mul(u, Node.Fn("ln", Node.Num(10.0))))
                "log2" -> Node.Div(ONE, Node.Mul(u, Node.Fn("ln", TWO)))
                "sqrt" -> Node.Div(ONE, Node.Mul(TWO, Node.Fn("sqrt", u)))
                "cbrt" -> Node.Div(ONE, Node.Mul(Node.Num(3.0), Node.Pow(Node.Fn("cbrt", u), TWO)))
                "asin" -> Node.Div(ONE, Node.Fn("sqrt", Node.Sub(ONE, Node.Pow(u, TWO))))
                "acos" -> Node.Neg(Node.Div(ONE, Node.Fn("sqrt", Node.Sub(ONE, Node.Pow(u, TWO)))))
                "atan" -> Node.Div(ONE, Node.Add(ONE, Node.Pow(u, TWO)))
                "sinh" -> Node.Fn("cosh", u)
                "cosh" -> Node.Fn("sinh", u)
                "tanh" -> Node.Div(ONE, Node.Pow(Node.Fn("cosh", u), TWO))
                "abs" -> Node.Fn("sign", u)
                else -> throw Error("no derivative for ${n.name}")
            }
            Node.Mul(outer, inner)
        }
    }

    // --- Antiderivatives (where the rules reach) -------------------------------------

    fun antiderivative(n: Node, v: String): Node? = runCatching { integral(simplify(n), v)?.let { simplify(it) } }.getOrNull()

    /** a·v + b: the coefficient a, when [n] is that, else null. */
    private fun linear(n: Node, v: String): Double? {
        val slope = simplify(d(n, v))
        if (has(slope, v)) return null
        val a = eval(slope, emptyMap())
        return a.takeIf { it != 0.0 && it.isFinite() }
    }

    private fun integral(n: Node, v: String): Node? {
        if (!has(n, v)) return Node.Mul(n, Node.Var(v))
        return when (n) {
            is Node.Var -> Node.Div(Node.Pow(n, TWO), TWO)
            is Node.Add -> Node.Add(integral(n.a, v) ?: return null, integral(n.b, v) ?: return null)
            is Node.Sub -> Node.Sub(integral(n.a, v) ?: return null, integral(n.b, v) ?: return null)
            is Node.Neg -> Node.Neg(integral(n.a, v) ?: return null)
            is Node.Mul -> when {
                !has(n.a, v) -> Node.Mul(n.a, integral(n.b, v) ?: return null)
                !has(n.b, v) -> Node.Mul(n.b, integral(n.a, v) ?: return null)
                else -> null
            }
            is Node.Div -> when {
                !has(n.b, v) -> Node.Div(integral(n.a, v) ?: return null, n.b)
                !has(n.a, v) -> {
                    val a = linear(n.b, v)
                    when {
                        a != null -> Node.Div(Node.Mul(n.a, Node.Fn("ln", Node.Fn("abs", n.b))), Node.Num(a))
                        n.b == Node.Add(ONE, Node.Pow(Node.Var(v), TWO)) || n.b == Node.Add(Node.Pow(Node.Var(v), TWO), ONE) ->
                            Node.Mul(n.a, Node.Fn("atan", Node.Var(v)))
                        else -> null
                    }
                }
                else -> null
            }
            is Node.Pow -> {
                val baseA = linear(n.a, v)
                when {
                    baseA != null && !has(n.b, v) -> {
                        val k = eval(n.b, emptyMap())
                        if (k == -1.0) {
                            Node.Div(Node.Fn("ln", Node.Fn("abs", n.a)), Node.Num(baseA))
                        } else {
                            Node.Div(Node.Pow(n.a, Node.Num(k + 1)), Node.Num((k + 1) * baseA))
                        }
                    }
                    !has(n.a, v) -> {
                        val a = linear(n.b, v) ?: return null
                        val base = eval(n.a, emptyMap())
                        if (n.a is Node.Const && n.a.name == "e") Node.Div(n, Node.Num(a)) else Node.Div(n, Node.Mul(Node.Num(a), Node.Fn("ln", n.a)).takeIf { base > 0 } ?: return null)
                    }
                    else -> null
                }
            }
            is Node.Fn -> {
                val a = linear(n.a, v) ?: return when {
                    n.name == "ln" && n.a == Node.Var(v) -> Node.Sub(Node.Mul(n.a, n), n.a)
                    else -> null
                }
                val u = n.a
                val f: Node = when (n.name) {
                    "sin" -> Node.Neg(Node.Fn("cos", u))
                    "cos" -> Node.Fn("sin", u)
                    "tan" -> Node.Neg(Node.Fn("ln", Node.Fn("abs", Node.Fn("cos", u))))
                    "exp" -> Node.Fn("exp", u)
                    "sinh" -> Node.Fn("cosh", u)
                    "cosh" -> Node.Fn("sinh", u)
                    "sqrt" -> Node.Div(Node.Mul(TWO, Node.Pow(u, Node.Num(1.5))), Node.Num(3.0))
                    "ln" -> Node.Sub(Node.Mul(u, Node.Fn("ln", u)), u)
                    else -> return null
                }
                if (a == 1.0) f else Node.Div(f, Node.Num(a))
            }
            else -> null
        }
    }

    // --- Simplifying, enough to read the results --------------------------------------

    fun simplify(n: Node): Node {
        var current = n
        repeat(8) {
            val next = step(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun num(n: Node): Double? = (n as? Node.Num)?.v

    private fun whole(x: Double) = x == floor(x) && abs(x) < 1e15

    private fun step(n: Node): Node = when (n) {
        is Node.Add -> {
            val a = step(n.a)
            val b = step(n.b)
            val x = num(a)
            val y = num(b)
            when {
                x != null && y != null -> Node.Num(x + y)
                x == 0.0 -> b
                y == 0.0 -> a
                b is Node.Neg -> Node.Sub(a, b.a)
                y != null && y < 0 -> Node.Sub(a, Node.Num(-y))
                a == b -> Node.Mul(TWO, a)
                else -> Node.Add(a, b)
            }
        }
        is Node.Sub -> {
            val a = step(n.a)
            val b = step(n.b)
            val x = num(a)
            val y = num(b)
            when {
                x != null && y != null -> Node.Num(x - y)
                y == 0.0 -> a
                x == 0.0 -> Node.Neg(b)
                a == b -> ZERO
                b is Node.Neg -> Node.Add(a, b.a)
                else -> Node.Sub(a, b)
            }
        }
        is Node.Mul -> {
            val a = step(n.a)
            val b = step(n.b)
            val x = num(a)
            val y = num(b)
            when {
                x != null && y != null -> Node.Num(x * y)
                x == 0.0 || y == 0.0 -> ZERO
                x == 1.0 -> b
                y == 1.0 -> a
                x == -1.0 -> Node.Neg(b)
                y == -1.0 -> Node.Neg(a)
                y != null -> Node.Mul(b, a)
                a is Node.Neg -> Node.Neg(Node.Mul(a.a, b))
                b is Node.Neg -> Node.Neg(Node.Mul(a, b.a))
                x != null && b is Node.Mul && num(b.a) != null -> Node.Mul(Node.Num(x * num(b.a)!!), b.b)
                x != null && b is Node.Div && num(b.b) != null && whole(x / num(b.b)!!) -> Node.Mul(Node.Num(x / num(b.b)!!), b.a)
                a == b -> Node.Pow(a, TWO)
                b is Node.Pow && b.a == a && num(b.b) != null -> Node.Pow(a, Node.Num(num(b.b)!! + 1))
                a is Node.Pow && a.a == b && num(a.b) != null -> Node.Pow(b, Node.Num(num(a.b)!! + 1))
                a is Node.Fn && a.name == "exp" && b is Node.Fn && b.name == "exp" -> Node.Fn("exp", Node.Add(a.a, b.a))
                else -> Node.Mul(a, b)
            }
        }
        is Node.Div -> {
            val a = step(n.a)
            val b = step(n.b)
            val x = num(a)
            val y = num(b)
            when {
                x != null && y != null && y != 0.0 && whole(x / y) -> Node.Num(x / y)
                y == 1.0 -> a
                x == 0.0 -> ZERO
                a == b -> ONE
                a is Node.Neg -> Node.Neg(Node.Div(a.a, b))
                y != null && a is Node.Mul && num(a.a) != null && whole(num(a.a)!! / y) -> Node.Mul(Node.Num(num(a.a)!! / y), a.b)
                else -> Node.Div(a, b)
            }
        }
        is Node.Pow -> {
            val a = step(n.a)
            val b = step(n.b)
            val x = num(a)
            val y = num(b)
            when {
                y == 0.0 -> ONE
                y == 1.0 -> a
                x != null && y != null && whole(x) && whole(y) && y > 0 -> Node.Num(x.pow(y))
                a is Node.Const && a.name == "e" -> Node.Fn("exp", b)
                else -> Node.Pow(a, b)
            }
        }
        is Node.Neg -> {
            val a = step(n.a)
            when {
                a is Node.Neg -> a.a
                num(a) != null -> Node.Num(-num(a)!!)
                else -> Node.Neg(a)
            }
        }
        is Node.Fn -> {
            val a = step(n.a)
            when {
                n.name == "ln" && a is Node.Const && a.name == "e" -> ONE
                n.name == "ln" && a is Node.Fn && a.name == "exp" -> a.a
                n.name == "exp" && a is Node.Fn && a.name == "ln" -> a.a
                n.name == "exp" && num(a) == 0.0 -> ONE
                else -> Node.Fn(n.name, a)
            }
        }
        else -> n
    }

    // --- Writing them -----------------------------------------------------------------

    /** As the calculator reads it back: 2x, 3x^2, x*cos(x), e^x, ln(abs(x)). */
    fun print(n: Node): String = when (n) {
        is Node.Num -> Calculator.format(n.v)
        is Node.Const -> n.name
        is Node.Var -> n.name
        is Node.Add -> "${print(n.a)} + ${print(n.b)}"
        is Node.Sub -> "${print(n.a)} - ${wrap(n.b, n.b is Node.Add || n.b is Node.Sub)}"
        is Node.Neg -> "-" + wrap(n.a, n.a is Node.Add || n.a is Node.Sub)
        is Node.Mul -> {
            val a = n.a
            val b = n.b
            val number = a is Node.Num && a.v >= 0
            val tight = b is Node.Var || b is Node.Fn || b is Node.Const || (b is Node.Pow && (b.a is Node.Var || b.a is Node.Const))
            if (number && tight) {
                print(a) + print(b)
            } else {
                wrap(a, a is Node.Add || a is Node.Sub) + "*" + wrap(b, b is Node.Add || b is Node.Sub || b is Node.Neg)
            }
        }
        is Node.Div -> wrap(n.a, n.a is Node.Add || n.a is Node.Sub) + "/" +
            wrap(n.b, n.b !is Node.Num && n.b !is Node.Var && n.b !is Node.Const && n.b !is Node.Fn)
        is Node.Pow -> wrap(n.a, n.a !is Node.Num && n.a !is Node.Var && n.a !is Node.Const && n.a !is Node.Fn) + "^" +
            wrap(n.b, n.b !is Node.Num && n.b !is Node.Var && n.b !is Node.Const)
        is Node.Fn -> if (n.name == "exp") "e^" + wrap(n.a, n.a !is Node.Num && n.a !is Node.Var) else "${n.name}(${print(n.a)})"
    }

    private fun wrap(n: Node, parentheses: Boolean): String = if (parentheses) "(${print(n)})" else print(n)

    // --- Numbers --------------------------------------------------------------------

    /** ∫ f from a to b, adaptive Simpson; an infinite end is folded onto [0, 1). */
    fun definite(f: (Double) -> Double, a: Double, b: Double): Double {
        if (a == b) return 0.0
        if (a > b) return -definite(f, b, a)
        if (a.isInfinite() && b.isInfinite()) return definite(f, a, 0.0) + definite(f, 0.0, b)
        if (b.isInfinite()) return simpson({ t -> f(a + t / (1 - t)) / ((1 - t) * (1 - t)) }, 0.0, 1.0 - 1e-12)
        if (a.isInfinite()) return simpson({ t -> f(b - t / (1 - t)) / ((1 - t) * (1 - t)) }, 0.0, 1.0 - 1e-12)
        return simpson(f, a, b)
    }

    private fun simpson(f: (Double) -> Double, a: Double, b: Double): Double {
        fun safe(x: Double): Double = f(x).let { if (it.isFinite()) it else f(x + 1e-12 * max(1.0, abs(x))) }
        val fa = safe(a)
        val fb = safe(b)
        val m = (a + b) / 2
        val fm = safe(m)
        var calls = 0
        fun step(a: Double, b: Double, fa: Double, fm: Double, fb: Double, whole: Double, eps: Double, depth: Int): Double {
            val m = (a + b) / 2
            val lm = (a + m) / 2
            val rm = (m + b) / 2
            val flm = safe(lm)
            val frm = safe(rm)
            calls += 2
            val left = (m - a) / 6 * (fa + 4 * flm + fm)
            val right = (b - m) / 6 * (fm + 4 * frm + fb)
            val delta = left + right - whole
            if (depth <= 0 || calls > 200000 || abs(delta) <= 15 * eps) return left + right + delta / 15
            return step(a, m, fa, flm, fm, left, eps / 2, depth - 1) + step(m, b, fm, frm, fb, right, eps / 2, depth - 1)
        }
        val whole = (b - a) / 6 * (fa + 4 * fm + fb)
        return step(a, b, fa, fm, fb, whole, 1e-10, 48)
    }

    /** The real roots of f in [-1000, 1000]: sign changes refined by bisection, and touching zeros. */
    fun roots(f: (Double) -> Double): List<Double> {
        val found = ArrayList<Double>()
        fun add(x: Double) {
            val clean = if (abs(x - Math.rint(x)) < 1e-9) Math.rint(x) else x
            if (found.none { abs(it - clean) < 1e-7 * max(1.0, abs(clean)) }) found += clean
        }
        fun scan(from: Double, to: Double, steps: Int) {
            var x0 = from
            var y0 = f(x0)
            for (k in 1..steps) {
                val x1 = from + (to - from) * k / steps
                val y1 = f(x1)
                if (y0 == 0.0) add(x0)
                if (y0.isFinite() && y1.isFinite() && y0 * y1 < 0) {
                    var lo = x0
                    var hi = x1
                    var flo = y0
                    repeat(200) {
                        val mid = (lo + hi) / 2
                        val fm = f(mid)
                        if (flo * fm <= 0) hi = mid else {
                            lo = mid
                            flo = fm
                        }
                    }
                    val r = (lo + hi) / 2
                    // A pole changes sign too: only a root where f is small.
                    if (abs(f(r)) < 1e-6 * max(1.0, abs(y0) + abs(y1))) add(r)
                }
                x0 = x1
                y0 = y1
            }
        }
        scan(-10.0, 10.0, 20000)
        scan(-1000.0, -10.0, 20000)
        scan(10.0, 1000.0, 20000)
        // Zeros that touch without crossing (x^2 = 0): a small value at a local minimum of |f|.
        var x = -10.0
        while (x <= 10.0) {
            val y = abs(f(x))
            if (y < 1e-12) add(x)
            x += 0.0005
        }
        return found.sorted()
    }

    // --- The calculator's way in --------------------------------------------------------

    private val CALL = Regex("^\\s*([a-záéíóú]+)\\s*\\((.*)\\)\\s*$")

    private val NAMES = mapOf(
        "diff" to "diff", "derivative" to "diff", "derivada" to "diff", "deriva" to "diff", "der" to "diff",
        "integrate" to "integrate", "integral" to "integrate", "integra" to "integrate", "int" to "integrate",
        "solve" to "solve", "resuelve" to "solve", "resolver" to "solve",
        "sum" to "sum", "suma" to "sum", "sumatorio" to "sum",
        "product" to "product", "producto" to "product", "prod" to "product",
        "limit" to "limit", "limite" to "limit", "límite" to "limit", "lim" to "limit",
    )

    /** For Calculator: the answer to one of these calls, or null when [text] isn't one. */
    fun calculate(text: String): Calculator.Answer? {
        val m = CALL.matchEntire(normalize(text)) ?: return null
        val what = NAMES[m.groupValues[1]] ?: return null
        val args = split(m.groupValues[2])
        if (args.isEmpty() || args[0].isBlank()) throw Error("nothing to work on")
        return when (what) {
            "diff" -> diff(args)
            "integrate" -> integrate(args)
            "solve" -> solve(args)
            "sum", "product" -> series(args, what == "sum")
            else -> limit(args)
        }
    }

    /** The arguments, apart at the ";" that aren't inside parentheses. */
    private fun split(inside: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in inside) {
            when {
                c == '(' -> depth++
                c == ')' -> depth--
            }
            if (c == ';' && depth == 0) {
                out += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
        }
        out += current.toString().trim()
        return out
    }

    private fun isName(arg: String) = arg.length == 1 && arg[0].isLetter() && arg != "e"

    private fun variableOf(f: Node, named: String?): String {
        if (named != null) return named
        val found = variables(f)
        return when (found.size) {
            0 -> "x"
            1 -> found.first()
            else -> throw Error("say which variable: ${found.joinToString()}")
        }
    }

    private fun number(arg: String): Double = eval(parse(arg), emptyMap())

    private fun function(f: Node, v: String): (Double) -> Double = { x -> eval(f, mapOf(v to x)) }

    private fun diff(args: List<String>): Calculator.Answer {
        val f = parse(args[0])
        val named = args.getOrNull(1)?.takeIf { isName(it) }
        val v = variableOf(f, named)
        val at = args.drop(if (named != null) 2 else 1).firstOrNull()
        val symbolic = runCatching { derivative(f, v) }.getOrNull()
        if (at == null) {
            symbolic ?: throw Error("no derivative")
            val text = print(symbolic)
            val value = runCatching { eval(symbolic, emptyMap()) }.getOrDefault(Double.NaN)
            return Calculator.Answer(value, text)
        }
        val x = number(at)
        val value = symbolic?.let { runCatching { eval(it, mapOf(v to x)) }.getOrNull() }
            ?: run {
                // Five points around x, where there is no rule.
                val g = function(f, v)
                val h = 1e-3 * max(1.0, abs(x))
                (-g(x + 2 * h) + 8 * g(x + h) - 8 * g(x - h) + g(x - 2 * h)) / (12 * h)
            }
        return Calculator.Answer(value, Calculator.format(value))
    }

    private fun integrate(args: List<String>): Calculator.Answer {
        val f = parse(args[0])
        val named = args.getOrNull(1)?.takeIf { isName(it) }
        val v = variableOf(f, named)
        val bounds = args.drop(if (named != null) 2 else 1)
        if (bounds.isEmpty()) {
            val F = antiderivative(f, v) ?: throw Error("no antiderivative by the rules: give it bounds")
            return Calculator.Answer(Double.NaN, print(F) + " + C")
        }
        if (bounds.size != 2) throw Error("two bounds")
        val a = number(bounds[0])
        val b = number(bounds[1])
        val value = definite(function(f, v), a, b)
        if (!value.isFinite()) throw Error("it doesn't converge")
        val exact = if (abs(value - Math.rint(value)) < 1e-9) Math.rint(value) else value
        return Calculator.Answer(exact, Calculator.format(exact))
    }

    private fun solve(args: List<String>): Calculator.Answer {
        val equation = args[0]
        val sides = equation.split('=')
        if (sides.size > 2) throw Error("one =")
        val f = if (sides.size == 2) Node.Sub(parse(sides[0]), parse(sides[1])) else parse(sides[0])
        val v = variableOf(f, args.getOrNull(1)?.takeIf { isName(it) })
        val roots = roots(function(f, v))
        if (roots.isEmpty()) throw Error("no real solutions")
        return Calculator.Answer(roots.first(), roots.take(8).joinToString(", ") { "$v = " + Calculator.format(it) })
    }

    private fun series(args: List<String>, sum: Boolean): Calculator.Answer {
        if (args.size != 4 || !isName(args[1])) throw Error("f; i; from; to")
        val f = parse(args[0])
        val i = args[1]
        val from = number(args[2])
        val to = number(args[3])
        if (!whole(from) || !whole(to) || to - from > 1e6) throw Error("whole bounds, a million terms at most")
        var acc = if (sum) 0.0 else 1.0
        var k = from
        while (k <= to) {
            val term = eval(f, mapOf(i to k))
            acc = if (sum) acc + term else acc * term
            k += 1.0
        }
        return Calculator.Answer(acc, Calculator.format(acc))
    }

    private fun limit(args: List<String>): Calculator.Answer {
        val f = parse(args[0])
        val named = args.getOrNull(1)?.takeIf { isName(it) }
        val v = variableOf(f, named)
        val at = args.drop(if (named != null) 2 else 1).firstOrNull() ?: throw Error("where to")
        val g = function(f, v)
        val a = number(at)
        val value = if (a.isInfinite()) {
            val near = g(if (a > 0) 1e8 else -1e8)
            val far = g(if (a > 0) 1e10 else -1e10)
            if (abs(near - far) > 1e-6 * max(1.0, abs(far))) throw Error("it doesn't settle")
            far
        } else {
            val h = 1e-7 * max(1.0, abs(a))
            val left = g(a - h)
            val right = g(a + h)
            if (!left.isFinite() || !right.isFinite() || abs(left - right) > 1e-4 * max(1.0, abs(left))) throw Error("no limit")
            (left + right) / 2
        }
        val clean = if (abs(value - Math.rint(value)) < 1e-6) Math.rint(value) else value
        return Calculator.Answer(clean, Calculator.format(clean))
    }
}
