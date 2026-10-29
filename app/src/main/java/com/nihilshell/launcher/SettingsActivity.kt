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
import com.nihilshell.launcher.system.Access
import com.nihilshell.launcher.ui.LedSwitch
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.Pill
import com.nihilshell.launcher.ui.Segmented
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.llp
import com.nihilshell.launcher.ui.mono
import com.nihilshell.launcher.ui.ripple
import com.nihilshell.launcher.ui.rounded
import com.nihilshell.launcher.ui.rule
import com.nihilshell.launcher.ui.text

/**
 * NIHIL's settings: one black page, sections in Space Mono, switches as LEDs.
 * It's also the "NIHIL" entry in the app list.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout
    private val fonts by lazy { Nihil.fonts(this) }

    private lateinit var homeStatus: TextView
    private lateinit var homeLed: View
    private lateinit var glyphSwitch: LedSwitch
    private lateinit var glyphSub: TextView
    private lateinit var lockSwitch: LedSwitch
    private lateinit var lockSub: TextView
    private lateinit var hiddenList: LinearLayout

    private val appsChanged: () -> Unit = { fillHidden() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Apps.init(this)
        // Edge to edge on every version (Android 15 enforces it; 12-14 need the call).
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
        // Back from a system page: the home role or an access may have changed.
        refreshAccess()
        fillHidden()
    }

    override fun onDestroy() {
        Apps.unlisten(appsChanged)
        super.onDestroy()
    }

    private fun build() {
        // Wordmark.
        column.addView(text(getString(R.string.app_name), 64f, Nihil.INK, fonts.dot), llp(WRAP, WRAP))
        column.addView(mono(getString(R.string.tagline), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { topMargin = dp(10) })

        // Home app.
        homeLed = View(this)
        homeStatus = body("")
        column.addView(
            row(getString(R.string.set_default), homeStatus, homeLed.also { it.layoutParams = llp(dp(10), dp(10)) }) {
                // The role dialog needs a caller to answer to: start it for a result.
                @Suppress("DEPRECATION")
                runCatching { startActivityForResult(Access.defaultHomeIntent(this), REQUEST_HOME) }
            },
            llp(MATCH, WRAP).apply { topMargin = dp(28) },
        )

        // Look.
        section(R.string.section_look)
        val icons = Segmented(
            this,
            listOf(getString(R.string.icons_mono), getString(R.string.icons_grey), getString(R.string.icons_color)),
            prefs.iconStyle.ordinal,
        ) { prefs.iconStyle = IconStyle.entries[it] }
        column.addView(row(getString(R.string.set_icons), body(getString(R.string.set_icons_sub)), null, null))
        column.addView(icons, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
        column.addView(switchRow(getString(R.string.set_fav_icons), null, prefs.favoriteIcons) { prefs.favoriteIcons = it })
        column.addView(switchRow(getString(R.string.set_dim), null, prefs.dimWallpaper) { prefs.dimWallpaper = it })
        column.addView(
            row(getString(R.string.set_wallpaper), body(getString(R.string.set_wallpaper_sub)), chevron()) {
                startActivity(Intent(this, WallpaperActivity::class.java))
            },
        )

        // Behaviour.
        section(R.string.section_behaviour)
        column.addView(switchRow(getString(R.string.set_keyboard), null, prefs.keyboardOnOpen) { prefs.keyboardOnOpen = it })

        glyphSub = body(getString(R.string.set_glyph_sub))
        glyphSwitch = LedSwitch(this)
        column.addView(row(getString(R.string.set_glyph), glyphSub, glyphSwitch) { glyphSwitch.performClick() })
        glyphSwitch.set(prefs.glyph)
        glyphSwitch.onChange = { on ->
            prefs.glyph = on
            if (on && !Access.hasNotificationAccess(this)) Access.startFirst(this, Access.notificationAccessIntents(this))
            refreshAccess()
        }

        lockSub = body(getString(R.string.set_lock_sub))
        lockSwitch = LedSwitch(this)
        column.addView(row(getString(R.string.set_lock), lockSub, lockSwitch) { lockSwitch.performClick() })
        lockSwitch.set(prefs.doubleTapLock)
        lockSwitch.onChange = { on ->
            prefs.doubleTapLock = on
            if (on && !Access.hasLockService(this)) Access.startFirst(this, listOf(Access.lockServiceIntent()))
            refreshAccess()
        }

        // Hidden apps.
        section(R.string.section_apps)
        column.addView(body(getString(R.string.set_hidden_sub)), llp(MATCH, WRAP).apply { bottomMargin = dp(6) })
        hiddenList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(hiddenList, llp(MATCH, WRAP))

        // About.
        section(R.string.section_about)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        column.addView(body(getString(R.string.about_text, version)), llp(MATCH, WRAP))
        column.addView(body(getString(R.string.about_fonts)), llp(MATCH, WRAP).apply { topMargin = dp(10) })
        val site = mono(getString(R.string.about_site), 12f, Nihil.INK).apply {
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                Access.startFirst(this@SettingsActivity, listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://nihilshell.com"))))
            }
        }
        column.addView(site, llp(WRAP, WRAP).apply { topMargin = dp(4) })
    }

    private fun refreshAccess() {
        val isHome = Access.isDefaultHome(this)
        homeStatus.text = getString(if (isHome) R.string.set_default_on else R.string.set_default_off)
        homeLed.background = rounded(if (isHome) Nihil.INK else Nihil.LINE, dp(5f))

        val notifications = Access.hasNotificationAccess(this)
        glyphSub.text = if (prefs.glyph && !notifications) {
            getString(R.string.glyph_needs_access) + "\n" + getString(R.string.restricted_hint)
        } else {
            getString(R.string.set_glyph_sub)
        }
        val lock = Access.hasLockService(this)
        lockSub.text = if (prefs.doubleTapLock && !lock) {
            getString(R.string.lock_needs_service) + "\n" + getString(R.string.restricted_hint)
        } else {
            getString(R.string.set_lock_sub)
        }
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
            val show = Pill(this, getString(R.string.set_unhide)).apply {
                setOnClickListener {
                    prefs.unhide(entry.key)
                    fillHidden()
                }
            }
            hiddenList.addView(row(entry.label, null, show, null))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HOME) refreshAccess()
    }

    // --- Building blocks -------------------------------------------------------------

    private fun section(title: Int) {
        val label = mono(getString(title), 11f, Nihil.MUTED)
        column.addView(rule(), llp(MATCH, dp(1)).apply { topMargin = dp(30) })
        column.addView(label, llp(WRAP, WRAP).apply {
            topMargin = dp(16)
            bottomMargin = dp(6)
        })
    }

    private fun body(value: String): TextView = text(value, 13f, Nihil.MUTED, fonts.sans).apply {
        setLineSpacing(dp(3f), 1f)
    }

    private fun chevron(): View = android.widget.ImageView(this).apply {
        setImageDrawable(LineIcon(LineIcon.Shape.CHEVRON, Nihil.DIM))
        layoutParams = llp(dp(20), dp(20))
    }

    private fun switchRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = LedSwitch(this)
        toggle.set(value)
        toggle.onChange = onChange
        return row(title, sub?.let { body(it) }, toggle) { toggle.performClick() }
    }

    /** Title (and subtitle) on the left, a control on the right; the whole row is the target. */
    private fun row(title: String, sub: TextView?, trailing: View?, onClick: (() -> Unit)?): LinearLayout {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 17f, Nihil.INK, fonts.sans).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (sub != null) addView(sub, llp(MATCH, WRAP).apply { topMargin = dp(5) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(0, dp(12), 0, dp(12))
            addView(texts, llp(0, WRAP, 1f))
            if (trailing != null) {
                val params = trailing.layoutParams as? LinearLayout.LayoutParams ?: llp(WRAP, WRAP)
                params.marginStart = dp(16)
                addView(trailing, params)
            }
            if (onClick != null) {
                background = ripple(dp(14f))
                setOnClickListener { onClick() }
            }
        }
    }

    private companion object {
        const val REQUEST_HOME = 7
    }
}
