package com.treadz.console.programs.engine

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.heartrate.HeartRateZoneBpm
import com.treadz.console.programs.heartrate.StrapWindowConfig
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The heart-rate control with a strap whose readings the RR may or may not confirm (finding 1 of the audit of the zone-2
 * workout of 2026-10-24), the wait after a change (finding 2) and the rising heart rate (finding 4), on exact times.
 *
 * Synthetic data in the shape of the audit: zone 122-130 bpm (maximum 185, resting 58, ceiling 138), walking with the
 * incline between 0 and 15 % from 10 %. A reading every 500 ms, the beats of that half second with it.
 */
class StrapCheckControlTest {

    /** What a bad strap does for a while: the readings run above the truth and the beats are impossible intervals. */
    private val junk = intArrayOf(250, 390, 300, 380, 260, 350, 290, 370)

    private class Sim(
        config: HeartRateControlConfig = HeartRateControlConfig(),
        mode: HeartRateControlMode = HeartRateControlMode.Continuous,
        val source: HeartRateSourceKind = HeartRateSourceKind.DirectStrap,
        start: Double = 10.0,
        /** What the strap reports at block time `ms`. */
        var reported: (Long) -> Int = { 127 },
        /** The length of the beat that ends at block time `ms`; by default consistent with [truth]. */
        var beat: ((Int, Long) -> Int)? = null,
        /** The real heart rate at block time `ms`. */
        var truth: (Long) -> Int = { 127 },
        /** The steady heart rate of the 30 s before the block. */
        pre: Int = 127,
    ) {
        val tracker = HeartRateTracker(HeartRateProfile(185, 58), config)
        var t = 0L
        var value = start
        val blockStart: Long
        val control: HeartRateBlockControl
        val changes = mutableListOf<Triple<Long, Double, HeartRateAdjustReason>>()
        val signals = mutableListOf<HeartRateSignal>()
        private var nextBeat = 0.0
        private var beatIndex = 0

        init {
            // 30 s of history before the block (steady 127, sound beats).
            nextBeat = 0.0
            while (t < 30_000) {
                t += 500
                feed(t, pre, -1L, pre)
            }
            blockStart = t
            control = HeartRateBlockControl(
                0, HeartRateAdjust.Incline, mode, HeartRateZoneBpm(122, 130, HeartRateBasis.MaxHeartRate), 185, 0.0, 15.0, 1.0, start, start,
                tracker, config, t,
            )
        }

        private fun feed(at: Long, bpm: Int, blockMs: Long, pre: Int = 0) {
            val rr = ArrayList<Int>()
            while (nextBeat < at) {
                val ms = if (blockMs < 0) (60_000.0 / pre).toInt() else beat?.invoke(beatIndex, blockMs) ?: (60_000.0 / truth(blockMs)).toInt()
                beatIndex++
                nextBeat += ms
                rr += ms
            }
            val reading = if (source == HeartRateSourceKind.DirectStrap) {
                HeartRateReading(bpm, HeartRateValidity.Valid, at, source, rr)
            } else {
                HeartRateReading(bpm, HeartRateValidity.Unconfirmed, at, source)
            }
            val filtered = tracker.onReading(at, reading)
            if (blockMs >= 0) control.onSample(at, filtered)
        }

        fun run(ms: Long) {
            val end = t + ms
            while (t < end) {
                t += 250
                if (t % 500 == 0L) feed(t, reported(t - blockStart), t - blockStart)
                control.update(t, t - blockStart, value)?.let {
                    val reason = control.pending.filterIsInstance<HeartRateSignal.Adjusted>().last().reason
                    changes += Triple(t - blockStart, it, reason)
                    value = it
                }
                signals += control.pending
                control.pending.clear()
            }
        }

        val status get() = control.status(t, value)
        val times get() = changes.map { it.first }
    }

    // ------------------------------------------------------------------ finding 1

    /** Stable at 127 (beats of 472 ms); from 100 to 130 s the strap says 137 while the beats are impossible intervals. */
    private fun falseRise(config: HeartRateControlConfig = HeartRateControlConfig(), source: HeartRateSourceKind = HeartRateSourceKind.DirectStrap) =
        Sim(config = config, source = source).also { sim ->
            sim.reported = { ms -> if (ms in 100_000 until 130_000) 137 else 127 }
            sim.truth = { 127 }
            sim.beat = { i, ms -> if (ms in 100_000 until 130_000) junk[i % junk.size] else 472 }
        }

    @Test
    fun `a rise of the strap that its RR do not confirm lowers nothing, and the status says it was deferred`() {
        val sim = falseRise()
        sim.run(125_000)
        // The decision at 120 s saw a mean of ~137 in a window of impossible beats: deferred.
        assertTrue(sim.changes.isEmpty(), "${sim.changes}")
        assertTrue(sim.status.decisionDeferredByStrap, "${sim.status}")
        assertEquals(HeartRateControlState.Waiting, sim.status.state)
        sim.run(40_000) // the strap is sound again and back at 127 (in the zone)
        assertTrue(sim.changes.isEmpty(), "${sim.changes}")
        assertFalse(sim.status.decisionDeferredByStrap)
        assertEquals(1, sim.control.summary(sim.t, sim.value).strapDeferrals)
        assertEquals(10.0, sim.value)
    }

