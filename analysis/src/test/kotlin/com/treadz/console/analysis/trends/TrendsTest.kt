package com.treadz.console.analysis.trends

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.TrendFixtures.analysis
import com.treadz.console.analysis.trends.TrendFixtures.dated
import com.treadz.console.analysis.workout.RecoveryContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrendsTest {

    // ------------------------------------------------------------------ fitness index

    @Test
    fun `fitness index rolling median and slope per 30 days`() {
        // One session every 5 days with an estimate rising 0.3 per session: 0.06 per day = 1.8 per 30 days.
        val sessions = (0 until 8).map { dated(100L + 5 * it, analysis(fitness = 40.0 + 0.3 * it)) }
        val trend = FitnessIndexTrends.compute(sessions, window = 5)
        assertEquals(8, trend.points.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 5, 5), trend.points.map { it.sessionsInWindow })
        // Median of the first three: the middle one.
        assertEquals(40.3, trend.points[2].rollingMedian, 1e-9)
        // Median of the last five (sessions 3..7).
        assertEquals(40.0 + 0.3 * 5, trend.points[7].rollingMedian, 1e-9)
        val full = trend.points.filter { it.sessionsInWindow >= 3 }
        val expected = leastSquares(full.map { it.epochDay.toDouble() }, full.map { it.rollingMedian }) * 30.0
        assertEquals(expected, trend.slopePer30Days!!, 1e-9)
        assertTrue(trend.slopePer30Days!! > 1.0)
    }

    @Test
    fun `fitness index trend ignores sessions without index and needs enough points`() {
        val a = listOf(
            dated(10, analysis(fitness = 40.0)),
            dated(11, analysis()),
            dated(12, analysis(fitness = 42.0)),
        )
        val trend = FitnessIndexTrends.compute(a)
        assertEquals(2, trend.points.size)
        assertNull(trend.slopePer30Days)
        assertTrue(FitnessIndexTrends.compute(emptyList()).points.isEmpty())
    }

    @Test
    fun `fitness index median resists an outlier`() {
        val values = listOf(40.0, 40.5, 52.0, 40.2, 40.4)
        val sessions = values.mapIndexed { i, v -> dated(i * 7L, analysis(fitness = v)) }
        val last = FitnessIndexTrends.compute(sessions, window = 5).points.last()
        assertEquals(40.4, last.rollingMedian, 1e-9)
    }

    @Test
    fun `slope needs a span of 14 days`() {
        val sessions = (0 until 6).map { dated(it.toLong(), analysis(fitness = 40.0 + it)) }
        assertNull(FitnessIndexTrends.compute(sessions).slopePer30Days)
    }

    // ------------------------------------------------------------------ decoupling and recovery

    @Test
    fun `decoupling trend only takes long steady zone 2 sessions`() {
        val a = listOf(
            dated(0, analysis(decoupling = TrendFixtures.decoupling(8.0, steady = true))),
            dated(10, analysis(decoupling = TrendFixtures.decoupling(20.0, steady = false))),
            dated(15, analysis(decoupling = TrendFixtures.decoupling(6.0, steady = true))),
            dated(30, analysis(decoupling = TrendFixtures.decoupling(4.0, steady = true))),
            dated(31, analysis()),
        )
        val t = DecouplingTrends.compute(a)
        assertEquals(listOf(0L, 15L, 30L), t.points.map { it.epochDay })
        assertEquals(listOf(8.0, 6.0, 4.0), t.points.map { it.decouplingPercent })
        // Exactly linear: -4 points in 30 days.
        assertEquals(-4.0, t.slopePer30Days!!, 0.3)
    }

    @Test
    fun `recovery is grouped by context`() {
        val a = listOf(
            dated(5, analysis(recovery = TrendFixtures.recovery(RecoveryContext.BeltStopped, 30.0, 50.0))),
            dated(1, analysis(recovery = TrendFixtures.recovery(RecoveryContext.BeltMoving, 20.0, 35.0, vo2 = 10.0))),
            dated(9, analysis(recovery = TrendFixtures.recovery(RecoveryContext.BeltStopped, 33.0))),
            dated(10, analysis()),
        )
        val g = RecoveryTrends.byContext(a)
        assertEquals(listOf(5L, 9L), g.getValue(RecoveryContext.BeltStopped).map { it.epochDay })
        assertEquals(listOf(1L), g.getValue(RecoveryContext.BeltMoving).map { it.epochDay })
        assertNull(g.getValue(RecoveryContext.BeltStopped)[1].drop120Bpm)
        assertEquals(10.0, g.getValue(RecoveryContext.BeltMoving)[0].meanVo2MlKgMin)
        assertTrue(RecoveryTrends.byContext(emptyList()).values.all { it.isEmpty() })
    }

    // ------------------------------------------------------------------ measurements, absolute VO2, tests

    @Test
    fun `seven day trailing mean`() {
        val v = listOf(
            DatedValue(10, 60.0), DatedValue(11, 62.0), DatedValue(11, 64.0), DatedValue(14, 58.0), DatedValue(20, 70.0),
        )
        val m = MeasurementTrends.trailingMean(v)
        assertEquals(listOf(10L, 11L, 14L, 20L), m.map { it.epochDay })
        assertEquals(60.0, m[0].mean, 1e-9)
        assertEquals(62.0, m[1].mean, 1e-9) // 60, 62, 64
        assertEquals(3, m[1].count)
        assertEquals((60.0 + 62 + 64 + 58) / 4, m[2].mean, 1e-9)
        // Day 20: the window is days 14..20: 58 and 70.
        assertEquals(64.0, m[3].mean, 1e-9)
        assertEquals(2, m[3].count)
        // The window is exactly seven days: day 17 still sees day 11, day 18 does not.
        assertEquals(2, MeasurementTrends.trailingMean(listOf(DatedValue(11, 1.0), DatedValue(17, 3.0))).last().count)
        assertEquals(1, MeasurementTrends.trailingMean(listOf(DatedValue(11, 1.0), DatedValue(18, 3.0))).last().count)
    }

    @Test
    fun `absolute VO2 with the weight of each date`() {
        val weights = listOf(DatedValue(100, 80.0), DatedValue(200, 75.0), DatedValue(300, 70.0))
        val tests = listOf(TestPoint(250, vo2max = 40.0), TestPoint(100, vo2max = 38.0), TestPoint(50, vo2max = 36.0), TestPoint(310, vt1Bpm = 130.0))
        val a = AbsoluteVo2.compute(tests, weights)
        assertEquals(listOf(50L, 100L, 250L), a.map { it.epochDay })
        // Before the first weight: the earliest one after.
        assertEquals(80.0, a[0].weightKg)
        assertEquals(100L, a[0].weightEpochDay)
        assertEquals(36.0 * 80.0 / 1000.0, a[0].litresPerMinute, 1e-9)
        assertEquals(38.0 * 80.0 / 1000.0, a[1].litresPerMinute, 1e-9)
        // On day 250 the weight of day 200.
        assertEquals(75.0, a[2].weightKg)
        assertEquals(3.0, a[2].litresPerMinute, 1e-9)
        assertTrue(AbsoluteVo2.compute(tests, emptyList()).isEmpty())
    }

    @Test
    fun `test series with the SEE band and quality`() {
        val s = TestTrends.series(
            listOf(
                TestPoint(30, vo2max = 44.0, vo2maxSee = 3.5, vt1Bpm = 132.0, vt2Bpm = 150.0, thresholdQuality = ThresholdQuality.High),
                TestPoint(10, vo2max = 48.9, vo2maxSee = 4.85, vo2maxIsLowerBound = true),
                TestPoint(20),
            ),
        )
        assertEquals(listOf(10L, 30L), s.vo2max.map { it.epochDay })
        assertEquals(48.9 - 4.85, s.vo2max[0].low!!, 1e-9)
        assertEquals(48.9 + 4.85, s.vo2max[0].high!!, 1e-9)
        assertTrue(s.vo2max[0].isLowerBound)
        assertEquals(1, s.thresholds.size)
        assertEquals(ThresholdQuality.High, s.thresholds[0].quality)
    }

    // ------------------------------------------------------------------ load

    private fun expApprox(n: Int, tau: Int) = Math.exp(-n.toDouble() / tau)

    @Test
    fun `Banister series against hand-computed values`() {
        // 100 TRIMP on day 0, nothing afterwards. e^(-1/42) = 0.976472, e^(-1/7) = 0.866878.
        val days = LoadTrends.daily(listOf(dated(0, analysis(trimp = 100.0))), untilEpochDay = 2)
        assertEquals(3, days.size)
        // Day 0: fitness = 100 * (1 - 0.976472) = 2.3528; fatigue = 100 * (1 - 0.866878) = 13.3122.
        assertEquals(2.3528, days[0].fitness, 1e-3)
        assertEquals(13.3122, days[0].fatigue, 1e-3)
        assertEquals(-10.9594, days[0].balance, 1e-3)
        // Day 1: both decay.
        assertEquals(2.3528 * 0.976472, days[1].fitness, 1e-3)
        assertEquals(13.3122 * 0.866878, days[1].fatigue, 1e-3)
        assertEquals(0.0, days[1].trimp)
        assertFalse(days[1].hasMissingTrimp)
        assertEquals(days[2].fitness - days[2].fatigue, days[2].balance, 1e-12)
    }

    @Test
    fun `constant daily load converges to the load`() {
        val a = (0 until 42).map { dated(it.toLong(), analysis(trimp = 100.0)) }
        val days = LoadTrends.daily(a, untilEpochDay = 41)
        // After n days of a constant load d: fitness = d * (1 - e^(-n/42)), fatigue = d * (1 - e^(-n/7)).
        assertEquals(100.0 * (1 - expApprox(42, 42)), days.last().fitness, 1e-9)
        assertEquals(100.0 * (1 - expApprox(42, 7)), days.last().fatigue, 1e-9)
        assertEquals(63.2121, days.last().fitness, 1e-3)
        assertEquals(99.7521, days.last().fatigue, 1e-3)
    }

    @Test
    fun `daily series fills days without workouts, flags missing TRIMP and sums sessions`() {
        val a = listOf(
            dated(10, analysis(trimp = 30.0)),
            dated(10, analysis(trimp = 20.0)),
            dated(13, analysis()), // a workout without heart rate
            dated(14, analysis(trimp = 10.0)),
            dated(14, analysis()),
            dated(40, analysis(trimp = 99.0)), // after the last day: ignored
        )
        val days = LoadTrends.daily(a, untilEpochDay = 15)
        assertEquals((10L..15L).toList(), days.map { it.epochDay })
        assertEquals(50.0, days[0].trimp)
        assertEquals(2, days[0].workouts)
        assertEquals(0.0, days[1].trimp)
        assertEquals(0, days[1].workouts)
        assertFalse(days[1].hasMissingTrimp)
        assertEquals(0.0, days[3].trimp)
        assertTrue(days[3].hasMissingTrimp)
        assertEquals(1, days[3].workoutsWithoutTrimp)
        assertEquals(10.0, days[4].trimp)
        assertTrue(days[4].hasMissingTrimp)
        assertEquals(0.0, days[5].trimp)
    }

    @Test
    fun `daily series can start later without changing the model`() {
        val a = listOf(dated(0, analysis(trimp = 100.0)))
        val all = LoadTrends.daily(a, untilEpochDay = 10)
        val cut = LoadTrends.daily(a, untilEpochDay = 10, fromEpochDay = 5)
        assertEquals(all.drop(5), cut)
        assertTrue(LoadTrends.daily(emptyList(), 10).isEmpty())
        assertTrue(LoadTrends.daily(a, untilEpochDay = -1).isEmpty())
    }

    @Test
    fun `weekly TRIMP by calendar week`() {
        // Epoch day 0 was a Thursday: Monday of that week is day -3; the weeks start on days 4, 11...
        assertEquals(-3L, EpochDays.weekStart(0))
        assertEquals(-3L, EpochDays.weekStart(3))
        assertEquals(4L, EpochDays.weekStart(4))
        assertEquals(4L, EpochDays.weekStart(10))
        assertEquals(-10L, EpochDays.weekStart(-4))
        // Days 2 to 12: part of the week from day -3, the whole week from day 4 and the first two days of the one from day 11.
        val a = listOf(
            dated(2, analysis(trimp = 10.0)),
            dated(4, analysis(trimp = 20.0)),
            dated(10, analysis(trimp = 30.0)),
            dated(11, analysis(trimp = 40.0)),
            dated(12, analysis()),
        )
        val weeks = LoadTrends.weekly(LoadTrends.daily(a, untilEpochDay = 12))
        assertEquals(listOf(-3L, 4L, 11L), weeks.map { it.weekStartEpochDay })
        assertEquals(listOf(10.0, 50.0, 40.0), weeks.map { it.trimp })
        assertEquals(listOf(2, 7, 2), weeks.map { it.daysInSeries })
        assertEquals(listOf(1, 2, 2), weeks.map { it.workouts })
        assertEquals(1, weeks[2].workoutsWithoutTrimp)
    }

    // ------------------------------------------------------------------ zones

    @Test
    fun `weekly time in zones`() {
        val a = listOf(
            dated(4, analysis(zoneSeconds = listOf(100, 200, 300, 0, 0))), // week starting 4
            dated(6, analysis(zoneSeconds = listOf(0, 100, 0, 100, 0))),
            dated(7, analysis()), // no zones
            dated(20, analysis(zoneSeconds = listOf(60, 0, 0, 0, 0))), // week starting 18
        )
        val weeks = ZoneDistributionTrends.weekly(a, untilEpochDay = 22)
        assertEquals(listOf(4L, 11L, 18L), weeks.map { it.weekStartEpochDay })
        assertEquals(listOf(100, 300, 300, 100, 0), weeks[0].secondsByZone)
        assertEquals(2, weeks[0].sessionsWithZones)
        assertEquals(1, weeks[0].sessionsWithoutZones)
        assertEquals(800, weeks[0].totalSeconds)
        assertEquals(0.375, weeks[0].fractions[1], 1e-9)
        assertEquals(listOf(0, 0, 0, 0, 0), weeks[1].secondsByZone)
        assertTrue(weeks[1].fractions.all { it == 0.0 })
        assertEquals(1.0, weeks[2].fractions[0], 1e-9)
        assertTrue(ZoneDistributionTrends.weekly(emptyList(), 10).isEmpty())
    }

    private fun leastSquares(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.sumOf { (it - mx) * (it - mx) }
    }
}
