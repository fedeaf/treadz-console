package com.treadz.console.userdata.room

import androidx.room.withTransaction
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.Validation
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.sync.ApplyResult
import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncStore
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncWeightEntry
import com.treadz.console.userdata.sync.SyncWorkout
import com.treadz.console.userdata.sync.SyncWorkoutData
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.UploadHold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/** [SyncStore] over the Room database. See the interface for the rules. */
internal class RoomSyncStore(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val zone: ZoneId,
    private val stamper: Stamper,
    private val hold: UploadHold = UploadHold(),
) : SyncStore {
    private val users = db.users()
    private val weights = db.weights()
    private val workouts = db.workouts()
    private val programs = db.programs()
    private val fitnessTests = db.fitnessTests()
    private val startParameters = db.startParameters()

    override val deviceId: String get() = stamper.deviceId

    /** Held workouts (see [UploadHold]) are not pending. */
    override val pendingWorkoutIds: Flow<Set<Long>> =
        combine(workouts.observePendingIds(), hold.workoutIds) { ids, held -> ids.toSet() - held }

    override val pendingCount: Flow<Int> = combine(
        users.observePendingCount(),
        weights.observePendingCount(),
        workouts.observePendingRowIds(),
        hold.workoutIds,
    ) { a, b, ids, held -> a + b + ids.count { it !in held } }

    override suspend fun profile(uuid: String): SyncProfile? = db.withTransaction { users.getByUuid(uuid)?.toSync() }

    override suspend fun pendingChanges(): PendingChanges = db.withTransaction {
        val pendingUsers = users.pending()
        val pendingWeights = weights.pending()
        val pendingWorkouts = workouts.pending().filter { !hold.isWorkoutHeld(it.id) }
        val uuidOfUser = HashMap<Long, UserEntity>()
        suspend fun owner(id: Long): UserEntity = uuidOfUser.getOrPut(id) { users.getAny(id)!! }
        PendingChanges(
            profiles = pendingUsers.map { it.toSync() },
            weights = pendingWeights.map { it.toSync(owner(it.userId).uuid) },
            workouts = pendingWorkouts.map { w -> val o = owner(w.userId!!); w.toSync(o.uuid, o.name) },
        )
    }

    override suspend fun weightEntries(userUuid: String): List<SyncWeightEntry> = db.withTransaction {
        val user = users.getByUuid(userUuid) ?: return@withTransaction emptyList()
        weights.allForSync(user.id).map { it.toSync(userUuid) }
    }

    override suspend fun workoutSamples(workoutUuid: String): List<WorkoutSample> = db.withTransaction {
        val workout = workouts.getByUuid(workoutUuid) ?: return@withTransaction emptyList()
        if (workout.deleted) emptyList() else workouts.samples(workout.id).map { it.toSample() }
    }

    override suspend fun markSynced(synced: SyncedRecords) {
        db.withTransaction {
            synced.profiles.forEach { users.markSynced(it.uuid, it.version.updatedAtMillis, it.version.deviceId) }
            synced.weights.forEach { weights.markSynced(it.uuid, it.version.updatedAtMillis, it.version.deviceId) }
            synced.workouts.forEach { workouts.markSynced(it.uuid, it.version.updatedAtMillis, it.version.deviceId) }
        }
    }

    override suspend fun applyRemote(changes: RemoteChanges, dropUnusedGeneric: Boolean): ApplyResult = db.withTransaction {
        var applied = 0
        var kept = 0
        var skipped = 0
        var newProfiles = 0
        fun record(outcome: Outcome) {
            when (outcome) {
                Outcome.Applied -> applied++
                Outcome.KeptLocal -> kept++
                Outcome.Skipped -> skipped++
            }
        }
        // Profiles first, then what belongs to them.
        for (profile in changes.profiles) {
            val isNew = !profile.deleted && users.getByUuid(profile.uuid) == null
            val outcome = applyProfile(profile)
            if (isNew && outcome == Outcome.Applied) newProfiles++
            record(outcome)
        }
        changes.weights.forEach { record(applyWeight(it)) }
        changes.workouts.forEach { record(applyWorkout(it)) }
        if (users.count() == 0) {
            insertGenericProfile(users, weights, clock, stamper, today(), makeActive = true)
        } else if (dropUnusedGeneric && newProfiles > 0) {
            dropUnusedGenericProfile()
        }
        ApplyResult(applied, kept, skipped)
    }

    private enum class Outcome { Applied, KeptLocal, Skipped }

    private fun today(): LocalDate = java.time.Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    private suspend fun applyProfile(remote: SyncProfile): Outcome {
        val local = users.getByUuid(remote.uuid)
        if (local == null) {
            if (remote.deleted) return Outcome.Skipped
            users.insert(remote.toEntity(id = 0))
            return Outcome.Applied
        }
        if (local.deleted) return Outcome.KeptLocal // a deleted profile stays deleted
        if (remote.deleted) {
            // The deletion always wins; the local record is gone together with its data.
            softDeleteUserAs(local, remote.version)
            return Outcome.Applied
        }
        val localVersion = RecordVersion(local.updatedAtMillis, local.updatedBy)
        return when {
            remote.version > localVersion -> {
                users.update(remote.toEntity(id = local.id).copy(isGeneric = false))
                Outcome.Applied
            }
            remote.version == localVersion -> {
                if (local.dirty) users.markSynced(local.uuid, local.updatedAtMillis, local.updatedBy)
                Outcome.KeptLocal
            }
            else -> Outcome.KeptLocal
        }
    }

    /** Applies a remote deletion: tombstone with the remote version, children included, and not pending. */
    private suspend fun softDeleteUserAs(local: UserEntity, version: RecordVersion) {
        val at = maxOf(version.updatedAtMillis, local.updatedAtMillis)
        users.markDeleted(local.id, at, version.deviceId)
        users.markSynced(local.uuid, at, version.deviceId)
        weights.markDeletedForUser(local.id, at, version.deviceId)
        workouts.deleteSamplesForUser(local.id)
        workouts.markDeletedForUser(local.id, at, version.deviceId)
        programs.markDeletedForUser(local.id, at, version.deviceId)
        fitnessTests.markDeletedForUser(local.id, at, version.deviceId)
        startParameters.deleteForUser(local.id)
        users.clearActiveIf(local.id)
    }

    private suspend fun applyWeight(remote: SyncWeightEntry): Outcome {
        val owner = users.getByUuid(remote.userUuid)
        if (owner == null || owner.deleted) return Outcome.Skipped
        val local = weights.getByUuid(remote.uuid)
        if (local == null) {
            if (remote.deleted) return Outcome.Skipped
            weights.deletePlaceholders(owner.id)
            weights.insert(remote.toEntity(id = 0, userId = owner.id))
            return Outcome.Applied
        }
        val localVersion = RecordVersion(local.updatedAtMillis, local.updatedBy)
        return when {
            remote.version > localVersion -> {
                weights.update(remote.toEntity(id = local.id, userId = owner.id))
                Outcome.Applied
            }
            remote.version == localVersion -> {
                if (local.dirty) weights.markSynced(local.uuid, local.updatedAtMillis, local.updatedBy)
                Outcome.KeptLocal
            }
            else -> Outcome.KeptLocal
        }
    }

    private suspend fun applyWorkout(remote: SyncWorkoutData): Outcome {
        val w = remote.workout
        val owner = users.getByUuid(w.userUuid)
        if (owner == null || owner.deleted) return Outcome.Skipped
        val local = workouts.getByUuid(w.uuid)
        if (local == null) {
            if (w.deleted) return Outcome.Skipped
            val id = workouts.insert(w.toEntity(id = 0, userId = owner.id))
            workouts.insertSamples(remote.samples.map { it.toEntity(id) })
            return Outcome.Applied
        }
        val localVersion = RecordVersion(local.updatedAtMillis, local.updatedBy)
        return when {
            w.version > localVersion -> {
                workouts.update(w.toEntity(id = local.id, userId = owner.id))
                workouts.deleteSamples(local.id)
                if (!w.deleted) workouts.insertSamples(remote.samples.map { it.toEntity(local.id) })
                Outcome.Applied
            }
            w.version == localVersion -> {
                if (local.dirty) workouts.markSynced(local.uuid, local.updatedAtMillis, local.updatedBy)
                Outcome.KeptLocal
            }
            else -> Outcome.KeptLocal
        }
    }

    private suspend fun dropUnusedGenericProfile() {
        val live = users.allLive()
        if (live.none { !it.isGeneric }) return
        for (generic in live.filter { it.isGeneric }) {
            val unused = workouts.countLive(generic.id) == 0 && weights.countLiveReal(generic.id) == 0
            if (unused) softDeleteUser(db, generic, stamper)
        }
    }

    // ---- mapping ----

    private fun UserEntity.version() = RecordVersion(updatedAtMillis, updatedBy)

    private suspend fun UserEntity.toSync(): SyncProfile {
        val profile = toProfile()
        // A held result (see [UploadHold]) does not reach the uploaded fitness block either.
        val results = fitnessTests.liveForUser(id).filter { !hold.isTestHeld(it.uuid) }.mapNotNull { readFitnessResult(it.json) }
        return SyncProfile(
            uuid = uuid,
            version = version(),
            deleted = deleted,
            name = name,
            sex = profile.sex,
            heightCm = heightCm,
            birthDate = profile.birthDate,
            restingHeartRateBpm = restingHeartRateBpm,
            maxHeartRateBpm = maxHeartRateBpm,
            fitnessLevel = profile.fitnessLevel,
            goal = profile.goal,
            targetWeightKg = targetWeightKg,
            createdAtMillis = createdAtMillis,
            maxHeartRateSetAtMillis = maxHeartRateSetAtMillis,
            fitness = if (deleted) null else FitnessProfiles.snapshot(results, profile.sex, profile.birthDate),
        )
    }

    private fun SyncProfile.toEntity(id: Long) = UserEntity(
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
        isGeneric = false,
        uuid = uuid,
        updatedAtMillis = version.updatedAtMillis,
        updatedBy = version.deviceId,
        deleted = deleted,
        dirty = false,
        maxHeartRateSetAtMillis = maxHeartRateSetAtMillis,
    )

    private fun WeightEntryEntity.toSync(userUuid: String) = SyncWeightEntry(
        uuid = uuid,
        userUuid = userUuid,
        version = RecordVersion(updatedAtMillis, updatedBy),
        deleted = deleted,
        date = LocalDate.ofEpochDay(epochDay),
        weightKg = weightKg,
        createdAtMillis = createdAtMillis,
    )

    private fun SyncWeightEntry.toEntity(id: Long, userId: Long) = WeightEntryEntity(
        id = id,
        userId = userId,
        epochDay = date.toEpochDay(),
        weightKg = weightKg,
        isPlaceholder = false,
        uuid = uuid,
        updatedAtMillis = version.updatedAtMillis,
        updatedBy = version.deviceId,
        deleted = deleted,
        dirty = false,
        createdAtMillis = createdAtMillis,
    )

    private fun WorkoutEntity.toSync(userUuid: String, ownerName: String) = SyncWorkout(
        uuid = uuid,
        userUuid = userUuid,
        ownerName = ownerName,
        version = RecordVersion(updatedAtMillis, updatedBy),
        deleted = deleted,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis ?: startedAtMillis,
        endReason = toSummary().endReason ?: com.treadz.console.userdata.WorkoutEndReason.Interrupted,
        stats = toSummary().stats,
        planJson = planJson,
        fitnessTestId = fitnessTestId,
    )

    private fun SyncWorkout.toEntity(id: Long, userId: Long) = WorkoutEntity(
        id = id,
        userId = userId,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        endReason = endReason.name,
        durationSeconds = stats.durationSeconds,
        distanceMeters = stats.distanceMeters,
        calories = stats.calories,
        averageSpeedKmh = stats.averageSpeedKmh,
        maxSpeedKmh = stats.maxSpeedKmh,
        averageHeartRateBpm = stats.averageHeartRateBpm,
        maxHeartRateBpm = stats.maxHeartRateBpm,
        maxInclinePercent = stats.maxInclinePercent,
        uuid = uuid,
        updatedAtMillis = version.updatedAtMillis,
        updatedBy = version.deviceId,
        deleted = deleted,
        dirty = false,
        planJson = planJson,
        programId = readPlan(planJson)?.source?.programId,
        programName = readPlan(planJson)?.source?.programName,
        fitnessTestId = fitnessTestId,
    )
}
