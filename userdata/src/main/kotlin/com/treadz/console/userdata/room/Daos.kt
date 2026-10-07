package com.treadz.console.userdata.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface UserDao {
    @Query("SELECT * FROM users ORDER BY nameKey, id")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id")
    fun observe(id: Long): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun get(id: Long): UserEntity?

    @Query("SELECT u.* FROM users u INNER JOIN active_user a ON a.userId = u.id WHERE a.id = 1")
    fun observeActive(): Flow<UserEntity?>

    @Query("SELECT userId FROM active_user WHERE id = 1")
    suspend fun activeUserId(): Long?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE nameKey = :nameKey AND id <> :exceptId")
    suspend fun countWithName(nameKey: String, exceptId: Long): Int

    @Insert
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity): Int

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActive(active: ActiveUserEntity)

    @Query("SELECT targetWeightKg FROM users WHERE id = :userId")
    fun observeTargetWeight(userId: Long): Flow<Double?>

    @Query("UPDATE users SET targetWeightKg = :kg WHERE id = :userId")
    suspend fun setTargetWeight(userId: Long, kg: Double?): Int
}

@Dao
internal interface WeightDao {
    @Query("SELECT * FROM weight_entries WHERE userId = :userId ORDER BY epochDay, id")
    fun observeEntries(userId: Long): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries WHERE userId = :userId ORDER BY epochDay DESC, id DESC LIMIT 1")
    fun observeLatest(userId: Long): Flow<WeightEntryEntity?>

    @Query("SELECT * FROM weight_entries WHERE id = :id")
    suspend fun get(id: Long): WeightEntryEntity?

    @Insert
    suspend fun insert(entry: WeightEntryEntity): Long

    @Update
    suspend fun update(entry: WeightEntryEntity): Int

    @Query("DELETE FROM weight_entries WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM weight_entries WHERE userId = :userId AND isPlaceholder = 1")
    suspend fun deletePlaceholders(userId: Long): Int
}

@Dao
internal interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE userId = :userId ORDER BY startedAtMillis DESC, id DESC")
    fun observeByUser(userId: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE userId IS NULL ORDER BY startedAtMillis DESC, id DESC")
    fun observeUnassigned(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE endReason IS NULL ORDER BY startedAtMillis DESC, id DESC LIMIT 1")
    fun observeInProgress(): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun get(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workout_samples WHERE workoutId = :workoutId ORDER BY seq")
    suspend fun samples(workoutId: Long): List<WorkoutSampleEntity>

    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<WorkoutSampleEntity>)

    @Query(
        "UPDATE workouts SET durationSeconds = :durationSeconds, distanceMeters = :distanceMeters, calories = :calories, " +
            "averageSpeedKmh = :averageSpeedKmh, maxSpeedKmh = :maxSpeedKmh, averageHeartRateBpm = :averageHeartRateBpm, " +
            "maxHeartRateBpm = :maxHeartRateBpm, maxInclinePercent = :maxInclinePercent WHERE id = :id",
    )
    suspend fun updateStats(
        id: Long,
        durationSeconds: Int,
        distanceMeters: Int,
        calories: Int,
        averageSpeedKmh: Double,
        maxSpeedKmh: Double,
        averageHeartRateBpm: Int?,
        maxHeartRateBpm: Int?,
        maxInclinePercent: Double,
    ): Int

    @Query("UPDATE workouts SET endedAtMillis = :endedAtMillis, endReason = :reason WHERE id = :id")
    suspend fun markEnded(id: Long, endedAtMillis: Long, reason: String): Int

    @Query("UPDATE workouts SET userId = :userId WHERE id = :id")
    suspend fun assign(id: Long, userId: Long): Int

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query(
        "UPDATE workouts SET endReason = :reason, endedAtMillis = COALESCE(" +
            "(SELECT MAX(timestampMillis) FROM workout_samples WHERE workoutId = workouts.id), startedAtMillis) " +
            "WHERE endReason IS NULL",
    )
    suspend fun closeInterrupted(reason: String): Int
}
