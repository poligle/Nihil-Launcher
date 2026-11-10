package com.nihilshell.launcher.core

import com.nihilshell.launcher.core.TaskBoard.Board
import com.nihilshell.launcher.core.TaskBoard.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kanban's merge: what the phone and the desktop each did, both kept. */
class TaskBoardTest {
    private val now = 1_800_000_000_000L
    private fun card(id: String, text: String, column: Int, t: Long, p: Long = t) = Card(id, text, column, now + t, now + p)

    @Test
    fun bothSidesChangedWhileApart() {
        val before = Board(listOf(card("a", "Entregar la práctica", 0, 1), card("b", "Keycaps", 0, 2)))
        // The phone moved a to doing and added c; the desktop renamed a and removed b.
        val phone = Board(listOf(card("a", "Entregar la práctica", 1, 1, 10), card("b", "Keycaps", 0, 2), card("c", "Nilo v3", 0, 11)))
        val desktop = Board(listOf(card("a", "Entregar la práctica 2", 0, 12, 1)), mapOf("b" to now + 13))
        val merged = TaskBoard.merge(phone, desktop, now + 20)
        val a = merged.cards.first { it.id == "a" }
        assertEquals("Entregar la práctica 2", a.text)
        assertEquals(1, a.column)
        assertTrue(merged.cards.none { it.id == "b" })
        assertTrue(merged.cards.any { it.id == "c" })
        assertEquals(now + 13, merged.removed["b"])
        assertEquals(TaskBoard.merge(before, merged, now + 20), TaskBoard.merge(merged, before, now + 20))
    }

    @Test
    fun theSameWhicheverWayRoundAndHoweverOften() {
        val x = Board(listOf(card("a", "One", 0, 5), card("b", "Two", 2, 3)), mapOf("z" to now + 1))
        val y = Board(listOf(card("a", "Uno", 1, 4, 6), card("c", "Three", 0, 7)), mapOf("b" to now + 9))
        val xy = TaskBoard.merge(x, y, now + 10)
        assertEquals(xy, TaskBoard.merge(y, x, now + 10))
        assertEquals(xy, TaskBoard.merge(xy, x, now + 10))
        assertEquals(xy, TaskBoard.merge(TaskBoard.merge(xy, y, now + 10), x, now + 10))
    }

    @Test
    fun aLaterEditBringsBackARemovedCard() {
        val removed = Board(emptyList(), mapOf("a" to now + 5))
        val edited = Board(listOf(card("a", "Still to do", 0, 8, 1)))
        assertEquals(listOf("a"), TaskBoard.merge(removed, edited, now + 10).cards.map { it.id })
        val older = Board(listOf(card("a", "Old", 0, 3)))
        assertTrue(TaskBoard.merge(removed, older, now + 10).cards.isEmpty())
    }

    @Test
    fun removalsAreForgottenAfterSixtyDays() {
        val removed = Board(emptyList(), mapOf("a" to now))
        assertEquals(mapOf("a" to now), TaskBoard.merge(removed, Board(), now + 1000).removed)
        assertTrue(TaskBoard.merge(removed, Board(), now + TaskBoard.KEEP_REMOVED + 1).removed.isEmpty())
    }

    @Test
    fun putWhereItIsDroppedInItsColumnOrAnother() {
        var board = Board(listOf(card("a", "A", 0, 1), card("b", "B", 0, 2), card("c", "C", 0, 3), card("d", "D", 1, 4)))
        board = TaskBoard.place(board, "c", 0, 0, now + 10)
        assertEquals(listOf("c", "a", "b", "d"), board.cards.map { it.id })
        board = TaskBoard.place(board, "a", 0, 2, now + 11)
        assertEquals(listOf("c", "b", "a", "d"), board.cards.map { it.id })
        board = TaskBoard.place(board, "d", 0, 1, now + 12)
        assertEquals(listOf("c", "d", "b", "a"), board.cards.map { it.id })
        assertSame(board, TaskBoard.place(board, "d", 0, 1, now + 13))
        assertEquals(now + 12, board.cards.first { it.id == "d" }.placeStamp)
    }

    @Test
    fun anOrderKeptByTheOtherSideAndByOneFromBeforeRanks() {
        val desktop = TaskBoard.place(Board(listOf(card("a", "A", 0, 1), card("b", "B", 0, 2))), "b", 0, 0, now + 5)
        val older = Board(desktop.cards.map { it.copy(rank = null) })
        assertEquals(listOf("b", "a"), TaskBoard.merge(desktop, older, now + 9).cards.map { it.id })
        assertEquals(TaskBoard.merge(desktop, older, now + 9), TaskBoard.merge(older, desktop, now + 9))
        val moved = Board(listOf(card("a", "A", 1, 1, 7), card("b", "B", 0, 2, 5)))
        assertEquals(listOf("b" to 0, "a" to 1), TaskBoard.merge(desktop, moved, now + 9).cards.map { it.id to it.column })
    }

    @Test
    fun noRoomBetweenTwoRanksTheColumnAgain() {
        val tight = Board(listOf(card("a", "A", 0, 1).copy(rank = 1.0), card("b", "B", 0, 2).copy(rank = 1.0 + Math.ulp(1.0)), card("c", "C", 0, 3)))
        val placed = TaskBoard.place(tight, "c", 0, 1, now + 10)
        assertEquals(listOf("a", "c", "b"), placed.cards.map { it.id })
        assertEquals(listOf(now + 10.0, now + 11.0, now + 12.0), placed.cards.map { it.rank })
        assertTrue(placed.cards.all { it.placeStamp == now + 10 })
        assertEquals((now + 3).toDouble(), TaskBoard.rankAt(emptyList(), 0, now + 3))
    }

    @Test
    fun inTheOrderTheDesktopShows() {
        val board = Board(listOf(card("x", "Later", 0, 9), card("y", "Done", 2, 1), card("z", "Sooner", 0, 2)))
        assertEquals(listOf("z", "x", "y"), TaskBoard.merge(board, Board(), now + 10).cards.map { it.id })
        assertEquals(now + 9, TaskBoard.latest(board))
    }
}
