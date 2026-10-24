package com.treadz.console.programs.engine

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.heartrate.HeartRateZoneBpm
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules of the heart-rate control on exact times: readings at 2 Hz (each value repeated, like the console), ticks
 * every 250 ms. Zone 126-144 bpm (70-80 % of 180), ceiling 152.
 */
class HeartRateBlockControlTest {

    private class Sim(
        adjust: HeartRateAdjust = HeartRateAdjust.Speed,
        mode: HeartRateControlMode = HeartRateControlMode.Continuous,
        val start: Double = 10.0,
        initial: Double = start,
        min: Double = 6.0,
        max: Double = 12.0,
        grid: Double = if (adjust == HeartRateAdjust.Speed) 0.1 else 1.0,
        config: HeartRateControlConfig = HeartRateControlConfig(),
        /** Readings before the block (so the block starts with a valid heart rate). */
        preBpm: Int? = 130,
    ) {
        val tracker = HeartRateTracker(HeartRateProfile(180, 60), config)
        var t = 0L
        var value = initial
        val changes = mutableListOf<Pair<Long, Double>>()
        val signals = mutableListOf<HeartRateSignal>()
        /** Block time of every signal. */
        val signalTimes = mutableListOf<Pair<Long, HeartRateSignal>>()
        var bpm: Int? = preBpm
        val blockStart: Long
        val control: HeartRateBlockControl

        init {
            // 30 s of history before the block.
            while (t < 30_000) {
                t += 500
                tracker.onReading(t, reading(bpm))
            }
            blockStart = t
            control = HeartRateBlockControl(
                0, adjust, mode, HeartRateZoneBpm(126, 144, HeartRateBasis.MaxHeartRate), 180, min, max, grid, start, initial,
                tracker, config, t,
            )
        }

        fun reading(b: Int?) = if (b == null) HeartRateReading(null, HeartRateValidity.Invalid, t) else HeartRateReading(b, HeartRateValidity.Unconfirmed, t, HeartRateSourceKind.TreadmillConsole)

        /** Runs for [ms] with [hr] giving the heart rate at each reading (block time in ms). */
        fun run(ms: Long, hr: (Long) -> Int? = { bpm }) {
            val end = t + ms
            while (t < end) {
                t += 250
                if (t % 500 == 0L) {
                    bpm = hr(t - blockStart)
                    val f = tracker.onReading(t, reading(bpm))
                    control.onSample(t, f)
                }
                control.update(t, t - blockStart, value)?.let {
                    value = it
                    changes += (t - blockStart) to it
                }
                signals += control.pending
                control.pending.forEach { signalTimes += (t - blockStart) to it }
                control.pending.clear()
            }
        }

        val state get() = control.state(t)
        inline fun <reified T : HeartRateSignal> signalsOf() = signals.filterIsInstance<T>()
    }

    @Test
    fun `inside the zone nothing changes`() {
        val s = Sim()
        s.run(600_000) { 135 }
        assertEquals(emptyList<Pair<Long, Double>>(), s.changes)
        assertEquals(HeartRateControlState.Active, s.state)
    }

    @Test
    fun `above the zone the step is proportional and decisions come every 20 s`() {
        // Without the wait after a change in the same direction (tested on its own below).
        val s = Sim(config = HeartRateControlConfig(sameDirectionHoldMs = 0))
        s.run(70_000) { 150 } // 6 bpm above: 6 x 0.05 = 0.3 km/h
        assertEquals(listOf(20_000L to 9.7, 40_000L to 9.4, 60_000L to 9.1), s.changes)
        val adj = s.signalsOf<HeartRateSignal.Adjusted>()
        assertTrue(adj.all { it.reason == HeartRateAdjustReason.AboveZone })
        assertEquals(150.0, adj.first().heartRateBpm)
    }

    @Test
    fun `steps are bounded and faster down than up`() {
        val s = Sim()
        assertEquals(0.1, s.control.step(1.0, down = false))
        assertEquals(0.3, s.control.step(10.0, down = false))
        assertEquals(0.5, s.control.step(10.0, down = true))
        assertEquals(0.5, s.control.step(40.0, down = false))
        val incline = Sim(HeartRateAdjust.Incline, start = 5.0, min = 0.0, max = 15.0)
        assertEquals(1.0, incline.control.step(1.0, down = false), "0.5 % rounds to the treadmill step of 1 %")
        assertEquals(2.0, incline.control.step(10.0, down = true))
        assertEquals(2.0, incline.control.step(10.0, down = false)) // 1.5 % on a 1 % grid rounds to 2
        val fine = Sim(HeartRateAdjust.Incline, start = 5.0, min = 0.0, max = 15.0, grid = 0.5)
        assertEquals(0.5, fine.control.step(1.0, down = false))
        assertEquals(1.5, fine.control.step(10.0, down = false))
    }

