package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.rr.Dfa
import com.treadz.console.analysis.samples.RrInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class ThresholdEngineTest {

    companion object {
        /**
         * The estimator's response to stationary series of each synthetic α (DFA over 4-16 beats reads a little high
         * below 0.75, a known small-scale effect that the published cut-offs already include): mean α1 of 40 windows of
         * 260 beats for α 0.30, 0.35 ... 1.30.
         */
        private val calibration: List<Double> by lazy {
            val random = Random(99)
            (6..26).map { k ->
                (0 until 40).map {
                    val x = SyntheticRr.fractionalNoise(260, k * 0.05, random)
                    Dfa.alpha1(DoubleArray(260) { i -> 450.0 + 20.0 * x[i] })!!
                }.average()
            }
        }

        private fun measuredAlpha(synthetic: Double): Double {
            val i = ((synthetic - 0.3) / 0.05).toInt().coerceIn(0, calibration.size - 2)
            val f = (synthetic - (0.3 + i * 0.05)) / 0.05
            return calibration[i] + f * (calibration[i + 1] - calibration[i])
        }

        /** The heart rate of a ramp (α [a0] → [a1], HR [h0] → [h1]) where the estimator's α1 crosses [target]. */
        fun expectedHeartRate(target: Double, a0: Double = 1.2, a1: Double = 0.3, h0: Double = 100.0, h1: Double = 180.0): Double {
            var lo = 0.0
            var hi = 1.0
            repeat(50) {
                val m = (lo + hi) / 2
                if (measuredAlpha(a0 + m * (a1 - a0)) > target) lo = m else hi = m
            }
            return h0 + lo * (h1 - h0)
        }
    }

    private val start = 1_000_000L
    private val minutes = 12
    private val end = start + minutes * 60_000L

    /**
     * Heart rate 100 → 180 bpm and the synthetic α 1.2 → 0.3 over 12 min (linear in the heart rate). The estimator
     * reads α1 = 0.75 at about 142 bpm (VT1) and 0.5 at about 173 bpm (VT2), see [expectedHeartRate]. The incline rises
     * from 5 to 17 % at 6 km/h.
     */
    private fun ramp(alphaEnd: Double = 0.3, hrEnd: Double = 180.0, seed: Long = 3) =
        SyntheticRr.ramp(start, minutes * 60, 100.0, hrEnd, 1.2, alphaEnd, seed = seed)

    private val samples = SyntheticRr.rampSamples(start, minutes * 60, 6.0, 5.0, 17.0)

    private fun input(rr: List<RrInterval>, maxHr: Int = 190) =
        ThresholdInput(rr, samples, start, end, maxHeartRateBpm = maxHr, restingHeartRateBpm = 60, vo2maxMlKgMin = 45.0)

    @Test
    fun `a synthetic ramp of known alpha1 gives VT1 and VT2 where alpha1 crosses 0,75 and 0,5`() {
        val a = ThresholdEngine.analyse(input(ramp()))
        val r = a.result
        assertNull(r.reason, "$r")
        assertEquals(ThresholdEngine.ALGORITHM_VERSION, r.algorithmVersion)
        val vt1 = checkNotNull(r.vt1)
        val vt2 = checkNotNull(r.vt2)
        assertEquals(expectedHeartRate(0.75), vt1.heartRateBpm, 6.0)
        assertEquals(expectedHeartRate(0.5), vt2.heartRateBpm, 6.0)
        assertEquals(ThresholdQuality.High, r.quality, "${r.metrics}")
        // 61 windows of 120 s every 10 s in 12 min; all valid (no artifacts).
        assertEquals(61, r.metrics!!.totalWindows)
        assertEquals(61, r.metrics!!.validWindows)
        assertTrue(r.metrics!!.slopePerBpm < 0)

        // The load at VT1: at the time its heart rate is reached the incline is 5 + 12·(HR − 100)/80 %.
        assertEquals(6.0, vt1.speedKmh!!, 1e-9)
        assertEquals(5.0 + 12.0 * (vt1.heartRateBpm - 100.0) / 80.0, vt1.inclinePercent!!, 1.5)
        assertEquals(Acsm.walkingVo2(6.0, vt1.inclinePercent!!), vt1.vo2MlKgMin!!, 1e-9)
        assertEquals(vt1.vo2MlKgMin!! / 45.0 * 100.0, vt1.percentVo2max!!, 1e-9)
        assertEquals((vt1.heartRateBpm - 60) / 130.0 * 100.0, vt1.percentHeartRateReserve!!, 1e-9)
    }

    @Test
    fun `over many random series VT1 is unbiased, each within 10 bpm`() {
        val expected = expectedHeartRate(0.75)
        val errors = (20L..31L).map { seed -> ThresholdEngine.analyse(input(ramp(seed = seed))).result.vt1!!.heartRateBpm - expected }
        assertEquals(0.0, errors.average(), 3.0, "$errors")
        assertTrue(errors.all { kotlin.math.abs(it) <= 10.0 }, "$errors")
    }

    @Test
    fun `without RR in the incremental phase there are no thresholds and the reason says so`() {
        val r = ThresholdEngine.analyse(input(emptyList())).result
        assertEquals(ThresholdReason.NoRrIntervals, r.reason)
        assertNull(r.vt1)
        assertNull(r.quality)
        // RR only before the ramp do not count either.
        val before = ThresholdEngine.analyse(input(listOf(RrInterval(start - 1_000, 600)))).result
        assertEquals(ThresholdReason.NoRrIntervals, before.reason)
    }

    @Test
    fun `a ramp whose alpha1 never goes below 0,55 gives VT1 but no VT2`() {
        // α 1.2 → 0.58: the estimator ends near 0.62.
        val expected = expectedHeartRate(0.75, a1 = 0.58)
        for (seed in 40L..44L) {
            val r = ThresholdEngine.analyse(input(SyntheticRr.ramp(start, minutes * 60, 100.0, 180.0, 1.2, 0.58, seed = seed))).result
            assertNotNull(r.vt1, "seed $seed: $r")
            assertEquals(expected, r.vt1!!.heartRateBpm, 10.0, "seed $seed")
            assertNull(r.vt2, "seed $seed")
            assertNull(r.reason)
        }
    }

    @Test
    fun `a ramp that stops before alpha1 reaches 0,75 has no VT1`() {
        // α 1.2 → 0.85: the estimator never reads 0.75.
        val r = ThresholdEngine.analyse(input(ramp(alphaEnd = 0.85))).result
        assertNull(r.vt1)
        assertNull(r.vt2)
        assertEquals(ThresholdReason.Vt1OutOfRange, r.reason)
    }

    @Test
    fun `injected artifacts are corrected - a few keep the thresholds, many invalidate the windows`() {
        val clean = ramp()
        val random = Random(5)
        fun withArtifacts(fraction: Double): List<RrInterval> = clean.map {
            if (random.nextDouble() < fraction) it.copy(rrMs = if (random.nextBoolean()) it.rrMs * 2 else it.rrMs / 2) else it
        }
        val few = ThresholdEngine.analyse(input(withArtifacts(0.01))).result
        assertEquals(ThresholdEngine.analyse(input(clean)).result.vt1!!.heartRateBpm, few.vt1!!.heartRateBpm, 4.0)
        assertTrue(few.metrics!!.meanArtifactPercent in 0.3..2.5, "${few.metrics}")
        assertTrue(few.quality != ThresholdQuality.Low, "${few.metrics}")

        val many = ThresholdEngine.analyse(input(withArtifacts(0.10))).result
        assertEquals(ThresholdReason.NotEnoughValidWindows, many.reason, "$many")
        assertNull(many.vt1)
    }

    @Test
    fun `partial coverage - RR only for the first half of the ramp`() {
        // RR until minute 7 (about 147 bpm, α1 about 0.73): no VT2.
        val half = ramp().filter { it.timeMillis <= start + 7 * 60_000L }
        val r = ThresholdEngine.analyse(input(half)).result
        assertNull(r.vt2)
        assertTrue(r.metrics!!.validWindows < r.metrics!!.totalWindows)
        // With the strap lost in the middle, the windows of the gap are invalid but the rest still gives the thresholds.
        val gap = ramp().filterNot { it.timeMillis in (start + 3 * 60_000L)..(start + 5 * 60_000L) }
        val g = ThresholdEngine.analyse(input(gap)).result
        assertEquals(expectedHeartRate(0.75), g.vt1!!.heartRateBpm, 8.0)
        assertTrue(g.metrics!!.validWindows < g.metrics!!.totalWindows)
    }

    @Test
    fun `one window below 0,4 does not end the stretch, two in a row do`() {
        fun stretchOf(vararg alphas: Double, maxHr: Int = 200): List<Int> {
            val windows = alphas.mapIndexed { i, a ->
                Alpha1Window(i * 10_000L, i * 10_000L + 120_000L, 250, 0.0, true, a, 120.0 + i)
            }
            return ThresholdEngine.stretch(windows, windows.indices.toList(), maxHr, ThresholdRules())
        }
        // A lone dip (0.39) between 0.45 and 0.42: the stretch goes on to the pair 0.38, 0.35 and ends at its first.
        assertEquals((0..6).toList(), stretchOf(1.1, 0.8, 0.39, 0.45, 0.42, 0.41, 0.38, 0.35, 0.3))
        // The last window below 0.4 with nothing after it ends the stretch.
        assertEquals((0..3).toList(), stretchOf(1.1, 0.8, 0.6, 0.38))
        // A window below 0.4 whose next window is at 95 % of the maximum ends it as well.
        assertEquals((0..2).toList(), stretchOf(1.1, 0.8, 0.38, 0.6, maxHr = 129))
    }

    @Test
    fun `the stretch ends below 95 percent of the maximum heart rate`() {
        // Maximum 160: windows from 152 bpm on are left out, so the regression never sees α1 below about 0.65.
        val r = ThresholdEngine.analyse(input(ramp(), maxHr = 160)).result
        assertNotNull(r.vt1)
        assertNull(r.vt2)
    }

    @Test
    fun `quality falls with few windows`() {
        // A 4-minute ramp: 13 windows, all of them in the stretch; then 2.5 minutes: 4 windows -> low.
        val short = SyntheticRr.ramp(start, 150, 120.0, 160.0, 1.0, 0.45, seed = 4)
        val r = ThresholdEngine.analyse(ThresholdInput(short, emptyList(), start, start + 150_000, 190)).result
        assertEquals(4, r.metrics!!.totalWindows)
        assertEquals(ThresholdQuality.Low, r.quality)
        // No samples: no load at the threshold.
        r.vt1?.let { assertNull(it.vo2MlKgMin) }
    }
}
