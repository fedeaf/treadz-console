package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ebbeling single-stage treadmill walking test (Ebbeling et al. 1991), as run by the app.
 *
 * **Stage 1 (4 min, 0 %): find the walking speed.** It starts at the caller's speed (clamped to 3.2-7.2 km/h and the
 * user's limits). The goal is a heart rate in **50-70 % of 220 − age** (the original study's reference). Automatic
 * adjustment:
 *  - Checks at 60 s, 105 s and 150 s (every 45 s, the time the heart rate needs to respond) on the mean of the last
 *    10 s. Below `low + 3` bpm the speed goes up, above `high − 3` it goes down (aiming 3 bpm inside the band so the
 *    final check does not sit on an edge): 0.4 km/h, or 0.8 km/h when more than 15 bpm away.
 *  - No changes in the last 90 s: the speed is held constant.
 *  - At 4:00 the mean of the last 30 s is checked against the real band. Outside it, if the speed can still move,
 *    it is adjusted once more and stage 1 is extended by another 90 s at constant speed (at most twice: stage 1
 *    lasts 4-7 min).
 *  - Still **below** the band at the fastest speed allowed: the test continues at that speed with the warning
 *    [FitnessTestFlag.HeartRateBelowBand]. The equation's inputs stay within the range it was built on (speed
 *    ≤ 4.5 mph); the band only guides the choice of speed, and the 5 % stage still raises the heart rate (checked by
 *    the plausibility rule). A very fit user is the typical case.
 *  - Still **above** the band at the slowest speed (or after the extensions): the test ends invalid
 *    ([FitnessTestFlag.HeartRateAboveBand]). The 5 % stage would push the heart rate further up, towards the safety
 *    limit, and the user is outside the population the equation describes.
 *
 * **The user may choose the pace** (the original protocol lets the participant pick a comfortable speed): until the
 * freeze (the first 150 s of a 240 s stage 1) the speed can be changed with the app's controls
 * ([FitnessTestRunner.adjustSpeed]) or the console's buttons. The speed the user sets (within 3.2-7.2 km/h and the
 * safety limits; a console speed outside that range, or any incline change, still invalidates the test) becomes the
 * base speed, is recorded as a [SpeedAdjustment] with its [SpeedAdjustmentOrigin] and flagged
 * [FitnessTestFlag.SpeedAdjustedByUser]. **From then on the automatic mid-stage corrections stop**, so the protocol
 * never fights the user; the check at the end of stage 1 still applies unchanged (outside the band it adjusts once
 * more and extends stage 1 by 90 s at constant speed, at most twice). The intent of the protocol stays: constant
 * speed in the final part of stage 1 and in stage 2. After the freeze, in stage 2, or with an incline change, a manual
 * change breaks the test as before.
 *
 * **Stage 2 (4 min, 5 %, same speed).** The heart rate is recorded every minute (minute records). **Steady state**:
 * the highest minus the lowest 5-s mean over the last 2 min is at most 5 bpm; then the steady-state heart rate is the
 * mean of the last 30 s. Otherwise the stage is extended by 1 min (at most twice, so ≤ 6 min); still not steady →
 * invalid ([FitnessTestFlag.NoSteadyState]).
 *
 * **Plausibility** (the console relays the strap without a contact indicator and keeps sending plausible values for
 * 1-2 min after the strap comes off): the steady-state heart rate must exceed the stage 1 end heart rate by ≥ 5 bpm
 * (a 5 % grade at walking speed raises the oxygen demand by ~7 ml·kg⁻¹·min⁻¹, i.e. ≥ ~10 bpm even for very fit
 * people), and stage 2 must not be flat (one single value). Either failure invalidates the result: a heart rate that
 * does not respond to the load does not measure it, and the equation would then overestimate VO2max.
 *
 * **Safety stop** (any time): mean heart rate of 5 s above 85 % of the user's maximum ([FitnessSubject.hrMaxBpm]),
 * or no valid heart rate for more than 5 s, or the user ends the test → invalid, cool-down. A manual speed or
 * incline change (or a speed change after the freeze) breaks the constant-speed protocol → invalid, cool-down.
 *
 * **Cool-down**: 3 min at min(test speed, 4 km/h), 0 %, then the belt is stopped.
 */
