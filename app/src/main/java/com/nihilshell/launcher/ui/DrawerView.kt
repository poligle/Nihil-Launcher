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
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.Calculator
import com.nihilshell.launcher.core.IconStyle
import com.nihilshell.launcher.core.Search

/**
 * The drawer: the frame grown over the whole screen. One field searches
 * apps and actions and does sums; "> " lists the actions, "= " is the
 * calculator, enter runs the first result. With no query, every app,
 * alphabetically, under dot-matrix letters.
 */
class DrawerView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun launch(entry: AppEntry, from: View)
        fun menu(entry: AppEntry, from: View)
        fun run(action: Action)
        fun copy(text: String)
        fun webSearch(query: String)
        fun openSettings()
        fun hideKeyboard()
        fun showKeyboard(view: View)
    }

    private val fonts = Nihil.fonts(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val mode = context.mono(size = 11f, color = Nihil.MUTED)
    private val search = EditText(context)
    private val clear = ImageView(context)
    private val list = ListView(context)
    private val footer = context.mono(size = 10f, color = Nihil.MUTED)
    private val adapter = Rows()

    private var apps: List<AppEntry> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var actions: List<Action> = emptyList()
    private var style = IconStyle.MONO
    private var rows: List<Search.Row> = emptyList()
    private var topInset = 0
    private var bottomInset = 0

    init {
        clipChildren = true

        // Header: what the field is doing, and the way to NIHIL's settings.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(mode, llp(0, WRAP, 1f))
        val settings = ImageView(context).apply {
            setImageDrawable(LineIcon(LineIcon.Shape.SETTINGS, Nihil.DIM))
            pad(context.dp(9))
            background = ripple(context.dp(18f))
            contentDescription = context.getString(R.string.act_nihil)
            setOnClickListener { host.openSettings() }
        }
        header.addView(settings, llp(context.dp(38), context.dp(38)))
        column.addView(header, llp(MATCH, WRAP).apply {
            leftMargin = context.dp(24)
            rightMargin = context.dp(12)
            topMargin = context.dp(6)
        })

        // The field.
        val field = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glass = ImageView(context).apply { setImageDrawable(LineIcon(LineIcon.Shape.SEARCH, Nihil.DIM)) }
        field.addView(glass, llp(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(12) })
        search.apply {
            typeface = fonts.sans
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Nihil.INK)
            setHintTextColor(Nihil.MUTED)
            hint = context.getString(R.string.search_hint)
            background = null
            setPadding(0, context.dp(10), 0, context.dp(10))
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
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        field.addView(search, llp(0, WRAP, 1f))
        clear.apply {
            setImageDrawable(LineIcon(LineIcon.Shape.CLOSE, Nihil.DIM))
            pad(context.dp(8))
            background = ripple(context.dp(16f))
            visibility = View.GONE
            setOnClickListener { search.setText("") }
        }
        field.addView(clear, llp(context.dp(34), context.dp(34)))
        column.addView(field, llp(MATCH, WRAP).apply {
            leftMargin = context.dp(24)
            rightMargin = context.dp(14)
            topMargin = context.dp(4)
        })
        column.addView(context.rule(), llp(MATCH, context.dp(1)).apply {
            leftMargin = context.dp(24)
            rightMargin = context.dp(24)
            bottomMargin = context.dp(6)
        })

        // Results.
        list.apply {
            adapter = this@DrawerView.adapter
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(context.dp(12), 0, context.dp(12), context.dp(8))
            setOnItemClickListener { _, view, position, _ -> activate(position, view) }
            setOnItemLongClickListener { _, view, position, _ ->
                val row = rows.getOrNull(position) as? Search.Row.App ?: return@setOnItemLongClickListener false
                host.menu(row.entry, view)
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) host.hideKeyboard()
                }

                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) = Unit
            })
        }
        column.addView(list, llp(MATCH, 0, 1f))

        footer.gravity = Gravity.CENTER
        column.addView(footer, llp(MATCH, WRAP).apply {
            topMargin = context.dp(6)
            bottomMargin = context.dp(10)
        })

        addView(column, lp(MATCH, MATCH))
        refresh()
    }

    /** Status bar on top; gesture bar or keyboard at the bottom. */
    fun setInsets(top: Int, bottom: Int) {
        if (top == topInset && bottom == bottomInset) return
        topInset = top
        bottomInset = bottom
        column.setPadding(0, top, 0, bottom)
    }

    fun setContent(apps: List<AppEntry>, hidden: Set<String>, actions: List<Action>, style: IconStyle) {
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
     * drawer rather than scroll: above the list, or with the list at its top.
     */
    fun canCloseFrom(y: Float): Boolean = y < column.top + list.top || !list.canScrollVertically(-1)

    val query: String get() = search.text.toString()

    private fun refresh() {
        val q = query
        clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        val result = Search.run(q, apps, hidden, actions)
        rows = result.rows
        mode.text = when (result.mode) {
            Search.Mode.APPS -> context.getString(R.string.apps_count, apps.count { it.key !in hidden })
            Search.Mode.ACTIONS -> context.getString(R.string.mode_actions)
            Search.Mode.CALCULATOR -> context.getString(R.string.mode_calculator)
            Search.Mode.SEARCH -> context.resources.getQuantityString(R.plurals.results, result.matches, result.matches)
        }
        footer.text = context.getString(
            if (result.mode == Search.Mode.CALCULATOR || rows.firstOrNull() is Search.Row.Calc) R.string.calc_hint else R.string.drawer_hint,
        )
        adapter.notifyDataSetChanged()
        if (q.isNotEmpty()) list.setSelection(0)
    }

    private fun activate(position: Int, view: View?) {
        val row = rows.getOrNull(position) ?: return
        when (row) {
            is Search.Row.App -> host.launch(row.entry, view ?: list.getChildAt(position - list.firstVisiblePosition) ?: list)
            is Search.Row.Act -> host.run(row.action)
            is Search.Row.Calc -> host.copy(Calculator.format(row.value))
            is Search.Row.Web -> host.webSearch(row.query)
            is Search.Row.Letter, is Search.Row.Message -> Unit
        }
    }

    // --- Rows ----------------------------------------------------------------

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun hasStableIds() = false

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Search.Row.App, is Search.Row.Act, is Search.Row.Web -> 0
            is Search.Row.Calc -> 1
            is Search.Row.Letter -> 2
            is Search.Row.Message -> 3
        }

        override fun isEnabled(position: Int) = when (rows[position]) {
            is Search.Row.Letter, is Search.Row.Message -> false
            else -> true
        }

        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val first = position == 0 && query.isNotBlank()
            return when (val row = rows[position]) {
                is Search.Row.App -> item(convertView, parent).bindApp(row.entry, first)
                is Search.Row.Act -> item(convertView, parent).bindAction(row.action, first)
                is Search.Row.Web -> item(convertView, parent).bindWeb(row.query, first)
                is Search.Row.Calc -> calc(convertView, row)
                is Search.Row.Letter -> letter(convertView, row.letter)
                is Search.Row.Message -> message(convertView, row.text)
            }
        }

        private fun item(convertView: View?, parent: ViewGroup): ItemView =
            convertView as? ItemView ?: ItemView(parent.context)

        private fun calc(convertView: View?, row: Search.Row.Calc): View {
            val view = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(16))
                background = ripple(context.dp(18f), rounded(Nihil.RAISED, context.dp(18f)))
                addView(context.text("", 44f, Nihil.INK, fonts.dot).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.START
                })
                addView(context.mono(size = 11f, color = Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = context.dp(8) })
            }
            (view.getChildAt(0) as TextView).text = Calculator.format(row.value)
            (view.getChildAt(1) as TextView).text = "= ${row.expression.trim()}"
            return view
        }

        private fun letter(convertView: View?, letter: String): View {
            val view = convertView as? TextView ?: context.text("", 15f, Nihil.MUTED, fonts.dot).apply {
                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(6))
            }
            view.text = letter
            return view
        }

        private fun message(convertView: View?, text: Int): View {
            val view = convertView as? TextView ?: context.mono(size = 11f, color = Nihil.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(16), context.dp(28), context.dp(16), context.dp(28))
            }
            view.text = context.getString(text)
            return view
        }
    }

    /** An app, an action or "search the web": icon, name, a hint, and ↵ on the first one. */
    private inner class ItemView(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val label = context.text("", 18f, Nihil.INK, fonts.sans).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val hint = context.mono(size = 10f, color = Nihil.MUTED)
        private val enter = ImageView(context).apply { setImageDrawable(LineIcon(LineIcon.Shape.ENTER, Nihil.MUTED)) }
        private val iconSize = context.dp(26)
        private var key: String? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(56)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            addView(icon, llp(iconSize, iconSize).apply { marginEnd = context.dp(16) })
            addView(label, llp(0, WRAP, 1f))
            addView(hint, llp(WRAP, WRAP).apply { marginStart = context.dp(10) })
            addView(enter, llp(context.dp(16), context.dp(16)).apply { marginStart = context.dp(10) })
            layoutParams = AbsListView.LayoutParams(MATCH, context.dp(56))
        }

        private fun frame(first: Boolean) {
            val r = context.dp(18f)
            background = ripple(r, if (first) rounded(Nihil.RAISED, r) else null)
            enter.visibility = if (first) View.VISIBLE else View.GONE
        }

        fun bindApp(entry: AppEntry, first: Boolean): ItemView {
            frame(first)
            label.text = entry.label
            val isHidden = entry.key in hidden
            hint.text = if (isHidden) context.getString(R.string.hint_hidden) else ""
            hint.visibility = if (isHidden) View.VISIBLE else View.GONE
            key = entry.key
            icon.setPadding(0, 0, 0, 0)
            icon.setImageDrawable(null)
            val cached = Apps.icon(entry, iconSize, style) { bitmap -> if (key == entry.key) icon.setImageBitmap(bitmap) }
            if (cached != null) icon.setImageBitmap(cached)
            return this
        }

        fun bindAction(action: Action, first: Boolean): ItemView {
            frame(first)
            key = null
            label.text = action.label
            hint.text = action.hint()
            hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
            icon.setImageDrawable(LineIcon(action.icon, Nihil.INK))
            icon.setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            return this
        }

        fun bindWeb(query: String, first: Boolean): ItemView {
            frame(first)
            key = null
            label.text = context.getString(R.string.web_search, query.trim())
            hint.visibility = View.GONE
            icon.setImageDrawable(LineIcon(LineIcon.Shape.WEB, Nihil.INK))
            icon.setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            return this
        }
    }
}
