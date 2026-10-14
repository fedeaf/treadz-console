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

    /** The user (profile id) has answered the one-time "use this strap?" question, whatever the answer. */
    fun strapQuestionAnswered(userId: Long): Boolean

    fun setStrapQuestionAnswered(userId: Long)

    /**
     * When the user last declined an observed heart-rate peak as their maximum (epoch ms), `null` if never. A peak
     * recorded before that moment is not offered again.
     */
    fun peakAnsweredAtMillis(userId: Long): Long?

    fun setPeakAnsweredAtMillis(userId: Long, atMillis: Long)
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

    override fun strapQuestionAnswered(userId: Long): Boolean = prefs.getBoolean(KEY_STRAP_ASKED + userId, false)

    override fun setStrapQuestionAnswered(userId: Long) = prefs.edit { putBoolean(KEY_STRAP_ASKED + userId, true) }

    override fun peakAnsweredAtMillis(userId: Long): Long? =
        prefs.getLong(KEY_PEAK_ANSWERED + userId, NONE).takeIf { it != NONE }

    override fun setPeakAnsweredAtMillis(userId: Long, atMillis: Long) = prefs.edit { putLong(KEY_PEAK_ANSWERED + userId, atMillis) }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val KEY_STRAP_ASKED = "strap_question_answered_"
        const val KEY_PEAK_ANSWERED = "peak_answered_at_"
        const val FILE = "treadz_settings"
        const val KEY_ADDRESS = "last_treadmill_address"
        const val KEY_NAME = "last_treadmill_name"
        const val KEY_DARK = "dark_theme"
    }
}
