package com.nihilshell.launcher.system

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class GlyphListener : NotificationListenerService() {
    private val seen = HashSet<String>()

    override fun onListenerConnected() {
        Glyph.setConnected(true)
        seen.clear()
        current().forEach { seen += it.key }
        publish()
    }

    override fun onListenerDisconnected() {
        seen.clear()
        Glyph.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val fresh = seen.add(sbn.key)
        if (counts(sbn) && alerts(sbn, rankingMap, fresh)) {
            val category = sbn.notification.category
            val signal = category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM
            Glyph.pulse(if (signal) Glyph.Pulse.SIGNAL else Glyph.Pulse.NOTIFY)
        }
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        seen.remove(sbn.key)
        publish()
    }

    private fun current(): List<StatusBarNotification> =
        runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()

    private fun publish() {
        val ranking = runCatching { currentRanking }.getOrNull()
        val packages = current()
            .filter { counts(it) && (ranking == null || !isSilent(it, ranking)) }
            .sortedByDescending { it.postTime }
            .map { it.packageName }
            .distinct()
        Glyph.publish(packages)
    }

    private fun counts(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        return sbn.packageName != packageName &&
            flags and Notification.FLAG_ONGOING_EVENT == 0 &&
            flags and Notification.FLAG_FOREGROUND_SERVICE == 0
    }

    private fun isSilent(sbn: StatusBarNotification, map: RankingMap): Boolean {
        val r = Ranking()
        if (!map.getRanking(sbn.key, r)) return false
        return r.isAmbient || r.importance < NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun alerts(sbn: StatusBarNotification, map: RankingMap, fresh: Boolean): Boolean {
        val again = sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0
        if (!fresh && !again) return false
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val r = Ranking()
        if (!map.getRanking(sbn.key, r)) return fresh
        return r.matchesInterruptionFilter() && !r.isAmbient && r.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }
}
