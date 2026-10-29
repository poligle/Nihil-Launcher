package com.nihilshell.launcher.core

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import com.nihilshell.launcher.R
import com.nihilshell.launcher.ui.LineIcon

/**
 * The "> actions" of the drawer: quick switches and system pages. They also
 * show up in normal search ("lin" finds the torch), in Spanish or English.
 */
class Action(
    val id: String,
    val label: String,
    keywords: String,
    val icon: LineIcon.Shape,
    val hint: () -> String,
) {
    val searchLabel: String = Fuzzy.normalize(label)
    val words: List<String> = Fuzzy.normalize(keywords).split(' ').filter { it.isNotBlank() }

    /** Best score of the label or any keyword for a normalised [q]. */
    fun score(q: String): Int =
        maxOf(Fuzzy.score(searchLabel, q), words.maxOfOrNull { w -> if (w.startsWith(q)) 600 - w.length else -1 } ?: -1)
}

/** What an action needs from the screen that runs it. */
interface ActionHost {
    val context: Context
    fun start(vararg intents: Intent): Boolean
    fun lockScreen()
    fun openStudio()
    fun openSettings()
    fun notice(text: String)
    fun nextAlarmText(): String?
}

object Actions {
    const val TORCH = "torch"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val SOUND = "sound"
    const val BATTERY = "battery"
    const val ALARMS = "alarms"
    const val LOCK = "lock"
    const val WALLPAPER = "wallpaper"
    const val NIHIL = "nihil"
    const val SYSTEM = "system"

    fun all(context: Context, host: ActionHost): List<Action> {
        fun s(id: Int) = context.getString(id)
        return listOf(
            Action(TORCH, s(R.string.act_torch), "torch flashlight linterna flash luz light", LineIcon.Shape.TORCH) {
                s(if (Torch.on) R.string.hint_on else R.string.hint_off)
            },
            Action(WIFI, s(R.string.act_wifi), "wifi internet red network datos data", LineIcon.Shape.WIFI) { s(R.string.hint_panel) },
            Action(BLUETOOTH, s(R.string.act_bluetooth), "bluetooth bt auriculares headphones", LineIcon.Shape.BLUETOOTH) {
                s(R.string.hint_system)
            },
            Action(SOUND, s(R.string.act_sound), "sound volume sonido volumen audio", LineIcon.Shape.SOUND) { s(R.string.hint_panel) },
            Action(BATTERY, s(R.string.act_battery), "battery bateria energia power", LineIcon.Shape.BATTERY) { s(R.string.hint_system) },
            Action(ALARMS, s(R.string.act_alarms), "alarm alarma reloj clock despertador", LineIcon.Shape.ALARM) {
                host.nextAlarmText() ?: s(R.string.hint_system)
            },
            Action(LOCK, s(R.string.act_lock), "lock bloquear pantalla screen", LineIcon.Shape.LOCK) { s(R.string.hint_screen) },
            Action(WALLPAPER, s(R.string.act_wallpaper), "wallpaper fondo studio estudio led", LineIcon.Shape.IMAGE) {
                s(R.string.hint_studio)
            },
            Action(NIHIL, s(R.string.act_nihil), "nihil settings ajustes launcher", LineIcon.Shape.SETTINGS) { "" },
            Action(SYSTEM, s(R.string.act_system), "settings ajustes sistema system", LineIcon.Shape.GEAR) { s(R.string.hint_system) },
        )
    }

    /** Runs [action]; false if nothing could be opened. */
    fun run(action: Action, host: ActionHost): Boolean = when (action.id) {
        TORCH -> Torch.toggle().also { ok -> if (!ok) host.notice(host.context.getString(R.string.torch_unavailable)) }
        WIFI -> host.start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIFI_SETTINGS))
        BLUETOOTH -> host.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        SOUND -> host.start(Intent(Settings.Panel.ACTION_VOLUME), Intent(Settings.ACTION_SOUND_SETTINGS))
        BATTERY -> host.start(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        ALARMS -> host.start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        LOCK -> {
            host.lockScreen()
            true
        }
        WALLPAPER -> {
            host.openStudio()
            true
        }
        NIHIL -> {
            host.openSettings()
            true
        }
        SYSTEM -> host.start(Intent(Settings.ACTION_SETTINGS))
        else -> false
    }
}
