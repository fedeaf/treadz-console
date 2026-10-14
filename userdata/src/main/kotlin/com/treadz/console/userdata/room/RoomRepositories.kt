package com.treadz.console.userdata.room

import androidx.room.withTransaction
import com.treadz.console.userdata.sync.UploadHold
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.GenericProfile
import com.treadz.console.userdata.HeartRatePeakRecord
import com.treadz.console.userdata.RestingHeartRateAssessment
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateMeasurement
import com.treadz.console.userdata.RestingHeartRateReference
import com.treadz.console.userdata.RestingHeartRateRepository
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.RestingHeartRates
import com.treadz.console.userdata.RestingPoint
import com.treadz.console.userdata.StoredSessionRecord
import com.treadz.console.userdata.StrapRules
import com.treadz.console.userdata.StrapSaveResult
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
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Produces the version stamp of a change: the time (never behind the record's previous stamp, so a local edit made
 * after applying a change from a device with a faster clock still wins) and the id of this device.
 */
internal class Stamper(private val clock: Clock, val deviceId: String, private val uuidSource: () -> String) {
    fun at(previousMillis: Long = 0): Long = maxOf(clock.nowMillis(), previousMillis + 1)

    fun nowMillis(): Long = clock.nowMillis()

    private var lastCreationMillis = Long.MIN_VALUE

    /**
     * The creation time of a new weight entry: never equal to the one handed out before by this device, so that the
     * entries it creates have a total order (the order of creation) whatever the clock resolution.
     */
    @Synchronized
    fun creationTime(): Long {
        lastCreationMillis = maxOf(clock.nowMillis(), lastCreationMillis + 1)
        return lastCreationMillis
    }

    fun newUuid(): String = uuidSource()
}