    @Test
    fun `the same readings without the check do lower the load - it is the check that holds it back`() {
        val sim = falseRise(HeartRateControlConfig(strapWindow = StrapWindowConfig(enabled = false)))
        sim.run(125_000)
        assertEquals(listOf(Triple(120_000L, 8.0, HeartRateAdjustReason.AboveZone)), sim.changes)
        assertFalse(sim.status.decisionDeferredByStrap)
    }

    @Test
    fun `with the console's heart rate (no RR) the control behaves as before`() {
        val sim = falseRise(source = HeartRateSourceKind.TreadmillConsole)
        sim.run(125_000)
        assertEquals(listOf(Triple(120_000L, 8.0, HeartRateAdjustReason.AboveZone)), sim.changes)
        assertFalse(sim.status.decisionDeferredByStrap)
        assertEquals(0, sim.control.summary(sim.t, sim.value).strapDeferrals)
    }

    @Test
    fun `a real sustained rise - the beats shorten to 435 ms and 138 bpm - lowers the load as usual`() {
        val sim = Sim()
        sim.truth = { ms -> if (ms < 60_000) 127 else (127 + (ms - 60_000) * 11 / 40_000).toInt().coerceAtMost(138) }
        sim.reported = sim.truth
        sim.run(130_000)
        assertTrue(sim.changes.isNotEmpty(), "a rise confirmed by the RR must be acted on")
        assertTrue(sim.changes.all { it.third == HeartRateAdjustReason.AboveZone && it.second < 10.0 }, "${sim.changes}")
        assertFalse(sim.status.decisionDeferredByStrap)
        assertEquals(0, sim.control.summary(sim.t, sim.value).strapDeferrals)
    }

    @Test
    fun `a doubtful window does not stop the ceiling rule - the raw readings still lower the load`() {
        val sim = Sim()
        sim.reported = { ms -> if (ms >= 100_000) 150 else 127 } // ceiling 138
        sim.beat = { i, ms -> if (ms >= 100_000) junk[i % junk.size] else 472 }
        sim.run(130_000)
        val ceiling = sim.changes.filter { it.third == HeartRateAdjustReason.CeilingExceeded }
        assertTrue(ceiling.isNotEmpty(), "${sim.changes}")
        assertEquals(7.0, ceiling.first().second, "-3 % from 10 %")
        assertTrue(sim.changes.none { it.third == HeartRateAdjustReason.AboveZone }, "the regular decisions are deferred: ${sim.changes}")
        assertEquals(HeartRateControlState.Ceiling, sim.status.state)
    }

    @Test
    fun `a doubtful window does not stop the maximum-heart-rate rule`() {
        val sim = Sim()
        // 127 -> 155 -> 185 in steps the plausibility filter accepts; the beats are impossible intervals all along.
        sim.reported = { ms -> if (ms < 100_000) 127 else if (ms < 101_000) 155 else 185 }
        sim.beat = { i, ms -> if (ms >= 100_000) junk[i % junk.size] else 472 }
        sim.run(110_000)
        assertTrue(sim.signals.any { it is HeartRateSignal.MaxReached && it.reduced }, "${sim.signals}")
        assertEquals(0.0, sim.value, "the block's minimum")
        assertTrue(sim.changes.any { it.third == HeartRateAdjustReason.MaxHeartRateReached })
    }

    @Test
    fun `between repetitions - a doubtful window at the end keeps the value, a sound one corrects it`() {
        val doubtful = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 8.0)
        doubtful.reported = { 137 }
        doubtful.beat = { i, _ -> junk[i % junk.size] }
        doubtful.run(60_000)
        assertEquals(8.0, doubtful.control.nextRepetitionValue(doubtful.t, doubtful.value), "kept")
        assertEquals(1, doubtful.control.summary(doubtful.t, doubtful.value).strapDeferrals)

