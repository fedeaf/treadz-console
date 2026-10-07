package com.treadz.console.userdata.room

import androidx.room.withTransaction
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.GenericProfile
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.Validation
import com.treadz.console.userdata.ValidationCode
import com.treadz.console.userdata.ValidationIssue
import com.treadz.console.userdata.WeightEntry
import com.treadz.console.userdata.WeightRepository
import com.treadz.console.userdata.WorkoutDetail
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutHistoryRepository
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class RoomProfileRepository(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val zone: ZoneId,
    seedGenericProfile: Boolean,
) : ProfileRepository {
    private val dao = db.users()
    private val weightDao = db.weights()

    // A fresh database gets the generic profile before anything reads or writes. After that the "at least one
    // profile" invariant (see delete) means the database is never empty again.
    private val seedLock = Mutex()
    @Volatile private var seeded = !seedGenericProfile

    private suspend fun ensureSeeded() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            db.withTransaction { if (dao.count() == 0) insertGeneric(makeActive = true) }
            seeded = true
        }
    }

    override val profiles: Flow<List<UserProfile>> = flow {
        ensureSeeded()
        emitAll(dao.observeAll().map { list -> list.map { it.toProfile() } })
    }
    override val activeUser: Flow<UserProfile?> = flow {
        ensureSeeded()
        emitAll(dao.observeActive().map { it?.toProfile() })
    }

    override fun profile(id: Long): Flow<UserProfile?> = flow {
        ensureSeeded()
        emitAll(dao.observe(id).map { it?.toProfile() })
    }

    override suspend fun activeUserId(): Long? {
        ensureSeeded()
        return dao.activeUserId()
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    /** Must run inside a transaction. */
    private suspend fun insertGeneric(makeActive: Boolean): Long {
        val today = today()
        val input = GenericProfile.input(today)
        val id = dao.insert(input.toEntity(id = 0, targetWeightKg = null, createdAtMillis = clock.nowMillis(), isGeneric = true))
        weightDao.insert(
            WeightEntryEntity(
                userId = id, epochDay = today.toEpochDay(), weightKg = GenericProfile.WEIGHT_KG, isPlaceholder = true,
            ),
        )
        if (makeActive) dao.setActive(ActiveUserEntity(userId = id))
        return id
    }

    override suspend fun create(input: ProfileInput): DataResult<UserProfile> {
        ensureSeeded()
        val issues = Validation.profile(input, today())
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        return guarded {
            db.withTransaction {
                if (dao.countWithName(Validation.nameKey(input.name), exceptId = 0) > 0) {
                    return@withTransaction DataResult.Invalid(listOf(nameTaken))
                }
                val first = dao.count() == 0 // only possible with seeding disabled (tests)
                val id = dao.insert(input.toEntity(id = 0, targetWeightKg = null, createdAtMillis = clock.nowMillis()))
                if (first) dao.setActive(ActiveUserEntity(userId = id))
                DataResult.Success(dao.get(id)!!.toProfile())
            }
        }
    }

    override suspend fun update(id: Long, input: ProfileInput): DataResult<UserProfile> {
        ensureSeeded()
        return guarded { db.withTransaction { save(id, input) } }
    }

    override suspend fun personalize(id: Long, input: ProfileInput, weightKg: Double?): DataResult<UserProfile> {
        ensureSeeded()
        val today = today()
        val issues = Validation.profile(input, today) +
            (if (weightKg != null) Validation.weightEntry(today, weightKg, today) else emptyList())
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        return guarded {
            db.withTransaction {
                val result = save(id, input)
                if (result is DataResult.Success && weightKg != null) {
                    weightDao.deletePlaceholders(id)
                    weightDao.insert(WeightEntryEntity(userId = id, epochDay = today.toEpochDay(), weightKg = weightKg, isPlaceholder = false))
                }
                result
            }
        }
    }

    /** Validates and saves the edit of a profile. Must run inside a transaction. */
    private suspend fun save(id: Long, input: ProfileInput): DataResult<UserProfile> {
        val current = dao.get(id) ?: return DataResult.NotFound
        val stillGeneric = current.isGeneric && input.name.isBlank()
        val issues = Validation.profile(input, today(), allowBlankName = stillGeneric)
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        if (!stillGeneric && dao.countWithName(Validation.nameKey(input.name), exceptId = id) > 0) {
            return DataResult.Invalid(listOf(nameTaken))
        }
        dao.update(input.toEntity(id, current.targetWeightKg, current.createdAtMillis, isGeneric = stillGeneric))
        return DataResult.Success(dao.get(id)!!.toProfile())
    }

    override suspend fun delete(id: Long): DataResult<Unit> {
        ensureSeeded()
        return guarded {
            db.withTransaction {
                // Foreign keys cascade to weights and workouts, and clear the active user.
                if (dao.delete(id) == 0) return@withTransaction DataResult.NotFound
                if (dao.count() == 0) insertGeneric(makeActive = true)
                DataResult.Success(Unit)
            }
        }
    }

    override suspend fun setActiveUser(id: Long?): DataResult<Unit> {
        ensureSeeded()
        return guarded {
            db.withTransaction {
                if (id != null && dao.get(id) == null) return@withTransaction DataResult.NotFound
                dao.setActive(ActiveUserEntity(userId = id))
                DataResult.Success(Unit)
            }
        }
    }

    private fun ProfileInput.toEntity(id: Long, targetWeightKg: Double?, createdAtMillis: Long, isGeneric: Boolean = false) = UserEntity(
        id = id,
        name = Validation.normalizedName(name),
        nameKey = Validation.nameKey(name),
        sex = sex.name,
        heightCm = heightCm,
        birthEpochDay = birthDate.toEpochDay(),
        restingHeartRateBpm = restingHeartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm,
        fitnessLevel = fitnessLevel?.name,
        goal = goal?.name,
        targetWeightKg = targetWeightKg,
        createdAtMillis = createdAtMillis,
        isGeneric = isGeneric,
    )

    private companion object {
        val nameTaken = ValidationIssue(InputField.Name, ValidationCode.NameTaken)
    }
}

