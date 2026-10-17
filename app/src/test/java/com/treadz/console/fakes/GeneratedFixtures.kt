package com.treadz.console.fakes

import com.treadz.console.programs.engine.HeartRateBlockSummary
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestInputs
import com.treadz.console.programs.fitness.FitnessTestOutputs
import com.treadz.console.programs.fitness.Vo2MaxMethod
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxHeartRateWalkMeasurements
import com.treadz.console.programs.fitness.MinuteRecord
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.fitness.StageRecord
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.GeneratedProgram
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.programs.personalised.ProgramGenerators
import com.treadz.console.programs.personalised.SessionBlockRecord
import com.treadz.console.programs.personalised.SessionOutcome
import com.treadz.console.training.GeneratorContext
import com.treadz.console.training.GeneratorForm
import com.treadz.console.training.GeneratorRequests
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.UUID

/** Generated sessions, their records and walking heart-rate test results, for the tests and the screenshots. */
object GeneratedFixtures {

    const val DAY_MS = 86_400_000L

    /** A record of a Couch to 5K [session] (or of another generator) with the things the progression looks at. */
    fun record(
        generatorId: String = CouchToFiveK.ID,
        session: Int? = null,
        startedAtMillis: Long,
        durationSeconds: Int = 1_800,
        completed: Boolean = true,
        endHeartRate: Double? = 140.0,
        manualReductions: Int = 0,
        programId: String = UUID.randomUUID().toString(),
        maxHeartRateBpm: Int = 180,
        inputs: Map<String, String> = emptyMap(),
        settledValue: Double? = null,
        key: String = "1",
    ): GeneratedSessionRecord {
        val origin = ProgramOrigin(generatorId, 1, buildMap { putAll(inputs); session?.let { put("session", it.toString()) } })
        val summary = settledValue?.let {
            HeartRateBlockSummary(
                blockIndex = 1, adjust = HeartRateAdjust.Speed, mode = HeartRateControlMode.Continuous, zoneLowBpm = 120, zoneHighBpm = 140,
                startValue = 8.0, initialValue = 8.0, endValue = it, settledValue = it, secondsInZone = 900, meanHeartRateBpm = 132.0,
                endHeartRateBpm = endHeartRate, adjustments = 4, safetyReductions = 0, manualOverride = false, secondsWithoutHeartRate = 0,
                source = HeartRateSourceKind.DirectStrap,
            )
        }
        val work = SessionBlockRecord(
            blockIndex = 1, key = key, phase = BlockPhase.Work, repetition = null, completed = completed,
            elapsedSeconds = durationSeconds, plannedSeconds = durationSeconds, plannedSpeedKmh = 8.0, plannedInclinePercent = 1.0,
            finalSpeedKmh = settledValue ?: 8.0, finalInclinePercent = 1.0, manualSpeedReductions = manualReductions,
            manualChanges = manualReductions, endHeartRateBpm = endHeartRate, heartRate = summary,
        )
        return GeneratedSessionRecord(
            origin = origin, programId = programId, startedAtMillis = startedAtMillis, endedAtMillis = startedAtMillis + durationSeconds * 1000L,
            outcome = if (completed) SessionOutcome.Completed else SessionOutcome.Stopped, programSeconds = durationSeconds,
            maxHeartRateBpm = maxHeartRateBpm, manualSpeedReductions = manualReductions, manualChanges = manualReductions,
            workBlocksPlanned = 1, workBlocksCompleted = if (completed) 1 else 0, blocks = listOf(work),
        )
    }

    /** Stores [record] with a finished workout of [userId]; returns the workout id. */
    fun addSession(data: InMemoryUserData, userId: Long, record: GeneratedSessionRecord): Long {
        val id = data.addWorkout(
            userId,
            WorkoutStats(
                durationSeconds = record.programSeconds, distanceMeters = 3_000, calories = 200, averageSpeedKmh = 7.2, maxSpeedKmh = 8.6,
                averageHeartRateBpm = 138, maxHeartRateBpm = 151, maxInclinePercent = 1.0,
            ),
            addSessionSamples(record.startedAtMillis), record.startedAtMillis,
        )
        runBlocking { data.workouts.attachSessionRecord(id, record) }
        return id
    }

    /** A few samples of a workout that started at [startedAtMillis]. */
    fun addSessionSamples(startedAtMillis: Long): List<WorkoutSample> = (0 until 3).map { i ->
        WorkoutSample(
            seq = i, timestampMillis = startedAtMillis + i * 1000L, elapsedSeconds = i, speedKmh = 8.0, targetSpeedKmh = 8.0,
            inclinePercent = 1.0, distanceMeters = i * 2, calories = i, heartRateBpm = 140, beltStatus = BeltStatus.Running,
        )
    }

