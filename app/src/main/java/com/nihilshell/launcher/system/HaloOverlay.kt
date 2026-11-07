package com.nihilshell.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import com.nihilshell.launcher.ui.Halo

/**
 * The Glyph light over other apps: while NIHIL is not on screen, the light
 * runs round the screen's own edge, as the desktop's runs round its frame.
 * A window of the accessibility service's (it needs no other permission),
 * there only while the light is: it takes no touch and no keys, and goes
 * as soon as the light has gone out.
 */
class HaloOverlay(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var ring: Ring? = null

    fun show(pulse: Glyph.Pulse) {
        val view = ring ?: add() ?: return
        view.halo.play(pulse) { remove() }
    }

    fun remove() {
        val view = ring ?: return
        ring = null
        view.halo.cancel()
        runCatching { windows?.removeView(view) }
    }

    private fun add(): Ring? {
        val manager = windows ?: return null
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "NIHIL Glyph light"
        }
        val view = Ring(service)
        return runCatching { manager.addView(view, params) }.map { view.also { ring = it } }.getOrNull()
    }

    /** The screen's edge, a little inside it, following its rounded corners. */
    private class Ring(context: Context) : View(context) {
        val halo = Halo(this)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutRing()

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            layoutRing()
        }

        private fun layoutRing() {
            if (width == 0 || height == 0) return
            val inset = 2.5f * resources.displayMetrics.density
            val corner = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
            halo.setRing(RectF(inset, inset, width - inset, height - inset), (corner - inset).coerceAtLeast(0f))
        }

        override fun onDraw(canvas: Canvas) = halo.draw(canvas)
    }
}
