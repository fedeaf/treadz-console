package com.treadz.console.programs.fitness

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maximal incremental running test (VAMEVAL / UMTT style, on a treadmill at 1 % grade, which compensates the missing
 * air resistance: Jones and Doust 1996), as run by the app.
 *
 * **Warm-up (5 min, 1 %)**: 2 min brisk walk at min(5.5 km/h, start), then 3 min easy jog at start − 1.5 km/h (never
 * slower than the walk). Manual changes are allowed here and kept until the next warm-up step.
 *
 * **Incremental phase (1 %)**: stage 1 at the start speed (caller's, default 8, clamped to 6-10 km/h and the cap);
 * +0.5 km/h every minute. The cap is the treadmill maximum and the user's speed limit: once the next stage would pass
 * it, the speed is held at the cap ([FitnessTestFlag.SpeedCapReached]) and the MAS becomes a lower bound.
 * It ends when the user reports exhaustion ([FitnessTestRunner.exhausted]): the belt does not stop abruptly, it drops
 * to a recovery walk. A manual speed/incline change breaks the protocol → invalid (it is impossible to tell an
 * "I cannot keep up" from any other reason; the user has the "exhausted" button for that), cool-down.
 *
 * **STOP at exhaustion** (user decision, 2026-10-08): a STOP after at least 3 min of the incremental phase counts as
 * exhaustion, whether it comes from the app ([FitnessTestRunner.stop]) or from the console (the belt is seen stopped
 * or paused; the time of the result is when the runner saw it, a few seconds after the key press at most). The result
 * is computed exactly as for the "exhausted" button, flagged [FitnessTestFlag.EndedWithStop]; the belt is stopped, so
 * there is **no cool-down** ([FitnessTestFlag.CoolDownIncomplete], `coolDownSeconds` 0, and the end detail says so).
 * Before 3 min a STOP stays an invalid interruption. The other interruptions (safety key, connection lost, command not
 * executed or failed, frozen data) are never exhaustion: invalid.
 *
 * **Results** at exhaustion (incremental time T s, stage length 60 s, increment 0.5 km/h):
 *  - T < 3 min → invalid ([FitnessTestFlag.IncrementalPhaseTooShort]).
 *  - MAS = speed of the last completed stage + (seconds into the incomplete stage / 60) · 0.5 km/h; at the cap, MAS =
 *    cap as a lower bound.
 *  - VO2max ≈ 3.5 · MAS (Léger and Mercier 1984), see [FitnessFormulas.LEGER_MERCIER_RATIO].
 *  - Heart rate (not required to run the test): used only if ≥ 80 % of the incremental phase has a valid reading, the
 *    last 30 s have one, and the last 30 s are ≥ 10 bpm above the first stage (a strap really measuring). Then the
 *    peak heart rate is the highest 5-s mean of the last 2 min, a candidate for the measured maximum heart rate.
 *  - Effort (the exhaustion is always confirmed by the user, with the button or the STOP): [EffortAssessment.Maximal]
 *    when T ≥ 5 min and either the peak is within 10 bpm of the user's maximum, **or there is a heart-rate plateau**;
 *    [EffortAssessment.NotMaximal] when T < 5 min, or the peak is lower and there is no plateau;
 *    [EffortAssessment.Unknown] without a reliable heart rate.
 *  - **Heart-rate plateau**: let h(k) be the heart rate at the end of completed stage k (mean of its last 10 s) and
 *    n = [FitnessTestConfig.plateauStages] (2). Plateau when h(last) − h(last − n) < [FitnessTestConfig.plateauMaxRiseBpm]
 *    (3 bpm; a fall counts) and the speed really rose by n increments between those two stages (so it does not apply
 *    when the speed cap was reached: a constant speed gives a constant heart rate). It needs a usable heart rate and
 *    n + 1 completed stages with heart rate. A plateau-based maximal effort adopts the peak as measured maximum heart
 *    rate even though it is below the estimate ([FitnessTestFlag.MaximalByHeartRatePlateau], informational; the
 *    measurements always carry [MaxTestMeasurements.heartRatePlateau] and the rise).
 *
 * **Cool-down**: 5 min at min(5 km/h, last speed), 0 %, then the belt is stopped. STOP (app or console) always stops
 * the belt and makes the test invalid, like any interruption.
 */
internal class MaxIncrementalProtocol(
    id: String,
    subject: FitnessSubject,
    config: FitnessTestConfig,
    requestedStartSpeedKmh: Double,
    maxSpeedLimitKmh: Double?,
    maxInclineLimitPercent: Double?,
    heartRateKind: HeartRateSourceKind,
    clampSpeed: (Double) -> Double,
    /** Highest speed allowed: treadmill maximum and user limit. */
    val capKmh: Double,
) : FitnessProtocol(
    FitnessTestType.MaxIncrementalRun, PROTOCOL_VERSION, id, subject, config, requestedStartSpeedKmh,
    maxSpeedLimitKmh, maxInclineLimitPercent, heartRateKind, clampSpeed,
) {
    override val startSpeedKmh: Double =
        clampSpeed(min(requestedStartSpeedKmh.coerceIn(config.maxTestMinStartSpeedKmh, config.maxTestMaxStartSpeedKmh), capKmh))
    override val coolDownSeconds: Int get() = config.maxTestCoolDownSeconds
    override val coolDownMaxSpeedKmh: Double get() = config.maxTestCoolDownSpeedKmh

    private val walkSpeed = clampSpeed(min(config.warmUpWalkSpeedKmh, startSpeedKmh))
    private val jogSpeed = clampSpeed(max(walkSpeed, startSpeedKmh - config.warmUpJogBelowStartKmh))
    private val incline = config.maxTestInclinePercent
    private val stageMs = config.stageSeconds * 1000L

    private var warmUpStep = 0
    private var overrideSpeed: Double? = null
    private var overrideIncline: Double? = null
    private var incStart: Long? = null
    private var stageIndex = -1
    private var stageStartT = 0L
    private val stages = mutableListOf<StageRecord>()
    private var capReached = false
    private var plateau = false
    private var plateauRise: Double? = null
    private var lastCompletedSpeed: Double? = null
    private var secondsIntoLast: Int? = null
    private var peakHr: Double? = null
    private var coverage: Double? = null

    override fun onBegin() {
        if (abs(startSpeedKmh - requestedStartSpeedKmh) > 0.049) flags += FitnessTestFlag.StartSpeedClamped
        enterPhase(0, FitnessTestPhase.WarmUp)
        targetSpeed = walkSpeed
        targetIncline = incline
    }

    private fun nominal(stage: Int) = startSpeedKmh + config.speedIncrementKmh * stage

    private fun speedOf(stage: Int) = clampSpeed(min(nominal(stage), capKmh))

    override fun effortUpdate(t: Long, obs: Observation) {
        when (phase) {
            FitnessTestPhase.WarmUp -> {
                val warmUpEnd = (config.warmUpWalkSeconds + config.warmUpJogSeconds) * 1000L
                if (t >= warmUpEnd) {
                    startIncremental(warmUpEnd)
                    incrementalUpdate(t)
                    return
                }
                val step = if (t < config.warmUpWalkSeconds * 1000L) 0 else 1
                if (step != warmUpStep) {
                    warmUpStep = step
                    overrideSpeed = null
                    overrideIncline = null
                }
                targetSpeed = overrideSpeed ?: if (step == 0) walkSpeed else jogSpeed
                targetIncline = overrideIncline ?: incline
            }
            FitnessTestPhase.Incremental -> incrementalUpdate(t)
            else -> Unit
        }
    }

    private fun startIncremental(at: Long) {
        enterPhase(at, FitnessTestPhase.Incremental)
        incStart = at
        overrideSpeed = null
        overrideIncline = null
    }

    private fun incrementalUpdate(t: Long) {
        val start = incStart ?: return
        val k = ((t - start) / stageMs).toInt()
        while (stageIndex < k) {
            if (stageIndex >= 0) closeStage(stageStartT + stageMs, completed = true)
            stageIndex++
            stageStartT = start + stageIndex * stageMs
            val speed = speedOf(stageIndex)
            if (nominal(stageIndex) > capKmh + 1e-9 && !capReached) {
                capReached = true
                flags += FitnessTestFlag.SpeedCapReached
            }
            pendingEvents += FitnessTestEvent.StageStarted(at(stageStartT), (stageStartT / 1000).toInt(), stageIndex + 1, speed)
        }
        targetSpeed = speedOf(stageIndex)
        targetIncline = incline
    }

    private fun closeStage(endT: Long, completed: Boolean) {
        val duration = ((endT - stageStartT) / 1000).toInt()
        if (duration <= 0 && !completed) return
        stages += StageRecord(
            number = stageIndex + 1,
            speedKmh = speedOf(stageIndex),
            inclinePercent = incline,
            durationSeconds = duration,
            completed = completed,
            heartRateEndBpm = hr.mean(endT - 10_000, endT)?.let(FitnessFormulas::round1),
        )
    }

    override fun onManualChange(t: Long, speedKmh: Double?, inclinePercent: Double?): Boolean {
        if (phase == FitnessTestPhase.WarmUp) {
            speedKmh?.let { overrideSpeed = it; targetSpeed = it }
            inclinePercent?.let { overrideIncline = it; targetIncline = it }
            return false
        }
        return super.onManualChange(t, speedKmh, inclinePercent)
    }

    override fun exhausted(t: Long): Boolean = when (phase) {
        FitnessTestPhase.WarmUp -> endByUser(t)
        FitnessTestPhase.Incremental -> {
            incrementalUpdate(t)
            endEffort(t, FitnessEndReason.Exhausted, null, coolDown = true)
            true
        }
        else -> false
    }

    override fun endEffortOnStop(t: Long, fromConsole: Boolean): Boolean {
        val start = incStart
        if (phase != FitnessTestPhase.Incremental || start == null || t - start < config.minIncrementalSeconds * 1000L) return false
        incrementalUpdate(t)
        flags += FitnessTestFlag.EndedWithStop
        val source = if (fromConsole) "the belt was stopped or paused from the console" else "STOP was pressed in the app"
        endEffort(
            t, FitnessEndReason.Exhausted,
            "$source after ${(t - start) / 1000} s of the incremental phase: counted as exhaustion; the belt stopped, so there was no cool-down",
            coolDown = false,
        )
        return true
    }

    /**
     * Heart rate at the end of the last completed stage minus the one [FitnessTestConfig.plateauStages] stages
     * earlier; `null` when there are not enough stages with heart rate or the speed did not rise by that many full
     * increments (speed cap).
     */
    private fun plateauRiseOverLastStages(): Double? {
        val n = config.plateauStages
        val done = stages.filter { it.completed }
        if (n < 1 || done.size < n + 1) return null
        val last = done.last()
        val reference = done[done.size - 1 - n]
        if (last.speedKmh - reference.speedKmh < n * config.speedIncrementKmh - 0.049) return null
        val a = last.heartRateEndBpm ?: return null
        val b = reference.heartRateEndBpm ?: return null
        return FitnessFormulas.round1(a - b)
    }

    /** MAS if the user reported exhaustion at [t] (`null` before the first completed stage). */
    private fun masAt(t: Long): Pair<Double, Boolean>? {
        val start = incStart ?: return null
        if (capReached) return capKmh to true
        val mas = FitnessFormulas.mas(startSpeedKmh, config.speedIncrementKmh, config.stageSeconds, (t - start) / 1000.0) ?: return null
        return FitnessFormulas.round2(mas) to false
    }

    override fun closeMeasurements(t: Long) {
        val start = incStart ?: return
        if (stageIndex >= 0) {
            val completedStages = ((t - start) / stageMs).toInt()
            closeStage(t, completed = false)
            if (completedStages >= 1) lastCompletedSpeed = speedOf(completedStages - 1)
            secondsIntoLast = ((t - stageStartT) / 1000).toInt()
        }
    }

    override fun computeOutputs(t: Long, reason: FitnessEndReason): FitnessTestOutputs? {
        if (reason != FitnessEndReason.Exhausted) return null
        val start = incStart ?: return null
        val incSeconds = (t - start) / 1000.0
        if (incSeconds < config.minIncrementalSeconds) {
            flags += FitnessTestFlag.IncrementalPhaseTooShort
            return null
        }
        val (mas, lowerBound) = masAt(t) ?: return null

        // Heart rate: only when it was really measuring.
        val cov = hr.coverage(start, t)
        coverage = FitnessFormulas.round2(cov)
        val last30 = hr.mean(t - 30_000, t)
        val firstStage = hr.mean(start, start + stageMs)
        val hrUsable = cov >= config.minHeartRateCoverage && last30 != null && firstStage != null &&
            hr.coverage(t - 30_000, t) >= config.minHeartRateCoverage &&
            last30 - firstStage >= config.minMaxTestHeartRateRiseBpm
        when {
            hrUsable -> peakHr = hr.maxSlidingMean(t - config.peakWindowSeconds * 1000L, t, config.peakMeanSeconds * 1000L)?.let(FitnessFormulas::round1)
            cov < config.noHeartRateCoverage -> flags += FitnessTestFlag.NoValidHeartRate
            else -> flags += FitnessTestFlag.HeartRateUnreliable
        }
        val peak = peakHr

        if (peak != null) {
            plateauRise = plateauRiseOverLastStages()
            plateau = plateauRise?.let { it < config.plateauMaxRiseBpm } ?: false
        }

        val short = incSeconds < config.maximalMinIncrementalSeconds
        if (short) flags += FitnessTestFlag.ShortIncrementalPhase
        val peakLow = peak != null && peak < subject.hrMaxBpm - config.effortHrMarginBpm
        if (peakLow) flags += if (plateau && !short) FitnessTestFlag.MaximalByHeartRatePlateau else FitnessTestFlag.PeakHeartRateLow
        val effort = when {
            short -> EffortAssessment.NotMaximal
            peak == null -> EffortAssessment.Unknown
            peakLow && !plateau -> EffortAssessment.NotMaximal
            else -> EffortAssessment.Maximal
        }
        val vo2 = FitnessFormulas.round1(FitnessFormulas.vo2maxFromMas(mas))
        return FitnessTestOutputs(
            vo2maxMlKgMin = vo2,
            vo2maxSeeMlKgMin = FitnessFormulas.MAS_VO2MAX_SEE,
            method = Vo2MaxMethod.LegerMercier1984,
            vo2maxIsLowerBound = lowerBound,
            classification = Vo2MaxNorms.classify(vo2, subject.ageYears, subject.sex),
            masKmh = mas,
            masIsLowerBound = lowerBound,
            hrMaxCandidateBpm = peak?.roundToInt(),
            effort = effort,
        )
    }

    override fun measurements(): FitnessTestMeasurements {
        val start = incStart
        val end = effortEndT
        return MaxTestMeasurements(
            warmUpSeconds = ((start ?: end ?: 0L) / 1000).toInt(),
            incrementalSeconds = if (start != null && end != null) ((end - start) / 1000).toInt() else 0,
            startSpeedKmh = startSpeedKmh,
            speedIncrementKmh = config.speedIncrementKmh,
            stageSeconds = config.stageSeconds,
            inclinePercent = incline,
            capKmh = capKmh,
            capReached = capReached,
            stages = stages.toList(),
            lastCompletedStageSpeedKmh = lastCompletedSpeed,
            secondsIntoLastStage = secondsIntoLast,
            peakHeartRateBpm = peakHr,
            heartRateCoverage = coverage,
            heartRatePlateau = plateau,
            plateauRiseBpm = plateauRise,
        )
    }

    override fun decorate(p: FitnessTestProgress, t: Long): FitnessTestProgress = when (phase) {
        FitnessTestPhase.WarmUp -> {
            val warmUpEnd = (config.warmUpWalkSeconds + config.warmUpJogSeconds) * 1000L
            p.copy(
                instruction = if (warmUpStep == 0) FitnessInstruction.WarmUpWalk else FitnessInstruction.WarmUpJog,
                phaseRemainingSeconds = ceilSeconds(max(0L, warmUpEnd - t)),
                nextStageSpeedKmh = startSpeedKmh,
                canReportExhaustion = false,
            )
        }
        else -> {
            val atCap = nominal(stageIndex + 1) > capKmh + 1e-9
            p.copy(
                instruction = if (capReached) FitnessInstruction.HoldTopSpeed else FitnessInstruction.RunUntilExhausted,
                stageNumber = stageIndex + 1,
                stageRemainingSeconds = if (atCap) null else ceilSeconds(max(0L, stageStartT + stageMs - t)),
                nextStageSpeedKmh = if (atCap) null else speedOf(stageIndex + 1),
                canReportExhaustion = true,
                capReached = capReached,
                masIfExhaustedNowKmh = masAt(t)?.first,
            )
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
