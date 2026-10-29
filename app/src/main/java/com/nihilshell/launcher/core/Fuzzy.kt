package com.nihilshell.launcher.core

import java.text.Normalizer
import java.util.Locale

/**
 * The launcher's search, the same scoring as the desktop shell: exact name,
 * prefix, start of a word, anywhere, then letters in order. Accents and case
 * are ignored, so "camara" finds "Cámara". Pure Kotlin, tested on the JVM.
 */
object Fuzzy {
    private val MARKS = Regex("\\p{Mn}+")
    private val WORDS = Regex("[\\s\\-_.·]+")

    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

    /** Score of a normalised [name] for a normalised, non-empty query [q]; -1 if it doesn't match. */
    fun score(name: String, q: String): Int {
        if (q.isEmpty()) return 0
        if (name == q) return 1000
        if (name.startsWith(q)) return 800 - name.length
        if (name.split(WORDS).any { it.startsWith(q) }) return 600 - name.length
        val at = name.indexOf(q)
        if (at >= 0) return 400 - at
        var matched = 0
        for (c in name) {
            if (matched < q.length && c == q[matched]) matched++
        }
        return if (matched == q.length) 100 - name.length else -1
    }

    /** The [items] that match [query], best first; all of them, in order, for an empty query. */
    fun <T> filter(items: List<T>, query: String, text: (T) -> String): List<T> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return items
        return items
            .map { it to score(text(it), q) }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
