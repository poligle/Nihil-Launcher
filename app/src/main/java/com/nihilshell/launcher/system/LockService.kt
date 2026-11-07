package com.nihilshell.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.nihilshell.launcher.core.Prefs

/**
 * An accessibility service that reads nothing: it locks the screen (double
 * tap on the home screen), pulls down the notification shade as a fallback,
 * and draws the Glyph light round the screen while NIHIL is not on it
 * (HaloOverlay), as the desktop's frame lights over every window.
 */
class LockService : AccessibilityService(), Glyph.Listener {
    private var overlay: HaloOverlay? = null
    private var receiving = false

    // The charger going in with another app on screen: the light rises there.
    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onPulse(Glyph.Pulse.CHARGE)
    }

    override fun onServiceConnected() {
        instance = this
        Glyph.listen(this)
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(power, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(power, filter)
        }
        receiving = true
    }

    private fun stop() {
        instance = null
        Glyph.unlisten(this)
        if (receiving) runCatching { unregisterReceiver(power) }
        receiving = false
        overlay?.remove()
        overlay = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // --- Glyph.Listener: the home screen draws its own light; here, the rest.

    override fun onUnread(packages: List<String>) = Unit

    override fun onPulse(pulse: Glyph.Pulse) {
        if (Glyph.homeShowing || !Prefs.get(this).glyph) return
        // With the screen off there is nothing to light.
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        (overlay ?: HaloOverlay(this).also { overlay = it }).show(pulse)
    }

    companion object {
        @Volatile
        private var instance: LockService? = null

        val running: Boolean get() = instance != null

        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        fun notifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true
    }
}
