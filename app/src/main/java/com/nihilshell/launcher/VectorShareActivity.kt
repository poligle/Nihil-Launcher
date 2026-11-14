package com.nihilshell.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.deck.Vector
import com.nihilshell.launcher.ui.Notice
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.lp

class VectorShareActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notice: Notice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notice = Notice(this)
        val root = FrameLayout(this)
        root.addView(notice, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(28)
            insets
        }
        if (DeckStore.get(this).computer == null) {
            startActivity(Intent(this, DeckActivity::class.java))
            finish()
            return
        }
        val payload = shared(intent)
        if (payload == null) {
            notice.show(getString(R.string.vector_nothing))
            main.postDelayed({ finish() }, 1800)
            return
        }
        Vector.start(this)
        Vector.send(this, payload) { outcome, computer ->
            if (isDestroyed) return@send
            notice.show(Vector.say(this, outcome, computer))
            main.postDelayed({ finish() }, 1600)
        }
    }

    private fun shared(intent: Intent?): Vector.Payload? {
        val sent = intent ?: return null
        if (sent.action != Intent.ACTION_SEND) return null
        stream(sent)?.let { return Vector.payload(this, it) }
        val text = sent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        return if (text.isBlank()) null else Vector.text(text)
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
