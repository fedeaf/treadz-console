package com.treadz.console.userdata.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Enums are stored by name (stable codes). Dates as epoch days, instants as epoch milliseconds.
//
// Since schema version 2 users, weight entries and workouts also carry the synchronisation bookkeeping:
//  - uuid: global identity, the same on every device (the Long ids are local keys only).
//  - updatedAtMillis / updatedBy: version of the record (last change and the device that made it): last writer wins.
//  - deleted: tombstone. Rows are never removed when the user deletes them, so the deletion can be synchronised;
//    every query that serves the UI filters them out.
//  - dirty: the record changed since it was last synchronised (whether it is *eligible* for upload is decided by the
//    sync queries: generic profile, placeholder weights, workouts in progress or without an owner never are).
//
// Since schema version 3 weight entries also carry createdAtMillis (the tie-break between entries of the same day).

@Entity(
    tableName = "users",
    // Not unique: two devices may create a profile with the same name; the name rule is enforced by the repository
    // against live profiles only.
    indices = [Index(value = ["nameKey"]), Index(value = ["uuid"], unique = true)],
)
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
    @ColumnInfo(defaultValue = "''") val uuid: String,
    @ColumnInfo(defaultValue = "0") val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "''") val updatedBy: String,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean,
    @ColumnInfo(defaultValue = "0") val dirty: Boolean,
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
    indices = [Index(value = ["userId", "epochDay"]), Index(value = ["uuid"], unique = true)],
)
internal data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val epochDay: Long,
    val weightKg: Double,
    /** Made-up weight of the generic profile; replaced by the first real entry. Never synchronised. */
    val isPlaceholder: Boolean,
    @ColumnInfo(defaultValue = "''") val uuid: String,
    @ColumnInfo(defaultValue = "0") val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "''") val updatedBy: String,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean,
    @ColumnInfo(defaultValue = "0") val dirty: Boolean,
    /**
     * When the entry was created (since schema version 3). Synchronised and never changed by an edit: together with
     * the date and the [uuid] it gives every device the same order, hence the same "latest" entry. Unlike the Long
     * [id] it means the same on every device.
     */
    @ColumnInfo(defaultValue = "0") val createdAtMillis: Long,
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
    indices = [Index("userId"), Index("startedAtMillis"), Index(value = ["uuid"], unique = true)],
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
    @ColumnInfo(defaultValue = "''") val uuid: String,
    @ColumnInfo(defaultValue = "0") val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "''") val updatedBy: String,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean,
    @ColumnInfo(defaultValue = "0") val dirty: Boolean,
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
