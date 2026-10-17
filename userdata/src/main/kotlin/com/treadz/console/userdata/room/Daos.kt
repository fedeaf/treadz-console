package com.treadz.console.userdata.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Every query that serves the UI filters tombstones (`deleted = 0`). Queries named `*Any*`, `*Sync*` or `*ByUuid*`
// are for the synchronisation port and see them.

@Dao
internal interface UserDao {
    @Query("SELECT * FROM users WHERE deleted = 0 ORDER BY nameKey, id")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id AND deleted = 0")
    fun observe(id: Long): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): UserEntity?

    @Query("SELECT u.* FROM users u INNER JOIN active_user a ON a.userId = u.id WHERE a.id = 1 AND u.deleted = 0")
    fun observeActive(): Flow<UserEntity?>

    @Query("SELECT u.id FROM users u INNER JOIN active_user a ON a.userId = u.id WHERE a.id = 1 AND u.deleted = 0")
    suspend fun activeUserId(): Long?

    @Query("SELECT COUNT(*) FROM users WHERE deleted = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE nameKey = :nameKey AND id <> :exceptId AND deleted = 0")
    suspend fun countWithName(nameKey: String, exceptId: Long): Int

    @Insert
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity): Int

    /** Tombstones the profile (children are tombstoned by the caller, in the same transaction). */
    @Query("UPDATE users SET deleted = 1, dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE id = :id AND deleted = 0")
    suspend fun markDeleted(id: Long, at: Long, by: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActive(active: ActiveUserEntity)

    @Query("UPDATE active_user SET userId = NULL WHERE userId = :userId")
    suspend fun clearActiveIf(userId: Long): Int

    @Query("SELECT targetWeightKg FROM users WHERE id = :userId AND deleted = 0")
    fun observeTargetWeight(userId: Long): Flow<Double?>

    @Query("UPDATE users SET targetWeightKg = :kg, dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE id = :userId AND deleted = 0")
    suspend fun setTargetWeight(userId: Long, kg: Double?, at: Long, by: String): Int

    // ---- synchronisation ----

    @Query("SELECT * FROM users WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getAny(id: Long): UserEntity?

    /** Profiles waiting to be uploaded: the generic profile is not synchronised until it is personalised. */
    @Query("SELECT * FROM users WHERE dirty = 1 AND isGeneric = 0 ORDER BY id")
    suspend fun pending(): List<UserEntity>

    @Query("SELECT COUNT(*) FROM users WHERE dirty = 1 AND isGeneric = 0")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE users SET dirty = 0 WHERE uuid = :uuid AND updatedAtMillis = :at AND updatedBy = :by")
    suspend fun markSynced(uuid: String, at: Long, by: String): Int

    @Query("SELECT * FROM users WHERE deleted = 0")
    suspend fun allLive(): List<UserEntity>
}

@Dao
internal interface WeightDao {
    @Query("SELECT * FROM weight_entries WHERE userId = :userId AND deleted = 0 ORDER BY epochDay, createdAtMillis, uuid")
    fun observeEntries(userId: Long): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries WHERE userId = :userId AND deleted = 0 ORDER BY epochDay DESC, createdAtMillis DESC, uuid DESC LIMIT 1")
    fun observeLatest(userId: Long): Flow<WeightEntryEntity?>

    @Query("SELECT * FROM weight_entries WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): WeightEntryEntity?

    @Insert
    suspend fun insert(entry: WeightEntryEntity): Long

    @Update
    suspend fun update(entry: WeightEntryEntity): Int

    @Query("UPDATE weight_entries SET deleted = 1, dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE id = :id AND deleted = 0")
    suspend fun markDeleted(id: Long, at: Long, by: String): Int

    /** Placeholders are never synchronised, so they are really removed. */
    @Query("DELETE FROM weight_entries WHERE userId = :userId AND isPlaceholder = 1")
    suspend fun deletePlaceholders(userId: Long): Int

    /** The profile was deleted: its weights go with it, without being uploaded one by one. */
    @Query("UPDATE weight_entries SET deleted = 1, dirty = 0, updatedAtMillis = :at, updatedBy = :by WHERE userId = :userId AND deleted = 0")
    suspend fun markDeletedForUser(userId: Long, at: Long, by: String): Int

    // ---- synchronisation ----

    @Query("SELECT * FROM weight_entries WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): WeightEntryEntity?

    /** Every entry of the user, tombstones included, without placeholders: the content of its weight log file. */
    @Query("SELECT * FROM weight_entries WHERE userId = :userId AND isPlaceholder = 0 ORDER BY epochDay, createdAtMillis, uuid")
    suspend fun allForSync(userId: Long): List<WeightEntryEntity>

    @Query("SELECT COUNT(*) FROM weight_entries WHERE userId = :userId AND deleted = 0 AND isPlaceholder = 0")
    suspend fun countLiveReal(userId: Long): Int

    @Query(
        "SELECT w.* FROM weight_entries w INNER JOIN users u ON u.id = w.userId " +
            "WHERE w.dirty = 1 AND w.isPlaceholder = 0 AND u.isGeneric = 0 AND u.deleted = 0 ORDER BY w.id",
    )
    suspend fun pending(): List<WeightEntryEntity>

    @Query(
        "SELECT COUNT(*) FROM weight_entries w INNER JOIN users u ON u.id = w.userId " +
            "WHERE w.dirty = 1 AND w.isPlaceholder = 0 AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE weight_entries SET dirty = 0 WHERE uuid = :uuid AND updatedAtMillis = :at AND updatedBy = :by")
    suspend fun markSynced(uuid: String, at: Long, by: String): Int
}

@Dao
internal interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE userId = :userId AND deleted = 0 ORDER BY startedAtMillis DESC, id DESC")
    fun observeByUser(userId: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE userId IS NULL AND deleted = 0 ORDER BY startedAtMillis DESC, id DESC")
    fun observeUnassigned(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE endReason IS NULL AND deleted = 0 ORDER BY startedAtMillis DESC, id DESC LIMIT 1")
    fun observeInProgress(): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workout_samples WHERE workoutId = :workoutId ORDER BY seq")
    suspend fun samples(workoutId: Long): List<WorkoutSampleEntity>

    /** The workout in which a fitness test was run (the earliest, if several claim it; ties by uuid, the same on every device). */
    @Query("SELECT * FROM workouts WHERE fitnessTestId = :testId AND deleted = 0 ORDER BY startedAtMillis, uuid LIMIT 1")
    suspend fun linkedToTest(testId: String): WorkoutEntity?

    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    @Update
    suspend fun update(workout: WorkoutEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<WorkoutSampleEntity>)

    /** Running totals of a workout in progress (not a change that needs synchronising: it is not uploaded until it ends). */
    @Query(
        "UPDATE workouts SET durationSeconds = :durationSeconds, distanceMeters = :distanceMeters, calories = :calories, " +
            "averageSpeedKmh = :averageSpeedKmh, maxSpeedKmh = :maxSpeedKmh, averageHeartRateBpm = :averageHeartRateBpm, " +
            "maxHeartRateBpm = :maxHeartRateBpm, maxInclinePercent = :maxInclinePercent, peakHeartRateBpm = :peakHeartRateBpm, " +
            "peakHeartRateSource = :peakHeartRateSource, peakHeartRateAtMillis = :peakHeartRateAtMillis WHERE id = :id AND deleted = 0",
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
        peakHeartRateBpm: Int?,
        peakHeartRateSource: String?,
        peakHeartRateAtMillis: Long?,
    ): Int

    @Query(
        "UPDATE workouts SET endedAtMillis = :endedAtMillis, endReason = :reason, dirty = 1, " +
            "updatedAtMillis = :at, updatedBy = :by WHERE id = :id",
    )
    suspend fun markEnded(id: Long, endedAtMillis: Long, reason: String, at: Long, by: String): Int

    @Query("UPDATE workouts SET userId = :userId, dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE id = :id AND deleted = 0")
    suspend fun assign(id: Long, userId: Long, at: Long, by: String): Int

    /** Tombstones a workout; its samples are dropped (the tombstone keeps the identity and the summary). */
    @Query(
        "UPDATE workouts SET deleted = 1, dirty = 1, planJson = NULL, sessionRecordJson = NULL, updatedAtMillis = :at, updatedBy = :by " +
            "WHERE id = :id AND deleted = 0",
    )
    suspend fun markDeleted(id: Long, at: Long, by: String): Int

    @Query("DELETE FROM workout_samples WHERE workoutId = :workoutId")
    suspend fun deleteSamples(workoutId: Long): Int

    /** The profile was deleted: its workouts go with it, without being uploaded one by one. */
    @Query(
        "UPDATE workouts SET deleted = 1, dirty = 0, planJson = NULL, sessionRecordJson = NULL, updatedAtMillis = :at, updatedBy = :by " +
            "WHERE userId = :userId AND deleted = 0",
    )
    suspend fun markDeletedForUser(userId: Long, at: Long, by: String): Int

    @Query("DELETE FROM workout_samples WHERE workoutId IN (SELECT id FROM workouts WHERE userId = :userId)")
    suspend fun deleteSamplesForUser(userId: Long): Int

    /** A workout that was never uploaded: one that never produced useful data (belt never moved) or a discarded one. */
    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun hardDelete(id: Long): Int

    @Query(
        "UPDATE workouts SET endReason = :reason, dirty = 1, updatedAtMillis = :at, updatedBy = :by, endedAtMillis = COALESCE(" +
            "(SELECT MAX(timestampMillis) FROM workout_samples WHERE workoutId = workouts.id), startedAtMillis) " +
            "WHERE endReason IS NULL AND deleted = 0",
    )
    suspend fun closeInterrupted(reason: String, at: Long, by: String): Int

    // ---- synchronisation ----

    @Query("SELECT * FROM workouts WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): WorkoutEntity?

    /**
     * Finished workouts of a synchronisable owner that changed since the last synchronisation. A workout in progress
     * or without an owner is never eligible; neither are the workouts of the generic profile.
     */
    @Query(
        "SELECT w.* FROM workouts w INNER JOIN users u ON u.id = w.userId " +
            "WHERE w.dirty = 1 AND w.endReason IS NOT NULL AND u.isGeneric = 0 AND u.deleted = 0 ORDER BY w.id",
    )
    suspend fun pending(): List<WorkoutEntity>

    @Query(
        "SELECT COUNT(*) FROM workouts w INNER JOIN users u ON u.id = w.userId " +
            "WHERE w.dirty = 1 AND w.endReason IS NOT NULL AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingCount(): Flow<Int>

    @Query(
        "SELECT w.id FROM workouts w INNER JOIN users u ON u.id = w.userId " +
            "WHERE w.dirty = 1 AND w.endReason IS NOT NULL AND w.deleted = 0 AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingIds(): Flow<List<Long>>

    /** Ids of every row [pending] returns, tombstones included. */
    @Query(
        "SELECT w.id FROM workouts w INNER JOIN users u ON u.id = w.userId " +
            "WHERE w.dirty = 1 AND w.endReason IS NOT NULL AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingRowIds(): Flow<List<Long>>

    @Query("UPDATE workouts SET dirty = 0 WHERE uuid = :uuid AND updatedAtMillis = :at AND updatedBy = :by")
    suspend fun markSynced(uuid: String, at: Long, by: String): Int

    @Query("SELECT COUNT(*) FROM workouts WHERE userId = :userId AND deleted = 0")
    suspend fun countLive(userId: Long): Int

    /** Attaches the executed plan and/or fitness test. A change of the record: it is uploaded (once the workout has ended). */
    @Query(
        "UPDATE workouts SET planJson = :planJson, programId = :programId, programName = :programName, fitnessTestId = :fitnessTestId, " +
            "generatorId = :generatorId, dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE id = :id AND deleted = 0",
    )
    suspend fun setLink(
        id: Long,
        planJson: String?,
        programId: String?,
        programName: String?,
        fitnessTestId: String?,
        generatorId: String?,
        at: Long,
        by: String,
    ): Int

    /** Attaches the record of a generated session. A change of the record: it is uploaded (once the workout has ended). */
    @Query(
        "UPDATE workouts SET sessionRecordJson = :json, generatorId = :generatorId, dirty = 1, updatedAtMillis = :at, updatedBy = :by " +
            "WHERE id = :id AND deleted = 0",
    )
    suspend fun setSessionRecord(id: Long, json: String, generatorId: String?, at: Long, by: String): Int

    @Query(
        "SELECT * FROM workouts WHERE userId = :userId AND generatorId = :generatorId AND sessionRecordJson IS NOT NULL AND deleted = 0 " +
            "ORDER BY startedAtMillis DESC, id DESC",
    )
    fun observeSessionRecords(userId: Long, generatorId: String): Flow<List<WorkoutEntity>>

    /** The finished workout with the highest heart rate peak reached since [since]. */
    @Query(
        "SELECT * FROM workouts WHERE userId = :userId AND deleted = 0 AND endReason IS NOT NULL AND peakHeartRateBpm IS NOT NULL " +
            "AND peakHeartRateAtMillis >= :since ORDER BY peakHeartRateBpm DESC, peakHeartRateAtMillis DESC, id DESC LIMIT 1",
    )
    fun observeHighestPeak(userId: Long, since: Long): Flow<WorkoutEntity?>
}

@Dao
internal interface ProgramDao {
    @Query("SELECT * FROM programs WHERE userId = :userId AND deleted = 0 ORDER BY nameKey, uuid")
    fun observeByUser(userId: Long): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM programs WHERE uuid = :uuid AND deleted = 0")
    fun observe(uuid: String): Flow<ProgramEntity?>

    @Query("SELECT * FROM programs WHERE uuid = :uuid AND deleted = 0")
    suspend fun get(uuid: String): ProgramEntity?

    @Insert
    suspend fun insert(program: ProgramEntity): Long

    @Update
    suspend fun update(program: ProgramEntity): Int

    /** Tombstone: the document is dropped, the identity and version stay. */
    @Query("UPDATE programs SET deleted = 1, json = '', dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE uuid = :uuid AND deleted = 0")
    suspend fun markDeleted(uuid: String, at: Long, by: String): Int

    /** The profile was deleted: its programs go with it, without being uploaded one by one. */
    @Query("UPDATE programs SET deleted = 1, json = '', dirty = 0, updatedAtMillis = :at, updatedBy = :by WHERE userId = :userId AND deleted = 0")
    suspend fun markDeletedForUser(userId: Long, at: Long, by: String): Int

    // ---- synchronisation ----

    @Query("SELECT * FROM programs WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ProgramEntity?

    @Query(
        "SELECT p.* FROM programs p INNER JOIN users u ON u.id = p.userId " +
            "WHERE p.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0 ORDER BY p.id",
    )
    suspend fun pending(): List<ProgramEntity>

    @Query(
        "SELECT COUNT(*) FROM programs p INNER JOIN users u ON u.id = p.userId " +
            "WHERE p.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE programs SET dirty = 0 WHERE uuid = :uuid AND updatedAtMillis = :at AND updatedBy = :by")
    suspend fun markSynced(uuid: String, at: Long, by: String): Int
}

@Dao
internal interface FitnessTestDao {
    @Query("SELECT * FROM fitness_tests WHERE userId = :userId AND deleted = 0 ORDER BY startedAtMillis DESC, uuid DESC")
    fun observeByUser(userId: Long): Flow<List<FitnessTestEntity>>

    @Query("SELECT * FROM fitness_tests WHERE userId = :userId AND deleted = 0 ORDER BY startedAtMillis DESC, uuid DESC")
    suspend fun liveForUser(userId: Long): List<FitnessTestEntity>

    @Query("SELECT * FROM fitness_tests WHERE uuid = :uuid AND deleted = 0")
    suspend fun get(uuid: String): FitnessTestEntity?

    /** The live, valid results of the given test types (the ones whose derived values can be recalculated), of every user. */
    @Query("SELECT * FROM fitness_tests WHERE deleted = 0 AND isValid = 1 AND testType IN (:testTypes) ORDER BY startedAtMillis, uuid")
    suspend fun liveValidOfTypes(testTypes: List<String>): List<FitnessTestEntity>

    @Insert
    suspend fun insert(test: FitnessTestEntity): Long

    @Update
    suspend fun update(test: FitnessTestEntity): Int

    @Query("UPDATE fitness_tests SET deleted = 1, json = '', dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE uuid = :uuid AND deleted = 0")
    suspend fun markDeleted(uuid: String, at: Long, by: String): Int

    /** The profile was deleted: its results go with it, without being uploaded one by one. */
    @Query("UPDATE fitness_tests SET deleted = 1, json = '', dirty = 0, updatedAtMillis = :at, updatedBy = :by WHERE userId = :userId AND deleted = 0")
    suspend fun markDeletedForUser(userId: Long, at: Long, by: String): Int

    // ---- synchronisation ----

    @Query("SELECT * FROM fitness_tests WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): FitnessTestEntity?

    @Query(
        "SELECT t.* FROM fitness_tests t INNER JOIN users u ON u.id = t.userId " +
            "WHERE t.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0 ORDER BY t.id",
    )
    suspend fun pending(): List<FitnessTestEntity>

    @Query(
        "SELECT COUNT(*) FROM fitness_tests t INNER JOIN users u ON u.id = t.userId " +
            "WHERE t.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingCount(): Flow<Int>

    @Query(
        "SELECT t.uuid FROM fitness_tests t INNER JOIN users u ON u.id = t.userId " +
            "WHERE t.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingUuids(): Flow<List<String>>

    /** A result discarded before it was ever uploaded: it leaves no tombstone. */
    @Query("DELETE FROM fitness_tests WHERE uuid = :uuid")
    suspend fun hardDelete(uuid: String): Int

    @Query("UPDATE fitness_tests SET dirty = 0 WHERE uuid = :uuid AND updatedAtMillis = :at AND updatedBy = :by")
    suspend fun markSynced(uuid: String, at: Long, by: String): Int
}

@Dao
internal interface StartParametersDao {
    @Query("SELECT * FROM start_parameters WHERE userId = :userId AND programId = :programId")
    fun observe(userId: Long, programId: String): Flow<StartParametersEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(parameters: StartParametersEntity)

    @Query("DELETE FROM start_parameters WHERE userId = :userId AND programId = :programId")
    suspend fun delete(userId: Long, programId: String): Int

    /** A user program was deleted. */
    @Query("DELETE FROM start_parameters WHERE programId = :programId")
    suspend fun deleteForProgram(programId: String): Int

    /** A profile was deleted (its rows are tombstones, so the foreign key does not remove these). */
    @Query("DELETE FROM start_parameters WHERE userId = :userId")
    suspend fun deleteForUser(userId: Long): Int
}

@Dao
internal interface RestingHeartRateDao {
    @Query("SELECT * FROM resting_heart_rates WHERE userId = :userId AND deleted = 0 ORDER BY measuredAtMillis, uuid")
    fun observeByUser(userId: Long): Flow<List<RestingHeartRateEntity>>

    @Query("SELECT * FROM resting_heart_rates WHERE userId = :userId AND deleted = 0 ORDER BY measuredAtMillis, uuid")
    suspend fun liveForUser(userId: Long): List<RestingHeartRateEntity>

    /** The live measurements of every user, to derive the resting heart rate of the profiles. */
    @Query("SELECT * FROM resting_heart_rates WHERE deleted = 0")
    fun observeAllLive(): Flow<List<RestingHeartRateEntity>>

    @Query("SELECT * FROM resting_heart_rates WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): RestingHeartRateEntity?

    @Insert
    suspend fun insert(row: RestingHeartRateEntity): Long

    @Update
    suspend fun update(row: RestingHeartRateEntity): Int

    @Query("UPDATE resting_heart_rates SET deleted = 1, dirty = 1, updatedAtMillis = :at, updatedBy = :by WHERE id = :id AND deleted = 0")
    suspend fun markDeleted(id: Long, at: Long, by: String): Int

    /** The profile was deleted: its measurements go with it, without being uploaded one by one. */
    @Query("UPDATE resting_heart_rates SET deleted = 1, dirty = 0, updatedAtMillis = :at, updatedBy = :by WHERE userId = :userId AND deleted = 0")
    suspend fun markDeletedForUser(userId: Long, at: Long, by: String): Int

    // ---- synchronisation ----

    @Query("SELECT * FROM resting_heart_rates WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): RestingHeartRateEntity?

    /** Every measurement of the user, tombstones included: the content of its resting heart rate file. */
    @Query("SELECT * FROM resting_heart_rates WHERE userId = :userId ORDER BY measuredAtMillis, uuid")
    suspend fun allForSync(userId: Long): List<RestingHeartRateEntity>

    @Query(
        "SELECT r.* FROM resting_heart_rates r INNER JOIN users u ON u.id = r.userId " +
            "WHERE r.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0 ORDER BY r.id",
    )
    suspend fun pending(): List<RestingHeartRateEntity>

    @Query(
        "SELECT COUNT(*) FROM resting_heart_rates r INNER JOIN users u ON u.id = r.userId " +
            "WHERE r.dirty = 1 AND u.isGeneric = 0 AND u.deleted = 0",
    )
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE resting_heart_rates SET dirty = 0 WHERE uuid = :uuid AND updatedAtMillis = :at AND updatedBy = :by")
    suspend fun markSynced(uuid: String, at: Long, by: String): Int
}
