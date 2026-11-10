package com.nihilshell.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import com.nihilshell.launcher.ui.ReorderGrid
import com.nihilshell.launcher.ui.AppMenu
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nihilshell.launcher.core.IconStyle
import com.nihilshell.launcher.core.Prefs
import com.nihilshell.launcher.deck.Computer
import com.nihilshell.launcher.deck.DeckCache
import com.nihilshell.launcher.deck.DeckClient
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.deck.TrackpadLink
import com.nihilshell.launcher.deck.Web
import com.nihilshell.launcher.ui.KeyButton
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.Notice
import com.nihilshell.launcher.ui.PageDots
import com.nihilshell.launcher.ui.Trackpad
import com.nihilshell.launcher.ui.Wordmark
import com.nihilshell.launcher.ui.Workspaces
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.llp
import com.nihilshell.launcher.ui.lp
import com.nihilshell.launcher.ui.mono
import com.nihilshell.launcher.ui.pad
import com.nihilshell.launcher.ui.ripple
import com.nihilshell.launcher.ui.rounded
import com.nihilshell.launcher.ui.rule
import com.nihilshell.launcher.ui.text
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * The deck: this phone as a remote for NIHIL on the computer (docs/deck.md
 * in Nihil-Shell). Its music and volume; its caffeine, night light and lock,
 * at the top with the desktop's own icons; and its apps and the webs kept
 * here, each with its icon: the apps' from the computer's icon theme, the
 * webs' found by the computer, so the phone talks to no one else.
 * In landscape, on one screen without scrolling, in workspaces as the
 * desktop's: the music and the volume in one; the apps and the webs, each
 * scrolling on its own, in the next; and the computer's trackpad in the
 * last. Sharing a web to "Open on the computer" is DeckShareActivity.
 */
class DeckActivity : Activity() {

    private lateinit var store: DeckStore
    private lateinit var content: FrameLayout
    private lateinit var notice: Notice
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val pictures = HashMap<String, Bitmap>()
    private val fonts by lazy { Nihil.fonts(this) }

    private var status: TextView? = null
    private var track: TextView? = null
    private var artist: TextView? = null
    private var play: ImageView? = null
    private var volume: TextView? = null
    private var volumeUnit: TextView? = null
    private var appsGrid: LinearLayout? = null
    private var websGrid: LinearLayout? = null
    private var workspaces: Workspaces? = null
    private var playing = false
    private var caffeineKey: ImageView? = null
    private var nightKey: ImageView? = null
    private var cover: ImageView? = null
    private var coverShown = ""
    private val popup by lazy { AppMenu(this) }
    private var appsColumns = 6
    private var websColumns = 4
    private var trackpad: Trackpad? = null
    private var trackpadStatus: TextView? = null
    private var trackpadHint: TextView? = null
    private val link by lazy { TrackpadLink(store) { showTrackpad(it) } }
    private var onScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeckStore.get(this)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        content = FrameLayout(this).apply { setBackgroundColor(Nihil.VOID) }
        notice = Notice(this)
        val root = FrameLayout(this)
        root.addView(content, lp(MATCH, MATCH))
        root.addView(notice, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(bars.left + dp(20), bars.top + dp(14), bars.right + dp(20), bars.bottom + dp(8))
            (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(20)
            insets
        }
        if (intent?.getBooleanExtra(EXTRA_PAIRED, false) == true) {
            store.computer?.let { main.postDelayed({ notice.show(getString(R.string.deck_paired, it.name)) }, 300) }
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        // Settings, "Screen on in the deck": this phone's screen, not the computer's.
        if (store.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onScreen = true
        refresh()
        trackpadOn()
    }

    override fun onPause() {
        onScreen = false
        link.stop()
        super.onPause()
    }

    override fun onDestroy() {
        link.stop()
        pool.shutdownNow()
        super.onDestroy()
    }

    // --- The screen -----------------------------------------------------------------

    private fun build() {
        content.removeAllViews()
        status = null
        workspaces = null
        trackpad = null
        trackpadStatus = null
        trackpadHint = null
        link.stop()
        val computer = store.computer
        if (computer == null) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(Wordmark(this, dp(36)), llp(WRAP, WRAP))
            column.addView(text(getString(R.string.deck_pair_title), 22f, Nihil.INK), llp(WRAP, WRAP).apply { topMargin = dp(22) })
            column.addView(text(getString(R.string.deck_unpaired_sub), 13f, Nihil.DIM).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(2f), 1f)
            }, llp(dp(460), WRAP).apply { topMargin = dp(6) })
            column.addView(KeyButton(this, getString(R.string.deck_pair), accent = true).apply {
                setOnClickListener { startActivity(Intent(this@DeckActivity, DeckScanActivity::class.java)) }
            }, llp(WRAP, WRAP).apply { topMargin = dp(18) })
            content.addView(column, lp(WRAP, WRAP, Gravity.CENTER))
            return
        }

