package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.ValueRange
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Clamps commands to the treadmill range and to the user's safety limits. */
object SafetyClamp {

    /** Documented Z-Tech ranges, only for when they cannot be read from the treadmill. */
    val DEFAULT_SPEED_RANGE = ValueRange(0.5, 20.0, 0.1)
    val DEFAULT_INCLINE_RANGE = ValueRange(0.0, 18.0, 1.0)

    fun speed(requestedKmh: Double, range: ValueRange, limits: SafetyLimits): Double {
        // A limit below the treadmill minimum cannot lead to a speed the treadmill does not accept.
        val upper = limits.maxSpeedKmh?.let { min(range.max, max(it, range.min)) } ?: range.max
        return roundToStep(requestedKmh.coerceIn(range.min, upper), range, upper)
    }

    fun incline(requestedPercent: Double, range: ValueRange, limits: SafetyLimits): Double {
        val upper = limits.maxInclinePercent?.let { min(range.max, max(it, range.min)) } ?: range.max
        return roundToStep(requestedPercent.coerceIn(range.min, upper), range, upper)
    }

    /** Snaps to the treadmill step without ever exceeding [upper] (rounding cannot skip a limit). */
    private fun roundToStep(v: Double, range: ValueRange, upper: Double): Double {
        if (range.step <= 0.0) return v
        var steps = ((v - range.min) / range.step).roundToLong()
        while (steps > 0 && range.min + steps * range.step > upper + 1e-9) steps--
        val snapped = range.min + steps * range.step
        // Removes floating point noise (3.0000000000000004).
        return (Math.round(snapped * 100.0) / 100.0).coerceIn(range.min, range.max)
    }
}
