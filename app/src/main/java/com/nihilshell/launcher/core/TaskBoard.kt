package com.nihilshell.launcher.core

object TaskBoard {
    const val COLUMNS = 3
    const val MAX_TEXT = 160
    const val KEEP_REMOVED = 60L * 24 * 60 * 60 * 1000

    data class Card(
        val id: String,
        val text: String,
        val column: Int,
        val textStamp: Long,
        val placeStamp: Long,
        val rank: Double? = null,
    ) {
        val position: Double get() = rank ?: placeStamp.toDouble()
    }

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

    private fun combine(x: Card, y: Card): Card {
        val text = when {
            x.textStamp != y.textStamp -> if (x.textStamp > y.textStamp) x else y
            else -> if (x.text >= y.text) x else y
        }
        val place = when {
            x.placeStamp != y.placeStamp -> if (x.placeStamp > y.placeStamp) x else y
            (x.rank != null) != (y.rank != null) -> if (x.rank != null) x else y
            x.column != y.column -> if (x.column > y.column) x else y
            else -> if (x.rank != null && y.rank != null && y.rank > x.rank) y else x
        }
        return Card(x.id, text.text, place.column, text.textStamp, place.placeStamp, place.rank)
    }

    fun ordered(cards: List<Card>): List<Card> = cards.sortedWith(compareBy<Card>({ it.column }, { it.position }, { it.id }))

    fun rankAt(others: List<Card>, index: Int, now: Long): Double? {
        val i = index.coerceIn(0, others.size)
        val before = others.getOrNull(i - 1)?.position
        val after = others.getOrNull(i)?.position
        if (before == null) return if (after == null) now.toDouble() else after - 1
        if (after == null) return before + 1
        return ((before + after) / 2).takeIf { it > before && it < after }
    }

    fun place(board: Board, id: String, column: Int, index: Int, now: Long): Board {
        val card = board.cards.firstOrNull { it.id == id } ?: return board
        val lane = ordered(board.cards.filter { it.column == column })
        val others = lane.filter { it.id != id }
        val at = index.coerceIn(0, others.size)
        if (card.column == column && lane.indexOfFirst { it.id == id } == at) return board
        val ranks = HashMap<String, Double>()
        val rank = rankAt(others, at, now)
        if (rank != null) {
            ranks[id] = rank
        } else {
            (others.take(at) + card + others.drop(at)).forEachIndexed { i, c -> ranks[c.id] = (now + i).toDouble() }
        }
        return Board(ordered(board.cards.map { c -> ranks[c.id]?.let { c.copy(column = column, placeStamp = now, rank = it) } ?: c }), board.removed)
    }

    fun rank(value: Double?): Double? = value?.takeIf { it.isFinite() }?.plus(0.0)

    fun latest(board: Board): Long =
        maxOf(board.cards.maxOfOrNull { maxOf(it.textStamp, it.placeStamp) } ?: 0L, board.removed.values.maxOrNull() ?: 0L)
}