    /** What the generators know about [user] right now, as the screens read it. */
    fun context(data: InMemoryUserData, user: UserProfile, generatorId: String): GeneratorContext = runBlocking {
        GeneratorContext(
            user = user,
            weight = data.weights.latestWeight(user.id).first(),
            fitnessProfile = data.fitnessTests.liveFitnessProfile(user.id).first(),
            heartRateMax = data.fitnessTests.heartRateMax(user.id).first(),
            restingBpm = data.restingHeartRates.reference(user.id).first()?.bpm,
            history = data.workouts.sessionRecords(user.id, generatorId).first().map { it.record },
        )
    }

    /** Generates a session of [generatorId] for [user] with the data layer's current values. */
    fun generate(
        data: InMemoryUserData,
        user: UserProfile,
        generatorId: String,
        form: GeneratorForm? = null,
        today: LocalDate,
        nowMillis: Long,
        name: String? = null,
    ): GeneratedProgram {
        val descriptor = checkNotNull(ProgramGenerators.byId(generatorId)).descriptor
        val request = GeneratorRequests.requestOf(
            descriptor, context(data, user, generatorId), form ?: GeneratorForm.defaults(descriptor), today, nowMillis,
            name ?: descriptor.id, UUID.randomUUID().toString(),
        )
        return ProgramGenerators.generate(generatorId, request)
    }

    // ---- walking heart-rate test results

    /**
     * A finished walking heart-rate test: valid and maximal by default (peak [peakBpm]), or not maximal (a short ramp), or
     * invalid (a manual change during the ramp).
     */
    fun balke(
        id: String = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b21",
        startedAtMillis: Long,
        peakBpm: Double? = 186.0,
        rampSeconds: Int = 600,
        effort: EffortAssessment? = EffortAssessment.Maximal,
        valid: Boolean = true,
        flags: List<FitnessTestFlag> = emptyList(),
        endReason: FitnessEndReason = FitnessEndReason.Exhausted,
        startSpeedKmh: Double = 5.5,
        startInclinePercent: Double = 5.0,
        ageYears: Int = 39,
        hrMaxBpm: Int = 183,
        /** VO2max of the result (ACSM at the last minute's load, SEE 3.5); `null` leaves the outputs out (a result stored before version 2). */
        vo2max: Double? = if (valid) 44.1 else null,
        vo2maxIsLowerBound: Boolean = false,
        thresholds: ThresholdResult? = null,
    ): FitnessTestResult {
        val stages = (0 until rampSeconds / 60).map { i ->
            StageRecord(i + 1, startSpeedKmh, (startInclinePercent + i).coerceAtMost(18.0), 60, true, 120.0 + i * 6.0)
        }
        val minutes = stages.mapIndexed { i, s ->
            MinuteRecord(FitnessTestPhase.Incremental, i + 1, 120 + (i + 1) * 60, 60, s.speedKmh, s.inclinePercent, s.heartRateEndBpm)
        }
        val measurements = MaxHeartRateWalkMeasurements(
            warmUpSeconds = 120, rampSeconds = rampSeconds, startSpeedKmh = startSpeedKmh, startInclinePercent = startInclinePercent,
            inclineIncrementPercent = 1.0, inclineCapPercent = 18.0, speedIncrementKmh = 0.2, speedCapKmh = 20.0, stageSeconds = 60,
            capReached = false, stages = stages, peakHeartRateBpm = peakBpm, heartRateCoverage = 0.97, heartRatePlateau = false,
            plateauRiseBpm = null, hrMaxCandidateBpm = if (valid) peakBpm?.toInt() else null, effort = effort,
        )
        return FitnessTestResult(
            id = id, testType = FitnessTestType.MaxHeartRateWalk, protocolVersion = 1, startedAtMillis = startedAtMillis,
            effortEndedAtMillis = startedAtMillis + (120 + rampSeconds) * 1000L, endedAtMillis = startedAtMillis + (300 + rampSeconds) * 1000L,
            validity = if (valid) Validity.Valid else Validity.Invalid, flags = flags, endReason = endReason,
            inputs = FitnessTestInputs(
                ageYears = ageYears, sex = Sex.Female, weightKg = 66.0, hrMaxBpm = hrMaxBpm, agePredictedHrMaxBpm = 220 - ageYears,
                heartRateSource = HeartRateSourceKind.DirectStrap, requestedStartSpeedKmh = startSpeedKmh, startSpeedKmh = startSpeedKmh,
            ),
            measurements = measurements, minutes = minutes, coolDownSeconds = 180,
            outputs = vo2max?.let {
                FitnessTestOutputs(
                    vo2maxMlKgMin = it, vo2maxSeeMlKgMin = 3.5, method = Vo2MaxMethod.AcsmWalkRamp, vo2maxIsLowerBound = vo2maxIsLowerBound,
                    classification = Vo2MaxNorms.classify(it, ageYears, Sex.Female), masKmh = Math.round(it / 3.5 * 100) / 100.0,
                    hrMaxCandidateBpm = if (valid) peakBpm?.toInt() else null, effort = effort, masIsEstimated = true,
                )
            },
            thresholds = thresholds,
        )
    }
}
