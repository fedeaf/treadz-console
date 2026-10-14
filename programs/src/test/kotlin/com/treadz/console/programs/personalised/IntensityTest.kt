package com.treadz.console.programs.personalised

import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.MasEstimate
import com.treadz.console.programs.fitness.Sex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntensityTest {

    private fun refs(vo2max: Double = 42.0, mas: Double = 12.0, max: Int = 180, rest: Int? = null) = IntensityReferences(
        vo2maxMlKgMin = vo2max, vo2maxEstimated = false, masKmh = mas, masEstimated = false,
        maxHeartRateBpm = max, maxHeartRateEstimated = false, restingHeartRateBpm = rest, jackson = null,
    )

    // ------------------------------------------------------------------ estimation without tests

    @Test
    fun `Jackson 1990 BMI equation with the published coefficients`() {
        // 56.363 + 1.921*4 - 0.381*40 - 0.754*24.6914 + 10.987 = 41.177
        assertEquals(41.177, IntensityEstimator.jacksonVo2max(4, 40, 80.0 / (1.8 * 1.8), Sex.Male), 0.001)
        // 56.363 + 1.921*1 - 0.381*30 - 0.754*22.0386 = 30.237
        assertEquals(30.237, IntensityEstimator.jacksonVo2max(1, 30, 60.0 / (1.65 * 1.65), Sex.Female), 0.001)
        assertEquals(10.987, IntensityEstimator.jacksonVo2max(3, 35, 25.0, Sex.Male) - IntensityEstimator.jacksonVo2max(3, 35, 25.0, Sex.Female), 1e-9)
    }

    @Test
    fun `self-rated level maps to the activity rating, unknown is conservative`() {
        assertEquals(1, IntensityEstimator.physicalActivityRating(SelfRatedFitness.Beginner))
        assertEquals(4, IntensityEstimator.physicalActivityRating(SelfRatedFitness.Intermediate))
        assertEquals(6, IntensityEstimator.physicalActivityRating(SelfRatedFitness.Advanced))
        assertEquals(1, IntensityEstimator.physicalActivityRating(null))
    }

    @Test
    fun `without tests VO2max is estimated, MAS is VO2max over 3_5 and both are flagged`() {
        val r = IntensityEstimator.references(estimatedProfile())
        assertEquals(41.2, r.vo2maxMlKgMin)
        assertTrue(r.vo2maxEstimated)
        assertEquals(11.77, r.masKmh)
        assertTrue(r.masEstimated)
        assertTrue(r.maxHeartRateEstimated)
        val j = checkNotNull(r.jackson)
        assertEquals(24.7, j.bmi)
        assertEquals(4, j.physicalActivityRating)
        assertFalse(j.bmiAssumed)
        assertTrue(r.estimatedFor(usesVo2max = true, usesMas = false, usesMaxHeartRate = false))
    }

    @Test
    fun `missing height or weight assumes a BMI of 27`() {
        val j = IntensityEstimator.nonExercise(estimatedProfile().copy(heightCm = null, selfRatedFitness = null))
        assertTrue(j.bmiAssumed)
        assertEquals(27.0, j.bmi)
        assertEquals(1, j.physicalActivityRating)
        // 56.363 + 1.921 - 15.24 - 20.358 + 10.987 = 33.673
        assertEquals(33.7, j.vo2maxMlKgMin)
    }

    @Test
    fun `the estimate is kept within plausible values`() {
        val old = estimatedProfile().copy(ageYears = 100, weightKg = 200.0, sex = Sex.Female, selfRatedFitness = SelfRatedFitness.Beginner)
        assertEquals(IntensityEstimator.MIN_VO2MAX, IntensityEstimator.nonExercise(old).vo2maxMlKgMin)
    }

    @Test
    fun `measured values are used as they are`() {
        val r = IntensityEstimator.references(measuredProfile(restingBpm = 55))
        assertEquals(42.0, r.vo2maxMlKgMin)
        assertEquals(12.0, r.masKmh)
        assertFalse(r.vo2maxEstimated)
        assertFalse(r.masEstimated)
        assertFalse(r.maxHeartRateEstimated)
        assertEquals(55, r.restingHeartRateBpm)
        assertNull(r.jackson)
        assertFalse(r.estimatedFor(usesVo2max = true, usesMas = true, usesMaxHeartRate = true))
    }

    @Test
    fun `a VO2max without a maximal test gives an estimated MAS`() {
        val fp = measuredFitness().copy(mas = MasEstimate(12.0, estimated = true, lowerBound = false, sourceResultId = null, testedAtMillis = null, effort = null))
        val r = IntensityEstimator.references(measuredProfile().copy(fitnessProfile = fp))
        assertFalse(r.vo2maxEstimated)
        assertTrue(r.masEstimated)
        assertEquals(12.0, r.masKmh)
        assertTrue(r.estimatedFor(usesVo2max = false, usesMas = true, usesMaxHeartRate = false))
        assertFalse(r.estimatedFor(usesVo2max = true, usesMas = false, usesMaxHeartRate = false))
    }

    @Test
    fun `a measured MAS without VO2max gives VO2max = 3_5 MAS`() {
        val fp = FitnessProfile(NOW, null, measuredFitness().mas, null)
        val r = IntensityEstimator.references(measuredProfile().copy(fitnessProfile = fp, maxHeartRateOrigin = HeartRateMaxOrigin.Manual))
        assertEquals(42.0, r.vo2maxMlKgMin)
        assertFalse(r.vo2maxEstimated)
        assertFalse(r.maxHeartRateEstimated)
    }

    // ------------------------------------------------------------------ ACSM translator

    @Test
    fun `ACSM equations give the known values`() {
        // 5 km/h = 83.33 m/min, 10 %: 3.5 + 8.333 + 15.0
        assertEquals(26.833, IntensityTranslator.walkingVo2(5.0, 10.0), 0.001)
        // 10 km/h = 166.67 m/min, 1 %: 3.5 + 33.333 + 1.5
        assertEquals(38.333, IntensityTranslator.runningVo2(10.0, 1.0), 0.001)
        // Flat walking at 4.8 km/h (80 m/min): 3.5 + 8.0
        assertEquals(11.5, IntensityTranslator.walkingVo2(4.8, 0.0), 1e-9)
        // 12 km/h (200 m/min) flat: 3.5 + 40
        assertEquals(43.5, IntensityTranslator.runningVo2(12.0, 0.0), 1e-9)
    }

    @Test
    fun `walking keeps the comfortable speed and solves the incline`() {
        val t = IntensityTranslator.walking(26.833, 5.0)
        assertEquals(5.0, t.speedKmh)
        assertEquals(10.0, t.inclinePercent)
        assertFalse(t.capped)
    }

    @Test
    fun `walking below flat demand slows down, above 15 percent speeds up, beyond 6_5 km per h is capped`() {
        val slow = IntensityTranslator.walking(10.0, 5.0)
        assertEquals(0.0, slow.inclinePercent)
        assertEquals(3.9, slow.speedKmh)
        val steep = IntensityTranslator.walking(36.0, 5.0)
        assertEquals(15.0, steep.inclinePercent)
        assertEquals(5.3, steep.speedKmh)
        assertFalse(steep.capped)
        val capped = IntensityTranslator.walking(50.0, 5.0)
        assertEquals(15.0, capped.inclinePercent)
        assertEquals(6.5, capped.speedKmh)
        assertTrue(capped.capped)
    }

    @Test
    fun `running speed comes from the MAS at 1 percent`() {
        val r = refs()
        assertEquals(12.0, IntensityTranslator.forVo2maxFraction(1.0, ExerciseMode.Run, r).speedKmh)
        // (21 - 3.5) / (42 - 3.5) * 12 = 5.45
        val half = IntensityTranslator.forVo2maxFraction(0.5, ExerciseMode.Run, r)
        assertEquals(5.5, half.speedKmh)
        assertEquals(1.0, half.inclinePercent)
        assertEquals(9.6, IntensityTranslator.forMasFraction(0.8, ExerciseMode.Run, r).speedKmh)
        // Walking at 60 % of the MAS = 60 % of VO2max = 25.2: (25.2 - 11.833) / 150 = 8.9 %
        val walk = IntensityTranslator.forMasFraction(0.6, ExerciseMode.Walk, r, 5.0)
        assertEquals(9.0, walk.inclinePercent)
        assertEquals(5.0, walk.speedKmh)
    }

    @Test
    fun `heart rate to VO2max fraction - Swain 1994 without resting rate, reserve with it`() {
        // (90 - 37.182) / 0.6463 = 81.72 %
        assertEquals(0.8172, IntensityTranslator.vo2maxFractionForHeartRate(0.90, refs()), 0.0001)
        // (65 - 37.182) / 0.6463 = 43.04 %
        assertEquals(0.4304, IntensityTranslator.vo2maxFractionForHeartRate(0.65, refs()), 0.0001)
        // 162 bpm with rest 60 and max 180: 85 % HRR -> 3.5 + 0.85 * 38.5 = 36.225 -> 86.25 %
        assertEquals(0.8625, IntensityTranslator.vo2maxFractionForHeartRate(0.90, refs(rest = 60)), 0.0001)
        assertEquals(0.2, IntensityTranslator.vo2maxFractionForHeartRate(0.3, refs()))
    }
}
