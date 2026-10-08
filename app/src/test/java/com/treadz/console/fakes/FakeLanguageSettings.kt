package com.treadz.console.fakes

import com.treadz.console.settings.AppLanguage
import com.treadz.console.settings.LanguageSettings

/** Remembers the chosen language and the calls received, instead of asking Android. */
class FakeLanguageSettings(var language: AppLanguage = AppLanguage.Automatic) : LanguageSettings {

    /** Every language passed to [set], in order. */
    val calls = mutableListOf<AppLanguage>()

    override fun current(): AppLanguage = language

    override fun set(language: AppLanguage) {
        calls += language
        this.language = language
    }
}
