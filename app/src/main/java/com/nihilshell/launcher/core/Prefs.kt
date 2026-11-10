package com.nihilshell.launcher.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class IconStyle {
    /** The app's themed (monochrome) icon in ink; desaturated when there is none. */
    MONO,
    /** The full icon, desaturated. */
    GREY,
    COLOR;

    companion object {
        fun from(value: String?): IconStyle = entries.firstOrNull { it.name == value } ?: MONO
    }
}

/** Everything the launcher remembers, in one SharedPreferences file. */
class Prefs private constructor(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("nihil", Context.MODE_PRIVATE)

    /** The keys of the apps and webs on the home screen (see [Entry.key]), in order. */
    var favorites: List<String>
        get() = sp.getString(FAVORITES, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = sp.edit().putString(FAVORITES, value.joinToString("\n")).apply()

    /** False until the first favourites are chosen (or the user edits them). */
    val favoritesChosen: Boolean get() = sp.contains(FAVORITES)

    var hidden: Set<String>
        get() = sp.getStringSet(HIDDEN, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(HIDDEN, value).apply()

    var iconStyle: IconStyle
        get() = IconStyle.from(sp.getString(ICONS, null))
        set(value) = sp.edit().putString(ICONS, value.name).apply()

    var favoriteIcons: Boolean
        get() = sp.getBoolean(FAVORITE_ICONS, true)
        set(value) = sp.edit().putBoolean(FAVORITE_ICONS, value).apply()

    var dimWallpaper: Boolean
        get() = sp.getBoolean(DIM, false)
        set(value) = sp.edit().putBoolean(DIM, value).apply()

    var keyboardOnOpen: Boolean
        get() = sp.getBoolean(KEYBOARD, true)
        set(value) = sp.edit().putBoolean(KEYBOARD, value).apply()

    var glyph: Boolean
        get() = sp.getBoolean(GLYPH, true)
        set(value) = sp.edit().putBoolean(GLYPH, value).apply()

    var doubleTapLock: Boolean
        get() = sp.getBoolean(DOUBLE_TAP, false)
        set(value) = sp.edit().putBoolean(DOUBLE_TAP, value).apply()

    /** Whether NIHIL's own wallpaper was put on, the first time it was the home app. */
    var wallpaperSeeded: Boolean
        get() = sp.getBoolean(SEEDED, false)
        set(value) = sp.edit().putBoolean(SEEDED, value).apply()

    /** Distance between the LEDs of a studio wallpaper, in pixels. */
    var dotPitch: Int
        get() = sp.getInt(PITCH, 20)
        set(value) = sp.edit().putInt(PITCH, value).apply()

    /** The webs kept here (Webs), in the order they were added. */
    var webs: List<WebEntry>
        get() = runCatching {
            val list = JSONArray(sp.getString(WEBS, "[]"))
            List(list.length()) { list.getJSONObject(it).let { o -> WebEntry(o.getString("url"), o.optString("title")) } }
        }.getOrDefault(emptyList())
        set(value) {
            val list = JSONArray()
            value.forEach { list.put(JSONObject().put("url", it.url).put("title", it.label)) }
            sp.edit().putString(WEBS, list.toString()).apply()
        }

    /** Adds [web], or puts it where the one at [replacing] was, on the home screen too. */
    fun saveWeb(web: WebEntry, replacing: String? = null) {
        val list = webs.toMutableList()
        val at = list.indexOfFirst { it.url == (replacing ?: web.url) }
        list.removeAll { it.url == web.url || it.url == replacing }
        list.add(if (at >= 0) at.coerceAtMost(list.size) else list.size, web)
        webs = list
        if (replacing != null && replacing != web.url) {
            favorites = favorites.map { if (it == WebEntry.KEY + replacing) web.key else it }.distinct()
        }
    }

    fun removeWeb(url: String) {
        webs = webs.filter { it.url != url }
        removeFavorite(WebEntry.KEY + url)
    }

    fun addFavorite(key: String) {
        if (key !in favorites) favorites = favorites + key
    }

    fun removeFavorite(key: String) {
        favorites = favorites - key
    }

    fun moveFavorite(key: String, by: Int) {
        val list = favorites.toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return
        val to = (from + by).coerceIn(0, list.size - 1)
        list.removeAt(from)
        list.add(to, key)
        favorites = list
    }

    fun hide(key: String) {
        hidden = hidden + key
        removeFavorite(key)
    }

    fun unhide(key: String) {
        hidden = hidden - key
    }

    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val FAVORITES = "favorites"
        const val HIDDEN = "hidden"
        const val ICONS = "icons"
        const val FAVORITE_ICONS = "favorite_icons"
        const val DIM = "dim"
        const val KEYBOARD = "keyboard"
        const val GLYPH = "glyph"
        const val DOUBLE_TAP = "double_tap"
        const val PITCH = "pitch"
        const val SEEDED = "wallpaper_seeded"
        const val WEBS = "webs"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context.applicationContext).also { instance = it } }
    }
}
