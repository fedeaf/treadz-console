package com.treadz.console.analysis.fitness

import com.treadz.console.analysis.samples.LoadAverage
import com.treadz.console.analysis.samples.WorkoutSample

/** One stage of a ramp as stored in a test result: its load and how long it lasted. */
data class StageLoad(val speedKmh: Double, val inclinePercent: Double, val durationSeconds: Int)

/**
 * VO2max of the maximal walking test (an incline ramp walked to exhaustion) from the ACSM equations.
 *
 * @property vo2maxMlKgMin Unrounded.
 * @property speedKmh Mean speed of the load used.
 * @property inclinePercent Mean incline of the load used.
 * @property aboveWalkingSpeeds The mean speed was above [Acsm.RUNNING_SPEED_THRESHOLD_KMH]: the walking equation was
 *   used beyond the speeds the app counts as walking (the test is walked by definition).
 * @property fromStages Computed from the stored stage records instead of the samples.
 * @property coveredSeconds Seconds of the window the data covered.
 */
data class WalkingTestVo2maxEstimate(
    val vo2maxMlKgMin: Double,
    val speedKmh: Double,
    val inclinePercent: Double,
    val aboveWalkingSpeeds: Boolean,
    val fromStages: Boolean,
    val coveredSeconds: Double,
)

/**
 * VO2max of the maximal walking test: the ACSM walking equation ([Acsm.walkingVo2OfMeans]) at the **mean load of the
 * last [WINDOW_SECONDS] before the end of the effort**, with the real speed and incline: `VO2 = 3.5 + 0.1·S + 1.8·S·G`
 * (S in m/min, G a fraction), **at any speed**. The test is walked by definition; with the equation of the speed (running
 * above 7 km/h, [Acsm.vo2]) a user who went past 7 km/h at the 18 % of the ramp's top would get a lower VO2max than by
 * stopping a minute earlier (at 18 %: 55.8 walking at 7.4 km/h, 49.3 running at 7.6), so the walking one is kept (since
 * the thresholds' algorithm version 5; until then the running one above 7.5 km/h).
 *
 * Why the last 60 s and not the final load: in a ramp with 1-min stages the oxygen uptake lags the load (its time
 * constant is 30-40 s), so the load of the final seconds overstates the VO2 reached; the mean of the last minute
 * compensates that lag. The ACSM equations predict the oxygen cost of steady walking and running; at exhaustion on a
 * ramp they are the usual way to turn the final treadmill load into a VO2max (the treadmill-time equations of Pollock
 * et al. 1976 and Foster et al. 1984 rest on the same idea).
 *
 * **SEE [SEE_ML_KG_MIN] (3.5 ml·kg⁻¹·min⁻¹) is an assumption**, in line with the standard errors of the treadmill
 * time equations (about 2.5-3.5: Pollock ML, Bohannon RL, Cooper KH, et al. A comparative analysis of four protocols
 * for maximal treadmill stress testing. Am Heart J. 1976;92(1):39-46; Foster C, Jackson AS, Pollock ML, et al.
 * Generalized equations for predicting functional capacity from treadmill performance. Am Heart J.
 * 1984;107(6):1229-1234). Like the 4.0 of the running test, it is only used to weigh the tests against each other.
 * Holding the handrails lowers the real cost of the load: the protocol forbids it.
 */
object WalkingTestVo2max {

    /** Assumed standard error of the estimate, ml·kg⁻¹·min⁻¹ (see the class). */
    const val SEE_ML_KG_MIN = 3.5

    /** The load is averaged over the last this many seconds of the effort. */
    const val WINDOW_SECONDS = 60

    /** Less than this many seconds of data in the window: no estimate from the samples. */
    const val MIN_COVERED_SECONDS = 30

    /**
     * From the samples of the workout (in time order, any rate): the mean load over (effortEnd − 60 s, effortEnd].
     * Returns `null` when the samples cover less than [MIN_COVERED_SECONDS] of that window.
     */
    fun fromSamples(samples: List<WorkoutSample>, effortEndMillis: Long, windowSeconds: Int = WINDOW_SECONDS): WalkingTestVo2maxEstimate? {
        val load = LoadAverage.over(samples, effortEndMillis - windowSeconds * 1000L, effortEndMillis) ?: return null
        if (load.coveredMillis < MIN_COVERED_SECONDS * 1000L) return null
        val vo2 = Acsm.walkingVo2OfMeans(load.speedKmh, load.speedTimesInclinePercent)
        return WalkingTestVo2maxEstimate(vo2, load.speedKmh, load.inclinePercent, Acsm.isRunning(load.speedKmh), false, load.coveredMillis / 1000.0)
    }

    /**
     * From the stage records of the result (in order, the last one being the stage in which the effort ended), for a
     * result whose workout samples are not available: the load of the last 60 s weighted by the seconds each stage
     * contributes to them. Uses the nominal stage loads (the samples are better: the incline motor lags). Returns `null`
     * without any stage time.
     */
    fun fromStages(stages: List<StageLoad>, windowSeconds: Int = WINDOW_SECONDS): WalkingTestVo2maxEstimate? {
        var left = windowSeconds.toDouble()
        var covered = 0.0
        var sumS = 0.0
        var sumG = 0.0
        var sumSG = 0.0
        for (stage in stages.asReversed()) {
            if (left <= 0.0) break
            val take = minOf(left, stage.durationSeconds.toDouble().coerceAtLeast(0.0))
            if (take <= 0.0) continue
            covered += take
            left -= take
            sumS += stage.speedKmh * take
            sumG += stage.inclinePercent * take
            sumSG += stage.speedKmh * stage.inclinePercent * take
        }
        if (covered <= 0.0) return null
        val speed = sumS / covered
        val incline = sumG / covered
        val vo2 = Acsm.walkingVo2OfMeans(speed, sumSG / covered)
        return WalkingTestVo2maxEstimate(vo2, speed, incline, Acsm.isRunning(speed), true, covered)
    }
}
