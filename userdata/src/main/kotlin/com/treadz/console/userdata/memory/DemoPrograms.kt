package com.treadz.console.userdata.memory

import com.treadz.console.programs.fitness.EbbelingMeasurements
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessTestInputs
import com.treadz.console.programs.fitness.FitnessTestOutputs
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxTestMeasurements
import com.treadz.console.programs.fitness.MinuteRecord
import com.treadz.console.programs.fitness.Sex
import com.treadz.console.programs.fitness.SpeedAdjustment
import com.treadz.console.programs.fitness.StageRecord
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.fitness.Vo2MaxMethod
import com.treadz.console.programs.fitness.Vo2MaxNorms
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.Repeat

/** Ready-made programs and fitness test results for the UI's tests and screenshots (see [DemoData]). */
object DemoPrograms {
    const val NORWEGIAN_ID = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01"
    const val EBBELING_ID = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b01"
    const val MAX_TEST_ID = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b02"

    /** A free program with absolute values: 10 min warm-up, 4 x (4 min work + 3 min recovery), 5 min cool-down. */
    fun norwegian4x4(id: String = NORWEGIAN_ID, name: String = "Norwegian 4x4") = Program(
        id = id,
        name = name,
        kind = ProgramKind.Free,
        description = "Four hard intervals with active recovery.",
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

    /** A complete, valid Ebbeling walking test result of a [ageYears]-year-old [sex] (VO2max [vo2max]). */
    fun ebbeling(
        id: String = EBBELING_ID,
        startedAtMillis: Long,
        vo2max: Double = 41.0,
        ageYears: Int = 40,
        sex: Sex = Sex.Male,
        weightKg: Double = 80.0,
    ): FitnessTestResult {
        val hrMax = Math.round(208 - 0.7 * ageYears).toInt()
        return FitnessTestResult(
            id = id, testType = FitnessTestType.EbbelingWalk, protocolVersion = 1,
            startedAtMillis = startedAtMillis, effortEndedAtMillis = startedAtMillis + 480_000, endedAtMillis = startedAtMillis + 660_500,
            validity = Validity.Valid, flags = emptyList(), endReason = FitnessEndReason.Completed,
            inputs = FitnessTestInputs(ageYears, sex, weightKg, hrMax, 220 - ageYears, HeartRateSourceKind.TreadmillConsole, 4.8, 4.8),
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
            outputs = FitnessTestOutputs(vo2max, 4.85, Vo2MaxMethod.Ebbeling1991, classification = Vo2MaxNorms.classify(vo2max, ageYears, sex)),
        )
    }

    /** A complete maximal incremental test result: maximal aerobic speed [masKmh], measured peak heart rate [peakBpm]. */
    fun maxTest(
        id: String = MAX_TEST_ID,
        startedAtMillis: Long,
        masKmh: Double = 13.5,
        peakBpm: Int = 185,
        ageYears: Int = 40,
        sex: Sex = Sex.Male,
        weightKg: Double = 80.0,
    ): FitnessTestResult {
        val vo2max = Math.round(3.5 * masKmh * 10) / 10.0
        return FitnessTestResult(
            id = id, testType = FitnessTestType.MaxIncrementalRun, protocolVersion = 1,
            startedAtMillis = startedAtMillis, effortEndedAtMillis = startedAtMillis + 1_020_000, endedAtMillis = startedAtMillis + 1_330_000,
            validity = Validity.Valid, flags = emptyList(), endReason = FitnessEndReason.Exhausted,
            inputs = FitnessTestInputs(ageYears, sex, weightKg, Math.round(208 - 0.7 * ageYears).toInt(), 220 - ageYears, HeartRateSourceKind.TreadmillConsole, 8.0, 8.0),
            measurements = MaxTestMeasurements(
                300, 720, 8.0, 0.5, 60, 1.0, 20.0, false,
                listOf(StageRecord(1, 8.0, 1.0, 60, true, 128.0), StageRecord(2, 8.5, 1.0, 20, false, null)),
                8.0, 20, peakBpm.toDouble(), 0.98,
            ),
            minutes = listOf(MinuteRecord(FitnessTestPhase.Incremental, 1, 360, 60, 8.0, 1.0, 128.0)),
            coolDownSeconds = 300,
            outputs = FitnessTestOutputs(
                vo2max, 4.0, Vo2MaxMethod.LegerMercier1984, false, Vo2MaxNorms.classify(vo2max, ageYears, sex),
                masKmh = masKmh, masIsLowerBound = false, hrMaxCandidateBpm = peakBpm, effort = EffortAssessment.Maximal,
            ),
        )
    }
}
