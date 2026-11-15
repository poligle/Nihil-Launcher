package com.nihilshell.launcher.ui

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import kotlin.math.max

@Suppress("DEPRECATION")
private const val EDGE_FLAGS =
    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

fun Window.edgeToEdge() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        @Suppress("DEPRECATION")
        setDecorFitsSystemWindows(false)
    } else {
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = decorView.systemUiVisibility or EDGE_FLAGS
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        isStatusBarContrastEnforced = false
    }
}

fun WindowInsets.barInsets(cutout: Boolean = true, ime: Boolean = false): Rect {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        var mask = WindowInsets.Type.systemBars()
        if (cutout) mask = mask or WindowInsets.Type.displayCutout()
        if (ime) mask = mask or WindowInsets.Type.ime()
        val i = getInsets(mask)
        return Rect(i.left, i.top, i.right, i.bottom)
    }
    val safe = if (cutout) displayCutout else null
    @Suppress("DEPRECATION")
    val bottom = if (ime) systemWindowInsetBottom else minOf(systemWindowInsetBottom, stableInsetBottom)
    @Suppress("DEPRECATION")
    return Rect(
        max(systemWindowInsetLeft, safe?.safeInsetLeft ?: 0),
        max(systemWindowInsetTop, safe?.safeInsetTop ?: 0),
        max(systemWindowInsetRight, safe?.safeInsetRight ?: 0),
        max(bottom, safe?.safeInsetBottom ?: 0),
    )
}

fun WindowInsets.imeBottom(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return getInsets(WindowInsets.Type.ime()).bottom
    }
    @Suppress("DEPRECATION")
    return if (systemWindowInsetBottom > stableInsetBottom) systemWindowInsetBottom else 0
}

fun WindowInsets.bottomLeftCornerRadius(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    } else {
        0
    }

fun WindowInsets.topLeftCornerRadius(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    } else {
        0
    }

fun Context.screenBounds(): Rect {
    val wm = getSystemService(WindowManager::class.java)
    if (wm != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.maximumWindowMetrics.bounds
        }
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        if (size.x > 0 && size.y > 0) return Rect(0, 0, size.x, size.y)
    }
    val dm = resources.displayMetrics
    return Rect(0, 0, dm.widthPixels, dm.heightPixels)
}

fun Window.setSystemBarsVisible(shown: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = insetsController ?: return
        if (shown) {
            controller.show(WindowInsets.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        }
    } else {
        @Suppress("DEPRECATION")
        val hidden = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = if (shown) EDGE_FLAGS else (EDGE_FLAGS or hidden)
    }
}

fun Window.hideIme(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insetsController?.hide(WindowInsets.Type.ime())
    } else {
        val token = currentFocus?.windowToken ?: decorView.windowToken
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
    }
}

fun Window.showIme(view: View) {
    view.post {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insetsController?.show(WindowInsets.Type.ime())
        }
        view.context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

fun View.watchImeInsets(onInsets: (WindowInsets) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        attachImeAnimation(this, onInsets)
    }
}

@TargetApi(Build.VERSION_CODES.R)
private fun attachImeAnimation(view: View, onInsets: (WindowInsets) -> Unit) {
    view.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
            if (running.any { it.typeMask and WindowInsets.Type.ime() != 0 }) onInsets(insets)
            return insets
        }
    })
}
