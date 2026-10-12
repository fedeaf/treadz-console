package com.treadz.console

import com.treadz.console.ui.Format
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    private val original = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(original)

    @Test fun inclineHasNoDecimalsBecauseTheTreadmillMovesInWholePercent() {
        assertEquals("3", Format.incline(3.0))
        assertEquals("0", Format.incline(0.0))
        assertEquals("18", Format.incline(18.0))
        assertEquals("12", Format.incline(11.9))
    }

    @Test fun numbersUseADecimalPointAndPlainDigitsInEveryLanguage() {
        listOf(Locale.forLanguageTag("es-ES"), Locale.forLanguageTag("ar-EG"), Locale.ENGLISH).forEach { locale ->
            Locale.setDefault(locale)
            assertEquals("8.5", Format.speed(8.5))
            assertEquals("3", Format.incline(3.0))
            assertEquals("1.25", Format.distanceKm(1250))
            assertEquals("68.5", Format.weight(68.5))
            assertEquals("05:07", Format.duration(307))
            assertEquals("6.0", Decimals.format(6.0))
        }
    }
}
