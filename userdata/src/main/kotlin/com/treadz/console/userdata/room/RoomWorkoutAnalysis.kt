package com.treadz.console.userdata.room

import androidx.room.withTransaction
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.userdata.AnalysisCandidate
import com.treadz.console.userdata.AnalysisRecord
import com.treadz.console.userdata.AnalysisSource
import com.treadz.console.userdata.AnalysisUserInputs
import com.treadz.console.userdata.DatedWorkoutAnalysis
import com.treadz.console.userdata.PerformanceInputs
import com.treadz.console.userdata.PerformanceInputsMapper
import com.treadz.console.userdata.RestingPoint
import com.treadz.console.userdata.SampleSignature
import com.treadz.console.userdata.StoredWorkoutAnalysis
import com.treadz.console.userdata.WorkoutAnalysisJson
import com.treadz.console.userdata.WorkoutAnalysisRepository
import com.treadz.console.userdata.WorkoutSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/** The stored analysis of an entity, or `null` if its document cannot be read (it is recalculated by the next run). */
private fun readAnalysis(json: String): WorkoutAnalysis? = try {
    WorkoutAnalysisJson.decode(json)
} catch (e: Exception) {
    null
}

internal fun AnalysisRecord.toEntity(): WorkoutAnalysisEntity = WorkoutAnalysisEntity(
    workoutId = workoutId,
    algorithmVersion = analysis.algorithmVersion,
    fingerprint = fingerprint,
    computedAtMillis = computedAtMillis,
    json = WorkoutAnalysisJson.encode(analysis),
    trimp = analysis.trimp.value?.trimp,
    fitnessIndex = analysis.fitnessIndex.value?.vo2maxEstimate,
    recoveryDrop60Bpm = analysis.recovery.value?.drop60Bpm,
    recoveryDrop120Bpm = analysis.recovery.value?.drop120Bpm,
    recoveryContext = analysis.recovery.value?.context?.name,
)

internal class RoomWorkoutAnalysisRepository(
    private val db: UserDataDatabase,
    private val zone: ZoneId,
) : WorkoutAnalysisRepository {
    private val dao = db.workoutAnalysis()

    override fun analysis(workoutId: Long): Flow<StoredWorkoutAnalysis?> =
        dao.observe(workoutId).distinctUntilChanged().map { row ->
            row?.let { r -> readAnalysis(r.json)?.let { StoredWorkoutAnalysis(r.workoutId, it, r.computedAtMillis) } }
        }.distinctUntilChanged()

    override fun analyses(userId: Long): Flow<List<DatedWorkoutAnalysis>> =
        dao.observeByUser(userId).distinctUntilChanged().map { rows ->
            rows.mapNotNull { r ->
                readAnalysis(r.json)?.let {
                    DatedWorkoutAnalysis(r.workoutId, r.startedAtMillis, PerformanceInputsMapper.epochDay(r.startedAtMillis, zone), it)
                }
            }
        }

    override fun performanceInputs(userId: Long): Flow<PerformanceInputs> = combine(
        db.fitnessTests().observeByUser(userId),
        db.restingHeartRates().observeByUser(userId),
        db.weights().observeEntries(userId),
    ) { tests, resting, weights ->
        PerformanceInputsMapper.of(
            tests.mapNotNull { readFitnessResult(it.json) },
            resting.map { it.toMeasurement() },
            weights.map { it.toEntry() },
            zone,
        )
    }.distinctUntilChanged()
}

/** The Room rows the recalculation of the workout analyses reads and writes (see `WorkoutAnalysisRecalculator`). */
internal class RoomAnalysisSource(private val db: UserDataDatabase) : AnalysisSource {

    override suspend fun pruneOrphans() {
        db.workoutAnalysis().deleteOrphans()
    }

    override suspend fun candidates(): List<AnalysisCandidate> = db.workoutAnalysis().candidates().map {
        AnalysisCandidate(
            it.workoutId, it.userId, it.startedAtMillis,
            SampleSignature(it.sampleCount, it.firstSampleMillis, it.lastSampleMillis),
            it.storedVersion, it.storedFingerprint,
        )
    }

    override suspend fun inputs(userId: Long): AnalysisUserInputs? = db.withTransaction {
        val user = db.users().get(userId) ?: return@withTransaction null
        AnalysisUserInputs(
            // The profile's resting value is derived from the measurements, which are given on their own.
            profile = user.toProfile(restingHeartRateBpm = null),
            weights = db.weights().liveForUser(userId).map { it.toEntry() },
            tests = db.fitnessTests().liveForUser(userId).mapNotNull { readFitnessResult(it.json) },
            resting = db.restingHeartRates().liveForUser(userId).map { RestingPoint(it.measuredAtMillis, it.bpm) },
        )
    }

    override suspend fun samples(workoutId: Long): List<WorkoutSample> = db.workouts().samples(workoutId).map { it.toSample() }

    override suspend fun store(record: AnalysisRecord): Boolean = db.withTransaction {
        // A workout deleted or discarded while it was analysed has no row to belong to.
        val workout = db.workouts().get(record.workoutId)
        if (workout == null || workout.deleted || workout.userId == null) return@withTransaction false
        db.workoutAnalysis().upsert(record.toEntity())
        true
    }
}

/**
 * Emits (at once, and again) whenever the data the analyses depend on changes: the finished workouts, the weights, the
 * valid fitness test results, the resting heart rate measurements and the profiles. A workout in progress does not count
 * (its rows change every few seconds); when it ends, it does.
 */
internal fun analysisChanges(db: UserDataDatabase): Flow<Any?> {
    val dao = db.workoutAnalysis()
    return combine(dao.workoutsStamp(), dao.weightsStamp(), dao.testsStamp(), dao.restingStamp(), dao.usersStamp()) { a, b, c, d, e ->
        listOf(a, b, c, d, e)
    }.distinctUntilChanged()
}
