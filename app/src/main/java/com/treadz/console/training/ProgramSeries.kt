package com.treadz.console.training

/** What the treadmill really did at one moment of a program: [seconds] on the program clock, speed and incline. */
data class ActualSample(val seconds: Int, val speedKmh: Double, val inclinePercent: Double)

/**
 * What the treadmill really did during the current (or last) program, for the live chart and the chart at the end. The
 * x axis is the program clock (the seconds the belt ran for the program), the same one the history uses for a recorded
 * workout, so the live chart and the history draw the same picture. One sample per [strideSeconds] of program time;
 * when [MAX_SAMPLES] are reached every other sample is dropped and the stride doubles, so a long program keeps its
 * whole shape at a coarser resolution.
 */
data class ProgramSeries(val samples: List<ActualSample> = emptyList(), val strideSeconds: Int = 1) {

    /**
     * This series with [sample] at the end, or this same series when [sample] is less than [strideSeconds] after the last
     * one (the program clock runs once a second; the progress is published more often).
     */
    fun appended(sample: ActualSample, maxSamples: Int = MAX_SAMPLES): ProgramSeries {
        val last = samples.lastOrNull()
        if (last != null && sample.seconds < last.seconds + strideSeconds) return this
        val grown = samples + sample
        if (grown.size <= maxSamples) return copy(samples = grown)
        // Full: keep every other sample (the first and the newest always stay) at twice the stride.
        val halved = grown.filterIndexed { i, _ -> i % 2 == 0 }.let { if (it.last() == sample) it else it + sample }
        return ProgramSeries(halved, strideSeconds * 2)
    }

    companion object {
        /** About 40 minutes at one sample per second; a longer program is kept at a coarser resolution. */
        const val MAX_SAMPLES = 2400
    }
}
