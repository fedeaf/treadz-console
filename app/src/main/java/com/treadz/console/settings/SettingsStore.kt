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

    /**
     * The inputs the user last started a generated session with, for one generator (mode, duration, series, walking
     * speed, jog adjustment…), as text. Empty when there are none. Per user and generator, local to the device.
     */
    fun generatorInputs(userId: Long, generatorId: String): Map<String, String>

    fun setGeneratorInputs(userId: Long, generatorId: String, inputs: Map<String, String>)
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

    override fun generatorInputs(userId: Long, generatorId: String): Map<String, String> =
        prefs.getString(generatorKey(userId, generatorId), null)?.let(::decodeInputs).orEmpty()

    override fun setGeneratorInputs(userId: Long, generatorId: String, inputs: Map<String, String>) =
        prefs.edit { putString(generatorKey(userId, generatorId), encodeInputs(inputs)) }

    private fun generatorKey(userId: Long, generatorId: String) = "$KEY_GENERATOR_INPUTS${userId}_$generatorId"

    private companion object {
        const val KEY_GENERATOR_INPUTS = "generator_inputs_"
        const val NONE = Long.MIN_VALUE
        const val KEY_STRAP_ASKED = "strap_question_answered_"
        const val KEY_PEAK_ANSWERED = "peak_answered_at_"
        const val FILE = "treadz_settings"
        const val KEY_ADDRESS = "last_treadmill_address"
        const val KEY_NAME = "last_treadmill_name"
        const val KEY_DARK = "dark_theme"
    }
}

/** `key=value;key=value`: the inputs are numbers and words, never containing `;` or `=`. */
internal fun encodeInputs(inputs: Map<String, String>): String =
    inputs.entries.filter { ';' !in it.key + it.value && '=' !in it.key + it.value }.joinToString(";") { "${it.key}=${it.value}" }

internal fun decodeInputs(text: String): Map<String, String> =
    text.split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
    }.toMap()
