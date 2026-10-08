package com.treadz.console

import com.treadz.console.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {

    @Test fun inclineHasNoDecimalsBecauseTheTreadmillMovesInWholePercent() {
        assertEquals("3", Format.incline(3.0, Locale.US))
        assertEquals("3", Format.incline(3.0, Locale("es", "ES")))
        assertEquals("0", Format.incline(0.0, Locale.US))
        assertEquals("18", Format.incline(18.0, Locale.US))
        assertEquals("12", Format.incline(11.9, Locale.US))
    }

    @Test fun inclineIsFormattedForTheLocale() {
        // Locales with their own digits keep them: no hand-made string building.
        assertEquals("٣", Format.incline(3.0, Locale.forLanguageTag("ar-EG")))
    }

    @Test fun speedKeepsOneDecimalWithTheLocaleSeparator() {
        assertEquals("8.5", Format.speed(8.5, Locale.US))
        assertEquals("8,5", Format.speed(8.5, Locale("es", "ES")))
    }
}
