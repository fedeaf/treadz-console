package com.treadz.console.userdata.export

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.WorkoutSample

/** A sample ready to export, with the elevation computed from the incline. */
internal class ExportPoint(
    val sample: WorkoutSample,
    /** Cumulative climb since the start (never decreases). */
    val elevationGainMeters: Double,
    /** Net altitude relative to the start (0 at the first sample; goes down on negative incline). */
    val altitudeMeters: Double,
)

internal object ExportPoints {

    /**
     * Samples ordered by `seq`, without consecutive duplicates: a sample identical to the previous one in every
     * value (all but `seq` and the timestamp) adds nothing and is dropped. The treadmill sends ~2 packets per
     * second with repeated values, so this keeps about one sample per second without losing any information. The RR
     * intervals of a dropped sample are not lost: they are appended to the sample that is kept.
     */
    fun collapseRepeats(samples: List<WorkoutSample>): List<WorkoutSample> {
        val result = ArrayList<WorkoutSample>(samples.size)
        for (s in samples.sortedBy { it.seq }) {
            val last = result.lastOrNull()
            if (last == null || !sameValues(last, s)) {
                result += s
            } else if (s.rrIntervalsMs.isNotEmpty()) {
                result[result.lastIndex] = last.copy(rrIntervalsMs = last.rrIntervalsMs + s.rrIntervalsMs)
            }
        }
        return result
    }

    private fun sameValues(a: WorkoutSample, b: WorkoutSample) =
        a.elapsedSeconds == b.elapsedSeconds && a.speedKmh == b.speedKmh && a.targetSpeedKmh == b.targetSpeedKmh &&
            a.inclinePercent == b.inclinePercent && a.distanceMeters == b.distanceMeters && a.calories == b.calories &&
            a.heartRateBpm == b.heartRateBpm && a.heartRateSource == b.heartRateSource && a.beltStatus == b.beltStatus &&
            a.isStale == b.isStale

    /**
     * Elevation from the incline. The treadmill does not report altitude, so each step between consecutive samples
     * climbs `distance increment x mean incline / 100` meters (incline is a grade in percent). A distance that does not
     * grow (pause, stale sample, counter reset) climbs nothing.
     */
    fun withElevation(samples: List<WorkoutSample>): List<ExportPoint> {
        var gain = 0.0
        var altitude = 0.0
        var previous: WorkoutSample? = null
        return samples.map { s ->
            previous?.let { p ->
                val meters = maxOf(0, s.distanceMeters - p.distanceMeters)
                val rise = meters * (p.inclinePercent + s.inclinePercent) / 2.0 / 100.0
                if (rise.isFinite()) {
                    altitude += rise
                    if (rise > 0) gain += rise
                }
            }
            previous = s
            ExportPoint(s, gain, altitude)
        }
    }

    /** Leading countdown samples (`Starting` with the belt still): not part of the activity itself. */
    fun dropLeadingCountdown(points: List<ExportPoint>): List<ExportPoint> =
        points.dropWhile { it.sample.beltStatus == BeltStatus.Starting && it.sample.speedKmh == 0.0 }
}
