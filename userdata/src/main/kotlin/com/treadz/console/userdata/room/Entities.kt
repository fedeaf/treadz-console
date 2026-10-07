package com.treadz.console.userdata.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Enums are stored by name (stable codes). Dates as epoch days, instants as epoch milliseconds.

@Entity(tableName = "users", indices = [Index(value = ["nameKey"], unique = true)])
internal data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Lower-cased name, unique: two profiles cannot differ only by case. */
    val nameKey: String,
    val sex: String,
    val heightCm: Int,
    val birthEpochDay: Long,
    val restingHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val fitnessLevel: String?,
    val goal: String?,
    val targetWeightKg: Double?,
    val createdAtMillis: Long,
    /** The generic default profile (empty name, nameKey ""). */
    val isGeneric: Boolean,
)

/** Single row (id 1) holding the active user; cleared by the database itself when that user is deleted. */
@Entity(
    tableName = "active_user",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("userId")],
)
internal data class ActiveUserEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val userId: Long?,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}

@Entity(
    tableName = "weight_entries",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId", "epochDay"])],
)
internal data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val epochDay: Long,
    val weightKg: Double,
    /** Made-up weight of the generic profile; replaced by the first real entry. */
    val isPlaceholder: Boolean,
)

@Entity(
    tableName = "workouts",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId"), Index("startedAtMillis")],
)
internal data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `null`: done with no active user. */
    val userId: Long?,
    val startedAtMillis: Long,
    /** `null` while in progress. */
    val endedAtMillis: Long?,
    /** `null` while in progress. */
    val endReason: String?,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val calories: Int,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val maxInclinePercent: Double,
)

@Entity(
    tableName = "workout_samples",
    primaryKeys = ["workoutId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class WorkoutSampleEntity(
    val workoutId: Long,
    val seq: Int,
    val timestampMillis: Long,
    val elapsedSeconds: Int,
    val speedKmh: Double,
    val targetSpeedKmh: Double?,
    val inclinePercent: Double,
    val distanceMeters: Int,
    val calories: Int,
    val heartRateBpm: Int?,
    val beltStatus: String,
    val isStale: Boolean,
)
