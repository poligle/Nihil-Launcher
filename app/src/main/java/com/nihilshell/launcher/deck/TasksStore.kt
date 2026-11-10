package com.nihilshell.launcher.deck

import android.content.Context
import com.nihilshell.launcher.core.TaskBoard
import com.nihilshell.launcher.core.TaskBoard.Board
import com.nihilshell.launcher.core.TaskBoard.Card
import org.json.JSONArray
import org.json.JSONObject

/**
 * The kanban on this phone: its board, kept in the deck's preferences, and its
 * clock. Every change is stamped at once, paired or not, connected or not; a sync
 * sends the whole board to the computer (POST /v1/tasks), which merges it with its
 * own and answers with the result, merged here again for what changed meanwhile.
 * Stamps are in the computer's time: the phone keeps how far apart the two clocks are.
 */
class TasksStore private constructor(context: Context) {
    private val sp = context.getSharedPreferences(DeckStore.FILE, Context.MODE_PRIVATE)

    var board: Board = read()
        private set

    private var last = sp.getLong(CLOCK, 0L)
    private var offset = sp.getLong(OFFSET, 0L)

    /** Now, in the computer's time, after everything already stamped. */
    private fun stamp(): Long {
        last = maxOf(System.currentTimeMillis() + offset, last + 1)
        return last
    }

    private fun now() = System.currentTimeMillis() + offset

    fun add(text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(TaskBoard.MAX_TEXT)
        if (clean.isEmpty()) return
        val s = stamp()
        val id = java.lang.Long.toString(s, 36) + java.lang.Long.toString((Math.random() * 46656).toLong(), 36)
        val todo = TaskBoard.ordered(board.cards.filter { it.column == 0 })
        change(Board(board.cards + Card(id, clean, 0, s, s, TaskBoard.rankAt(todo, todo.size, s)), board.removed))
    }

    /** To the end of another column. */
    fun move(id: String, column: Int) = place(id, column, Int.MAX_VALUE)

    /** Where it is put: at [index] of [column], counted without it. */
    fun place(id: String, column: Int, index: Int) {
        val next = TaskBoard.place(board, id, column.coerceIn(0, TaskBoard.COLUMNS - 1), index, stamp())
        if (next !== board) change(next)
    }

    fun edit(id: String, text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(TaskBoard.MAX_TEXT)
        if (clean.isEmpty()) return
        val s = stamp()
        change(Board(board.cards.map { if (it.id == id) it.copy(text = clean, textStamp = s) else it }, board.removed))
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val removed = HashMap(board.removed)
        ids.forEach { removed[it] = stamp() }
        change(Board(board.cards.filter { it.id !in ids }, removed))
    }

    private fun change(next: Board) {
        board = TaskBoard.merge(next, Board(), now())
        save()
    }

    /** The computer's answer to a sync: its merged board, and its clock. */
    fun received(reply: JSONObject) {
        val theirs = parse(reply)
        val serverNow = reply.optLong("now", 0L)
        if (serverNow > 0) offset = serverNow - System.currentTimeMillis()
        board = TaskBoard.merge(board, theirs, now())
        last = maxOf(last, TaskBoard.latest(board))
        save()
    }

    fun json(): JSONObject {
        val cards = JSONArray()
        board.cards.forEach {
            val card = JSONObject().put("id", it.id).put("text", it.text).put("column", it.column)
                .put("textStamp", it.textStamp).put("placeStamp", it.placeStamp)
            it.rank?.let { rank -> card.put("rank", rank) }
            cards.put(card)
        }
        val removed = JSONObject()
        board.removed.forEach { (id, at) -> removed.put(id, at) }
        return JSONObject().put("cards", cards).put("removed", removed)
    }

    private fun parse(o: JSONObject): Board {
        val list = o.optJSONArray("cards") ?: JSONArray()
        val cards = (0 until list.length()).mapNotNull { i ->
            val c = list.optJSONObject(i) ?: return@mapNotNull null
            val id = c.optString("id")
            val text = c.optString("text").trim()
            if (id.isEmpty() || text.isEmpty()) return@mapNotNull null
            Card(id, text.take(TaskBoard.MAX_TEXT), c.optInt("column").coerceIn(0, TaskBoard.COLUMNS - 1),
                c.optLong("textStamp"), c.optLong("placeStamp"), if (c.has("rank")) TaskBoard.rank(c.optDouble("rank")) else null)
        }
        val removed = HashMap<String, Long>()
        o.optJSONObject("removed")?.let { r -> r.keys().forEach { removed[it] = r.optLong(it) } }
        return Board(cards, removed)
    }

    private fun read(): Board = runCatching { parse(JSONObject(sp.getString(TASKS, null) ?: return Board())) }.getOrDefault(Board())

    private fun save() {
        sp.edit().putString(TASKS, json().toString()).putLong(CLOCK, last).putLong(OFFSET, offset).apply()
    }

    companion object {
        private const val TASKS = "tasks"
        private const val CLOCK = "tasks_clock"
        private const val OFFSET = "tasks_offset"

        @Volatile
        private var instance: TasksStore? = null

        fun get(context: Context): TasksStore =
            instance ?: synchronized(this) { instance ?: TasksStore(context.applicationContext).also { instance = it } }
    }
}
