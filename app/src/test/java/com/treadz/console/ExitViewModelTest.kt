package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.lifecycle.ExitPrompt
import com.treadz.console.lifecycle.ExitViewModel
import com.treadz.console.lifecycle.finishOnExit
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.lifecycle.ExitRefusedReason
import com.treadz.console.treadmill.lifecycle.ExitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Back on the root screen: the dialog rules, Exit, Keep in background and the notification's exit. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExitViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val app = FakeApp()
    private val controller get() = app.controller
    private lateinit var vm: ExitViewModel
    private var finished = 0
    private var movedToBack = 0

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ExitViewModel(app.appLifecycle, controller)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun exit() = vm.exit(onExiting = { finished++ })

    // ---- The dialog: only a belt at rest, never a moving one ----

    @Test fun connectedWithTheBeltIdleAsksToExitAndMentionsTheTreadmill() {
        controller.showConnected()
        vm.onBack()
        assertEquals(ExitPrompt.Confirm(connected = true), vm.prompt.value)
    }

    @Test fun withoutAConnectionTheDialogDoesNotMentionTheTreadmill() {
        vm.onBack()
        assertEquals(ExitPrompt.Confirm(connected = false), vm.prompt.value)
    }

    @Test fun everyNonIdleStatusRefusesToExitAndTellsToPressStop() {
        controller.showConnected()
        for (status in BeltStatus.entries.filter { it != BeltStatus.Idle }) {
            controller.showState { it.copy(status = status, speedKmh = 0.0) }
            vm.onBack()
            assertEquals("status $status", ExitPrompt.BeltMoving, vm.prompt.value)
            vm.dismiss()
        }
    }

    @Test fun aBeltStillDeceleratingAfterAStopIsNotIdle() {
        controller.showConnected()
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.4) }
        vm.onBack()
        assertEquals(ExitPrompt.BeltMoving, vm.prompt.value)
    }

    @Test fun connectionLostWhileTheBeltWasRunningStillOffersToExit() {
        // The belt stops by itself when the connection is lost; the backend allows the exit without a connection.
        controller.showConnected()
        controller.showRunning()
        controller.showReconnecting()
        vm.onBack()
        assertEquals(ExitPrompt.Confirm(connected = false), vm.prompt.value)
    }

    // ---- Answers ----

    @Test fun exitCallsTheBackendDisconnectsAndFinishes() {
        controller.showConnected()
        vm.onBack()
        exit()
        assertEquals(1, app.appLifecycle.exitCalls)
        assertEquals(1, controller.disconnectCalls)
        assertEquals(1, finished)
        assertNull(vm.prompt.value)
    }

    @Test fun exitWithoutAConnectionFinishesToo() {
        vm.onBack()
        exit()
        assertEquals(1, finished)
    }

    @Test fun aBackendRefusalShowsTheStopMessageAndDoesNotFinish() {
        controller.showConnected()
        vm.onBack()
        app.appLifecycle.forcedExitResult = ExitResult.Refused(ExitRefusedReason.BeltNotIdle)
        exit()
        assertEquals(0, finished)
        assertEquals(ExitPrompt.BeltMoving, vm.prompt.value)
    }

    @Test fun aBeltThatStartsUnderTheDialogTurnsExitIntoTheStopMessage() {
        controller.showConnected()
        vm.onBack()
        controller.showRunning() // started from the console while the dialog was open
        exit()
        assertEquals(0, finished)
        assertEquals(0, controller.disconnectCalls)
        assertEquals(ExitPrompt.BeltMoving, vm.prompt.value)
    }

    @Test fun keepInBackgroundMovesTheTaskAndStaysConnected() {
        controller.showConnected()
        vm.onBack()
        vm.keepInBackground { movedToBack++ }
        assertEquals(1, movedToBack)
        assertEquals(0, app.appLifecycle.exitCalls)
        assertEquals(0, controller.disconnectCalls)
        assertEquals(0, finished)
        assertNull(vm.prompt.value)
    }

    @Test fun cancelJustClosesTheDialog() {
        controller.showConnected()
        vm.onBack()
        vm.dismiss()
        assertNull(vm.prompt.value)
        assertEquals(0, app.appLifecycle.exitCalls)
        assertEquals(0, finished)
        assertEquals(0, movedToBack)
    }

    @Test fun exitTappedTwiceAsksTheBackendOnce() {
        controller.showConnected()
        vm.onBack()
        val gate = CompletableDeferred<Unit>()
        val slow = ExitViewModel(
            object : com.treadz.console.treadmill.lifecycle.AppLifecycle by app.appLifecycle {
                override suspend fun exitApp(): ExitResult {
                    gate.await()
                    return app.appLifecycle.exitApp()
                }
            },
            controller,
        )
        slow.exit { finished++ }
        slow.exit { finished++ }
        gate.complete(Unit)
        assertEquals(1, finished)
        assertEquals(1, app.appLifecycle.exitCalls)
    }

    @Test fun theStopMessageDisappearsOnItsOwn() {
        controller.showConnected()
        controller.showRunning()
        vm.onBack()
        assertEquals(ExitPrompt.BeltMoving, vm.prompt.value)
        dispatcher.scheduler.advanceTimeBy(ExitViewModel.BELT_MOVING_NOTICE_MILLIS - 1)
        assertEquals(ExitPrompt.BeltMoving, vm.prompt.value)
        dispatcher.scheduler.advanceTimeBy(2)
        assertNull(vm.prompt.value)
    }

    // ---- Exit requested from the notification ----

    @Test fun anExitRequestFromTheNotificationFinishesTheActivity() {
        val scope = TestScope(dispatcher)
        finishOnExit(scope, app.appLifecycle) { finished++ }
        app.appLifecycle.requestExitFromNotification()
        assertEquals(1, finished)
        app.appLifecycle.requestExitFromNotification()
        assertEquals(2, finished)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun anExitFromTheBackDialogAlsoReachesTheFinishCollector() {
        // The activity finishes twice (the dialog's own callback and the collector): harmless.
        val scope = TestScope(dispatcher)
        var collectorFinishes = 0
        finishOnExit(scope, app.appLifecycle) { collectorFinishes++ }
        exit()
        assertEquals(1, finished)
        assertEquals(1, collectorFinishes)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
