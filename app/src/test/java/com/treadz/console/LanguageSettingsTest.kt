package com.treadz.console

import android.app.LocaleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.treadz.console.settings.AndroidLanguageSettings
import com.treadz.console.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The per-app language goes through Android's `LocaleManager`, the same one the system settings use. */
@RunWith(RobolectricTestRunner::class)
class LanguageSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = AndroidLanguageSettings(context)
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @Test fun startsAutomatic() {
        assertEquals(AppLanguage.Automatic, settings.current())
    }

    @Test fun spanishAndEnglishAreSetAsApplicationLocales() {
        settings.set(AppLanguage.Spanish)
        assertEquals("es", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLanguage.Spanish, settings.current())
        settings.set(AppLanguage.English)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLanguage.English, settings.current())
    }

    @Test fun automaticClearsTheApplicationLocales() {
        settings.set(AppLanguage.Spanish)
        settings.set(AppLanguage.Automatic)
        assertEquals("", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLanguage.Automatic, settings.current())
    }

    @Test fun regionalTagsMapToTheirLanguage() {
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTags("es-AR"))
        assertEquals(AppLanguage.English, AppLanguage.fromTags("en-GB,es"))
        assertEquals(AppLanguage.Automatic, AppLanguage.fromTags(""))
        assertEquals(AppLanguage.Automatic, AppLanguage.fromTags("fr"))
    }
}
