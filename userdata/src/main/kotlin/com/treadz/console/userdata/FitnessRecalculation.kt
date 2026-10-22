package com.treadz.console.userdata

import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.programs.fitness.FitnessTestRecalculation
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.HeartRateSourceKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.treadz.console.analysis.samples.WorkoutSample as AnalysisSample

/**
 * Brings the stored fitness test results up to date: the derived values of the maximal tests (the VO2max of the walking
 * test and the DFA-α1 thresholds of both maximal tests) are computed again from the workout each result belongs to
 * (`FitnessTestRecalculation`, a pure and idempotent function of `:programs`).
 *
 * ## When it runs
 * [recalculate] is cheap when there is nothing to do (it reads the valid maximal results and decides from the result
 * alone). The data layer runs it **once at app start**, in the background, as soon as the database is open
 * (`TreadZUserData.get`), and the synchronisation runs it **after a pull that brought fitness test results or workouts
 * linked to a test** (`SyncEngine`), before pushing, so the recalculated documents go up in the same cycle. A result is
 * recalculated **once**: afterwards it carries the current algorithm version and is left alone; a result without a
 * linked workout is stored as `noWorkoutSamples` (with the VO2max from its stage records for the walking test) and
 * is only looked at again when the workout appears or the algorithm version goes up. Nothing is ever retried in a loop.
 *
 * ## What it feeds to the calculation
 *  - **Samples**: those of the workout whose `fitnessTestId` is the result's id, without the **stale** samples (their
 *    values are the last known ones replayed on a lost connection, not measurements; the export drops them too),
 *    mapped to the neutral `WorkoutSample` of `:analysis`: heart rate `null` or 0 becomes `null`; the source
 *    `DirectStrap` is `Strap`, `TreadmillConsole` is `Console` and a heart rate without a source (workouts imported from
 *    the official app, recorded before the source existed) is `Unknown` (it counts as the console's: valid for
 *    heart-rate metrics, never for RR). A workout without samples counts as no workout. A workout **still in progress**
 *    postpones the result (nothing is written), so a half-recorded workout never produces a final answer.
 *  - **Resting heart rate** (only used for the % of the heart-rate reserve at each threshold): the value the app uses
 *    for the user's heart-rate profile (the reference of the resting heart rate history, [RestingHeartRates.reference])
 *    **evaluated at the start time of the test** instead of at the current time, so that it depends only on
 *    synchronised data and two devices that recalculate the same result write the same document: see [restingAt].
 *
 * Saving goes through the normal path (`FitnessTestRepository.save`), so the result is synchronised and the fitness
 * profile (live, and the snapshot of `fitness.json`) is derived from it as for any other change.
 */
interface FitnessRecalculator {

    /**
     * Recalculates every stored result that needs it. Returns how many results were rewritten (0 when everything is up
     * to date). Safe to call at any time and from several places at once (calls are serialised); never throws for
     * missing samples or RR intervals, which are data in the result. Runs on the caller's dispatcher: call it from a
     * background one.
     */
    suspend fun recalculate(): Int
}

/** A workout linked to a fitness test, as the recalculation needs it. */
internal class LinkedWorkout(val inProgress: Boolean, val samples: List<WorkoutSample>)

/** The storage the shared recalculation works on (Room, in memory). */
internal interface RecalculationSource {
    /** The live results that may need recalculation: valid results of the analysed test types, in any order. */
    suspend fun candidates(): List<FitnessTestRecord>

    /** The workout linked to the test, or `null`. */
    suspend fun linkedWorkout(testId: String): LinkedWorkout?

    /** The live resting heart rate measurements of the user. */
    suspend fun restingPoints(userId: Long): List<RestingPoint>

    /** Stores the recalculated result through the normal path. */
    suspend fun save(userId: Long, result: FitnessTestResult)
}

internal class StoredResultsRecalculator(private val source: RecalculationSource) : FitnessRecalculator {
    private val lock = Mutex()

    override suspend fun recalculate(): Int = lock.withLock {
        var rewritten = 0
        for (record in source.candidates()) {
            val result = record.result
            // Decided from the result alone first: no database work for a result that is up to date.
            if (!FitnessTestRecalculation.needsRecalculation(result, samplesAvailable = true)) continue
            val workout = source.linkedWorkout(result.id)
            if (workout != null && workout.inProgress) continue
            val samples = workout?.samples?.let(FitnessRecalculationInputs::samples)?.takeIf { it.isNotEmpty() }
            val resting = FitnessRecalculationInputs.restingAt(source.restingPoints(record.userId), result.startedAtMillis)
            val updated = FitnessTestRecalculation.recalculate(result, samples, resting) ?: continue
            source.save(record.userId, updated)
            rewritten++
        }
        rewritten
    }
}

/** The inputs of the recalculation that this layer decides (pure functions, shared by every implementation). */
internal object FitnessRecalculationInputs {

    /**
     * The stored samples as the neutral samples of `:analysis`, in the same order, without the stale ones (see
     * [FitnessRecalculator]).
     */
    fun samples(stored: List<WorkoutSample>): List<AnalysisSample> = stored.filter { !it.isStale }.map { s ->
        val bpm = s.heartRateBpm?.takeIf { it > 0 }
        AnalysisSample(
            timeMillis = s.timestampMillis,
            speedKmh = s.speedKmh,
            inclinePercent = s.inclinePercent,
            heartRateBpm = bpm,
            heartRateSource = bpm?.let {
                when (s.heartRateSource) {
                    HeartRateSourceKind.DirectStrap -> SampleHeartRateSource.Strap
                    HeartRateSourceKind.TreadmillConsole -> SampleHeartRateSource.Console
                    null -> SampleHeartRateSource.Unknown
                }
            },
            rrIntervalsMs = s.rrIntervalsMs,
        )
    }

    /**
     * The resting heart rate for a result whose test started at [testStartMillis]: the reference of the measurements
     * ([RestingHeartRates.reference]: the median of those of the 30 days up to the test, or the latest one before
     * that) considering **only the measurements taken up to the start of the test**. Without any such measurement, the
     * **earliest** measurement there is (a resting rate typed or measured after the test is still the best figure for
     * the user then, and the earliest one does not change as new ones are added). `null` without measurements.
     *
     * It is a function of the synchronised measurements and the test's start time only (never of the clock): the
     * reference of the profile as the app uses it is the same rule evaluated at the current time.
     */
    fun restingAt(points: List<RestingPoint>, testStartMillis: Long): Int? {
        val upToTest = points.filter { it.atMillis <= testStartMillis }
        if (upToTest.isNotEmpty()) return RestingHeartRates.reference(upToTest, testStartMillis)?.bpm
        return points.minWithOrNull(compareBy<RestingPoint>({ it.atMillis }, { it.bpm }))?.bpm
    }
}
