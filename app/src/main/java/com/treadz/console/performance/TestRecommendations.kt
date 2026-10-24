package com.treadz.console.performance

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.TestPoint
import com.treadz.console.analysis.trends.ThresholdRecommendation
import com.treadz.console.analysis.trends.ThresholdRecommendations
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.userdata.DatedWorkoutAnalysis
import com.treadz.console.userdata.UserData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The recommendation to take a fitness test, from what the user's workouts say about their thresholds
 * ([ThresholdRecommendations]): "In your last 4 workouts VT1 looks about 8 bpm higher: want to take a test?". A workout never
 * changes the profile; the most it does is suggest a test. It cannot be dismissed: it goes by itself when a new test makes
 * the workouts before it irrelevant (only the workouts after the latest test count).
 */
object TestRecommendations {

    /**
     * @param workouts The analyses of the user's workouts.
     * @param tests The user's tests: the latest one with thresholds that count is the point after which workouts count.
     * @param profile The fitness profile as of today: its VT1 and VT2 are what the workouts are compared with.
     */
    fun compute(workouts: List<DatedWorkoutAnalysis>, tests: List<TestPoint>, profile: FitnessProfile?, todayEpochDay: Long): List<ThresholdRecommendation> =
        ThresholdRecommendations.compute(
            analyses = workouts.map { it.dated },
            todayEpochDay = todayEpochDay,
            profileVt1Bpm = profile?.thresholds?.vt1?.heartRateBpm?.toDouble(),
            profileVt2Bpm = profile?.thresholds?.vt2?.heartRateBpm?.toDouble(),
            // Only a test that measured thresholds (of a quality that counts) moves the reference the workouts are compared with.
            afterEpochDay = tests.filter { (it.vt1Bpm != null || it.vt2Bpm != null) && it.thresholdQuality != ThresholdQuality.Low }
                .maxOfOrNull { it.epochDay },
        )

    /** [compute] for [userId], following every change of the workouts, the tests and the profile. */
    fun flow(userData: UserData, userId: Long, today: () -> java.time.LocalDate): Flow<List<ThresholdRecommendation>> =
        combine(
            userData.workoutAnalysis.analyses(userId),
            userData.workoutAnalysis.performanceInputs(userId),
            userData.fitnessTests.liveFitnessProfile(userId),
        ) { workouts, inputs, profile -> compute(workouts, inputs.tests, profile, today().toEpochDay()) }
}
