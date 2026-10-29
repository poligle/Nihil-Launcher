package com.nihilshell.launcher.core

import android.content.Context
import android.content.SharedPreferences

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

    /** App keys (see [AppEntry.key]) on the home screen, in order. */
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

    /** Distance between the LEDs of a studio wallpaper, in pixels. */
    var dotPitch: Int
        get() = sp.getInt(PITCH, 20)
        set(value) = sp.edit().putInt(PITCH, value).apply()

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

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context.applicationContext).also { instance = it } }
    }
}
