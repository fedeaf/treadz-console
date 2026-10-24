package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.StageLoad
import com.treadz.console.analysis.fitness.WalkingTestVo2max
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.programs.heartrate.HeartRatePlausibilityFilter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maximal walking test with incline: a modified Balke-Ware protocol (constant walking speed, +1 % per minute) designed
 * to measure the maximum heart rate efficiently; it also gives the VO2max and, with RR intervals from a strap
 * connected to the app, the thresholds.
 *
 * **Warm-up (2 min)**: 1 min at 75 % of the ramp speed and 0 %, then 1 min at 80 % and 1 % (rounded to 0.1 km/h: 4.1 and
 * 4.4 km/h for 5.5). Manual changes are kept until the next warm-up step; the ramp start (speed, incline) can still be
 * changed ([FitnessTestRunner.adjustRampStart]); "+1" ([FitnessTestRunner.extendPhase]) makes the current step a
 * minute longer.
 *
 * **Ramp**: starts at 5.5 km/h and 5 % (fixed defaults, changeable before the start or during the warm-up), +1 % every
 * minute. Once the incline reaches 18 % (or the user's incline limit, or the treadmill maximum), every minute the speed
 * rises by the equivalent of +1 % of grade ([Acsm.walkingSpeedStepForGrade], computed once at the cap: +0.2
 * km/h at 5.5 km/h). If the speed cap (treadmill or user limit) is reached too, the load is held there
 * ([MaxHeartRateWalkMeasurements.capReached], [FitnessTestFlag.SpeedCapReached]).
 *
 * **End and validity, as in the running test**: the "exhausted" button, or STOP (app or console) after at least 3 min of
 * ramp ([FitnessTestFlag.EndedWithStop], no cool-down). A manual change during the ramp or any interruption makes it
 * invalid; exhaustion before 3 min of ramp too ([FitnessTestFlag.IncrementalPhaseTooShort]).
 *
 * **Result**: maximum heart rate = highest 5-s mean from 2 minutes before the exhaustion to the end of the test (the
 * cool-down or the stop: the heart rate keeps rising for a few seconds; known when the effort ends, it may still rise
 * until the test finishes, and the effort is assessed again then), used only with a reliable heart
 * rate (≥ 80 % coverage, a reading in the last 30 s, and the last 30 s ≥ 10 bpm above the first stage). Effort with the
 * rules of the running test: [EffortAssessment.Maximal] with ≥ 5 min of ramp and a peak within 10 bpm of the user's
 * maximum or a heart-rate plateau (the end-of-stage heart rate rose < 3 bpm over the last 2 stages while the load kept
 * rising: never at the caps). Impossible readings (spikes, a sudden drop of a strap taken off, < 40, > 240) are
 * discarded with [HeartRatePlausibilityFilter] (without its "maximum + 15" rule: this test measures the maximum).
 *
 * **VO2max** (valid results that ended by exhaustion): the ACSM equation at the mean real load (speed and incline
 * reported by the treadmill) of the last 60 s of the effort ([WalkingTestVo2max]), SEE 3.5 (assumed); a lower bound
 * when the effort was not maximal or both caps were reached. MAS = VO2max / 3.5, estimated
 * ([FitnessTestRecalculation.walkOutputs]). **Thresholds**: DFA-α1 from the start of the warm-up to the exhaustion
 * ([FitnessTestRecalculation.thresholds]); without RR intervals the reason `noRrIntervals`.
 *
 * **Cool-down**: 3 min flat at the lower of 5 km/h and the last speed ("+1" makes it a minute longer), changeable by
 * hand; a STOP there keeps the result.
 */
internal class MaxHeartRateWalkProtocol(
    id: String,
    subject: FitnessSubject,
    config: FitnessTestConfig,
    requestedStartSpeedKmh: Double,
    val requestedStartInclinePercent: Double,
    maxSpeedLimitKmh: Double?,
    maxInclineLimitPercent: Double?,
    heartRateKind: HeartRateSourceKind,
    clampSpeed: (Double) -> Double,
    private val clampIncline: (Double) -> Double,
    /** Highest speed allowed: treadmill maximum and user limit. */
    val speedCapKmh: Double,
    /** Highest incline of the ramp: 18 %, the user limit, the treadmill maximum. */
    val inclineCapPercent: Double,
) : FitnessProtocol(
    FitnessTestType.MaxHeartRateWalk, PROTOCOL_VERSION, id, subject, config, requestedStartSpeedKmh,
    maxSpeedLimitKmh, maxInclineLimitPercent, heartRateKind, clampSpeed,
) {
    private var rampSpeed = clampStartSpeed(requestedStartSpeedKmh)
    private var rampIncline = clampStartIncline(requestedStartInclinePercent)

    override val startSpeedKmh: Double get() = rampSpeed
    val startInclinePercent: Double get() = rampIncline
    override val checksStrap: Boolean get() = true
    override val coolDownSeconds: Int get() = config.walkTestCoolDownSeconds
    override val coolDownMaxSpeedKmh: Double get() = config.walkTestCoolDownSpeedKmh

    private val stageMs = config.stageSeconds * 1000L
    private val warmUpStepMs = config.walkTestWarmUpStepSeconds * 1000L
    private val filter = FitnessTestRecalculation.peakFilter()

    private var warmUpStep = 0
    private val warmUp = TwoStepWarmUp(warmUpStepMs, warmUpStepMs)
    private var overrideSpeed: Double? = null
    private var overrideIncline: Double? = null
    private var incStart: Long? = null
    private var stageIndex = -1
    private var stageStartT = 0L
    private val stages = mutableListOf<StageRecord>()

    /** Stage at which the incline reaches its cap; the speed rises from the following one. */
    private var capStage = 0
    private var speedStep = 0.1
    private var capReached = false
    private var plateau = false
    private var plateauRise: Double? = null
    private var peakHr: Double? = null
    private var coverage: Double? = null
    private var candidate: Int? = null
    private var effort: EffortAssessment? = null

    private fun clampStartSpeed(v: Double) = clampSpeed(min(v.coerceIn(config.walkTestMinStartSpeedKmh, config.walkTestMaxStartSpeedKmh), speedCapKmh))

    private fun clampStartIncline(v: Double) = clampIncline(min(v.coerceIn(0.0, config.walkTestMaxStartInclinePercent), inclineCapPercent))

    override fun plausibleHeartRate(t: Long, bpm: Int?): Int? = filter.accept(t, bpm)

    override fun onBegin() {
        if (abs(rampSpeed - requestedStartSpeedKmh) > 0.049 || abs(rampIncline - requestedStartInclinePercent) > 0.49) {
            flags += FitnessTestFlag.StartSpeedClamped
        }
        enterPhase(0, FitnessTestPhase.WarmUp)
        warmUpTargets()
    }

    private fun warmUpTargets() {
        targetSpeed = overrideSpeed ?: clampSpeed(rampSpeed * if (warmUpStep == 0) config.walkTestWarmUpFraction1 else config.walkTestWarmUpFraction2)
        targetIncline = overrideIncline ?: if (warmUpStep == 0) 0.0 else clampIncline(min(config.walkTestWarmUpIncline2Percent, inclineCapPercent))
    }

    /** The user changes the ramp start during the warm-up. Returns false outside the warm-up. */
    fun adjustRampStart(speedKmh: Double?, inclinePercent: Double?): Boolean {
        if (phase != FitnessTestPhase.WarmUp) return false
        speedKmh?.let { rampSpeed = clampStartSpeed(it) }
        inclinePercent?.let { rampIncline = clampStartIncline(it) }
        warmUpTargets()
        return true
    }

    private fun inclineOf(stage: Int) = min(rampIncline + config.walkTestInclineIncrementPercent * stage, inclineCapPercent)

    private fun nominalSpeedOf(stage: Int) = if (stage <= capStage) rampSpeed else rampSpeed + speedStep * (stage - capStage)

    private fun speedOf(stage: Int) = clampSpeed(min(nominalSpeedOf(stage), speedCapKmh))

    override fun extendWarmUp(t: Long, ms: Long): Boolean {
        warmUp.extend(t, ms)
        return true
    }

    override fun effortUpdate(t: Long, obs: Observation) {
        when (phase) {
            FitnessTestPhase.WarmUp -> {
                val warmUpEnd = warmUp.endMs
                if (t >= warmUpEnd) {
                    startRamp(warmUpEnd)
                    rampUpdate(t)
                    return
                }
                val step = warmUp.step(t)
                if (step != warmUpStep) {
                    warmUpStep = step
                    overrideSpeed = null
                    overrideIncline = null
                }
                warmUpTargets()
            }
            FitnessTestPhase.Incremental -> rampUpdate(t)
            else -> Unit
        }
    }

    private fun startRamp(at: Long) {
        enterPhase(at, FitnessTestPhase.Incremental)
        incStart = at
        overrideSpeed = null
        overrideIncline = null
        capStage = max(0, ceil((inclineCapPercent - rampIncline) / config.walkTestInclineIncrementPercent - 1e-9).toInt())
        speedStep = Acsm.walkingSpeedStepForGrade(rampSpeed, inclineCapPercent, config.walkTestInclineIncrementPercent)
    }

    private fun rampUpdate(t: Long) {
        val start = incStart ?: return
        val k = ((t - start) / stageMs).toInt()
        while (stageIndex < k) {
            if (stageIndex >= 0) closeStage(stageStartT + stageMs, completed = true)
            stageIndex++
            stageStartT = start + stageIndex * stageMs
            if (nominalSpeedOf(stageIndex) > speedCapKmh + 1e-9 && !capReached) {
                capReached = true
                flags += FitnessTestFlag.SpeedCapReached
            }
            pendingEvents += FitnessTestEvent.StageStarted(at(stageStartT), (stageStartT / 1000).toInt(), stageIndex + 1, speedOf(stageIndex))
        }
        targetSpeed = speedOf(stageIndex)
        targetIncline = inclineOf(stageIndex)
    }

    private fun closeStage(endT: Long, completed: Boolean) {
        val duration = ((endT - stageStartT) / 1000).toInt()
        if (duration <= 0 && !completed) return
        stages += StageRecord(
            number = stageIndex + 1,
            speedKmh = speedOf(stageIndex),
            inclinePercent = inclineOf(stageIndex),
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
            rampUpdate(t)
            endEffort(t, FitnessEndReason.Exhausted, null, coolDown = true)
            true
        }
        else -> false
    }

    override fun endEffortOnStop(t: Long, fromConsole: Boolean): Boolean {
        val start = incStart
        if (phase != FitnessTestPhase.Incremental || start == null || t - start < config.minIncrementalSeconds * 1000L) return false
        rampUpdate(t)
        flags += FitnessTestFlag.EndedWithStop
        val source = if (fromConsole) "the belt was stopped or paused from the console" else "STOP was pressed in the app"
        endEffort(
            t, FitnessEndReason.Exhausted,
            "$source after ${(t - start) / 1000} s of the ramp: counted as exhaustion; the belt stopped, so there was no cool-down",
            coolDown = false,
        )
        return true
    }

    override fun closeMeasurements(t: Long) {
        if (incStart != null && stageIndex >= 0) closeStage(t, completed = false)
    }

    /** End-of-stage heart rate of the last completed stage minus [n] stages earlier, if the load rose at every stage. */
    private fun plateauRiseOverLastStages(): Double? {
        val n = config.plateauStages
        val done = stages.filter { it.completed }
        if (n < 1 || done.size < n + 1) return null
        val window = done.takeLast(n + 1)
        for (i in 1 until window.size) {
            val rose = window[i].inclinePercent > window[i - 1].inclinePercent + 1e-9 || window[i].speedKmh > window[i - 1].speedKmh + 1e-9
            if (!rose) return null
        }
        val a = window.last().heartRateEndBpm ?: return null
        val b = window.first().heartRateEndBpm ?: return null
        return FitnessFormulas.round1(a - b)
    }

    override fun computeOutputs(t: Long, reason: FitnessEndReason): FitnessTestOutputs? {
        if (reason != FitnessEndReason.Exhausted) return null
        val start = incStart ?: return null
        val rampSeconds = (t - start) / 1000.0
        if (rampSeconds < config.minIncrementalSeconds) {
            flags += FitnessTestFlag.IncrementalPhaseTooShort
            return null
        }
        val cov = hr.coverage(start, t)
        coverage = FitnessFormulas.round2(cov)
        val last30 = hr.mean(t - 30_000, t)
        val firstStage = hr.mean(start, start + stageMs)
        val usable = cov >= config.minHeartRateCoverage && last30 != null && firstStage != null &&
            hr.coverage(t - 30_000, t) >= config.minHeartRateCoverage && last30 - firstStage >= config.minMaxTestHeartRateRiseBpm
        when {
            usable -> peakHr = peakHeartRate(t, t)
            cov < config.noHeartRateCoverage -> flags += FitnessTestFlag.NoValidHeartRate
            else -> flags += FitnessTestFlag.HeartRateUnreliable
        }
        val peak = peakHr
        if (peak != null) {
            plateauRise = plateauRiseOverLastStages()
            plateau = plateauRise?.let { it < config.plateauMaxRiseBpm } ?: false
        }
        val short = rampSeconds < config.maximalMinIncrementalSeconds
        if (short) flags += FitnessTestFlag.ShortIncrementalPhase
        val verdict = FitnessTestRecalculation.effortVerdict(peak, plateau, short, subject.hrMaxBpm, config)
        verdict.flag?.let { flags += it }
        effort = verdict.effort
        candidate = peak?.roundToInt()
        // VO2max from the real load of the last minute; from the stages if the samples do not cover it.
        val estimate = WalkingTestVo2max.fromSamples(effortSamples, at(t)) ?: WalkingTestVo2max.fromStages(
            stages.map { StageLoad(it.speedKmh, it.inclinePercent, it.durationSeconds) } +
                StageLoad(speedOf(stageIndex), inclineOf(stageIndex), ((t - stageStartT) / 1000).toInt()),
        )
        return estimate?.let { FitnessTestRecalculation.walkOutputs(it, subject.ageYears, subject.sex, effort, capReached, candidate) }
    }

    override fun computeThresholds(t: Long, reason: FitnessEndReason, outputs: FitnessTestOutputs?): ThresholdResult? {
        if (reason != FitnessEndReason.Exhausted || incStart == null) return null
        return effortThresholds(t, outputs)
    }

    /** The peak with the seconds after the exhaustion: when it is higher, the candidate, the effort and the VO2max bound follow it. */
    override fun refineAfterEffort(t: Long) {
        val end = effortEndT ?: return
        val start = incStart ?: return
        val old = peakHr ?: return
        val peak = peakHeartRate(end, t) ?: return
        if (peak <= old) return
        peakHr = peak
        val short = (end - start) / 1000.0 < config.maximalMinIncrementalSeconds
        val verdict = FitnessTestRecalculation.effortVerdict(peak, plateau, short, subject.hrMaxBpm, config)
        flags.removeAll(FitnessTestRecalculation.PEAK_FLAGS)
        verdict.flag?.let { flags += it }
        effort = verdict.effort
        candidate = peak.roundToInt()
        outputs = outputs?.let { FitnessTestRecalculation.withWalkEffort(it, verdict.effort, capReached, candidate) }
    }

    override fun measurements(): FitnessTestMeasurements {
        val start = incStart
        val end = effortEndT
        return MaxHeartRateWalkMeasurements(
            warmUpSeconds = ((start ?: end ?: 0L) / 1000).toInt(),
            rampSeconds = if (start != null && end != null) ((end - start) / 1000).toInt() else 0,
            startSpeedKmh = rampSpeed,
            startInclinePercent = rampIncline,
            inclineIncrementPercent = config.walkTestInclineIncrementPercent,
            inclineCapPercent = inclineCapPercent,
            speedIncrementKmh = if (start != null) speedStep else Acsm.walkingSpeedStepForGrade(rampSpeed, inclineCapPercent, config.walkTestInclineIncrementPercent),
            speedCapKmh = speedCapKmh,
            stageSeconds = config.stageSeconds,
            capReached = capReached,
            stages = stages.toList(),
            peakHeartRateBpm = peakHr,
            heartRateCoverage = coverage,
            heartRatePlateau = plateau,
            plateauRiseBpm = plateauRise,
            hrMaxCandidateBpm = candidate,
            effort = effort,
        )
    }

    override fun decorate(p: FitnessTestProgress, t: Long): FitnessTestProgress = when (phase) {
        FitnessTestPhase.WarmUp -> p.copy(
            instruction = FitnessInstruction.WarmUpWalk,
            phaseRemainingSeconds = ceilSeconds(max(0L, warmUp.endMs - t)),
            nextStageSpeedKmh = rampSpeed,
            nextStageInclinePercent = rampIncline,
            canReportExhaustion = false,
        )
        else -> {
            val atCap = capReached && nominalSpeedOf(stageIndex + 1) > speedCapKmh + 1e-9
            p.copy(
                instruction = if (capReached) FitnessInstruction.HoldTopSpeed else FitnessInstruction.WalkUphillUntilExhausted,
                stageNumber = stageIndex + 1,
                stageRemainingSeconds = if (atCap) null else ceilSeconds(max(0L, stageStartT + stageMs - t)),
                nextStageSpeedKmh = if (atCap) null else speedOf(stageIndex + 1),
                nextStageInclinePercent = if (atCap) null else inclineOf(stageIndex + 1),
                canReportExhaustion = true,
                capReached = capReached,
            )
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
