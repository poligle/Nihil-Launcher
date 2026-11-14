package com.nihilshell.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

class Workspaces(context: Context) : ViewGroup(context) {
    var onPage: ((Int) -> Unit)? = null

    var page = 0
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var startScroll = 0
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val exact = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) getChildAt(i).measure(exact(w), exact(h))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (animator == null && !dragging) scrollTo(page * w, 0)
    }

    fun show(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != page
        page = target
        animator?.cancel()
        val to = target * width
        if (!animate || width == 0) {
            scrollTo(to, 0)
        } else {
            animator = ValueAnimator.ofInt(scrollX, to).apply {
                duration = Nihil.NORMAL
                interpolator = EASE
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
        if (changed) onPage?.invoke(target)
    }

    fun beginDrag() {
        animator?.cancel()
        animator = null
        startScroll = scrollX
    }

    fun dragTo(dx: Float) {
        val max = (childCount - 1).coerceAtLeast(0) * width
        scrollTo((startScroll - dx).roundToInt().coerceIn(0, max), 0)
    }

    fun endDrag(velocityX: Float) {
        val nearest = if (width > 0) (scrollX.toFloat() / width).roundToInt() else page
        show(
            when {
                velocityX < -fling -> page + 1
                velocityX > fling -> page - 1
                else -> nearest
            },
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                track(ev, reset = true)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!dragging) begin(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) begin(ev)
                if (dragging) {
                    val max = (childCount - 1) * width
                    scrollTo((startScroll - (ev.x - downX)).roundToInt().coerceIn(0, max), 0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val tracker = velocity
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val nearest = if (width > 0) (scrollX.toFloat() / width).roundToInt() else page
                    show(
                        when {
                            vx < -fling -> page + 1
                            vx > fling -> page - 1
                            else -> nearest
                        },
                    )
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun begin(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy)) {
            dragging = true
            animator?.cancel()
            animator = null
            startScroll = scrollX
            downX = ev.x
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun track(ev: MotionEvent, reset: Boolean = false) {
        if (reset) {
            velocity?.recycle()
            velocity = null
        }
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.addMovement(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollTo(page * w, 0)
    }
}

class PageDots(context: Context, names: List<String>, private val onPick: (Int) -> Unit) : android.widget.LinearLayout(context) {
    private val dots = ArrayList<View>()

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        names.forEachIndexed { index, name ->
            val dot = View(context).apply {
                contentDescription = name
                setOnClickListener { onPick(index) }
            }
            dots += dot
            addView(dot, llp(context.dp(22), context.dp(22)))
        }
        select(0)
    }

    fun select(index: Int) {
        dots.forEachIndexed { i, dot ->
            val size = context.dp(if (i == index) 8f else 6f)
            dot.background = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (i == index) Nihil.INK else Nihil.OFF
                }
                override fun draw(canvas: android.graphics.Canvas) {
                    canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), size / 2, paint)
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
            dot.isSelected = i == index
        }
    }
}

class SwipeBar(context: Context, private val pages: () -> Workspaces?) : android.widget.LinearLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        follow(ev)
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        follow(ev)
        return true
    }

    private fun follow(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.rawX - downX
                if (!dragging && abs(dx) > slop && abs(dx) > abs(ev.rawY - downY)) {
                    dragging = true
                    downX = ev.rawX
                    pages()?.beginDrag()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) pages()?.dragTo(ev.rawX - downX)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    pages()?.endDrag(tracker?.xVelocity ?: 0f)
                }
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
    }
}
