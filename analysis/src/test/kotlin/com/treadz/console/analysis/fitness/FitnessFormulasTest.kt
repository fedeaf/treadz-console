package com.treadz.console.analysis.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FitnessFormulasTest {

    // ------------------------------------------------------------------ Ebbeling

    @Test
    fun `Ebbeling equation matches hand-computed values`() {
        // 15.1 + 21.8*3 - 0.327*120 - 0.263*3*40 + 0.00504*120*40 + 5.98
        // = 15.1 + 65.4 - 39.24 - 31.56 + 24.192 + 5.98 = 39.872
        assertEquals(39.872, FitnessFormulas.ebbelingVo2max(3.0, 120.0, 40, Sex.Male), 1e-9)
        // Same without the 5.98 of the male term.
        assertEquals(33.892, FitnessFormulas.ebbelingVo2max(3.0, 120.0, 40, Sex.Female), 1e-9)
        // 15.1 + 87.2 - 35.97 - 26.3 + 13.86 = 53.89
        assertEquals(53.89, FitnessFormulas.ebbelingVo2max(4.0, 110.0, 25, Sex.Female), 1e-9)
    }

    @Test
    fun `speed is converted to miles per hour with the international mile`() {
        assertEquals(1.0, FitnessFormulas.kmhToMph(1.609344), 1e-12)
        assertEquals(2.98258, FitnessFormulas.kmhToMph(4.8), 1e-5)
        assertEquals(1.98839, FitnessFormulas.kmhToMph(3.2), 1e-5)
        assertEquals(4.47387, FitnessFormulas.kmhToMph(7.2), 1e-5)
    }

    @Test
    fun `age-predicted maximum heart rate is 220 minus age`() {
        assertEquals(180, FitnessFormulas.agePredictedHrMax(40))
        assertEquals(195, FitnessFormulas.agePredictedHrMax(25))
    }

    // ------------------------------------------------------------------ MAS

    @Test
    fun `MAS interpolates the incomplete stage`() {
        // 630 s from 8 km/h: 10 stages completed (the last one at 8 + 9*0.5 = 12.5), 30 s into the 11th: +0.25.
        assertEquals(12.75, FitnessFormulas.mas(8.0, 0.5, 60, 630.0)!!, 1e-9)
        // Exactly at the end of a stage: that stage's speed.
        assertEquals(8.0, FitnessFormulas.mas(8.0, 0.5, 60, 60.0)!!, 1e-9)
        assertEquals(8.25, FitnessFormulas.mas(8.0, 0.5, 60, 90.0)!!, 1e-9)
        assertEquals(10.0 + 0.5 * 45.0 / 60.0, FitnessFormulas.mas(10.0, 0.5, 60, 45.0 + 60.0)!!, 1e-9)
        // No stage completed: no MAS.
        assertNull(FitnessFormulas.mas(8.0, 0.5, 60, 59.9))
    }

    @Test
    fun `VO2max from MAS uses the Leger-Mercier ratio of 3_5 both ways`() {
        assertEquals(44.625, FitnessFormulas.vo2maxFromMas(12.75), 1e-9)
        assertEquals(12.75, FitnessFormulas.masFromVo2max(44.625), 1e-9)
    }
}
