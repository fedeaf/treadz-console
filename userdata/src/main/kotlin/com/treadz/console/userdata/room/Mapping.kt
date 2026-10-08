package com.treadz.console.userdata.room

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestResult
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
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.UserProgram
import com.treadz.console.userdata.WeightEntry
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

internal fun UserEntity.toProfile() = UserProfile(
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
    ),
    programId = programId,
    programName = programName,
    fitnessTestId = fitnessTestId,
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