internal class RoomWeightRepository(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val zone: ZoneId,
) : WeightRepository {
    private val users = db.users()
    private val dao = db.weights()

    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    override fun entries(userId: Long): Flow<List<WeightEntry>> = dao.observeEntries(userId).map { l -> l.map { it.toEntry() } }

    override fun latestWeight(userId: Long): Flow<WeightEntry?> = dao.observeLatest(userId).map { it?.toEntry() }

    override fun targetWeightKg(userId: Long): Flow<Double?> = users.observeTargetWeight(userId)

    override suspend fun add(userId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry> {
        val issues = Validation.weightEntry(date, weightKg, today())
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        return guarded {
            db.withTransaction {
                if (users.get(userId) == null) return@withTransaction DataResult.NotFound
                dao.deletePlaceholders(userId)
                val id = dao.insert(WeightEntryEntity(userId = userId, epochDay = date.toEpochDay(), weightKg = weightKg, isPlaceholder = false))
                DataResult.Success(dao.get(id)!!.toEntry())
            }
        }
    }

    override suspend fun update(entryId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry> {
        val issues = Validation.weightEntry(date, weightKg, today())
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        return guarded {
            db.withTransaction {
                val current = dao.get(entryId) ?: return@withTransaction DataResult.NotFound
                dao.update(current.copy(epochDay = date.toEpochDay(), weightKg = weightKg, isPlaceholder = false))
                DataResult.Success(dao.get(entryId)!!.toEntry())
            }
        }
    }

    override suspend fun delete(entryId: Long): DataResult<Unit> = guarded {
        if (dao.delete(entryId) == 0) DataResult.NotFound else DataResult.Success(Unit)
    }

    override suspend fun setTargetWeight(userId: Long, weightKg: Double?): DataResult<Unit> {
        if (weightKg != null) {
            val issues = Validation.targetWeight(weightKg)
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        }
        return guarded { if (users.setTargetWeight(userId, weightKg) == 0) DataResult.NotFound else DataResult.Success(Unit) }
    }
}

internal class RoomWorkoutRepository(private val db: UserDataDatabase) : WorkoutHistoryRepository, WorkoutSessionStore {
    private val users = db.users()
    private val dao = db.workouts()

    override fun workouts(userId: Long): Flow<List<WorkoutSummary>> = dao.observeByUser(userId).map { l -> l.map { it.toSummary() } }

    override fun unassignedWorkouts(): Flow<List<WorkoutSummary>> = dao.observeUnassigned().map { l -> l.map { it.toSummary() } }

    override fun inProgressWorkout(): Flow<WorkoutSummary?> = dao.observeInProgress().map { it?.toSummary() }

    override suspend fun detail(workoutId: Long): WorkoutDetail? = db.withTransaction {
        val workout = dao.get(workoutId) ?: return@withTransaction null
        WorkoutDetail(workout.toSummary(), dao.samples(workoutId).map { it.toSample() })
    }

    override suspend fun delete(workoutId: Long): DataResult<Unit> = guarded {
        if (dao.delete(workoutId) == 0) DataResult.NotFound else DataResult.Success(Unit)
    }

    override suspend fun assign(workoutId: Long, userId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            if (users.get(userId) == null || dao.get(workoutId) == null) return@withTransaction DataResult.NotFound
            dao.assign(workoutId, userId)
            DataResult.Success(Unit)
        }
    }

    // ---- WorkoutSessionStore ----

    override suspend fun begin(userId: Long?, startedAtMillis: Long): Long = dao.insert(
        WorkoutEntity(
            userId = userId,
            startedAtMillis = startedAtMillis,
            endedAtMillis = null,
            endReason = null,
            durationSeconds = 0,
            distanceMeters = 0,
            calories = 0,
            averageSpeedKmh = 0.0,
            maxSpeedKmh = 0.0,
            averageHeartRateBpm = null,
            maxHeartRateBpm = null,
            maxInclinePercent = 0.0,
        ),
    )

    override suspend fun append(workoutId: Long, samples: List<WorkoutSample>, stats: WorkoutStats): Boolean =
        db.withTransaction { write(workoutId, samples, stats) }

    override suspend fun finish(
        workoutId: Long,
        samples: List<WorkoutSample>,
        stats: WorkoutStats,
        endedAtMillis: Long,
        reason: WorkoutEndReason,
    ): Boolean = db.withTransaction {
        if (!write(workoutId, samples, stats)) return@withTransaction false
        dao.markEnded(workoutId, endedAtMillis, reason.name)
        true
    }

    private suspend fun write(workoutId: Long, samples: List<WorkoutSample>, stats: WorkoutStats): Boolean {
        val updated = dao.updateStats(
            id = workoutId,
            durationSeconds = stats.durationSeconds,
            distanceMeters = stats.distanceMeters,
            calories = stats.calories,
            averageSpeedKmh = stats.averageSpeedKmh,
            maxSpeedKmh = stats.maxSpeedKmh,
            averageHeartRateBpm = stats.averageHeartRateBpm,
            maxHeartRateBpm = stats.maxHeartRateBpm,
            maxInclinePercent = stats.maxInclinePercent,
        )
        if (updated == 0) return false
        if (samples.isNotEmpty()) dao.insertSamples(samples.map { it.toEntity(workoutId) })
        return true
    }

    override suspend fun discard(workoutId: Long) {
        dao.delete(workoutId)
    }

    override suspend fun closeInterrupted(): Int = dao.closeInterrupted(WorkoutEndReason.Interrupted.name)
}
