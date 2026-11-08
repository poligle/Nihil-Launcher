package com.nihilshell.launcher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.hypot

/**
 * Cells in a grid, [columns] across and [cellHeight] tall (one column: a list),
 * that can be put in another order by holding one and moving it, as the
 * desktop's kanban moves its cards. A long press [arm]s a cell: moving the
 * finger then lifts it ([onStart], to close the menu the press opened), the
 * others make room, and letting go puts it there ([onReorder]). The last
 * [fixedTail] cells (an "add" key) stay where they are.
 */
class ReorderGrid(context: Context, columns: Int, private val cellHeight: Int) : ViewGroup(context) {
    var columns = columns.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    var fixedTail = 0
    var onReorder: ((from: Int, to: Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var armed: View? = null
    private var onStart: (() -> Unit)? = null
    private var dragged: View? = null
    private var from = -1
    private var target = -1
    private var downX = 0f
    private var downY = 0f
    private var grabX = 0f
    private var grabY = 0f

    /** Where the last touch went down on a cell, in screen pixels: for [arm] from a long press. */
    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    /** Makes [child] a cell that can be held and moved. */
    fun movable(child: View) {
        child.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                lastRawX = e.rawX
                lastRawY = e.rawY
            }
            false
        }
    }

    /** After a long press on [child]: moving the finger now picks it up. */
    fun arm(child: View, onStart: (() -> Unit)? = null) {
        val index = indexOfChild(child)
        if (index < 0 || index >= childCount - fixedTail) return
        armed = child
        this.onStart = onStart
        val at = IntArray(2)
        getLocationOnScreen(at)
        downX = lastRawX - at[0]
        downY = lastRawY - at[1]
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun cellWidth() = (width / columns).coerceAtLeast(1)

    private fun slotLeft(i: Int) = (i % columns) * cellWidth()

    private fun slotTop(i: Int) = (i / columns) * cellHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cell = MeasureSpec.makeMeasureSpec((width / columns).coerceAtLeast(1), MeasureSpec.EXACTLY)
        val tall = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cell, tall)
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(width, rows * cellHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = (r - l) / columns
        for (i in 0 until childCount) {
            getChildAt(i).layout(slotLeft(i), slotTop(i), slotLeft(i) + w, slotTop(i) + cellHeight)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (armed == null && dragged == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (dragged == null && hypot(ev.x - downX, ev.y - downY) > slop) begin()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragged == null) armed = null
        }
        return dragged != null
    }

    private fun begin() {
        val child = armed ?: return
        dragged = child
        from = indexOfChild(child)
        target = from
        grabX = downX - child.left
        grabY = downY - child.top
        child.translationZ = 8 * resources.displayMetrics.density
        child.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.92f).setDuration(Nihil.FAST).start()
        child.performHapticFeedback(android.view.HapticFeedbackConstants.DRAG_START)
        parent?.requestDisallowInterceptTouchEvent(true)
        onStart?.invoke()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val child = dragged ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                child.translationX = ev.x - grabX - child.left
                child.translationY = ev.y - grabY - child.top
                val slot = slotAt(ev.x, ev.y)
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

    private fun slotAt(x: Float, y: Float): Int {
        val column = (x / cellWidth()).toInt().coerceIn(0, columns - 1)
        val row = (y / cellHeight).toInt().coerceAtLeast(0)
        return (row * columns + column).coerceIn(0, (childCount - 1 - fixedTail).coerceAtLeast(0))
    }

    /** Every other cell to where it goes with [from] lifted and put down at [target]. */
    private fun makeRoom() {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === dragged) continue
            val slot = when {
                from < target && i in (from + 1)..target -> i - 1
                from > target && i in target until from -> i + 1
                else -> i
            }
            c.animate().translationX((slotLeft(slot) - c.left).toFloat()).translationY((slotTop(slot) - c.top).toFloat())
                .setDuration(Nihil.FAST).setInterpolator(EASE).start()
        }
    }

    private fun settle(commit: Boolean) {
        val child = dragged ?: return
        val to = if (commit) target else from
        val start = from
        dragged = null
        armed = null
        child.animate().translationX((slotLeft(to) - child.left).toFloat()).translationY((slotTop(to) - child.top).toFloat())
            .scaleX(1f).scaleY(1f).alpha(1f).setDuration(Nihil.FAST).setInterpolator(EASE)
            .withEndAction {
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    c.animate().cancel()
                    c.translationX = 0f
                    c.translationY = 0f
                    c.translationZ = 0f
                }
                if (to != start) {
                    val moved = getChildAt(start)
                    removeViewAt(start)
                    addView(moved, to)
                    onReorder?.invoke(start, to)
                }
            }.start()
        if (!commit && to == start) makeRoomBack()
    }

    private fun makeRoomBack() {
        for (i in 0 until childCount) getChildAt(i).animate().translationX(0f).translationY(0f).setDuration(Nihil.FAST).start()
    }
}
