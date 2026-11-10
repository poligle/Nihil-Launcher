package com.nihilshell.launcher.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.nihilshell.launcher.R
import com.nihilshell.launcher.core.Action
import com.nihilshell.launcher.core.AppEntry
import com.nihilshell.launcher.core.Entry
import com.nihilshell.launcher.core.Calculator
import com.nihilshell.launcher.core.IconStyle
import com.nihilshell.launcher.core.Search

/**
 * The drawer: the frame grown over the whole screen, laid out as the
 * desktop's launcher (home/shell/drawers/Launcher.qml). The apps in a grid,
 * monochrome, the first result raised and in colour; the field at the
 * bottom, by the thumb and the keyboard, with how many apps it holds; and
 * under it the keys: "> " lists the actions, "= " is the calculator, enter
 * opens the first result.
 */
class DrawerView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun launch(entry: Entry, from: View)
        fun menu(entry: Entry, from: View)
        fun run(action: Action)
        fun copy(text: String)
        fun webSearch(query: String)
        fun openSettings()
        fun openDeck()
        fun openTasks()
        fun hideKeyboard()
        fun showKeyboard(view: View)
    }

    /** What the list shows: a row of app tiles, or one result across. */
    private sealed class Item {
        class Tiles(val entries: List<Entry>, val first: Int) : Item()
        class Single(val row: Search.Row, val index: Int) : Item()
    }

    private val fonts = Nihil.fonts(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val list = ListView(context)
    private val field = LinearLayout(context)
    private val search = EditText(context)
    private val count = context.mono(size = 11f, color = Nihil.MUTED)
    private val clear = ImageView(context)
    private val adapter = Rows()

    private var apps: List<Entry> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var actions: List<Action> = emptyList()
    private var style = IconStyle.MONO
    private var rows: List<Search.Row> = emptyList()
    private var items: List<Item> = emptyList()
    private var columns = 4
    private var topInset = 0
    private var bottomInset = 0

    init {
        clipChildren = true

        // Results, from the top.
        list.apply {
            adapter = this@DrawerView.adapter
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(context.dp(12), context.dp(14), context.dp(12), context.dp(10))
            setOnItemClickListener { _, view, position, _ ->
                val item = items.getOrNull(position) as? Item.Single ?: return@setOnItemClickListener
                activate(item.index, view)
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) host.hideKeyboard()
                }

                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) = Unit
            })
        }
        column.addView(list, llp(MATCH, 0, 1f))

        // The field: the surface with a hairline, the glass, the query, how many.
        field.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(10), 0)
            background = rounded(Nihil.SURFACE, context.dp(12f), context.dp(1), Nihil.LINE)
        }
        val glass = ImageView(context).apply { setImageDrawable(LineIcon(LineIcon.Shape.SEARCH, Nihil.MUTED)) }
        field.addView(glass, llp(context.dp(18), context.dp(18)).apply { marginEnd = context.dp(12) })
        search.apply {
            typeface = fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Nihil.INK)
            setHintTextColor(Nihil.MUTED)
            hint = context.getString(R.string.search_hint)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            textCursorDrawable = rounded(Nihil.INK, 0f).apply { setSize(context.dp(2), 0) }
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) activate(0, null)
                enter
            }
            setOnFocusChangeListener { _, focused ->
                field.background = rounded(Nihil.SURFACE, context.dp(12f), context.dp(1), if (focused) Nihil.MUTED else Nihil.LINE)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        field.addView(search, llp(0, MATCH, 1f))
        field.addView(count, llp(WRAP, WRAP).apply { marginStart = context.dp(10) })
        clear.apply {
            setImageDrawable(LineIcon(LineIcon.Shape.CLOSE, Nihil.DIM))
            pad(context.dp(8))
            background = ripple(context.dp(16f))
            visibility = View.GONE
            contentDescription = context.getString(android.R.string.cancel)
            setOnClickListener { search.setText("") }
        }
        field.addView(clear, llp(context.dp(32), context.dp(32)).apply { marginStart = context.dp(4) })
        column.addView(field, llp(MATCH, context.dp(52)).apply {
            leftMargin = context.dp(20)
            rightMargin = context.dp(20)
        })

        // The keys under it, as the desktop's: tapping one starts that mode.
        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keys.addView(hint(">", R.string.keys_actions) { start("> ") })
        keys.addView(hint("=", R.string.keys_calc) { start("= ") }, llp(WRAP, WRAP).apply { marginStart = context.dp(18) })
        keys.addView(View(context), llp(0, 1, 1f))
        val deck = ImageView(context).apply {
            setImageDrawable(LineIcon(LineIcon.Shape.LAPTOP, Nihil.DIM))
            pad(context.dp(8))
            val r = context.dp(17f)
            background = ripple(r, rounded(Nihil.SURFACE, r, context.dp(1), Nihil.LINE))
            contentDescription = context.getString(R.string.deck_title)
            setOnClickListener { host.openDeck() }
        }
        val tasks = ImageView(context).apply {
            setImageDrawable(LineIcon(LineIcon.Shape.TASKS, Nihil.DIM))
            pad(context.dp(8))
            val r = context.dp(17f)
            background = ripple(r, rounded(Nihil.SURFACE, r, context.dp(1), Nihil.LINE))
            contentDescription = context.getString(R.string.tasks_title)
            setOnClickListener { host.openTasks() }
        }
        keys.addView(tasks, llp(context.dp(34), context.dp(34)).apply { marginEnd = context.dp(10) })
        keys.addView(deck, llp(context.dp(34), context.dp(34)).apply { marginEnd = context.dp(10) })
        val settings = ImageView(context).apply {
            setImageDrawable(LineIcon(LineIcon.Shape.SETTINGS, Nihil.DIM))
            pad(context.dp(8))
            val r = context.dp(17f)
            background = ripple(r, rounded(Nihil.SURFACE, r, context.dp(1), Nihil.LINE))
            contentDescription = context.getString(R.string.act_nihil)
            setOnClickListener { host.openSettings() }
        }
        keys.addView(settings, llp(context.dp(34), context.dp(34)))
        column.addView(keys, llp(MATCH, WRAP).apply {
            leftMargin = context.dp(22)
            rightMargin = context.dp(20)
            topMargin = context.dp(10)
            bottomMargin = context.dp(12)
        })

        addView(column, lp(MATCH, MATCH))
        refresh()
    }

    /** A key and what it does, the whole of it tappable. */
    private fun hint(key: String, word: Int, onTap: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(6), context.dp(6), context.dp(6))
        background = ripple(context.dp(8f))
        addView(context.keycap(key))
        addView(context.text(context.getString(word), 13f, Nihil.MUTED), llp(WRAP, WRAP).apply { marginStart = context.dp(6) })
        setOnClickListener { onTap() }
    }

    private fun start(prefix: String) {
        if (!query.startsWith(prefix.trim())) search.setText(prefix)
        search.setSelection(search.text.length)
        search.requestFocus()
        host.showKeyboard(search)
    }

    /** Status bar on top; gesture bar or keyboard at the bottom. */
    fun setInsets(top: Int, bottom: Int) {
        if (top == topInset && bottom == bottomInset) return
        topInset = top
        bottomInset = bottom
        column.setPadding(0, top, 0, bottom)
    }

    fun setContent(apps: List<Entry>, hidden: Set<String>, actions: List<Action>, style: IconStyle) {
        this.apps = apps
        this.hidden = hidden
        this.actions = actions
        this.style = style
        refresh()
    }

    /** Called when the drawer has finished opening. */
    fun opened(keyboard: Boolean) {
        if (keyboard) {
            search.requestFocus()
            host.showKeyboard(search)
        }
    }

    /** Called when the drawer has closed: back to an empty field at the top of the list. */
    fun closed() {
        search.clearFocus()
        if (search.text.isNotEmpty()) search.setText("")
        list.setSelection(0)
    }

    /**
     * True when a downward drag starting at [y] (in this view) should close the
     * drawer rather than scroll: with the list at its top.
     */
    fun canCloseFrom(y: Float): Boolean = y > column.top + field.top || !list.canScrollVertically(-1)

    val query: String get() = search.text.toString()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Four across on a phone, more where there is room, as wide as the desktop's cells.
        val wanted = ((w - context.dp(24)) / context.dp(92)).coerceAtLeast(4)
        if (wanted != columns) {
            columns = wanted
            refresh()
        }
    }

    private fun refresh() {
        val q = query
        clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        val result = Search.run(q, apps, hidden, actions)
        rows = result.rows
        count.text = when (result.mode) {
            Search.Mode.APPS -> context.getString(R.string.apps_count, apps.count { it is AppEntry && it.key !in hidden })
            Search.Mode.ACTIONS -> context.getString(R.string.mode_actions)
            Search.Mode.CALCULATOR -> context.getString(R.string.mode_calculator)
            Search.Mode.SEARCH -> context.resources.getQuantityString(R.plurals.results, result.matches, result.matches)
        }
        items = group(rows)
        adapter.notifyDataSetChanged()
        if (q.isNotEmpty()) list.setSelection(0)
    }

    /** Consecutive apps in rows of [columns] tiles; everything else across. The letters go: the grid is in order. */
    private fun group(rows: List<Search.Row>): List<Item> {
        val out = ArrayList<Item>()
        var run = ArrayList<Entry>()
        var runStart = 0
        fun flush() {
            run.chunked(columns).forEachIndexed { i, chunk -> out += Item.Tiles(chunk, runStart + i * columns) }
            run = ArrayList()
        }
        rows.forEachIndexed { index, row ->
            when (row) {
                is Search.Row.App -> {
                    if (run.isEmpty()) runStart = index
                    run += row.entry
                }
                is Search.Row.Letter -> Unit
                else -> {
                    flush()
                    out += Item.Single(row, index)
                }
            }
        }
        flush()
        return out
    }

    private fun activate(index: Int, view: View?) {
        val row = rows.getOrNull(index) ?: return
        when (row) {
            is Search.Row.App -> host.launch(row.entry, view ?: firstTile() ?: list)
            is Search.Row.Act -> host.run(row.action)
            is Search.Row.Calc -> host.copy(row.text)
            is Search.Row.Web -> host.webSearch(row.query)
            is Search.Row.Letter, is Search.Row.Message -> Unit
        }
    }

    private fun firstTile(): View? = ((list.getChildAt(0) as? ViewGroup)?.getChildAt(0)).takeIf { list.firstVisiblePosition == 0 }

    // --- Rows ----------------------------------------------------------------

    private inner class Rows : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun hasStableIds() = false

        override fun getItemViewType(position: Int) = when (val item = items[position]) {
            is Item.Tiles -> 0
            is Item.Single -> when (item.row) {
                is Search.Row.Calc -> 2
                is Search.Row.Message -> 3
                else -> 1
            }
        }

        override fun isEnabled(position: Int) = when (val item = items[position]) {
            is Item.Tiles -> false
            is Item.Single -> item.row !is Search.Row.Message
        }

        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val selectedFirst = query.isNotBlank()
            return when (val item = items[position]) {
                is Item.Tiles -> (convertView as? TileRow ?: TileRow(parent.context)).bind(item, selectedFirst)
                is Item.Single -> {
                    val first = selectedFirst && item.index == 0
                    when (val row = item.row) {
                        is Search.Row.Act -> line(convertView, parent).bindAction(row.action, first)
                        is Search.Row.Web -> line(convertView, parent).bindWeb(row.query, first)
                        is Search.Row.App -> line(convertView, parent).bindApp(row.entry, first)
                        is Search.Row.Calc -> calc(convertView, row)
                        is Search.Row.Message -> message(convertView, row.text)
                        is Search.Row.Letter -> message(convertView, 0)
                    }
                }
            }
        }

        private fun line(convertView: View?, parent: ViewGroup): LineView =
            convertView as? LineView ?: LineView(parent.context)

        private fun calc(convertView: View?, row: Search.Row.Calc): View {
            val view = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(16))
                val r = context.dp(18f)
                background = ripple(r, rounded(Nihil.RAISED, r, context.dp(1), Nihil.LINE))
                addView(context.text("", 44f, Nihil.INK, fonts.dot).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.START
                })
                addView(context.mono(size = 11f, color = Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = context.dp(8) })
            }
            (view.getChildAt(0) as TextView).text = row.text
            (view.getChildAt(1) as TextView).text = "= ${row.expression.trim()}"
            return view
        }

        private fun message(convertView: View?, text: Int): View {
            val view = convertView as? TextView ?: context.mono(size = 11f, color = Nihil.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(16), context.dp(28), context.dp(16), context.dp(28))
            }
            view.text = if (text != 0) context.getString(text) else ""
            return view
        }
    }

    /** A row of the grid: [columns] cells, an app in each, the rest empty. */
    private inner class TileRow(context: Context) : LinearLayout(context) {
        private val cells = ArrayList<Tile>()

        init {
            orientation = HORIZONTAL
            layoutParams = AbsListView.LayoutParams(MATCH, context.dp(108))
        }

        fun bind(item: Item.Tiles, selectedFirst: Boolean): TileRow {
            while (cells.size < columns) {
                val cell = Tile(context)
                cells += cell
                addView(cell, llp(0, MATCH, 1f))
            }
            while (cells.size > columns) removeView(cells.removeAt(cells.size - 1))
            cells.forEachIndexed { i, cell ->
                val entry = item.entries.getOrNull(i)
                if (entry == null) {
                    cell.empty()
                } else {
                    cell.bind(entry, selected = selectedFirst && item.first + i == 0)
                }
            }
            return this
        }
    }

    /** An app as the desktop's cell: the icon over its name; the selected one raised, in colour. */
    private inner class Tile(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val name = context.mono(size = 11f, color = Nihil.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val iconSize = context.dp(46)
        private val plain = ripple(context.dp(18f))
        private val raised = context.dp(18f).let { ripple(it, rounded(Nihil.RAISED, it, context.dp(1), Nihil.LINE)) }
        private var key: String? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(4), context.dp(14), context.dp(4), 0)
            addView(icon, llp(iconSize, iconSize))
            addView(name, llp(MATCH, WRAP).apply { topMargin = context.dp(10) })
        }

        fun empty() {
            key = null
            visibility = View.INVISIBLE
            setOnClickListener(null)
            setOnLongClickListener(null)
        }

        fun bind(entry: Entry, selected: Boolean) {
            visibility = View.VISIBLE
            val wanted = if (selected) raised else plain
            if (background !== wanted) background = wanted
            name.text = if (entry.key in hidden) "${entry.label} (${context.getString(R.string.hint_hidden).lowercase()})" else entry.label
            name.setTextColor(if (selected) Nihil.INK else Nihil.MUTED)
            contentDescription = entry.label
            key = entry.key
            icon.bindIcon(entry, iconSize, if (selected) IconStyle.COLOR else style)
            setOnClickListener { host.launch(entry, this) }
            setOnLongClickListener {
                host.menu(entry, this)
                true
            }
        }
    }

    /** An action, "search the web" or an app across: as the desktop's actions, a glyph, the name, a hint. */
    private inner class LineView(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val label = context.text("", 15f, Nihil.DIM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val hint = context.mono(size = 11f, color = Nihil.MUTED)
        private val iconSize = context.dp(22)
        private val plain = ripple(context.dp(14f))
        private val raised = context.dp(14f).let { ripple(it, rounded(Nihil.RAISED, it)) }
        private var key: String? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(16), 0)
            addView(icon, llp(iconSize, iconSize).apply { marginEnd = context.dp(14) })
            addView(label, llp(0, WRAP, 1f))
            addView(hint, llp(WRAP, WRAP).apply { marginStart = context.dp(10) })
            layoutParams = AbsListView.LayoutParams(MATCH, context.dp(52))
        }

        private fun frame(first: Boolean) {
            val wanted = if (first) raised else plain
            if (background !== wanted) background = wanted
            label.setTextColor(if (first) Nihil.INK else Nihil.DIM)
        }

        fun bindApp(entry: Entry, first: Boolean): LineView {
            frame(first)
            label.text = entry.label
            hint.text = if (entry.key in hidden) context.getString(R.string.hint_hidden) else ""
            hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
            key = entry.key
            icon.setPadding(0, 0, 0, 0)
            icon.bindIcon(entry, iconSize, style)
            return this
        }

        fun bindAction(action: Action, first: Boolean): LineView {
            frame(first)
            key = null
            label.text = action.label
            hint.text = action.hint()
            icon.tag = null
            hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
            icon.setImageDrawable(LineIcon(action.icon, if (first) Nihil.INK else Nihil.DIM))
            icon.setPadding(context.dp(1), context.dp(1), context.dp(1), context.dp(1))
            return this
        }

        fun bindWeb(query: String, first: Boolean): LineView {
            frame(first)
            key = null
            label.text = context.getString(R.string.web_search, query.trim())
            icon.tag = null
            hint.visibility = View.GONE
            icon.setImageDrawable(LineIcon(LineIcon.Shape.WEB, if (first) Nihil.INK else Nihil.DIM))
            icon.setPadding(context.dp(1), context.dp(1), context.dp(1), context.dp(1))
            return this
        }
    }
}
