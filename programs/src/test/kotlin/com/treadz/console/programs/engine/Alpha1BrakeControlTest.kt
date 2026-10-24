package com.treadz.console.programs.engine

import com.treadz.console.analysis.thresholds.Alpha1Evaluation
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
 * The α1 brake on exact times, with the evaluations given directly (the estimator has its own tests): readings at 2 Hz,
 * ticks every 250 ms, an evaluation every 10 s of block time. Zone 114-126 bpm (zone 2 at 45-55 % of the reserve for a
 * maximum of 180 and a resting rate of 60), ceiling 134. The first evaluation whose window lies entirely inside the
 * block is the one at block time 120 s; the brake needs six counting evaluations (120..170 s) whose median is below 0.70,
 * so with α1 low from there it fires at 170 s, and then not again for 180 s.
 */
class Alpha1BrakeControlTest {

    /** A change of the adjusted quantity at block time [ms]. */
    private data class Change(val ms: Long, val from: Double, val to: Double, val reason: HeartRateAdjustReason)

    /** The regular control without its 60-s wait after a change in the same direction: these tests need regular reductions 20 s apart to check how the brake combines with them. */
    private val everyDecision = HeartRateControlConfig(sameDirectionHoldMs = 0)

    private class Sim(
        val adjust: HeartRateAdjust = HeartRateAdjust.Incline,
        mode: HeartRateControlMode = HeartRateControlMode.Continuous,
        val start: Double = 4.0,
        min: Double = 0.0,
        max: Double = 10.0,
        brakeActive: Boolean = true,
        val source: HeartRateSourceKind = HeartRateSourceKind.DirectStrap,
        val bpm: Int = 120,
        val evaluationOffsetMs: Long = 0,
        config: HeartRateControlConfig = HeartRateControlConfig(),
    ) {
        val grid = if (adjust == HeartRateAdjust.Speed) 0.1 else 1.0
        val tracker = HeartRateTracker(HeartRateProfile(180, 60), config)
        var t = 0L
        var value = start
        val blockStart: Long
        val control: HeartRateBlockControl

        /** Every change the control made. */
        val changes = mutableListOf<Change>()

        /** α1 of the evaluation at block time `ms`; `null` = a window that is not valid (no RR). */
        var alpha: (Long) -> Double? = { 0.5 }
        var artifacts: (Long) -> Double = { 0.01 }

        init {
            while (t < 30_000) {
                t += 500
                tracker.onReading(t, reading(bpm))
            }
            blockStart = t
            control = HeartRateBlockControl(
                0, adjust, mode, HeartRateZoneBpm(114, 126, HeartRateBasis.MaxHeartRate), 180, min, max, grid, start, start,
                tracker, config, t, alpha1BrakeActive = brakeActive,
            )
        }

        fun reading(b: Int?) = HeartRateReading(b, HeartRateValidity.Valid, t, source)

        fun evaluation(at: Long): Alpha1Evaluation {
            val a = alpha(at - blockStart)
            return Alpha1Evaluation(
                atMillis = at, windowStartMillis = at - 120_000L, beats = if (a != null) 240 else 40,
                artifactFraction = if (a != null) artifacts(at - blockStart) else 1.0, valid = a != null, alpha1 = a, heartRateBpm = 120.0,
            )
        }

        /** Runs for [ms]; [hr] gives the heart rate by block time (`null` = no reading). */
        fun run(ms: Long, hr: (Long) -> Int? = { bpm }) {
            val end = t + ms
            while (t < end) {
                t += 250
                if (t % 500 == 0L) control.onSample(t, tracker.onReading(t, reading(hr(t - blockStart))))
                if ((t - blockStart) % 10_000 == evaluationOffsetMs) control.onAlpha1(t, evaluation(t))
                control.update(t, t - blockStart, value)?.let {
                    val reason = control.pending.filterIsInstance<HeartRateSignal.Adjusted>().last().reason
                    changes += Change(t - blockStart, value, it, reason)
                    value = it
                }
                control.pending.clear()
            }
        }

        fun brakeChanges() = changes.filter { it.reason == HeartRateAdjustReason.Alpha1Brake }
        fun summary() = control.summary(t, value)
    }

