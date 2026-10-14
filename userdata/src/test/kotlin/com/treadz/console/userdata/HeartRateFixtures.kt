package com.treadz.console.userdata

import com.treadz.console.programs.engine.HeartRateBlockSummary
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessTestInputs
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxHeartRateWalkMeasurements
import com.treadz.console.programs.fitness.MinuteRecord
import com.treadz.console.programs.fitness.StageRecord
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.programs.personalised.SessionBlockRecord
import com.treadz.console.programs.personalised.SessionOutcome
import com.treadz.console.programs.fitness.Sex as FitnessSex

const val GENERATOR_4X4 = "norwegian-4x4"
const val GENERATOR_C25K = "couch-to-5k"

fun origin(generatorId: String = GENERATOR_4X4, session: Int? = null) =
    ProgramOrigin(generatorId, 2, linkedMapOf("durationMinutes" to "40", "mode" to "walk") + (session?.let { mapOf("session" to it.toString()) } ?: emptyMap()))

/** A personalised program with its origin (as the generators store it). */
fun personalised(id: String = PROGRAM_2, generatorId: String = GENERATOR_4X4, name: String = "Norwegian 4x4 (generated)") =
    norwegian(id, name, ProgramKind.Personalised).copy(origin = origin(generatorId))

/** The record of one generated session, with a heart-rate block summary and a block without one. */
fun sessionRecord(
    generatorId: String = GENERATOR_4X4,
    startedAt: Long = T0,
    outcome: SessionOutcome = SessionOutcome.Completed,
    schemaVersion: Int = GeneratedSessionRecord.SCHEMA_VERSION,
    session: Int? = null,
) = GeneratedSessionRecord(
    origin = origin(generatorId, session),
    programId = PROGRAM_2,
    startedAtMillis = startedAt,
    endedAtMillis = startedAt + 2_400_000,
    outcome = outcome,
    programSeconds = 2_400,
    maxHeartRateBpm = 181,
    manualSpeedReductions = 1,
    manualChanges = 2,
    workBlocksPlanned = 4,
    workBlocksCompleted = 4,
    blocks = listOf(
        SessionBlockRecord(
            0, "0", BlockPhase.WarmUp, null, true, 600, 600, 6.0, 2.0, 6.0, 2.0, 0, 0, 118.5, null,
        ),
        SessionBlockRecord(
            1, "1.0", BlockPhase.Work, 1, true, 240, 240, 10.0, 6.0, 9.4, 6.0, 1, 2, 160.0,
            HeartRateBlockSummary(
                blockIndex = 1, adjust = HeartRateAdjust.Speed, mode = HeartRateControlMode.BetweenRepetitions,
                zoneLowBpm = 154, zoneHighBpm = 171, startValue = 10.0, initialValue = 10.0, endValue = 9.4, settledValue = 9.4,
                secondsInZone = 150, meanHeartRateBpm = 158.2, endHeartRateBpm = 160.0, adjustments = 2, safetyReductions = 0,
                manualOverride = true, secondsWithoutHeartRate = 0, source = HeartRateSourceKind.DirectStrap,
            ),
        ),
    ),
    schemaVersion = schemaVersion,
)

/** A hand-made, complete result of the maximal heart-rate walking test (modified Balke) for a 40-year-old man. */
fun balkeResult(
    id: String = TEST_3,
    startedAt: Long = T0,
    peak: Int? = 186,
    validity: Validity = Validity.Valid,
    source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap,
): FitnessTestResult {
    val inputs = FitnessTestInputs(40, FitnessSex.Male, 80.5, 180, 180, source, 5.5, 5.5, maxSpeedLimitKmh = 16.0)
    return FitnessTestResult(
        id = id, testType = FitnessTestType.MaxHeartRateWalk, protocolVersion = 1,
        startedAtMillis = startedAt, effortEndedAtMillis = startedAt + 720_000, endedAtMillis = startedAt + 900_000,
        validity = validity, flags = emptyList(), endReason = FitnessEndReason.Exhausted, endDetail = "exhausted", inputs = inputs,
        measurements = MaxHeartRateWalkMeasurements(
            warmUpSeconds = 120, rampSeconds = 600, startSpeedKmh = 5.5, startInclinePercent = 5.0, inclineIncrementPercent = 1.0,
            inclineCapPercent = 18.0, speedIncrementKmh = 0.2, speedCapKmh = 16.0, stageSeconds = 60, capReached = false,
            stages = listOf(StageRecord(1, 5.5, 5.0, 60, true, 118.0), StageRecord(2, 5.5, 6.0, 60, true, 126.0)),
            peakHeartRateBpm = peak?.toDouble(), heartRateCoverage = 0.98, heartRatePlateau = true, plateauRiseBpm = 1.5,
            hrMaxCandidateBpm = peak, effort = EffortAssessment.Maximal,
        ),
        minutes = listOf(
            MinuteRecord(FitnessTestPhase.WarmUp, 1, 60, 60, 4.1, 0.0, 100.0),
            MinuteRecord(FitnessTestPhase.Incremental, 1, 180, 60, 5.5, 5.0, 118.0),
            MinuteRecord(FitnessTestPhase.CoolDown, 3, 900, 60, 4.0, 0.0, null),
        ),
        coolDownSeconds = 180,
        outputs = null,
    )
}

/** Samples of a workout with a strap: [seconds] one-second samples at [bpm], with RR intervals `[1000 * 60 / bpm]` each. */
fun strapSamples(startedAt: Long = T0, seconds: Int = 6, bpm: Int = 150): List<WorkoutSample> = (0 until seconds).map {
    WorkoutSample(
        seq = it, timestampMillis = startedAt + it * 1000L, elapsedSeconds = it + 1, speedKmh = 5.5, targetSpeedKmh = 5.5,
        inclinePercent = 5.0, distanceMeters = it * 2, calories = it / 4, heartRateBpm = bpm,
        beltStatus = com.treadz.console.treadmill.BeltStatus.Running, heartRateSource = HeartRateSourceKind.DirectStrap,
        rrIntervalsMs = listOf(60_000 / bpm, 60_000 / bpm + 3),
    )
}
