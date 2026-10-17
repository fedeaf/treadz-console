package com.treadz.console.analysis.samples

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RrSeriesTest {

    @Test
    fun `the last interval of a notification ends when it was received and the earlier ones go back from it`() {
        val series = mutableListOf<RrInterval>()
        RrSeries.append(series, 10_000, listOf(600, 650))
        assertEquals(listOf(RrInterval(9_350, 600), RrInterval(10_000, 650)), series)
        // A late notification whose back-computed beat lands before the previous one is kept in order.
        RrSeries.append(series, 10_300, listOf(700, 400))
        assertEquals(listOf(10_001L, 10_300L), series.drop(2).map { it.timeMillis })
    }

    @Test
    fun `only strap samples give RR intervals`() {
        val samples = listOf(
            WorkoutSample(1_000, 5.0, 1.0, 100, SampleHeartRateSource.Strap, listOf(600)),
            WorkoutSample(2_000, 5.0, 1.0, 100, SampleHeartRateSource.Console, listOf(600)),
            WorkoutSample(3_000, 5.0, 1.0, 100, SampleHeartRateSource.Unknown, listOf(600)),
            WorkoutSample(4_000, 5.0, 1.0, 100, SampleHeartRateSource.Strap, listOf(590, 610)),
        )
        assertEquals(listOf(RrInterval(1_000, 600), RrInterval(3_390, 590), RrInterval(4_000, 610)), RrSeries.fromSamples(samples))
    }

    @Test
    fun `the mean load is time weighted and the equation follows the mean speed`() {
        // 5 km/h and 10 % for 30 s, then 5 km/h and 20 % for 30 s, sampled at different rates.
        val samples = listOf(WorkoutSample(0, 5.0, 10.0), WorkoutSample(30_000, 5.0, 20.0), WorkoutSample(45_000, 5.0, 20.0))
        val load = LoadAverage.over(samples, 0, 60_000)!!
        assertEquals(15.0, load.inclinePercent, 1e-9)
        assertEquals(false, load.running)
        val s = 5000.0 / 60.0
        assertEquals(3.5 + 0.1 * s + 1.8 * s * 0.15, load.vo2MlKgMin, 1e-9)
        assertEquals(60_000, load.coveredMillis)
        val run = LoadAverage.over(listOf(WorkoutSample(0, 10.0, 1.0)), 0, 60_000)!!
        assertEquals(true, run.running)
        assertEquals(3.5 + 0.2 * 10000.0 / 60 + 0.9 * 10000.0 / 60 * 0.01, run.vo2MlKgMin, 1e-9)
    }
}
