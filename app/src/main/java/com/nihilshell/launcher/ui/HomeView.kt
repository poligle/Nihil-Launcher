package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.text.format.DateFormat
import android.util.TypedValue
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

class HomeView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(entry: Entry, from: View)
        fun menu(entry: Entry, from: View)
        fun openShade()
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
    private val favorites = ReorderGrid(context, 1, context.dp(52))
    private val favoritesHolder = FrameLayout(context)
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
    private var bottomShade: Shader? = null
    private var shadeFor = ""
    private var shadeLeft = 0
    private var dim = false
    private var bandTop = 0
    private var bandBottom = 0
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
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

    fun setCard(left: Int, top: Int, right: Int, bottom: Int) {
        insetLeft = left
        insetTop = top
        insetRight = right
        bandTop = top
        bandBottom = bottom
        val side = context.dp(24)
        favoritesHolder.setPadding(side - context.dp(8), 0, side, bottom + context.dp(22))
        empty.setPadding(side, 0, side, bottom + context.dp(34))
        requestLayout()
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val wide = w > MeasureSpec.getSize(heightMeasureSpec)
        val pane = minOf((w - insetLeft - insetRight - context.dp(GAP)) / 2, context.dp(PANE_MAX))
        val cardWidth = if (wide) insetLeft + pane else minOf(w, context.dp(CARD_MAX))
        val tablet = context.isTablet
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tablet) CLOCK_TABLET else CLOCK_PHONE)
        header.setPadding(
            insetLeft + context.dp(14),
            insetTop + context.dp(14),
            (if (wide) 0 else insetRight) + context.dp(14),
            0,
        )
        (header.layoutParams as? LayoutParams)?.apply {
            width = cardWidth
            gravity = if (wide) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        header.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        (favoritesScroll.layoutParams as? LayoutParams)?.apply {
            width = if (wide) pane else LayoutParams.MATCH_PARENT
            gravity = if (wide) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
            topMargin = if (wide) 0 else header.measuredHeight + context.dp(10)
            leftMargin = if (wide) 0 else insetLeft
            rightMargin = insetRight
        }
        (empty.layoutParams as? LayoutParams)?.apply {
            width = if (wide) pane else LayoutParams.MATCH_PARENT
            gravity = if (wide) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM
            leftMargin = if (wide) 0 else insetLeft
            rightMargin = insetRight
        }
        shadeLeft = if (wide) w - insetRight - pane else 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun favoritesScrollAt(rawX: Float, rawY: Float, dy: Float): Boolean {
        val at = IntArray(2)
        favoritesScroll.getLocationOnScreen(at)
        val inside = rawX >= at[0] && rawX < at[0] + favoritesScroll.width && rawY >= at[1] && rawY < at[1] + favoritesScroll.height
        return inside && favoritesScroll.canScrollVertically(if (dy < 0) 1 else -1)
    }

    fun backgroundAt(x: Float, y: Float): Boolean {
        if (contains(header, x, y)) return false
        for (i in 0 until favorites.childCount) if (contains(favorites.getChildAt(i), x, y)) return false
        return true
    }

    private fun contains(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown) return false
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return x >= at[0] && x < at[0] + view.width && y >= at[1] && y < at[1] + view.height
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
        val cardEnd = h - bandBottom
        val bottomStart = cardEnd - h * 0.5f
        val fadeFrom = if (shadeLeft > 0) shadeLeft - context.dp(56f) else 0f
        val key = "$w $h $bandBottom $shadeLeft"
        if (key != shadeFor) {
            shadeFor = key
            val rising = LinearGradient(0f, bottomStart, 0f, cardEnd, 0, 0xA6000000.toInt(), Shader.TileMode.CLAMP)
            bottomShade = if (shadeLeft <= 0) rising else ComposeShader(
                rising,
                LinearGradient(fadeFrom, 0f, shadeLeft + context.dp(8f), 0f, 0, 0xFF000000.toInt(), Shader.TileMode.CLAMP),
                PorterDuff.Mode.DST_IN,
            )
        }
        scrim.shader = bottomShade
        canvas.drawRect(fadeFrom.coerceAtLeast(0f), bottomStart, w, h, scrim)
    }

    private companion object {
        const val MAX_UNREAD = 6
        const val CARD_MAX = 560
        const val PANE_MAX = 440
        const val GAP = 16
        const val CLOCK_PHONE = 68f
        const val CLOCK_TABLET = 84f
    }
}
