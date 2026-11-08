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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nihilshell.launcher.core.IconStyle
import com.nihilshell.launcher.core.Prefs
import com.nihilshell.launcher.deck.Computer
import com.nihilshell.launcher.deck.DeckClient
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.deck.Web
import com.nihilshell.launcher.ui.KeyButton
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.Notice
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
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The deck: this phone as a remote for NIHIL on the computer (docs/deck.md
 * in Nihil-Shell). Its music and volume, its lock, and its apps and the webs
 * kept here, each with its icon: the apps' from the computer's icon theme,
 * the webs' found by the computer, so the phone talks to no one else.
 * Sharing a web to "Open on the computer" opens it there.
 */
class DeckActivity : Activity() {

    private lateinit var store: DeckStore
    private lateinit var column: LinearLayout
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
    private var appsGrid: LinearLayout? = null
    private var websGrid: LinearLayout? = null
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeckStore.get(this)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Nihil.VOID)
            addView(column, llp(MATCH, WRAP))
        }
        notice = Notice(this)
        val root = FrameLayout(this)
        root.addView(scroll, lp(MATCH, MATCH))
        root.addView(notice, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(24), bars.top + dp(28), bars.right + dp(24), bars.bottom + dp(40))
            (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(24)
            insets
        }
        if (intent?.getBooleanExtra(EXTRA_PAIRED, false) == true) {
            store.computer?.let { main.postDelayed({ notice.show(getString(R.string.deck_paired, it.name)) }, 300) }
        }
        if (intent?.action == Intent.ACTION_SEND && shareToComputer(intent)) return
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    // --- Shared from another app ---------------------------------------------------

    /** A web shared from the browser opens on the computer, and this screen goes. */
    private fun shareToComputer(intent: Intent): Boolean {
        val computer = store.computer ?: return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = URL_IN_TEXT.find(text)?.value
        if (url == null) {
            notice.show(getString(R.string.deck_no_url))
            main.postDelayed({ finish() }, 1600)
            return true
        }
        val client = DeckClient(computer, store.token())
        pool.execute {
            val reply = client.call("POST", "/v1/open", JSONObject().put("url", url), this)
            store.save(computer)
            main.post {
                notice.show(message(reply, computer, getString(R.string.deck_opened, computer.name)))
                main.postDelayed({ finish() }, 1400)
            }
        }
        return true
    }

    // --- The screen -----------------------------------------------------------------

    private fun build() {
        column.removeAllViews()
        status = null
        val computer = store.computer
        column.addView(text(getString(R.string.app_name), 44f, Nihil.INK, fonts.dot), llp(WRAP, WRAP))
        column.addView(mono(getString(R.string.deck_title), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { topMargin = dp(6) })
        if (computer == null) {
            column.addView(text(getString(R.string.deck_pair_title), 22f, Nihil.INK), llp(MATCH, WRAP).apply { topMargin = dp(40) })
            column.addView(text(getString(R.string.deck_unpaired_sub), 13f, Nihil.DIM).apply { setLineSpacing(dp(2f), 1f) },
                llp(MATCH, WRAP).apply { topMargin = dp(6) })
            column.addView(KeyButton(this, getString(R.string.deck_pair), accent = true).apply {
                setOnClickListener { startActivity(Intent(this@DeckActivity, DeckScanActivity::class.java)) }
            }, llp(WRAP, WRAP).apply { topMargin = dp(20) })
            return
        }

        column.addView(text(computer.name, 22f, Nihil.INK), llp(MATCH, WRAP).apply { topMargin = dp(40) })
        status = mono(getString(R.string.deck_looking, computer.name), 11f, Nihil.MUTED)
        column.addView(status, llp(MATCH, WRAP).apply { topMargin = dp(6) })

        // The music, the volume and the lock, in a card like the desktop's media widget.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Nihil.VOID, dp(22f), dp(1), Nihil.LINE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        card.addView(mono(getString(R.string.deck_music), 11f, Nihil.MUTED))
        track = text(getString(R.string.deck_nothing_playing), 15f, Nihil.INK).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(track, llp(MATCH, WRAP).apply { topMargin = dp(8) })
        artist = text("", 13f, Nihil.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(artist, llp(MATCH, WRAP).apply { topMargin = dp(3) })
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transport.addView(circle(LineIcon.Shape.PREVIOUS, R.string.deck_previous) { media("previous") })
        play = circle(LineIcon.Shape.PLAY, R.string.deck_toggle, big = true) { media("toggle") }
        transport.addView(play, llp(dp(48), dp(48)).apply { marginStart = dp(14); marginEnd = dp(14) })
        transport.addView(circle(LineIcon.Shape.NEXT, R.string.deck_next) { media("next") })
        card.addView(transport, llp(WRAP, WRAP).apply {
            topMargin = dp(14)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(rule(), llp(MATCH, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val sound = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sound.addView(circle(LineIcon.Shape.MINUS, R.string.deck_volume_down) { turn("down") })
        volume = mono("", 11f, Nihil.DIM).apply { gravity = Gravity.CENTER }
        sound.addView(volume, llp(dp(84), WRAP))
        sound.addView(circle(LineIcon.Shape.PLUS, R.string.deck_volume_up) { turn("up") })
        sound.addView(View(this), llp(0, 1, 1f))
        sound.addView(KeyButton(this, getString(R.string.deck_lock)).apply { setOnClickListener { lock() } })
        card.addView(sound, llp(MATCH, WRAP))
        column.addView(card, llp(MATCH, WRAP).apply { topMargin = dp(22) })

        column.addView(text(getString(R.string.deck_apps), 22f, Nihil.INK), llp(MATCH, WRAP).apply { topMargin = dp(36) })
        appsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(appsGrid, llp(MATCH, WRAP).apply { topMargin = dp(8) })

        column.addView(text(getString(R.string.deck_webs), 22f, Nihil.INK), llp(MATCH, WRAP).apply { topMargin = dp(32) })
        websGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(websGrid, llp(MATCH, WRAP).apply { topMargin = dp(8) })
        showWebs()

        column.addView(rule(), llp(MATCH, dp(1)).apply { topMargin = dp(36) })
        column.addView(KeyButton(this, getString(R.string.deck_forget)).apply { setOnClickListener { forget(computer) } },
            llp(WRAP, WRAP).apply { topMargin = dp(18) })
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
        play?.setImageDrawable(LineIcon(if (playing) LineIcon.Shape.PAUSE else LineIcon.Shape.PLAY, Nihil.VOID))
        showVolume(state.optJSONObject("volume"))
    }

    private fun showVolume(level: JSONObject?) {
        volume?.text = when {
            level == null -> ""
            level.optBoolean("muted") -> getString(R.string.deck_muted)
            else -> getString(R.string.deck_volume, level.optInt("level"))
        }
    }

    // --- Tiles ---------------------------------------------------------------------

    private fun showApps(apps: List<Pair<String, String>>, computer: Computer) {
        val grid = appsGrid ?: return
        fill(grid, apps.map { (id, name) ->
            tile(name, "app:$id", "/v1/apps/${Uri.encode(id)}/icon", name, computer) { open(JSONObject().put("app", id)) }
        })
    }

    private fun showWebs() {
        val grid = websGrid ?: return
        val computer = store.computer ?: return
        val tiles = store.webs.map { web ->
            tile(web.title, "web:${web.url}", "/v1/favicon?url=" + URLEncoder.encode(web.url, "UTF-8"), web.title, computer,
                onLong = { removeWeb(web) }) { open(JSONObject().put("url", web.url)) }
        } + addTile()
        fill(grid, tiles)
    }

    /** Rows of four, as the drawer's grid. */
    private fun fill(grid: LinearLayout, tiles: List<View>) {
        grid.removeAllViews()
        tiles.chunked(COLUMNS).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(it, llp(0, dp(104), 1f)) }
            repeat(COLUMNS - chunk.size) { row.addView(View(this), llp(0, dp(104), 1f)) }
            grid.addView(row, llp(MATCH, WRAP))
        }
    }

    private fun tile(
        label: String,
        key: String,
        path: String,
        letterOf: String,
        computer: Computer,
        onLong: (() -> Unit)? = null,
        onTap: () -> Unit,
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(14), dp(4), 0)
            background = ripple(dp(18f))
            contentDescription = label
            setOnClickListener { onTap() }
            if (onLong != null) {
                setOnLongClickListener {
                    onLong()
                    true
                }
            }
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
        val file = File(File(cacheDir, "deck"), sha(key))
        val token = store.token()
        pool.execute {
            val bytes = file.takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < WEEK }?.readBytes()
                ?: DeckClient(computer, token).call("GET", path)?.takeIf { it.status == 200 }?.bytes?.also { fetched ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeBytes(fetched)
                    }
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

    /** A request in the background; [done] says what to tell when it went well. */
    private fun send(path: String, body: JSONObject, done: (Computer, DeckClient.Reply) -> String?) {
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
                File(cacheDir, "deck").deleteRecursively()
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

    private fun circle(shape: LineIcon.Shape, description: Int, big: Boolean = false, onTap: () -> Unit): ImageView =
        ImageView(this).apply {
            val r = dp(if (big) 24f else 20f)
            setImageDrawable(LineIcon(shape, if (big) Nihil.VOID else Nihil.DIM))
            pad(dp(if (big) 13 else 11))
            background = ripple(r, if (big) rounded(Nihil.INK, r) else rounded(Nihil.SURFACE, r, dp(1), Nihil.LINE))
            contentDescription = getString(description)
            setOnClickListener { onTap() }
            layoutParams = llp(dp(40), dp(40))
        }

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val EXTRA_PAIRED = "paired"
        private const val COLUMNS = 4
        private const val WEEK = 7L * 24 * 60 * 60 * 1000
        private val URL_IN_TEXT = Regex("https?://\\S+")
    }
}
