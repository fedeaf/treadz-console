package com.treadz.console.analysis.rr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * An RR series after artifact correction.
 *
 * @property rrMs The intervals, artifacts replaced by interpolation.
 * @property artifact Which intervals were artifacts.
 */
class CleanedRr(val rrMs: DoubleArray, val artifact: BooleanArray) {
    val size: Int get() = rrMs.size
    val artifactCount: Int get() = artifact.count { it }
}

/**
 * Artifact correction of RR intervals for detrended fluctuation analysis.
 *
 * An interval is an artifact when it is outside [MIN_RR_MS]-[MAX_RR_MS] or more than [MAX_DEVIATION] (20 %) away from
 * the median of the [LOCAL_BEATS] (11) intervals centred on it (in-range intervals only). Artifacts are replaced by
 * linear interpolation between the nearest correct intervals (by beat index; at the ends, the nearest correct one).
 * The beat times are kept as they are. Artifacts bias α1 (towards uncorrelated values) even after correction, so the
 * share of corrected beats is counted per window (Rogers B, Giles D, Draper N, Mourot L, Gronwald T. Influence of
 * artefact correction and recording device type on the practical application of a non-linear heart rate variability
 * biomarker for aerobic threshold determination. Sensors. 2021;21(3):821).
 */
object RrCleaning {

    const val MIN_RR_MS = 300
    const val MAX_RR_MS = 2_000
    const val LOCAL_BEATS = 11
    const val MAX_DEVIATION = 0.20

    fun clean(rrMs: List<Int>): CleanedRr {
        val n = rrMs.size
        val raw = DoubleArray(n) { rrMs[it].toDouble() }
        val inRange = BooleanArray(n) { rrMs[it] in MIN_RR_MS..MAX_RR_MS }
        val artifact = BooleanArray(n)
        val half = LOCAL_BEATS / 2
        val buffer = DoubleArray(LOCAL_BEATS)
        for (i in 0 until n) {
            if (!inRange[i]) {
                artifact[i] = true
                continue
            }
            var m = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) if (inRange[j]) buffer[m++] = raw[j]
            val median = median(buffer, m)
            artifact[i] = abs(raw[i] - median) > MAX_DEVIATION * median
        }
        val out = raw.copyOf()
        var previousGood = -1
        var i = 0
        while (i < n) {
            if (!artifact[i]) {
                previousGood = i
                i++
                continue
            }
            var next = i
            while (next < n && artifact[next]) next++
            for (k in i until next) {
                out[k] = when {
                    previousGood >= 0 && next < n ->
                        raw[previousGood] + (raw[next] - raw[previousGood]) * (k - previousGood).toDouble() / (next - previousGood)
                    previousGood >= 0 -> raw[previousGood]
                    next < n -> raw[next]
                    else -> raw[k]
                }
            }
            i = next
        }
        return CleanedRr(out, artifact)
    }

    private fun median(values: DoubleArray, count: Int): Double {
        if (count == 0) return Double.NaN
        val sorted = values.copyOf(count).also { it.sort() }
        return if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
    }
}

/**
 * Detrended fluctuation analysis (DFA) of an RR series: the short-term scaling exponent α1.
 *
 * The series x is integrated after removing its mean (y(k) = Σ_{i≤k} (x_i − x̄)); for every box size n from
 * [MIN_SCALE] to [MAX_SCALE] beats the profile is divided into consecutive non-overlapping boxes of n beats (the
 * remainder at the end is left out), a straight line is fitted by least squares in each box, and F(n) is the root mean
 * square of the residuals. α1 is the slope of log F(n) against log n.
 *
 * Source: Peng C-K, Havlin S, Stanley HE, Goldberger AL. Quantification of scaling exponents and crossover phenomena in
 * nonstationary heartbeat time series. Chaos. 1995;5(1):82-87 (α1 over 4-16 beats). A correlated (1/f) series gives
 * α1 ≈ 1, uncorrelated noise 0.5; during exercise α1 falls from ≈ 1 or above at low intensity towards 0.5 and below
 * near the maximum (Gronwald T, Rogers B, Hoos O. Fractal correlation properties of heart rate variability: a new
 * biomarker for intensity distribution in endurance exercise and training prescription? Front Physiol.
 * 2020;11:550572).
 */
object Dfa {

    const val MIN_SCALE = 4
    const val MAX_SCALE = 16

    /**
     * α1 of [rr] between [from] (inclusive) and [to] (exclusive). Returns `null` with fewer than 2·[maxScale] beats or
     * when the fluctuation is zero at some scale (a constant series).
     */
    fun alpha1(rr: DoubleArray, from: Int = 0, to: Int = rr.size, minScale: Int = MIN_SCALE, maxScale: Int = MAX_SCALE): Double? {
        val n = to - from
        if (n < 2 * maxScale || minScale < 3 || maxScale <= minScale) return null
        var mean = 0.0
        for (i in from until to) mean += rr[i]
        mean /= n
        val y = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            acc += rr[from + i] - mean
            y[i] = acc
        }
        val logN = ArrayList<Double>()
        val logF = ArrayList<Double>()
        for (scale in minScale..maxScale) {
            val boxes = n / scale
            if (boxes < 1) continue
            // x = 0..scale-1 within each box: its sums are the same for every box.
            val sx = scale * (scale - 1) / 2.0
            val sxx = (scale - 1) * scale * (2.0 * scale - 1) / 6.0
            val dx = sxx - sx * sx / scale
            var sumSquares = 0.0
            for (b in 0 until boxes) {
                val start = b * scale
                var sy = 0.0
                var sxy = 0.0
                for (k in 0 until scale) {
                    val v = y[start + k]
                    sy += v
                    sxy += k * v
                }
                val slope = (sxy - sx * sy / scale) / dx
                val intercept = (sy - slope * sx) / scale
                for (k in 0 until scale) {
                    val r = y[start + k] - (intercept + slope * k)
                    sumSquares += r * r
                }
            }
            val f = sqrt(sumSquares / (boxes * scale))
            if (f <= 0.0 || f.isNaN()) return null
            logN += log10(scale.toDouble())
            logF += log10(f)
        }
        if (logN.size < 3) return null
        return slope(logN, logF)
    }

    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            num += (x[i] - mx) * (y[i] - my)
            den += (x[i] - mx) * (x[i] - mx)
        }
        return num / den
    }
}
