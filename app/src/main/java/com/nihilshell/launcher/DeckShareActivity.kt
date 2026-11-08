package com.nihilshell.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import com.nihilshell.launcher.deck.DeckClient
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.ui.Notice
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.lp
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * "Open on the computer" in the share sheet: the web shared opens on the
 * paired computer, a notice says so, and this goes, over whatever was on
 * screen and in its orientation (the deck itself is landscape).
 */
class DeckShareActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notice = Notice(this)
        val root = FrameLayout(this)
        root.addView(notice, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(28)
            insets
        }
        val store = DeckStore.get(this)
        val computer = store.computer
        if (computer == null) {
            // Not paired yet: the deck, to pair.
            startActivity(Intent(this, DeckActivity::class.java))
            finish()
            return
        }
        val url = URL_IN_TEXT.find(intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value
        if (url == null) {
            notice.show(getString(R.string.deck_no_url))
            main.postDelayed({ finish() }, 1800)
            return
        }
        val client = DeckClient(computer, store.token())
        worker.execute {
            val reply = client.call("POST", "/v1/open", JSONObject().put("url", url), this)
            store.save(computer)
            main.post {
                if (isDestroyed) return@post
                notice.show(
                    when {
                        reply == null -> getString(R.string.deck_unreachable_status, computer.name)
                        reply.status == 401 -> getString(R.string.deck_forgotten, computer.name).also { store.forget() }
                        reply.status == 200 -> getString(R.string.deck_opened, computer.name)
                        else -> getString(R.string.deck_refused, computer.name)
                    },
                )
                main.postDelayed({ finish() }, 1600)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        val URL_IN_TEXT = Regex("https?://\\S+")
    }
}
