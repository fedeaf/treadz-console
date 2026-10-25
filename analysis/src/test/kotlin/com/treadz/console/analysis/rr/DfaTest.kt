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
    fun `the pause after a premature beat is corrected with it and marked as a pause`() {
        // At 125 bpm: a premature beat (300 ms) and the pause after it (555 ms, +17 %), as in real recordings.
        val rr = MutableList(40) { 478 + (it % 3) * 2 }
        rr[20] = 300
        rr[21] = 555
        val c = RrCleaning.clean(rr)
        assertTrue(c.artifact[20] && c.artifact[21])
        assertFalse(c.pause[20])
        assertTrue(c.pause[21])
        assertEquals(2, c.artifactCount)
        assertEquals(1, c.strapArtifactCount, "the pause is a real beat, not a fault of the strap")
        assertTrue(c.isStrapArtifact(20) && !c.isStrapArtifact(21))
        // Both interpolated between the neighbours.
        assertEquals(rr[19] + (rr[22] - rr[19]) / 3.0, c.rrMs[20], 1e-9)
        assertEquals(rr[19] + 2 * (rr[22] - rr[19]) / 3.0, c.rrMs[21], 1e-9)
        assertFalse(c.artifact[22])
        // The same at rest (premature beat at 600 ms, pause 1050 ms).
        val rest = MutableList(40) { 900 + (it % 4) * 10 }
        rest[15] = 600
        rest[16] = 1050
        val r = RrCleaning.clean(rest)
        assertTrue(r.pause[16] && r.artifact[15])
        assertEquals(1, r.strapArtifactCount)
    }

    @Test
    fun `only the short-long pattern - extra detections, missed beats, small pauses and ordinary variation are left alone`() {
        val base = { MutableList(40) { 600 + (it % 5) * 4 } }
        // An extra detection splits one interval in two short ones; the beat after them is normal.
        val split = base().also { it[20] = 330; it[21] = 278 }
        val s = RrCleaning.clean(split)
        assertTrue(s.artifact[20] && s.artifact[21])
        assertFalse(s.artifact[22])
        assertTrue(s.pause.none { it })
        // A missed beat (one long interval) and the beat after it.
        val missed = base().also { it[20] = 1210 }
        val m = RrCleaning.clean(missed)
        assertTrue(m.artifact[20] && !m.artifact[21])
        assertTrue(m.pause.none { it })
        // A premature beat followed by an interval only 6 % long: not a pause.
        val small = base().also { it[20] = 450; it[21] = 645 }
        val p = RrCleaning.clean(small)
        assertTrue(p.artifact[20])
        assertFalse(p.artifact[21])
        // Ordinary beat-to-beat variation, a long interval without a premature beat before it.
        val variation = base().also { it[20] = 560; it[21] = 660 }
        assertEquals(0, RrCleaning.clean(variation).artifactCount)
    }

    @Test
    fun `artifacts at the ends take the nearest correct value`() {
        val rr = listOf(2500, 600, 610, 605, 600, 595, 600, 602, 604, 3000)
        val c = RrCleaning.clean(rr)
        assertEquals(600.0, c.rrMs[0], 1e-9)
        assertEquals(604.0, c.rrMs[9], 1e-9)
    }
}