        // As many tiles across as fit: the apps on three fifths of the width, the webs on two.
        val wide = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        appsColumns = ((wide * 0.6f - 40) / 84).toInt().coerceIn(4, 10)
        websColumns = ((wide * 0.4f - 40) / 84).toInt().coerceIn(3, 6)

        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(computer.name, 16f, Nihil.INK))
        status = mono(getString(R.string.deck_looking, computer.name), 11f, Nihil.MUTED)
        names.addView(status, llp(WRAP, WRAP).apply { topMargin = dp(2) })
        header.addView(names, llp(0, WRAP, 1f))
        // The computer's own keys, as its quick settings: lit while on.
        caffeineKey = key(R.drawable.mdi_coffee, R.string.deck_caffeine) {
            toggle("caffeine", caffeineKey, R.string.deck_caffeine_on, R.string.deck_caffeine_off)
        }
        header.addView(caffeineKey, llp(dp(40), dp(40)).apply { marginEnd = dp(10) })
        nightKey = key(R.drawable.mdi_night, R.string.deck_night) {
            toggle("night", nightKey, R.string.deck_night_on, R.string.deck_night_off)
        }
        header.addView(nightKey, llp(dp(40), dp(40)).apply { marginEnd = dp(10) })
        header.addView(key(R.drawable.mdi_lock, R.string.deck_lock) { lock() }, llp(dp(40), dp(40)).apply { marginEnd = dp(22) })
        header.addView(circle(LineIcon.Shape.CLOSE, R.string.deck_forget) { forget(computer) })
        screen.addView(header, llp(MATCH, WRAP))

        val pages = Workspaces(this)
        pages.addView(musicPage())
        pages.addView(appsPage())
        pages.addView(trackpadPage())
        workspaces = pages
        screen.addView(pages, llp(MATCH, 0, 1f).apply { topMargin = dp(10) })
        val titles = listOf(getString(R.string.deck_music), getString(R.string.deck_apps_webs), getString(R.string.deck_trackpad))
        val dots = PageDots(this, titles) { pages.show(it) }
        pages.onPage = {
            dots.select(it)
            trackpadOn()
        }
        screen.addView(dots, llp(MATCH, dp(28)))
        content.addView(screen, lp(MATCH, MATCH))
        showWebs()
    }

    /** The third workspace: this screen as the computer's touchpad, while it shows. */
    private fun trackpadPage(): View {
        val card = FrameLayout(this).apply {
            background = rounded(Nihil.VOID, dp(22f), dp(1), Nihil.LINE)
            clipToOutline = true
            // Every finger to the trackpad, wherever it lands.
            isMotionEventSplittingEnabled = false
        }
        val pad = Trackpad(this).apply { contentDescription = getString(R.string.deck_trackpad) }
        pad.onFrame = { time, count, ids, xs, ys ->
            trackpadHint?.visibility = View.GONE
            link.frame(time, count, ids, xs, ys)
        }
        pad.onSize = { trackpadOn() }
        card.addView(pad, lp(MATCH, MATCH))
        card.addView(mono(getString(R.string.deck_trackpad), 11f, Nihil.MUTED), lp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(20)
            topMargin = dp(16)
        })
        trackpadStatus = mono("", 11f, Nihil.MUTED).apply {
            gravity = Gravity.END
            maxWidth = dp(360)
        }
        card.addView(trackpadStatus, lp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(20)
            topMargin = dp(16)
        })
        trackpadHint = mono(getString(R.string.deck_trackpad_hint), 11f, Nihil.MUTED).apply { gravity = Gravity.CENTER }
        card.addView(trackpadHint, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        trackpad = pad
        return card
    }

    /** The stream while the trackpad is the workspace on screen; none otherwise. */
    private fun trackpadOn() {
        val pad = trackpad
        if (onScreen && pad != null && pad.width > 0 && workspaces?.page == TRACKPAD && store.computer != null) {
            link.start(TrackpadLink.Size(pad.widthUnits, pad.heightUnits, Trackpad.RESOLUTION))
        } else {
            link.stop()
            trackpadHint?.visibility = View.VISIBLE
        }
    }

    private fun showTrackpad(state: TrackpadLink.State) {
        val name = store.computer?.name.orEmpty()
        trackpadStatus?.text = when (state) {
            TrackpadLink.State.CONNECTING -> getString(R.string.deck_trackpad_connecting)
            TrackpadLink.State.READY -> ""
            TrackpadLink.State.OLDER -> getString(R.string.deck_older, name)
            TrackpadLink.State.ACCESS -> getString(R.string.deck_trackpad_access, name)
            TrackpadLink.State.REFUSED -> getString(R.string.deck_trackpad_refused, name)
            TrackpadLink.State.UNREACHABLE -> getString(R.string.deck_unreachable_status, name)
            TrackpadLink.State.FORGOTTEN -> getString(R.string.deck_forgotten, name)
        }
    }

    /** The first workspace: what plays, with its keys, and the volume beside it. */
    private fun musicPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val music = card()
        val playingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = rounded(Nihil.SURFACE, dp(14f), dp(1), Nihil.LINE)
            // In greys, as everything else here.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            contentDescription = getString(R.string.deck_cover)
        }
        playingRow.addView(cover, llp(dp(104), dp(104)).apply { marginEnd = dp(18) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(mono(getString(R.string.deck_music), 11f, Nihil.MUTED))
        track = text(getString(R.string.deck_nothing_playing), 22f, Nihil.INK).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        words.addView(track, llp(MATCH, WRAP).apply { topMargin = dp(8) })
        artist = text("", 15f, Nihil.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        words.addView(artist, llp(MATCH, WRAP).apply { topMargin = dp(4) })
        playingRow.addView(words, llp(0, WRAP, 1f))
        music.addView(playingRow, llp(MATCH, WRAP))
        music.addView(View(this), llp(MATCH, 0, 1f))
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(circle(LineIcon.Shape.PREVIOUS, R.string.deck_previous, size = 52) { media("previous") })
        play = circle(LineIcon.Shape.PLAY, R.string.deck_toggle, big = true, size = 68) { media("toggle") }
        transport.addView(play, llp(dp(68), dp(68)).apply {
            marginStart = dp(22)
            marginEnd = dp(22)
        })
        transport.addView(circle(LineIcon.Shape.NEXT, R.string.deck_next, size = 52) { media("next") })
        music.addView(transport, llp(MATCH, WRAP))
        page.addView(music, llp(0, MATCH, 3f).apply { marginEnd = dp(14) })

        val sound = card()
        sound.addView(mono(getString(R.string.deck_volume_title), 11f, Nihil.MUTED))
        val level = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        volume = text("--", 56f, Nihil.INK, fonts.dot)
        level.addView(volume)
        volumeUnit = mono("%", 13f, Nihil.MUTED)
        level.addView(volumeUnit, llp(WRAP, WRAP).apply {
            marginStart = dp(6)
            bottomMargin = dp(12)
        })
        sound.addView(level, llp(WRAP, WRAP).apply { topMargin = dp(4) })
        val turn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        turn.addView(circle(LineIcon.Shape.MINUS, R.string.deck_volume_down, size = 52) { turn("down") })
        turn.addView(circle(LineIcon.Shape.PLUS, R.string.deck_volume_up, size = 52) { turn("up") }, llp(dp(52), dp(52)).apply { marginStart = dp(14) })
        sound.addView(turn, llp(WRAP, WRAP).apply { topMargin = dp(6) })
        page.addView(sound, llp(0, MATCH, 2f))
        return page
    }

    /** The second workspace: the computer's apps and the webs kept here, each scrolling on its own. */
    private fun appsPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        appsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(panel(R.string.deck_apps, appsGrid!!), llp(0, MATCH, 3f).apply { marginEnd = dp(14) })
        websGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(panel(R.string.deck_webs, websGrid!!), llp(0, MATCH, 2f))
        return page
    }

    private fun panel(title: Int, grid: LinearLayout): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Nihil.VOID, dp(22f), dp(1), Nihil.LINE)
        setPadding(dp(14), dp(12), dp(14), dp(4))
        addView(mono(getString(title), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { marginStart = dp(4) })
        addView(ScrollView(this@DeckActivity).apply {
            isVerticalScrollBarEnabled = false
            addView(grid, llp(MATCH, WRAP))
        }, llp(MATCH, 0, 1f))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Nihil.VOID, dp(22f), dp(1), Nihil.LINE)
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }

    /** The computer's apps and what it plays, asked again each time the screen shows. */
    private fun refresh() {
        val computer = store.computer ?: return
        val client = DeckClient(computer, store.token())
        pool.execute {
            val apps = client.call("GET", "/v1/apps", null, this)
            val state = if (apps != null && apps.status == 200) client.call("GET", "/v1/state", null, this) else null
            store.save(computer)
            main.post {
                if (isDestroyed) return@post
                if (apps == null) {
                    status?.text = getString(R.string.deck_unreachable_status, computer.name)
                    return@post
                }
                if (apps.status == 401) {
                    unpaired(computer)
                    return@post
                }
                status?.text = getString(R.string.deck_connected, computer.last.orEmpty())
                apps.json?.optJSONArray("apps")?.let { list ->
                    showApps(List(list.length()) { list.getJSONObject(it) }.map { it.getString("id") to it.optString("name") }, computer)
                }
                state?.json?.let { showState(it) }
            }
        }
    }

    private fun showState(state: JSONObject) {
        val media = state.optJSONObject("media")
        if (media == null) {
            track?.text = getString(R.string.deck_nothing_playing)
            artist?.text = ""
            playing = false
        } else {
            track?.text = media.optString("title").ifEmpty { media.optString("player") }
            artist?.text = media.optString("artist")
            playing = media.optBoolean("playing")
        }
        showCover(media?.optString("cover").orEmpty())
        play?.setImageDrawable(LineIcon(if (playing) LineIcon.Shape.PAUSE else LineIcon.Shape.PLAY, Nihil.VOID))
        showVolume(state.optJSONObject("volume"))
        // A computer with an older NIHIL says nothing of these: they stay unlit.
        lit(caffeineKey, state.optBoolean("caffeine"))
        lit(nightKey, state.optBoolean("night"))
    }

    /** The cover of what plays, asked for again only when the computer says it changed. */
    private fun showCover(token: String) {
        val view = cover ?: return
        if (token == coverShown) return
        coverShown = token
        if (token.isEmpty()) {
            view.setImageDrawable(null)
            return
        }
        val computer = store.computer ?: return
        val client = DeckClient(computer, store.token())
        pool.execute {
            val bytes = client.call("GET", "/v1/cover?c=" + Uri.encode(token))?.takeIf { it.status == 200 }?.bytes
            val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            main.post {
                if (!isDestroyed && coverShown == token) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun showVolume(level: JSONObject?) {
        volume?.text = if (level == null) "--" else level.optInt("level").toString()
        volumeUnit?.text = if (level?.optBoolean("muted") == true) getString(R.string.deck_muted) else "%"
    }

    // --- Tiles ---------------------------------------------------------------------

    // The apps in the order put here (held and moved), the ones new on the computer at the end.
    private fun showApps(apps: List<Pair<String, String>>, computer: Computer) {
        val holder = appsGrid ?: return
        val order = store.appsOrder
        val sorted = apps.sortedWith(compareBy({ order.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.second.lowercase() }))
        val grid = ReorderGrid(this, appsColumns, dp(104))
        grid.onReorder = { from, to ->
            val ids = sorted.map { it.first }.toMutableList()
            ids.add(to, ids.removeAt(from))
            store.appsOrder = ids
            showApps(ids.mapNotNull { id -> sorted.firstOrNull { it.first == id } }, computer)
        }
        for ((id, name) in sorted) {
            val cell = tile(name, "app:$id", "/v1/apps/${Uri.encode(id)}/icon", name, computer) { open(JSONObject().put("app", id)) }
            grid.movable(cell)
            cell.setOnLongClickListener {
                grid.arm(it)
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                true
            }
            grid.addView(cell)
        }
        holder.removeAllViews()
        holder.addView(grid, llp(MATCH, WRAP))
    }

    // The webs in their order, held and moved; held and let go, a menu to remove one.
    private fun showWebs() {
        val holder = websGrid ?: return
        val computer = store.computer ?: return
        val webs = store.webs
        val grid = ReorderGrid(this, websColumns, dp(104))
        grid.fixedTail = 1
        grid.onReorder = { from, to ->
            val list = webs.toMutableList()
            list.add(to, list.removeAt(from))
            store.webs = list
            showWebs()
        }
        for (web in webs) {
            val cell = tile(web.title, "web:${web.url}", "/v1/favicon?url=" + URLEncoder.encode(web.url, "UTF-8"), web.title, computer) {
                open(JSONObject().put("url", web.url))
            }
            grid.movable(cell)
            cell.setOnLongClickListener {
                popup.show(anchor = it, title = web.title, items = listOf(
                    AppMenu.Item(LineIcon.Shape.TRASH, getString(R.string.deck_remove), danger = true) { removeWeb(web) },
                ))
                grid.arm(it) { popup.dismiss() }
                true
            }
            grid.addView(cell)
        }
        grid.addView(addTile())
        holder.removeAllViews()
        holder.addView(grid, llp(MATCH, WRAP))
    }

    private fun tile(
        label: String,
        key: String,
        path: String,
        letterOf: String,
        computer: Computer,
        onTap: () -> Unit,
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(14), dp(4), 0)
            background = ripple(dp(18f))
            contentDescription = label
            setOnClickListener { onTap() }
        }
        val size = dp(46)
        val holder = FrameLayout(this)
        val letter = text(letterOf.trim().take(1).uppercase(), 20f, Nihil.DIM).apply {
            gravity = Gravity.CENTER
            background = rounded(Nihil.SURFACE, dp(14f), dp(1), Nihil.LINE)
        }
        val icon = ImageView(this).apply { visibility = View.GONE }
        if (Prefs.get(this).iconStyle != IconStyle.COLOR) {
            icon.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        holder.addView(letter, lp(MATCH, MATCH))
        holder.addView(icon, lp(MATCH, MATCH))
        cell.addView(holder, llp(size, size))
        cell.addView(mono(label, 11f, Nihil.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, llp(MATCH, WRAP).apply { topMargin = dp(10) })
        picture(key, path, computer) { bitmap ->
            icon.setImageBitmap(bitmap)
            icon.visibility = View.VISIBLE
            letter.visibility = View.GONE
        }
        return cell
    }

    private fun addTile(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(14), dp(4), 0)
        background = ripple(dp(18f))
        contentDescription = getString(R.string.deck_add_web)
        setOnClickListener { addWeb() }
        addView(ImageView(this@DeckActivity).apply {
            setImageDrawable(LineIcon(LineIcon.Shape.PLUS, Nihil.DIM))
            pad(dp(11))
            background = rounded(0, dp(14f), dp(1), Nihil.LINE)
        }, llp(dp(46), dp(46)))
        addView(mono(getString(R.string.deck_add_web), 11f, Nihil.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, llp(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    /** An icon: kept in memory, then on disk (the computer's change rarely), then asked for. */
    private fun picture(key: String, path: String, computer: Computer, ready: (Bitmap) -> Unit) {
        pictures[key]?.let { return ready(it) }
        val file = DeckCache.file(this, key)
        val token = store.token()
        pool.execute {
            val bytes = file.takeIf { !DeckCache.stale(this, key) }?.readBytes()
                ?: DeckClient(computer, token).call("GET", path)?.takeIf { it.status == 200 }?.bytes?.also { fetched ->
                    DeckCache.keep(this, key, fetched)
                }
            val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } ?: return@execute
            main.post {
                if (isDestroyed) return@post
                pictures[key] = bitmap
                ready(bitmap)
            }
        }
    }

    // --- What the buttons do --------------------------------------------------------

    private fun open(body: JSONObject) = send("/v1/open", body) { computer, _ -> getString(R.string.deck_opened, computer.name) }

    private fun media(action: String) = send("/v1/media", JSONObject().put("action", action)) { _, _ ->
        refresh()
        null
    }

    private fun turn(action: String) = send("/v1/volume", JSONObject().put("action", action)) { _, reply ->
        showVolume(reply.json)
        null
    }

    private fun lock() = send("/v1/lock", JSONObject()) { computer, _ -> getString(R.string.deck_locked, computer.name) }

    /** The computer's caffeine or night light, turned over; the key shows what it says now. */
    private fun toggle(kind: String, view: ImageView?, on: Int, off: Int) =
        send("/v1/$kind", JSONObject().put("action", "toggle"), missing = true) { computer, reply ->
            val now = reply.json?.optBoolean("on") == true
            lit(view, now)
            getString(if (now) on else off, computer.name)
        }

    /** A request in the background; [done] says what to tell when it went well.
     *  With [missing], a 404 means the computer's NIHIL is older than this. */
    private fun send(path: String, body: JSONObject, missing: Boolean = false, done: (Computer, DeckClient.Reply) -> String?) {
        val computer = store.computer ?: return
        val client = DeckClient(computer, store.token())
        pool.execute {
            val reply = client.call("POST", path, body, this)
            store.save(computer)
            main.post {
                if (isDestroyed) return@post
                when {
                    reply == null -> notice.show(getString(R.string.deck_unreachable_status, computer.name))
                    reply.status == 401 -> unpaired(computer)
                    reply.status == 200 -> done(computer, reply)?.let { notice.show(it) }
                    missing && reply.status == 404 -> notice.show(getString(R.string.deck_older, computer.name))
                    else -> notice.show(message(reply, computer, ""))
                }
            }
        }
    }

    private fun message(reply: DeckClient.Reply?, computer: Computer, ok: String): String = when {
        reply == null -> getString(R.string.deck_unreachable_status, computer.name)
        reply.status == 401 -> getString(R.string.deck_forgotten, computer.name)
        reply.status == 200 -> ok
        reply.status == 503 -> getString(R.string.deck_busy, computer.name)
        else -> getString(R.string.deck_refused, computer.name)
    }

    /** The computer forgot this phone (Preferences, Phone): so does the phone. */
    private fun unpaired(computer: Computer) {
        store.forget()
        build()
        notice.show(getString(R.string.deck_forgotten, computer.name))
    }

    private fun forget(computer: Computer) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(getString(R.string.deck_forget_confirm, computer.name))
            .setPositiveButton(R.string.deck_forget) { _, _ ->
                val client = DeckClient(computer, store.token())
                pool.execute { client.call("POST", "/v1/unpair", JSONObject()) }
                store.forget()
                DeckCache.dir(this).deleteRecursively()
                pictures.clear()
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addWeb() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.deck_add_web_hint)
            isSingleLine = true
        }
        val frame = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0) }
        frame.addView(field)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.deck_add_web)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = field.text.toString().trim()
                val url = if (typed.contains("://")) typed else "https://$typed"
                val uri = Uri.parse(url)
                if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()) {
                    val title = uri.host!!.removePrefix("www.")
                    store.webs = store.webs.filter { it.url != url } + Web(url, title)
                    showWebs()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeWeb(web: Web) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(getString(R.string.deck_remove_web, web.title))
            .setPositiveButton(R.string.deck_remove) { _, _ ->
                store.webs = store.webs.filter { it.url != web.url }
                showWebs()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun circle(shape: LineIcon.Shape, description: Int, big: Boolean = false, size: Int = 40, onTap: () -> Unit): ImageView =
        ImageView(this).apply {
            val r = dp(size / 2f)
            setImageDrawable(LineIcon(shape, if (big) Nihil.VOID else Nihil.DIM))
            pad(dp(size * 0.27f).toInt())
            background = ripple(r, if (big) rounded(Nihil.INK, r) else rounded(Nihil.SURFACE, r, dp(1), Nihil.LINE))
            contentDescription = getString(description)
            setOnClickListener { onTap() }
            layoutParams = llp(dp(size), dp(size))
        }

    /** A key with the desktop's own glyph for it (Material Design Icons, as its Nerd Font has them). */
    private fun key(icon: Int, description: Int, onTap: () -> Unit): ImageView = ImageView(this).apply {
        tag = icon
        pad(dp(10))
        contentDescription = getString(description)
        setOnClickListener { onTap() }
        lit(this, false)
    }

    /** Lit as the desktop's toggles while on: ink, the glyph in black. */
    private fun lit(view: ImageView?, on: Boolean) {
        view ?: return
        val r = dp(20f)
        view.setImageDrawable(getDrawable(view.tag as Int)?.mutate()?.apply { setTint(if (on) Nihil.VOID else Nihil.DIM) })
        view.background = ripple(r, if (on) rounded(Nihil.INK, r) else rounded(Nihil.SURFACE, r, dp(1), Nihil.LINE))
    }

    companion object {
        const val EXTRA_PAIRED = "paired"
        private const val TRACKPAD = 2
    }
}
