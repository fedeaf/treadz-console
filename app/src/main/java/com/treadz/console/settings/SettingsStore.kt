package com.treadz.console.settings

import android.content.Context
import androidx.core.content.edit

/** Last treadmill the app connected to, to reconnect by itself when it opens. */
data class RememberedTreadmill(val address: String, val name: String?)

/** Simple milestone 1 preferences. Tests use an in-memory implementation. */
interface SettingsStore {
    var lastTreadmill: RememberedTreadmill?

    /** Dark theme by default. */
    var darkTheme: Boolean
}

class SharedPreferencesSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var lastTreadmill: RememberedTreadmill?
        get() = prefs.getString(KEY_ADDRESS, null)?.let { RememberedTreadmill(it, prefs.getString(KEY_NAME, null)) }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_ADDRESS)
                remove(KEY_NAME)
            } else {
                putString(KEY_ADDRESS, value.address)
                putString(KEY_NAME, value.name)
            }
        }

    override var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK, true)
        set(value) = prefs.edit { putBoolean(KEY_DARK, value) }

    private companion object {
        const val FILE = "treadz_settings"
        const val KEY_ADDRESS = "last_treadmill_address"
        const val KEY_NAME = "last_treadmill_name"
        const val KEY_DARK = "dark_theme"
    }
}
