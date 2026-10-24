package com.treadz.console.programs.engine

import com.treadz.console.programs.block
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Repeat
import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.LoadEventType
import com.treadz.console.treadmill.events.LoadQuantity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The load events of a program run: what changed the load besides the plan's own blocks, with the program clock as time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadEventsRunnerTest {

    private fun hrBlock(
        seconds: Int, low: Int, high: Int, adjust: HeartRateAdjust, min: Double, max: Double, speed: Double, incline: Double,
    ): Block = block(
        BlockPhase.Work, BlockEnd.Duration(seconds),
        BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, low, high), adjust, min, max, BlockTarget.Absolute(speed, incline)),
    )

    private fun zone3Run() = planOf(
        timed(180, 6.0, 1.0, BlockPhase.WarmUp),
        hrBlock(1200, 70, 80, HeartRateAdjust.Speed, 7.5, 12.0, 10.0, 1.0),
        timed(120, 5.0, 0.0, BlockPhase.CoolDown),
    )

    private fun HeartRateRig.loadEvents(): List<LoadEvent> = runner.loadEvents.value

    private fun List<LoadEvent>.of(type: LoadEventType) = filter { it.type == type }

    // ------------------------------------------------------------------ manual changes

    @Test
    fun `manual changes - the app's and the console's, one event per quantity, with the program clock`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(600, 6.0, 1.0)))
        rig.awaitTrue(message = "running at 6") { rig.progress.state == RunState.Running && rig.controller.state.value.speedKmh >= 5.9 }
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(8.0))
        val appSeconds = rig.progress.totalElapsedSeconds
        delay(20_000)
        rig.sim.physicalSetIncline(5.0)
        delay(15_000)
        val events = rig.runner.loadEvents.value
        assertEquals(2, events.size, "$events")
        assertEquals(LoadEventType.ManualChange, events[0].type)
        assertEquals(LoadQuantity.Speed, events[0].quantity)
        assertEquals(8.0, events[0].value)
        assertTrue(events[0].seconds in appSeconds - 2..appSeconds + 2, "${events[0]} vs program second $appSeconds")
        assertEquals(LoadEvent(events[1].seconds, LoadEventType.ManualChange, LoadQuantity.Incline, 5.0), events[1])
        assertTrue(events[1].seconds in appSeconds + 20..appSeconds + 25, "${events[1]}")
        // The same moments as the program's own events.
        val manual = rig.eventsOf<ProgramEvent.ManualChange>()
        assertEquals(manual.map { it.programSeconds }, events.map { it.seconds })
        rig.runner.stop()
    }

    @Test
    fun `speed and incline changed together are two events`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(600, 6.0, 1.0)))
        rig.awaitTrue(message = "running at 6") { rig.progress.state == RunState.Running && rig.controller.state.value.speedKmh >= 5.9 }
        delay(10_000)
        rig.sim.physicalSetSpeed(7.0)
        rig.sim.physicalSetIncline(4.0)
        delay(15_000)
        val events = rig.runner.loadEvents.value
        assertEquals(setOf(LoadQuantity.Speed to 7.0, LoadQuantity.Incline to 4.0), events.map { it.quantity!! to it.value!! }.toSet(), "$events")
        assertTrue(events.all { it.type == LoadEventType.ManualChange })
        rig.runner.stop()
    }

    @Test
    fun `the list belongs to the run - emptied at the start, in the final event, kept after it`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 6.0, 1.0)))
        rig.awaitTrue(message = "running") { rig.controller.state.value.speedKmh >= 5.9 }
        delay(5_000)
        rig.runner.adjustSpeed(7.0)
        rig.awaitState(RunState.Finished)
        val finished = rig.eventsOf<ProgramEvent.Finished>().single()
        assertEquals(1, finished.loadEvents.size)
        assertEquals(finished.loadEvents, rig.runner.loadEvents.value)
        rig.start(planOf(timed(60, 6.0, 1.0)))
        assertTrue(rig.runner.loadEvents.value.isEmpty(), "a new run starts a new list")
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ block starts

    @Test
    fun `block starts - where every block really started, skipped or at its end, in the progress and as events`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(300, 6.0, 0.0, BlockPhase.WarmUp), timed(60, 6.0, 2.0), timed(60, 5.0, 0.0, BlockPhase.CoolDown)))
        rig.awaitTrue(message = "running") { rig.progress.state == RunState.Running && rig.controller.state.value.speedKmh >= 5.9 }
        delay(100_000)
        assertEquals(RunnerResult.Success, rig.runner.skipToNext())
        val skippedAt = rig.progress.totalElapsedSeconds
        rig.awaitTrue(message = "cool-down") { rig.progress.currentBlockIndex == 2 }
        val starts = rig.progress.blockStarts
        assertEquals(listOf(0, 1, 2), starts.map { it.blockIndex })
        assertEquals(0, starts[0].programSeconds)
        assertTrue(starts[1].programSeconds in skippedAt - 1..skippedAt, "$starts vs skip at $skippedAt")
        // the work block ran its full minute after the skip
        assertTrue(starts[2].programSeconds - starts[1].programSeconds in 59..61, "$starts")
        val events = rig.runner.loadEvents.value.of(LoadEventType.BlockStarted)
        assertEquals(starts.drop(1).map { LoadEvent(it.programSeconds, LoadEventType.BlockStarted, block = it.blockIndex) }, events)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ heart-rate regulation

    @Test
    fun `continuous regulation - a decrease above the zone and an increase below it`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        // Above the zone first (the heart rate does not follow the load), then below it.
        rig.scripted = { s -> if (s < 330) 140 else (140 - (s - 330)).coerceAtLeast(110).toInt() }
        rig.start(planOf(timed(180, 6.0, 1.0, BlockPhase.WarmUp), hrBlock(1200, 70, 76, HeartRateAdjust.Speed, 7.5, 12.0, 10.0, 1.0)))
        rig.awaitTrue(message = "increase") { rig.loadEvents().of(LoadEventType.HeartRateIncrease).isNotEmpty() }
        val adjusted = rig.eventsOf<ProgramEvent.HeartRateAdjusted>()
        val events = rig.loadEvents()
        val decreases = events.of(LoadEventType.HeartRateDecrease)
        val increases = events.of(LoadEventType.HeartRateIncrease)
        assertEquals(adjusted.filter { it.reason == HeartRateAdjustReason.AboveZone }.map { it.programSeconds to it.toValue }, decreases.map { it.seconds to it.value })
        assertEquals(adjusted.filter { it.reason == HeartRateAdjustReason.BelowZone }.map { it.programSeconds to it.toValue }, increases.map { it.seconds to it.value })
        assertTrue(decreases.isNotEmpty() && decreases.all { it.quantity == LoadQuantity.Speed })
        assertTrue(increases.all { it.quantity == LoadQuantity.Speed })
        assertTrue(decreases.last().seconds < increases.first().seconds)
        // Nothing but the regulation happened.
        assertTrue(events.all { it.type == LoadEventType.HeartRateDecrease || it.type == LoadEventType.HeartRateIncrease || it.type == LoadEventType.BlockStarted }, "$events")
        // The regulation does not start before the block does (180 s of warm-up).
        assertTrue(events.all { it.seconds >= 180 })
        rig.runner.stop()
    }

    @Test
    fun `between repetitions - the correction at the start of a repetition`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        val plan = planOf(
            timed(300, 6.0, 1.0, BlockPhase.WarmUp),
            Repeat(
                4,
                listOf(
                    hrBlock(240, 85, 95, HeartRateAdjust.Speed, 7.5, 14.0, 8.5, 1.0),
                    timed(180, 6.0, 1.0, BlockPhase.Recovery),
                ),
            ),
            timed(120, 5.0, 0.0, BlockPhase.CoolDown),
        )
        rig.start(plan)
        rig.awaitFinished()
        val next = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.NextRepetition }
        assertEquals(3, next.size)
        val expected = next.map { LoadEvent(it.programSeconds, if (it.toValue >= it.fromValue) LoadEventType.HeartRateIncrease else LoadEventType.HeartRateDecrease, LoadQuantity.Speed, it.toValue) }
        assertEquals(expected, rig.loadEvents().filter { it.type == LoadEventType.HeartRateIncrease || it.type == LoadEventType.HeartRateDecrease })
    }

    // ------------------------------------------------------------------ safety

    @Test
    fun `ceiling - a reduction is an event, the alert alone is not`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 160 }
        rig.start(planOf(hrBlock(300, 70, 80, HeartRateAdjust.Speed, 8.0, 12.0, 11.0, 1.0)))
        rig.awaitTrue(message = "at the minimum") { rig.progress.targetSpeedKmh == 8.0 }
        delay(60_000)
        val reductions = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.CeilingExceeded }
        val events = rig.loadEvents().of(LoadEventType.CeilingReduction)
        assertEquals(reductions.map { it.programSeconds to it.toValue }, events.map { it.seconds to it.value })
        assertEquals(listOf(10.0, 9.0, 8.0), events.map { it.value })
        assertTrue(events.all { it.quantity == LoadQuantity.Speed })
        // At the minimum nothing is reduced any more: no more events.
        assertEquals(3, rig.loadEvents().size, "${rig.loadEvents()}")
        rig.runner.stop()
    }

    @Test
    fun `maximum heart rate - with a reduction it is one event with the quantity, without one it has none`() = runTest {
        val reduced = HeartRateRig(this)
        reduced.connect()
        reduced.scripted = { s -> if (s < 30) 140 else (140 + (s - 30) * 2).coerceAtMost(185).toInt() }
        reduced.start(planOf(hrBlock(400, 80, 90, HeartRateAdjust.Speed, 8.0, 12.0, 11.0, 1.0)))
        reduced.awaitTrue(message = "recovery value") { reduced.progress.targetSpeedKmh == 8.0 }
        val max = reduced.loadEvents().of(LoadEventType.MaxHeartRateReached)
        assertEquals(1, max.size, "one event, not two: ${reduced.loadEvents()}")
        assertEquals(LoadQuantity.Speed, max.single().quantity)
        assertEquals(8.0, max.single().value)
        assertEquals(reduced.eventsOf<ProgramEvent.HeartRateMaxReached>().single().programSeconds, max.single().seconds)
        reduced.runner.stop()
    }

    @Test
    fun `maximum heart rate already at the minimum - the event has no quantity and no value`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 185 }
        // The block starts at its minimum: nothing can be reduced.
        rig.start(planOf(hrBlock(300, 80, 90, HeartRateAdjust.Speed, 8.0, 12.0, 8.0, 1.0)))
        rig.awaitTrue(message = "maximum reached") { rig.eventsOf<ProgramEvent.HeartRateMaxReached>().isNotEmpty() }
        delay(30_000)
        val max = rig.loadEvents().of(LoadEventType.MaxHeartRateReached)
        assertEquals(1, max.size, "${rig.loadEvents()}")
        assertEquals(LoadEvent(max.single().seconds, LoadEventType.MaxHeartRateReached), max.single())
        assertTrue(rig.eventsOf<ProgramEvent.HeartRateMaxReached>().all { !it.reduced })
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ no heart rate, resume

    @Test
    fun `without heart rate - back to the start value, and the control resuming when the heart rate is back`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect(withStrap = true)
        rig.scripted = { 100 } // under the zone: the control raises the speed above the start value
        rig.start(zone3Run())
        rig.awaitBlock(1)
        delay(420_000)
        val raised = checkNotNull(rig.progress.targetSpeedKmh)
        assertTrue(raised > 10.0, "raised to $raised")
        assertTrue(rig.loadEvents().none { it.type == LoadEventType.NoHeartRateFallback }, "${rig.loadEvents()}")

        rig.strap.takeOff()
        delay(75_000)
        val fallback = rig.loadEvents().of(LoadEventType.NoHeartRateFallback)
        assertEquals(1, fallback.size, "${rig.loadEvents()}")
        assertEquals(LoadEvent(fallback.single().seconds, LoadEventType.NoHeartRateFallback, LoadQuantity.Speed, 10.0), fallback.single())
        // The heart rate comes back (the automatic source falls back to the console some seconds after the strap went off,
        // and 10 s of valid readings end the holding): the control resumes by itself.
        rig.strap.putOn()
        delay(30_000)
        val resumed = rig.loadEvents().of(LoadEventType.HeartRateControlResumed)
        assertEquals(1, resumed.size, "${rig.loadEvents()}")
        assertEquals(LoadEvent(resumed.single().seconds, LoadEventType.HeartRateControlResumed), resumed.single())
        assertTrue(resumed.single().seconds >= fallback.single().seconds + 10, "${rig.loadEvents()}")
        rig.runner.stop()
    }

    @Test
    fun `the user resuming the heart-rate control after a manual change is an event`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.start(zone3Run())
        rig.awaitBlock(1)
        delay(240_000)
        rig.sim.physicalSetSpeed(11.0)
        rig.awaitTrue(message = "control off") { rig.progress.heartRateControl?.state == HeartRateControlState.OffByManualChange }
        val manual = rig.loadEvents().of(LoadEventType.ManualChange)
        assertEquals(listOf(LoadQuantity.Speed to 11.0), manual.map { it.quantity!! to it.value!! })
        assertTrue(rig.loadEvents().none { it.type == LoadEventType.HeartRateControlResumed })
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.resumeHeartRateControl())
        val resumed = rig.loadEvents().of(LoadEventType.HeartRateControlResumed)
        assertEquals(1, resumed.size)
        assertEquals(LoadEvent(resumed.single().seconds, LoadEventType.HeartRateControlResumed), resumed.single())
        assertEquals(rig.progress.totalElapsedSeconds, resumed.single().seconds)
        rig.runner.stop()
    }
}
