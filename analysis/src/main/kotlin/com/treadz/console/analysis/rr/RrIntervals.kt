package com.treadz.console.analysis.rr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rules of the RR-interval filter and of the RMSSD.
 *
 * @property minRrMs RR intervals outside [minRrMs]..[maxRrMs] are artifacts and left out.
 * @property maxRrChange An interval that differs from the previous accepted one by more than this fraction is an
 *   artifact or ectopic beat.
 * @property minIntervals The RMSSD needs at least this many accepted intervals.
 */
data class RmssdRules(
    val minRrMs: Int = 300,
    val maxRrMs: Int = 2_000,
    val maxRrChange: Double = 0.2,
    val minIntervals: Int = 60,
)

/** Processing of RR intervals (ms between consecutive heartbeats). */
object RrIntervals {

    /**
     * RMSSD (root mean square of successive differences, ms) of [rrMs], the RR intervals of the window in time order.
     *
     * An interval outside [RmssdRules.minRrMs]..[RmssdRules.maxRrMs], or more than [RmssdRules.maxRrChange] away from
     * the previous accepted one, is left out and so is the difference across it (the next accepted interval starts a
     * new run). Returns `null` with fewer than [RmssdRules.minIntervals] accepted intervals or no usable difference.
     */
    fun rmssd(rrMs: List<Int>, rules: RmssdRules = RmssdRules()): Double? {
        var previous: Int? = null
        var accepted = 0
        var sumSquares = 0.0
        var differences = 0
        for (rr in rrMs) {
            if (rr < rules.minRrMs || rr > rules.maxRrMs) {
                previous = null
                continue
            }
            val p = previous
            if (p != null && abs(rr - p) > rules.maxRrChange * p) {
                previous = null
                continue
            }
            accepted++
            if (p != null) {
                val d = (rr - p).toDouble()
                sumSquares += d * d
                differences++
            }
            previous = rr
        }
        if (accepted < rules.minIntervals || differences == 0) return null
        return sqrt(sumSquares / differences)
    }
}
