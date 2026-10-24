package com.treadz.console.userdata

import com.treadz.console.analysis.fitness.Sex as AnalysisSex
import com.treadz.console.analysis.thresholds.ThresholdMetrics
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.programs.fitness.FitnessTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The analysis context of a workout as of its date (`WorkoutAnalysisContexts`) and the fingerprint of its inputs, with
 * pure functions: no database, no clock.
 */
class WorkoutAnalysisContextTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val ana = UserProfile(1, "Ana", Sex.Female, 168, LocalDate.of(1986, 10, 8)) // 39 on 2026-10-07, 40 on the 8th

    private fun weight(id: Long, date: String, kg: Double, created: Long = id) = WeightEntry(id, 1, LocalDate.parse(date), kg, createdAtMillis = created)

    private fun inputs(
        profile: UserProfile = ana,
        weights: List<WeightEntry> = emptyList(),
        tests: List<FitnessTestResult> = emptyList(),
        resting: List<RestingPoint> = emptyList(),
    ) = AnalysisUserInputs(profile, weights, tests, resting)

    private fun context(i: AnalysisUserInputs, start: Long = T0, zone: ZoneId = utc) = WorkoutAnalysisContexts.build(i, start, zone)

    private fun withThresholds(r: FitnessTestResult) = r.copy(
        thresholds = ThresholdResult(1, vt1 = ThresholdPoint(130.0), vt2 = ThresholdPoint(160.0), quality = ThresholdQuality.High),
    )

    // ---------------------------------------------------------------- weight

    @Test fun `the weight is the latest entry on or before the workout day, else the earliest after, else none`() {
        val weights = listOf(
            weight(1, "2026-09-01", 80.0), weight(2, "2026-10-05", 78.0), weight(3, "2026-10-07", 77.0), weight(4, "2026-10-20", 75.0),
        )
        assertEquals("same day counts", 77.0, context(inputs(weights = weights)).weightKg!!, 0.0)
        val day = 86_400_000L
        assertEquals("the day before: the previous entry", 78.0, context(inputs(weights = weights), T0 - day).weightKg!!, 0.0)
        assertEquals("a later entry is not used while an earlier one exists", 77.0, context(inputs(weights = weights), T0 + 5 * day).weightKg!!, 0.0)
        assertEquals("only later entries: the earliest of them", 80.0, context(inputs(weights = weights), T0 - 60 * day).weightKg!!, 0.0)
        assertNull("no weight at all", context(inputs()).weightKg)
    }

    @Test fun `the day of the workout is the day in the device's time zone`() {
        val weights = listOf(weight(1, "2026-10-07", 77.0), weight(2, "2026-10-08", 76.0))
        val lateEvening = T0 + 11 * 3_600_000L + 30 * 60_000L // 2026-10-07 23:30 UTC
        assertEquals(77.0, context(inputs(weights = weights), lateEvening, ZoneOffset.UTC).weightKg!!, 0.0)
        assertEquals("already the 8th in UTC+2", 76.0, context(inputs(weights = weights), lateEvening, ZoneOffset.ofHours(2)).weightKg!!, 0.0)
    }

    @Test fun `the placeholder weight of the generic profile counts as the weight the app uses`() {
        val placeholder = WeightEntry(1, 1, LocalDate.of(2026, 10, 1), 80.0, isPlaceholder = true)
        assertEquals(80.0, context(inputs(weights = listOf(placeholder))).weightKg!!, 0.0)
    }

    // ---------------------------------------------------------------- sex, age and maximum heart rate

    @Test fun `sex comes from the profile and the age is the one on the workout day`() {
        assertEquals(AnalysisSex.Female, context(inputs()).sex)
        assertEquals(AnalysisSex.Male, context(inputs(profile = ana.copy(sex = Sex.Male))).sex)
        // no test: the estimate from the age that day (39 on the 7th: 208 - 0.7 * 39 = 180.7; 40 on the 8th: 180)
        assertEquals(181, context(inputs()).maxHeartRateBpm)
        assertEquals(180, context(inputs(), T0 + 86_400_000L).maxHeartRateBpm)
        assertEquals(ana.estimatedMaxHeartRateBpm(LocalDate.of(2026, 10, 7)), context(inputs()).maxHeartRateBpm)
    }

    @Test fun `a test taken before the workout gives its measured maximum, VO2max and thresholds`() {
        val test = withThresholds(maxTestResult(TEST_2, T0 - 5 * 86_400_000L, peak = 185))
        val c = context(inputs(tests = listOf(test)))
        assertEquals(185, c.maxHeartRateBpm)
        assertNotNull(c.vo2maxMlKgMin)
        assertEquals(3.5 * 13.5, c.vo2maxMlKgMin!!, 4.0)
        assertEquals(ZoneSource.MeasuredThresholds, c.zones!!.source)
        assertEquals(130, c.zones!!.vt1Bpm)
        // the same workout without the test: the estimate, no VO2max, zones by the self-rated level or the default
        val without = context(inputs())
        assertNull(without.vo2maxMlKgMin)
        assertNotEquals(ZoneSource.MeasuredThresholds, without.zones!!.source)
    }

    @Test fun `a test after the workout, or the test that the workout itself is, does not take part`() {
        val before = context(inputs())
        val after = maxTestResult(TEST_2, T0 + 86_400_000L, peak = 199)
        assertEquals(before, context(inputs(tests = listOf(withThresholds(after)))))
        // a test whose belt was confirmed running a few seconds after the workout started is not "before" it
        val itself = maxTestResult(TEST_3, T0 + 5_000, peak = 199)
        assertEquals(before, context(inputs(tests = listOf(withThresholds(itself)))))
        // but it is for the next workout
        assertEquals(199, context(inputs(tests = listOf(itself)), T0 + 3_600_000L).maxHeartRateBpm)
    }

    @Test fun `invalid and old results are left out, like the live profile does`() {
        val invalid = maxTestResult(TEST_2, T0 - 86_400_000L).copy(validity = com.treadz.console.programs.fitness.Validity.Invalid)
        assertEquals(context(inputs()), context(inputs(tests = listOf(invalid))))
        val old = maxTestResult(TEST_2, T0 - 400 * 86_400_000L)
        assertEquals(context(inputs()), context(inputs(tests = listOf(old))))
    }

    @Test fun `the manual values of the profile have no history, so the current value applies to every date`() {
        val manual = ana.copy(maxHeartRateBpm = 190)
        val day = 86_400_000L
        listOf(T0 - 400 * day, T0, T0 + 400 * day).forEach { assertEquals(190, context(inputs(profile = manual), it).maxHeartRateBpm) }
        val advanced = ana.copy(fitnessLevel = FitnessLevel.Advanced)
        listOf(T0 - 400 * day, T0).forEach { assertEquals(ZoneSource.SelfRatedFitness, context(inputs(profile = advanced), it).zones!!.source) }
    }

    // ---------------------------------------------------------------- resting heart rate and zones

    @Test fun `the resting heart rate is the reference at the workout time, not the latest or the current one`() {
        val day = 86_400_000L
        val points = listOf(RestingPoint(T0 - 10 * day, 60), RestingPoint(T0 - 3 * day, 58), RestingPoint(T0 + 2 * day, 50))
        assertEquals("median of those up to the workout (the one after it is ignored)", 59, context(inputs(resting = points)).restingHeartRateBpm)
        assertEquals("a workout two days later also sees the third", 58, context(inputs(resting = points), T0 + 3 * day).restingHeartRateBpm)
        // only later measurements: the earliest one is the best figure there is
        val later = listOf(RestingPoint(T0 + 2 * day, 50), RestingPoint(T0 + 4 * day, 52))
        assertEquals(50, context(inputs(resting = later)).restingHeartRateBpm)
        assertNull(context(inputs()).restingHeartRateBpm)
        // the profile's own value is only the fallback
        assertEquals(63, context(inputs(profile = ana.copy(restingHeartRateBpm = 63))).restingHeartRateBpm)
        assertEquals(59, context(inputs(profile = ana.copy(restingHeartRateBpm = 63), resting = points)).restingHeartRateBpm)
    }

    @Test fun `the zones use the maximum and the resting heart rate of that day`() {
        val day = 86_400_000L
        val withResting = context(inputs(resting = listOf(RestingPoint(T0 - 2 * day, 55))))
        assertTrue("Karvonen once there is a resting rate", withResting.zones!!.usedReserve)
        assertTrue(!context(inputs()).zones!!.usedReserve)
        assertEquals(withResting.maxHeartRateBpm, withResting.zones!!.zones.last().highBpm)
        // before the resting measurement existed, the workout is analysed without it... unless it is the only one (earliest)
        val c = context(inputs(resting = listOf(RestingPoint(T0 + day, 55))))
        assertEquals(55, c.restingHeartRateBpm)
    }

    // ---------------------------------------------------------------- the fingerprint

    private val signature = SampleSignature(600, T0, T0 + 599_000)

    private fun fingerprint(i: AnalysisUserInputs, signature: SampleSignature = this.signature, start: Long = T0) =
        WorkoutAnalysisFingerprint.of(start, signature, context(i, start))

    @Test fun `the fingerprint changes with the context and the samples, and with nothing else`() {
        val day = 86_400_000L
        val base = inputs(weights = listOf(weight(1, "2026-09-01", 80.0)), resting = listOf(RestingPoint(T0 - 5 * day, 60)))
        val f = fingerprint(base)
        assertEquals("deterministic", f, fingerprint(base))
        assertEquals(32, f.length)

        // changes that alter the context of this workout
        assertNotEquals(f, fingerprint(inputs(weights = base.weights + weight(2, "2026-10-01", 78.0), resting = base.resting)))
        assertNotEquals(f, fingerprint(inputs(weights = base.weights, resting = base.resting + RestingPoint(T0 - 2 * day, 50))))
        assertNotEquals(f, fingerprint(inputs(weights = base.weights, resting = base.resting, tests = listOf(maxTestResult(TEST_2, T0 - day, peak = 185)))))
        assertNotEquals(f, fingerprint(inputs(profile = ana.copy(fitnessLevel = FitnessLevel.Advanced), weights = base.weights, resting = base.resting)))
        assertNotEquals(f, fingerprint(inputs(profile = ana.copy(maxHeartRateBpm = 190), weights = base.weights, resting = base.resting)))
        assertNotEquals(f, fingerprint(inputs(profile = ana.copy(sex = Sex.Male), weights = base.weights, resting = base.resting)))
        // the samples
        assertNotEquals(f, fingerprint(base, SampleSignature(601, T0, T0 + 600_000)))
        assertNotEquals(f, fingerprint(base, SampleSignature(600, T0 + 1000, T0 + 600_000)))

        // changes that do not
        assertEquals("a test after the workout", f, fingerprint(inputs(weights = base.weights, resting = base.resting, tests = listOf(maxTestResult(TEST_2, T0 + day, peak = 199)))))
        assertEquals("a weight after the workout, while an earlier one exists", f, fingerprint(inputs(weights = base.weights + weight(2, "2026-10-20", 70.0), resting = base.resting)))
        assertEquals("a resting measurement after the workout", f, fingerprint(inputs(weights = base.weights, resting = base.resting + RestingPoint(T0 + 3 * day, 40))))
        assertEquals("another name, height or goal", f, fingerprint(inputs(profile = ana.copy(name = "Anita", heightCm = 170, goal = Goal.LoseWeight), weights = base.weights, resting = base.resting)))
        assertEquals("a weight that is not the nearest one", f, fingerprint(inputs(weights = listOf(weight(1, "2026-08-01", 90.0)) + base.weights, resting = base.resting)))
    }

    // ---------------------------------------------------------------- α1 slope (thresholds implied by the workouts)

    private fun withSlope(r: FitnessTestResult, slope: Double, quality: ThresholdQuality = ThresholdQuality.Medium) = r.copy(
        thresholds = ThresholdResult(
            3, vt1 = ThresholdPoint(130.0), vt2 = ThresholdPoint(150.0), quality = quality,
            metrics = ThresholdMetrics(31, 48, 50, 0.82, 1.2, slope, 3.53),
        ),
    )

    @Test fun `the alpha1 slope is the one of the latest counting test before the workout, and it is in the fingerprint`() {
        val day = 86_400_000L
        val older = withSlope(maxTestResult(TEST_2, T0 - 20 * day, peak = 185), -0.020)
        val newer = withSlope(maxTestResult(TEST_3, T0 - 5 * day, peak = 185), -0.014)
        assertEquals(-0.014, context(inputs(tests = listOf(older, newer))).alpha1SlopePerBpm!!, 0.0)
        assertEquals("a low-quality detection does not count", -0.020,
            context(inputs(tests = listOf(older, withSlope(newer, -0.014, ThresholdQuality.Low)))).alpha1SlopePerBpm!!, 0.0)
        assertNull("a test after the workout does not count", context(inputs(tests = listOf(older)), T0 - 30 * day).alpha1SlopePerBpm)
        assertNull("no test with thresholds", context(inputs()).alpha1SlopePerBpm)
        assertNotEquals(
            fingerprint(inputs(tests = listOf(older))),
            fingerprint(inputs(tests = listOf(withSlope(older, -0.030)))),
        )
    }
}
