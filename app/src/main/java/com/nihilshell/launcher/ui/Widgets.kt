package com.nihilshell.launcher.ui

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
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The easing of every NIHIL animation (the desktop's "emphasized" curve). */
val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

/** A TextView in one of the NIHIL voices. */
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

/** Metadata: Space Mono, upper case, spaced out. */
fun Context.mono(value: CharSequence = "", size: Float = 11f, color: Int = Nihil.MUTED): TextView =
    text(value.toString().uppercase(), size, color, Nihil.fonts(this).mono, 0.08f).apply { isAllCaps = true }

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** A press highlight in the shape of [radius] (no colour, only a soft white). */
fun ripple(radius: Float, content: Drawable? = null): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), content, rounded(Nihil.INK, radius))

fun View.pad(all: Int) = setPadding(all, all, all, all)

fun lp(width: Int, height: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(width, height, gravity)

fun llp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** A pill button: outlined, Space Mono in capitals. Filled with ink when [filled]. */
class Pill(context: Context, label: CharSequence, filled: Boolean = false) : TextView(context) {
    init {
        text = label.toString().uppercase()
        typeface = Nihil.fonts(context).mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        minHeight = context.dp(40)
        setPadding(context.dp(18), 0, context.dp(18), 0)
        val radius = context.dp(20f)
        if (filled) {
            setTextColor(Nihil.VOID)
            background = ripple(radius, rounded(Nihil.INK, radius))
        } else {
            setTextColor(Nihil.INK)
            background = ripple(radius, rounded(0, radius, context.dp(1), Nihil.LINE))
        }
        isClickable = true
        isFocusable = true
    }
}

/**
 * A switch as an LED: an outlined track with a dot that slides across and
 * lights up when on.
 */
class LedSwitch(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.2f)
    }
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
                duration = Nihil.NORMAL
                interpolator = EASE
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (notify) onChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dp(46), context.dp(26))

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth / 2
        rect.set(inset, inset, width - inset, height - inset)
        val r = rect.height() / 2
        track.color = blend(Nihil.LINE, Nihil.DIM, position)
        canvas.drawRoundRect(rect, r, r, track)
        val dotR = r * (0.42f + 0.12f * position)
        val cx = rect.left + r + (rect.width() - 2 * r) * position
        dot.color = blend(Nihil.MUTED, Nihil.INK, position)
        canvas.drawCircle(cx, rect.centerY(), dotR, dot)
    }
}

/** Options in a row; the chosen one is an ink pill. */
class Segmented(
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
        val radius = context.dp(16f)
        background = rounded(0, radius + context.dp(3), context.dp(1), Nihil.LINE)
        pad(context.dp(3))
        options.forEachIndexed { index, label ->
            val item = context.mono(label, 11f, Nihil.DIM).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(12), 0, context.dp(12), 0)
                minHeight = context.dp(30)
                setOnClickListener { select(index, notify = true) }
            }
            items += item
            addView(item, llp(WRAP, context.dp(30)))
        }
        select(selected, notify = false)
    }

    fun select(index: Int, notify: Boolean) {
        selected = index
        val radius = context.dp(15f)
        items.forEachIndexed { i, item ->
            val on = i == index
            item.setTextColor(if (on) Nihil.VOID else Nihil.DIM)
            item.background = if (on) rounded(Nihil.INK, radius) else ripple(radius)
        }
        if (notify) onSelect(index)
    }
}

/** A short message at the bottom of the screen, in the NIHIL voice instead of a toast. */
class Notice(context: Context) : TextView(context) {
    private val hide = Runnable { animate().alpha(0f).translationY(context.dp(8f)).setDuration(Nihil.NORMAL).start() }

    init {
        typeface = Nihil.fonts(context).mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.06f
        setTextColor(Nihil.INK)
        gravity = Gravity.CENTER
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        background = rounded(Nihil.VOID, context.dp(20f), context.dp(1), Nihil.LINE)
        alpha = 0f
        isClickable = false
    }

    fun show(message: CharSequence) {
        text = message.toString().uppercase()
        removeCallbacks(hide)
        translationY = context.dp(8f)
        animate().alpha(1f).translationY(0f).setDuration(Nihil.FAST).setInterpolator(EASE).start()
        postDelayed(hide, 1800)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

/** A thin horizontal rule. */
fun Context.rule(): View = View(this).apply { background = ColorDrawable(Nihil.LINE) }

fun blend(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * k).toInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun withAlpha(color: Int, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00FFFFFF)