    @Test
    fun `fires on the sixth counting evaluation, not on the fifth`() {
        val s = Sim()
        s.run(165_000) // evaluations at 120, 130, 140, 150 and 160 s are low: five
        assertTrue(s.changes.isEmpty(), "five low evaluations are not enough: ${s.changes}")
        s.run(10_000) // the sixth, at 170 s
        assertEquals(listOf(Change(170_000L, 4.0, 3.0, HeartRateAdjustReason.Alpha1Brake)), s.changes)
        assertEquals(3.0, s.value)
        assertEquals(1, s.summary().adjustments)
        assertEquals(1, s.summary().alpha1Reductions)
        assertEquals(0, s.summary().safetyReductions)
    }

    @Test
    fun `the trigger is the median of the six, so one high evaluation does not cancel the signal`() {
        val s = Sim()
        s.alpha = { ms -> if (ms == 130_000L) 0.9 else 0.5 }
        s.run(175_000) // 0.5, 0.9, 0.5, 0.5, 0.5, 0.5: median 0.5
        assertEquals(listOf(Change(170_000L, 4.0, 3.0, HeartRateAdjustReason.Alpha1Brake)), s.changes)
    }

    @Test
    fun `half of the evaluations above the trigger are enough to stop it`() {
        // Three low (0.5) and three high (1.0): the median is 0.75.
        val half = Sim()
        half.alpha = { ms -> if (ms < 150_000L) 0.5 else 1.0 }
        half.run(175_000)
        assertTrue(half.changes.isEmpty(), "median 0.75: ${half.changes}")
        // Four low and two high: the median is 0.5.
        val most = Sim()
        most.alpha = { ms -> if (ms < 160_000L) 0.5 else 1.0 }
        most.run(175_000)
        assertEquals(1, most.changes.size)
    }

    @Test
    fun `a short low episode and then a normal one never fire`() {
        val s = Sim()
        s.alpha = { ms -> if (ms in 120_000L..130_000L) 0.4 else 1.0 }
        s.run(400_000)
        assertTrue(s.changes.isEmpty())
        assertEquals(0, s.summary().alpha1Reductions, "active, with valid windows, never fired")
    }

    @Test
    fun `a low episode right after a reduction cannot cause a second one within 180 s`() {
        // The situation of the improvement plan: the first reduction, and α1 still low in the windows that mostly hold
        // beats from before it. The second one must wait for 180 s and for six evaluations that all come after the first.
        val s = Sim()
        s.alpha = { ms -> if (ms in 120_000L..260_000L) 0.4 else 1.0 }
        s.run(700_000)
        assertEquals(listOf(170_000L), s.brakeChanges().map { it.ms }, "${s.changes}")
        // With α1 low all along, the second reduction comes at 350 s: 180 s later, and not earlier.
        val low = Sim()
        low.run(700_000)
        assertEquals(listOf(170_000L, 350_000L, 530_000L), low.brakeChanges().map { it.ms })
        assertTrue(low.brakeChanges().zipWithNext().all { (a, b) -> b.ms - a.ms >= 180_000L })
    }

    @Test
    fun `the trigger is below 0_70`() {
        val s = Sim()
        s.alpha = { 0.70 }
        s.run(300_000)
        assertTrue(s.changes.isEmpty())
        val t = Sim()
        t.alpha = { 0.69 }
        t.run(175_000)
        assertEquals(1, t.changes.size)
    }

    @Test
    fun `reductions are at least 180 s apart and stop at the block minimum`() {
        val s = Sim(adjust = HeartRateAdjust.Speed, start = 9.0, min = 8.5, max = 12.0)
        s.run(900_000)
        // 170 s, then six evaluations after it and at least 180 s later (350 s); the second lands on the minimum and nothing follows.
        assertEquals(listOf(170_000L, 350_000L), s.brakeChanges().map { it.ms })
        assertEquals(listOf(8.7, 8.5), s.brakeChanges().map { it.to })
        assertEquals(8.5, s.value)
        assertEquals(2, s.summary().alpha1Reductions)
        assertTrue(s.changes.all { it.to < it.from }, "only ever lowers: ${s.changes}")
    }

    @Test
    fun `without a minimum in the way it keeps lowering every 180 s`() {
        val s = Sim(start = 10.0, min = 0.0)
        s.run(1_300_000)
        assertEquals(listOf(170_000L, 350_000L, 530_000L, 710_000L, 890_000L, 1_070_000L), s.brakeChanges().map { it.ms }.take(6))
        assertEquals(listOf(9.0, 8.0, 7.0, 6.0, 5.0, 4.0), s.brakeChanges().map { it.to }.take(6))
    }