internal class RoomProfileRepository(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val zone: ZoneId,
    seedGenericProfile: Boolean,
    private val stamper: Stamper,
) : ProfileRepository {
    private val dao = db.users()
    private val weightDao = db.weights()
    private val restingDao = db.restingHeartRates()

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

    // The resting heart rate of a profile is the reference of the user's history, evaluated when the data changes.
    private fun referenceBpm(rows: List<RestingHeartRateEntity>?): Int? =
        if (rows.isNullOrEmpty()) null else RestingHeartRates.reference(rows.map { RestingPoint(it.measuredAtMillis, it.bpm) }, clock.nowMillis())?.bpm

    override val profiles: Flow<List<UserProfile>> = flow {
        ensureSeeded()
        emitAll(
            combine(dao.observeAll(), restingDao.observeAllLive()) { users, rows ->
                val byUser = rows.groupBy { it.userId }
                users.map { it.toProfile(referenceBpm(byUser[it.id])) }
            },
        )
    }
    override val activeUser: Flow<UserProfile?> = flow {
        ensureSeeded()
        emitAll(
            combine(dao.observeActive(), restingDao.observeAllLive()) { user, rows ->
                user?.toProfile(referenceBpm(rows.filter { it.userId == user.id }))
            },
        )
    }

    override fun profile(id: Long): Flow<UserProfile?> = flow {
        ensureSeeded()
        emitAll(combine(dao.observe(id), restingDao.observeByUser(id)) { user, rows -> user?.toProfile(referenceBpm(rows)) })
    }

    /** The profile as it is now, with its resting heart rate. Must run inside a transaction. */
    private suspend fun profileOf(id: Long): UserProfile = dao.get(id)!!.toProfile(referenceBpm(restingDao.liveForUser(id)))

    /** Stores the resting heart rate typed in a profile form as a manual measurement when it differs from the reference. */
    private suspend fun recordTypedResting(userId: Long, bpm: Int?) {
        if (bpm == null) return
        if (referenceBpm(restingDao.liveForUser(userId)) == bpm) return
        restingDao.insert(
            newMeasurement(
                stamper, userId,
                RestingHeartRateInput(clock.nowMillis(), bpm, RestingHeartRateSource.Manual),
            ),
        )
    }

    override suspend fun activeUserId(): Long? {
        ensureSeeded()
        return dao.activeUserId()
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    /** Must run inside a transaction. */
    private suspend fun insertGeneric(makeActive: Boolean): Long = insertGenericProfile(dao, weightDao, clock, stamper, today(), makeActive)

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
                val id = dao.insert(
                    input.toEntity(
                        id = 0, targetWeightKg = null, createdAtMillis = clock.nowMillis(),
                        maxHeartRateSetAtMillis = if (input.maxHeartRateBpm != null) clock.nowMillis() else null,
                    ).fresh(),
                )
                if (first) dao.setActive(ActiveUserEntity(userId = id))
                recordTypedResting(id, input.restingHeartRateBpm)
                DataResult.Success(profileOf(id))
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
                    weightDao.insert(newWeight(stamper, id, today.toEpochDay(), weightKg, isPlaceholder = false))
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
        // The time of the manual maximum heart rate: now when it is set or changed, cleared with the value, else kept.
        val setAt = when {
            input.maxHeartRateBpm == null -> null
            input.maxHeartRateBpm != current.maxHeartRateBpm -> clock.nowMillis()
            else -> current.maxHeartRateSetAtMillis
        }
        // The legacy resting heart rate column is left as it is (it is no longer read); the strap is not part of the form.
        val edited = input.toEntity(id, current.targetWeightKg, current.createdAtMillis, isGeneric = stillGeneric, maxHeartRateSetAtMillis = setAt)
            .copy(
                uuid = current.uuid, updatedAtMillis = current.updatedAtMillis, updatedBy = current.updatedBy, dirty = current.dirty,
                restingHeartRateBpm = current.restingHeartRateBpm, strapAddress = current.strapAddress, strapName = current.strapName,
            )
        // An edit that changes nothing is not a change: it must not be uploaded.
        if (edited != current) {
            dao.update(edited.copy(updatedAtMillis = stamper.at(current.updatedAtMillis), updatedBy = stamper.deviceId, dirty = true))
        }
        recordTypedResting(id, input.restingHeartRateBpm)
        return DataResult.Success(profileOf(id))
    }

    override suspend fun delete(id: Long): DataResult<Unit> {
        ensureSeeded()
        return guarded {
            db.withTransaction {
                val current = dao.get(id) ?: return@withTransaction DataResult.NotFound
                softDeleteUser(db, current, stamper)
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

    override suspend fun setStrap(id: Long, address: String, name: String?): StrapSaveResult {
        ensureSeeded()
        val normalised = StrapRules.normalizeAddress(address) ?: return StrapSaveResult.InvalidAddress
        val strapName = StrapRules.normalizeName(name)
        if (strapName != null && strapName.length > StrapRules.NAME_MAX_LENGTH) return StrapSaveResult.NameTooLong
        return try {
            db.withTransaction {
                val current = dao.get(id) ?: return@withTransaction StrapSaveResult.NotFound
                if (current.strapAddress != normalised || current.strapName != strapName) {
                    dao.update(
                        current.copy(
                            strapAddress = normalised, strapName = strapName,
                            updatedAtMillis = stamper.at(current.updatedAtMillis), updatedBy = stamper.deviceId, dirty = true,
                        ),
                    )
                }
                StrapSaveResult.Saved(profileOf(id))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            StrapSaveResult.StorageError(e.message ?: e::class.java.simpleName)
        }
    }

    override suspend fun clearStrap(id: Long): DataResult<Unit> {
        ensureSeeded()
        return guarded {
            db.withTransaction {
                val current = dao.get(id) ?: return@withTransaction DataResult.NotFound
                if (current.strapAddress != null || current.strapName != null) {
                    dao.update(
                        current.copy(
                            strapAddress = null, strapName = null,
                            updatedAtMillis = stamper.at(current.updatedAtMillis), updatedBy = stamper.deviceId, dirty = true,
                        ),
                    )
                }
                DataResult.Success(Unit)
            }
        }
    }

    /** Identity and version of a record that is created now. */
    private fun UserEntity.fresh() = copy(uuid = stamper.newUuid(), updatedAtMillis = stamper.at(), updatedBy = stamper.deviceId, dirty = true)

    private fun ProfileInput.toEntity(
        id: Long,
        targetWeightKg: Double?,
        createdAtMillis: Long,
        isGeneric: Boolean = false,
        maxHeartRateSetAtMillis: Long? = null,
    ) = UserEntity(
        id = id,
        name = Validation.normalizedName(name),
        nameKey = Validation.nameKey(name),
        sex = sex.name,
        heightCm = heightCm,
        birthEpochDay = birthDate.toEpochDay(),
        restingHeartRateBpm = null, // legacy column, see UserEntity
        maxHeartRateBpm = maxHeartRateBpm,
        fitnessLevel = fitnessLevel?.name,
        goal = goal?.name,
        targetWeightKg = targetWeightKg,
        createdAtMillis = createdAtMillis,
        isGeneric = isGeneric,
        uuid = "",
        updatedAtMillis = 0,
        updatedBy = "",
        deleted = false,
        dirty = false,
        maxHeartRateSetAtMillis = maxHeartRateSetAtMillis,
    )

    private companion object {
        val nameTaken = ValidationIssue(InputField.Name, ValidationCode.NameTaken)
    }
}

internal class RoomWeightRepository(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val zone: ZoneId,
    private val stamper: Stamper,
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
                val id = dao.insert(newWeight(stamper, userId, date.toEpochDay(), weightKg, isPlaceholder = false))
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
                val edited = current.copy(epochDay = date.toEpochDay(), weightKg = weightKg, isPlaceholder = false)
                if (edited != current) {
                    dao.update(edited.copy(updatedAtMillis = stamper.at(current.updatedAtMillis), updatedBy = stamper.deviceId, dirty = true))
                }
                DataResult.Success(dao.get(entryId)!!.toEntry())
            }
        }
    }

    override suspend fun delete(entryId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(entryId) ?: return@withTransaction DataResult.NotFound
            dao.markDeleted(entryId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            DataResult.Success(Unit)
        }
    }

    override suspend fun setTargetWeight(userId: Long, weightKg: Double?): DataResult<Unit> {
        if (weightKg != null) {
            val issues = Validation.targetWeight(weightKg)
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        }
        return guarded {
            db.withTransaction {
                val current = users.get(userId) ?: return@withTransaction DataResult.NotFound
                if (current.targetWeightKg != weightKg) {
                    users.setTargetWeight(userId, weightKg, stamper.at(current.updatedAtMillis), stamper.deviceId)
                }
                DataResult.Success(Unit)
            }
        }
    }
}

internal class RoomWorkoutRepository(
    private val db: UserDataDatabase,
    private val stamper: Stamper,
    private val hold: UploadHold = UploadHold(),
) : WorkoutHistoryRepository, WorkoutSessionStore {
    private val users = db.users()
    private val dao = db.workouts()
    private val fitnessTests = db.fitnessTests()

    override fun workouts(userId: Long): Flow<List<WorkoutSummary>> = dao.observeByUser(userId).map { l -> l.map { it.toSummary() } }

    override fun unassignedWorkouts(): Flow<List<WorkoutSummary>> = dao.observeUnassigned().map { l -> l.map { it.toSummary() } }

    override fun inProgressWorkout(): Flow<WorkoutSummary?> = dao.observeInProgress().map { it?.toSummary() }

    override suspend fun detail(workoutId: Long): WorkoutDetail? = db.withTransaction {
        val workout = dao.get(workoutId) ?: return@withTransaction null
        WorkoutDetail(
            workout.toSummary(),
            dao.samples(workoutId).map { it.toSample() },
            readPlan(workout.planJson),
            readSessionRecord(workout.sessionRecordJson),
        )
    }

    override suspend fun attachSessionRecord(workoutId: Long, record: GeneratedSessionRecord): DataResult<Unit> = guarded {
        val json = GeneratedSessionJson.encode(record)
        db.withTransaction {
            val current = dao.get(workoutId) ?: return@withTransaction DataResult.NotFound
            val generatorId = current.generatorId ?: record.generatorId
            if (current.sessionRecordJson != json || current.generatorId != generatorId) {
                dao.setSessionRecord(workoutId, json, generatorId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            }
            DataResult.Success(Unit)
        }
    }

    override fun sessionRecords(userId: Long, generatorId: String): Flow<List<StoredSessionRecord>> =
        dao.observeSessionRecords(userId, generatorId).map { rows ->
            rows.mapNotNull { row ->
                readSessionRecord(row.sessionRecordJson)?.takeIf { it.generatorId == generatorId }
                    ?.let { StoredSessionRecord(row.id, row.startedAtMillis, it) }
            }
        }

    override fun highestHeartRatePeak(userId: Long, withinDays: Int): Flow<HeartRatePeakRecord?> = flow {
        val since = stamper.nowMillis() - withinDays * 86_400_000L
        emitAll(
            dao.observeHighestPeak(userId, since).map { row ->
                row?.toSummary()?.stats?.peakHeartRate?.let { HeartRatePeakRecord(row.id, it) }
            },
        )
    }

    override suspend fun link(workoutId: Long, link: WorkoutLink): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(workoutId) ?: return@withTransaction DataResult.NotFound
            applyLink(current, link)
            DataResult.Success(Unit)
        }
    }

    /** Merges [link] into the workout; nothing changes (and nothing is uploaded again) if it adds nothing. */
    private suspend fun applyLink(current: WorkoutEntity, link: WorkoutLink) {
        val planJson = link.plan?.let { PlanJson.encode(it) } ?: current.planJson
        val programId = link.plan?.source?.programId ?: current.programId
        val programName = link.plan?.source?.programName ?: current.programName
        val fitnessTestId = link.fitnessTestId ?: current.fitnessTestId
        val generatorId = link.plan?.source?.origin?.generatorId ?: current.generatorId
        if (planJson == current.planJson && programId == current.programId && programName == current.programName &&
            fitnessTestId == current.fitnessTestId && generatorId == current.generatorId
        ) {
            return
        }
        dao.setLink(current.id, planJson, programId, programName, fitnessTestId, generatorId, stamper.at(current.updatedAtMillis), stamper.deviceId)
    }

    /** Soft delete: the workout becomes a tombstone (so the deletion can be synchronised) and its samples are dropped. */
    override suspend fun delete(workoutId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(workoutId) ?: return@withTransaction DataResult.NotFound
            dao.markDeleted(workoutId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            dao.deleteSamples(workoutId)
            DataResult.Success(Unit)
        }
    }

    override fun holdUpload(workoutId: Long) = hold.holdWorkout(workoutId)

    override fun releaseUpload(workoutId: Long) = hold.releaseWorkout(workoutId)

    override suspend fun discardFinished(workoutId: Long): DataResult<Unit> {
        val result = guarded {
            db.withTransaction {
                val current = dao.get(workoutId) ?: return@withTransaction DataResult.NotFound
                current.fitnessTestId?.let { testId ->
                    val test = fitnessTests.get(testId)
                    when {
                        test == null -> Unit
                        hold.isTestHeld(testId) -> fitnessTests.hardDelete(testId)
                        else -> fitnessTests.markDeleted(testId, stamper.at(test.updatedAtMillis), stamper.deviceId)
                    }
                }
                dao.deleteSamples(workoutId)
                if (hold.isWorkoutHeld(workoutId)) {
                    dao.hardDelete(workoutId)
                } else {
                    dao.markDeleted(workoutId, stamper.at(current.updatedAtMillis), stamper.deviceId)
                }
                DataResult.Success(Unit)
            }
        }
        if (result is DataResult.Success) hold.releaseWorkout(workoutId)
        return result
    }

    override suspend fun assign(workoutId: Long, userId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(workoutId)
            if (users.get(userId) == null || current == null) return@withTransaction DataResult.NotFound
            if (current.userId != userId) dao.assign(workoutId, userId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            DataResult.Success(Unit)
        }
    }

    // ---- WorkoutSessionStore ----

    override suspend fun begin(userId: Long?, startedAtMillis: Long, link: WorkoutLink?): Long = dao.insert(
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
            uuid = stamper.newUuid(),
            updatedAtMillis = stamper.at(),
            updatedBy = stamper.deviceId,
            deleted = false,
            dirty = true,
            planJson = link?.plan?.let { PlanJson.encode(it) },
            programId = link?.plan?.source?.programId,
            programName = link?.plan?.source?.programName,
            fitnessTestId = link?.fitnessTestId,
            generatorId = link?.plan?.source?.origin?.generatorId,
        ),
    )

    override suspend fun attachLink(workoutId: Long, link: WorkoutLink): Boolean = db.withTransaction {
        val current = dao.get(workoutId) ?: return@withTransaction false
        applyLink(current, link)
        true
    }

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
        val previous = dao.get(workoutId)?.updatedAtMillis ?: 0
        dao.markEnded(workoutId, endedAtMillis, reason.name, stamper.at(previous), stamper.deviceId)
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
            peakHeartRateBpm = stats.peakHeartRate?.bpm,
            peakHeartRateSource = stats.peakHeartRate?.source?.name,
            peakHeartRateAtMillis = stats.peakHeartRate?.atMillis,
        )
        if (updated == 0) return false
        if (samples.isNotEmpty()) dao.insertSamples(samples.map { it.toEntity(workoutId) })
        return true
    }

    override suspend fun discard(workoutId: Long) {
        dao.hardDelete(workoutId)
    }

    override suspend fun closeInterrupted(): Int = dao.closeInterrupted(WorkoutEndReason.Interrupted.name, stamper.at(), stamper.deviceId)
}

