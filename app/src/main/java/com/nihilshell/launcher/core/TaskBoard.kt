package com.nihilshell.launcher.core

/**
 * The kanban the phone and the desktop both keep (home/shell/services/Tasks.qml
 * there, with the same rules in TaskMerge.js). Each card has an id, its text and
 * its column, and when each of those was last set (textStamp, placeStamp, in the
 * desktop's milliseconds); a removed card leaves the stamp of its removal.
 *
 * Two boards merge into one, the same whichever way round and however many
 * times: for each card the text set last and the column set last win, and a
 * removal wins over everything set before it. So a phone away for a week and a
 * desktop that went on meanwhile each keep what the other did.
 */
object TaskBoard {
    const val COLUMNS = 3
    const val MAX_TEXT = 160
    /** Removals are remembered for 60 days, so a phone back after long doesn't bring a card back. */
    const val KEEP_REMOVED = 60L * 24 * 60 * 60 * 1000

    data class Card(val id: String, val text: String, val column: Int, val textStamp: Long, val placeStamp: Long)

    data class Board(val cards: List<Card> = emptyList(), val removed: Map<String, Long> = emptyMap())

    fun merge(a: Board, b: Board, now: Long): Board {
        val mine = a.cards.associateBy { it.id }
        val theirs = b.cards.associateBy { it.id }
        val cards = ArrayList<Card>()
        val removed = HashMap<String, Long>()
        for (id in mine.keys + theirs.keys + a.removed.keys + b.removed.keys) {
            val x = mine[id]
            val y = theirs[id]
            val card = if (x != null && y != null) combine(x, y) else x ?: y
            val gone = maxOf(a.removed[id] ?: Long.MIN_VALUE, b.removed[id] ?: Long.MIN_VALUE)
            if (card != null && gone < maxOf(card.textStamp, card.placeStamp)) {
                cards += card
            } else if (gone != Long.MIN_VALUE && now - gone < KEEP_REMOVED) {
                removed[id] = gone
            }
        }
        return Board(ordered(cards), removed)
    }

    // Equal stamps (rare): the greater text, the further column, on both sides alike.
    private fun combine(x: Card, y: Card): Card {
        val text = when {
            x.textStamp != y.textStamp -> if (x.textStamp > y.textStamp) x else y
            else -> if (x.text >= y.text) x else y
        }
        val place = when {
            x.placeStamp != y.placeStamp -> if (x.placeStamp > y.placeStamp) x else y
            else -> if (x.column >= y.column) x else y
        }
        return Card(x.id, text.text, place.column, text.textStamp, place.placeStamp)
    }

    /** By column, then in the order they got there, as the desktop shows them. */
    fun ordered(cards: List<Card>): List<Card> = cards.sortedWith(compareBy<Card>({ it.column }, { it.placeStamp }, { it.id }))

    /** The latest stamp a board holds: the clock goes on from there. */
    fun latest(board: Board): Long =
        maxOf(board.cards.maxOfOrNull { maxOf(it.textStamp, it.placeStamp) } ?: 0L, board.removed.values.maxOrNull() ?: 0L)
}
