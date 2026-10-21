package com.treadz.console.fakes

import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessInstruction
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.SteadyStateStatus
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import kotlin.math.abs
import kotlin.math.roundToInt

/** Progress snapshots of the fitness tests and workouts that follow a plan, for the screenshots and the UI tests. */
object TrainingFixtures {

    fun ebbelingStage1(speedLocked: Boolean = false, hr: Int? = 104) = FitnessTestProgress(
        testType = FitnessTestType.EbbelingWalk, phase = FitnessTestPhase.Stage1,
        instruction = if (speedLocked) FitnessInstruction.WalkSpeedFixed else FitnessInstruction.WalkSpeedMayChange,
        elapsedSeconds = 95, phaseElapsedSeconds = 95, phaseRemainingSeconds = 145, stageNumber = 1,
        targetSpeedKmh = 4.8, targetInclinePercent = 0.0, actualSpeedKmh = 4.8, actualInclinePercent = 0.0,
        heartRateBpm = hr, heartRateValidity = if (hr != null) HeartRateValidity.Unconfirmed else HeartRateValidity.Invalid,
        heartRateBandLowBpm = 90, heartRateBandHighBpm = 126, heartRateLimitBpm = 153,
        speedLocked = speedLocked, canEndTest = true,
    )

    fun ebbelingStage2(steady: SteadyStateStatus = SteadyStateStatus.Pending, extensions: Int = 0) = FitnessTestProgress(
        testType = FitnessTestType.EbbelingWalk, phase = FitnessTestPhase.Stage2, instruction = FitnessInstruction.WalkUphill,
        elapsedSeconds = 380, phaseElapsedSeconds = 140, phaseRemainingSeconds = 100, stageNumber = 2,
        targetSpeedKmh = 4.8, targetInclinePercent = 5.0, actualSpeedKmh = 4.8, actualInclinePercent = 5.0,
        heartRateBpm = 121, heartRateValidity = HeartRateValidity.Unconfirmed,
        heartRateBandLowBpm = 90, heartRateBandHighBpm = 126, heartRateLimitBpm = 153,
        speedLocked = true, steadyState = steady, steadyStateVariationBpm = 2.4, stage2Extensions = extensions, canEndTest = true,
    )

    fun maxIncremental(capReached: Boolean = false) = FitnessTestProgress(
        testType = FitnessTestType.MaxIncrementalRun, phase = FitnessTestPhase.Incremental,
        instruction = if (capReached) FitnessInstruction.HoldTopSpeed else FitnessInstruction.RunUntilExhausted,
        elapsedSeconds = 640, phaseElapsedSeconds = 310, phaseRemainingSeconds = null, stageNumber = 6,
        stageRemainingSeconds = if (capReached) null else 25,
        targetSpeedKmh = 10.5, targetInclinePercent = 1.0, nextStageSpeedKmh = if (capReached) null else 11.0,
        actualSpeedKmh = 10.5, actualInclinePercent = 1.0, heartRateBpm = 171, heartRateValidity = HeartRateValidity.Unconfirmed,
        heartRateLimitBpm = null, canReportExhaustion = true, capReached = capReached, masIfExhaustedNowKmh = 10.0, canEndTest = true,
    )

    /** The walking heart-rate test in its warm-up (second step): the ramp will start at 5.5 km/h and 5 %. */
    fun walkWarmUp() = FitnessTestProgress(
        testType = FitnessTestType.MaxHeartRateWalk, phase = FitnessTestPhase.WarmUp, instruction = FitnessInstruction.WarmUpWalk,
        elapsedSeconds = 78, phaseElapsedSeconds = 78, phaseRemainingSeconds = 42,
        targetSpeedKmh = 4.4, targetInclinePercent = 1.0, nextStageSpeedKmh = 5.5, nextStageInclinePercent = 5.0,
        actualSpeedKmh = 4.4, actualInclinePercent = 1.0, heartRateBpm = 104, heartRateValidity = HeartRateValidity.Valid,
        canEndTest = true,
    )

    /** The walking heart-rate test [rampSeconds] into the ramp (+1 % of incline every minute from 5 %). */
    fun walkRamp(rampSeconds: Int, hr: Int = 158, capReached: Boolean = false): FitnessTestProgress {
        val stage = rampSeconds / 60
        val incline = (5 + stage).coerceAtMost(18).toDouble()
        return FitnessTestProgress(
            testType = FitnessTestType.MaxHeartRateWalk, phase = FitnessTestPhase.Incremental,
            instruction = if (capReached) FitnessInstruction.HoldTopSpeed else FitnessInstruction.WalkUphillUntilExhausted,
            elapsedSeconds = 120 + rampSeconds, phaseElapsedSeconds = rampSeconds, phaseRemainingSeconds = null, stageNumber = stage + 1,
            stageRemainingSeconds = 60 - rampSeconds % 60, targetSpeedKmh = 5.5, targetInclinePercent = incline,
            nextStageSpeedKmh = 5.5, nextStageInclinePercent = (incline + 1).coerceAtMost(18.0),
            actualSpeedKmh = 5.5, actualInclinePercent = incline, heartRateBpm = hr, heartRateValidity = HeartRateValidity.Valid,
            canReportExhaustion = true, capReached = capReached, canEndTest = true,
        )
    }

