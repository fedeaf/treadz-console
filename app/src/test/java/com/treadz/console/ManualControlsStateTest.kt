package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** What the screen shows and enables for each belt status and connection state (the rules of the manual controls). */
class ManualControlsStateTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val app = FakeApp()
    private val controller get() = app.controller
    private val ui get() = app.viewModel.uiState.value

    private fun belt(status: BeltStatus, speed: Double = 0.0) {
        controller.showConnected()
        controller.showState { it.copy(status = status, speedKmh = speed) }
    }

    @Test fun idleBeltHasNoControlsAndOffersStart() {
        belt(BeltStatus.Idle)
        assertFalse(ui.beltActive)
        assertFalse(ui.showStop)
        assertFalse(ui.stopUnreachable)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
        assertTrue(ui.canStart)
    }

    @Test fun startingShowsStopButNothingElseWorks() {
        belt(BeltStatus.Starting)
        assertTrue(ui.showStop)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
        assertFalse(ui.canResume)
        assertFalse(ui.canStart)
    }

    @Test fun runningEnablesEverything() {
        belt(BeltStatus.Running, 8.0)
        assertTrue(ui.showStop)
        assertTrue(ui.canAdjust)
        assertTrue(ui.canPause)
        assertFalse(ui.canResume)
        assertFalse(ui.isPaused)
    }

    @Test fun pausedEnablesOnlyResumeAndOnlyOnceTheBeltStopped() {
        belt(BeltStatus.Paused, speed = 2.0)
        assertTrue(ui.showStop)
        assertTrue(ui.isPaused)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
        assertFalse(ui.canResume)
        assertTrue(ui.resumeWaitsForBelt)
        belt(BeltStatus.Paused, speed = 0.0)
        assertTrue(ui.canResume)
        assertFalse(ui.resumeWaitsForBelt)
        assertFalse(ui.canAdjust)
    }

    @Test fun stopStaysWhileTheBeltIsStillDeceleratingAfterAStop() {
        // After STOP the status is Idle at once but the belt takes a while to stop.
        belt(BeltStatus.Idle, speed = 6.0)
        assertTrue(ui.beltActive)
        assertTrue(ui.showStop)
        assertFalse(ui.canStart) // starting now would be refused by the treadmill
        assertTrue(ui.keepScreenOn)
        belt(BeltStatus.Idle, speed = 0.0)
        assertFalse(ui.beltActive)
        assertFalse(ui.keepScreenOn)
    }

    @Test fun keyRemovedShowsStopButNoControls() {
        belt(BeltStatus.SafetyKeyRemoved)
        assertTrue(ui.showStop)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
    }

    @Test fun withoutAnActiveUserStopIsStillThereButNothingElse() {
        belt(BeltStatus.Running, 8.0)
        runBlocking { app.userData.profiles.setActiveUser(null) }
        assertTrue(ui.showStop)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
        belt(BeltStatus.Paused, 0.0)
        assertFalse(ui.canResume)
    }

    @Test fun withoutConnectionStopIsReplacedByTheConsoleInstruction() {
        belt(BeltStatus.Running, 8.0)
        controller.showReconnecting()
        assertFalse(ui.showStop)
        assertTrue(ui.stopUnreachable)
        assertFalse(ui.canAdjust)
        controller.connection.value = ConnectionState.Disconnected
        assertTrue(ui.stopUnreachable)
        // Back online: STOP again.
        controller.showConnected()
        assertTrue(ui.showStop)
        assertFalse(ui.stopUnreachable)
    }

    @Test fun staleDataDisablesTheControlsButNotStop() {
        belt(BeltStatus.Running, 8.0)
        controller.showState { it.copy(isStale = true) }
        assertTrue(ui.showStop)
        assertFalse(ui.canAdjust)
        assertFalse(ui.canPause)
    }

    @Test fun targetsAreOnlyShownWhileRunning() {
        belt(BeltStatus.Running, 8.0)
        controller.showState { it.copy(targetSpeedKmh = 9.0) }
        assertEquals(9.0, ui.targetSpeed!!, 0.0)
        app.viewModel.controls.setSpeed(10.0)
        assertEquals(10.0, ui.targetSpeed!!, 0.0) // what the user asked for wins
        belt(BeltStatus.Paused, 0.0)
        assertNull(ui.targetSpeed)
        assertNull(ui.targetIncline)
    }
}
