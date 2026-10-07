package com.treadz.console.userdata.room

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserProfile
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
)

internal fun WeightEntryEntity.toEntry() = WeightEntry(id, userId, LocalDate.ofEpochDay(epochDay), weightKg, isPlaceholder)

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
