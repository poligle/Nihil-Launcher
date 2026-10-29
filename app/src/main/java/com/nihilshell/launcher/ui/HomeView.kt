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
import com.nihilshell.launcher.R
import com.nihilshell.launcher.core.AppEntry
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.IconStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen, inside the frame's card: the LED clock, the date, the
 * battery as LEDs, the next alarm, unread apps, and favourites at the bottom
 * where the thumb is.
 */
class HomeView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun launch(entry: AppEntry, from: View)
        fun menu(entry: AppEntry, from: View)
        fun openShade()
    }

    private val fonts = Nihil.fonts(context)
    private val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val clock = LedClock(context)
    private val date = context.mono(size = 13f, color = Nihil.INK)
    private val gauge = LedGauge(context)
    private val battery = context.mono(size = 11f, color = Nihil.DIM)
    private val alarm = context.mono(size = 11f, color = Nihil.DIM)
    private val unread = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val unreadMore = context.mono(size = 11f, color = Nihil.DIM)
    private val favorites = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = context.mono(context.getString(R.string.favorites_empty), 11f, Nihil.DIM)

    private val scrim = Paint()
    private var dim = false
    private var bandTop = 0
    private var bandBottom = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
        // The clock's module reaches a little beyond its bounds.
        header.clipChildren = false
        header.clipToPadding = false

        header.addView(clock, llp(WRAP, WRAP))
        header.addView(date, llp(WRAP, WRAP).apply { topMargin = context.dp(18) })

        val batteryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gauge, llp(WRAP, WRAP))
            addView(battery, llp(WRAP, WRAP).apply { marginStart = context.dp(10) })
        }
        header.addView(batteryRow, llp(WRAP, WRAP).apply { topMargin = context.dp(14) })
        header.addView(alarm, llp(WRAP, WRAP).apply { topMargin = context.dp(10) })

        unread.addView(unreadMore)
        unread.setOnClickListener { host.openShade() }
        unread.background = ripple(context.dp(12f))
        unread.setPadding(0, context.dp(6), context.dp(6), context.dp(6))
        header.addView(unread, llp(WRAP, WRAP).apply { topMargin = context.dp(12) })

        addView(header, lp(MATCH, WRAP, Gravity.TOP))
        addView(favorites, lp(MATCH, WRAP, Gravity.BOTTOM))
        addView(empty, lp(MATCH, WRAP, Gravity.BOTTOM))

        listOf(date, battery, alarm, unreadMore, empty).forEach { it.setShadowLayer(context.dp(6f), 0f, 0f, 0x99000000.toInt()) }
        updateClock(animate = false)
    }

    /** The card's edges (frame thickness) so content sits inside it. */
    fun setCard(left: Int, top: Int, right: Int, bottom: Int) {
        bandTop = top
        bandBottom = bottom
        val side = context.dp(24)
        header.setPadding(left + side, top + context.dp(30), right + side, 0)
        favorites.setPadding(left + side - context.dp(8), 0, right + side, bottom + context.dp(22))
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
        clock.show(SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.ROOT).format(now), animate)
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")
        date.text = SimpleDateFormat(pattern, Locale.getDefault()).format(now).replace(".", "").uppercase()
    }

    fun updateBattery(level: Int, charging: Boolean) {
        gauge.set(level, charging)
        val percent = context.getString(R.string.battery_percent, level)
        battery.text = if (charging) "$percent · ${context.getString(R.string.battery_charging)}" else percent
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
            bindIcon(icon, entry, size, if (style == IconStyle.COLOR) IconStyle.COLOR else IconStyle.MONO)
            unread.addView(icon, index, llp(size, size).apply { marginEnd = context.dp(10) })
        }
        val extra = apps.size - MAX_UNREAD
        unreadMore.text = if (extra > 0) "+$extra" else ""
        unreadMore.visibility = if (extra > 0) View.VISIBLE else View.GONE
        unread.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        unread.contentDescription = apps.joinToString { it.label }
    }

    fun setFavorites(entries: List<AppEntry>, icons: Boolean, style: IconStyle) {
        favorites.removeAllViews()
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
                    true
                }
            }
            if (icons) {
                val icon = ImageView(context)
                bindIcon(icon, entry, size, style)
                row.addView(icon, llp(size, size).apply { marginEnd = context.dp(16) })
            }
            val label = context.text(entry.label, 24f, Nihil.INK, fonts.sans, -0.01f).apply {
                maxLines = 1
                setShadowLayer(context.dp(8f), 0f, 0f, 0x80000000.toInt())
            }
            row.addView(label, llp(WRAP, WRAP))
            favorites.addView(row, llp(WRAP, context.dp(52)))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (dim) {
            canvas.drawColor(0x8C000000.toInt())
            return
        }
        // Soft shades behind the clock and the favourites, so any wallpaper stays readable.
        val topEnd = bandTop + h * 0.45f
        scrim.shader = LinearGradient(0f, bandTop.toFloat(), 0f, topEnd, 0x8C000000.toInt(), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, topEnd, scrim)
        val cardEnd = h - bandBottom
        val bottomStart = cardEnd - h * 0.5f
        scrim.shader = LinearGradient(0f, bottomStart, 0f, cardEnd, 0, 0xA6000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, bottomStart, w, h, scrim)
    }

    private fun bindIcon(view: ImageView, entry: AppEntry, size: Int, style: IconStyle) {
        view.tag = entry.key
        val cached = Apps.icon(entry, size, style) { bitmap ->
            if (view.tag == entry.key) view.setImageBitmap(bitmap)
        }
        if (cached != null) view.setImageBitmap(cached)
    }

    private companion object {
        const val MAX_UNREAD = 6
    }
}
