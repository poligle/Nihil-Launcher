package com.nihilshell.launcher.ui

import android.content.Context
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.IconStyle

/**
 * The long-press menu: a black panel with a hairline border. The app's own
 * shortcuts first (when NIHIL is the home app), then what NIHIL can do with it.
 * Destructive entries are the only red text.
 */
class AppMenu(private val context: Context) {

    class Item(
        val icon: LineIcon.Shape,
        val label: String,
        val danger: Boolean = false,
        val run: () -> Unit,
    )

    private var popup: PopupWindow? = null
    private val fonts = Nihil.fonts(context)

    val showing: Boolean get() = popup?.isShowing == true

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    /**
     * Shows the menu next to [anchor] (or at [point], in window coordinates).
     * [onShortcut] opens one of [shortcuts].
     */
    fun show(
        anchor: View,
        title: String,
        items: List<Item>,
        shortcuts: List<ShortcutInfo> = emptyList(),
        style: IconStyle = IconStyle.MONO,
        point: Pair<Int, Int>? = null,
        onShortcut: (ShortcutInfo, View) -> Unit = { _, _ -> },
    ) {
        dismiss()
        val width = context.dp(248)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Nihil.VOID, context.dp(22f), context.dp(1), Nihil.LINE)
            setPadding(context.dp(6), context.dp(8), context.dp(6), context.dp(8))
        }

        panel.addView(context.mono(title, 10f, Nihil.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(8))
        })

        if (shortcuts.isNotEmpty()) {
            for (shortcut in shortcuts) {
                val row = row(shortcut.shortLabel?.toString().orEmpty(), Nihil.INK)
                val icon = row.getChildAt(0) as ImageView
                val size = context.dp(20)
                icon.tag = shortcut.id
                val cached = Apps.shortcutIcon(shortcut, size, style) { bitmap ->
                    if (icon.tag == shortcut.id) icon.setImageBitmap(bitmap)
                }
                if (cached != null) icon.setImageBitmap(cached)
                row.setOnClickListener {
                    dismiss()
                    onShortcut(shortcut, anchor)
                }
                panel.addView(row)
            }
            panel.addView(context.rule(), llp(MATCH, context.dp(1)).apply {
                setMargins(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            })
        }

        for (item in items) {
            val row = row(item.label, if (item.danger) Nihil.SIGNAL else Nihil.INK)
            (row.getChildAt(0) as ImageView).setImageDrawable(LineIcon(item.icon, if (item.danger) Nihil.SIGNAL else Nihil.DIM))
            row.setOnClickListener {
                dismiss()
                item.run()
            }
            panel.addView(row)
        }

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = panel.measuredHeight

        // Next to the anchor: below it if there is room, otherwise above.
        val root = anchor.rootView
        val frame = Rect().also { root.getWindowVisibleDisplayFrame(it) }
        val margin = context.dp(12)
        val x: Int
        val y: Int
        if (point != null) {
            x = point.first - width / 2
            y = point.second - height / 2
        } else {
            val at = IntArray(2).also { anchor.getLocationInWindow(it) }
            x = at[0] + context.dp(8)
            val below = at[1] + anchor.height + context.dp(4)
            y = if (below + height <= frame.bottom - margin) below else at[1] - height - context.dp(4)
        }
        val px = x.coerceIn(margin, (root.width - width - margin).coerceAtLeast(margin))
        val py = y.coerceIn(frame.top + margin, (frame.bottom - height - margin).coerceAtLeast(frame.top + margin))

        val window = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            elevation = 0f
            animationStyle = 0
        }
        popup = window
        panel.alpha = 0f
        panel.scaleX = 0.96f
        panel.scaleY = 0.96f
        window.showAtLocation(root, Gravity.NO_GRAVITY, px, py)
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Nihil.FAST).setInterpolator(EASE).start()
    }

    private fun row(label: String, color: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(46)
        setPadding(context.dp(14), 0, context.dp(14), 0)
        background = ripple(context.dp(16f))
        addView(ImageView(context), llp(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(14) })
        addView(context.text(label, 15f, color, fonts.sans).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
