package com.treadz.console.programs.fitness

import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.LoadEventType
import com.treadz.console.treadmill.events.LoadQuantity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The load events of a fitness test: only the manual changes, with the test clock, in every phase. */
@OptIn(ExperimentalCoroutinesApi::class)
class FitnessLoadEventsTest {

    private fun manual(quantity: LoadQuantity, value: Double, seconds: Int) = LoadEvent(seconds, LoadEventType.ManualChange, quantity, value)

    private val FitnessRig.loadEvents: List<LoadEvent> get() = runner.loadEvents.value

    @Test
    fun `Ebbeling - the app's speed and the console's speed in stage 1, once each, the automatic corrections not at all`() = runTest {
        val rig = FitnessRig(this)
        rig.connect()
        assertEquals(FitnessActionResult.Success, rig.runner.startEbbeling(EbbelingParameters(SUBJECT, 4.8)))
        delay(20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.adjustSpeed(6.0))
        delay(40_000)
        rig.sim.physicalSetSpeed(5.2)
        delay(30_000)
        val events = rig.loadEvents
        assertEquals(2, events.size, "$events")
        assertEquals(LoadEvent(events[0].seconds, LoadEventType.ManualChange, LoadQuantity.Speed, 6.0), events[0])
        assertTrue(events[0].seconds in 20..22, "${events[0]}")
        assertEquals(LoadQuantity.Speed, events[1].quantity)
        assertEquals(5.2, events[1].value)
        assertTrue(events[1].seconds in 60..75, "the console's change is seen once the speed settled: ${events[1]}")
        rig.runner.stop()
    }

    @Test
    fun `maximal test - the manual changes of the warm-up count, +1 does not, the one that breaks the ramp counts, the cool-down too`() = runTest {
        val rig = FitnessRig(this)
        rig.connect()
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxTest(MaxTestParameters(SUBJECT, 8.0)))
        delay(20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.extendPhase())
        assertTrue(rig.loadEvents.isEmpty(), "+1 is not a load change: ${rig.loadEvents}")
        rig.sim.physicalSetSpeed(6.0)
        delay(30_000)
        assertEquals(1, rig.loadEvents.size, "${rig.loadEvents}")
        assertEquals(manual(LoadQuantity.Speed, 6.0, rig.loadEvents[0].seconds), rig.loadEvents[0])

        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(90_000)
        rig.sim.physicalSetSpeed(9.0)
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(FitnessEndReason.ManualChange, rig.result.endReason)
        assertEquals(2, rig.loadEvents.size, "${rig.loadEvents}")
        val broke = rig.loadEvents[1]
        assertEquals(LoadEvent(broke.seconds, LoadEventType.ManualChange, LoadQuantity.Speed, 9.0), broke)
        assertTrue(broke.seconds > 5 * 60 + 60 + 80, "after 6 min of warm-up (+1 min) and 90 s of the ramp: $broke")

        // The cool-down is a manual part too, with the app's controls and the console's.
        delay(10_000)
        assertEquals(FitnessActionResult.Success, rig.runner.adjustByHand(4.0, 2.0))
        assertEquals(4, rig.loadEvents.size, "${rig.loadEvents}")
        assertEquals(setOf(LoadQuantity.Speed to 4.0, LoadQuantity.Incline to 2.0), rig.loadEvents.drop(2).map { it.quantity!! to it.value!! }.toSet())
        rig.awaitFinished()
        // The final event carries the whole list.
        assertEquals(rig.loadEvents, rig.eventsOf<FitnessTestEvent.Finished>().single().loadEvents)
    }

    @Test
    fun `walking test - changing the ramp start in the warm-up changes the load by hand`() = runTest {
        val rig = FitnessRig(this)
        rig.connect(warmSeconds = 10)
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxHeartRateWalk(MaxHeartRateWalkParameters(SUBJECT)))
        delay(10_000)
        // The first warm-up minute is 75 % of the ramp speed (4.1 of 5.5): a faster ramp start asks 6.0 * 0.75 = 4.5.
        assertEquals(FitnessActionResult.Success, rig.runner.adjustRampStart(6.0, null))
        val events = rig.loadEvents
        assertEquals(listOf(LoadQuantity.Speed to 4.5), events.map { it.quantity!! to it.value!! }, "$events")
        assertTrue(events.single().seconds in 10..12)
        // The incline of the ramp start does not change the first minute (0 %).
        assertEquals(FitnessActionResult.Success, rig.runner.adjustRampStart(null, 8.0))
        assertEquals(1, rig.loadEvents.size, "${rig.loadEvents}")
        rig.runner.stop()
    }

    @Test
    fun `a new test starts a new list and a test without changes has none`() = runTest {
        val rig = FitnessRig(this)
        rig.connect()
        rig.runner.startMaxTest(MaxTestParameters(SUBJECT, 8.0))
        delay(15_000)
        rig.sim.physicalSetSpeed(6.5)
        delay(30_000)
        assertEquals(1, rig.loadEvents.size)
        rig.runner.stop()
        rig.awaitFinished()
        rig.runner.startMaxTest(MaxTestParameters(SUBJECT, 8.0))
        assertTrue(rig.loadEvents.isEmpty(), "${rig.loadEvents}")
        delay(30_000)
        assertTrue(rig.loadEvents.isEmpty())
        rig.runner.stop()
    }
}
