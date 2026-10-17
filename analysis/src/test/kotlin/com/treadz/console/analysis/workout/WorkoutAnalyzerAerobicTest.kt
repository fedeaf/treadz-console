package com.treadz.console.analysis.workout

import com.treadz.console.analysis.workout.WorkoutFixtures.context
import com.treadz.console.analysis.workout.WorkoutFixtures.session
import com.treadz.console.analysis.samples.WorkoutSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkoutAnalyzerAerobicTest {

    private fun analyse(samples: List<WorkoutSample>, ctx: WorkoutAnalysisContext = context()) = WorkoutAnalyzer.analyse(samples, ctx)

    /** Heart rate rising during the warm-up, [hr1] until [splitSecond] and [hr2] after it. */
    private fun drift(hr1: Int, hr2: Int, splitSecond: Int): (Int) -> Int = { t ->
        when {
            t < 300 -> 100 + t / 10
            t < splitSecond -> hr1
            else -> hr2
        }
    }

    // ------------------------------------------------------------------ decoupling

    @Test
    fun `decoupling of an imposed drift`() {
        // 40 min at 6 km/h and 5 %; the analysed part is 300..2399 s, halves of 1050 s: 130 bpm then 143 bpm.
        val a = analyse(session(2400, speed = { 6.0 }, incline = { 5.0 }, heartRate = drift(130, 143, 1350)))
        val d = a.decoupling.value!!
        assertEquals(22.5 / 130.0, d.ef1, 1e-9)
        assertEquals(22.5 / 143.0, d.ef2, 1e-9)
        assertEquals((1.0 - 130.0 / 143.0) * 100.0, d.decouplingPercent, 1e-9)
        assertEquals(2400, d.mainBlockSeconds)
        assertEquals(2100, d.analysedSeconds)
    }

    @Test
    fun `no drift gives no decoupling`() {
        val d = analyse(session(2400, speed = { 6.0 }, incline = { 5.0 }, heartRate = drift(130, 130, 1350))).decoupling.value!!
        assertEquals(0.0, d.decouplingPercent, 1e-9)
    }

    @Test
    fun `decoupling uses the computed VO2 when the incline changes`() {
        // The incline rises from 4 % to 6 % and the heart rate in proportion to the oxygen demand: EF stays constant.
        val speed = 6.0
        fun vo2(i: Double) = 3.5 + 0.1 * speed * 1000 / 60 + 1.8 * speed * 1000 / 60 * i / 100
        val s = session(
            2400, speed = { speed },
            incline = { if (it < 1350) 4.0 else 6.0 },
            heartRate = { t -> if (t < 300) 100 + t / 10 else if (t < 1350) (vo2(4.0) * 6).toInt() else (vo2(6.0) * 6).toInt() },
        )
        val d = analyse(s).decoupling.value!!
        // The integer heart rates make it not exactly 0, but far from a drift of several percent.
        assertEquals(0.0, d.decouplingPercent, 1.0)
    }

    @Test
    fun `a trailing cool-down is not part of the main block`() {
        // 30 min at 6 km/h and 5 % (130 then 143 bpm after the warm-up), then 5 min cool-down at 3 km/h, 0 %.
        val s = session(
            2100,
            speed = { if (it < 1800) 6.0 else 3.0 },
            incline = { if (it < 1800) 5.0 else 0.0 },
            heartRate = { t -> if (t < 1800) drift(130, 143, 1050)(t) else 100 },
        )
        val d = analyse(s).decoupling.value!!
        assertEquals(1800, d.mainBlockSeconds)
        assertEquals(1500, d.analysedSeconds)
        assertEquals((1.0 - 130.0 / 143.0) * 100.0, d.decouplingPercent, 1e-9)
    }

    @Test
    fun `decoupling needs 20 minutes after the warm-up`() {
        assertEquals(AnalysisReason.TooShort, analyse(session(1499, heartRate = drift(130, 140, 900))).decoupling.reason)
        assertNotNull(analyse(session(1500, heartRate = drift(130, 140, 900))).decoupling.value)
        assertEquals(AnalysisReason.TooShort, analyse(session(200)).decoupling.reason)
    }

    @Test
    fun `an interrupted block has no decoupling`() {
        // 40 min with the belt stopped from 1000 to 1300 s: 1800 of the 2100 analysed seconds are moving (86 %).
        val s = session(2400, speed = { if (it in 1000 until 1300) 0.0 else 6.0 }, heartRate = drift(130, 143, 1350))
        assertEquals(AnalysisReason.Interrupted, analyse(s).decoupling.reason)
    }

    @Test
    fun `long steady zone 2 session`() {
        // Zone 2 is 114-126 bpm.
        val z2 = analyse(session(2400, heartRate = drift(118, 124, 1350))).decoupling.value!!
        assertTrue(z2.longSteadyZone2)
        assertEquals(1.0, z2.zone12Fraction!!, 1e-9)
        // Zone 3 (126-150 bpm): decoupling computed, but not a zone 2 session.
        val z3 = analyse(session(2400, heartRate = drift(135, 145, 1350))).decoupling.value!!
        assertFalse(z3.longSteadyZone2)
        assertEquals(0.0, z3.zone12Fraction!!, 1e-9)
        // Three quarters in zones 1-2 is the limit.
        val mixed = analyse(session(2400, heartRate = { t -> if (t < 300) 100 else if (t < 300 + 1575) 120 else 140 })).decoupling.value!!
        assertEquals(0.75, mixed.zone12Fraction!!, 1e-9)
        assertTrue(mixed.longSteadyZone2)
    }

    @Test
    fun `without zones the decoupling has no classification`() {
        val d = analyse(session(2400, heartRate = drift(118, 124, 1350)), context(zones = null)).decoupling.value!!
        assertNull(d.zone12Fraction)
        assertFalse(d.longSteadyZone2)
    }

    // ------------------------------------------------------------------ fitness index

    @Test
    fun `fitness index of one steady stretch`() {
        // 6 km/h, 5 %: VO2 22.5; HRr = (120 - 60) / 120 = 0.5; VO2max = 3.5 + 19 / 0.5 = 41.5.
        val f = analyse(session(600, speed = { 6.0 }, incline = { 5.0 }, heartRate = { 120 })).fitnessIndex.value!!
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
        assertEquals(1, f.stretchCount)
        assertEquals(1, f.stretchesFound)
        assertEquals(listOf(41.5), f.estimates)
        assertFalse(f.restingAssumed)
    }

    @Test
    fun `fitness index uses only the last 60 percent of the stretch`() {
        // The first 4 of 10 minutes at 100 bpm (heart rate still settling), the last 6 at 120 bpm.
        val f = analyse(session(600, speed = { 6.0 }, incline = { 5.0 }, heartRate = { if (it < 240) 100 else 120 })).fitnessIndex.value!!
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
    }

    @Test
    fun `fitness index is the median of the stretches with a usable reserve fraction`() {
        // Four stretches of 5 min, different incline (more than 1 % apart).
        //  A 5 km/h  0 %, 100 bpm: HRr 0.333, out of range
        //  B 6 km/h  5 %, 120 bpm: VO2 22.5 -> 41.5
        //  C 6 km/h  8 %, 135 bpm: VO2 27.9 -> 3.5 + 24.4 / 0.625 = 42.54
        //  D 6 km/h 10 %, 150 bpm: VO2 31.5 -> 3.5 + 28 / 0.75 = 40.8333
        val inc = listOf(0.0, 5.0, 8.0, 10.0)
        val sp = listOf(5.0, 6.0, 6.0, 6.0)
        val hr = listOf(100, 120, 135, 150)
        val s = session(1200, speed = { sp[it / 300] }, incline = { inc[it / 300] }, heartRate = { hr[it / 300] })
        val f = analyse(s).fitnessIndex.value!!
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
        assertEquals(3, f.stretchCount)
        assertEquals(4, f.stretchesFound)
        assertEquals(41.5, f.estimates[0], 1e-9)
        assertEquals(42.54, f.estimates[1], 1e-9)
        assertEquals(3.5 + 28.0 / 0.75, f.estimates[2], 1e-9)
    }

    @Test
    fun `fitness index with an even number of stretches is the mean of the middle two`() {
        val inc = listOf(5.0, 8.0)
        val hr = listOf(120, 135)
        val s = session(600, speed = { 6.0 }, incline = { inc[it / 300] }, heartRate = { hr[it / 300] })
        assertEquals((41.5 + 42.54) / 2, analyse(s).fitnessIndex.value!!.vo2maxEstimate, 1e-9)
    }

    @Test
    fun `no fitness index without a long steady stretch or a usable reserve fraction`() {
        // The incline changes every 2 min: no stretch of 3 min.
        val short = session(1200, speed = { 6.0 }, incline = { 3.0 + 2 * (it / 120) }, heartRate = { 120 })
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(short).fitnessIndex.reason)
        // 175 bpm is 96 % of the reserve.
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(session(600, heartRate = { 175 })).fitnessIndex.reason)
        // A walk shorter than 3 minutes is no stretch.
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(session(170)).fitnessIndex.reason)
    }

    @Test
    fun `fitness index flags an assumed resting heart rate`() {
        val f = analyse(session(600, speed = { 6.0 }, incline = { 5.0 }, heartRate = { 120 }), context(resting = null)).fitnessIndex.value!!
        assertTrue(f.restingAssumed)
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
    }

    @Test
    fun `a pause splits the stretches`() {
        val s = session(900, speed = { if (it in 300 until 330) 0.0 else 6.0 }, incline = { 5.0 }, heartRate = { 120 })
        // 300 s, pause, 570 s: two stretches.
        assertEquals(2, analyse(s).fitnessIndex.value!!.stretchesFound)
    }
}
