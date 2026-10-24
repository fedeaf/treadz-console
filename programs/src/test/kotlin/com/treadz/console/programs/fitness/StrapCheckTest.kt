package com.treadz.console.programs.fitness

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The strap check of the warm-up of the two maximal tests: the share of artifacts of the RR so far. */
@OptIn(ExperimentalCoroutinesApi::class)
class StrapCheckTest {

    /**
     * A strap connected to the app: one reading per second with the beats of that second at 100 bpm (600 ms), every
     * [artifactEvery]-th beat an impossible interval while [noisy] is true; [withRr] false gives the console's readings.
     */
    private class Strap(private val scope: TestScope, private val withRr: Boolean = true) : HeartRateSource {
        private val flow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 64)

        @Volatile
        var noisy = false

        @Volatile
        var artifactEvery = 8
        private var beat = 0
        private var carry = 0.0

        @Volatile
        private var last = HeartRateReading.NONE
        override fun latest() = last
        override val readings: Flow<HeartRateReading> = flow.asSharedFlow()

        init {
            scope.backgroundScope.launch {
                while (true) {
                    delay(1_000)
                    carry += 1_000.0
                    val rr = ArrayList<Int>()
                    while (carry >= 600.0) {
                        carry -= 600.0
                        val artifact = noisy && beat++ % artifactEvery == artifactEvery - 1
                        rr += if (artifact) 150 else 600 + (if (beat % 2 == 0) 10 else -10)
                    }
                    val reading = if (withRr) {
                        HeartRateReading(100, HeartRateValidity.Valid, scope.testScheduler.currentTime, HeartRateSourceKind.DirectStrap, rr)
                    } else {
                        HeartRateReading(100, HeartRateValidity.Unconfirmed, scope.testScheduler.currentTime, HeartRateSourceKind.TreadmillConsole)
                    }
                    last = reading
                    flow.tryEmit(reading)
                }
            }
        }
    }

    private class Rig(testScope: TestScope, val strap: Strap) {
        private val bg = testScope.backgroundScope
        val clock = Clock { FT0 + testScope.currentTime }
        val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
        val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
        val runner = FitnessTestRunner(controller, bg, clock, heartRate = strap, idFactory = { RESULT_ID })
        val progress: FitnessTestProgress get() = runner.progress.value
    }

    private suspend fun TestScope.started(strap: Strap, walking: Boolean = false): Rig {
        val rig = Rig(this, strap)
        assertEquals(CommandResult.Success, rig.controller.connect(rig.sim.address))
        delay(5_000)
        val result = if (walking) {
            rig.runner.startMaxHeartRateWalk(MaxHeartRateWalkParameters(SUBJECT))
        } else {
            rig.runner.startMaxTest(MaxTestParameters(SUBJECT, 8.0))
        }
        assertEquals(FitnessActionResult.Success, result)
        return rig
    }

    @Test
    fun `a clean strap - nothing before the first minute, then a share under 5 percent that is not a warning`() = runTest {
        val rig = started(Strap(this))
        delay(40_000)
        assertNull(rig.progress.strapCheck, "from the first minute of the warm-up")
        delay(30_000)
        val check = rig.progress.strapCheck
        assertNotNull(check)
        assertEquals(0.0, check!!.artifactPercent)
        assertFalse(check.poor)
        rig.runner.stop()
    }

    @Test
    fun `a noisy strap - above 5 percent the warm-up shows the warning, until the ramp starts, and it never blocks`() = runTest {
        val strap = Strap(this).also { it.noisy = true }
        val rig = started(strap)
        delay(50_000)
        assertNull(rig.progress.strapCheck)
        delay(20_000) // 70 s: one beat in 8 is an artifact
        val check = rig.progress.strapCheck!!
        assertTrue(check.poor, "$check")
        assertTrue(check.artifactPercent in 10.0..14.0, "$check")
        assertEquals(FitnessTestPhase.WarmUp, rig.progress.phase)
        delay(180_000)
        assertTrue(rig.progress.strapCheck!!.poor)
        // It does not stop the test: the ramp starts at the end of the warm-up (5 min), and the warning goes.
        rig.runner.progress.value.let { assertEquals(FitnessTestPhase.WarmUp, it.phase) }
        delay(60_000)
        assertEquals(FitnessTestPhase.Incremental, rig.progress.phase)
        assertNull(rig.progress.strapCheck)
        rig.runner.stop()
    }

    @Test
    fun `it clears by itself when the share falls back to 5 percent or less`() = runTest {
        val strap = Strap(this).also { it.noisy = true }
        val rig = started(strap)
        delay(80_000)
        assertTrue(rig.progress.strapCheck!!.poor)
        strap.noisy = false // the user wetted the strap
        delay(40_000)
        val still = rig.progress.strapCheck!!
        assertTrue(still.artifactPercent < 12.0)
        // About 20 artifacts among the beats so far: the share goes under 5 % when there are 400 beats (4 min of warm-up).
        delay(160_000)
        val cleared = rig.progress.strapCheck!!
        assertFalse(cleared.poor, "$cleared")
        assertTrue(cleared.artifactPercent <= 5.0)
        assertEquals(FitnessTestPhase.WarmUp, rig.progress.phase)
        rig.runner.stop()
    }

    @Test
    fun `the walking test checks the strap too`() = runTest {
        val strap = Strap(this).also { it.noisy = true }
        val rig = started(strap, walking = true)
        delay(90_000)
        assertTrue(rig.progress.strapCheck!!.poor)
        rig.runner.stop()
    }

    @Test
    fun `without RR - the console's heart rate - there is no check`() = runTest {
        val rig = started(Strap(this, withRr = false))
        delay(100_000)
        assertNull(rig.progress.strapCheck)
        rig.runner.stop()
    }

    @Test
    fun `Ebbeling has no check`() = runTest {
        val strap = Strap(this).also { it.noisy = true }
        val rig = Rig(this, strap)
        rig.controller.connect(rig.sim.address)
        delay(5_000)
        assertEquals(FitnessActionResult.Success, rig.runner.startEbbeling(EbbelingParameters(SUBJECT)))
        delay(100_000)
        assertNull(rig.progress.strapCheck)
        rig.runner.stop()
    }
}
