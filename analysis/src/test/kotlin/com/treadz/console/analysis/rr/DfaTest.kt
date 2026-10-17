package com.treadz.console.analysis.rr

import com.treadz.console.analysis.thresholds.SyntheticRr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class DfaTest {

    /** Mean α1 of [windows] independent 240-beat series of known α (RR around 500 ms, SD 20 ms). */
    private fun meanAlpha(alpha: Double, windows: Int = 12, seed: Long = 7): Pair<Double, List<Double>> {
        val random = Random(seed)
        val values = (0 until windows).map {
            val noise = SyntheticRr.fractionalNoise(240, alpha, random)
            Dfa.alpha1(DoubleArray(noise.size) { i -> 500.0 + 20.0 * noise[i] })!!
        }
        return values.average() to values
    }

    @Test
    fun `alpha1 of synthetic series of known alpha is recovered within 0,1`() {
        for (alpha in listOf(0.5, 0.75, 1.0, 1.2)) {
            val (mean, values) = meanAlpha(alpha)
            assertEquals(alpha, mean, 0.1, "α $alpha: $values")
        }
    }

    @Test
    fun `alpha1 orders series by their correlation`() {
        val low = meanAlpha(0.5).first
        val mid = meanAlpha(0.75).first
        val high = meanAlpha(1.0).first
        assertTrue(low < mid && mid < high, "$low $mid $high")
    }

    @Test
    fun `too short or constant series give no alpha1`() {
        assertNull(Dfa.alpha1(DoubleArray(31) { 500.0 + it % 3 }))
        assertNull(Dfa.alpha1(DoubleArray(200) { 500.0 }))
    }

    @Test
    fun `cleaning marks out of range values and jumps from the local median and interpolates them`() {
        val rr = MutableList(40) { 600 + (it % 5) * 4 }
        rr[10] = 1200 // a missed beat
        rr[20] = 250 // out of range
        rr[30] = 450 // an extra beat (25 % short)
        val c = RrCleaning.clean(rr)
        assertEquals(3, c.artifactCount)
        assertTrue(c.artifact[10] && c.artifact[20] && c.artifact[30])
        assertEquals((rr[9] + rr[11]) / 2.0, c.rrMs[10], 1e-9)
        assertEquals((rr[19] + rr[21]) / 2.0, c.rrMs[20], 1e-9)
        assertFalse(c.artifact[11])
        assertEquals(rr[0].toDouble(), c.rrMs[0], 1e-9)
    }

    @Test
    fun `artifacts at the ends take the nearest correct value`() {
        val rr = listOf(2500, 600, 610, 605, 600, 595, 600, 602, 604, 3000)
        val c = RrCleaning.clean(rr)
        assertEquals(600.0, c.rrMs[0], 1e-9)
        assertEquals(604.0, c.rrMs[9], 1e-9)
    }
}
