package com.treadz.console.userdata.room

import androidx.room.withTransaction
import com.treadz.console.userdata.sync.UploadHold
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.FitnessTestRecord
import com.treadz.console.userdata.FitnessTestRepository
import com.treadz.console.userdata.ProgramRepository
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.StartParametersRepository
import com.treadz.console.userdata.UserProgram
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.SyncedRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import com.treadz.console.programs.model.ValidationIssue as ProgramIssue

internal class RoomProgramRepository(
    private val db: UserDataDatabase,
    private val stamper: Stamper,
) : ProgramRepository {
    private val users = db.users()
    private val dao = db.programs()
    private val startParameters = db.startParameters()

    override fun programs(userId: Long): Flow<List<UserProgram>> =
        dao.observeByUser(userId).map { rows -> rows.mapNotNull { it.toUserProgram() } }

    override fun program(programId: String): Flow<UserProgram?> = dao.observe(programId).map { it?.toUserProgram() }

    override suspend fun get(programId: String): UserProgram? = dao.get(programId)?.toUserProgram()

    override suspend fun save(userId: Long, program: Program): ProgramSaveResult {
        val candidate = if (program.id.isBlank()) program.copy(id = stamper.newUuid()) else program
        val issues = ProgramRules.issues(candidate)
        if (issues.isNotEmpty()) return ProgramSaveResult.Invalid(issues)
        val json = ProgramJson.encode(candidate)
        return try {
            db.withTransaction {
                if (users.get(userId) == null) return@withTransaction ProgramSaveResult.NotFound
                val existing = dao.getByUuid(candidate.id)
                if (existing != null && existing.userId != userId) {
                    return@withTransaction ProgramSaveResult.Invalid(listOf(ProgramRules.idTaken))
                }
                val saved = when {
                    existing == null -> {
                        val at = stamper.at()
                        val fresh = entity(userId, candidate, json, createdAt = at, at = at)
                        fresh.copy(id = dao.insert(fresh))
                    }
                    !existing.deleted && existing.json == json -> existing // identical: nothing to store or upload
                    else -> {
                        val changed = entity(userId, candidate, json, createdAt = existing.createdAtMillis, at = stamper.at(existing.updatedAtMillis))
                            .copy(id = existing.id)
                        dao.update(changed)
                        changed
                    }
                }
                ProgramSaveResult.Saved(checkNotNull(saved.toUserProgram()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProgramSaveResult.StorageError(e.message ?: e::class.java.simpleName)
        }
    }

    private fun entity(userId: Long, p: Program, json: String, createdAt: Long, at: Long) = ProgramEntity(
        userId = userId,
        uuid = p.id,
        name = p.name.trim(),
        nameKey = p.name.trim().lowercase(Locale.ROOT),
        kind = p.kind.name,
        json = json,
        createdAtMillis = createdAt,
        updatedAtMillis = at,
        updatedBy = stamper.deviceId,
        deleted = false,
        dirty = true,
    )

    override suspend fun delete(programId: String): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(programId) ?: return@withTransaction DataResult.NotFound
            dao.markDeleted(programId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            startParameters.deleteForProgram(programId)
            DataResult.Success(Unit)
        }
    }
}

/** What a stored program must satisfy besides the model's own validation. Shared by every implementation. */
internal object ProgramRules {
    val idTaken = ProgramIssue("id", "this id already belongs to a program of another user")
    private val notStorable = ProgramIssue("kind", "standard programs are built into the app and are not stored")

    fun issues(program: Program): List<ProgramIssue> =
        (if (program.kind == ProgramKind.Standard) listOf(notStorable) else emptyList()) + ProgramValidator.validate(program)
}

internal class RoomStartParametersRepository(
    private val db: UserDataDatabase,
    private val stamper: Stamper,
) : StartParametersRepository {
    private val users = db.users()
    private val dao = db.startParameters()

    override fun lastUsed(userId: Long, programId: String): Flow<StartParameters?> =
        dao.observe(userId, programId).map { it?.toParameters() }

    override suspend fun save(userId: Long, programId: String, parameters: StartParameters): DataResult<Unit> = guarded {
        db.withTransaction {
            if (users.get(userId) == null) return@withTransaction DataResult.NotFound
            dao.upsert(
                StartParametersEntity(
                    userId = userId,
                    programId = programId,
                    totalDurationSeconds = parameters.totalDurationSeconds,
                    easySpeedKmh = parameters.easySpeedKmh,
                    maxSpeedKmh = parameters.maxSpeedKmh,
                    maxInclinePercent = parameters.maxInclinePercent,
                    intensityAdjustmentPercent = parameters.intensityAdjustmentPercent,
                    updatedAtMillis = stamper.at(),
                ),
            )
            DataResult.Success(Unit)
        }
    }

    override suspend fun clear(userId: Long, programId: String): DataResult<Unit> = guarded {
        dao.delete(userId, programId)
        DataResult.Success(Unit)
    }
}

internal class RoomFitnessTestRepository(
    private val db: UserDataDatabase,
    private val clock: Clock,
    private val zone: ZoneId,
    private val stamper: Stamper,
    private val hold: UploadHold = UploadHold(),
) : FitnessTestRepository {
    private val users = db.users()
    private val dao = db.fitnessTests()

    override fun results(userId: Long): Flow<List<FitnessTestRecord>> =
        dao.observeByUser(userId).map { rows -> rows.mapNotNull { it.toRecord() } }

    override suspend fun get(testId: String): FitnessTestRecord? = dao.get(testId)?.toRecord()

    override suspend fun save(userId: Long, result: FitnessTestResult): DataResult<FitnessTestRecord> {
        require(FitnessRules.isValidId(result.id)) { "Invalid fitness test id '${result.id}'" }
        val json = com.treadz.console.programs.fitness.FitnessTestJson.encode(result)
        return guarded {
            db.withTransaction {
                if (users.get(userId) == null) return@withTransaction DataResult.NotFound
                val existing = dao.getByUuid(result.id)
                if (existing != null && existing.userId != userId) {
                    return@withTransaction DataResult.StorageError("the fitness test id belongs to another user")
                }
                val saved = when {
                    existing == null -> {
                        val fresh = fitnessEntity(userId, result, json, stamper.at(), stamper.deviceId)
                        fresh.copy(id = dao.insert(fresh))
                    }
                    !existing.deleted && existing.json == json -> existing
                    else -> {
                        val changed = fitnessEntity(userId, result, json, stamper.at(existing.updatedAtMillis), stamper.deviceId).copy(id = existing.id)
                        dao.update(changed)
                        changed
                    }
                }
                DataResult.Success(checkNotNull(saved.toRecord()))
            }
        }
    }

    override suspend fun delete(testId: String): DataResult<Unit> = guarded {
        db.withTransaction {
            val current = dao.get(testId) ?: return@withTransaction DataResult.NotFound
            dao.markDeleted(testId, stamper.at(current.updatedAtMillis), stamper.deviceId)
            DataResult.Success(Unit)
        }
    }

    override fun holdUpload(testId: String) = hold.holdTest(testId)

    override fun releaseUpload(testId: String) = hold.releaseTest(testId)

    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    private fun results(rows: List<FitnessTestEntity>) = rows.mapNotNull { readFitnessResult(it.json) }

    override fun fitnessProfile(userId: Long): Flow<FitnessProfile?> =
        combine(users.observe(userId), dao.observeByUser(userId)) { user, rows ->
            user?.let { u -> u.toProfile().let { p -> FitnessProfiles.snapshot(results(rows), p.sex, p.birthDate) } }
        }.distinctUntilChanged()

    override fun liveFitnessProfile(userId: Long): Flow<FitnessProfile?> =
        combine(users.observe(userId), dao.observeByUser(userId)) { user, rows ->
            user?.let { u -> u.toProfile().let { p -> FitnessProfiles.live(results(rows), p.sex, p.birthDate, clock.nowMillis(), zone) } }
        }.distinctUntilChanged()

    override fun heartRateMax(userId: Long): Flow<HeartRateMaxDecision?> =
        combine(users.observe(userId), dao.observeByUser(userId)) { user, rows ->
            user?.let { u ->
                val p = u.toProfile()
                val live = FitnessProfiles.live(results(rows), p.sex, p.birthDate, clock.nowMillis(), zone)
                FitnessProfiles.heartRateMax(p, live, today())
            }
        }.distinctUntilChanged()

    override suspend fun useMeasuredHeartRateMax(userId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            val user = users.get(userId) ?: return@withTransaction DataResult.NotFound
            val p = user.toProfile()
            val live = FitnessProfiles.live(results(dao.liveForUser(userId)), p.sex, p.birthDate, clock.nowMillis(), zone)
            if (live.hrMax == null) return@withTransaction DataResult.NotFound // nothing measured to adopt
            if (user.maxHeartRateBpm != null) {
                users.update(
                    user.copy(
                        maxHeartRateBpm = null,
                        maxHeartRateSetAtMillis = null,
                        updatedAtMillis = stamper.at(user.updatedAtMillis),
                        updatedBy = stamper.deviceId,
                        dirty = true,
                    ),
                )
            }
            DataResult.Success(Unit)
        }
    }

    override suspend fun keepManualHeartRateMax(userId: Long): DataResult<Unit> = guarded {
        db.withTransaction {
            val user = users.get(userId) ?: return@withTransaction DataResult.NotFound
            if (user.maxHeartRateBpm == null) return@withTransaction DataResult.NotFound // no manual value to keep
            val p = user.toProfile()
            val live = FitnessProfiles.live(results(dao.liveForUser(userId)), p.sex, p.birthDate, clock.nowMillis(), zone)
            // The answer is newer than the test it answers, whatever this device's clock says.
            val confirmedAt = maxOf(clock.nowMillis(), (live.hrMax?.testedAtMillis ?: Long.MIN_VALUE) + 1)
            users.update(
                user.copy(
                    maxHeartRateSetAtMillis = confirmedAt,
                    updatedAtMillis = stamper.at(user.updatedAtMillis),
                    updatedBy = stamper.deviceId,
                    dirty = true,
                ),
            )
            DataResult.Success(Unit)
        }
    }
}

internal object FitnessRules {
    private val ID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun isValidId(id: String) = ID.matches(id)
}

private fun fitnessEntity(userId: Long, r: FitnessTestResult, json: String, at: Long, by: String) = FitnessTestEntity(
    userId = userId,
    uuid = r.id,
    testType = r.testType.name,
    startedAtMillis = r.startedAtMillis,
    isValid = r.validity == Validity.Valid,
    vo2maxMlKgMin = r.outputs?.vo2maxMlKgMin,
    masKmh = r.outputs?.masKmh,
    json = json,
    updatedAtMillis = at,
    updatedBy = by,
    deleted = false,
    dirty = true,
)

// ---------------------------------------------------------------- synchronisation

internal class RoomProgramDocuments(private val db: UserDataDatabase) : ProgramDocumentStore {
    private val users = db.users()
    private val dao = db.programs()
    private val startParameters = db.startParameters()

    override val pendingCount: Flow<Int> = dao.observePendingCount()

    override suspend fun pending(): List<ProgramDocument> = db.withTransaction {
        dao.pending().map { e ->
            ProgramDocument(e.uuid, users.getAny(e.userId)!!.uuid, RecordVersion(e.updatedAtMillis, e.updatedBy), e.deleted, e.json)
        }
    }

    override suspend fun applyRemote(documents: List<ProgramDocument>): Int = db.withTransaction {
        var applied = 0
        for (d in documents) {
            val owner = users.getByUuid(d.userUuid)?.takeIf { !it.deleted } ?: continue
            val local = dao.getByUuid(d.uuid)
            if (local == null) {
                if (d.deleted) continue
                dao.insert(remoteEntity(d, owner.id, id = 0, createdAt = d.version.updatedAtMillis))
                applied++
                continue
            }
            val localVersion = RecordVersion(local.updatedAtMillis, local.updatedBy)
            when {
                d.version > localVersion -> {
                    dao.update(remoteEntity(d, owner.id, id = local.id, createdAt = local.createdAtMillis))
                    if (d.deleted) startParameters.deleteForProgram(d.uuid)
                    applied++
                }
                d.version == localVersion -> if (local.dirty) dao.markSynced(local.uuid, local.updatedAtMillis, local.updatedBy)
            }
        }
        applied
    }

    override suspend fun markSynced(synced: List<SyncedRef>) {
        db.withTransaction { synced.forEach { dao.markSynced(it.uuid, it.version.updatedAtMillis, it.version.deviceId) } }
    }

    private fun remoteEntity(d: ProgramDocument, userId: Long, id: Long, createdAt: Long): ProgramEntity {
        val program = if (d.deleted) null else readProgram(d.json)
        val name = program?.name?.trim().orEmpty()
        return ProgramEntity(
            id = id,
            userId = userId,
            uuid = d.uuid,
            name = name,
            nameKey = name.lowercase(Locale.ROOT),
            kind = (program?.kind ?: ProgramKind.Free).name,
            json = if (d.deleted) "" else d.json,
            createdAtMillis = createdAt,
            updatedAtMillis = d.version.updatedAtMillis,
            updatedBy = d.version.deviceId,
            deleted = d.deleted,
            dirty = false,
        )
    }
}

internal class RoomFitnessTestDocuments(
    private val db: UserDataDatabase,
    private val hold: UploadHold = UploadHold(),
) : FitnessTestDocumentStore {
    private val users = db.users()
    private val dao = db.fitnessTests()

    /** Held results (see [UploadHold]) are not pending. */
    override val pendingCount: Flow<Int> =
        combine(dao.observePendingUuids(), hold.testIds) { uuids, held -> uuids.count { it !in held } }

    override suspend fun pending(): List<FitnessTestDocument> = db.withTransaction {
        dao.pending().filter { !hold.isTestHeld(it.uuid) }.map { e ->
            FitnessTestDocument(e.uuid, users.getAny(e.userId)!!.uuid, RecordVersion(e.updatedAtMillis, e.updatedBy), e.deleted, e.json)
        }
    }

    override suspend fun applyRemote(documents: List<FitnessTestDocument>): Int = db.withTransaction {
        var applied = 0
        for (d in documents) {
            val owner = users.getByUuid(d.userUuid)?.takeIf { !it.deleted } ?: continue
            val local = dao.getByUuid(d.uuid)
            if (local == null) {
                if (d.deleted) continue
                dao.insert(remoteEntity(d, owner.id, id = 0))
                applied++
                continue
            }
            val localVersion = RecordVersion(local.updatedAtMillis, local.updatedBy)
            when {
                d.version > localVersion -> {
                    dao.update(remoteEntity(d, owner.id, id = local.id))
                    applied++
                }
                d.version == localVersion -> if (local.dirty) dao.markSynced(local.uuid, local.updatedAtMillis, local.updatedBy)
            }
        }
        applied
    }

    override suspend fun markSynced(synced: List<SyncedRef>) {
        db.withTransaction { synced.forEach { dao.markSynced(it.uuid, it.version.updatedAtMillis, it.version.deviceId) } }
    }

    private fun remoteEntity(d: FitnessTestDocument, userId: Long, id: Long): FitnessTestEntity {
        val result = if (d.deleted) null else readFitnessResult(d.json)
        return FitnessTestEntity(
            id = id,
            userId = userId,
            uuid = d.uuid,
            testType = result?.testType?.name ?: "Unknown",
            startedAtMillis = result?.startedAtMillis ?: d.version.updatedAtMillis,
            isValid = result?.validity == Validity.Valid,
            vo2maxMlKgMin = result?.outputs?.vo2maxMlKgMin,
            masKmh = result?.outputs?.masKmh,
            json = if (d.deleted) "" else d.json,
            updatedAtMillis = d.version.updatedAtMillis,
            updatedBy = d.version.deviceId,
            deleted = d.deleted,
            dirty = false,
        )
    }
}
