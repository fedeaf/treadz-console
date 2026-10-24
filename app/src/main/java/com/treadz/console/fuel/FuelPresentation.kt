package com.treadz.console.fuel

import com.treadz.console.analysis.energy.FuelSnapshot
import com.treadz.console.analysis.workout.FuelAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.training.FinishedWorkout
import com.treadz.console.userdata.StoredWorkoutAnalysis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The estimated fat/carbohydrate split of the energy of a workout, as whole numbers that **add up to the calories shown
 * next to them**: [fatKcal] + [carbKcal] is the calories of that screen and [fatPercent] + [carbPercent] is 100.
 *
 * @property dimmed The live figures are kept but the latest heart rate was not valid, so they are shown in the secondary
 *   colour. Never set for a stored analysis.
 */
data class FuelFigures(
    val fatKcal: Int,
    val carbKcal: Int,
    val fatPercent: Int,
    val carbPercent: Int,
    val dimmed: Boolean = false,
) {
    val totalKcal: Int get() = fatKcal + carbKcal
}

/** One zone with the share of fat in the energy spent in it ([FuelZoneShare.fatPercent], whole percent). */
data class FuelZoneShare(val zone: Int, val seconds: Int, val fatPercent: Int)

/**
 * One minute of the energy chart. [startSeconds] is on the time axis of the other charts of the workout detail (seconds
 * since its first sample).
 */
data class FuelMinuteBar(val startSeconds: Double, val fatKcal: Double, val carbKcal: Double)

/**
 * What the «Energy sources» card of the workout detail shows.
 *
 * @property minutes Empty when the samples are not known (nothing to place the minutes on).
 * @property durationSeconds Length of the time axis of the other charts of the detail (first to last sample).
 */
data class FuelDetail(
    val figures: FuelFigures,
    val zones: List<FuelZoneShare>,
    val minutes: List<FuelMinuteBar>,
    val durationSeconds: Double,
)

/**
 * The one place that turns the share of fat of the engine into what the screens show, so they all agree.
 *
 * **Rules, the same everywhere**: the split is shown only when it exists (live: [FuelSnapshot.showable]; stored: the
 * analysis has its fuel section) and only when there are calories to split (more than 0); otherwise `null` and the
 * screen shows nothing. The kcal always come from the calories **of the same screen** (never from the analysis' own kcal,
 * which can differ for workouts recorded before the app computed the calories): fat = round(calories × share),
 * carbohydrate = the rest; fat % = round(share × 100), carbohydrate % = 100 − fat %.
 */
object FuelPresenter {

    /** The figures for [calories] split by [fatShare] (0-1), or `null` when either is missing. */
    fun figures(calories: Int?, fatShare: Double?, dimmed: Boolean = false): FuelFigures? {
        if (calories == null || calories <= 0 || fatShare == null || fatShare.isNaN()) return null
        val share = fatShare.coerceIn(0.0, 1.0)
        val fatKcal = (calories * share).roundToInt()
        val fatPercent = (share * 100).roundToInt()
        return FuelFigures(fatKcal, calories - fatKcal, fatPercent, 100 - fatPercent, dimmed)
    }

    /** The live figures of [calories] (the live count): hidden unless [fuel] is showable; dimmed while it is not currently valid. */
    fun live(calories: Int?, fuel: FuelSnapshot?): FuelFigures? =
        if (fuel == null || !fuel.showable) null else figures(calories, fuel.fatShare, dimmed = !fuel.currentlyValid)

    /** The figures of a finished workout of [calories] (the stored ones) from its [analysis]; `null` while there is none. */
    fun finished(calories: Int?, analysis: WorkoutAnalysis?): FuelFigures? =
        figures(calories, analysis?.fuel?.value?.fatShare)

    /**
     * The card of the workout detail. [firstSampleMillis] and [lastSampleMillis] are the first and last stored sample times
     * (the other charts' axis); without them the minutes are left out.
     */
    fun detail(calories: Int?, analysis: WorkoutAnalysis?, firstSampleMillis: Long?, lastSampleMillis: Long?): FuelDetail? {
        val fuel: FuelAnalysis = analysis?.fuel?.value ?: return null
        val figures = figures(calories, fuel.fatShare) ?: return null
        val zones = fuel.byZone.mapNotNull { zone ->
            val energy = zone.fatKcal + zone.carbKcal
            if (zone.seconds <= 0 || energy <= 0.0) null
            else FuelZoneShare(zone.number, zone.seconds, (zone.fatKcal / energy * 100).roundToInt().coerceIn(0, 100))
        }.sortedBy { it.zone }
        val known = firstSampleMillis != null && lastSampleMillis != null
        val minutes = if (!known) emptyList() else fuel.minutes.map { FuelMinuteBar((it.startMillis - firstSampleMillis!!) / 1000.0, it.fatKcal, it.carbKcal) }
        val duration = if (known) ((lastSampleMillis!! - firstSampleMillis!!) / 1000.0).coerceAtLeast(0.0) else 0.0
        return FuelDetail(figures, zones, minutes, duration)
    }

    /**
     * The figures of the workout that has just ended, as its analysis arrives (the data layer computes it a few seconds
     * after the end): `null` until then, while there is no finished workout, and for a fitness test's workout (the test
     * screens do not show the split). [analysis] is `WorkoutAnalysisRepository.analysis`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun finishedFlow(ended: Flow<FinishedWorkout?>, analysis: (Long) -> Flow<StoredWorkoutAnalysis?>): Flow<FuelFigures?> =
        ended.flatMapLatest { workout ->
            if (workout == null || workout.fitnessTestId != null) flowOf(null)
            else analysis(workout.workoutId).map { finished(workout.calories, it?.analysis) }
        }.distinctUntilChanged()
}
