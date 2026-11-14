package com.nihilshell.launcher.system

import android.os.Handler
import android.os.Looper

object Glyph {
    enum class Pulse {
        NOTIFY,
        SIGNAL,
        CHARGE,
    }

    interface Listener {
        fun onUnread(packages: List<String>)
        fun onPulse(pulse: Pulse)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    var unread: List<String> = emptyList()
        private set

    @Volatile
    var homeShowing = false

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
