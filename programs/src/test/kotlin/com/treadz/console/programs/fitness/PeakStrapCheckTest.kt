package com.treadz.console.programs.fitness

import com.treadz.console.analysis.heartrate.StrapWindowConfig
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The peak heart rate of the two maximal tests leaves out the 5-s windows whose strap readings the strap's RR do not
 * confirm (the audit of the zone-2 workout of 2026-10-24: a burst of false readings 8-11 bpm high that, at the end of a
 * test, would have become the maximum heart rate). Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeakStrapCheckTest {

    /** Impossible beats, as multiples of the real interval (a missed beat, a doubled one, noise...). */
    private val junk = doubleArrayOf(0.5, 1.4, 0.6, 1.3, 0.55, 1.45)
    private val off = FitnessTestConfig(strapWindow = StrapWindowConfig(enabled = false))

    // ------------------------------------------------------------------ from stored samples

    /**
     * One sample per second of a test: the strap reads [reportedAt] and its beats follow [truthAt] (the real rate), or are
     * impossible intervals while [junkAt]; [withRr] false gives no beats (the console's readings).
     */
    private fun samples(
        result: FitnessTestResult,
        truthAt: (Int) -> Int,
        reportedAt: (Int) -> Int = truthAt,
        junkAt: (Int) -> Boolean = { false },
        source: SampleHeartRateSource? = SampleHeartRateSource.Strap,
        withRr: Boolean = true,
    ): List<WorkoutSample> {
        var beat = 0
        var carry = 0.0
        return (0..((result.endedAtMillis - result.startedAtMillis) / 1000).toInt()).map { s ->
            val rr = ArrayList<Int>()
            carry += 1_000.0
            val ms = 60_000 / truthAt(s)
            while (carry >= ms) {
                val length = if (junkAt(s)) (ms * junk[beat++ % junk.size]).toInt() else ms
                carry -= length
                rr += length
            }
            WorkoutSample(result.startedAtMillis + s * 1000L, 12.0, 1.0, reportedAt(s), source, if (withRr) rr else emptyList())
        }
    }

    /** [base] bpm all along; from [from] s to [to] s the strap reads [extra] more than the truth, with impossible beats. */
    private fun withBurst(result: FitnessTestResult, base: Int, from: Int, to: Int, extra: Int, source: SampleHeartRateSource? = SampleHeartRateSource.Strap, withRr: Boolean = true) =
        samples(result, { base }, { s -> base + if (s in from until to) extra else 0 }, { s -> s in from until to }, source, withRr)

    private val running = sampleResult(FitnessTestType.MaxIncrementalRun)
    private val effortEnd = ((running.effortEndedAtMillis - running.startedAtMillis) / 1000).toInt()

    @Test
    fun `a burst of false readings in the peak range does not make the peak`() {
        // 170 bpm in the last two minutes of the effort, 20 s of 185 that the RR do not confirm.
        val s = withBurst(running, 170, effortEnd - 60, effortEnd - 40, 15)
        val with = FitnessTestRecalculation.peakFromSamples(running, s)
        val without = FitnessTestRecalculation.peakFromSamples(running, s, off)
        assertEquals(170.0, with!!, 0.5)
        assertEquals(185.0, without!!, 0.5)
    }

    @Test
    fun `the console's heart rate, which has no RR, counts as before`() {
        val s = withBurst(running, 170, effortEnd - 60, effortEnd - 40, 15, source = SampleHeartRateSource.Console, withRr = false)
        assertEquals(185.0, FitnessTestRecalculation.peakFromSamples(running, s)!!, 0.5)
        // A strap without RR is not judged either.
        val noRr = withBurst(running, 170, effortEnd - 60, effortEnd - 40, 15, withRr = false)
        assertEquals(185.0, FitnessTestRecalculation.peakFromSamples(running, noRr)!!, 0.5)
    }

    @Test
    fun `a real rise to the peak that the RR confirm is the peak`() {
        // The beats shorten with the readings: 170 for a while, then 185, and the RR say so.
        val s = samples(running, { sec -> if (sec >= effortEnd - 40) 185 else 170 })
        assertEquals(185.0, FitnessTestRecalculation.peakFromSamples(running, s)!!, 0.5)
    }

    @Test
    fun `a stored result is not raised by an artifact when it is recalculated for another reason`() {
        // The stored peak is 185.0; the burst reads 195.
        val s = withBurst(running, 170, effortEnd - 60, effortEnd - 40, 25)
        assertSame(running, FitnessTestRecalculation.refinedPeak(running, s), "nothing changes")
        val raised = FitnessTestRecalculation.refinedPeak(running, s, off)
        assertEquals(195.0, (raised.measurements as MaxTestMeasurements).peakHeartRateBpm!!, 0.5)
    }

    // ------------------------------------------------------------------ live tests

    /**
     * A strap connected to the app on virtual time: the true heart rate climbs 0.1 bpm per second from 100 and the strap
     * reads it, with consistent beats; while [burst] is true the readings are 12 bpm higher and the beats are impossible
     * intervals ([withRr] false: the console's readings, no RR).
     */
    private class Strap(private val scope: TestScope, private val withRr: Boolean) : HeartRateSource {
        private val flow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 64)

        @Volatile
        var burst = false

        /** The true heart rate stops climbing here (the test ended: the strap model has no physiology). */
        @Volatile
        var ceiling = 175
        private var beat = 0
        private var carry = 0.0

        @Volatile
        private var last = HeartRateReading.NONE
        override fun latest() = last
        override val readings: Flow<HeartRateReading> = flow.asSharedFlow()

        private val startedAt = scope.testScheduler.currentTime

        fun truth(): Int = minOf(ceiling, 100 + ((scope.testScheduler.currentTime - startedAt) / 10_000).toInt())

        init {
            val junk = doubleArrayOf(0.5, 1.4, 0.6, 1.3, 0.55, 1.45)
            scope.backgroundScope.launch {
                while (true) {
                    delay(1_000)
                    val truth = truth()
                    carry += 1_000.0
                    val rr = ArrayList<Int>()
                    val ms = 60_000 / truth
                    while (carry >= ms) {
                        carry -= ms
                        rr += if (burst) (ms * junk[beat++ % junk.size]).toInt() else ms
                    }
                    val bpm = truth + if (burst) 12 else 0
                    val reading = if (withRr) {
                        HeartRateReading(bpm, HeartRateValidity.Valid, scope.testScheduler.currentTime, HeartRateSourceKind.DirectStrap, rr)
                    } else {
                        HeartRateReading(bpm, HeartRateValidity.Unconfirmed, scope.testScheduler.currentTime, HeartRateSourceKind.TreadmillConsole)
                    }
                    last = reading
                    flow.tryEmit(reading)
                }
            }
        }
    }

    private class Rig(testScope: TestScope, val strap: Strap, config: FitnessTestConfig) {
        private val bg = testScope.backgroundScope
        val clock = Clock { FT0 + testScope.currentTime }
        val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
        val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
        val runner = FitnessTestRunner(controller, bg, clock, config = config, heartRate = strap, idFactory = { RESULT_ID })
        val progress: FitnessTestProgress get() = runner.progress.value

        suspend fun await(phase: FitnessTestPhase) {
            var waited = 0L
            while (progress.phase != phase) {
                check(waited <= 1_800_000) { "timed out waiting for $phase (phase=${progress.phase})" }
                delay(500)
                waited += 500
            }
        }
    }

    /** Runs a maximal test to exhaustion with a 15-s burst about 35 s before the end; returns the final peak. */
    private suspend fun TestScope.peakOfLiveTest(walking: Boolean, withRr: Boolean, config: FitnessTestConfig): Double {
        val strap = Strap(this, withRr)
        val rig = Rig(this, strap, config)
        assertEquals(CommandResult.Success, rig.controller.connect(rig.sim.address))
        delay(5_000)
        val started = if (walking) rig.runner.startMaxHeartRateWalk(MaxHeartRateWalkParameters(SUBJECT)) else rig.runner.startMaxTest(MaxTestParameters(SUBJECT, 8.0))
        assertEquals(FitnessActionResult.Success, started)
        rig.await(FitnessTestPhase.Incremental)
        delay(240_000)
        strap.burst = true
        delay(15_000)
        strap.burst = false
        delay(20_000)
        strap.ceiling = strap.truth()
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        rig.await(FitnessTestPhase.Finished)
        val res = checkNotNull(rig.progress.result)
        val m = res.measurements
        return checkNotNull(
            when (m) {
                is MaxTestMeasurements -> m.peakHeartRateBpm
                is MaxHeartRateWalkMeasurements -> m.peakHeartRateBpm
                else -> null
            },
        ) { "no peak: flags=${res.flags} validity=${res.validity} end=${res.endReason} m=$m" }
    }

    @Test
    fun `running test live - the false burst is left out of the peak`() = runTest {
        val clean = peakOfLiveTest(walking = false, withRr = true, config = FitnessTestConfig())
        // The true heart rate is ~150 at the end (0.1 bpm per second); the burst read 12 above it.
        assertTrue(clean in 140.0..160.0, "$clean")
        val noRule = peakOfLiveTest(walking = false, withRr = true, config = off)
        assertTrue(noRule >= clean + 7, "without the rule the burst is the peak: $noRule against $clean")
    }

    @Test
    fun `walking test live - the false burst is left out of the peak`() = runTest {
        val clean = peakOfLiveTest(walking = true, withRr = true, config = FitnessTestConfig())
        assertTrue(clean in 135.0..160.0, "$clean")
        val noRule = peakOfLiveTest(walking = true, withRr = true, config = off)
        assertTrue(noRule >= clean + 7, "$noRule against $clean")
    }

    @Test
    fun `live with the console's heart rate - no RR, the readings count as before`() = runTest {
        val withRule = peakOfLiveTest(walking = false, withRr = false, config = FitnessTestConfig())
        val noRule = peakOfLiveTest(walking = false, withRr = false, config = off)
        assertEquals(noRule, withRule)
    }
}