    fun coolDown(result: FitnessTestResult) = FitnessTestProgress(
        testType = result.testType, phase = FitnessTestPhase.CoolDown, instruction = FitnessInstruction.CoolDownWalk,
        elapsedSeconds = 1100, phaseElapsedSeconds = 160, phaseRemainingSeconds = 140,
        targetSpeedKmh = 5.0, targetInclinePercent = 0.0, actualSpeedKmh = 5.0, actualInclinePercent = 0.0,
        heartRateBpm = 138, heartRateValidity = HeartRateValidity.Unconfirmed, endReason = result.endReason, result = result,
        manualChangesAllowed = true, canExtendPhase = true,
    )

    fun finished(result: FitnessTestResult) = FitnessTestProgress(
        testType = result.testType, phase = FitnessTestPhase.Finished, instruction = FitnessInstruction.Finished,
        elapsedSeconds = 1330, endReason = result.endReason, result = result,
    )

    /** The result of an Ebbeling test that was thrown out: the strap did not respond and the user changed the speed. */
    /** A free program with a heart-rate guided block (zone 2: 60-70 % of the maximum) between a warm-up and a cool-down. */
    fun heartRateProgram(adjust: HeartRateAdjust = HeartRateAdjust.Speed) = Program(
        id = "3b6e1c1e-5d7e-4a53-9a41-0f1d2c3b4a5d", name = "Zone 2 base", kind = ProgramKind.Free,
        steps = listOf(
            Block(BlockPhase.WarmUp, BlockEnd.Duration(120), BlockTarget.Absolute(4.5, 0.0), name = "Warm-up"),
            Block(
                BlockPhase.Work, BlockEnd.Duration(1200),
                BlockTarget.HeartRate(
                    zone = HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70),
                    adjust = adjust,
                    minimum = if (adjust == HeartRateAdjust.Speed) 5.0 else 0.0,
                    maximum = if (adjust == HeartRateAdjust.Speed) 9.0 else 10.0,
                    start = if (adjust == HeartRateAdjust.Speed) BlockTarget.Absolute(6.0, 1.0) else BlockTarget.Absolute(5.0, 3.0),
                ),
                name = "Zone 2",
            ),
            Block(BlockPhase.CoolDown, BlockEnd.Duration(180), BlockTarget.Absolute(4.0, 0.0), name = "Cool-down"),
        ),
    )

    /**
     * A free program with a distance block and values kept from the block before: a warm-up, 1 km at 10 km/h and 2 %, a
     * recovery that keeps the speed (flat), a block that keeps the incline, and a cool-down.
     */
    fun keepProgram() = Program(
        id = "5c7d2e3f-6a8b-4c9d-8e0f-1a2b3c4d5e6f", name = "Keep and distance", kind = ProgramKind.Free,
        steps = listOf(
            Block(BlockPhase.WarmUp, BlockEnd.Duration(300), BlockTarget.Absolute(5.0, 0.0), name = "Warm-up"),
            Block(BlockPhase.Work, BlockEnd.Distance(1000), BlockTarget.Absolute(10.0, 2.0), name = "1 km"),
            Block(BlockPhase.Recovery, BlockEnd.Duration(180), BlockTarget.Absolute(null, 0.0), name = "Easy"),
            Block(BlockPhase.Work, BlockEnd.Duration(300), BlockTarget.Absolute(9.0, null), name = "Steady"),
            Block(BlockPhase.CoolDown, BlockEnd.Duration(240), BlockTarget.Absolute(4.5, 0.0), name = "Cool-down"),
        ),
    )

    fun invalidEbbeling(base: FitnessTestResult): FitnessTestResult = base.copy(
        validity = Validity.Invalid,
        flags = listOf(FitnessTestFlag.SpeedAdjustedByUser, FitnessTestFlag.HeartRateNotResponsive, FitnessTestFlag.ManualChange),
        endReason = FitnessEndReason.ManualChange,
        outputs = null,
    )

    /** One sample per second of a workout that follows [plan]: the speed ramps at 0.5 km/h per second towards each block's target. */
    fun planSamples(plan: ResolvedPlan, startedAtMillis: Long, hrBase: Int = 120): List<WorkoutSample> {
        val total = plan.estimatedDurationSeconds
        var speed = 0.0
        var distance = 0.0
        return (0 until total).map { t ->
            val block = plan.blocks.last { b -> plan.blocks.take(b.index).sumOf { it.estimatedDurationSeconds } <= t }
            speed = if (abs(block.speedKmh - speed) <= 0.5) block.speedKmh else speed + if (block.speedKmh > speed) 0.5 else -0.5
            distance += speed / 3.6
            WorkoutSample(
                seq = t, timestampMillis = startedAtMillis + t * 1000L, elapsedSeconds = t, speedKmh = (speed * 10).roundToInt() / 10.0,
                targetSpeedKmh = block.speedKmh, inclinePercent = block.inclinePercent, distanceMeters = distance.roundToInt(),
                calories = t / 9, heartRateBpm = hrBase + (speed * 4).roundToInt(), beltStatus = BeltStatus.Running,
            )
        }
    }

    fun statsOf(samples: List<WorkoutSample>) = WorkoutStats(
        durationSeconds = samples.size,
        distanceMeters = samples.last().distanceMeters,
        calories = samples.last().calories,
        averageSpeedKmh = samples.map { it.speedKmh }.average(),
        maxSpeedKmh = samples.maxOf { it.speedKmh },
        averageHeartRateBpm = samples.mapNotNull { it.heartRateBpm }.average().roundToInt(),
        maxHeartRateBpm = samples.mapNotNull { it.heartRateBpm }.maxOrNull(),
        maxInclinePercent = samples.maxOf { it.inclinePercent },
    )
}