    @Test
    fun `no upward correction in the first 90 s of the block`() {
        val s = Sim(start = 8.0, config = HeartRateControlConfig(sameDirectionHoldMs = 0))
        s.run(130_000) { 110 } // far below the zone and flat
        assertEquals(100_000L, s.changes.first().first, "first decision after 90 s is at 100 s")
        assertEquals(8.5, s.changes.first().second)
        assertEquals(120_000L to 9.0, s.changes[1])
    }

    @Test
    fun `while waiting the state says so`() {
        val s = Sim(start = 8.0)
        s.run(45_000) { 110 }
        assertEquals(HeartRateControlState.Waiting, s.state)
    }

    @Test
    fun `a trend entering the zone makes it wait`() {
        val s = Sim(preBpm = 151)
        // Falling 0.4 bpm/s from 151: at 20 s the mean is ~145 (above the zone) but the forecast ~133: wait.
        s.run(25_000) { b -> (151 - 0.4 * b / 1000).toInt() }
        assertEquals(emptyList<Pair<Long, Double>>(), s.changes)
        assertEquals(HeartRateControlState.Waiting, s.state)
        s.run(20_000) { b -> (151 - 0.4 * b / 1000).toInt() }
        assertEquals(emptyList<Pair<Long, Double>>(), s.changes, "it entered the zone by itself")
        assertEquals(HeartRateControlState.Active, s.state)
    }

    @Test
    fun `bounds are respected`() {
        val s = Sim(start = 6.4)
        s.run(400_000) { 150 }
        assertEquals(6.0, s.value)
        assertEquals(listOf(6.1, 6.0), s.changes.map { it.second })
        val up = Sim(start = 11.8, preBpm = 100) // (from 130 a sudden fall to 100 would be an impossible drop)
        up.run(400_000) { 100 }
        assertEquals(12.0, up.value)
    }

    @Test
    fun `above the ceiling for 10 s - sharp reduction, then at least 20 s between reductions`() {
        val s = Sim()
        s.run(5_000) { 135 }
        s.run(60_000) { 160 } // ceiling 152
        val ceiling = s.signalTimes.filter { (_, sig) -> sig is HeartRateSignal.Adjusted && sig.reason == HeartRateAdjustReason.CeilingExceeded }
        assertTrue(ceiling.size >= 2, "$ceiling")
        val first = ceiling.first().second as HeartRateSignal.Adjusted
        assertEquals(10.0, first.from)
        assertEquals(9.0, first.to)
        assertEquals(15_500L, ceiling.first().first, "10 s above the ceiling (first reading above at 5.5 s)")
        val times = ceiling.map { it.first }
        for (i in 1 until times.size) assertTrue(times[i] - times[i - 1] >= 20_000, "$times")
        // The regular decisions wait a full interval after a safety reduction.
        val all = s.signalTimes.filter { it.second is HeartRateSignal.Adjusted }.map { it.first }
        for (i in 1 until all.size) assertTrue(all[i] - all[i - 1] >= 20_000, "$all")
        assertTrue(s.signalsOf<HeartRateSignal.CeilingExceeded>().all { it.reduced && it.ceilingBpm == 152 })
        assertEquals(HeartRateControlState.Ceiling, s.state)
    }

    @Test
    fun `reaching the maximum goes to the recovery value at once`() {
        val s = Sim()
        s.run(5_000) { 135 }
        s.run(5_000) { 181 }
        assertEquals(6.0, s.value)
        val max = s.signalsOf<HeartRateSignal.MaxReached>().single()
        assertEquals(180, max.maxBpm)
        assertTrue(max.reduced)
        assertEquals(HeartRateAdjustReason.MaxHeartRateReached, s.signalsOf<HeartRateSignal.Adjusted>().last().reason)
    }

    @Test
    fun `no heart rate - hold 60 s, back to the start value if lower, resume after 10 s of valid readings`() {
        val s = Sim(start = 8.0)
        s.run(150_000) { 110 } // the control raised the speed
        val raised = s.value
        assertTrue(raised > 8.0)
        val n = s.changes.size
        s.run(30_000) { null }
        assertEquals(HeartRateControlState.HoldingNoHeartRate, s.state)
        assertEquals(n, s.changes.size, "nothing changes while holding")
        s.run(32_000) { null }
        assertEquals(8.0, s.value, "after 60 s the lower start value is restored")
        assertEquals(HeartRateAdjustReason.NoHeartRateFallback, s.signalsOf<HeartRateSignal.Adjusted>().last().reason)
        s.run(60_000) { null }
        assertEquals(8.0, s.value, "the fallback happens once")
        s.run(9_000) { 150 }
        assertEquals(HeartRateControlState.HoldingNoHeartRate, s.state, "less than 10 s of valid readings")
        s.run(2_000) { 150 }
        assertTrue(s.value < 8.0, "control resumed and corrected downwards")
    }

    @Test
    fun `without heart rate a lower current value is kept`() {
        val s = Sim(start = 10.0, initial = 9.0)
        s.run(70_000) { null }
        assertEquals(9.0, s.value)
        assertTrue(s.changes.isEmpty())
    }

