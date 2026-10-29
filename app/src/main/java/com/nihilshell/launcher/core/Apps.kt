package com.nihilshell.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import java.text.Collator
import java.util.concurrent.Executors

/** One launchable activity, in one user profile (personal, work, Secure Folder...). */
class AppEntry(val info: LauncherActivityInfo, serial: Long) {
    val component: ComponentName = info.componentName
    val packageName: String = component.packageName
    val user: UserHandle = info.user
    val label: String = info.label?.toString()?.trim().orEmpty().ifEmpty { packageName }

    /** Stable id: "package/.Activity#userSerial". */
    val key: String = "${component.flattenToShortString()}#$serial"

    /** Normalised label for [Fuzzy]. */
    val searchLabel: String = Fuzzy.normalize(label)

    val isPersonal: Boolean = user == Process.myUserHandle()

    /** System apps can only lose their updates, not be removed. */
    val canUninstall: Boolean = info.applicationInfo.flags.let {
        it and ApplicationInfo.FLAG_SYSTEM == 0 || it and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }
}

/**
 * Every launchable app, from LauncherApps across all user profiles, kept in
 * sync with installs and updates. Icons are rendered off the main thread and
 * cached per style and size.
 */
object Apps {
    private lateinit var context: Context
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val painter = Executors.newFixedThreadPool(2)
    private val listeners = LinkedHashSet<() -> Unit>()

    private val icons = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    var all: List<AppEntry> = emptyList()
        private set

    var loaded = false
        private set

    fun init(context: Context) {
        if (this::context.isInitialized) return
        this.context = context.applicationContext
        launcherApps = this.context.getSystemService(LauncherApps::class.java)
        userManager = this.context.getSystemService(UserManager::class.java)
        launcherApps.registerCallback(callback, main)
        reload()
    }

    fun listen(listener: () -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: () -> Unit) {
        listeners -= listener
    }

    fun reload() {
        loader.execute {
            val list = runCatching { load() }.getOrDefault(all)
            main.post {
                all = list
                loaded = true
                listeners.toList().forEach { it() }
            }
        }
    }

    private fun load(): List<AppEntry> {
        val collator = Collator.getInstance()
        val self = context.packageName
        val out = ArrayList<AppEntry>()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                // The launcher itself shows up once, as its settings ("NIHIL").
                if (info.componentName.packageName == self && user != Process.myUserHandle()) continue
                out += AppEntry(info, serial)
            }
        }
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return out
    }

    fun visible(hidden: Set<String>): List<AppEntry> = all.filter { it.key !in hidden }

    fun find(key: String): AppEntry? = all.firstOrNull { it.key == key }

    /** The personal-profile app that owns [packageName], if any. */
    fun byPackage(packageName: String): AppEntry? =
        all.firstOrNull { it.packageName == packageName && it.isPersonal } ?: all.firstOrNull { it.packageName == packageName }

    /** Cached icon, or null; then [ready] is called on the main thread once it's painted. */
    fun icon(entry: AppEntry, size: Int, style: IconStyle, ready: (Bitmap) -> Unit): Bitmap? {
        val cacheKey = "${entry.key}|$style|$size"
        icons.get(cacheKey)?.let { return it }
        val density = context.resources.displayMetrics.densityDpi
        paint(cacheKey, ready) {
            IconRenderer.render(entry.info.getIcon(density), size, style, badge = !entry.isPersonal)
        }
        return null
    }

    fun shortcutIcon(shortcut: ShortcutInfo, size: Int, style: IconStyle, ready: (Bitmap) -> Unit): Bitmap? {
        val cacheKey = "${shortcut.`package`}/#${shortcut.id}|$style|$size"
        icons.get(cacheKey)?.let { return it }
        val density = context.resources.displayMetrics.densityDpi
        paint(cacheKey, ready) {
            launcherApps.getShortcutIconDrawable(shortcut, density)?.let { IconRenderer.render(it, size, style) }
        }
        return null
    }

    private fun paint(cacheKey: String, ready: (Bitmap) -> Unit, render: () -> Bitmap?) {
        painter.execute {
            val bitmap = runCatching(render).getOrNull() ?: return@execute
            main.post {
                icons.put(cacheKey, bitmap)
                ready(bitmap)
            }
        }
    }

    /** The app's shortcuts (static first, then dynamic), when NIHIL is the home app. */
    fun shortcuts(entry: AppEntry): List<ShortcutInfo> {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val list = runCatching { launcherApps.getShortcuts(query, entry.user) }.getOrNull().orEmpty()
        return list
            .filter { it.isEnabled && !it.shortLabel.isNullOrBlank() }
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
            .take(4)
    }

    fun startShortcut(shortcut: ShortcutInfo, bounds: Rect?, options: Bundle?) =
        launcherApps.startShortcut(shortcut, bounds, options)

    /** Opens the app; a paused work profile is woken up first. */
    fun launch(entry: AppEntry, bounds: Rect?, options: Bundle?) {
        if (!entry.isPersonal && userManager.isQuietModeEnabled(entry.user)) {
            userManager.requestQuietModeEnabled(false, entry.user)
            return
        }
        launcherApps.startMainActivity(entry.component, entry.user, bounds, options)
    }

    fun showInfo(entry: AppEntry, bounds: Rect?) =
        launcherApps.startAppDetailsActivity(entry.component, entry.user, bounds, null)

    fun uninstallIntent(entry: AppEntry): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
            .putExtra(Intent.EXTRA_USER, entry.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Drops cached icons, e.g. when the icon style changes. */
    fun clearIcons() = icons.evictAll()

    private fun evict(packageName: String) {
        icons.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach { icons.remove(it) }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            evict(packageName)
            reload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            evict(packageName)
            reload()
        }

        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }
}