/** A new weight entry, with its identity and version. */
internal fun newWeight(stamper: Stamper, userId: Long, epochDay: Long, weightKg: Double, isPlaceholder: Boolean): WeightEntryEntity {
    val at = stamper.creationTime()
    return WeightEntryEntity(
        userId = userId,
        epochDay = epochDay,
        weightKg = weightKg,
        isPlaceholder = isPlaceholder,
        uuid = stamper.newUuid(),
        updatedAtMillis = at,
        updatedBy = stamper.deviceId,
        deleted = false,
        dirty = true,
        createdAtMillis = at,
    )
}

/**
 * Inserts the generic profile with its placeholder weight. Must run inside a transaction. It is flagged as changed
 * like any record, but it is not eligible for synchronisation until it is personalised.
 */
internal suspend fun insertGenericProfile(
    users: UserDao,
    weights: WeightDao,
    clock: Clock,
    stamper: Stamper,
    today: LocalDate,
    makeActive: Boolean,
): Long {
    val input = GenericProfile.input(today)
    val id = users.insert(
        UserEntity(
            name = "",
            nameKey = "",
            sex = input.sex.name,
            heightCm = input.heightCm,
            birthEpochDay = input.birthDate.toEpochDay(),
            restingHeartRateBpm = null,
            maxHeartRateBpm = null,
            fitnessLevel = null,
            goal = null,
            targetWeightKg = null,
            createdAtMillis = clock.nowMillis(),
            isGeneric = true,
            uuid = stamper.newUuid(),
            updatedAtMillis = stamper.at(),
            updatedBy = stamper.deviceId,
            deleted = false,
            dirty = true,
        ),
    )
    weights.insert(newWeight(stamper, id, today.toEpochDay(), GenericProfile.WEIGHT_KG, isPlaceholder = true))
    if (makeActive) users.setActive(ActiveUserEntity(userId = id))
    return id
}

