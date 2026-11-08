package com.nihilshell.launcher.core

import com.nihilshell.launcher.core.TaskBoard.Board
import com.nihilshell.launcher.core.TaskBoard.Card
import org.junit.Assert.assertEquals
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
    fun inTheOrderTheDesktopShows() {
        val board = Board(listOf(card("x", "Later", 0, 9), card("y", "Done", 2, 1), card("z", "Sooner", 0, 2)))
        assertEquals(listOf("z", "x", "y"), TaskBoard.merge(board, Board(), now + 10).cards.map { it.id })
        assertEquals(now + 9, TaskBoard.latest(board))
    }
}
