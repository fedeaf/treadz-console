package com.treadz.console.userdata

import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.FitnessProfileCalculator
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.HeartRateMaxPolicy
import com.treadz.console.programs.fitness.Validity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import com.treadz.console.analysis.fitness.Sex as FitnessSex

/** A stored fitness test result of a user. */
data class FitnessTestRecord(
    val userId: Long,
    val result: FitnessTestResult,
    /** Last change of the record, epoch ms. */
    val updatedAtMillis: Long,
) {
    val id: String get() = result.id
}

/**
 * The fitness test results of each user, the fitness profile derived from them and the maximum heart rate decision.
 *
 * Results are synchronised (`users/<name>-<uuid>/fitness-tests/<id>.json`, same rules as the other records). A result
 * whose document cannot be read (written by a newer app) is kept and synchronised as it is, and left out of the lists.
 *
 * ## The fitness profile
 * The weighted VO2max, the MAS and the measured maximum heart rate belong to the user's profile. There are two views
 * of the same data (see [FitnessProfiles]):
 *  - [fitnessProfile], the **snapshot**: the same on every device that has the same results. It is what the synchronised
 *    `fitness.json` carries (for people and Claude to read) and is not stored: it is derived, so it can never be stale.
 *  - [liveFitnessProfile], computed with the real current time: results age and expire while the app is not used. Use
 *    it for personalisation and for the maximum heart rate decision.
 */
interface FitnessTestRepository {

    /** The user's results, newest first (by start time). Empty for an unknown user. */
    fun results(userId: Long): Flow<List<FitnessTestRecord>>

    suspend fun get(testId: String): FitnessTestRecord?

    /**
     * Stores a result (the document of `FitnessTestJson`) for the user, replacing the one with the same id. Saving an
     * identical result changes nothing. The id must be 1 to 64 letters, digits and hyphens (a UUID in practice): it
     * names the file in the repository (`IllegalArgumentException` otherwise). [DataResult.NotFound] if the user does
     * not exist; a result id that belongs to another user is a [DataResult.StorageError].
     */
    suspend fun save(userId: Long, result: FitnessTestResult): DataResult<FitnessTestRecord>

    /** Deletes a result (a tombstone). The fitness profile and the maximum heart rate decision are recomputed. */
    suspend fun delete(testId: String): DataResult<Unit>

    /**
     * Keeps a result out of the uploads until [releaseUpload] (see [WorkoutHistoryRepository.holdUpload]): it is held
     * from the start of the test, before it exists. In memory only.
     */
    fun holdUpload(testId: String) {}

    fun releaseUpload(testId: String) {}

    /**
     * The deterministic snapshot of the fitness profile (see [FitnessProfiles.snapshot]); `null` when the user has no
     * usable result (or is unknown). Emits again whenever the results or the profile's sex and birth date change.
     */
    fun fitnessProfile(userId: Long): Flow<FitnessProfile?>

    /**
     * The fitness profile as of the current time (see [FitnessProfiles.live]); `null` only for an unknown user (the
     * profile of a user without usable results is empty). Recomputed when the data changes, not as time passes.
     */
    fun liveFitnessProfile(userId: Long): Flow<FitnessProfile?>

    /**
     * Which maximum heart rate to use, by `HeartRateMaxPolicy`: the manual value of the profile, else the maximum
     * measured by the fitness tests, else the estimate from the age. `askUser` is set when the user should be asked
     * "use the measured X instead of your Y?": answer with [useMeasuredHeartRateMax] or [keepManualHeartRateMax].
     * `null` for an unknown user. It is the value to use from now on (not `UserProfile.effectiveMaxHeartRateBpm`).
     */
    fun heartRateMax(userId: Long): Flow<HeartRateMaxDecision?>

    /**
     * "Use the measured value": clears the manual maximum heart rate (the policy then returns the measured one).
     * [DataResult.NotFound] if the user does not exist or there is no measured value to adopt.
     */
    suspend fun useMeasuredHeartRateMax(userId: Long): DataResult<Unit>

