package com.nihilshell.launcher.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Rows one under another, [gap] apart and each as tall as it needs, that can be put in
 * another order by holding one and moving it, as ReorderGrid's cells (the tasks of a
 * column). A long press [arm]s a row: moving the finger lifts it, the others make room,
 * and letting go puts it there ([onReorder], with where it ends up).
 */
class ReorderList(context: Context, private val gap: Int) : ViewGroup(context) {
    var onReorder: ((from: Int, to: Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var armed: View? = null
    private var dragged: View? = null
    private var from = -1
    private var target = -1
    private var downY = 0f
    private var grabY = 0f
    private var lastRawY = 0f

    /** Makes [child] a row that can be held and moved. */
    fun movable(child: View) {
        child.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) lastRawY = e.rawY
            false
        }
    }

    /** After a long press on [child]: moving the finger now picks it up. */
    fun arm(child: View) {
        if (indexOfChild(child) < 0) return
        armed = child
        val at = IntArray(2)
        getLocationOnScreen(at)
        downY = lastRawY - at[1]
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val exact = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(exact, loose)
            height += child.measuredHeight + if (i > 0) gap else 0
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var top = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, top, r - l, top + child.measuredHeight)
            top += child.measuredHeight + gap
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (armed == null && dragged == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragged == null && abs(ev.y - downY) > slop) begin()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragged == null) armed = null
        }
        return dragged != null
    }

    private fun begin() {
        val child = armed ?: return
        dragged = child
        from = indexOfChild(child)
        target = from
        grabY = downY - child.top
        child.translationZ = 8 * resources.displayMetrics.density
        child.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.92f).setDuration(Nihil.FAST).start()
        child.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val child = dragged ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val top = (ev.y - grabY).coerceIn(-child.height / 2f, height - child.height / 2f)
                child.translationY = top - child.top
                val slot = slotFor(top + child.height / 2f)
                if (slot != target) {
                    target = slot
                    makeRoom()
                }
            }
            MotionEvent.ACTION_UP -> settle(commit = true)
            MotionEvent.ACTION_CANCEL -> settle(commit = false)
        }
        return true
    }

    /** Where a row dragged with its centre at [y] ends up: after every other row whose centre is above. */
    private fun slotFor(y: Float): Int {
        var slot = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c !== dragged && c.top + c.height / 2f < y) slot++
        }
        return slot
    }

    /** The rows' indices with the dragged one moved to [to]. */
    private fun order(to: Int): List<Int> {
        val rest = (0 until childCount).filter { it != from }.toMutableList()
        rest.add(to.coerceIn(0, rest.size), from)
        return rest
    }

    /** Where each row's top goes in [order], one under another. */
    private fun tops(order: List<Int>): IntArray {
        val out = IntArray(childCount)
        var top = 0
        for (i in order) {
            out[i] = top
            top += getChildAt(i).height + gap
        }
        return out
    }

    private fun makeRoom() {
        val tops = tops(order(target))
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === dragged) continue
            c.animate().translationY((tops[i] - c.top).toFloat()).setDuration(Nihil.FAST).setInterpolator(EASE).start()
        }
    }

    private fun settle(commit: Boolean) {
        val child = dragged ?: return
        val to = if (commit) target else from
        val start = from
        dragged = null
        armed = null
        val tops = tops(order(to))
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c !== child) c.animate().translationY((tops[i] - c.top).toFloat()).setDuration(Nihil.FAST).setInterpolator(EASE).start()
        }
        child.animate().translationY((tops[start] - child.top).toFloat()).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(Nihil.FAST).setInterpolator(EASE)
            .withEndAction {
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    c.animate().cancel()
                    c.translationY = 0f
                    c.translationZ = 0f
                }
                if (to != start) {
                    removeViewAt(start)
                    addView(child, to)
                    onReorder?.invoke(start, to)
                }
            }.start()
    }
}
