package com.treadz.console.programs.fitness

import com.treadz.console.programs.heartrate.StrapWindowConfig
import com.treadz.console.programs.heartrate.StrapWindowJudge

/**
 * Timings, thresholds and speeds of the fitness tests. The defaults are the protocol; tests of the code may change
 * them, the app should not. Times are test time (the injected clock, from the moment the belt is confirmed running).
 */
data class FitnessTestConfig(
    /** Longest the runner waits before looking at the clock again. */
    val tickMs: Long = 250,
    /** "+1": how much longer the current warm-up step or the cool-down becomes ([FitnessTestRunner.extendPhase]). */
    val extendPhaseSeconds: Int = 60,

    // ---------------------------------------------------------------- shared heart-rate rules
    /** A reading below this (or 0) is not a heart rate. */
    val minPlausibleBpm: Int = 30,
    /** Ebbeling: no valid heart rate for longer than this ends the test. */
    val heartRateLostSeconds: Int = 5,
    /** Ebbeling: safety stop when the mean of this window exceeds [safetyFractionOfHrMax] of the user's maximum. */
    val safetyWindowSeconds: Int = 5,
    val safetyFractionOfHrMax: Double = 0.85,

    // ---------------------------------------------------------------- Ebbeling
    val ebbelingMinSpeedKmh: Double = 3.2,
    val ebbelingMaxSpeedKmh: Double = 7.2,
    val ebbelingDefaultStartSpeedKmh: Double = 4.8,
    /** Heart-rate band of stage 1, as fractions of the age-predicted maximum (220 − age). */
    val bandLowFraction: Double = 0.50,
    val bandHighFraction: Double = 0.70,
    /** Mid-stage adjustments aim inside the band by this margin, so the final check does not sit on an edge. */
    val bandMarginBpm: Int = 3,
    val stage1Seconds: Int = 240,
    /** First automatic adjustment no earlier than this (the heart rate needs about a minute to respond). */
    val stage1FirstCheckSeconds: Int = 60,
    /** Minimum time between two adjustments (heart-rate response time). */
    val stage1SettleSeconds: Int = 45,
    /** The speed is held constant for this long before stage 1 ends (and after an end-of-stage adjustment). */
    val stage1FreezeSeconds: Int = 90,
    /** End-of-stage adjustments allowed when the heart rate is outside the band at the end of stage 1. */
    val stage1MaxExtensions: Int = 2,
    /** Heart-rate window evaluated by a mid-stage adjustment. */
    val adjustWindowSeconds: Int = 10,
    val smallStepKmh: Double = 0.4,
    val largeStepKmh: Double = 0.8,
    /** A heart rate further than this from the target zone gets the large step. */
    val largeStepBeyondBpm: Int = 15,
    val stage2Seconds: Int = 240,
    val stage2GradePercent: Double = 5.0,
    val stage2ExtensionSeconds: Int = 60,
    val stage2MaxExtensions: Int = 2,
    /** Steady state: highest minus lowest [steadyBucketSeconds]-s mean over the last [steadyWindowSeconds]. */
    val steadyWindowSeconds: Int = 120,
    val steadyBucketSeconds: Int = 5,
    val steadyMaxVariationBpm: Double = 5.0,
    /** Steady-state heart rate = mean of this many final seconds; also the stage 1 end heart rate. */
    val steadyMeanSeconds: Int = 30,
    /** Plausibility: stage 2 heart rate must exceed the stage 1 end heart rate by at least this. */
    val minGradeRiseBpm: Double = 5.0,
    val ebbelingCoolDownSeconds: Int = 180,
    val ebbelingCoolDownMaxSpeedKmh: Double = 4.0,

    // ---------------------------------------------------------------- maximal incremental test
    val maxTestMinStartSpeedKmh: Double = 6.0,
    val maxTestMaxStartSpeedKmh: Double = 10.0,
    val maxTestDefaultStartSpeedKmh: Double = 8.0,
    val maxTestInclinePercent: Double = 1.0,
    val warmUpWalkSeconds: Int = 120,
    val warmUpJogSeconds: Int = 180,
    val warmUpWalkSpeedKmh: Double = 5.5,
    /** The easy jog of the warm-up is this much slower than the start speed. */
    val warmUpJogBelowStartKmh: Double = 1.5,
    val stageSeconds: Int = 60,
    val speedIncrementKmh: Double = 0.5,
    /** Exhaustion earlier than this in the incremental phase makes the test invalid. */
    val minIncrementalSeconds: Int = 180,
    /** A maximal effort needs at least this long in the incremental phase. */
    val maximalMinIncrementalSeconds: Int = 300,
    /**
     * The peak heart rate is the highest [peakMeanSeconds]-s mean from this long before the end of the effort to the end
     * of the test (the heart rate keeps rising for a few seconds after the user stops: the cool-down counts).
     */
    val peakWindowSeconds: Int = 120,
    val peakMeanSeconds: Int = 5,
    /** Maximal effort: peak heart rate within this many bpm of the user's maximum. */
    val effortHrMarginBpm: Int = 10,
    /**
     * A maximal test whose final peak heart rate is below this fraction of the maximum in use is not a maximal effort,
     * whatever its plateau or duration: it gives no VO2max and no MAS, not even as lower bounds
     * ([OutputsWithheldReason.PeakHeartRateBelowMinimum]). Does not apply without a usable heart rate.
     */
    val minPeakFractionOfHrMax: Double = 0.85,
    /**
     * Heart-rate plateau: the heart rate at the end of the last completed stage rose by less than [plateauMaxRiseBpm]
     * compared with the end of the stage [plateauStages] stages earlier, although the speed rose by the full
     * increments in between. Together with a confirmed exhaustion it counts as a maximal effort even when the peak
     * is more than [effortHrMarginBpm] below the user's maximum.
     */
    val plateauStages: Int = 2,
    val plateauMaxRiseBpm: Double = 3.0,
    /** Fraction of the incremental phase that must have a valid heart rate for it to be used. */
    val minHeartRateCoverage: Double = 0.8,
    /** Below this coverage there is simply "no heart rate". */
    val noHeartRateCoverage: Double = 0.2,
    /** The heart rate of the last 30 s must exceed the first stage's by this much (strap really measuring). */
    val minMaxTestHeartRateRiseBpm: Double = 10.0,
    val maxTestCoolDownSeconds: Int = 300,
    val maxTestCoolDownSpeedKmh: Double = 5.0,

    // ---------------------------------------------------------------- strap check (both maximal tests, warm-up)
    /** The check starts this long into the warm-up (it needs a minute of beats)... */
    val strapCheckFromSeconds: Int = 60,
    /** ...and needs at least this many beats of RR. */
    val strapCheckMinBeats: Int = 30,
    /** The strap is poor when the artifacts exceed this share of the RR received so far in the warm-up, %. */
    val strapCheckMaxArtifactPercent: Double = 5.0,

    // ---------------------------------------------------------------- peak heart rate (both maximal tests)
    /**
     * A 5-s window of the peak search counts only if the strap's readings in it are not doubtful against the strap's RR
     * (see [StrapWindowJudge]); a window that cannot be judged (the console's heart rate, no RR) counts as before.
     */
    val strapWindow: StrapWindowConfig = StrapWindowConfig(),

    // ---------------------------------------------------------------- maximal heart-rate walking test (modified Balke)
    // It reuses the stage length, the 3-min / 5-min incremental thresholds, the peak window and mean, the effort margin,
    // the plateau rule and the heart-rate coverage rules of the maximal incremental test above.
    /** Ramp start: fixed values, not individualised; the user may change them before starting or during the warm-up. */
    val walkTestDefaultSpeedKmh: Double = 5.5,
    val walkTestDefaultInclinePercent: Double = 5.0,
    /** Walking speeds the ramp may start at. */
    val walkTestMinStartSpeedKmh: Double = 3.0,
    val walkTestMaxStartSpeedKmh: Double = 7.5,
    val walkTestMaxStartInclinePercent: Double = 15.0,
    /** Warm-up: 1 min at 75 % of the ramp speed and 0 %, then 1 min at 80 % and 1 %. */
    val walkTestWarmUpStepSeconds: Int = 60,
    val walkTestWarmUpFraction1: Double = 0.75,
    val walkTestWarmUpFraction2: Double = 0.80,
    val walkTestWarmUpIncline2Percent: Double = 1.0,
    /** +1 % per minute up to this (or the user's incline limit); then the speed rises by the equivalent of +1 %. */
    val walkTestInclineIncrementPercent: Double = 1.0,
    val walkTestMaxInclinePercent: Double = 18.0,
    val walkTestCoolDownSeconds: Int = 180,
    val walkTestCoolDownSpeedKmh: Double = 5.0,
)
