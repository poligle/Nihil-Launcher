package com.nihilshell.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import com.nihilshell.launcher.deck.Vector
import com.nihilshell.launcher.deck.VectorAction
import com.nihilshell.launcher.ui.AppMenu
import com.nihilshell.launcher.ui.Choice
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.Notice
import com.nihilshell.launcher.ui.ReorderList
import com.nihilshell.launcher.ui.VectorBox
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

class TasksActivity : Activity() {
    private lateinit var tasks: TasksStore
    private lateinit var status: TextView
    private lateinit var tabs: Choice
    private lateinit var list: LinearLayout
    private lateinit var field: EditText
    private lateinit var notice: Notice
    private lateinit var box: VectorBox
    private lateinit var content: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var vectorTitle: TextView
    private lateinit var vectorDetail: TextView
    private lateinit var emptyKeys: LinearLayout
    private lateinit var itemKeys: LinearLayout
    private lateinit var outgoing: LinearLayout
    private lateinit var outgoingPicture: ImageView
    private lateinit var outgoingTitle: TextView
    private var shown = false
    private var shownId: String? = null
    private val vector: (String) -> Unit = { what -> vectorEvent(what) }
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
        column.addView(vectorBox(), llp(MATCH, WRAP).apply { topMargin = dp(18) })
        tabs = Choice(this, titles(), 0) { show(it) }
        column.addView(tabs, llp(MATCH, WRAP).apply { topMargin = dp(18) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, llp(MATCH, WRAP))
        }
        column.addView(scroll, llp(MATCH, 0, 1f).apply { topMargin = dp(12) })

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

