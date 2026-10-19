package com.treadz.console

import androidx.compose.ui.unit.sp
import com.treadz.console.ui.fittedFontSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** The number of a stepper field shrinks until it fits, so a narrow field never hides it. */
class StepperFieldFitTest {

    /** A text 5 characters long, each 0.6 of the font size wide (in pixels at 1 px per sp). */
    private val width: (androidx.compose.ui.unit.TextUnit) -> Float = { size -> 5 * 0.6f * size.value }

    @Test fun theBiggestSizeIsKeptWhenItFits() {
        assertEquals(22.sp, fittedFontSize(22.sp, 14.sp, available = 100f, width = width))
    }

    @Test fun theSizeShrinksToTheBiggestThatFits() {
        // 18 sp → 54 px fits in 55; 19 sp → 57 px does not.
        assertEquals(18.sp, fittedFontSize(22.sp, 14.sp, available = 55f, width = width))
    }

    @Test fun itNeverGoesBelowTheMinimum() {
        assertEquals(14.sp, fittedFontSize(22.sp, 14.sp, available = 10f, width = width))
    }
}
