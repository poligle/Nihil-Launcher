package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ScrollView
import com.nihilshell.launcher.R
import com.nihilshell.launcher.core.Entry
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.IconStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen, inside the frame's card. At the top, a card like the
 * desktop's clock widget (home/shell/background/Desktop.qml): the time in
 * dots, the day and the date, and under a hairline the battery as LEDs, the
 * next alarm and the apps with something unread. Favourites at the bottom,
 * where the thumb is.
 */
class HomeView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun launch(entry: Entry, from: View)
        fun menu(entry: Entry, from: View)
        fun openShade()
        /** The favourites in this order now (their keys), after one was moved. */
        fun orderFavorites(keys: List<String>)
        fun closeMenu()
    }

    private val fonts = Nihil.fonts(context)
    private val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val clock = context.text("", 68f, Nihil.INK, fonts.dot).apply {
        gravity = Gravity.CENTER
        maxLines = 1
    }
    private val day = context.mono(size = 12f, color = Nihil.DIM)
    private val date = context.text("", 14f, Nihil.MUTED)
    private val gauge = LedGauge(context)
    private val battery = context.mono(size = 11f, color = Nihil.DIM)
    private val alarm = context.mono(size = 11f, color = Nihil.DIM)
    private val unread = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val unreadMore = context.mono(size = 11f, color = Nihil.DIM)
    // The favourites: a list that can be put in order by holding one, scrolling under the
    // clock's card when there are more than fit, sitting at the bottom when there are few.
    private val favorites = ReorderGrid(context, 1, context.dp(52))
    private val favoritesHolder = FrameLayout(context)
    // They fade where they meet the card, and only there. The home screen lets the clock's
    // module reach beyond its bounds; the list stays in its own, so it never goes over it.
    private val favoritesScroll = object : ScrollView(context) {
        override fun getBottomFadingEdgeStrength(): Float = 0f
    }.apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(context.dp(56))
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
    }
    private var shownKeys: List<String> = emptyList()
    private val empty = context.mono(context.getString(R.string.favorites_empty), 11f, Nihil.DIM)

    private val scrim = Paint()
    // The shade behind the favourites, made again only when the card changes.
    private var bottomShade: LinearGradient? = null
    private var shadeFor = ""
    private var dim = false
    private var bandTop = 0
    private var bandBottom = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
        // The clock's module reaches a little beyond its bounds.
        header.clipChildren = false
        header.clipToPadding = false

        card.background = rounded(Nihil.VOID, context.dp(22f), context.dp(1), Nihil.LINE)
        card.setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
        card.addView(clock, llp(MATCH, WRAP))
        card.addView(day, llp(WRAP, WRAP).apply { topMargin = context.dp(6) })
        card.addView(date, llp(WRAP, WRAP).apply { topMargin = context.dp(4) })
        card.addView(context.rule(), llp(MATCH, context.dp(1)).apply {
            topMargin = context.dp(16)
            bottomMargin = context.dp(12)
        })

        // The battery on the left, the alarm on the right; the unread apps under them.
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gauge, llp(WRAP, WRAP))
            addView(battery, llp(0, WRAP, 1f).apply { marginStart = context.dp(10) })
            addView(alarm, llp(WRAP, WRAP).apply { marginStart = context.dp(10) })
        }
        card.addView(status, llp(MATCH, WRAP))

        unread.addView(unreadMore)
        unread.setOnClickListener { host.openShade() }
        unread.background = ripple(context.dp(12f))
        unread.setPadding(0, context.dp(6), context.dp(6), context.dp(2))
        card.addView(unread, llp(MATCH, WRAP).apply { topMargin = context.dp(8) })

        header.addView(card, llp(MATCH, WRAP))
        addView(header, lp(MATCH, WRAP, Gravity.TOP))
        favoritesHolder.addView(favorites, lp(MATCH, WRAP, Gravity.BOTTOM))
        favoritesScroll.addView(favoritesHolder, ViewGroup.LayoutParams(MATCH, WRAP))
        addView(favoritesScroll, lp(MATCH, MATCH))
        favorites.onReorder = { from, to ->
            val keys = shownKeys.toMutableList()
            keys.add(to, keys.removeAt(from))
            shownKeys = keys
            host.orderFavorites(keys)
        }
        addView(empty, lp(MATCH, WRAP, Gravity.BOTTOM))

        empty.setShadowLayer(context.dp(6f), 0f, 0f, 0x99000000.toInt())
        updateClock(animate = false)
    }

    /** The card's edges (frame thickness) so content sits inside it. */
    fun setCard(left: Int, top: Int, right: Int, bottom: Int) {
        bandTop = top
        bandBottom = bottom
        val side = context.dp(24)
        header.setPadding(left + context.dp(14), top + context.dp(14), right + context.dp(14), 0)
        favoritesHolder.setPadding(left + side - context.dp(8), 0, right + side, bottom + context.dp(22))
        empty.setPadding(left + side, 0, right + side, bottom + context.dp(34))
        invalidate()
    }

    fun setDim(value: Boolean) {
        dim = value
        invalidate()
    }

    fun updateClock(animate: Boolean) {
        val now = Date()
        val is24 = DateFormat.is24HourFormat(context)
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.ROOT).format(now)
        if (clock.text.toString() != time) {
            clock.text = time
            if (animate) {
                clock.alpha = 0.4f
                clock.animate().alpha(1f).setDuration(Nihil.NORMAL).setInterpolator(EASE).start()
            }
        }
        val locale = Locale.getDefault()
        day.text = SimpleDateFormat("EEEE", locale).format(now).replaceFirstChar { it.titlecase(locale) }
        date.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale).format(now)
    }

    fun updateBattery(level: Int, charging: Boolean) {
        gauge.set(level, charging)
        val percent = context.getString(R.string.battery_percent, level)
        battery.text = if (charging) "$percent, ${context.getString(R.string.battery_charging)}" else percent
        battery.setTextColor(if (gauge.low) Nihil.SIGNAL else Nihil.DIM)
    }

    fun updateAlarm(text: String?) {
        alarm.visibility = if (text == null) View.GONE else View.VISIBLE
        alarm.text = text.orEmpty()
    }

    fun updateUnread(packages: List<String>, style: IconStyle) {
        while (unread.childCount > 1) unread.removeViewAt(0)
        val apps = packages.mapNotNull { Apps.byPackage(it) }.distinctBy { it.packageName }
        val size = context.dp(18)
        apps.take(MAX_UNREAD).forEachIndexed { index, entry ->
            val icon = ImageView(context)
            icon.bindIcon(entry, size, if (style == IconStyle.COLOR) IconStyle.COLOR else IconStyle.MONO)
            unread.addView(icon, index, llp(size, size).apply { marginEnd = context.dp(10) })
        }
        val extra = apps.size - MAX_UNREAD
        unreadMore.text = if (extra > 0) "+$extra" else ""
        unreadMore.visibility = if (extra > 0) View.VISIBLE else View.GONE
        unread.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        unread.contentDescription = apps.joinToString { it.label }
    }

    // Below the clock's card, wherever it ends: the favourites never go under it.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        header.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        (favoritesScroll.layoutParams as? LayoutParams)?.topMargin = header.measuredHeight + context.dp(10)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Whether a vertical drag from (rawX, rawY) scrolls the favourites rather than opening the drawer. */
    fun favoritesScrollAt(rawX: Float, rawY: Float, dy: Float): Boolean {
        val at = IntArray(2)
        favoritesScroll.getLocationOnScreen(at)
        val inside = rawX >= at[0] && rawX < at[0] + favoritesScroll.width && rawY >= at[1] && rawY < at[1] + favoritesScroll.height
        return inside && favoritesScroll.canScrollVertically(if (dy < 0) 1 else -1)
    }

    fun setFavorites(entries: List<Entry>, icons: Boolean, style: IconStyle) {
        favorites.removeAllViews()
        shownKeys = entries.map { it.key }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val size = context.dp(24)
        for (entry in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(8), 0, context.dp(14), 0)
                background = ripple(context.dp(16f))
                contentDescription = entry.label
                setOnClickListener { host.launch(entry, this) }
                setOnLongClickListener {
                    host.menu(entry, this)
                    // Moving the finger now lifts the app and closes its menu.
                    favorites.arm(this) { host.closeMenu() }
                    true
                }
            }
            favorites.movable(row)
            if (icons) {
                val icon = ImageView(context)
                icon.bindIcon(entry, size, style)
                row.addView(icon, llp(size, size).apply { marginEnd = context.dp(16) })
            }
            val label = context.text(entry.label, 24f, Nihil.INK, fonts.sans, -0.01f).apply {
                maxLines = 1
                setShadowLayer(context.dp(8f), 0f, 0f, 0x80000000.toInt())
            }
            row.addView(label, llp(WRAP, WRAP))
            favorites.addView(row)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (dim) {
            canvas.drawColor(0x8C000000.toInt())
            return
        }
        // A soft shade behind the favourites, so any wallpaper stays readable;
        // the clock has its own card.
        val cardEnd = h - bandBottom
        val bottomStart = cardEnd - h * 0.5f
        val key = "$h $bandBottom"
        if (key != shadeFor) {
            shadeFor = key
            bottomShade = LinearGradient(0f, bottomStart, 0f, cardEnd, 0, 0xA6000000.toInt(), Shader.TileMode.CLAMP)
        }
        scrim.shader = bottomShade
        canvas.drawRect(0f, bottomStart, w, h, scrim)
    }

    private companion object {
        const val MAX_UNREAD = 6
    }
}
