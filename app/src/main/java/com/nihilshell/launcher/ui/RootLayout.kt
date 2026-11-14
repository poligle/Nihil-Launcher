package com.nihilshell.launcher.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class RootLayout(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        val progress: Float
        val travel: Float
        fun canCloseFrom(y: Float): Boolean
        fun onGrab()
        fun onDrag(progress: Float)
        fun onRelease(velocityY: Float)
        fun onSwipeDown()
        fun onDoubleTap()
        fun onLongPress(x: Float, y: Float)
    }

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val fling = config.scaledMinimumFlingVelocity * 4f
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var startProgress = 0f
    private var dragging = false
    private var spent = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            listener.onDoubleTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!dragging && !spent) listener.onLongPress(e.x, e.y)
        }
    })

    val flingVelocity: Float get() = fling

    var backgroundAt: ((Float, Float) -> Boolean)? = null
    private var ground = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) ground = backgroundAt?.invoke(ev.rawX, ev.rawY) == true
        if (ground && listener.progress <= 0.001f && !dragging && !spent) detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(ev)
                val p = listener.progress
                if (p > 0.001f && p < 0.999f) {
                    listener.onGrab()
                    startDrag(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && !spent && wantsDrag(ev)) start(ev)
        }
        return dragging || spent
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        val onHome = listener.progress <= 0.001f
        if (onHome && !dragging && !spent && backgroundAt == null) detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!dragging) begin(ev)
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !spent && wantsDrag(ev)) start(ev)
                if (dragging) listener.onDrag((startProgress - (ev.y - startY) / listener.travel).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val t = tracker
                    t?.computeCurrentVelocity(1000)
                    listener.onRelease(t?.yVelocity ?: 0f)
                }
                end()
            }
        }
        return true
    }

    var scrollableAt: ((Float, Float, Float) -> Boolean)? = null
    private var downRawX = 0f
    private var downRawY = 0f

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        downRawX = ev.rawX
        downRawY = ev.rawY
        dragging = false
        spent = false
    }

    private fun wantsDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < slop || abs(dy) < abs(dx) * 1.2f) return false
        val p = listener.progress
        if (p <= 0.001f && scrollableAt?.invoke(downRawX, downRawY, dy) == true) return false
        return when {
            p <= 0.001f -> true
            p >= 0.999f -> dy > 0 && listener.canCloseFrom(downY)
            else -> true
        }
    }

    private fun start(ev: MotionEvent) {
        val p = listener.progress
        if (p <= 0.001f && ev.y > downY) {
            spent = true
            cancelDetector(ev)
            listener.onSwipeDown()
            return
        }
        listener.onGrab()
        startDrag(ev)
        cancelDetector(ev)
    }

    private fun startDrag(ev: MotionEvent) {
        dragging = true
        startY = ev.y
        startProgress = listener.progress
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun cancelDetector(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        detector.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
        }
        tracker?.addMovement(ev)
    }

    private fun end() {
        dragging = false
        spent = false
        tracker?.recycle()
        tracker = null
    }
}
