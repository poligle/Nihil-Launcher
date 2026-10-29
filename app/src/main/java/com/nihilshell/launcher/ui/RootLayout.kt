package com.nihilshell.launcher.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The home screen's gestures, on top of whatever the children do:
 *   swipe up      the drawer follows the finger
 *   swipe down    the notification shade (or, with the drawer open and its
 *                 list at the top, the drawer closes)
 *   double tap    lock, if enabled
 *   long press    the home menu (wallpaper, settings)
 * A touch during the drawer's animation catches it mid-way.
 */
class RootLayout(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        /** 0 = home, 1 = drawer open. */
        val progress: Float
        /** Distance the drawer travels between closed and open. */
        val travel: Float
        /** Whether a downward drag starting at [y] should close the drawer. */
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
    /** A gesture already used (swipe down): ignore the rest of it. */
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

    /** Minimum fling speed, in px/s, for a release to decide the direction. */
    val flingVelocity: Float get() = fling

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(ev)
                // Caught in the middle of an animation: take it from here.
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
        if (onHome && !dragging && !spent) detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            // Already dragging if the down caught an animation in onInterceptTouchEvent.
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

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        dragging = false
        spent = false
    }

    private fun wantsDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < slop || abs(dy) < abs(dx) * 1.2f) return false
        val p = listener.progress
        return when {
            p <= 0.001f -> true
            p >= 0.999f -> dy > 0 && listener.canCloseFrom(downY)
            else -> true
        }
    }

    private fun start(ev: MotionEvent) {
        val p = listener.progress
        if (p <= 0.001f && ev.y > downY) {
            // Swipe down on the home screen: the notification shade.
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