internal class EbbelingProtocol(
    id: String,
    subject: FitnessSubject,
    config: FitnessTestConfig,
    requestedStartSpeedKmh: Double,
    maxSpeedLimitKmh: Double?,
    maxInclineLimitPercent: Double?,
    heartRateKind: HeartRateSourceKind,
    clampSpeed: (Double) -> Double,
) : FitnessProtocol(
    FitnessTestType.EbbelingWalk, PROTOCOL_VERSION, id, subject, config, requestedStartSpeedKmh,
    maxSpeedLimitKmh, maxInclineLimitPercent, heartRateKind, clampSpeed,
) {
    private val agePredicted = FitnessFormulas.agePredictedHrMax(subject.ageYears)
    val bandLow = (config.bandLowFraction * agePredicted).roundToInt()
    val bandHigh = (config.bandHighFraction * agePredicted).roundToInt()
    val safetyLimit = floor(config.safetyFractionOfHrMax * subject.hrMaxBpm).toInt()
    private val minSpeed = clampSpeed(config.ebbelingMinSpeedKmh)
    private val maxSpeed = clampSpeed(config.ebbelingMaxSpeedKmh)
    override val startSpeedKmh: Double = clampSpeed(requestedStartSpeedKmh.coerceIn(config.ebbelingMinSpeedKmh, config.ebbelingMaxSpeedKmh))
    override val coolDownSeconds: Int get() = config.ebbelingCoolDownSeconds
    override val coolDownMaxSpeedKmh: Double get() = config.ebbelingCoolDownMaxSpeedKmh

    private var speed = startSpeedKmh
    private var userAdjusted = false
    private var stage1End = 0L
    private var nextCheckT = 0L
    private var stage1Extensions = 0
    private var stage2Start: Long? = null
    private var stage2End = 0L
    private var stage2Extensions = 0
    private var testSpeed: Double? = null
    private val adjustments = mutableListOf<SpeedAdjustment>()
    private var stage1EndHr: Double? = null
    private var steadyHr: Double? = null
    private var variation: Double? = null
    private var rise: Double? = null
    private var steadyStatus = SteadyStateStatus.Pending

    override fun onBegin() {
        if (subject.ageYears !in FitnessFormulas.EBBELING_VALIDATED_AGES) flags += FitnessTestFlag.OutsideValidatedPopulation
        if (abs(startSpeedKmh - requestedStartSpeedKmh) > 0.049) flags += FitnessTestFlag.StartSpeedClamped
        enterPhase(0, FitnessTestPhase.Stage1)
        targetSpeed = speed
        targetIncline = 0.0
        stage1End = config.stage1Seconds * 1000L
        nextCheckT = config.stage1FirstCheckSeconds * 1000L
    }

    override fun effortUpdate(t: Long, obs: Observation) {
        val lastValid = hr.lastValidAt ?: 0L
        if (t - lastValid > config.heartRateLostSeconds * 1000L) {
            flags += FitnessTestFlag.HeartRateLost
            endEffort(t, FitnessEndReason.HeartRateLost, "no valid heart rate for ${(t - lastValid) / 1000} s", coolDown = true)
            return
        }
        val recent = hr.mean(t - config.safetyWindowSeconds * 1000L, t)
        if (recent != null && recent > safetyLimit) {
            flags += FitnessTestFlag.HeartRateAboveSafetyLimit
            endEffort(t, FitnessEndReason.HeartRateAboveLimit, "mean heart rate ${FitnessFormulas.round2(recent)} over ${config.safetyWindowSeconds} s > limit $safetyLimit", coolDown = true)
            return
        }
        when (phase) {
            FitnessTestPhase.Stage1 -> stage1(t)
            FitnessTestPhase.Stage2 -> stage2(t)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ stage 1

    private fun stage1(t: Long) {
        if (t >= stage1End) {
            endOfStage1(t)
            return
        }
        val freezeFrom = stage1End - config.stage1FreezeSeconds * 1000L
        if (!userAdjusted && t >= nextCheckT && nextCheckT <= freezeFrom) {
            nextCheckT += config.stage1SettleSeconds * 1000L
            val m = hr.mean(t - config.adjustWindowSeconds * 1000L, t) ?: return
            val low = bandLow + config.bandMarginBpm
            val high = bandHigh - config.bandMarginBpm
            when {
                m < low -> adjust(t, up = true, large = low - m > config.largeStepBeyondBpm, hrNow = m)
                m > high -> adjust(t, up = false, large = m - high > config.largeStepBeyondBpm, hrNow = m)
            }
        }
    }

    /** The speed may still be changed by the user: first part of stage 1, before the freeze. */
    private fun speedChangeAllowed(t: Long) = phase == FitnessTestPhase.Stage1 && t < stage1End - config.stage1FreezeSeconds * 1000L

    override fun adjustSpeedByApp(t: Long, speedKmh: Double): Double? {
        if (!speedChangeAllowed(t)) return null
        val v = clampSpeed(speedKmh).coerceIn(minSpeed, maxSpeed)
        userSpeed(t, v, SpeedAdjustmentOrigin.App)
        return v
    }

    override fun onManualChange(t: Long, speedKmh: Double?, inclinePercent: Double?): Boolean {
        if (speedKmh != null && inclinePercent == null && speedChangeAllowed(t)) {
            val v = FitnessFormulas.round1(speedKmh)
            if (v >= minSpeed - 0.049 && v <= maxSpeed + 0.049) {
                userSpeed(t, v.coerceIn(minSpeed, maxSpeed), SpeedAdjustmentOrigin.Console)
                return false
            }
        }
        return super.onManualChange(t, speedKmh, inclinePercent)
    }

    /** The user's speed becomes the base speed; the automatic mid-stage corrections stop. */
    private fun userSpeed(t: Long, next: Double, origin: SpeedAdjustmentOrigin) {
        userAdjusted = true
        if (abs(next - speed) < 0.05) return
        val a = SpeedAdjustment((t / 1000).toInt(), speed, next, hr.mean(t - config.adjustWindowSeconds * 1000L, t)?.let(FitnessFormulas::round1), origin)
        adjustments += a
        speed = next
        targetSpeed = next
        flags += FitnessTestFlag.SpeedAdjustedByUser
        pendingEvents += FitnessTestEvent.SpeedAdjusted(at(t), a)
    }

    /** Changes the walking speed; false when it is already at the limit. */
    private fun adjust(t: Long, up: Boolean, large: Boolean, hrNow: Double): Boolean {
        val step = if (large) config.largeStepKmh else config.smallStepKmh
        val wanted = (if (up) speed + step else speed - step).coerceIn(minSpeed, maxSpeed)
        val next = clampSpeed(wanted).coerceIn(minSpeed, maxSpeed)
        if (abs(next - speed) < 0.05) return false
        val a = SpeedAdjustment((t / 1000).toInt(), speed, next, FitnessFormulas.round1(hrNow))
        adjustments += a
        speed = next
        targetSpeed = next
        flags += FitnessTestFlag.SpeedAdjustedToHeartRateBand
        pendingEvents += FitnessTestEvent.SpeedAdjusted(at(t), a)
        return true
    }

    private fun endOfStage1(t: Long) {
        val m = hr.mean(t - config.steadyMeanSeconds * 1000L, t)
        stage1EndHr = m?.let(FitnessFormulas::round1)
        if (m == null) {
            flags += FitnessTestFlag.HeartRateLost
            endEffort(t, FitnessEndReason.HeartRateLost, "no heart rate at the end of stage 1", coolDown = true)
            return
        }
        val canExtend = stage1Extensions < config.stage1MaxExtensions
        when {
            m < bandLow -> {
                if (canExtend && adjust(t, up = true, large = bandLow - m > config.largeStepBeyondBpm, hrNow = m)) {
                    extendStage1(t)
                } else {
                    flags += FitnessTestFlag.HeartRateBelowBand
                    startStage2(t)
                }
            }
            m > bandHigh -> {
                if (canExtend && adjust(t, up = false, large = m - bandHigh > config.largeStepBeyondBpm, hrNow = m)) {
                    extendStage1(t)
                } else {
                    flags += FitnessTestFlag.HeartRateAboveBand
                    endEffort(
                        t, FitnessEndReason.HeartRateBandUnreachable,
                        "heart rate ${FitnessFormulas.round1(m)} above the band $bandLow-$bandHigh at $speed km/h", coolDown = true,
                    )
                }
            }
            else -> startStage2(t)
        }
    }

    private fun extendStage1(t: Long) {
        stage1Extensions++
        stage1End = t + config.stage1FreezeSeconds * 1000L
        nextCheckT = Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ stage 2

    private fun startStage2(t: Long) {
        enterPhase(t, FitnessTestPhase.Stage2)
        stage2Start = t
        stage2End = t + config.stage2Seconds * 1000L
        testSpeed = speed
        targetSpeed = speed
        targetIncline = config.stage2GradePercent
    }

    private fun stage2(t: Long) {
        if (t < stage2End) return
        val v = hr.bucketVariation(t - config.steadyWindowSeconds * 1000L, t, config.steadyBucketSeconds * 1000L)
        variation = v?.let(FitnessFormulas::round1)
        if (v != null && v <= config.steadyMaxVariationBpm) {
            steadyStatus = SteadyStateStatus.Steady
            val mean = hr.mean(t - config.steadyMeanSeconds * 1000L, t)
            steadyHr = mean?.let(FitnessFormulas::round1)
            checkPlausibility(t)
            endEffort(t, FitnessEndReason.Completed, null, coolDown = true)
        } else if (stage2Extensions < config.stage2MaxExtensions) {
            stage2Extensions++
            stage2End += config.stage2ExtensionSeconds * 1000L
            steadyStatus = SteadyStateStatus.Extended
            flags += FitnessTestFlag.SteadyStateExtended
            pendingEvents += FitnessTestEvent.SteadyStateExtended(at(t), stage2Extensions, variation)
        } else {
            steadyStatus = SteadyStateStatus.Failed
            rise = riseAt(t)
            flags += FitnessTestFlag.NoSteadyState
            endEffort(t, FitnessEndReason.NoSteadyState, "variation ${variation ?: "unknown"} bpm over the last 2 min", coolDown = true)
        }
    }

    private fun riseAt(t: Long): Double? {
        val last = hr.mean(t - config.steadyMeanSeconds * 1000L, t) ?: return null
        val before = stage1EndHr ?: return null
        return FitnessFormulas.round1(last - before)
    }

    private fun checkPlausibility(t: Long) {
        val steady = steadyHr
        val before = stage1EndHr
        rise = if (steady != null && before != null) FitnessFormulas.round1(steady - before) else null
        val r = rise
        if (r == null || r < config.minGradeRiseBpm) flags += FitnessTestFlag.HeartRateNotResponsive
        if (hr.isFlat(stage2Start ?: t, t)) flags += FitnessTestFlag.HeartRateFlat
    }

    // ------------------------------------------------------------------ result

    override fun computeOutputs(t: Long, reason: FitnessEndReason): FitnessTestOutputs? {
        if (reason != FitnessEndReason.Completed) return null
        val v = testSpeed ?: return null
        val hrSteady = steadyHr ?: return null
        val vo2 = FitnessFormulas.round1(FitnessFormulas.ebbelingVo2max(FitnessFormulas.kmhToMph(v), hrSteady, subject.ageYears, subject.sex))
        return FitnessTestOutputs(
            vo2maxMlKgMin = vo2,
            vo2maxSeeMlKgMin = FitnessFormulas.EBBELING_SEE,
            method = Vo2MaxMethod.Ebbeling1991,
            classification = Vo2MaxNorms.classify(vo2, subject.ageYears, subject.sex),
        )
    }

    override fun measurements(): FitnessTestMeasurements {
        val end = effortEndT
        val s2 = stage2Start
        return EbbelingMeasurements(
            heartRateBandLowBpm = bandLow,
            heartRateBandHighBpm = bandHigh,
            heartRateSafetyLimitBpm = safetyLimit,
            testSpeedKmh = testSpeed,
            testSpeedMph = testSpeed?.let { FitnessFormulas.round2(FitnessFormulas.kmhToMph(it)) },
            stage1Seconds = (((s2 ?: end ?: 0L)) / 1000).toInt(),
            stage2Seconds = if (s2 != null && end != null) ((end - s2) / 1000).toInt() else 0,
            stage1Extensions = stage1Extensions,
            stage2Extensions = stage2Extensions,
            speedAdjustments = adjustments.toList(),
            stage1EndHeartRateBpm = stage1EndHr,
            steadyStateHeartRateBpm = steadyHr,
            steadyStateVariationBpm = variation,
            heartRateRiseBpm = rise,
        )
    }

    // ------------------------------------------------------------------ progress

    override fun decorate(p: FitnessTestProgress, t: Long): FitnessTestProgress = when (phase) {
        FitnessTestPhase.Stage1 -> {
            val locked = stage1Extensions > 0 || t >= stage1End - config.stage1FreezeSeconds * 1000L
            p.copy(
                instruction = if (locked) FitnessInstruction.WalkSpeedFixed else FitnessInstruction.WalkSpeedMayChange,
                stageNumber = 1,
                phaseRemainingSeconds = ceilSeconds(max(0L, stage1End - t)),
                heartRateBandLowBpm = bandLow,
                heartRateBandHighBpm = bandHigh,
                heartRateLimitBpm = safetyLimit,
                speedLocked = locked,
            )
        }
        else -> {
            val s2 = stage2Start ?: t
            val current = if (t - s2 >= config.steadyWindowSeconds * 1000L) {
                hr.bucketVariation(t - config.steadyWindowSeconds * 1000L, t, config.steadyBucketSeconds * 1000L)?.let(FitnessFormulas::round1)
            } else {
                null
            }
            p.copy(
                instruction = if (stage2Extensions > 0) FitnessInstruction.WalkUphillExtended else FitnessInstruction.WalkUphill,
                stageNumber = 2,
                phaseRemainingSeconds = ceilSeconds(max(0L, stage2End - t)),
                heartRateLimitBpm = safetyLimit,
                speedLocked = true,
                steadyState = steadyStatus,
                steadyStateVariationBpm = current,
                stage2Extensions = stage2Extensions,
            )
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
