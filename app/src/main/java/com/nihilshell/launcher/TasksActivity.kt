package com.nihilshell.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nihilshell.launcher.core.TaskBoard
import com.nihilshell.launcher.deck.DeckClient
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.deck.TasksStore
import com.nihilshell.launcher.ui.AppMenu
import com.nihilshell.launcher.ui.Choice
import com.nihilshell.launcher.ui.KeyButton
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.ReorderList
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.llp
import com.nihilshell.launcher.ui.lp
import com.nihilshell.launcher.ui.mono
import com.nihilshell.launcher.ui.pad
import com.nihilshell.launcher.ui.rounded
import com.nihilshell.launcher.ui.ripple
import com.nihilshell.launcher.ui.text
import java.util.concurrent.Executors

/**
 * The desktop's kanban on the phone: to do, doing and done, the same cards. Every
 * change is kept here at once and sent to the computer when it answers; away from
 * it, the phone goes on, and the next sync merges what each side did (TaskBoard).
 */
class TasksActivity : Activity() {
    private lateinit var tasks: TasksStore
    private lateinit var status: TextView
    private lateinit var tabs: Choice
    private lateinit var list: LinearLayout
    private lateinit var field: EditText
    private val popup by lazy { AppMenu(this) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val syncSoon = Runnable { sync() }
    private var column = 0
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tasks = TasksStore.get(this)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        val fonts = Nihil.fonts(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(getString(R.string.tasks_title), 22f, Nihil.INK), llp(MATCH, WRAP))
        status = mono("", 11f, Nihil.MUTED)
        column.addView(status, llp(MATCH, WRAP).apply { topMargin = dp(6) })
        tabs = Choice(this, titles(), 0) { show(it) }
        column.addView(tabs, llp(MATCH, WRAP).apply { topMargin = dp(18) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, llp(MATCH, WRAP))
        }
        column.addView(scroll, llp(MATCH, 0, 1f).apply { topMargin = dp(12) })

        // The desktop's "+ New task": the field at the bottom, by the thumb.
        field = EditText(this).apply {
            typeface = fonts.sans
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Nihil.INK)
            setHintTextColor(Nihil.MUTED)
            hint = "+ " + getString(R.string.tasks_new)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
            setOnEditorActionListener { _, _, _ ->
                val typed = text.toString()
                if (typed.isNotBlank()) {
                    tasks.add(typed)
                    setText("")
                    changed()
                }
                true
            }
        }
        column.addView(field, llp(MATCH, dp(50)).apply { topMargin = dp(10) })

        val root = FrameLayout(this).apply { setBackgroundColor(Nihil.VOID) }
        root.addView(column, lp(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(24), bars.top + dp(24), bars.right + dp(24), bars.bottom + dp(16))
            insets
        }
        setContentView(root)
        show(0)
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    override fun onDestroy() {
        main.removeCallbacks(syncSoon)
        worker.shutdown()
        super.onDestroy()
    }

    private fun titles(): List<String> {
        val names = listOf(R.string.tasks_todo, R.string.tasks_doing, R.string.tasks_done)
        return names.mapIndexed { i, name -> getString(name) + " (" + tasks.board.cards.count { it.column == i } + ")" }
    }