        val sound = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 8.0)
        sound.truth = { 137 }
        sound.reported = { 137 }
        sound.run(60_000)
        assertEquals(6.0, sound.control.nextRepetitionValue(sound.t, sound.value), "7 bpm above the zone: -2 %")

        // The console: no RR, the correction as before.
        val console = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 8.0, source = HeartRateSourceKind.TreadmillConsole)
        console.reported = { 137 }
        console.run(60_000)
        assertEquals(6.0, console.control.nextRepetitionValue(console.t, console.value))
    }

    @Test
    fun `a window that cannot be judged - too few beats - decides as before`() {
        val sparse = Sim()
        sparse.reported = { ms -> if (ms >= 100_000) 137 else 127 }
        sparse.truth = { 127 }
        // 2-s beats (30 bpm worth of RR) leave 5 beats in 10 s.
        sparse.beat = { _, ms -> if (ms >= 90_000) 2_000 else 472 }
        sparse.run(125_000)
        assertEquals(listOf(Triple(120_000L, 8.0, HeartRateAdjustReason.AboveZone)), sparse.changes)
        assertFalse(sparse.status.decisionDeferredByStrap)
    }

    // ------------------------------------------------------------------ finding 2

    @Test
    fun `after a regular change none in the same direction for 60 s, then the next one comes at its decision`() {
        val sim = Sim()
        sim.truth = { 131 } // 1 bpm above the zone, steady
        sim.reported = { 131 }
        sim.run(200_000)
        assertEquals(listOf(20_000L, 80_000L, 140_000L, 200_000L), sim.times, "${sim.changes}")
        assertEquals(listOf(9.0, 8.0, 7.0, 6.0), sim.changes.map { it.second })
    }

    @Test
    fun `a heart rate that keeps clearly rising after a change is not held either - the next one waits for the 60 s`() {
        val sim = Sim()
        sim.truth = { ms -> 131 + (ms / 20_000).toInt() } // +3 bpm/min, above the zone all along, under the ceiling
        sim.reported = sim.truth
        sim.run(145_000)
        assertEquals(listOf(20_000L, 80_000L, 140_000L), sim.times, "nothing at 40 and 60 s: ${sim.changes}")
    }

    @Test
    fun `the wait does not hold the opposite direction`() {
        val sim = Sim()
        sim.truth = { ms -> if (ms < 105_000) 110 else 135 }
        sim.reported = sim.truth
        sim.run(125_000)
        // Below the zone from the first decision after 90 s: +2 % at 100 s; above it from 105 s: -1 % at the very next decision.
        assertEquals(listOf(Triple(100_000L, 12.0, HeartRateAdjustReason.BelowZone), Triple(120_000L, 11.0, HeartRateAdjustReason.AboveZone)), sim.changes)
    }

    @Test
    fun `the wait is cleared when the user gives the control back`() {
        val sim = Sim()
        sim.truth = { 131 }
        sim.reported = { 131 }
        sim.run(25_000)
        assertEquals(listOf(20_000L), sim.times)
        sim.control.manualOverride()
        sim.run(5_000)
        sim.control.reEnable(sim.t, sim.value)
        sim.run(5_000) // 10 s after the first change: the new start decides at once
        assertEquals(listOf(20_000L, 30_250L), sim.times, "${sim.changes}")
    }

    // ------------------------------------------------------------------ finding 4

    /** The block starts after a jump of load: 96 bpm climbing 10 bpm/min up to 118 (at 132 s), then steady (below the 122 zone). */
    private fun jumpedStart(config: HeartRateControlConfig = HeartRateControlConfig()) = Sim(config = config, start = 10.0, pre = 96).also { sim ->
        sim.truth = { ms -> if (ms < 132_000) 96 + (ms * 10 / 60_000).toInt() else 118 }
        sim.reported = sim.truth
    }

    @Test
    fun `no increase while the heart rate is still climbing, even if the forecast does not reach the zone`() {
        val sim = jumpedStart()
        sim.run(200_000)
        assertTrue(sim.changes.isNotEmpty(), "it does raise once the heart rate has settled")
        val first = sim.changes.first()
        assertEquals(HeartRateAdjustReason.BelowZone, first.third)
        assertTrue(first.first > 140_000, "no raise while climbing at ~10 bpm/min: ${sim.changes}")
        assertTrue(first.first <= 180_000, "${sim.changes}")
    }

    @Test
    fun `without the rule the same start is raised at 100 s - the rising-trend limit is what waits`() {
        val sim = jumpedStart(HeartRateControlConfig(risingTrendBpmPerMin = 1_000.0))
        sim.run(130_000)
        assertEquals(100_000L, sim.changes.first().first, "${sim.changes}")
    }

    @Test
    fun `a steady heart rate 6 bpm under the zone after 90 s is raised as always`() {
        val sim = Sim()
        sim.truth = { 116 }
        sim.reported = { 116 }
        sim.run(130_000)
        assertEquals(100_000L, sim.changes.first().first)
        assertEquals(HeartRateAdjustReason.BelowZone, sim.changes.first().third)
    }

    @Test
    fun `a climbing heart rate above the zone is still lowered - the limit is for increases only`() {
        val sim = Sim()
        sim.truth = { ms -> 126 + (ms / 4_000).toInt() } // +15 bpm/min through the zone and above it
        sim.reported = sim.truth
        sim.run(70_000)
        assertTrue(sim.changes.any { it.third == HeartRateAdjustReason.AboveZone || it.third == HeartRateAdjustReason.CeilingExceeded }, "${sim.changes}")
    }
}
