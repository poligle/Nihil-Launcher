package com.nihilshell.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.SearchManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.nihilshell.launcher.core.Action
import com.nihilshell.launcher.core.ActionHost
import com.nihilshell.launcher.core.Actions
import com.nihilshell.launcher.core.AppEntry
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.Prefs
import com.nihilshell.launcher.core.Torch
import com.nihilshell.launcher.system.Access
import com.nihilshell.launcher.system.Glyph
import com.nihilshell.launcher.system.LockService
import com.nihilshell.launcher.ui.AppMenu
import com.nihilshell.launcher.ui.DrawerView
import com.nihilshell.launcher.ui.EASE
import com.nihilshell.launcher.ui.FrameView
import com.nihilshell.launcher.ui.HomeView
import com.nihilshell.launcher.ui.LineIcon
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.Notice
import com.nihilshell.launcher.ui.RootLayout
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.lp
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * NIHIL's home screen. Three layers in one window:
 *   HomeView   clock, battery, favourites (on the wallpaper)
 *   FrameView  the black frame and the Glyph light
 *   DrawerView the drawer, which rises with the frame
 */
class LauncherActivity :
    Activity(),
    HomeView.Host,
    DrawerView.Host,
    RootLayout.Listener,
    ActionHost,
    Glyph.Listener {

    private lateinit var prefs: Prefs
    private lateinit var root: RootLayout
    private lateinit var home: HomeView
    private lateinit var frame: FrameView
    private lateinit var drawer: DrawerView
    private lateinit var noticeView: Notice
    private lateinit var popup: AppMenu
    private var actions: List<Action> = emptyList()

    private var drawerProgress = 0f
    private var settle: ValueAnimator? = null
    private var bandTop = 0
    private var bandBottom = 0
    private var imeBottom = 0
    private var receiving = false
    private var charging: Boolean? = null
    private var backCallback: OnBackInvokedCallback? = null

    // --- Lifecycle -------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Apps.init(this)
        Torch.init(this)

        // Edge to edge on every version (Android 15 enforces it; 12-14 need the call).
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false

        popup = AppMenu(this)
        home = HomeView(this, this)
        frame = FrameView(this)
        drawer = DrawerView(this, this)
        noticeView = Notice(this)
        root = RootLayout(this, this).apply {
            addView(home, lp(MATCH, MATCH))
            addView(frame, lp(MATCH, MATCH))
            addView(drawer, lp(MATCH, MATCH))
            addView(noticeView, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (running.any { it.typeMask and WindowInsets.Type.ime() != 0 }) applyInsets(insets)
                return insets
            }
        })

        actions = Actions.all(this, this)
        Apps.listen(appsChanged)
        prefs.listen(prefsChanged)
        Torch.listen(torchChanged)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!)
        }

        home.setDim(prefs.dimWallpaper)
        setProgress(0f)
        refreshApps()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A single page: keep a wallpaper that is wider than the screen centred.
        runCatching {
            WallpaperManager.getInstance(this).setWallpaperOffsets(window.decorView.windowToken, 0.5f, 0.5f)
        }
    }

    override fun onStart() {
        super.onStart()
        register()
        home.updateClock(animate = false)
        updateAlarm()
        Glyph.listen(this)
    }

    override fun onResume() {
        super.onResume()
        home.updateClock(animate = false)
        updateAlarm()
    }

    override fun onStop() {
        super.onStop()
        unregister()
        Glyph.unlisten(this)
        // Coming back always lands on the home screen.
        popup.dismiss()
        settle?.cancel()
        setProgress(0f)
        drawer.closed()
        hideKeyboard()
    }

    override fun onDestroy() {
        Apps.unlisten(appsChanged)
        prefs.unlisten(prefsChanged)
        Torch.unlisten(torchChanged)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    /** The home button while NIHIL is in front: close whatever is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            popup.dismiss()
            if (drawerProgress > 0f) animateDrawer(open = false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Android 12: no OnBackInvokedDispatcher. A home screen never finishes on back.
        back()
    }

    private fun back() {
        when {
            popup.showing -> popup.dismiss()
            drawerProgress > 0f -> animateDrawer(open = false)
        }
    }

    // --- Insets and the drawer ---------------------------------------------------

    private fun applyInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val side = dp(6)
        val left = max(bars.left, side)
        val right = max(bars.right, side)
        bandTop = max(bars.top, dp(24))
        bandBottom = max(bars.bottom, dp(10))
        imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom

        // The card follows the screen's own rounded corners, a little tighter.
        val corner = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        val radius = if (corner > 0) (corner - side).toFloat().coerceIn(dp(18f), dp(34f)) else dp(26f)

        frame.setFrame(left, bandTop, right, bandBottom, radius)
        home.setCard(left, bandTop, right, bandBottom)
        val bottom = max(bandBottom, imeBottom)
        drawer.setInsets(bandTop, bottom)
        (noticeView.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = bottom + dp(28)
        noticeView.requestLayout()
        setProgress(drawerProgress)
    }

    private fun setProgress(value: Float) {
        drawerProgress = value.coerceIn(0f, 1f)
        frame.progress = drawerProgress
        drawer.translationY = frame.cardBottom - bandTop
        drawer.alpha = Nihil.reveal(drawerProgress)
        drawer.visibility = if (drawerProgress > 0f) View.VISIBLE else View.INVISIBLE
        home.alpha = 1f - (drawerProgress / 0.35f).coerceIn(0f, 1f)
        home.translationY = -drawerProgress * dp(36f)
        home.importantForAccessibility =
            if (drawerProgress > 0.5f) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun animateDrawer(open: Boolean, velocity: Float = 0f) {
        settle?.cancel()
        val target = if (open) 1f else 0f
        val distance = abs(target - drawerProgress)
        if (distance < 0.001f) {
            setProgress(target)
            settled(open)
            return
        }
        // A fast fling finishes sooner.
        val speed = (abs(velocity) / (frame.travel * 3f)).coerceIn(0f, 1f)
        settle = ValueAnimator.ofFloat(drawerProgress, target).apply {
            duration = (Nihil.DRAWER * distance * (1f - 0.45f * speed)).toLong().coerceAtLeast(120L)
            interpolator = EASE
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (settle === animation) settle = null
                    if (!cancelled) settled(open)
                }
            })
            start()
        }
        if (!open) hideKeyboard()
    }

    private fun settled(open: Boolean) {
        if (open) {
            drawer.opened(prefs.keyboardOnOpen)
        } else {
            drawer.closed()
            hideKeyboard()
        }
    }

    // RootLayout.Listener

    override val progress: Float get() = drawerProgress

    override val travel: Float get() = frame.travel

    override fun canCloseFrom(y: Float): Boolean = drawer.canCloseFrom(y - drawer.translationY)

    override fun onGrab() {
        settle?.cancel()
        settle = null
        popup.dismiss()
        hideKeyboard()
    }

    override fun onDrag(progress: Float) = setProgress(progress)

    override fun onRelease(velocityY: Float) {
        val open = when {
            velocityY < -root.flingVelocity -> true
            velocityY > root.flingVelocity -> false
            else -> drawerProgress > 0.5f
        }
        animateDrawer(open, velocityY)
    }

    override fun onSwipeDown() {
        openShade()
    }

    override fun onDoubleTap() {
        if (prefs.doubleTapLock) lockScreen()
    }

    override fun onLongPress(x: Float, y: Float) {
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        popup.show(
            anchor = root,
            title = getString(R.string.app_name),
            point = x.toInt() to y.toInt(),
            items = listOf(
                AppMenu.Item(LineIcon.Shape.IMAGE, getString(R.string.act_wallpaper)) { openStudio() },
                AppMenu.Item(LineIcon.Shape.HOME, getString(R.string.menu_system_wallpaper)) {
                    start(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.menu_system_wallpaper)))
                },
                AppMenu.Item(LineIcon.Shape.SETTINGS, getString(R.string.act_nihil)) { openSettings() },
            ),
        )
    }

    // --- Apps --------------------------------------------------------------------

    private val appsChanged: () -> Unit = { refreshApps() }

    private val torchChanged: (Boolean) -> Unit = { drawer.setContent(Apps.all, prefs.hidden, actions, prefs.iconStyle) }

    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.ICONS -> {
                Apps.clearIcons()
                refreshApps()
                onUnread(Glyph.unread)
            }
            Prefs.FAVORITES, Prefs.FAVORITE_ICONS, Prefs.HIDDEN -> refreshApps()
            Prefs.DIM -> home.setDim(prefs.dimWallpaper)
            Prefs.GLYPH -> onUnread(Glyph.unread)
        }
    }

    private fun refreshApps() {
        if (Apps.loaded && !prefs.favoritesChosen) prefs.favorites = defaultFavorites()
        val favorites = prefs.favorites.mapNotNull { Apps.find(it) }
        home.setFavorites(favorites, prefs.favoriteIcons, prefs.iconStyle)
        drawer.setContent(Apps.all, prefs.hidden, actions, prefs.iconStyle)
    }

    /** First run: phone, messages, camera and browser, if they're there. */
    private fun defaultFavorites(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
        )
        return intents.mapNotNull { intent ->
            val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return@mapNotNull null
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == "android") null else Apps.byPackage(pkg)?.key
        }.distinct()
    }

    private fun launchApp(entry: AppEntry, from: View) {
        val bounds = Rect().also { from.getGlobalVisibleRect(it) }
        val options = ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.width, from.height).toBundle()
        runCatching { Apps.launch(entry, bounds, options) }
            .onFailure { notice(getString(R.string.launch_failed, entry.label)) }
    }

    private fun showAppMenu(entry: AppEntry, from: View) {
        from.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val favorites = prefs.favorites
        val index = favorites.indexOf(entry.key)
        val items = ArrayList<AppMenu.Item>()
        if (index >= 0) {
            items += AppMenu.Item(LineIcon.Shape.MINUS, getString(R.string.menu_remove_home)) { prefs.removeFavorite(entry.key) }
            if (index > 0) items += AppMenu.Item(LineIcon.Shape.UP, getString(R.string.menu_move_up)) { prefs.moveFavorite(entry.key, -1) }
            if (index < favorites.size - 1) {
                items += AppMenu.Item(LineIcon.Shape.DOWN, getString(R.string.menu_move_down)) { prefs.moveFavorite(entry.key, 1) }
            }
        } else {
            items += AppMenu.Item(LineIcon.Shape.PLUS, getString(R.string.menu_add_home)) { prefs.addFavorite(entry.key) }
        }
        if (entry.key in prefs.hidden) {
            items += AppMenu.Item(LineIcon.Shape.CHECK, getString(R.string.menu_unhide)) { prefs.unhide(entry.key) }
        } else {
            items += AppMenu.Item(LineIcon.Shape.HIDE, getString(R.string.menu_hide)) { prefs.hide(entry.key) }
        }
        items += AppMenu.Item(LineIcon.Shape.INFO, getString(R.string.menu_info)) {
            runCatching { Apps.showInfo(entry, Rect().also { from.getGlobalVisibleRect(it) }) }
        }
        if (entry.canUninstall) {
            items += AppMenu.Item(LineIcon.Shape.TRASH, getString(R.string.menu_uninstall), danger = true) {
                start(Apps.uninstallIntent(entry))
            }
        }
        popup.show(
            anchor = from,
            title = entry.label,
            items = items,
            shortcuts = Apps.shortcuts(entry),
            style = prefs.iconStyle,
            onShortcut = { shortcut: ShortcutInfo, view: View ->
                val bounds = Rect().also { view.getGlobalVisibleRect(it) }
                runCatching { Apps.startShortcut(shortcut, bounds, null) }
                    .onFailure { notice(getString(R.string.launch_failed, shortcut.shortLabel)) }
            },
        )
    }

    // HomeView.Host and DrawerView.Host

    override fun launch(entry: AppEntry, from: View) = launchApp(entry, from)

    override fun menu(entry: AppEntry, from: View) = showAppMenu(entry, from)

    override fun openShade() {
        if (!Access.expandNotifications(this)) notice(getString(R.string.shade_failed))
    }

    override fun run(action: Action) {
        val ok = Actions.run(action, this)
        if (ok && action.id == Actions.TORCH) animateDrawer(open = false)
    }

    override fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("NIHIL", text))
        notice("${getString(R.string.calc_copied)} · $text")
    }

    override fun webSearch(query: String) {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8")))
        start(web, fallback)
    }

    override fun hideKeyboard() {
        window.insetsController?.hide(WindowInsets.Type.ime())
    }

    override fun showKeyboard(view: View) {
        view.post {
            window.insetsController?.show(WindowInsets.Type.ime())
            getSystemService(InputMethodManager::class.java)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // --- ActionHost --------------------------------------------------------------

    override val context: Context get() = this

    override fun start(vararg intents: Intent): Boolean {
        val ok = Access.startFirst(this, intents.toList())
        if (!ok) notice(getString(R.string.open_failed))
        return ok
    }

    override fun lockScreen() {
        if (LockService.lock()) return
        notice(getString(R.string.lock_needs_service))
        Access.startFirst(this, listOf(Access.lockServiceIntent()))
    }

    override fun openStudio() {
        startActivity(Intent(this, WallpaperActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun notice(text: String) = noticeView.show(text)

    override fun nextAlarmText(): String? {
        val next = getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val inMs = next.triggerTime - System.currentTimeMillis()
        val skeleton = (if (DateFormat.is24HourFormat(this)) "Hm" else "hm").let { if (inMs > DAY) "EEE$it" else it }
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return SimpleDateFormat(pattern, Locale.getDefault()).format(next.triggerTime).replace(".", "")
    }

    // --- Glyph ---------------------------------------------------------------------

    override fun onUnread(packages: List<String>) {
        home.updateUnread(if (prefs.glyph) packages else emptyList(), prefs.iconStyle)
    }

    override fun onPulse(pulse: Glyph.Pulse) {
        if (prefs.glyph && hasWindowFocus() && drawerProgress == 0f) frame.pulse(pulse)
    }

    // --- Time, battery, alarm ------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK -> home.updateClock(animate = true)
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> {
                    home.updateClock(animate = true)
                    updateAlarm()
                }
                Intent.ACTION_BATTERY_CHANGED -> battery(intent)
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> updateAlarm()
            }
        }
    }

    private fun register() {
        if (receiving) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        // System broadcasts only; nothing else needs to reach this receiver.
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        receiving = true
        sticky?.let { battery(it) }
    }

    private fun unregister() {
        if (!receiving) return
        runCatching { unregisterReceiver(receiver) }
        receiving = false
        charging = null
    }

    private fun battery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        // The charger going in while NIHIL is on screen: light rises from the bottom.
        if (charging == false && plugged && prefs.glyph && hasWindowFocus() && drawerProgress == 0f) frame.pulse(Glyph.Pulse.CHARGE)
        charging = plugged
        home.updateBattery(level * 100 / scale, plugged)
    }

    private fun updateAlarm() {
        home.updateAlarm(nextAlarmText()?.let { getString(R.string.alarm_next, it) })
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