    @Test
    fun `running adjusts the speed in steps of 0_3 km h, walking the incline in steps of 1 percent`() {
        val run = Sim(adjust = HeartRateAdjust.Speed, start = 9.0, min = 7.0, max = 12.0)
        run.run(360_000)
        assertEquals(listOf(8.7, 8.4), run.brakeChanges().map { it.to })
        val walk = Sim(adjust = HeartRateAdjust.Incline, start = 6.0, min = 0.0, max = 12.0)
        walk.run(360_000)
        assertEquals(listOf(5.0, 4.0), walk.brakeChanges().map { it.to })
    }

    @Test
    fun `the step is cut at the block minimum, and nothing happens at it`() {
        val s = Sim(adjust = HeartRateAdjust.Speed, start = 8.0, min = 7.9, max = 12.0)
        s.run(175_000)
        assertEquals(listOf(7.9), s.brakeChanges().map { it.to })
        s.run(300_000)
        assertEquals(1, s.changes.size, "already at the minimum: no more orders")
        val atMinimum = Sim(start = 0.0, min = 0.0)
        atMinimum.run(300_000)
        assertTrue(atMinimum.changes.isEmpty())
        assertEquals(0, atMinimum.summary().alpha1Reductions)
    }

    @Test
    fun `evaluations with more than 3 percent artifacts do not count`() {
        val s = Sim()
        s.artifacts = { 0.04 }
        s.run(400_000)
        assertTrue(s.changes.isEmpty())
        val edge = Sim()
        edge.artifacts = { 0.03 }
        edge.run(175_000)
        assertEquals(1, edge.changes.size, "3 % is allowed")
    }

    @Test
    fun `windows that are not valid disable the brake and restart the count`() {
        val s = Sim()
        // Valid and low at 120 and 130, not valid at 140 (RR missing or more than 5 % artifacts), low again from 150.
        s.alpha = { ms -> if (ms == 140_000L) null else 0.4 }
        s.run(195_000)
        assertTrue(s.changes.isEmpty(), "the count restarted at 140 s: ${s.changes}")
        s.run(10_000) // 150..200: the sixth in a row after the gap
        assertEquals(listOf(200_000L), s.brakeChanges().map { it.ms })
        // Without any valid window the brake never acts and reports nothing.
        val none = Sim()
        none.alpha = { null }
        none.run(400_000)
        assertTrue(none.changes.isEmpty())
        assertNull(none.summary().alpha1Reductions)
        assertNull(none.summary().alpha1Mean)
        assertNull(none.summary().alpha1Windows)
    }

    @Test
    fun `the heart rate must be at or above the zone's lower limit`() {
        val low = Sim(bpm = 110)
        low.run(300_000, hr = { 110 })
        assertTrue(low.brakeChanges().isEmpty(), "under the zone the regular control raises the load; the brake does not act")
        val edge = Sim(bpm = 114)
        edge.run(175_000, hr = { 114 })
        assertEquals(1, edge.brakeChanges().size)
        // Above the zone counts too (the regular control has been lowering the load meanwhile).
        val above = Sim(start = 10.0, bpm = 130, config = everyDecision)
        above.run(185_000, hr = { 130 })
        assertEquals(listOf(180_000L), above.brakeChanges().map { it.ms }, "the regular control lowered the load at 160 s: ${above.changes}")
    }

    @Test
    fun `only a strap connected to the app counts`() {
        val s = Sim(source = HeartRateSourceKind.TreadmillConsole)
        s.run(400_000)
        assertTrue(s.changes.isEmpty())
        // The windows are recorded (they were valid), the brake just cannot act.
        assertEquals(0, s.summary().alpha1Reductions)
    }

    @Test
    fun `inactive in the blocks that are not easy - the evaluations are recorded anyway`() {
        val s = Sim(brakeActive = false)
        s.run(400_000)
        assertTrue(s.changes.isEmpty())
        val summary = s.summary()
        assertNull(summary.alpha1Reductions)
        assertEquals(0.5, summary.alpha1Mean)
        assertEquals(29, summary.alpha1Windows, "evaluations at 120..400 s")
    }