    /**
     * "Keep mine": records that the manual value was confirmed now ([UserProfile.maxHeartRateSetAtMillis]), so the
     * question is not asked again until a newer test measures something different. [DataResult.NotFound] if the user
     * does not exist or has no manual value.
     */
    suspend fun keepManualHeartRateMax(userId: Long): DataResult<Unit>
}

/**
 * Pure functions that derive the fitness profile and the maximum heart rate decision from stored results. Shared by
 * every implementation, so they all agree.
 */
object FitnessProfiles {

    /**
     * Version of the calculation rules behind the snapshot, part of the [FitnessFingerprint] of `fitness.json` in the data
     * repository. **Raise it whenever the output of `FitnessProfileCalculator` or of [snapshot] changes for the same
     * results** (a new rule, a changed weight, a new field in the profile document): every device then writes the file
     * again, and a device with a lower version never overwrites a file written with a higher one. A test pins the
     * snapshot of a fixed set of results, so that a change of the rules does not go unnoticed.
     */
    const val RULES_VERSION = 2 // 2: maximal walking test ×0.5 with a running test; no outputs below 85 % of the maximum

    /**
     * The **snapshot**: `FitnessProfileCalculator` with `now` = the start time of the newest usable result (valid,
     * with something to derive: see below), the age at that day in UTC, and the results in a fixed order (by start time, then id). It depends
     * only on synchronised data (the results, the sex and the birth date), never on this device's clock, time zone or
     * database order, so every device computes exactly the same document. Its `computedAtMillis` is that newest
     * result's time. `null` without a usable result.
     *
     * A result is usable when it is valid and has outputs (VO2max; the maximal walking test has them since format
     * version 2), a measured maximum heart rate (a walking result stored before version 2 and not recalculated yet has no
     * outputs, only the maximum heart rate) or thresholds. The snapshot therefore carries everything the calculator
     * derives: the VO2max with the weight of the submaximal tests reduced by the recent maximal test and the discrepancy
     * flag, the MAS (measured by the running test only; the walking test's estimate is never a MAS source), the measured
     * maximum heart rate and the thresholds profile.
     *
     * Consequence: the snapshot does not age. A result drops out of it only when a newer one is more than the
     * maximum age (365 days) after it; for ageing use [live].
     */
    fun snapshot(results: List<FitnessTestResult>, sex: Sex, birthDate: LocalDate): FitnessProfile? {
        val usable = results.filter {
            it.validity == Validity.Valid &&
                (it.outputs != null || it.measuredHrMaxCandidateBpm != null || it.thresholds?.vt1 != null || it.thresholds?.vt2 != null)
        }
        if (usable.isEmpty()) return null
        val now = usable.maxOf { it.startedAtMillis }
        val age = ageOn(birthDate, Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate())
        return FitnessProfileCalculator.compute(ordered(results), now, age, sex.toFitness())
    }

    /** The profile as of [nowMillis], with the age at that day in [zone]. Results older than the maximum age are left out. */
    fun live(results: List<FitnessTestResult>, sex: Sex, birthDate: LocalDate, nowMillis: Long, zone: ZoneId): FitnessProfile {
        val age = ageOn(birthDate, Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate())
        return FitnessProfileCalculator.compute(ordered(results), nowMillis, age, sex.toFitness())
    }

    /** The maximum heart rate to use, from the live profile (see [FitnessTestRepository.heartRateMax]). */
    fun heartRateMax(profile: UserProfile, live: FitnessProfile, today: LocalDate): HeartRateMaxDecision =
        HeartRateMaxPolicy.resolve(
            manualBpm = profile.maxHeartRateBpm,
            manualSetAtMillis = profile.maxHeartRateSetAtMillis,
            measured = live.hrMax,
            estimatedBpm = profile.estimatedMaxHeartRateBpm(today),
        )

    internal fun ordered(results: List<FitnessTestResult>) = results.sortedWith(compareBy({ it.startedAtMillis }, { it.id }))

    private fun ageOn(birthDate: LocalDate, date: LocalDate) = Period.between(birthDate, date).years.coerceAtLeast(0)
}

internal fun Sex.toFitness(): FitnessSex = if (this == Sex.Male) FitnessSex.Male else FitnessSex.Female
