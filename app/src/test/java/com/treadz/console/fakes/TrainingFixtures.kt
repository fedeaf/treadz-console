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

    fun coolDown(result: FitnessTestResult) = FitnessTestProgress(
        testType = result.testType, phase = FitnessTestPhase.CoolDown, instruction = FitnessInstruction.CoolDownWalk,
        elapsedSeconds = 1100, phaseElapsedSeconds = 160, phaseRemainingSeconds = 140,
        targetSpeedKmh = 5.0, targetInclinePercent = 0.0, actualSpeedKmh = 5.0, actualInclinePercent = 0.0,
        heartRateBpm = 138, heartRateValidity = HeartRateValidity.Unconfirmed, endReason = result.endReason, result = result,
    )

    fun finished(result: FitnessTestResult) = FitnessTestProgress(
        testType = result.testType, phase = FitnessTestPhase.Finished, instruction = FitnessInstruction.Finished,
        elapsedSeconds = 1330, endReason = result.endReason, result = result,
    )

    /** The result of an Ebbeling test that was thrown out: the strap did not respond and the user changed the speed. */
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