    @Test
    fun `never raises the load - not even when the heart rate falls under the zone`() {
        val s = Sim(start = 6.0, min = 0.0)
        // The heart rate drops under the zone after the first reduction (a real response to a lower load) while α1 stays low.
        s.run(900_000, hr = { ms -> if (ms < 175_000) 120 else 100 })
        assertEquals(1, s.brakeChanges().size, "under the zone the brake does not act: ${s.changes}")
        assertTrue(s.brakeChanges().all { it.to < it.from })
        // The only increases are the regular control's own, never the brake's.
        assertTrue(s.changes.any { it.to > it.from })
        assertTrue(s.changes.filter { it.to > it.from }.all { it.reason == HeartRateAdjustReason.BelowZone })
    }

    @Test
    fun `an α1 reduction counts as a decision - no upward correction for 90 s`() {
        val s = Sim(start = 6.0)
        // The heart rate falls under the zone after the reduction at 140 s.
        s.alpha = { ms -> if (ms <= 150_000L) 0.4 else 1.0 }
        s.run(400_000, hr = { ms -> if (ms < 175_000) 120 else 105 })
        assertEquals(Change(170_000L, 6.0, 5.0, HeartRateAdjustReason.Alpha1Brake), s.changes.first())
        val raise = s.changes.first { it.reason == HeartRateAdjustReason.BelowZone }
        assertTrue(raise.ms >= 170_000L + 90_000L, "no upward correction in the 90 s after the reduction: $raise")
        assertTrue(raise.ms <= 170_000L + 90_000L + 20_000L, "but then the regular control raises it: $raise")
    }

    @Test
    fun `the regular decision due at the same moment is postponed a full interval`() {
        val s = Sim(start = 6.0, config = everyDecision)
        // Above the zone (and under the ceiling) from 100 s: regular reductions at 120, 140 and 160 s. The sixth evaluation
        // (170 s) finds a change 10 s old, so the brake waits for 180 s, where it goes before the regular decision.
        s.run(300_000, hr = { ms -> if (ms < 100_000) 120 else 130 })
        assertEquals(Change(120_000L, 6.0, 5.0, HeartRateAdjustReason.AboveZone), s.changes[0])
        assertEquals(Change(160_000L, 4.0, 3.0, HeartRateAdjustReason.AboveZone), s.changes[2])
        assertEquals(Change(180_000L, 3.0, 2.0, HeartRateAdjustReason.Alpha1Brake), s.changes[3])
        // The regular decision due at 180 s waited: the next change is at 200 s.
        assertEquals(200_000L, s.changes[4].ms, "${s.changes}")
        assertTrue(s.changes.zipWithNext().all { (a, b) -> b.ms - a.ms >= 20_000L })
    }

    @Test
    fun `a change of the control in the last 20 s delays the brake`() {
        // Evaluations at 125, 135 ... 175 s: the sixth would fire at 175 s, but a regular reduction (heart rate above the
        // zone) happened at 160 s.
        val s = Sim(start = 6.0, evaluationOffsetMs = 5_000, config = everyDecision)
        s.run(300_000, hr = { ms -> if (ms < 130_000) 120 else 130 })
        assertEquals(Change(140_000L, 6.0, 5.0, HeartRateAdjustReason.AboveZone), s.changes.first())
        assertEquals(180_000L, s.brakeChanges().first().ms, "${s.changes}")
    }

    @Test
    fun `a manual change switches the brake off until the block ends`() {
        val s = Sim()
        s.run(125_000)
        s.control.manualOverride()
        s.run(400_000)
        assertTrue(s.changes.isEmpty())
        assertTrue(s.control.isOff)
        // The windows are still recorded.
        assertNotNull(s.summary().alpha1Mean)
        assertTrue(s.summary().manualOverride)
    }

    @Test
    fun `after giving control back only windows entirely after that moment count`() {
        val s = Sim()
        s.run(125_000)
        s.control.manualOverride()
        s.run(75_000) // 200 s
        s.control.reEnable(s.t, s.value)
        s.run(165_000) // 365 s: the first complete window after the re-enable is at 320 s, the sixth low one at 370 s
        assertTrue(s.changes.isEmpty(), "${s.changes}")
        s.run(10_000)
        assertEquals(listOf(370_000L), s.brakeChanges().map { it.ms })
    }

    @Test
    fun `after a pause only windows entirely after the resume count`() {
        val s = Sim()
        s.run(200_000)
        assertEquals(1, s.brakeChanges().size)
        s.control.onResume(s.t) // at 200 s
        s.run(165_000) // 365 s
        assertEquals(1, s.changes.size, "nothing new before the sixth window after the resume: ${s.changes}")
        s.run(10_000)
        assertEquals(listOf(170_000L, 370_000L), s.brakeChanges().map { it.ms })
    }

