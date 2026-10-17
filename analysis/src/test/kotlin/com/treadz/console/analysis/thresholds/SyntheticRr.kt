package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.WorkoutSample
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Synthetic RR series with a known DFA-α1, for the tests only (no real data in the repository). */
object SyntheticRr {

    /**
     * Fractional noise of [n] points with a power spectrum 1/f^β, β = 2α − 1, by spectral synthesis (random phases,
     * Gaussian amplitudes ∝ f^(−β/2)), scaled to zero mean and unit standard deviation. Its DFA exponent is α.
     */
    fun fractionalNoise(n: Int, alpha: Double, random: Random): DoubleArray {
        val beta = 2.0 * alpha - 1.0
        val x = DoubleArray(n)
        for (k in 1..n / 2) {
            val amplitude = k.toDouble().pow(-beta / 2.0) * random.nextGaussian()
            val phase = random.nextDouble() * 2.0 * PI
            val w = 2.0 * PI * k / n
            for (t in 0 until n) x[t] += amplitude * cos(w * t + phase)
        }
        val mean = x.average()
        val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / n)
        for (t in 0 until n) x[t] = (x[t] - mean) / sd
        return x
    }

    /**
     * A ramp from [startMillis] lasting [durationSeconds]: the heart rate rises linearly from [hrStart] to [hrEnd]
     * and α1 falls linearly from [alphaStart] to [alphaEnd] (so α1 is linear in the heart rate). Each beat takes its
     * value from a long stationary series of the α1 of that moment (a grid every 0.05), with an RR noise of [sdMs].
     */
    fun ramp(
        startMillis: Long,
        durationSeconds: Int,
        hrStart: Double,
        hrEnd: Double,
        alphaStart: Double,
        alphaEnd: Double,
        sdMs: Double = 20.0,
        seed: Long = 1L,
    ): List<RrInterval> {
        val random = Random(seed)
        val length = 2048
        val grid = HashMap<Int, DoubleArray>()
        fun series(alpha: Double): DoubleArray {
            val key = (alpha / 0.05).roundToInt()
            return grid.getOrPut(key) { fractionalNoise(length, key * 0.05, random) }
        }
        val out = ArrayList<RrInterval>()
        var t = startMillis.toDouble()
        val end = startMillis + durationSeconds * 1000L
        var i = 0
        while (t < end) {
            val f = (t - startMillis) / (durationSeconds * 1000.0)
            val hr = hrStart + f * (hrEnd - hrStart)
            val alpha = alphaStart + f * (alphaEnd - alphaStart)
            val rr = 60_000.0 / hr + sdMs * series(alpha)[i % length]
            t += rr
            out += RrInterval(t.roundToLong(), rr.roundToInt())
            i++
        }
        return out
    }

    /** Samples every second from [startMillis] for [durationSeconds]: constant speed, incline rising linearly. */
    fun rampSamples(startMillis: Long, durationSeconds: Int, speedKmh: Double, inclineFrom: Double, inclineTo: Double): List<WorkoutSample> =
        (0..durationSeconds).map { s ->
            WorkoutSample(startMillis + s * 1000L, speedKmh, inclineFrom + (inclineTo - inclineFrom) * s / durationSeconds)
        }
}
