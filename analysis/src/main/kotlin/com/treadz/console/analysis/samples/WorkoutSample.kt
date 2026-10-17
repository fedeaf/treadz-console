package com.treadz.console.analysis.samples

/**
 * Where the heart rate of a sample came from.
 *
 * Only [Strap] (a chest strap connected directly to the app) provides RR intervals that can be trusted for RR metrics
 * (DFA-α1, RMSSD). [Console] (the strap relayed by the treadmill console) and [Unknown] (imported workouts, recordings
 * without a source) are fine for heart-rate metrics but never for RR metrics.
 */
enum class SampleHeartRateSource { Strap, Console, Unknown }

/**
 * One recorded sample of a workout, as a neutral type: the storage layer maps its own rows to it.
 *
 * @property timeMillis When the sample was taken (epoch milliseconds, or any monotonic clock shared with the other
 *   times given to the same calculation).
 * @property heartRateBpm `null` without a valid reading.
 * @property heartRateSource `null` when there is no heart rate; a reading with an unknown origin is
 *   [SampleHeartRateSource.Unknown].
 * @property rrIntervalsMs RR intervals (ms between beats) that arrived with this sample, oldest first. They are used
 *   only when [heartRateSource] is [SampleHeartRateSource.Strap].
 */
data class WorkoutSample(
    val timeMillis: Long,
    val speedKmh: Double,
    val inclinePercent: Double,
    val heartRateBpm: Int? = null,
    val heartRateSource: SampleHeartRateSource? = null,
    val rrIntervalsMs: List<Int> = emptyList(),
)

/**
 * One RR interval with the time of the beat that ends it.
 *
 * @property timeMillis Time of the beat that closes the interval, on the same clock as the samples.
 * @property rrMs Length of the interval in milliseconds.
 */
data class RrInterval(val timeMillis: Long, val rrMs: Int)

/** Building time-stamped RR series from what the strap delivers. */
object RrSeries {

    /**
     * Appends the RR intervals of one strap notification received at [receivedAtMillis] to [series]. The last interval
     * of the notification ends at [receivedAtMillis] and the earlier ones are placed back from it by their own lengths;
     * an interval that would land at or before the previous beat of [series] (clock jitter) is placed 1 ms after it so
     * the series stays in time order.
     */
    fun append(series: MutableList<RrInterval>, receivedAtMillis: Long, rrMs: List<Int>) {
        if (rrMs.isEmpty()) return
        var t = receivedAtMillis - rrMs.drop(1).sumOf { it.toLong() }
        for ((i, rr) in rrMs.withIndex()) {
            if (i > 0) t += rr
            val previous = series.lastOrNull()?.timeMillis
            val at = if (previous != null && t <= previous) previous + 1 else t
            series += RrInterval(at, rr)
        }
    }

    /**
     * The RR series of [samples] (in time order): only the samples whose heart rate comes from
     * [SampleHeartRateSource.Strap]; each sample's intervals are placed as by [append].
     */
    fun fromSamples(samples: List<WorkoutSample>): List<RrInterval> {
        val series = ArrayList<RrInterval>()
        for (s in samples) {
            if (s.heartRateSource == SampleHeartRateSource.Strap) append(series, s.timeMillis, s.rrIntervalsMs)
        }
        return series
    }
}
