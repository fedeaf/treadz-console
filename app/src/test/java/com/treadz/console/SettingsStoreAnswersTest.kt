package com.treadz.console

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.treadz.console.settings.SharedPreferencesSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The answers to the strap question and to the observed-peak offer are kept per user, across a restart. */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreAnswersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun theStrapQuestionIsAnsweredPerUserAndSurvivesARestart() {
        val store = SharedPreferencesSettingsStore(context)
        assertFalse(store.strapQuestionAnswered(1))
        store.setStrapQuestionAnswered(1)
        assertTrue(store.strapQuestionAnswered(1))
        assertFalse(store.strapQuestionAnswered(2))
        assertTrue(SharedPreferencesSettingsStore(context).strapQuestionAnswered(1))
    }

    @Test fun thePeakAnswerIsATimePerUser() {
        val store = SharedPreferencesSettingsStore(context)
        assertNull(store.peakAnsweredAtMillis(7))
        store.setPeakAnsweredAtMillis(7, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, SharedPreferencesSettingsStore(context).peakAnsweredAtMillis(7))
        assertNull(store.peakAnsweredAtMillis(8))
        // A later answer replaces it.
        store.setPeakAnsweredAtMillis(7, 1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, store.peakAnsweredAtMillis(7))
    }
}
