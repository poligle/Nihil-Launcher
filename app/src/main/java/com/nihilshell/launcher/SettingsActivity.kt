package com.nihilshell.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.IconStyle
import com.nihilshell.launcher.core.Prefs
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.system.Access
import com.nihilshell.launcher.system.Glyph
import com.nihilshell.launcher.ui.Toggle
import com.nihilshell.launcher.ui.Glass
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.KeyButton
import com.nihilshell.launcher.ui.Choice
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.Wordmark
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.llp
import com.nihilshell.launcher.ui.mono
import com.nihilshell.launcher.ui.ripple
import com.nihilshell.launcher.ui.rounded
import com.nihilshell.launcher.ui.rule
import com.nihilshell.launcher.ui.text

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout
    private val fonts by lazy { Nihil.fonts(this) }

    private lateinit var homeStatus: TextView
    private lateinit var homeLed: View
    private lateinit var glyphSwitch: Toggle
    private lateinit var glyphSub: TextView
    private lateinit var lockSwitch: Toggle
    private lateinit var lockSub: TextView
    private lateinit var hiddenList: LinearLayout

    private val appsChanged: () -> Unit = { fillHidden() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Apps.init(this)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Nihil.VOID)
            addView(column, llp(MATCH, WRAP))
        }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(24), bars.top + dp(28), bars.right + dp(24), bars.bottom + dp(40))
            insets
        }

        build()
        Apps.listen(appsChanged)
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        fillHidden()
    }

    override fun onDestroy() {
        Apps.unlisten(appsChanged)
        super.onDestroy()
    }

    private fun build() {
        column.addView(Wordmark(this, dp(30)), llp(WRAP, WRAP))
        column.addView(mono(getString(R.string.prefs_title), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { topMargin = dp(6) })

        homeLed = View(this)
        homeStatus = body("")
        column.addView(
            row(getString(R.string.set_default), homeStatus, homeLed.also { it.layoutParams = llp(dp(10), dp(10)) }) {
                @Suppress("DEPRECATION")
                runCatching { startActivityForResult(Access.defaultHomeIntent(this), REQUEST_HOME) }
            },
            llp(MATCH, WRAP).apply { topMargin = dp(22) },
        )

        section(R.string.section_look)
        val icons = Choice(
            this,
            listOf(getString(R.string.icons_mono), getString(R.string.icons_grey), getString(R.string.icons_color)),
            prefs.iconStyle.ordinal,
        ) { prefs.iconStyle = IconStyle.entries[it] }
        column.addView(row(getString(R.string.set_icons), null, null, rule = false, onClick = null))
        column.addView(icons, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
        column.addView(rule(), llp(MATCH, dp(1)))
        column.addView(switchRow(getString(R.string.set_fav_icons), null, prefs.favoriteIcons) { prefs.favoriteIcons = it })
        column.addView(switchRow(getString(R.string.set_dim), null, prefs.dimWallpaper) { prefs.dimWallpaper = it })
        column.addView(
            switchRow(getString(R.string.set_glass), null, prefs.glass) {
                prefs.glass = it
                Glass.start(this) {}
                recreate()
            },
        )
        column.addView(
            row(getString(R.string.set_wallpaper), null, chevron()) {
                startActivity(Intent(this, WallpaperActivity::class.java))
            },
        )
        column.addView(
            row(getString(R.string.deck_title), null, chevron()) {
                startActivity(Intent(this, DeckActivity::class.java))
            },
        )
        val deck = DeckStore.get(this)
        column.addView(switchRow(getString(R.string.set_deck_screen), null, deck.keepScreenOn) { deck.keepScreenOn = it })

        section(R.string.section_behaviour)
        column.addView(switchRow(getString(R.string.set_keyboard), null, prefs.keyboardOnOpen) { prefs.keyboardOnOpen = it })
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            column.addView(row(getString(R.string.set_language), null, chevron()) {
                Access.startFirst(
                    this,
                    listOf(
                        Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")),
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                    ),
                )
            })
        }

        glyphSub = body("")
        glyphSwitch = Toggle(this)
        column.addView(row(getString(R.string.set_glyph), glyphSub, glyphSwitch) {
            if (prefs.glyph && !Access.hasNotificationAccess(this)) {
                Access.startFirst(this, Access.notificationAccessIntents(this))
            } else {
                glyphSwitch.performClick()
            }
        })
        glyphSwitch.set(prefs.glyph)
        column.addView(KeyButton(this, getString(R.string.glyph_try)).apply {
            setOnClickListener {
                finish()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Glyph.pulse(Glyph.Pulse.NOTIFY) }, 700)
            }
        }, llp(WRAP, WRAP).apply {
            topMargin = dp(16)
            bottomMargin = dp(12)
        })
        glyphSwitch.onChange = { on ->
            prefs.glyph = on
            if (on && !Access.hasNotificationAccess(this)) Access.startFirst(this, Access.notificationAccessIntents(this))
            refreshAccess()
        }

        lockSub = body("")
        lockSwitch = Toggle(this)
        column.addView(row(getString(R.string.set_lock), lockSub, lockSwitch) {
            if (prefs.doubleTapLock && !Access.hasLockService(this)) {
                Access.startFirst(this, listOf(Access.lockServiceIntent()))
            } else {
                lockSwitch.performClick()
            }
        })
        lockSwitch.set(prefs.doubleTapLock)
        lockSwitch.onChange = { on ->
            prefs.doubleTapLock = on
            if (on && !Access.hasLockService(this)) Access.startFirst(this, listOf(Access.lockServiceIntent()))
            refreshAccess()
        }

        section(R.string.section_apps)
        hiddenList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(hiddenList, llp(MATCH, WRAP))

        section(R.string.section_about)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        column.addView(body(getString(R.string.about_text, version)), llp(MATCH, WRAP))
        column.addView(body(getString(R.string.about_fonts)), llp(MATCH, WRAP).apply { topMargin = dp(10) })
        column.addView(body(getString(R.string.about_robot)), llp(MATCH, WRAP).apply { topMargin = dp(10) })
        column.addView(body(getString(R.string.about_icons)), llp(MATCH, WRAP).apply { topMargin = dp(10) })
        val site = KeyButton(this, getString(R.string.about_site)).apply {
            setOnClickListener {
                Access.startFirst(this@SettingsActivity, listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://nihilshell.com/android"))))
            }
        }
        column.addView(site, llp(WRAP, WRAP).apply { topMargin = dp(16) })
    }

    private fun refreshAccess() {
        val isHome = Access.isDefaultHome(this)
        homeStatus.text = getString(if (isHome) R.string.set_default_on else R.string.set_default_off)
        homeLed.background = rounded(if (isHome) Nihil.INK else Nihil.OFF, dp(5f))

        val notifications = Access.hasNotificationAccess(this)
        glyphSub.text = when {
            prefs.glyph && !notifications -> getString(R.string.glyph_needs_access) + "\n" + getString(R.string.restricted_hint)
            prefs.glyph && !Glyph.connected -> getString(R.string.glyph_not_connected)
            prefs.glyph -> getString(R.string.glyph_listening)
            else -> ""
        }
        glyphSub.visibility = if (glyphSub.text.isEmpty()) View.GONE else View.VISIBLE
        val lock = Access.hasLockService(this)
        lockSub.text = if (prefs.doubleTapLock && !lock) getString(R.string.lock_needs_service) + "\n" + getString(R.string.restricted_hint) else ""
        lockSub.visibility = if (lockSub.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fillHidden() {
        if (!this::hiddenList.isInitialized) return
        hiddenList.removeAllViews()
        val hidden = prefs.hidden.mapNotNull { Apps.find(it) }.sortedBy { it.label.lowercase() }
        if (hidden.isEmpty()) {
            hiddenList.addView(mono(getString(R.string.set_hidden_none), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            return
        }
        for (entry in hidden) {
            val show = KeyButton(this, getString(R.string.set_unhide)).apply {
                setOnClickListener {
                    prefs.unhide(entry.key)
                    fillHidden()
                }
            }
            hiddenList.addView(row(entry.label, null, show, onClick = null))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HOME) refreshAccess()
    }

    private fun section(title: Int) {
        column.addView(text(getString(title), 22f, Nihil.INK, fonts.sans), llp(MATCH, WRAP).apply {
            topMargin = dp(40)
            bottomMargin = dp(8)
        })
    }

    private fun body(value: String): TextView = text(value, 12f, Nihil.MUTED, fonts.sans).apply {
        setLineSpacing(dp(3f), 1f)
    }

    private fun chevron(): View = android.widget.ImageView(this).apply {
        setImageDrawable(LineIcon(LineIcon.Shape.CHEVRON, Nihil.DIM))
        layoutParams = llp(dp(20), dp(20))
    }

    private fun switchRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Toggle(this)
        toggle.set(value)
        toggle.onChange = onChange
        return row(title, sub?.let { body(it) }, toggle) { toggle.performClick() }
    }

    private fun row(title: String, sub: TextView?, trailing: View?, rule: Boolean = true, onClick: (() -> Unit)?): LinearLayout {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, Nihil.INK, fonts.sans).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (sub != null) addView(sub, llp(MATCH, WRAP).apply { topMargin = dp(5) })
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(14), 0, dp(14))
            addView(texts, llp(0, WRAP, 1f))
            if (trailing != null) {
                val params = trailing.layoutParams as? LinearLayout.LayoutParams ?: llp(WRAP, WRAP)
                params.marginStart = dp(16)
                addView(trailing, params)
            }
            if (onClick != null) {
                background = ripple(dp(10f))
                setOnClickListener { onClick() }
            }
        }
        if (!rule) return line
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, llp(MATCH, WRAP))
            addView(rule(), llp(MATCH, dp(1)))
        }
    }

    private companion object {
        const val REQUEST_HOME = 7
    }
}
