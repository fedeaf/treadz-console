package com.treadz.console.fakes

import com.treadz.console.analysis.energy.FuelAnchors
import com.treadz.console.analysis.energy.FuelSnapshot
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.workout.FuelAnalysis
import com.treadz.console.analysis.workout.FuelMinute
import com.treadz.console.analysis.workout.FuelZone
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.history.WorkoutAnalysisPresenter
import com.treadz.console.history.WorkoutDetailUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.PerformanceInputs
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.treadz.console.userdata.StoredWorkoutAnalysis
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.DatedWorkoutAnalysis
import com.treadz.console.userdata.WorkoutAnalysisRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Hand-made fat/carbohydrate splits (synthetic): the engine's live snapshot, the analysis section and a repository to serve them. */
object FuelFixtures {

    val anchors = FuelAnchors(restingBpm = 58, restingAssumed = false, vt1Bpm = 131, vt2Bpm = 155, vt2FromTable = false, maxBpm = 183, sex = Sex.Male)

    /** What the live tracker publishes. */
    fun snapshot(
        fatShare: Double? = 0.38,
        showable: Boolean = true,
        currentlyValid: Boolean = true,
    ) = FuelSnapshot(
        fatShare = fatShare, fatKcal = 0.0, carbKcal = 0.0, movingSeconds = 600, validSeconds = 590, validCoverage = 590 / 600.0,
        currentlyValid = currentlyValid, showable = showable,
    )

    /**
     * One minute per entry from [firstMinuteMillis]: the fat share falls from [startShare] to [endShare] along the
     * workout and every minute spends [kcalPerMinute] (the carbs are what fat leaves).
     */
    fun minutes(firstMinuteMillis: Long, count: Int, startShare: Double = 0.58, endShare: Double = 0.34, kcalPerMinute: Double = 10.7): List<FuelMinute> =
        (0 until count).map { i ->
            val share = startShare + (endShare - startShare) * i / (count - 1).coerceAtLeast(1)
            FuelMinute(firstMinuteMillis + i * 60_000L, kcalPerMinute * share, kcalPerMinute * (1 - share))
        }

    /** Zones 2, 3 and 4 with time in them (1 and 5 empty), and their fat share (62 %, 48 %, 20 %). */
    val zones = listOf(
        FuelZone(1, 0, 0.0, 0.0),
        FuelZone(2, 1200, 62.0, 38.0),
        FuelZone(3, 1500, 48.0, 52.0),
        FuelZone(4, 600, 20.0, 80.0),
        FuelZone(5, 0, 0.0, 0.0),
    )

    /**
     * A split with [fatShare]. Its own kcal ([kcal], not the workout's calories) are deliberately not the stored ones, to
     * show the screens never use them for the totals.
     */
    fun fuel(fatShare: Double = 0.452, kcal: Double = 600.0, minutes: List<FuelMinute> = emptyList(), byZone: List<FuelZone> = zones) = FuelAnalysis(
        fatKcal = kcal * fatShare, carbKcal = kcal * (1 - fatShare), fatShare = fatShare, movingSeconds = 3_600, validSeconds = 3_500,
        validCoverage = 3_500 / 3_600.0, byZone = byZone, minutes = minutes, anchors = anchors,
    )

    /**
     * The detail screen state of a one-hour workout of 642 kcal on [app] with [analysis] (the card's minutes start with the
     * first sample, [startedAtMillis]).
     */
    fun detailState(app: FakeApp, analysis: WorkoutAnalysis?, startedAtMillis: Long = app.nowMillis() - 2 * 3_600_000L): WorkoutDetailUiState {
        val userId = runBlocking { app.userData.profiles.activeUserId() }
        val samples = (0..360).map { i ->
            WorkoutSample(
                seq = i, timestampMillis = startedAtMillis + 10_000L * i, elapsedSeconds = i * 10, speedKmh = 7.0 + (i % 90) / 45.0, targetSpeedKmh = 8.0,
                inclinePercent = (i / 60 % 4).toDouble(), distanceMeters = i * 22, calories = i * 642 / 360, heartRateBpm = 112 + i / 12, beltStatus = BeltStatus.Running,
            )
        }
        val id = app.userData.addWorkout(
            userId,
            WorkoutStats(
                durationSeconds = 3_600, distanceMeters = 8_000, calories = 642, averageSpeedKmh = 8.0, maxSpeedKmh = 9.0,
                averageHeartRateBpm = 134, maxHeartRateBpm = 142, maxInclinePercent = 3.0,
            ),
            samples, startedAtMillis, WorkoutEndReason.Stopped,
        )
        val detail = runBlocking { app.userData.workouts.detail(id)!! }
        return WorkoutDetailUiState(
            loading = false,
            detail = detail,
            owner = runBlocking { app.userData.profiles.activeUser.first() },
            analysis = WorkoutAnalysisPresenter.present(analysis),
            storedAnalysis = analysis,
        )
    }

    /** A complete analysis with the split [fuel] (or without one when `null`: the section is missing). */
    fun analysis(fuel: FuelAnalysis? = fuel()): WorkoutAnalysis =
        AnalysisFixtures.full().copy(fuel = if (fuel != null) Section.of(fuel) else Section.missing(com.treadz.console.analysis.workout.AnalysisReason.NoMeasuredThresholds))
}

/** The analyses of the workouts, set by the tests (the in-memory data layer only computes them from real samples). */
class FakeAnalysisRepository : WorkoutAnalysisRepository {
    private val stored = MutableStateFlow<Map<Long, WorkoutAnalysis>>(emptyMap())

    fun put(workoutId: Long, analysis: WorkoutAnalysis) {
        stored.value = stored.value + (workoutId to analysis)
    }

    override fun analysis(workoutId: Long): Flow<StoredWorkoutAnalysis?> =
        stored.map { all -> all[workoutId]?.let { StoredWorkoutAnalysis(workoutId, it, 0L) } }.distinctUntilChanged()

    override fun analyses(userId: Long): Flow<List<DatedWorkoutAnalysis>> = flowOf(emptyList())

    override fun performanceInputs(userId: Long): Flow<PerformanceInputs> = flowOf(PerformanceInputs())
}

/** [base] with the analyses served by [workoutAnalysis]. */
class UserDataWithAnalysis(base: UserData, override val workoutAnalysis: WorkoutAnalysisRepository) : UserData by base
