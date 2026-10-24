package com.treadz.console.analysis.trends

import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.ThresholdEstimatesAnalysis
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.analysis.workout.WorkoutThresholdEstimate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When to recommend a test from the thresholds the workouts imply. */
class ThresholdRecommendationsTest {

    private val today = 20_000L

    private fun estimate(kind: ThresholdKind, implied: Double, weight: Int = 600) = WorkoutThresholdEstimate(
        kind = kind, startMillis = 0, endMillis = weight * 1000L, speedKmh = 5.0, inclinePercent = 0.0, cleanWindows = 20,
        alpha1 = 0.9, heartRateBpm = 120.0, slopePerBpm = -0.02, impliedBpm = implied, weightSeconds = weight,
    )

    private fun workout(daysAgo: Long, vararg estimates: WorkoutThresholdEstimate): DatedAnalysis {
        val base = TrendFixtures.analysis()
        return DatedAnalysis(today - daysAgo, base.copy(thresholdEstimates = Section.of(ThresholdEstimatesAnalysis(estimates.size, estimates.toList()))))
    }

    private fun vt1(daysAgo: Long, implied: Double) = workout(daysAgo, estimate(ThresholdKind.Vt1, implied))

    private fun recommend(analyses: List<DatedAnalysis>, vt1: Double? = 130.0, vt2: Double? = 150.0, after: Long? = null) =
        ThresholdRecommendations.compute(analyses, today, vt1, vt2, after)

    @Test
    fun `four workouts on different days, 8 bpm higher - take a test`() {
        val r = recommend(listOf(vt1(1, 138.0), vt1(3, 139.0), vt1(6, 137.0), vt1(10, 138.0)))
        assertEquals(listOf(ThresholdRecommendation(ThresholdKind.Vt1, RecommendationDirection.Higher, 8, 4)), r)
    }

    @Test
    fun `lower works the same way and the difference is rounded`() {
        val r = recommend(listOf(vt1(1, 122.4), vt1(2, 122.6), vt1(3, 122.5), vt1(4, 122.5)))
        assertEquals(listOf(ThresholdRecommendation(ThresholdKind.Vt1, RecommendationDirection.Lower, 8, 4)), r)
    }

    @Test
    fun `fewer than four days - nothing, and workouts of the same day count as one`() {
        assertTrue(recommend(listOf(vt1(1, 140.0), vt1(2, 140.0), vt1(3, 140.0))).isEmpty())
        assertTrue(recommend(listOf(vt1(1, 140.0), vt1(1, 141.0), vt1(2, 140.0), vt1(3, 140.0))).isEmpty(), "three days")
        assertEquals(1, recommend(listOf(vt1(1, 140.0), vt1(1, 141.0), vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0))).size)
    }

    @Test
    fun `the same day's estimates are combined by their durations`() {
        val day = workout(1, estimate(ThresholdKind.Vt1, 150.0, 900), estimate(ThresholdKind.Vt1, 130.0, 300))
        // (150·900 + 130·300) / 1200 = 145.
        val r = recommend(listOf(day, vt1(2, 145.0), vt1(3, 145.0), vt1(4, 145.0)))
        assertEquals(15, r.single().differenceBpm)
    }

    @Test
    fun `only the last 30 days count and the 4 most recent workouts are compared`() {
        // Four old ones are out of the window.
        assertTrue(recommend(listOf(vt1(31, 140.0), vt1(40, 140.0), vt1(50, 140.0), vt1(60, 140.0), vt1(1, 140.0))).isEmpty())
        // The 30th day back is the oldest one in.
        assertEquals(1, recommend(listOf(vt1(29, 140.0), vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0))).size)
        assertTrue(recommend(listOf(vt1(30, 140.0), vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0))).isEmpty())
        // Older workouts in the window that disagree do not matter: the last four are used.
        val r = recommend(listOf(vt1(1, 140.0), vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0), vt1(20, 120.0), vt1(25, 120.0)))
        assertEquals(RecommendationDirection.Higher, r.single().direction)
        assertEquals(4, r.single().workouts)
    }

    @Test
    fun `the median difference must be at least 5 bpm`() {
        assertTrue(recommend(listOf(vt1(1, 133.0), vt1(2, 133.0), vt1(3, 134.0), vt1(4, 136.0))).isEmpty(), "median 3.5")
        assertEquals(1, recommend(listOf(vt1(1, 135.0), vt1(2, 135.0), vt1(3, 136.0), vt1(4, 134.0))).size, "median 5")
    }

    @Test
    fun `at least 3 of the 4 must go the same way`() {
        // Median 5 but only two above the profile: a mixed picture.
        assertTrue(recommend(listOf(vt1(1, 110.0), vt1(2, 110.0), vt1(3, 160.0), vt1(4, 160.0))).isEmpty())
        // 3 of 4 above, one below.
        assertEquals(1, recommend(listOf(vt1(1, 139.0), vt1(2, 140.0), vt1(3, 141.0), vt1(4, 129.0))).size)
    }

    @Test
    fun `VT2 is looked at on its own and a threshold the profile does not have gets no recommendation`() {
        val both = (1L..4L).map { workout(it, estimate(ThresholdKind.Vt1, 138.0), estimate(ThresholdKind.Vt2, 142.0)) }
        val r = recommend(both)
        assertEquals(
            listOf(
                ThresholdRecommendation(ThresholdKind.Vt1, RecommendationDirection.Higher, 8, 4),
                ThresholdRecommendation(ThresholdKind.Vt2, RecommendationDirection.Lower, 8, 4),
            ),
            r,
        )
        assertEquals(listOf(ThresholdKind.Vt2), recommend(both, vt1 = null).map { it.kind })
        assertTrue(recommend(both, vt1 = null, vt2 = null).isEmpty())
        // Estimates of only one kind do not count for the other.
        assertEquals(listOf(ThresholdKind.Vt1), recommend((1L..4L).map { vt1(it, 140.0) }).map { it.kind })
    }

    @Test
    fun `only workouts after the last test count when asked`() {
        val ws = listOf(vt1(1, 140.0), vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0), vt1(5, 140.0))
        assertEquals(1, recommend(ws, after = today - 6).size)
        assertTrue(recommend(ws, after = today - 4).isEmpty(), "only 3 days after the test")
        assertEquals(1, recommend(ws, after = null).size)
    }

    @Test
    fun `workouts without estimates are ignored`() {
        val none = DatedAnalysis(today - 1, TrendFixtures.analysis())
        assertTrue(recommend(listOf(none, vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0))).isEmpty())
        assertEquals(1, recommend(listOf(none, vt1(2, 140.0), vt1(3, 140.0), vt1(4, 140.0), vt1(5, 140.0))).size)
    }
}