    @Test
    fun `impossible readings count as no heart rate`() {
        // Below 40 bpm
        val low = Sim(start = 8.0)
        low.run(200_000) { 35 }
        assertEquals(HeartRateControlState.HoldingNoHeartRate, low.state)
        assertEquals(8.0, low.value)
        // Above the maximum + 15
        val high = Sim()
        high.run(20_000) { 200 }
        assertEquals(HeartRateControlState.HoldingNoHeartRate, high.state)
        assertTrue(high.signalsOf<HeartRateSignal.MaxReached>().isEmpty())
        // A single spike is ignored
        val spike = Sim()
        spike.run(30_000) { b -> if (b in 10_000L..10_999L) 185 else 135 }
        assertTrue(spike.signals.none { it is HeartRateSignal.MaxReached || it is HeartRateSignal.CeilingExceeded })
        assertEquals(HeartRateControlState.Active, spike.state)
    }

    @Test
    fun `the console's strap-removed pattern is not taken as a low heart rate`() {
        val s = Sim(start = 9.0)
        s.run(120_000) { 140 }
        // Strap taken off: the console drops to 73-80 at once and keeps showing values there.
        s.run(300_000) { b -> 73 + ((b / 1000) % 8).toInt() }
        assertTrue(s.signalsOf<HeartRateSignal.Adjusted>().none { it.reason == HeartRateAdjustReason.BelowZone }, "${s.changes}")
        assertEquals(HeartRateControlState.HoldingNoHeartRate, s.state)
        assertEquals(9.0, s.value)
        // The strap is put back on: the heart rate jumps up and the control resumes after 10 s.
        s.run(15_000) { 138 }
        assertEquals(HeartRateControlState.Active, s.state)
    }

    @Test
    fun `manual change switches the control off until re-enabled`() {
        val s = Sim()
        s.control.manualOverride()
        s.run(120_000) { 150 }
        assertTrue(s.changes.isEmpty())
        assertEquals(HeartRateControlState.OffByManualChange, s.state)
        // Even off, a ceiling episode is reported once, without a reduction.
        s.run(30_000) { 160 }
        val ceiling = s.signalsOf<HeartRateSignal.CeilingExceeded>()
        assertEquals(1, ceiling.size)
        assertFalse(ceiling.single().reduced)
        s.value = 11.0
        s.control.reEnable(s.t, 11.0)
        s.run(2_000) { 150 }
        assertNotNull(s.changes.firstOrNull())
        assertTrue(s.value < 11.0)
    }

    @Test
    fun `between repetitions - no regular decisions inside, correction for the next one`() {
        val s = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 9.0)
        s.run(240_000) { 150 } // above the zone but below the ceiling: nothing inside the repetition
        assertTrue(s.changes.isEmpty())
        assertEquals(8.7, s.control.nextRepetitionValue(s.t, s.value), "6 bpm above: -0.3")
        val below = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 9.0)
        below.run(240_000) { 116 }
        assertEquals(9.3, below.control.nextRepetitionValue(below.t, below.value), "10 bpm below: +0.3")
        val inZone = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 9.0)
        inZone.run(240_000) { 140 }
        assertEquals(9.0, inZone.control.nextRepetitionValue(inZone.t, inZone.value))
        assertEquals(9.0, inZone.control.summary(inZone.t, 9.0).settledValue)
        val noHr = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 9.0, initial = 9.6)
        noHr.run(240_000) { null }
        assertEquals(9.0, noHr.control.nextRepetitionValue(noHr.t, noHr.value), "no heart rate at the end: the lower start value")
    }

    @Test
    fun `between repetitions - safety reductions still apply inside`() {
        val s = Sim(mode = HeartRateControlMode.BetweenRepetitions, start = 9.0)
        s.run(30_000) { 160 }
        assertEquals(8.0, s.value)
    }

    @Test
    fun `summary with the settled value`() {
        val s = Sim(start = 9.0)
        s.run(300_000) { 135 }
        val summary = s.control.summary(s.t, s.value)
        assertEquals(9.0, summary.settledValue)
        assertEquals(300, summary.secondsInZone)
        assertEquals(135.0, summary.meanHeartRateBpm)
        assertEquals(135.0, summary.endHeartRateBpm)
        assertEquals(HeartRateSourceKind.TreadmillConsole, summary.source)
        val never = Sim(start = 9.0)
        never.run(300_000) { 110 }
        assertNull(never.control.summary(never.t, never.value).settledValue)
    }

    @Test
    fun `mode is derived from the structure`() {
        val c = HeartRateControlConfig()
        assertEquals(HeartRateControlMode.BetweenRepetitions, HeartRateBlockControl.modeOf(true, 240, c))
        assertEquals(HeartRateControlMode.Continuous, HeartRateBlockControl.modeOf(true, 360, c))
        assertEquals(HeartRateControlMode.Continuous, HeartRateBlockControl.modeOf(false, 240, c))
    }
}
