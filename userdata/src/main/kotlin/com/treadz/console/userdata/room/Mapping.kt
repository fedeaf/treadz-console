package com.treadz.console.userdata.room

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.FitnessTestRecord
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.RestingHeartRateMeasurement
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.UserProgram
import com.treadz.console.userdata.WeightEntry
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * @param restingHeartRateBpm The reference of the user's resting heart rate history. The column
 *   `users.restingHeartRateBpm` is the value of schema versions before 5 and is no longer read.
 */
internal fun UserEntity.toProfile(restingHeartRateBpm: Int? = null) = UserProfile(
    id = id,
    name = name,
    sex = enumOrNull<Sex>(sex) ?: Sex.Male,
    heightCm = heightCm,
    birthDate = LocalDate.ofEpochDay(birthEpochDay),
    restingHeartRateBpm = restingHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    fitnessLevel = fitnessLevel?.let { enumOrNull<FitnessLevel>(it) },
    goal = goal?.let { enumOrNull<Goal>(it) },
    createdAtMillis = createdAtMillis,
    isGeneric = isGeneric,
    maxHeartRateSetAtMillis = maxHeartRateSetAtMillis,
    strapAddress = strapAddress,
    strapName = strapName,
    avatarId = avatar,
)

internal fun WeightEntryEntity.toEntry() = WeightEntry(id, userId, LocalDate.ofEpochDay(epochDay), weightKg, isPlaceholder, createdAtMillis)

internal fun WorkoutEntity.toSummary() = WorkoutSummary(
    id = id,
    userId = userId,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    endReason = endReason?.let { enumOrNull<WorkoutEndReason>(it) ?: WorkoutEndReason.Interrupted },
    stats = WorkoutStats(
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        calories = calories,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        averageHeartRateBpm = averageHeartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm,
        maxInclinePercent = maxInclinePercent,
        peakHeartRate = peakHeartRateBpm?.let {
            HeartRatePeak(it, peakHeartRateSource?.let { name -> enumOrNull<HeartRateSourceKind>(name) }, peakHeartRateAtMillis ?: 0)
        },
    ),
    programId = programId,
    programName = programName,
    fitnessTestId = fitnessTestId,
    generatorId = generatorId,
)

internal fun WorkoutSampleEntity.toSample() = WorkoutSample(
    seq = seq,
    timestampMillis = timestampMillis,
    elapsedSeconds = elapsedSeconds,
    speedKmh = speedKmh,
    targetSpeedKmh = targetSpeedKmh,
    inclinePercent = inclinePercent,
    distanceMeters = distanceMeters,
    calories = calories,
    heartRateBpm = heartRateBpm,
    beltStatus = enumOrNull<BeltStatus>(beltStatus) ?: BeltStatus.Idle,
    isStale = isStale,
    heartRateSource = heartRateSource?.let { enumOrNull<HeartRateSourceKind>(it) },
    rrIntervalsMs = RrCodec.decode(rrIntervalsMs),
)

internal fun WorkoutSample.toEntity(workoutId: Long) = WorkoutSampleEntity(
    workoutId = workoutId,
    seq = seq,
    timestampMillis = timestampMillis,
    elapsedSeconds = elapsedSeconds,
    speedKmh = speedKmh,
    targetSpeedKmh = targetSpeedKmh,
    inclinePercent = inclinePercent,
    distanceMeters = distanceMeters,
    calories = calories,
    heartRateBpm = heartRateBpm,
    beltStatus = beltStatus.name,
    isStale = isStale,
    heartRateSource = heartRateSource?.name,
    rrIntervalsMs = RrCodec.encode(rrIntervalsMs),
)

/** RR intervals as stored in a text column: milliseconds separated by `;`, `null` when there are none. */
internal object RrCodec {
    fun encode(intervals: List<Int>): String? = if (intervals.isEmpty()) null else intervals.joinToString(";")

    fun decode(text: String?): List<Int> = if (text.isNullOrEmpty()) emptyList() else text.split(';').mapNotNull { it.trim().toIntOrNull() }
}

internal fun RestingHeartRateEntity.toMeasurement() = RestingHeartRateMeasurement(
    id = id,
    userId = userId,
    measuredAtMillis = measuredAtMillis,
    bpm = bpm,
    source = enumOrNull<RestingHeartRateSource>(source) ?: RestingHeartRateSource.Manual,
    lowestMeanBpm = lowestMeanBpm,
    coverage = coverage,
    rmssdMs = rmssdMs,
)

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

/** Turns a database failure into [DataResult.StorageError] instead of crashing the caller. */
internal inline fun <T> guarded(block: () -> DataResult<T>): DataResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DataResult.StorageError(e.message ?: e::class.java.simpleName)
}

// ---- documents ----
//
// Programs, fitness test results and plans are stored as the JSON text of their documents. A document that cannot be
// read (written by a newer app, or damaged) is kept as it is and simply not shown: these functions return null for it.

internal fun readProgram(json: String): Program? = try {
    ProgramJson.decode(json, validate = false)
} catch (e: Exception) {
    null
}

internal fun readFitnessResult(json: String): FitnessTestResult? = try {
    FitnessTestJson.decode(json)
} catch (e: Exception) {
    null
}

internal fun readSessionRecord(json: String?): GeneratedSessionRecord? = if (json == null) null else try {
    GeneratedSessionJson.decode(json)
} catch (e: Exception) {
    null
}

internal fun readPlan(json: String?): ResolvedPlan? = if (json == null) null else try {
    PlanJson.decode(json)
} catch (e: Exception) {
    null
}

internal fun ProgramEntity.toUserProgram(): UserProgram? =
    readProgram(json)?.let { UserProgram(userId, it, createdAtMillis, updatedAtMillis) }

internal fun FitnessTestEntity.toRecord(): FitnessTestRecord? =
    readFitnessResult(json)?.let { FitnessTestRecord(userId, it, updatedAtMillis) }

internal fun StartParametersEntity.toParameters() = com.treadz.console.programs.plan.StartParameters(
    totalDurationSeconds = totalDurationSeconds,
    easySpeedKmh = easySpeedKmh,
    maxSpeedKmh = maxSpeedKmh,
    maxInclinePercent = maxInclinePercent,
    intensityAdjustmentPercent = intensityAdjustmentPercent,
)