    private fun show(index: Int) {
        column = index
        list.removeAllViews()
        val cards = tasks.board.cards.filter { it.column == index }
        if (cards.isEmpty()) {
            list.addView(text(getString(R.string.tasks_empty), 14f, Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = dp(16) })
        }
        // Held and moved, a card goes where it is let go, and so it is on the desktop.
        val rows = ReorderList(this, dp(8))
        rows.onReorder = { from, to ->
            tasks.place(cards[from].id, index, to)
            changed()
        }
        for (card in cards) rows.addView(row(card, rows))
        list.addView(rows, llp(MATCH, WRAP))
        if (index == TaskBoard.COLUMNS - 1 && cards.isNotEmpty()) {
            list.addView(KeyButton(this, getString(R.string.tasks_clear_done)).apply {
                setOnClickListener {
                    tasks.remove(cards.map { it.id })
                    changed()
                }
            }, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        }
    }

    /**
     * A card as the desktop's: its dot (hollow to do, lit doing, dim done), its text and the
     * pencil. Tapped, its menu; held and moved, another place in its column.
     */
    private fun row(card: TaskBoard.Card, rows: ReorderList): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(14), dp(6), dp(6), dp(6))
        val r = dp(10f)
        background = ripple(r, rounded(Nihil.VOID, r, dp(1), Nihil.LINE))
        val dot = View(this@TasksActivity).apply {
            background = when (card.column) {
                0 -> rounded(0, dp(4f), dp(1), Nihil.DIM)
                1 -> rounded(Nihil.INK, dp(4f))
                else -> rounded(Nihil.MUTED, dp(4f))
            }
        }
        addView(dot, llp(dp(8), dp(8)).apply { marginEnd = dp(12) })
        addView(text(card.text, 15f, if (card.column == 2) Nihil.MUTED else Nihil.INK).apply {
            setPadding(0, dp(4), 0, dp(4))
        }, llp(0, WRAP, 1f))
        addView(ImageView(this@TasksActivity).apply {
            setImageDrawable(LineIcon(LineIcon.Shape.PENCIL, Nihil.MUTED))
            pad(dp(9))
            background = ripple(dp(17f))
            contentDescription = getString(R.string.tasks_edit)
            setOnClickListener { edit(card) }
        }, llp(dp(36), dp(36)).apply { marginStart = dp(6) })
        setOnClickListener { menu(card, this) }
        rows.movable(this)
        setOnLongClickListener {
            rows.arm(it)
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
    }

    private fun menu(card: TaskBoard.Card, from: View) {
        val names = listOf(R.string.tasks_todo, R.string.tasks_doing, R.string.tasks_done)
        val items = ArrayList<AppMenu.Item>()
        for (to in 0 until TaskBoard.COLUMNS) {
            if (to == card.column) continue
            items += AppMenu.Item(LineIcon.Shape.TASKS, getString(R.string.tasks_move, getString(names[to]))) {
                tasks.move(card.id, to)
                changed()
            }
        }
        items += AppMenu.Item(LineIcon.Shape.PENCIL, getString(R.string.tasks_edit)) { edit(card) }
        items += AppMenu.Item(LineIcon.Shape.TRASH, getString(R.string.tasks_delete), danger = true) {
            tasks.remove(listOf(card.id))
            changed()
        }
        popup.show(anchor = from, title = card.text, items = items)
    }

    private fun edit(card: TaskBoard.Card) {
        val input = EditText(this).apply {
            setText(card.text)
            setSelection(card.text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val frame = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0) }
        frame.addView(input)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.tasks_edit)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                tasks.edit(card.id, input.text.toString())
                changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shown at once; sent a moment later, so a few changes in a row go together. */
    private fun changed() {
        refresh()
        main.removeCallbacks(syncSoon)
        main.postDelayed(syncSoon, 600)
    }

    private fun refresh() {
        val keep = column
        val choice = Choice(this, titles(), keep) { show(it) }
        val parent = tabs.parent as LinearLayout
        val at = parent.indexOfChild(tabs)
        parent.removeViewAt(at)
        parent.addView(choice, at, tabs.layoutParams)
        tabs = choice
        show(keep)
    }

    private fun sync() {
        val store = DeckStore.get(this)
        val computer = store.computer
        if (computer == null) {
            status.text = getString(R.string.tasks_local)
            return
        }
        if (syncing) return
        syncing = true
        status.text = getString(R.string.tasks_syncing)
        val client = DeckClient(computer, store.token())
        val body = tasks.json()
        worker.execute {
            val reply = client.call("POST", "/v1/tasks", body, this)
            store.save(computer)
            main.post {
                syncing = false
                if (isDestroyed) return@post
                val json = reply?.json
                when {
                    reply == null -> status.text = getString(R.string.tasks_offline, computer.name)
                    reply.status == 401 -> status.text = getString(R.string.deck_forgotten, computer.name)
                    reply.status == 200 && json != null -> {
                        tasks.received(json)
                        status.text = getString(R.string.tasks_synced, computer.name)
                        refresh()
                    }
                    else -> status.text = getString(R.string.tasks_offline, computer.name)
                }
            }
        }
    }
}