        notice = Notice(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Nihil.VOID) }
        root.addView(column, lp(MATCH, MATCH))
        root.addView(notice, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(24), bars.top + dp(24), bars.right + dp(24), bars.bottom + dp(16))
            (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(84)
            insets
        }
        setContentView(root)
        show(0)
        askToNotify()
    }

    override fun onStart() {
        super.onStart()
        box.visibility = if (DeckStore.get(this).computer != null) View.VISIBLE else View.GONE
        Vector.listen(vector)
        Vector.start(this)
        box.busy = Vector.sending
        showVector(false)
    }

    override fun onResume() {
        super.onResume()
        Vector.watching++
        sync()
    }

    override fun onPause() {
        Vector.watching--
        super.onPause()
    }

    override fun onStop() {
        Vector.unlisten(vector)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(syncSoon)
        worker.shutdown()
        super.onDestroy()
    }

    private fun askToNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || DeckStore.get(this).computer == null) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val asked = getSharedPreferences("vector", MODE_PRIVATE)
        if (asked.getBoolean("asked", false)) return
        asked.edit().putBoolean("asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
    }

    private fun vectorBox(): View {
        box = VectorBox(this).apply {
            isClickable = true
            contentDescription = getString(R.string.vector)
            setOnClickListener { tapVector() }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
        }
        preview = picture()
        row.addView(preview, llp(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(mono(getString(R.string.vector), 11f, Nihil.MUTED), llp(WRAP, WRAP))
        vectorTitle = text("", 14f, Nihil.INK).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(vectorTitle, llp(MATCH, WRAP).apply { topMargin = dp(5) })
        vectorDetail = mono("", 10f, Nihil.MUTED)
        texts.addView(vectorDetail, llp(MATCH, WRAP).apply { topMargin = dp(4) })
        row.addView(texts, llp(0, WRAP, 1f))
        emptyKeys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        emptyKeys.addView(vectorKey(getDrawable(R.drawable.mdi_clipboard), R.string.vector_paste) { send(Vector.clipboard(this)) }, llp(dp(40), dp(40)).apply { marginStart = dp(8) })
        emptyKeys.addView(vectorKey(LineIcon(LineIcon.Shape.PLUS, Nihil.DIM), R.string.vector_pick) { pick() }, llp(dp(40), dp(40)).apply { marginStart = dp(8) })
        row.addView(emptyKeys, llp(WRAP, WRAP))
        content.addView(row, llp(MATCH, WRAP))
        itemKeys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        content.addView(itemKeys, llp(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(content, lp(MATCH, WRAP))

        outgoing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        outgoingPicture = picture()
        outgoing.addView(outgoingPicture, llp(dp(48), dp(48)).apply { marginEnd = dp(14) })
        outgoingTitle = text("", 14f, Nihil.INK).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        outgoing.addView(outgoingTitle, llp(0, WRAP, 1f))
        box.addView(outgoing, lp(MATCH, MATCH))
        return box
    }

    private fun picture(): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8f))
        }
    }

    private fun vectorKey(icon: Drawable?, description: Int, onTap: () -> Unit): ImageView = ImageView(this).apply {
        val r = dp(20f)
        setImageDrawable(icon?.mutate()?.apply { setTint(Nihil.DIM) })
        pad(dp(10))
        background = ripple(r, rounded(Nihil.SURFACE, r, dp(1), Nihil.LINE))
        contentDescription = getString(description)
        setOnClickListener { onTap() }
    }

    private fun actionsOf(kind: String): List<String> = when (kind) {
        "text" -> listOf("copy", "save", "share", "discard")
        "image" -> listOf("copy", "save", "open", "share", "discard")
        else -> listOf("save", "open", "share", "discard")
    }

    private fun showVector(animate: Boolean) {
        val item = Vector.waiting(this)
        if (shown && item?.id == shownId) return
        when {
            !animate || !shown -> render(item)
            item == null -> content.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(260)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    if (isDestroyed) return@withEndAction
                    render(Vector.waiting(this))
                    content.scaleX = 1f
                    content.scaleY = 1f
                    content.animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
                }.start()
            else -> {
                render(item)
                content.scaleX = 0.85f
                content.scaleY = 0.85f
                content.alpha = 0f
                content.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun render(item: Vector.Item?) {
        shown = true
        shownId = item?.id
        preview.setImageDrawable(null)
        preview.setPadding(0, 0, 0, 0)
        preview.visibility = View.VISIBLE
        vectorTitle.visibility = if (item == null) View.GONE else View.VISIBLE
        vectorDetail.visibility = if (item == null || item.kind == "text") View.GONE else View.VISIBLE
        emptyKeys.visibility = if (item == null) View.VISIBLE else View.GONE
        itemKeys.visibility = if (item == null) View.GONE else View.VISIBLE
        itemKeys.removeAllViews()
        if (item == null) {
            preview.setImageDrawable(LineIcon(LineIcon.Shape.SWAP, Nihil.MUTED))
            preview.pad(dp(10))
            return
        }
        vectorDetail.text = Formatter.formatShortFileSize(this, item.size)
        when (item.kind) {
            "text" -> {
                preview.visibility = View.GONE
                vectorTitle.text = item.text(2048).trim()
            }
            "image" -> {
                vectorTitle.text = item.name
                val size = dp(96)
                worker.execute {
                    val picture = Vector.thumbnail(item.file, size)
                    main.post { if (!isDestroyed && shownId == item.id) preview.setImageBitmap(picture) }
                }
            }
            else -> {
                vectorTitle.text = item.name
                preview.setImageDrawable(LineIcon(if (item.kind == "video") LineIcon.Shape.VIDEO else LineIcon.Shape.FILE, Nihil.DIM))
                preview.pad(dp(10))
            }
        }
        for (action in actionsOf(item.kind)) {
            val shape = when (action) {
                "copy" -> LineIcon.Shape.COPY
                "save" -> LineIcon.Shape.SAVE
                "open" -> LineIcon.Shape.OPEN
                "share" -> LineIcon.Shape.SHARE
                else -> LineIcon.Shape.CLOSE
            }
            val label = when (action) {
                "copy" -> R.string.vector_copy
                "save" -> R.string.vector_save
                "open" -> R.string.vector_open
                "share" -> R.string.vector_share
                else -> R.string.vector_discard
            }
            itemKeys.addView(vectorKey(LineIcon(shape, Nihil.DIM), label) { act(action) }, llp(dp(40), dp(40)).apply { marginStart = dp(8) })
        }
    }

    private fun vectorEvent(what: String) {
        if (isDestroyed) return
        when (what) {
            "sending", "receiving" -> box.busy = true
            "missed" -> box.busy = Vector.sending
            "received" -> {
                box.busy = Vector.sending
                showVector(true)
                box.lap()
            }
            "used" -> showVector(true)
            "sent" -> {
                box.busy = false
                box.lap()
            }
            "failed" -> {
                box.busy = false
                box.blink(Nihil.SIGNAL)
            }
        }
    }

    private fun tapVector() {
        val item = Vector.waiting(this)
        if (item == null) pick() else act(actionsOf(item.kind).first())
    }

    private fun act(action: String) {
        val item = Vector.waiting(this) ?: return
        val uri = Vector.uri(this, item)
        when (action) {
            "copy" -> if (Vector.copy(this) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notice.show(getString(R.string.vector_copied))
            }
            "save" -> worker.execute {
                val saved = Vector.save(this)
                main.post { if (!isDestroyed) notice.show(getString(if (saved) R.string.vector_saved else R.string.vector_no_save)) }
            }
            "open" -> {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (runCatching { startActivity(view) }.isSuccess) Vector.use(this) else notice.show(getString(R.string.vector_none_opens))
            }
            "share" -> {
                val share = Intent(Intent.ACTION_SEND)
                if (item.kind == "text") {
                    share.setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.text())
                } else {
                    share.setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    share.clipData = ClipData.newRawUri(item.name, uri)
                }
                val chosen = Intent(this, VectorAction::class.java).setAction(VectorAction.SHARED)
                val callback = PendingIntent.getBroadcast(this, 2, chosen, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                runCatching { startActivity(Intent.createChooser(share, null, callback.intentSender)) }
            }
            else -> Vector.discard(this)
        }
    }

    private fun send(payload: Vector.Payload?) {
        if (payload == null) {
            notice.show(getString(R.string.vector_nothing))
            return
        }
        launch(payload)
        Vector.send(this, payload) { outcome, computer ->
            if (!isDestroyed && (outcome != Vector.Outcome.SENT || !Vector.notifies(this))) notice.show(Vector.say(this, outcome, computer))
        }
    }

    private fun launch(payload: Vector.Payload) {
        val words = payload.text.isNotEmpty()
        outgoingTitle.text = if (words) payload.text.trim() else payload.name
        outgoingPicture.setPadding(0, 0, 0, 0)
        outgoingPicture.visibility = if (words) View.GONE else View.VISIBLE
        val shape = when (payload.kind) {
            "image" -> LineIcon.Shape.IMAGE
            "video" -> LineIcon.Shape.VIDEO
            else -> LineIcon.Shape.FILE
        }
        outgoingPicture.setImageDrawable(LineIcon(shape, Nihil.DIM))
        outgoingPicture.pad(dp(10))
        val size = dp(96)
        worker.execute {
            val picture = Vector.preview(this, payload, size) ?: return@execute
            main.post {
                if (isDestroyed) return@post
                outgoingPicture.setPadding(0, 0, 0, 0)
                outgoingPicture.setImageBitmap(picture)
            }
        }
        outgoing.animate().cancel()
        outgoing.scaleX = 1f
        outgoing.scaleY = 1f
        outgoing.alpha = 1f
        outgoing.visibility = View.VISIBLE
        content.alpha = 0f
        outgoing.animate().scaleX(0.06f).scaleY(0.06f).alpha(0f).setStartDelay(220).setDuration(520)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                outgoing.visibility = View.GONE
                content.animate().alpha(1f).setDuration(200).start()
            }.start()
    }

    private fun pick() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQUEST_PICK) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && uri != null) send(Vector.payload(this, uri))
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
        val rows = ReorderList(this, dp(8))
        rows.onReorder = { from, to ->
            tasks.place(cards[from].id, index, to)
            changed()
        }
        for (card in cards) rows.addView(row(card, rows))
        list.addView(rows, llp(MATCH, WRAP))
        if (index == TaskBoard.COLUMNS - 1 && cards.isNotEmpty()) {
            val r = dp(22f)
            list.addView(ImageView(this).apply {
                setImageDrawable(getDrawable(R.drawable.mdi_broom)?.mutate()?.apply { setTint(Nihil.DIM) })
                pad(dp(11))
                background = ripple(r, rounded(Nihil.SURFACE, r, dp(1), Nihil.LINE))
                contentDescription = getString(R.string.tasks_clear_done)
                setOnClickListener {
                    tasks.remove(cards.map { it.id })
                    changed()
                }
            }, llp(dp(44), dp(44)).apply {
                topMargin = dp(16)
                gravity = Gravity.END
            })
        }
    }

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
            if (card.column == 2) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
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

    private companion object {
        const val REQUEST_PICK = 11
        const val REQUEST_NOTIFY = 12
    }
}
