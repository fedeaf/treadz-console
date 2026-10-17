package com.treadz.console.sync

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.WorkoutSample
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Synthetic workouts of a maximal test for the recalculation tests (no real data in the repository): RR intervals whose
 * DFA-alpha1 falls linearly from 1.2 to 0.3 while the heart rate rises, by spectral synthesis of fractional noise.
 */
object SyntheticTestWorkout {

    private fun fractionalNoise(n: Int, alpha: Double, random: Random): DoubleArray {
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

    /** Beats of a ramp: (time of the beat in ms, RR in ms). Heart rate [hrStart] to [hrEnd], alpha1 1.2 to 0.3. */
    private fun rampBeats(startMillis: Long, seconds: Int, hrStart: Double, hrEnd: Double, seed: Long): List<Pair<Long, Int>> {
        val random = Random(seed)
        val grid = HashMap<Int, DoubleArray>()
        fun series(alpha: Double) = grid.getOrPut((alpha / 0.05).roundToInt()) { fractionalNoise(2048, (alpha / 0.05).roundToInt() * 0.05, random) }
        val out = ArrayList<Pair<Long, Int>>()
        var t = startMillis.toDouble()
        val end = startMillis + seconds * 1000L
        var i = 0
        while (t < end) {
            val f = (t - startMillis) / (seconds * 1000.0)
            val rr = 60_000.0 / (hrStart + f * (hrEnd - hrStart)) + 20.0 * series(1.2 + f * (0.3 - 1.2))[i % 2048]
            t += rr
            out += Math.round(t) to rr.roundToInt()
            i++
        }
        return out
    }

    /**
     * One-second samples of a test that starts at [startedAt]: [warmUpSeconds] of easy warm-up and then a ramp of
     * [rampSeconds] (incline [inclineFrom] to [inclineTo] at [speedKmh], heart rate 100 to [hrEnd]); `warmUpSeconds +
     * rampSeconds + 1` samples, the last one at the end of the effort. With a strap ([source] `DirectStrap` and
     * [withRr]) each sample carries the RR intervals that ended during its second.
     */
    fun samples(
        startedAt: Long,
        warmUpSeconds: Int,
        rampSeconds: Int,
        speedKmh: Double,
        inclineFrom: Double,
        inclineTo: Double,
        source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap,
        withRr: Boolean = true,
        hrEnd: Double = 168.0,
        seed: Long = 3,
    ): List<WorkoutSample> {
        val beats = if (source == HeartRateSourceKind.DirectStrap && withRr) {
            rampBeats(startedAt + warmUpSeconds * 1000L, rampSeconds, 100.0, hrEnd, seed)
        } else {
            emptyList()
        }
        val total = warmUpSeconds + rampSeconds
        val bySecond = beats.groupBy({ ((it.first - startedAt + 999) / 1000).toInt().coerceIn(1, total) }, { it.second })
        return (0..total).map { s ->
            val f = ((s - warmUpSeconds).coerceAtLeast(0)).toDouble() / rampSeconds
            WorkoutSample(
                seq = s,
                timestampMillis = startedAt + s * 1000L,
                elapsedSeconds = s + 1,
                speedKmh = if (s < warmUpSeconds) 4.1 else speedKmh,
                targetSpeedKmh = if (s < warmUpSeconds) 4.1 else speedKmh,
                inclinePercent = if (s < warmUpSeconds) 0.0 else Math.round((inclineFrom + f * (inclineTo - inclineFrom)) * 10) / 10.0, // the console's resolution
                distanceMeters = s,
                calories = s / 10,
                heartRateBpm = (100 + f * (hrEnd - 100)).roundToInt(),
                beltStatus = BeltStatus.Running,
                heartRateSource = source,
                rrIntervalsMs = bySecond[s].orEmpty(),
            )
        }
    }

    /** The samples of [balkeResult]: 2 min of warm-up and a 10 min ramp, 5.5 km/h, 5 to 15 %. */
    fun walking(startedAt: Long = T0, source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap, withRr: Boolean = true) =
        samples(startedAt, 120, 600, 5.5, 5.0, 15.0, source, withRr)

    /** The samples of [maxTestResult]: 5 min of warm-up and a 12 min ramp, 8 to 14 km/h at 1 %. */
    fun running(startedAt: Long = T0, source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap, withRr: Boolean = true): List<WorkoutSample> {
        val base = samples(startedAt, 300, 720, 8.0, 1.0, 1.0, source, withRr)
        return base.map { s ->
            val f = ((s.seq - 300).coerceAtLeast(0)) / 720.0
            if (s.seq < 300) s else (Math.round((8.0 + 6.0 * f) * 100) / 100.0).let { v -> s.copy(speedKmh = v, targetSpeedKmh = v) }
        }
    }
}