    @Test
    fun `the reductions are counted in the summary together with the other adjustments`() {
        val s = Sim(start = 6.0)
        s.run(300_000, hr = { ms -> if (ms < 100_000) 120 else 130 })
        val summary = s.summary()
        assertEquals(s.changes.size, summary.adjustments)
        assertEquals(s.brakeChanges().size, summary.alpha1Reductions)
        assertEquals(0, summary.safetyReductions)
    }

    @Test
    fun `mean α1 of the valid windows inside the block`() {
        val s = Sim()
        s.alpha = { ms -> when { ms < 150_000L -> 0.6; ms < 200_000L -> null; else -> 1.0 } }
        s.run(260_000)
        val summary = s.summary()
        // Valid windows at 120, 130, 140 (0.6) and 200..260 (1.0); the ones at 150..190 are not valid.
        assertEquals(10, summary.alpha1Windows)
        assertEquals(0.88, summary.alpha1Mean)
    }

    @Test
    fun `windows that start before the block are not used`() {
        val s = Sim()
        s.run(115_000) // evaluations at 10..110 s have windows that start before the block
        assertNull(s.summary().alpha1Windows)
        assertTrue(s.changes.isEmpty())
    }

    @Test
    fun `disabled in the configuration`() {
        val zone = HeartRateZoneBpm(114, 126, HeartRateBasis.MaxHeartRate)
        val active = Alpha1BrakePolicy.appliesTo(zone, HeartRateControlMode.Continuous, HeartRateProfile(180, 60), Alpha1BrakeConfig(enabled = false))
        val s = Sim(brakeActive = active)
        s.run(400_000)
        assertTrue(s.changes.isEmpty())
    }

    // ------------------------------------------------------------------ which blocks get the brake

    private val config = Alpha1BrakeConfig()
    private val profile = HeartRateProfile(180, 60)

    private fun applies(low: Int, high: Int, p: HeartRateProfile = profile, mode: HeartRateControlMode = HeartRateControlMode.Continuous, c: Alpha1BrakeConfig = config) =
        Alpha1BrakePolicy.appliesTo(HeartRateZoneBpm(low, high, HeartRateBasis.MaxHeartRate), mode, p, c)

    @Test
    fun `with a measured VT1 the zone ceiling must be at or below it`() {
        val p = HeartRateProfile(180, 60, vt1Bpm = 140)
        assertTrue(applies(130, 140, p))
        assertTrue(applies(130, 143, p), "three bpm of tolerance for zones stored as whole percentages of the reserve")
        assertFalse(applies(130, 144, p))
        // A threshold lower than the table's zone 2 ceiling: the measured value wins (zone 2 of a sedentary person).
        assertFalse(applies(114, 126, HeartRateProfile(180, 60, vt1Bpm = 120)))
        // A VT1 above 65 % of the reserve: its zone 2 gets the brake, which the table rule alone would not give.
        assertTrue(applies(130, 148, HeartRateProfile(180, 60, vt1Bpm = 148)))
        assertFalse(applies(130, 148, HeartRateProfile(180, 60)))
        // An implausible VT1 is ignored.
        assertTrue(applies(114, 126, HeartRateProfile(180, 60, vt1Bpm = 20)))
    }

    @Test
    fun `without a measured VT1 only zone 2 blocks get the brake`() {
        // 65 % of the reserve = 60 + 0.65 * 120 = 138 bpm.
        assertTrue(applies(114, 126), "zone 2 at 45-55 % of the reserve")
        assertTrue(applies(108, 126), "60-70 % of the maximum")
        assertTrue(applies(120, 138))
        assertFalse(applies(120, 139))
        assertFalse(applies(132, 156), "zone 3, 60-80 % of the reserve")
        assertFalse(applies(126, 144), "70-80 % of the maximum")
        assertFalse(applies(153, 171), "zone 4")
        // No resting rate: the equivalent percentage of the maximum (65 % of the reserve ~ 77 % of the maximum, 139 bpm).
        val noRest = HeartRateProfile(180)
        assertTrue(applies(108, 126, noRest))
        assertFalse(applies(126, 144, noRest))
    }

    @Test
    fun `never between repetitions, and not when disabled`() {
        assertFalse(applies(114, 126, mode = HeartRateControlMode.BetweenRepetitions))
        assertFalse(applies(114, 126, c = Alpha1BrakeConfig(enabled = false)))
    }
}