/**
 * Deletes a profile as a tombstone, together with its weights, resting heart rate measurements, workouts, programs and fitness test results (also
 * tombstones: the profile's own tombstone covers them, so they are not uploaded one by one) and its start parameters and clears the active user if it was this one.
 * Must run inside a transaction.
 */
internal suspend fun softDeleteUser(db: UserDataDatabase, user: UserEntity, stamper: Stamper) {
    val at = stamper.at(user.updatedAtMillis)
    val by = stamper.deviceId
    db.users().markDeleted(user.id, at, by)
    db.weights().markDeletedForUser(user.id, at, by)
    db.workouts().deleteSamplesForUser(user.id)
    db.workouts().markDeletedForUser(user.id, at, by)
    db.programs().markDeletedForUser(user.id, at, by)
    db.fitnessTests().markDeletedForUser(user.id, at, by)
    db.restingHeartRates().markDeletedForUser(user.id, at, by)
    db.startParameters().deleteForUser(user.id)
    db.users().clearActiveIf(user.id)
}

internal class RoomRestingHeartRateRepository(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val stamper: Stamper,
) : RestingHeartRateRepository {
    private val users = db.users()
    private val dao = db.restingHeartRates()

    override fun measurements(userId: Long): Flow<List<RestingHeartRateMeasurement>> =
        dao.observeByUser(userId).map { rows -> rows.map { it.toMeasurement() } }

    override fun reference(userId: Long): Flow<RestingHeartRateReference?> =
        dao.observeByUser(userId).map { rows -> RestingHeartRates.reference(rows.map { RestingPoint(it.measuredAtMillis, it.bpm) }, clock.nowMillis()) }

    override suspend fun add(userId: Long, input: RestingHeartRateInput): DataResult<RestingHeartRateMeasurement> {
        val issues = RestingHeartRates.validate(input, clock.nowMillis())
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        return guarded {
            db.withTransaction {
                if (users.get(userId) == null) return@withTransaction DataResult.NotFound
                val id = dao.insert(newMeasurement(stamper, userId, input))
                DataResult.Success(dao.get(id)!!.toMeasurement())
            }
        }
    }

    override suspend fun update(measurementId: Long, input: RestingHeartRateInput): DataResult<RestingHeartRateMeasurement> {
        val issues = RestingHeartRates.validate(input, clock.nowMillis())
        if (issues.isNotEmpty()) return DataResult.Invalid(issues)
        return guarded {
            db.withTransaction {
                val current = dao.get(measurementId) ?: return@withTransaction DataResult.NotFound
                val edited = current.copy(
                    measuredAtMillis = input.measuredAtMillis, bpm = input.bpm, source = input.source.name,
                    lowestMeanBpm = input.lowestMeanBpm, coverage = input.coverage, rmssdMs = input.rmssdMs,
                )
                if (edited != current) {
                    dao.update(edited.copy(updatedAtMillis = stamper.at(current.updatedAtMillis), updatedBy = stamper.deviceId, dirty = true))
                }
                DataResult.Success(dao.get(measurementId)!!.toMeasurement())
            }
        }
    }

    override suspend fun delete(measurementId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(measurementId) ?: return@withTransaction DataResult.NotFound
            dao.markDeleted(measurementId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            DataResult.Success(Unit)
        }
    }

    override suspend fun assessment(measurementId: Long): RestingHeartRateAssessment? = db.withTransaction {
        val measurement = dao.get(measurementId)?.toMeasurement() ?: return@withTransaction null
        RestingHeartRates.assess(measurement, dao.liveForUser(measurement.userId).map { it.toMeasurement() })
    }
}

/** A new resting heart rate measurement, with its identity and version. */
internal fun newMeasurement(stamper: Stamper, userId: Long, input: RestingHeartRateInput) = RestingHeartRateEntity(
    userId = userId,
    uuid = stamper.newUuid(),
    measuredAtMillis = input.measuredAtMillis,
    bpm = input.bpm,
    source = input.source.name,
    lowestMeanBpm = input.lowestMeanBpm,
    coverage = input.coverage,
    rmssdMs = input.rmssdMs,
    updatedAtMillis = stamper.at(),
    updatedBy = stamper.deviceId,
    deleted = false,
    dirty = true,
)
