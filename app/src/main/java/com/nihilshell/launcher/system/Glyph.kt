package com.nihilshell.launcher.system

import android.os.Handler
import android.os.Looper

/**
 * The Glyph bus: what the notification listener (or the battery) tells the
 * home screen. Everything is delivered on the main thread.
 */
object Glyph {
    enum class Pulse {
        /** A notification that alerts: a white light runs around the frame. */
        NOTIFY,
        /** A call or an alarm: the only red light. */
        SIGNAL,
        /** The charger was connected: light rises from the bottom. */
        CHARGE,
    }

    interface Listener {
        fun onUnread(packages: List<String>)
        fun onPulse(pulse: Pulse)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    /** Apps with unread notifications, most recent first. */
    var unread: List<String> = emptyList()
        private set

    /** True while the notification listener is bound. */
    var connected = false
        private set

    fun listen(listener: Listener) {
        listeners += listener
        listener.onUnread(unread)
    }

    fun unlisten(listener: Listener) {
        listeners -= listener
    }

    fun publish(packages: List<String>) = main.post {
        if (packages != unread) {
            unread = packages
            listeners.toList().forEach { it.onUnread(packages) }
        }
    }

    fun pulse(pulse: Pulse) = main.post { listeners.toList().forEach { it.onPulse(pulse) } }

    fun setConnected(value: Boolean) = main.post {
        connected = value
        if (!value) publish(emptyList())
    }
}
