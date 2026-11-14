package com.nihilshell.launcher.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue

object Nihil {
    const val VOID = 0xFF000000.toInt()
    const val SURFACE = 0xFF0E0E0E.toInt()
    const val RAISED = 0xFF1A1A1A.toInt()
    const val LINE = 0xFF2A2A2A.toInt()
    const val OFF = 0xFF333333.toInt()
    const val MUTED = 0xFF7A7A7A.toInt()
    const val DIM = 0xFFA3A3A3.toInt()
    const val INK = 0xFFF2F2F2.toInt()
    const val SIGNAL = 0xFFD71921.toInt()

    const val FAST = 140L
    const val NORMAL = 240L
    const val DRAWER = 320L
    const val SLOW = 480L

    const val TABLET_DP = 600

    fun reveal(progress: Float): Float = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)

    @Volatile
    private var loaded: Fonts? = null

    fun fonts(context: Context): Fonts =
        loaded ?: synchronized(this) { loaded ?: Fonts(context.applicationContext).also { loaded = it } }

    class Fonts(context: Context) {
        val dot: Typeface = load(context, "Doto-Bold.ttf")
        val sans: Typeface = load(context, "SpaceGrotesk-Regular.ttf")
        val sansMedium: Typeface = load(context, "SpaceGrotesk-Medium.ttf")
        val mono: Typeface = load(context, "SpaceMono-Regular.ttf")
        val monoBold: Typeface = load(context, "SpaceMono-Bold.ttf")

        private fun load(context: Context, name: String): Typeface =
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$name") }.getOrDefault(Typeface.DEFAULT)
    }
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dp(value: Int): Int = (dp(value.toFloat()) + 0.5f).toInt()

val Context.isTablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= Nihil.TABLET_DP
