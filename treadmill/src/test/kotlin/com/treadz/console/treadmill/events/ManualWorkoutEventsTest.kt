package com.treadz.console.treadmill.events

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Rig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The load events of a manual workout: the app's buttons and the console's. */
class ManualWorkoutEventsTest {

    private fun speed(seconds: Int, value: Double) = LoadEvent(seconds, LoadEventType.ManualChange, LoadQuantity.Speed, value)
    private fun incline(seconds: Int, value: Double) = LoadEvent(seconds, LoadEventType.ManualChange, LoadQuantity.Incline, value)

    @Test
    fun `the app's buttons are manual changes, with the seconds since the start of the workout`() = runTest {
        val rig = Rig(this)
        rig.runAt(3.0)
        val manual = ManualWorkoutEvents(rig.controller, backgroundScope, rig.clock)
        manual.start()
        delay(30_000)
        assertEquals(CommandResult.Success, manual.setSpeed(5.0))
        delay(20_000)
        assertEquals(CommandResult.Success, manual.setIncline(4.0))
        val events = manual.events.value
        assertEquals(2, events.size)
        assertEquals(listOf(LoadQuantity.Speed, LoadQuantity.Incline), events.map { it.quantity })
        assertTrue(events.all { it.type == LoadEventType.ManualChange })
        assertEquals(5.0, events[0].value)
        assertEquals(4.0, events[1].value)
        // The time is the one of the workout, not of the command: the speed took a few seconds to be verified.
        assertTrue(events[0].seconds in 30..45, "${events[0]}")
        assertTrue(events[1].seconds in 50..70, "${events[1]}")
        assertTrue(events[0].seconds <= events[1].seconds)
    }

    @Test
    fun `a value is the one the treadmill was asked for, clamped like the command`() = runTest {
        val rig = Rig(this)
        rig.runAt(3.0)
        rig.controller.setLimits(com.treadz.console.treadmill.SafetyLimits(maxSpeedKmh = 6.0))
        val manual = ManualWorkoutEvents(rig.controller, backgroundScope, rig.clock)
        manual.start()
        assertEquals(CommandResult.Success, manual.setSpeed(9.0))
        assertEquals(6.0, manual.events.value.single().value)
    }

    @Test
    fun `the console's buttons are manual changes too, one event per quantity`() = runTest {
        val rig = Rig(this)
        rig.runAt(3.0, incline = 2.0)
        val manual = ManualWorkoutEvents(rig.controller, backgroundScope, rig.clock)
        manual.start()
        delay(20_000)
        rig.sim.physicalSetSpeed(6.0)
        rig.sim.physicalSetIncline(7.0)
        delay(15_000)
        val events = manual.events.value
        assertEquals(setOf(LoadQuantity.Speed to 6.0, LoadQuantity.Incline to 7.0), events.map { it.quantity!! to it.value!! }.toSet(), "$events")
        assertEquals(2, events.size)
        assertTrue(events.all { it.type == LoadEventType.ManualChange && it.seconds in 20..40 }, "$events")
    }

    @Test
    fun `a command that was not verified is not a change`() = runTest {
        val rig = Rig(this)
        rig.connect() // the belt is stopped: the incline is refused
        val manual = ManualWorkoutEvents(rig.controller, backgroundScope, rig.clock)
        manual.start()
        val result = manual.setIncline(5.0)
        assertFalse(result == CommandResult.Success, "$result")
        assertTrue(manual.events.value.isEmpty())
    }

    @Test
    fun `nothing is recorded before start() or after stop(), and start() begins a new list`() = runTest {
        val rig = Rig(this)
        rig.runAt(3.0)
        val manual = ManualWorkoutEvents(rig.controller, backgroundScope, rig.clock)
        assertFalse(manual.isActive)
        manual.setSpeed(4.0)
        assertTrue(manual.events.value.isEmpty(), "not attached yet")
        manual.start()
        assertTrue(manual.isActive)
        manual.setSpeed(5.0)
        val final = manual.stop()
        assertFalse(manual.isActive)
        assertEquals(1, final.size)
        manual.setSpeed(3.0)
        rig.sim.physicalSetIncline(3.0)
        delay(5_000)
        assertEquals(final, manual.events.value, "after stop() the list does not grow")
        manual.start()
        assertTrue(manual.events.value.isEmpty())
    }

    @Test
    fun `the app can report a command it sent itself`() = runTest {
        val rig = Rig(this)
        rig.runAt(3.0)
        val manual = ManualWorkoutEvents(rig.controller, backgroundScope, rig.clock)
        manual.start()
        delay(12_000)
        manual.onAppChange(speedKmh = 4.5, inclinePercent = 3.0)
        assertEquals(listOf(speed(12, 4.5), incline(12, 3.0)), manual.events.value)
    }

    @Test
    fun `the log rounds the values and ignores an exact repetition`() {
        val log = LoadEventLog()
        log.add(5, LoadEventType.ManualChange, LoadQuantity.Speed, 5.0000000001)
        log.add(5, LoadEventType.ManualChange, LoadQuantity.Speed, 5.0)
        log.add(6, LoadEventType.HeartRateControlResumed)
        log.add(7, LoadEventType.MaxHeartRateReached, LoadQuantity.Incline, Double.NaN)
        assertEquals(
            listOf(speed(5, 5.0), LoadEvent(6, LoadEventType.HeartRateControlResumed), LoadEvent(7, LoadEventType.MaxHeartRateReached)),
            log.events.value,
        )
        log.clear()
        assertTrue(log.events.value.isEmpty())
    }
}
