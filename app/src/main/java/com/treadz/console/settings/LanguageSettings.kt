package com.treadz.console.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/** The languages the app is translated into, plus "follow the system". */
enum class AppLanguage(
    /** BCP 47 tag handed to Android, `null` to follow the system language. */
    val tag: String?,
) {
    Automatic(null),
    Spanish("es"),
    English("en"),
    ;

    companion object {
        /** The option that matches the first locale of [tags] (a comma separated list, empty for automatic). */
        fun fromTags(tags: String): AppLanguage {
            val first = tags.split(',').firstOrNull { it.isNotBlank() }?.trim() ?: return Automatic
            val language = first.substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.tag == language } ?: Automatic
        }
    }
}

/**
 * The language of the app itself. On Android 13+ this is the same per-app language that the system settings show, so
 * the two always agree. Changing it recreates the activity.
 */
interface LanguageSettings {
    /** What is chosen right now. */
    fun current(): AppLanguage

    fun set(language: AppLanguage)
}

/** Per-app language through the platform's `LocaleManager` (the app's minimum is Android 13). */
class AndroidLanguageSettings(context: Context) : LanguageSettings {

    private val manager = context.applicationContext.getSystemService(LocaleManager::class.java)

    override fun current(): AppLanguage = AppLanguage.fromTags(manager.applicationLocales.toLanguageTags())

    override fun set(language: AppLanguage) {
        manager.applicationLocales = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
    }
}
