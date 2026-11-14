package com.nihilshell.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

fun Context.text(
    value: CharSequence = "",
    size: Float = 16f,
    color: Int = Nihil.INK,
    face: Typeface = Nihil.fonts(this).sans,
    spacing: Float = 0f,
): TextView = TextView(this).apply {
    text = value
    typeface = face
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    letterSpacing = spacing
    includeFontPadding = false
}

fun Context.mono(value: CharSequence = "", size: Float = 11f, color: Int = Nihil.MUTED): TextView =
    text(value, size, color, Nihil.fonts(this).mono)

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GlassDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun ripple(radius: Float, content: Drawable? = null): RippleDrawable =
    RippleDrawable(
        ColorStateList.valueOf(0x24FFFFFF),
        content,
        GradientDrawable().apply {
            setColor(Nihil.INK)
            cornerRadius = radius
        },
    )

fun View.pad(all: Int) = setPadding(all, all, all, all)

fun lp(width: Int, height: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(width, height, gravity)

fun llp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class KeyButton(context: Context, label: CharSequence, accent: Boolean = false, danger: Boolean = false) : TextView(context) {
    init {
        text = label
        typeface = Nihil.fonts(context).mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        includeFontPadding = false
        gravity = Gravity.CENTER
        minHeight = context.dp(38)
        minWidth = context.dp(38)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        val radius = context.dp(8f)
        val (fill, edge, ink) = when {
            danger -> Triple(Nihil.SIGNAL, Nihil.SIGNAL, Nihil.INK)
            accent -> Triple(Nihil.INK, Nihil.INK, Nihil.VOID)
            else -> Triple(Nihil.SURFACE, Nihil.LINE, Nihil.DIM)
        }
        setTextColor(ink)
        background = ripple(radius, rounded(fill, radius, context.dp(1), edge))
        isClickable = true
        isFocusable = true
    }
}

class Toggle(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var position = 0f
    private var animator: ValueAnimator? = null

    var onChange: ((Boolean) -> Unit)? = null

    var checked = false
        private set

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { set(!checked, animate = true, notify = true) }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = checked
            }
        }
    }

    fun set(value: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (value == checked && animator == null) {
            position = if (value) 1f else 0f
            invalidate()
            return
        }
        checked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate) {
            position = target
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(position, target).apply {
                duration = Nihil.FAST
                interpolator = EASE
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) animator = null
                    }
                })
                start()
            }
        }
        if (notify) onChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dp(40), context.dp(24))

    override fun onDraw(canvas: Canvas) {
        val edge = context.dp(1f)
        rect.set(edge / 2, edge / 2, width - edge / 2, height - edge / 2)
        val r = rect.height() / 2
        track.style = Paint.Style.FILL
        track.color = withAlpha(Nihil.INK, position)
        canvas.drawRoundRect(rect, r, r, track)
        track.style = Paint.Style.STROKE
        track.strokeWidth = edge
        track.color = blend(Nihil.OFF, Nihil.INK, position)
        canvas.drawRoundRect(rect, r, r, track)
        val dotR = context.dp(7f)
        val inset = context.dp(5f) + dotR
        val cx = inset + (width - 2 * inset) * position
        dot.color = blend(Nihil.MUTED, Nihil.VOID, position)
        canvas.drawCircle(cx, height / 2f, dotR, dot)
    }
}

class Choice(
    context: Context,
    options: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {
    private val items = ArrayList<TextView>()

    var selected = selected
        private set

    init {
        orientation = HORIZONTAL
        options.forEachIndexed { index, label ->
            val item = context.text(label, 14f, Nihil.MUTED).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(36)
                setOnClickListener { select(index, notify = true) }
            }
            items += item
            addView(item, llp(WRAP, context.dp(36)).apply { if (index > 0) marginStart = context.dp(20) })
        }
        select(selected, notify = false)
    }

    fun select(index: Int, notify: Boolean) {
        selected = index
        items.forEachIndexed { i, item ->
            val on = i == index
            item.setTextColor(if (on) Nihil.INK else Nihil.MUTED)
            item.isSelected = on
            item.background = if (on) Underline(context.dp(2f)) else null
        }
        if (notify) onSelect(index)
    }

    private class Underline(private val thickness: Float) : Drawable() {
        private val paint = Paint().apply { color = Nihil.INK }
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b.left.toFloat(), b.bottom - thickness, b.right.toFloat(), b.bottom.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}

class Notice(context: Context) : LinearLayout(context) {
    private val message = context.text("", 14f, Nihil.INK)
    private val hide = Runnable { animate().alpha(0f).translationY(context.dp(8f)).setDuration(Nihil.NORMAL).start() }

    init {
        orientation = VERTICAL
        setPadding(context.dp(16), context.dp(12), context.dp(18), context.dp(13))
        background = rounded(Nihil.VOID, context.dp(16f), context.dp(1), Nihil.LINE)
        addView(context.mono(context.getString(com.nihilshell.launcher.R.string.app_name), 11f, Nihil.MUTED))
        addView(message, llp(WRAP, WRAP).apply { topMargin = context.dp(6) })
        alpha = 0f
        isClickable = false
    }

    fun show(text: CharSequence) {
        message.text = text
        removeCallbacks(hide)
        translationY = context.dp(8f)
        animate().alpha(1f).translationY(0f).setDuration(Nihil.FAST).setInterpolator(EASE).start()
        postDelayed(hide, 2200)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

fun Context.rule(): View = View(this).apply { background = ColorDrawable(withAlpha(Nihil.LINE, 0.6f)) }

fun Context.keycap(key: String): TextView = mono(key, 10f, Nihil.DIM).apply {
    gravity = Gravity.CENTER
    minWidth = dp(22)
    minHeight = dp(22)
    setPadding(dp(6), 0, dp(6), 0)
    background = rounded(Nihil.SURFACE, dp(5f), dp(1), Nihil.LINE)
}

fun blend(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * k).toInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun withAlpha(color: Int, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00FFFFFF)
