package com.treadz.console

import com.treadz.console.controls.ControlAction
import com.treadz.console.controls.ControlAxis
import com.treadz.console.controls.ManualControls
import com.treadz.console.fakes.FakeApp
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillCapabilities
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.ValueRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Speed and incline control with coalescing, auto-repeat, presets, pause/resume/STOP and failure messages, on the real
 * [MainViewModel] and a fake treadmill. Time is virtual: [scheduler] drives the auto-repeat and the failure timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualControlsTest {

    private val scheduler = TestCoroutineScheduler()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        controller.showConnected()
        controller.showRunning() // 8.5 km/h, 3 %
        controller.showState { it.copy(speedKmh = 8.0, targetSpeedKmh = 8.0, inclinePercent = 3.0) }
        vm
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val app = FakeApp()
    private val controller get() = app.controller
    private val vm get() = app.viewModel
    private val controls get() = vm.controls
    private val ui get() = vm.uiState.value

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /** Makes every speed command wait until the test completes it, and records how many ran at the same time. */
    private inner class SlowSpeed {
        val gates = ArrayDeque<CompletableDeferred<CommandResult>>()
        var running = 0
        var maxRunning = 0

        init {
            controller.setSpeedHandler = { kmh ->
                running++
                maxRunning = maxOf(maxRunning, running)
                val gate = CompletableDeferred<CommandResult>()
                gates += gate
                val result = gate.await()
                running--
                if (result is CommandResult.Success) controller.showState { it.copy(targetSpeedKmh = kmh) }
                result
            }
        }

        fun finishOldest(result: CommandResult = CommandResult.Success) = gates.removeFirst().complete(result)
    }

    // ---- Coalescing ----

    @Test fun rapidTapsSendOnlyTheLatestTargetWithOneCommandInFlight() {
        val slow = SlowSpeed()
        repeat(5) { controls.stepSpeed(+1) }
        // The first tap went out; the other four only moved the pending target.
        assertEquals(listOf(8.1), controller.speedCommands)
        assertEquals(8.5, ui.controls.pendingSpeed!!, 0.0001)
        assertTrue(ui.controls.speedInFlight)
        assertEquals(8.5, ui.targetSpeed!!, 0.0001)

        slow.finishOldest()
        // The command finished: only the latest target is sent, the values in between are skipped.
        assertEquals(listOf(8.1, 8.5), controller.speedCommands)
        assertEquals(8.5, ui.controls.pendingSpeed!!, 0.0001)
        slow.finishOldest()
        assertEquals(listOf(8.1, 8.5), controller.speedCommands)
        assertNull(ui.controls.pendingSpeed)
        assertFalse(ui.controls.speedInFlight)
        assertEquals(1, slow.maxRunning)
    }

    @Test fun tapsAreComputedFromThePendingTargetNotFromTheStaleOne() {
        SlowSpeed()
        controls.stepSpeed(+1)
        assertEquals(8.0, ui.treadmill.targetSpeedKmh!!, 0.0001) // the treadmill has not confirmed anything yet
        controls.stepSpeed(+1)
        controls.stepSpeed(+1)
        assertEquals(8.3, ui.controls.pendingSpeed!!, 0.0001) // 8.0 + 3 x 0.1, not 8.1 each time
    }

    @Test fun tappingUpAndDownBackToTheSameValueSendsNothingMore() {
        val slow = SlowSpeed()
        controls.stepSpeed(+1)
        controls.stepSpeed(-1) // pending is 8.0 again, but 8.1 is in flight
        slow.finishOldest()
        // 8.1 was sent and confirmed: now the pending 8.0 differs from the target and is sent.
        assertEquals(listOf(8.1, 8.0), controller.speedCommands)
    }

    @Test fun anIdleTapAndBackSendsNothing() {
        controls.setSpeed(8.0) // already the target
        assertTrue(controller.speedCommands.isEmpty())
        assertNull(ui.controls.pendingSpeed)
    }

    @Test fun inclineCoalescesTheSameWay() {
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        controller.setInclineHandler = { percent ->
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
            controller.showState { it.copy(inclinePercent = percent) }
            CommandResult.Success
        }
        repeat(4) { controls.stepIncline(+1) }
        assertEquals(listOf(4.0), controller.inclineCommands)
        assertEquals(7.0, ui.controls.pendingIncline!!, 0.0001)
        assertEquals(7.0, ui.targetIncline!!, 0.0001)
        gates.removeFirst().complete(Unit)
        assertEquals(listOf(4.0, 7.0), controller.inclineCommands)
        gates.removeFirst().complete(Unit)
        assertNull(ui.controls.pendingIncline)
        assertEquals(7.0, ui.treadmill.inclinePercent, 0.0001)
    }

    @Test fun speedAndInclineGoThroughTheirOwnQueues() {
        SlowSpeed()
        controls.stepSpeed(+1)
        controls.stepIncline(+1) // does not wait for the speed command
        assertEquals(listOf(8.1), controller.speedCommands)
        assertEquals(listOf(4.0), controller.inclineCommands)
    }

    @Test fun targetsAreClampedToTheTreadmillRange() {
        controller.showState { it.copy(speedKmh = 20.0, targetSpeedKmh = 20.0, inclinePercent = 18.0) }
        controls.stepSpeed(+1)
        controls.stepIncline(+1)
        assertTrue(controller.speedCommands.isEmpty()) // 20.0 is the maximum: nothing to send
        assertTrue(controller.inclineCommands.isEmpty())
        controls.setSpeed(35.0)
        assertTrue(controller.speedCommands.isEmpty())

        controller.showState { it.copy(speedKmh = 0.5, targetSpeedKmh = 0.5, inclinePercent = 0.0) }
        controls.stepSpeed(-1)
        controls.stepIncline(-1)
        assertTrue(controller.speedCommands.isEmpty())
        assertTrue(controller.inclineCommands.isEmpty())
    }

    @Test fun presetsAreClampedToTheCapabilities() {
        controller.capabilities.value = TreadmillCapabilities(
            speed = ValueRange(1.0, 11.0, 0.1),
            incline = ValueRange(0.0, 10.0, 1.0),
            manufacturer = null,
            model = null,
            firmware = null,
        )
        controls.setSpeed(12.0)
        controls.setSpeed(4.0)
        assertEquals(listOf(11.0, 4.0), controller.speedCommands)
        assertEquals(11.0, ui.controls.speedRange.max, 0.0)
    }

    @Test fun presetsUseTheDecidedValuesAndRangeFallsBackWithoutCapabilities() {
        assertEquals(listOf(4.0, 6.0, 8.0, 10.0, 12.0), ManualControls.SPEED_PRESETS)
        controller.capabilities.value = null
        assertEquals(0.5, ui.controls.speedRange.min, 0.0)
        assertEquals(20.0, ui.controls.speedRange.max, 0.0)
        assertEquals(18.0, ui.controls.inclineRange.max, 0.0)
        controls.setIncline(30.0)
        assertEquals(listOf(18.0), controller.inclineCommands)
    }

    @Test fun inclinePresetsAreZeroThreeSixNineTwelve() {
        assertEquals(listOf(0.0, 3.0, 6.0, 9.0, 12.0), ManualControls.INCLINE_PRESETS)
    }

    @Test fun inclinePresetsAreCoalescedLikeEveryOtherTarget() {
        val gates = ArrayDeque<CompletableDeferred<CommandResult>>()
        controller.setInclineHandler = { percent ->
            val gate = CompletableDeferred<CommandResult>()
            gates += gate
            val result = gate.await()
            controller.showState { it.copy(inclinePercent = percent) }
            result
        }
        // 3 % is where the belt already is; 6, 9 and 12 are tapped while the first command is still being verified.
        for (preset in listOf(6.0, 9.0, 12.0, 0.0, 12.0)) controls.setIncline(preset)
        assertEquals(listOf(6.0), controller.inclineCommands)
        assertEquals(12.0, ui.controls.pendingIncline!!, 0.0)
        assertEquals(12.0, ui.targetIncline!!, 0.0)

        gates.removeFirst().complete(CommandResult.Success)
        // Only the latest target follows; 9, 12 and 0 in between are skipped.
        assertEquals(listOf(6.0, 12.0), controller.inclineCommands)
        gates.removeFirst().complete(CommandResult.Success)
        assertEquals(listOf(6.0, 12.0), controller.inclineCommands)
        assertNull(ui.controls.pendingIncline)
    }

    @Test fun anInclinePresetOnlyWorksWhileTheBeltRuns() {
        controller.showState { it.copy(status = BeltStatus.Paused) }
        controls.setIncline(6.0)
        assertTrue(controller.inclineCommands.isEmpty())
    }

    @Test fun stepsAreOneTenthOfAKmhAndOneWholePercent() {
        controls.stepSpeed(+1)
        controls.stepIncline(-1)
        assertEquals(listOf(8.1), controller.speedCommands)
        assertEquals(listOf(2.0), controller.inclineCommands)
        // No binary noise: ten steps up are exactly 9.0.
        repeat(9) { controls.stepSpeed(+1) }
        assertEquals(9.0, controller.speedCommands.last(), 0.0)
    }

    // ---- Auto-repeat ----

    @Test fun aShortPressIsOneStep() {
        controls.startRepeat(ControlAxis.Speed, +1)
        advance(100)
        controls.stopRepeat()
        advance(5_000)
        assertEquals(listOf(8.1), controller.speedCommands)
    }

    @Test fun holdingRepeatsAndSpeedsUp() {
        controls.startRepeat(ControlAxis.Speed, +1)
        assertEquals(1, controller.speedCommands.size)
        advance(ManualControls.REPEAT_START_DELAY_MILLIS - 1)
        assertEquals(1, controller.speedCommands.size) // nothing before the initial pause
        advance(2)
        assertEquals(2, controller.speedCommands.size)
        advance(1_000)
        val afterOneSecond = controller.speedCommands.size
        assertTrue("expected a quick repetition, got $afterOneSecond", afterOneSecond in 6..40)
        // The rhythm accelerates: the next second brings more steps than the first one did.
        advance(1_000)
        val secondSecond = controller.speedCommands.size - afterOneSecond
        assertTrue(secondSecond > afterOneSecond - 2)
        controls.stopRepeat()
        val stopped = controller.speedCommands.size
        advance(5_000)
        assertEquals(stopped, controller.speedCommands.size)
    }

    @Test fun repeatGoesDownWhenHoldingMinus() {
        controls.startRepeat(ControlAxis.Incline, -1)
        advance(3_000)
        controls.stopRepeat()
        assertEquals(0.0, controller.inclineCommands.last(), 0.0)
        assertTrue(controller.inclineCommands.size <= 3) // 3 % to 0 %, clamped
    }

    @Test fun repeatStopsByItselfWhenTheBeltLeavesRunning() {
        controls.startRepeat(ControlAxis.Speed, +1)
        advance(1_000)
        controller.showState { it.copy(status = BeltStatus.Paused) }
        val before = controller.speedCommands.size
        advance(5_000)
        assertEquals(before, controller.speedCommands.size)
    }

    @Test fun repeatNeverRunsForeverIfTheReleaseIsLost() {
        controls.startRepeat(ControlAxis.Speed, +1)
        advance(ManualControls.REPEAT_MAX_MILLIS + 1_000)
        val before = controller.speedCommands.size
        controller.showState { it.copy(speedKmh = 1.0, targetSpeedKmh = 1.0) }
        advance(10_000)
        assertEquals(before, controller.speedCommands.size)
    }

    @Test fun repeatIsCoalescedWithASlowTreadmill() {
        val slow = SlowSpeed()
        controls.startRepeat(ControlAxis.Speed, +1)
        advance(1_500)
        controls.stopRepeat()
        // Dozens of steps, but only one command in flight at a time.
        assertEquals(1, slow.maxRunning)
        assertEquals(1, controller.speedCommands.size)
        val pending = ui.controls.pendingSpeed!!
        assertTrue(pending > 8.5)
        slow.finishOldest()
        assertEquals(2, controller.speedCommands.size)
        assertEquals(pending, controller.speedCommands.last(), 0.0001)
    }

    // ---- When the controls work ----

    @Test fun nothingWorksWithoutAnActiveUserExceptStop() {
        runBlocking { app.userData.profiles.setActiveUser(null) }
        assertNull(ui.activeUser)
        controls.stepSpeed(+1)
        controls.stepIncline(+1)
        controls.setSpeed(10.0)
        controls.startRepeat(ControlAxis.Speed, +1)
        controls.pause()
        assertEquals(0, controller.manualCommandCount)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
        controls.stop()
        assertEquals(1, controller.stopCalls)
    }

    @Test fun nothingAdjustsWithTheBeltPausedStartingOrIdle() {
        for (status in listOf(BeltStatus.Paused, BeltStatus.Starting, BeltStatus.Idle, BeltStatus.SafetyKeyRemoved)) {
            controller.showState { it.copy(status = status) }
            controls.stepSpeed(+1)
            controls.setIncline(5.0)
            assertEquals("status $status", 0, controller.manualCommandCount)
        }
    }

    @Test fun nothingAdjustsWithoutConnectionOrWithStaleData() {
        controller.showReconnecting()
        controls.stepSpeed(+1)
        assertEquals(0, controller.manualCommandCount)
        controller.showConnected()
        controller.showState { it.copy(isStale = true) }
        controls.stepSpeed(+1)
        controls.pause()
        assertEquals(0, controller.manualCommandCount)
    }

    // ---- Pause, resume, STOP ----

    @Test fun pauseWorksOnlyWithTheBeltRunning() {
        controls.pause()
        assertEquals(1, controller.pauseCalls)
        assertEquals(BeltStatus.Paused, ui.treadmill.status)
        controls.pause() // already paused
        assertEquals(1, controller.pauseCalls)
    }

    @Test fun resumeWaitsUntilTheBeltHasStopped() {
        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 3.0) }
        assertFalse(ui.canResume)
        assertTrue(ui.resumeWaitsForBelt)
        controls.resume()
        assertEquals(0, controller.resumeCalls)

        controller.showState { it.copy(speedKmh = 0.0) }
        assertTrue(ui.canResume)
        assertFalse(ui.resumeWaitsForBelt)
        controls.resume()
        assertEquals(1, controller.resumeCalls)
    }

    @Test fun stopIsAlwaysSentAndDropsWhatWasPending() {
        SlowSpeed()
        repeat(3) { controls.stepSpeed(+1) }
        assertNotNull(ui.controls.pendingSpeed)
        controls.stop()
        assertEquals(1, controller.stopCalls)
        assertNull(ui.controls.pendingSpeed)
        assertFalse(ui.controls.stopInFlight)
    }

    @Test fun stopIsSentEvenWhenTheAppThinksItIsNotConnected() {
        // The controller answers NotConnected: the UI shows the "use the console" message, but it still tries.
        controller.showReconnecting()
        controller.stopResult = CommandResult.NotConnected
        controls.stop()
        assertEquals(1, controller.stopCalls)
        assertNull(ui.controls.failure) // no extra message: critical failures are full-screen alerts
    }

    @Test fun stopFailureAddsNoMessageBecauseTheAlertCoversIt() {
        controller.stopResult = CommandResult.NotVerified(FailureCode.StopNotConfirmed, "no 02")
        controls.stop()
        assertNull(ui.controls.failure)
    }

    @Test fun pausePressedTwiceWhileVerifyingSendsOnce() {
        val gate = CompletableDeferred<Unit>()
        var pauseCalls = 0
        val slow = object : com.treadz.console.treadmill.TreadmillController by controller {
            override suspend fun pause(): CommandResult {
                pauseCalls++
                gate.await()
                return CommandResult.Success
            }
        }
        val own = ManualControls(slow, kotlinx.coroutines.CoroutineScope(Dispatchers.Main))
        own.pause()
        assertTrue(own.state.value.pauseInFlight)
        own.pause()
        assertEquals(1, pauseCalls)
        gate.complete(Unit)
        assertFalse(own.state.value.pauseInFlight)
    }

    // ---- The treadmill changes under us ----

    @Test fun aManualChangeFromTheConsoleResetsThePendingTarget() {
        SlowSpeed()
        controller.setInclineHandler = { awaitCancellation() }
        repeat(3) { controls.stepSpeed(+1) }
        controls.stepIncline(+1)
        assertNotNull(ui.controls.pendingSpeed)
        assertNotNull(ui.controls.pendingIncline)

        controller.emit(TreadmillEvent.ManualChange(speedKmh = 9.0, inclinePercent = null))
        assertNull(ui.controls.pendingSpeed)
        assertNotNull(ui.controls.pendingIncline) // the incline was not touched

        controller.emit(TreadmillEvent.ManualChange(speedKmh = null, inclinePercent = 5.0))
        assertNull(ui.controls.pendingIncline)
    }

    @Test fun afterAManualChangeTheNextTapStartsFromTheNewValue() {
        controller.showState { it.copy(targetSpeedKmh = 8.0) }
        controls.stepSpeed(+1)
        controller.emit(TreadmillEvent.ManualChange(speedKmh = 12.0, inclinePercent = null))
        controller.showState { it.copy(speedKmh = 12.0, targetSpeedKmh = 12.0) }
        controls.stepSpeed(+1)
        assertEquals(12.1, controller.speedCommands.last(), 0.0001)
    }

    @Test fun pausingOrLosingTheConnectionResetsThePendingTargets() {
        SlowSpeed()
        controls.stepSpeed(+1)
        controls.stepSpeed(+1)
        controller.showState { it.copy(status = BeltStatus.Paused) }
        assertNull(ui.controls.pendingSpeed)

        controller.showState { it.copy(status = BeltStatus.Running) }
        controls.stepSpeed(+1)
        controls.stepSpeed(+1)
        assertNotNull(ui.controls.pendingSpeed)
        controller.showReconnecting()
        assertNull(ui.controls.pendingSpeed)
        controller.showConnected()
        assertNull(ui.controls.pendingSpeed)
    }

    // ---- Feedback ----

    @Test fun aFailedSpeedCommandShowsAMessageAndDropsThePendingTarget() {
        val slow = SlowSpeed()
        controls.stepSpeed(+1)
        controls.stepSpeed(+1)
        slow.finishOldest(CommandResult.Rejected(FailureCode.RejectedByTreadmill, "no"))
        val failure = ui.controls.failure!!
        assertEquals(ControlAction.Speed, failure.action)
        assertEquals(CommandResult.Rejected(FailureCode.RejectedByTreadmill, "no"), failure.result)
        assertNull(ui.controls.pendingSpeed)
        // The intermediate target is not sent after a failure.
        assertEquals(1, controller.speedCommands.size)
        assertFalse(ui.controls.speedInFlight)
    }

    @Test fun aCommandTheTreadmillDidNotCarryOutIsAnAlertNotAnExtraMessage() {
        val slow = SlowSpeed()
        controls.stepSpeed(+1)
        slow.finishOldest(CommandResult.NotVerified(FailureCode.TargetNotReached, "slow"))
        // The controller raised the full-screen alert; the panel does not repeat it.
        assertEquals(TreadmillEvent.CommandNotExecuted("setSpeed"), ui.alert)
        assertNull(ui.controls.failure)
        assertNull(ui.controls.pendingSpeed)
    }

    @Test fun theMessageDisappearsByItselfAndWithTheNextTap() {
        controller.setInclineHandler = { CommandResult.Rejected(FailureCode.RejectedByTreadmill, "no") }
        controls.stepIncline(+1)
        assertEquals(ControlAction.Incline, ui.controls.failure!!.action)
        advance(ManualControls.FAILURE_VISIBLE_MILLIS - 100)
        assertNotNull(ui.controls.failure)
        advance(200)
        assertNull(ui.controls.failure)

        controls.stepIncline(+1)
        assertNotNull(ui.controls.failure)
        controller.setInclineHandler = { CommandResult.Success }
        controls.stepIncline(+1)
        assertNull(ui.controls.failure)
    }

    @Test fun aNewFailureIsNotErasedByTheTimerOfTheOldOne() {
        controller.setInclineHandler = { CommandResult.Rejected(FailureCode.RejectedByTreadmill, "no") }
        controls.stepIncline(+1)
        advance(5_000)
        controls.stepIncline(+1) // a second failure, 5 s after the first
        advance(2_000) // the first one's timer fires now
        assertNotNull(ui.controls.failure)
        advance(5_000)
        assertNull(ui.controls.failure)
    }

    @Test fun aStopThatSupersededTheCommandIsNotAnError() {
        controller.setSpeedHandler = { CommandResult.NotVerified(FailureCode.Preempted, "stop") }
        controls.stepSpeed(+1)
        assertNull(ui.controls.failure)
        assertNull(ui.controls.pendingSpeed)
    }

    @Test fun pauseAndResumeFailuresAreReportedWhenNoAlertCoversThem() {
        // Pausing a belt that is not running is a refusal, not an unexecuted command.
        controller.pauseResult = CommandResult.NotAllowed(FailureCode.BeltNotRunning, "x")
        controls.pause()
        assertEquals(ControlAction.Pause, ui.controls.failure!!.action)
        controls.dismissFailure()
        assertNull(ui.controls.failure)

        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 0.0) }
        controller.resumeResult = CommandResult.Rejected(FailureCode.RejectedByTreadmill, "x")
        controls.resume()
        assertEquals(ControlAction.Resume, ui.controls.failure!!.action)
    }

    @Test fun aPauseTheTreadmillDidNotExecuteIsAnAlert() {
        controller.pauseResult = CommandResult.NotVerified(FailureCode.StillMoving, "x")
        controls.pause()
        assertEquals(TreadmillEvent.CommandNotExecuted("pause"), ui.alert)
        assertNull(ui.controls.failure)
    }

    @Test fun notConnectedIsReportedAsFailure() {
        controller.setSpeedHandler = { CommandResult.NotConnected }
        controls.stepSpeed(+1)
        assertEquals(CommandResult.NotConnected, ui.controls.failure!!.result)
    }
}
