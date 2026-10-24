package com.treadz.console.analysis.trends

import com.treadz.console.analysis.workout.Stats
import com.treadz.console.analysis.workout.ThresholdKind
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rules of the recommendation of a test (*assumptions*, decided with the user on 2026-10-23): the workouts must be on at
 * least [minWorkouts] different days in the last [windowDays] days, the median difference with the profile at least
 * [minDifferenceBpm] and at least [minSameDirection] of the [minWorkouts] most recent in the direction of the median.
 */
data class ThresholdRecommendationRules(
    val windowDays: Int = 30,
    val minWorkouts: Int = 4,
    val minDifferenceBpm: Double = 5.0,
    val minSameDirection: Int = 3,
)

enum class RecommendationDirection { Higher, Lower }

/**
 * "In your last [workouts] workouts [kind] looks about [differenceBpm] bpm [direction]: take a test?" Everything the
 * wording needs; the wording itself belongs to the UI.
 *
 * @property differenceBpm Rounded median difference between what the workouts imply and the profile value (always positive;
 *   [direction] says which way).
 * @property workouts The workouts it is based on: the most recent [ThresholdRecommendationRules.minWorkouts], one per day.
 */
data class ThresholdRecommendation(
    val kind: ThresholdKind,
    val direction: RecommendationDirection,
    val differenceBpm: Int,
    val workouts: Int,
)

/**
 * When to recommend a test, from the thresholds the workouts imply (`WorkoutAnalysis.thresholdEstimates`). A pure function
 * for the UI. **A workout never changes the profile**: the most this does is suggest a test, which is done rested.
 *
 * For each threshold, the workouts of the last [ThresholdRecommendationRules.windowDays] days that have an estimate of it
 * are reduced to one value per **day** (the mean of that day's estimates, weighted by their durations); with at least 4
 * days, the 4 most recent are compared with the profile value: the difference of each is its value minus the profile's.
 * It recommends a test when the median difference is at least 5 bpm either way and at least 3 of the 4 differences are
 * in that direction. Thresholds are looked at separately (VT1 and VT2 can both be recommended).
 */
object ThresholdRecommendations {

    /**
     * @param analyses The analyses of the user's workouts with their day.
     * @param todayEpochDay Today, as an epoch day in the user's time zone: the window is the [ThresholdRecommendationRules.windowDays]
     *   days ending today.
     * @param profileVt1Bpm The profile's VT1 and VT2 now; a threshold the profile does not have gets no recommendation.
     * @param afterEpochDay When the profile's thresholds were last measured (a test): only workouts after that day count
     *   (they are compared with a value that did not exist before); `null` = no limit.
     */
    fun compute(
        analyses: List<DatedAnalysis>,
        todayEpochDay: Long,
        profileVt1Bpm: Double?,
        profileVt2Bpm: Double?,
        afterEpochDay: Long? = null,
        rules: ThresholdRecommendationRules = ThresholdRecommendationRules(),
    ): List<ThresholdRecommendation> = listOfNotNull(
        recommend(ThresholdKind.Vt1, profileVt1Bpm, analyses, todayEpochDay, afterEpochDay, rules),
        recommend(ThresholdKind.Vt2, profileVt2Bpm, analyses, todayEpochDay, afterEpochDay, rules),
    )

    private fun recommend(
        kind: ThresholdKind,
        profileBpm: Double?,
        analyses: List<DatedAnalysis>,
        today: Long,
        after: Long?,
        rules: ThresholdRecommendationRules,
    ): ThresholdRecommendation? {
        if (profileBpm == null) return null
        // One value per day: the weighted mean of that day's estimates.
        val perDay = analyses
            .filter { it.epochDay in (today - rules.windowDays + 1)..today && (after == null || it.epochDay > after) }
            .flatMap { a -> a.analysis.thresholdEstimates.value?.estimates.orEmpty().filter { it.kind == kind }.map { a.epochDay to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) ->
                val weight = list.sumOf { it.weightSeconds.toDouble() }
                if (weight > 0.0) list.sumOf { it.impliedBpm * it.weightSeconds } / weight else list.map { it.impliedBpm }.average()
            }
        if (perDay.size < rules.minWorkouts) return null
        val differences = perDay.entries.sortedByDescending { it.key }.take(rules.minWorkouts).map { it.value - profileBpm }
        val median = Stats.median(differences)
        if (abs(median) < rules.minDifferenceBpm - 1e-9) return null
        val higher = median > 0
        val sameDirection = differences.count { if (higher) it > 0 else it < 0 }
        if (sameDirection < rules.minSameDirection) return null
        return ThresholdRecommendation(
            kind = kind,
            direction = if (higher) RecommendationDirection.Higher else RecommendationDirection.Lower,
            differenceBpm = abs(median).roundToInt(),
            workouts = differences.size,
        )
    }
}
