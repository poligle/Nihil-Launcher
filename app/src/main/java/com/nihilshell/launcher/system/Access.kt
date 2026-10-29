package com.nihilshell.launcher.system

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** What NIHIL may do on this phone, and where to grant it. Everything here is optional. */
object Access {

    fun isDefaultHome(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /** The system dialog that makes NIHIL the home app, or the Home settings page. */
    fun defaultHomeIntent(context: Context): Intent {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    // Notification access: the Glyph light and the unread LEDs.

    fun glyphComponent(context: Context) = ComponentName(context, GlyphListener::class.java)

    fun hasNotificationAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(glyphComponent(context)) == true

    fun notificationAccessIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, glyphComponent(context).flattenToString()),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    // Accessibility: only to lock the screen (and open the shade if reflection fails).

    fun hasLockService(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == context.packageName && s.name == LockService::class.java.name } }
    }

    fun lockServiceIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * Pulls down the notification shade. StatusBarManager.expandNotificationsPanel
     * is hidden API reached by reflection (as many launchers do) with the normal
     * EXPAND_STATUS_BAR permission. If a future Android blocks it, the
     * accessibility service, when enabled, does the same with a global action.
     */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context): Boolean {
        val viaReflection = runCatching {
            val service = context.getSystemService("statusbar") ?: error("no statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        }.isSuccess
        return viaReflection || LockService.notifications()
    }

    /** Opens the first of [intents] that the system can handle. */
    fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return true
        }
        return false
    }
}
