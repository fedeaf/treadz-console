package com.treadz.console.userdata

import com.treadz.console.programs.fitness.EbbelingMeasurements
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestInputs
import com.treadz.console.programs.fitness.FitnessTestOutputs
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxTestMeasurements
import com.treadz.console.programs.fitness.MinuteRecord
import com.treadz.console.programs.fitness.SpeedAdjustment
import com.treadz.console.programs.fitness.StageRecord
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.fitness.Vo2MaxMethod
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.analysis.fitness.Sex as FitnessSex

const val PROGRAM_1 = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01"
const val PROGRAM_2 = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c02"
const val TEST_1 = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b01"
const val TEST_2 = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b02"
const val TEST_3 = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b03"

const val DAY_MS = 86_400_000L

/** A free program with absolute values: 10 min warm-up, 4 x (4 min work + 3 min recovery), 5 min cool-down. */
fun norwegian(id: String = PROGRAM_1, name: String = "Norwegian 4x4", kind: ProgramKind = ProgramKind.Free) = Program(
    id = id,
    name = name,
    kind = kind,
    description = "Four hard intervals with active recovery.",
    origin = null,
    steps = listOf(
        Block(BlockPhase.WarmUp, BlockEnd.Duration(600), BlockTarget.Absolute(6.0, 2.0)),
        Repeat(
            4,
            listOf(
                Block(BlockPhase.Work, BlockEnd.Duration(240), BlockTarget.Absolute(10.0, 6.0), "4 min hard"),
                Block(BlockPhase.Recovery, BlockEnd.Duration(180), BlockTarget.Absolute(6.0, 2.0)),
            ),
        ),
        Block(BlockPhase.CoolDown, BlockEnd.Duration(300), BlockTarget.Absolute(5.0, 0.0)),
    ),
)

/** A concrete plan, resolved from the standard `Hill` program with its default parameters. */
fun hillPlan(): ResolvedPlan {
    val hill = checkNotNull(StandardPrograms.byId("standard-hill"))
    return ProgramResolver.resolve(hill.program, hill.defaultParameters)
}

/** A plan resolved from a user program (absolute values). */
fun planOf(program: Program): ResolvedPlan = ProgramResolver.resolve(program)

/** A hand-made, complete Ebbeling result for a 40-year-old man. */
fun ebbelingResult(
    id: String = TEST_1,
    startedAt: Long = T0,
    vo2max: Double = 41.0,
    validity: Validity = Validity.Valid,
    flags: List<FitnessTestFlag> = emptyList(),
): FitnessTestResult {
    val inputs = FitnessTestInputs(40, FitnessSex.Male, 80.5, 180, 180, HeartRateSourceKind.TreadmillConsole, 4.8, 4.8, maxSpeedLimitKmh = 16.0)
    return FitnessTestResult(
        id = id, testType = FitnessTestType.EbbelingWalk, protocolVersion = 1,
        startedAtMillis = startedAt, effortEndedAtMillis = startedAt + 480_000, endedAtMillis = startedAt + 660_500,
        validity = validity, flags = flags, endReason = FitnessEndReason.Completed, inputs = inputs,
        measurements = EbbelingMeasurements(
            90, 126, 153, 4.8, 2.98, 240, 240, 0, 1,
            listOf(SpeedAdjustment(60, 4.4, 4.8, 88.5)), 95.4, 113.6, 1.8, 18.2,
        ),
        minutes = listOf(
            MinuteRecord(FitnessTestPhase.Stage1, 1, 60, 60, 4.8, 0.0, 92.0),
            MinuteRecord(FitnessTestPhase.Stage2, 1, 300, 60, 4.8, 5.0, 110.5),
            MinuteRecord(FitnessTestPhase.CoolDown, 3, 660, 30, 4.0, 0.0, null),
        ),
        coolDownSeconds = 180,
        outputs = if (validity == Validity.Valid) {
            FitnessTestOutputs(vo2max, 4.85, Vo2MaxMethod.Ebbeling1991, classification = Vo2MaxNorms.classify(vo2max, 40, FitnessSex.Male))
        } else {
            null
        },
    )
}

/** A hand-made maximal incremental test result: MAS [mas] km/h, measured peak heart rate [peak]. */
fun maxTestResult(
    id: String = TEST_2,
    startedAt: Long = T0,
    mas: Double = 13.5,
    peak: Int? = 185,
    effort: EffortAssessment = EffortAssessment.Maximal,
): FitnessTestResult {
    val inputs = FitnessTestInputs(40, FitnessSex.Male, 80.5, 180, 180, null, 8.0, 8.0, maxSpeedLimitKmh = 16.0)
    return FitnessTestResult(
        id = id, testType = FitnessTestType.MaxIncrementalRun, protocolVersion = 1,
        startedAtMillis = startedAt, effortEndedAtMillis = startedAt + 1_020_000, endedAtMillis = startedAt + 1_330_000,
        validity = Validity.Valid, flags = emptyList(), endReason = FitnessEndReason.Exhausted, endDetail = "detail", inputs = inputs,
        measurements = MaxTestMeasurements(
            300, 720, 8.0, 0.5, 60, 1.0, 20.0, false,
            listOf(StageRecord(1, 8.0, 1.0, 60, true, 128.0), StageRecord(2, 8.5, 1.0, 20, false, null)),
            8.0, 20, peak?.toDouble(), 0.98,
        ),
        minutes = listOf(MinuteRecord(FitnessTestPhase.Incremental, 1, 360, 60, 8.0, 1.0, 128.0)),
        coolDownSeconds = 300,
        outputs = FitnessTestOutputs(
            3.5 * mas, 4.0, Vo2MaxMethod.LegerMercier1984, false, Vo2MaxNorms.classify(3.5 * mas, 40, FitnessSex.Male),
            masKmh = mas, masIsLowerBound = false, hrMaxCandidateBpm = peak, effort = effort,
        ),
    )
}
