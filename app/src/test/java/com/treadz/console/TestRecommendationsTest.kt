package com.treadz.console

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.RecommendationDirection
import com.treadz.console.analysis.trends.TestPoint
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.ThresholdEstimatesAnalysis
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.analysis.workout.WorkoutThresholdEstimate
import com.treadz.console.fakes.AnalysisFixtures
import com.treadz.console.performance.TestRecommendations
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.ThresholdEstimate
import com.treadz.console.programs.fitness.ThresholdProfile
import com.treadz.console.userdata.DatedWorkoutAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app suggests a test: the workouts of the user compared with the thresholds of their profile, only since the latest test. */
class TestRecommendationsTest {

    private val today = 20_000L

    private fun profile(vt1: Int? = 131, vt2: Int? = 149) = FitnessProfile(
        computedAtMillis = 0, vo2max = null, mas = null, hrMax = null,
        thresholds = ThresholdProfile(
            vt1 = vt1?.let { ThresholdEstimate(it, null, ThresholdQuality.Medium, emptyList()) },
            vt2 = vt2?.let { ThresholdEstimate(it, null, ThresholdQuality.Medium, emptyList()) },
            history = emptyList(),
        ),
    )

    private fun estimate(kind: ThresholdKind, implied: Double) = WorkoutThresholdEstimate(
        kind, 0, 1_200_000, 6.0, 1.0, cleanWindows = 8, alpha1 = 0.75, heartRateBpm = implied, slopePerBpm = 0.01, impliedBpm = implied, weightSeconds = 900,
    )

    private fun workout(daysAgo: Long, vt1: Double? = null, vt2: Double? = null): DatedWorkoutAnalysis {
        val estimates = listOfNotNull(vt1?.let { estimate(ThresholdKind.Vt1, it) }, vt2?.let { estimate(ThresholdKind.Vt2, it) })
        val analysis = AnalysisFixtures.full().copy(thresholdEstimates = Section.of(ThresholdEstimatesAnalysis(1, estimates)))
        return DatedWorkoutAnalysis(workoutId = daysAgo, startedAtMillis = 0, epochDay = today - daysAgo, analysis = analysis)
    }

    private val fourDays = listOf(1L, 3L, 5L, 8L)

    @Test fun fourWorkoutsOnDifferentDaysAboveTheProfileRecommendATestWithTheMedianDifference() {
        val workouts = fourDays.map { workout(it, vt1 = 139.0) }
        val result = TestRecommendations.compute(workouts, emptyList(), profile(), today)
        val reco = result.single()
        assertEquals(ThresholdKind.Vt1, reco.kind)
        assertEquals(RecommendationDirection.Higher, reco.direction)
        assertEquals(8, reco.differenceBpm)
        assertEquals(4, reco.workouts)
    }

    @Test fun workoutsBelowTheProfileSayLowerAndEachThresholdIsLookedAtSeparately() {
        val workouts = fourDays.map { workout(it, vt1 = 122.0, vt2 = 158.0) }
        val result = TestRecommendations.compute(workouts, emptyList(), profile(), today).associateBy { it.kind }
        assertEquals(RecommendationDirection.Lower, result.getValue(ThresholdKind.Vt1).direction)
        assertEquals(9, result.getValue(ThresholdKind.Vt1).differenceBpm)
        assertEquals(RecommendationDirection.Higher, result.getValue(ThresholdKind.Vt2).direction)
        assertEquals(9, result.getValue(ThresholdKind.Vt2).differenceBpm)
    }

    @Test fun nothingWithTooFewDaysOrTooSmallADifferenceOrWithoutAProfileThreshold() {
        assertTrue(TestRecommendations.compute(listOf(1L, 3L, 5L).map { workout(it, vt1 = 140.0) }, emptyList(), profile(), today).isEmpty())
        assertTrue(TestRecommendations.compute(fourDays.map { workout(it, vt1 = 133.0) }, emptyList(), profile(), today).isEmpty())
        assertTrue(TestRecommendations.compute(fourDays.map { workout(it, vt1 = 140.0) }, emptyList(), profile(vt1 = null), today).isEmpty())
        assertTrue(TestRecommendations.compute(fourDays.map { workout(it, vt1 = 140.0) }, emptyList(), null, today).isEmpty())
    }

    @Test fun onlyTheWorkoutsAfterTheLatestTestCountSoANewTestMakesTheRecommendationGo() {
        val workouts = fourDays.map { workout(it, vt1 = 140.0) } + workout(12, vt1 = 140.0)
        // A test long ago: the four most recent workouts are all after it.
        assertEquals(1, TestRecommendations.compute(workouts, listOf(TestPoint(today - 40, vo2max = 44.0)), profile(), today).size)
        // A test two days ago: only the workout of yesterday is after it.
        assertTrue(TestRecommendations.compute(workouts, listOf(TestPoint(today - 40), TestPoint(today - 2, vt1Bpm = 140.0)), profile(), today).isEmpty())
        // A test today with thresholds: nothing is after it.
        assertTrue(TestRecommendations.compute(workouts, listOf(TestPoint(today, vt1Bpm = 131.0)), profile(), today).isEmpty())
        // A test without thresholds (Ebbeling), or with a low-quality detection, does not move the reference.
        assertEquals(1, TestRecommendations.compute(workouts, listOf(TestPoint(today - 40, vt1Bpm = 131.0), TestPoint(today, vo2max = 44.0)), profile(), today).size)
        assertEquals(1, TestRecommendations.compute(workouts, listOf(TestPoint(today, vt1Bpm = 140.0, thresholdQuality = com.treadz.console.analysis.thresholds.ThresholdQuality.Low)), profile(), today).size)
    }

    @Test fun workoutsWithoutThresholdEstimatesSayNothing() {
        val workouts = fourDays.map { workout(it) }
        assertTrue(TestRecommendations.compute(workouts, emptyList(), profile(), today).isEmpty())
    }
}
