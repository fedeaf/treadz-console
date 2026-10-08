package com.treadz.console.programs.fitness

import kotlin.math.max
import kotlin.math.min

/**
 * Heart-rate samples of a test against test time (ms). Readings arrive about twice per second (every value repeated
 * on Android); all statistics are time-based, so the repetition does not matter. `null` samples are invalid readings.
 * Not thread-safe: the runner guards it with its lock.
 */
internal class HeartRateLog {

    private val times = ArrayList<Long>()
    private val values = ArrayList<Int?>()

    /** Test time of the last valid sample, or `null` if there has been none. */
    var lastValidAt: Long? = null
        private set

    val hasAnyValid: Boolean get() = lastValidAt != null

    fun add(t: Long, bpm: Int?) {
        if (times.isNotEmpty() && t < times.last()) return
        times += t
        values += bpm
        if (bpm != null) lastValidAt = t
    }

    /** Mean of the valid samples with time in (from, to]; `null` without any. */
    fun mean(from: Long, to: Long): Double? {
        var sum = 0.0
        var n = 0
        forRange(from, to) { v ->
            if (v != null) {
                sum += v
                n++
            }
        }
        return if (n == 0) null else sum / n
    }

    /**
     * Highest minus lowest mean of consecutive [bucketMs] buckets covering (from, to]. `null` when fewer than 80 % of
     * the buckets have data (not enough to judge a steady state).
     */
    fun bucketVariation(from: Long, to: Long, bucketMs: Long): Double? {
        val count = ((to - from) / bucketMs).toInt()
        if (count <= 0) return null
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var filled = 0
        for (i in 0 until count) {
            val m = mean(from + i * bucketMs, from + (i + 1) * bucketMs) ?: continue
            filled++
            lo = min(lo, m)
            hi = max(hi, m)
        }
        if (filled < count * 0.8) return null
        return hi - lo
    }

    /** Highest sliding [windowMs] mean among windows ending in (from, to]; `null` without valid samples. */
    fun maxSlidingMean(from: Long, to: Long, windowMs: Long): Double? {
        var best: Double? = null
        for (i in times.indices) {
            val t = times[i]
            if (t <= from || t > to || values[i] == null) continue
            val m = mean(max(from, t - windowMs), t) ?: continue
            if (best == null || m > best) best = m
        }
        return best
    }

    /** Fraction of the 1-s slots of (from, to] that hold at least one valid sample. */
    fun coverage(from: Long, to: Long): Double {
        val slots = ((to - from) / 1000).toInt()
        if (slots <= 0) return 0.0
        val seen = BooleanArray(slots)
        forRangeIndexed(from, to) { t, v ->
            if (v != null) {
                val slot = ((t - from - 1) / 1000).toInt()
                if (slot in 0 until slots) seen[slot] = true
            }
        }
        return seen.count { it }.toDouble() / slots
    }

    /** True when there are valid samples in (from, to] and all of them have the same value. */
    fun isFlat(from: Long, to: Long): Boolean {
        var first: Int? = null
        var flat = true
        forRange(from, to) { v ->
            if (v != null) {
                if (first == null) first = v else if (v != first) flat = false
            }
        }
        return first != null && flat
    }

    private inline fun forRange(from: Long, to: Long, action: (Int?) -> Unit) {
        forRangeIndexed(from, to) { _, v -> action(v) }
    }

    private inline fun forRangeIndexed(from: Long, to: Long, action: (Long, Int?) -> Unit) {
        // Binary search for the first sample after [from].
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= from) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i < times.size && times[i] <= to) {
            action(times[i], values[i])
            i++
        }
    }
}
