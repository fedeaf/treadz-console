package com.treadz.console.programs.personalised

import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.engine.HeartRateBlockSummary
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.MasEstimate
import com.treadz.console.programs.fitness.Vo2MaxEstimate
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.ProgramOrigin
import java.time.Instant

val NOW: Long = Instant.parse("2026-10-14T18:00:00Z").toEpochMilli()
const val DAY: Long = 86_400_000L
const val HOUR: Long = 3_600_000L
const val PROGRAM_ID = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c09"

/** A fitness profile with measured VO2max 42 and MAS 12 km/h. */
fun measuredFitness(vo2max: Double = 42.0, mas: Double = 12.0) = FitnessProfile(
    computedAtMillis = NOW,
    vo2max = Vo2MaxEstimate(vo2max, 4.0, null, emptyList()),
    mas = MasEstimate(mas, estimated = false, lowerBound = false, sourceResultId = "max-test", testedAtMillis = NOW - 10 * DAY, effort = null),
    hrMax = null,
)

/** Male, 40, 180 cm, 80 kg, intermediate; measured VO2max 42 / MAS 12; measured maximum 180, no resting rate. */
fun measuredProfile(restingBpm: Int? = null) = GeneratorProfile(
    sex = Sex.Male,
    ageYears = 40,
    heightCm = 180.0,
    weightKg = 80.0,
    selfRatedFitness = SelfRatedFitness.Intermediate,
    fitnessProfile = measuredFitness(),
    heartRate = HeartRateProfile(180, restingBpm),
    maxHeartRateOrigin = HeartRateMaxOrigin.Measured,
)

/** The same person without any test: everything estimated (Jackson VO2max 41.2, MAS 11.77, Tanaka maximum 180). */
fun estimatedProfile() = measuredProfile().copy(fitnessProfile = null, maxHeartRateOrigin = HeartRateMaxOrigin.Estimated)

fun request(
    profile: GeneratorProfile = measuredProfile(),
    mode: ExerciseMode = ExerciseMode.Run,
    durationMinutes: Int? = null,
    series: Int? = null,
    startWeek: Int? = null,
    walkingSpeedKmh: Double? = null,
    history: List<GeneratedSessionRecord> = emptyList(),
    nowMillis: Long = NOW,
    jogAdjustment: Int = 0,
    name: String? = null,
) = GeneratorRequest(
    profile = profile,
    nowMillis = nowMillis,
    mode = mode,
    durationMinutes = durationMinutes,
    series = series,
    startWeek = startWeek,
    walkingSpeedKmh = walkingSpeedKmh,
    jogSpeedAdjustmentPercent = jogAdjustment,
    history = history,
    programId = PROGRAM_ID,
    name = name,
)

fun hrSummary(
    blockIndex: Int,
    adjust: HeartRateAdjust = HeartRateAdjust.Speed,
    mode: HeartRateControlMode = HeartRateControlMode.Continuous,
    settled: Double? = null,
    end: Double? = null,
    safetyReductions: Int = 0,
    zoneHighBpm: Int = 126,
) = HeartRateBlockSummary(
    blockIndex = blockIndex, adjust = adjust, mode = mode, zoneLowBpm = 108, zoneHighBpm = zoneHighBpm,
    startValue = 6.0, initialValue = 6.0, endValue = 6.0, settledValue = settled, secondsInZone = 300,
    meanHeartRateBpm = 118.0, endHeartRateBpm = end, adjustments = 2, safetyReductions = safetyReductions,
    manualOverride = false, secondsWithoutHeartRate = 0, source = null,
)

fun blockRecord(
    index: Int,
    key: String,
    phase: BlockPhase,
    completed: Boolean = true,
    speed: Double = 7.0,
    finalSpeed: Double = speed,
    incline: Double = 1.0,
    reductions: Int = 0,
    endHr: Double? = null,
    heartRate: HeartRateBlockSummary? = null,
) = SessionBlockRecord(
    blockIndex = index, key = key, phase = phase, repetition = null, completed = completed, elapsedSeconds = 60, plannedSeconds = 60,
    plannedSpeedKmh = speed, plannedInclinePercent = incline, finalSpeedKmh = finalSpeed, finalInclinePercent = incline,
    manualSpeedReductions = reductions, manualChanges = reductions, endHeartRateBpm = endHr, heartRate = heartRate,
)

fun sessionRecord(
    generatorId: String,
    inputs: Map<String, String>,
    startedAt: Long,
    blocks: List<SessionBlockRecord>,
    outcome: SessionOutcome = SessionOutcome.Completed,
    maxHeartRateBpm: Int? = 180,
) = GeneratedSessionRecord(
    origin = ProgramOrigin(generatorId, 1, inputs),
    programId = PROGRAM_ID,
    startedAtMillis = startedAt,
    endedAtMillis = startedAt + 30 * 60_000L,
    outcome = outcome,
    programSeconds = 1800,
    maxHeartRateBpm = maxHeartRateBpm,
    manualSpeedReductions = blocks.sumOf { it.manualSpeedReductions },
    manualChanges = blocks.sumOf { it.manualChanges },
    workBlocksPlanned = blocks.count { it.phase == BlockPhase.Work },
    workBlocksCompleted = blocks.count { it.phase == BlockPhase.Work && it.completed },
    blocks = blocks,
)

/**
 * A Couch to 5K record: warm-up, [runs] run blocks with walks between them, cool-down. [runsCompleted] of the runs ran
 * to their end. The session ended 30 min after [startedAt].
 */
fun c25kRecord(
    session: Int,
    startedAt: Long,
    runs: Int = 3,
    runsCompleted: Int = runs,
    endHr: Double? = 140.0,
    reductions: Int = 0,
    safetyReductions: Int = 0,
    walkFinalSpeed: Double = 5.0,
    outcome: SessionOutcome = if (runsCompleted == runs) SessionOutcome.Completed else SessionOutcome.Stopped,
): GeneratedSessionRecord {
    val blocks = mutableListOf(blockRecord(0, "0", BlockPhase.WarmUp, speed = 5.0, finalSpeed = walkFinalSpeed))
    for (i in 0 until runs) {
        blocks += blockRecord(
            blocks.size, "1.0", BlockPhase.Work, completed = i < runsCompleted, endHr = endHr,
            reductions = if (i == 0) reductions else 0,
            heartRate = hrSummary(blocks.size, mode = HeartRateControlMode.BetweenRepetitions, end = endHr, safetyReductions = if (i == 0) safetyReductions else 0),
        )
        if (i < runsCompleted) blocks += blockRecord(blocks.size, "1.1", BlockPhase.Recovery, speed = 5.0, finalSpeed = walkFinalSpeed)
    }
    return sessionRecord(CouchToFiveK.ID, mapOf("session" to session.toString(), "mode" to "run"), startedAt, blocks, outcome)
}
