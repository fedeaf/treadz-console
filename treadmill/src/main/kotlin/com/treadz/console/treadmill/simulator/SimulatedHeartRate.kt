package com.treadz.console.treadmill.simulator

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the simulated runner's body "feels" during one simulated second. */
data class SimulatedBodyInput(
    /** Real belt speed (with the ramp). */
    val speedKmh: Double,
    val inclinePercent: Double,
    /** The belt is running (not idle, counting down, paused, stopping or stopped by the key). */
    val beltRunning: Boolean,
    /** Seconds since the model was attached to the simulator (counts every simulated second while connected). */
    val second: Long,
)

/**
 * Heart rate the simulated treadmill relays from a chest strap, computed once per simulated second. Return 0 for
 * "no heart-rate monitor" (what the console sends when the strap is off or absent).
 *
 * Implementations must be deterministic so the tests are reproducible. Tests can also pass a lambda to script a
 * particular heart-rate curve.
 */
fun interface SimulatedHeartRateModel {
    fun nextSecond(input: SimulatedBodyInput): Int
}

/**
 * A simple, deterministic physiological heart-rate model for the simulator.
 *
 * **Steady state.** The oxygen demand of the current speed and grade is estimated with the ACSM metabolic equations
 * (ACSM's Guidelines for Exercise Testing and Prescription):
 *  - walking (below [runningFromKmh]): VO2 = 0.1·S + 1.8·S·G + 3.5
 *  - running: VO2 = 0.2·S + 0.9·S·G + 3.5
 *
 * with S in m/min and G the grade as a fraction, VO2 in ml·kg⁻¹·min⁻¹. The steady-state heart rate follows the
 * usual %HRR ≈ %VO2R relation: HR = rest + (max − rest) · (VO2 − 3.5) / (VO2max − 3.5), capped at [maxBpm].
 * With the belt stopped the target is [restingBpm].
 *
 * **Dynamics.** The heart rate approaches the steady-state value as a first-order response ([tauUpSeconds] when it
 * rises, [tauDownSeconds] when it falls). An optional cardiovascular drift adds [driftBpmPerMinute] for every minute
 * the belt has been running. Optional noise of ±[noiseBpm] uses a fixed [seed].
 *
 * **Exhaustion.** [secondsAboveVo2max] counts how long the demand has been above [vo2maxMlKgMin] without a break
 * (reset when it drops below); tests of a maximal test use it to decide when the simulated runner "is exhausted".
 *
 * **Strap removed.** After [takeOffStrap] the console keeps reporting plausible values (73-80 bpm, like the real
 * console) for [strapOffPlausibleSeconds], then 0. [putOnStrap] restores the real heart rate. With
 * `strapPaired = false` the model always reports 0 (no strap at all).
 */
class PhysiologicalHeartRateModel(
    val restingBpm: Int = 70,
    val maxBpm: Int = 180,
    val vo2maxMlKgMin: Double = 45.0,
    val tauUpSeconds: Double = 30.0,
    val tauDownSeconds: Double = 45.0,
    val driftBpmPerMinute: Double = 0.0,
    val noiseBpm: Int = 1,
    val runningFromKmh: Double = 7.5,
    seed: Long = 7,
    strapPaired: Boolean = true,
    /** How long the console keeps reporting plausible values after the strap is taken off. */
    var strapOffPlausibleSeconds: Int = 90,
) : SimulatedHeartRateModel {

    private val random = java.util.Random(seed)
    private val lock = Any()
    private var hr = restingBpm.toDouble()
    private var runningSeconds = 0L
    private var strapOn = strapPaired
    /** Seconds since the strap came off; an unpaired strap has been "off" forever (no plausible values at all). */
    private var strapOffSeconds = if (strapPaired) 0 else Int.MAX_VALUE / 2
    private var ghost = 76
    private var aboveMax = 0

    /** Seconds in a row the oxygen demand has been above [vo2maxMlKgMin]. */
    val secondsAboveVo2max: Int get() = synchronized(lock) { aboveMax }

    /** The true heart rate of the simulated body (what a strap in contact would measure), without noise. */
    val trueHeartRate: Double get() = synchronized(lock) { hr }

    /** Takes the strap off: plausible values for [strapOffPlausibleSeconds], then 0. */
    fun takeOffStrap() = synchronized(lock) {
        strapOn = false
        strapOffSeconds = 0
    }

    fun putOnStrap() = synchronized(lock) { strapOn = true }

    override fun nextSecond(input: SimulatedBodyInput): Int = synchronized(lock) {
        val demand = if (input.beltRunning) vo2Demand(input.speedKmh, input.inclinePercent, runningFromKmh) else REST_VO2
        val fraction = ((demand - REST_VO2) / (vo2maxMlKgMin - REST_VO2)).coerceIn(0.0, 1.0)
        val target = restingBpm + (maxBpm - restingBpm) * fraction
        val tau = if (target >= hr) tauUpSeconds else tauDownSeconds
        hr += (target - hr) * (1 - exp(-1.0 / tau))
        aboveMax = if (input.beltRunning && demand > vo2maxMlKgMin) aboveMax + 1 else 0
        if (input.beltRunning) runningSeconds++ else runningSeconds = 0
        val drift = driftBpmPerMinute * runningSeconds / 60.0
        val noise = if (noiseBpm > 0) random.nextInt(2 * noiseBpm + 1) - noiseBpm else 0
        val measured = min(maxBpm.toDouble(), hr + drift).roundToInt() + noise

        if (strapOn) return@synchronized max(1, measured)
        if (strapOffSeconds < Int.MAX_VALUE / 2) strapOffSeconds++
        if (strapOffSeconds > strapOffPlausibleSeconds) return@synchronized 0
        // What the console shows with no contact: wandering values in 73-80.
        ghost = (ghost + random.nextInt(3) - 1).coerceIn(73, 80)
        ghost
    }

    companion object {
        /** Resting oxygen uptake (1 MET), ml·kg⁻¹·min⁻¹. */
        const val REST_VO2 = 3.5

        /** ACSM estimate of the oxygen demand, ml·kg⁻¹·min⁻¹, of walking or running at [speedKmh] and [inclinePercent]. */
        fun vo2Demand(speedKmh: Double, inclinePercent: Double, runningFromKmh: Double = 7.5): Double {
            val s = speedKmh * 1000.0 / 60.0
            val g = inclinePercent / 100.0
            return if (speedKmh < runningFromKmh) 0.1 * s + 1.8 * s * g + REST_VO2 else 0.2 * s + 0.9 * s * g + REST_VO2
        }
    }
}
