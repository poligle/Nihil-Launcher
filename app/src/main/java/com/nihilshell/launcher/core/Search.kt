package com.nihilshell.launcher.core

import com.nihilshell.launcher.R

/**
 * What the drawer shows for a query.
 *
 *   ""        every visible app, alphabetically, under letters
 *   "= 2+2"   the calculator
 *   "> wi"    actions only
 *   "lin"     apps (hidden ones too) and actions, best first; a sum when the
 *             query is one ("12*7"); "search the web" at the end
 */
object Search {

    enum class Mode { APPS, SEARCH, ACTIONS, CALCULATOR }

    sealed class Row {
        class App(val entry: AppEntry) : Row()
        class Act(val action: Action) : Row()
        class Calc(val expression: String, val value: Double) : Row()
        class Web(val query: String) : Row()
        class Letter(val letter: String) : Row()
        class Message(val text: Int) : Row()
    }

    class Result(val mode: Mode, val rows: List<Row>, val matches: Int)

    private const val LIMIT = 60

    fun run(query: String, apps: List<AppEntry>, hidden: Set<String>, actions: List<Action>): Result {
        val raw = query.trim()
        return when {
            raw.isEmpty() -> everything(apps, hidden)
            raw.startsWith("=") -> calculator(raw.drop(1))
            raw.startsWith(">") -> actionsOnly(Fuzzy.normalize(raw.drop(1).trim()), actions)
            else -> search(raw, apps, actions)
        }
    }

    private fun everything(apps: List<AppEntry>, hidden: Set<String>): Result {
        val rows = ArrayList<Row>(apps.size + 27)
        var last: String? = null
        var count = 0
        for (app in apps) {
            if (app.key in hidden) continue
            val letter = letterOf(app.label)
            if (letter != last) {
                rows += Row.Letter(letter)
                last = letter
            }
            rows += Row.App(app)
            count++
        }
        return Result(Mode.APPS, rows, count)
    }

    private fun calculator(expression: String): Result {
        val value = if (expression.isBlank()) null else Calculator.evaluate(expression)
        return if (value != null) {
            Result(Mode.CALCULATOR, listOf(Row.Calc(expression, value)), 1)
        } else {
            Result(Mode.CALCULATOR, listOf(Row.Message(R.string.calc_type)), 0)
        }
    }

    private fun actionsOnly(q: String, actions: List<Action>): Result {
        val list = if (q.isEmpty()) {
            actions
        } else {
            actions.map { it to it.score(q) }.filter { it.second >= 0 }.sortedByDescending { it.second }.map { it.first }
        }
        val rows: List<Row> = list.map { Row.Act(it) }
        return Result(Mode.ACTIONS, rows.ifEmpty { listOf(Row.Message(R.string.no_results)) }, list.size)
    }

    private fun search(raw: String, apps: List<AppEntry>, actions: List<Action>): Result {
        val q = Fuzzy.normalize(raw)
        val scored = ArrayList<Pair<Row, Int>>()
        for (app in apps) {
            val s = maxOf(Fuzzy.score(app.searchLabel, q), packageScore(app.packageName, q))
            if (s >= 0) scored += Row.App(app) to s
        }
        // Apps win ties: an action needs a clearly better match to go first.
        for (action in actions) {
            val s = action.score(q)
            if (s >= 0) scored += Row.Act(action) to s - 50
        }
        scored.sortByDescending { it.second }

        val rows = ArrayList<Row>()
        if (isMath(raw)) Calculator.evaluate(raw)?.let { rows += Row.Calc(raw, it) }
        scored.asSequence().take(LIMIT).mapTo(rows) { it.first }
        if (rows.isEmpty() || raw.length >= 2) rows += Row.Web(raw)
        return Result(Mode.SEARCH, rows, scored.size)
    }

    // --- Pure helpers, tested on the JVM -------------------------------------------

    /** The list letter for a label: A-Z without accents, "#" for digits and symbols. */
    fun letterOf(label: String): String {
        val c = Fuzzy.normalize(label).firstOrNull { !it.isWhitespace() } ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    /** True when the query reads as a sum rather than a name: "12*7", "sqrt(2)", "5!". */
    fun isMath(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        val hasDigit = s.any { it.isDigit() } || s.contains("pi") || s.contains('π')
        val operator = s.any { it in "+-*/^%×÷!(" }
        val startsRight = s[0].isDigit() || s[0] in "(-.π" || FUNCTION.matches(s)
        return hasDigit && operator && startsRight
    }

    private val FUNCTION = Regex("^[a-z]+\\(.*", RegexOption.IGNORE_CASE)

    private val COMMON = setOf("com", "android", "google", "samsung", "sec", "app", "apps", "org", "net", "mobile")

    /**
     * A package name word that starts with the query, so "settings" finds
     * "Ajustes" (com.android.settings). Weaker than any label match.
     */
    fun packageScore(packageName: String, q: String): Int {
        if (q.length < 3) return -1
        val words = packageName.lowercase().split('.', '_').filter { it.isNotEmpty() && it !in COMMON }
        return if (words.any { it.startsWith(q) }) 300 else -1
    }
}
