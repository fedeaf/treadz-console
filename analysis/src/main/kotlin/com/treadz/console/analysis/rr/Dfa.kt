package com.treadz.console.analysis.rr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * An RR series after artifact correction.
 *
 * @property rrMs The intervals, artifacts replaced by interpolation.
 * @property artifact Which intervals were corrected: artifacts and the pauses after premature beats.
 * @property pause Which of them are the pause after a premature beat ([RrCleaning]): a real beat, not a fault of the
 *   strap, corrected only because it distorts the beat-to-beat pattern.
 */
class CleanedRr(val rrMs: DoubleArray, val artifact: BooleanArray, val pause: BooleanArray = BooleanArray(rrMs.size)) {
    val size: Int get() = rrMs.size

    /** Corrected intervals (pauses included): the share that matters for DFA-α1. */
    val artifactCount: Int get() = artifact.count { it }

    /** Corrected intervals that are not pauses: the share that tells about the strap's readings. */
    val strapArtifactCount: Int get() = artifact.indices.count { artifact[it] && !pause[it] }

    /** The interval at [i] is an artifact of the readings (corrected and not a pause). */
    fun isStrapArtifact(i: Int): Boolean = artifact[i] && !pause[i]
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
 *
 * **Premature beats**: an atrial or ventricular premature beat gives a short interval followed by a long one (the
 * pause until the next normal beat, full or partial). The short one is caught by the 20 % rule; the pause is often only
 * 10-25 % above the local median and would stay, adding a spurious jump that lowers α1. So the interval right after a
 * short artifact (in range and more than [MAX_DEVIATION] below its median) is corrected too when it is more than
 * [PAUSE_MIN_EXCESS] (8 %) above its own local median. Only that pattern: an extra detection of the strap (two short
 * intervals, then a normal one), a missed beat (one interval of about twice the median) and the ordinary beat-to-beat
 * variation without a premature beat are left as before. The rule depends on no person: it is relative to the local
 * median at any heart rate. In real recordings the interval after a short one fell either at 0.97-1.03 of the median
 * (a normal beat) or at 1.10-1.25 (a pause), hence 8 %. Corrected pauses are marked ([CleanedRr.pause]): they count for
 * the quality of α1, not as faults of the strap. Source of the short-long pattern: Lipponen JA, Tarvainen MP. A robust
 * algorithm for heart rate variability time series artefact correction using novel beat classification. J Med Eng
 * Technol. 2019;43(3):173-181.
 */
object RrCleaning {

    const val MIN_RR_MS = 300
    const val MAX_RR_MS = 2_000
    const val LOCAL_BEATS = 11
    const val MAX_DEVIATION = 0.20
    const val PAUSE_MIN_EXCESS = 0.08

    fun clean(rrMs: List<Int>): CleanedRr {
        val n = rrMs.size
        val raw = DoubleArray(n) { rrMs[it].toDouble() }
        val inRange = BooleanArray(n) { rrMs[it] in MIN_RR_MS..MAX_RR_MS }
        val artifact = BooleanArray(n)
        val pause = BooleanArray(n)
        val medians = DoubleArray(n)
        val half = LOCAL_BEATS / 2
        val buffer = DoubleArray(LOCAL_BEATS)
        for (i in 0 until n) {
            var m = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) if (inRange[j]) buffer[m++] = raw[j]
            medians[i] = median(buffer, m)
            artifact[i] = !inRange[i] || abs(raw[i] - medians[i]) > MAX_DEVIATION * medians[i]
        }
        // The pause after a premature beat (see the description).
        for (i in 0 until n - 1) {
            val premature = inRange[i] && raw[i] < (1 - MAX_DEVIATION) * medians[i]
            if (premature && inRange[i + 1] && !artifact[i + 1] && raw[i + 1] > (1 + PAUSE_MIN_EXCESS) * medians[i + 1]) {
                artifact[i + 1] = true
                pause[i + 1] = true
            }
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
        return CleanedRr(out, artifact, pause)
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
