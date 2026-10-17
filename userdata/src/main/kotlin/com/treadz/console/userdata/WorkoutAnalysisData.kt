package com.treadz.console.userdata

import com.treadz.console.analysis.trends.DatedAnalysis
import com.treadz.console.analysis.trends.DatedValue
import com.treadz.console.analysis.trends.TestPoint
import com.treadz.console.analysis.workout.WorkoutAnalysis
import kotlinx.coroutines.flow.Flow

/**
 * The cached analysis of one workout (`WorkoutAnalyzer`), with when it was computed. The analysis is complete and
 * self-contained: every section has its value or its reason, and the zones used are inside it
 * (`WorkoutAnalysis.zoneTime`).
 */
data class StoredWorkoutAnalysis(
    val workoutId: Long,
    val analysis: WorkoutAnalysis,
    /** Epoch ms when it was computed (this device's clock; informative, nothing depends on it). */
    val computedAtMillis: Long,
)

/**
 * The analysis of a workout with its date, ready for the trends of `:analysis`.
 *
 * @property startedAtMillis Start of the workout (epoch ms).
 * @property epochDay The day of [startedAtMillis] **in the device's time zone** (days since 1970-01-01): the day the
 *   user did the workout, the same notion of "a day" the weights, tests and resting measurements use in
 *   [PerformanceInputs].
 */
data class DatedWorkoutAnalysis(
    val workoutId: Long,
    val startedAtMillis: Long,
    val epochDay: Long,
    val analysis: WorkoutAnalysis,
) {
    /** The input of `FitnessIndexTrends`, `DecouplingTrends`, `RecoveryTrends`, `LoadTrends` and `ZoneDistributionTrends`. */
    val dated: DatedAnalysis get() = DatedAnalysis(epochDay, analysis)
}

/**
 * The inputs of the trend functions of `:analysis` that do not come from the workouts, of one user; every `epochDay` is
 * a day in the device's time zone. Lists are in date order.
 *
 * @property tests One [TestPoint] per **valid** fitness test result that has a VO2max or thresholds: the VO2max with its
 *   standard error and lower-bound flag, VT1/VT2 in bpm and their quality (`null` for a test without thresholds, e.g.
 *   Ebbeling). For `TestTrends.series` and `AbsoluteVo2.compute`.
 * @property restingHeartRate The resting heart rate measurements (bpm). For `MeasurementTrends.trailingMean`.
 * @property rmssd The RMSSD (ms) of the resting measurements that have one (a strap with RR intervals).
 * @property weights The user's real weights (kg): the placeholder weight of the generic profile is not a measurement
 *   and is left out. For `AbsoluteVo2.compute` and the weight chart.
 */
data class PerformanceInputs(
    val tests: List<TestPoint> = emptyList(),
    val restingHeartRate: List<DatedValue> = emptyList(),
    val rmssd: List<DatedValue> = emptyList(),
    val weights: List<DatedValue> = emptyList(),
)

/**
 * The per-workout analysis cache, read side. Derived data: it is computed and kept up to date by the data layer (see
 * [WorkoutAnalysisRecalculator]), local to the device and never synchronised. All flows are cold, re-emit when the data
 * changes and are empty or `null` for unknown ids.
 */
interface WorkoutAnalysisRepository {

    /**
     * The analysis of one workout, `null` while it has not been computed (a workout that just ended, an unassigned or
     * in-progress workout, one without samples, an unknown id). Never an error: a missing section has its reason inside
     * the analysis.
     */
    fun analysis(workoutId: Long): Flow<StoredWorkoutAnalysis?>

    /**
     * The analyses of the user's finished workouts, **oldest first** (by start time); workouts without an analysis yet
     * are left out. Re-emitted when an analysis changes.
     */
    fun analyses(userId: Long): Flow<List<DatedWorkoutAnalysis>>

    /** The other inputs of the trends of one user (tests, resting heart rate, RMSSD, weights), mapped to the `:analysis` types. */
    fun performanceInputs(userId: Long): Flow<PerformanceInputs>
}

/**
 * Brings the cached workout analyses up to date: computes the analysis of every finished workout of a user that has none,
 * or whose analysis was made by another algorithm version or from other inputs (see `WorkoutAnalysisFingerprint`: the
 * context as of the workout's date, e.g. a new fitness test, weight or resting measurement from before the workout, and
 * the samples), and removes the analyses of workouts that no longer exist or have no owner.
 *
 * ## When it runs
 * The real data layer runs it by itself, in the background: at app start (once the database is open, after the
 * [FitnessRecalculator], whose results change the context), and then **after any change** of the workouts, weights, fitness
 * test results, resting heart rate measurements or profiles, debounced ([keepUpToDate]; a workout ends, a pull of the
 * synchronisation or a local edit all end in such a change). The synchronisation also calls [recalculate] after a pull that
 * brought any of that. The UI never needs to call it.
 */
interface WorkoutAnalysisRecalculator {

    /**
     * Recalculates every analysis that needs it, one workout at a time. Returns how many analyses were computed (0 when
     * everything is up to date). Idempotent, safe to call at any time and from several places at once (calls are
     * serialised), never throws for missing data (reasons are data). Runs on the caller's dispatcher: call it from a
     * background one.
     */
    suspend fun recalculate(): Int

    /**
     * Calls [recalculate] after every change of the data the analyses depend on, waiting [debounceMillis] after the last
     * change so that a burst of changes (a pull, an import) is one recalculation. Suspends until cancelled; failures of a
     * recalculation are ignored (the next change tries again). The real data layer starts it by itself.
     */
    suspend fun keepUpToDate(debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS): Nothing

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 2_000L
    }
}
