package com.treadz.console.treadmill.notification

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ServiceNotificationModelTest {

    private val connected = ConnectionState.Connected("AA:BB", "REEBOK 0517")

    private fun map(
        status: BeltStatus,
        speed: Double = 0.0,
        alerts: List<TreadmillAlert> = emptyList(),
        stop: StopRequest = StopRequest.None,
        stale: Boolean = false,
        connection: ConnectionState = connected,
    ) = ServiceNotificationMapper.map(connection, TreadmillState(status = status, speedKmh = speed, isStale = stale), alerts, stop)

    // ------------------------------------------------------------------ the STOP action

    @Test
    fun `the STOP action shows while the belt is Starting, Running or Paused and connected`() {
        assertTrue(map(BeltStatus.Starting).showStopAction)
        assertTrue(map(BeltStatus.Running, 5.0).showStopAction)
        assertTrue(map(BeltStatus.Paused).showStopAction, "STOP also ends a paused session")
        assertTrue(map(BeltStatus.Paused, 2.0).showStopAction, "and a pause that is still decelerating")
    }

    @Test
    fun `the STOP action is hidden when the belt is Idle, with the safety key removed or without a connection`() {
        assertFalse(map(BeltStatus.Idle).showStopAction)
        assertFalse(map(BeltStatus.SafetyKeyRemoved).showStopAction)
        assertFalse(map(BeltStatus.Idle, 3.0).showStopAction, "a stop in progress needs no second tap")
        listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting("AA:BB"),
            ConnectionState.Reconnecting("AA:BB", 2),
        ).forEach { c ->
            assertFalse(map(BeltStatus.Running, 5.0, connection = c).showStopAction, "$c")
        }
    }

    // ------------------------------------------------------------------ texts

    @Test
    fun `the message follows the connection and the belt state`() {
        assertEquals(NotificationMessage.Disconnected, map(BeltStatus.Idle, connection = ConnectionState.Disconnected).message)
        assertEquals(NotificationMessage.Connecting, map(BeltStatus.Idle, connection = ConnectionState.Connecting("AA")).message)
        val reconnecting = map(BeltStatus.Running, 5.0, connection = ConnectionState.Reconnecting("AA", 3))
        assertEquals(NotificationMessage.Reconnecting, reconnecting.message)
        assertEquals(3, reconnecting.attempt)
        assertEquals(NotificationMessage.Idle, map(BeltStatus.Idle).message)
        assertEquals(NotificationMessage.Starting, map(BeltStatus.Starting).message)
        assertEquals(NotificationMessage.Paused, map(BeltStatus.Paused).message)
        assertEquals(NotificationMessage.KeyRemoved, map(BeltStatus.SafetyKeyRemoved).message)
        val running = map(BeltStatus.Running, 12.34)
        assertEquals(NotificationMessage.Running, running.message)
        assertEquals(12.3, running.speedKmh, "speed rounded to 0.1 so identical texts are not posted twice")
    }

    @Test
    fun `a belt that is decelerating after Stop or Pause says so with its speed`() {
        val stopping = map(BeltStatus.Idle, 9.0)
        assertEquals(NotificationMessage.Stopping, stopping.message)
        assertEquals(9.0, stopping.speedKmh)
        val pausing = map(BeltStatus.Paused, 7.5)
        assertEquals(NotificationMessage.Pausing, pausing.message)
        assertEquals(7.5, pausing.speedKmh)
        assertFalse(pausing.urgent)
    }

    @Test
    fun `a stop requested from the notification reads stopping straight away`() {
        val m = map(BeltStatus.Running, 18.0, stop = StopRequest.InFlight)
        assertEquals(NotificationMessage.Stopping, m.message)
        assertEquals(18.0, m.speedKmh)
        assertEquals(NotificationMessage.Stopping, map(BeltStatus.Starting, stop = StopRequest.InFlight).message)
        assertEquals(NotificationMessage.Stopping, map(BeltStatus.Paused, stop = StopRequest.InFlight).message)
        assertFalse(m.urgent)
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a stop that failed says so, is urgent and keeps the STOP action to retry`() {
        val fromController = map(BeltStatus.Running, 6.0, alerts = listOf(TreadmillEvent.CommandNotExecuted("stop")))
        assertEquals(NotificationMessage.StopFailed, fromController.message)
        assertTrue(fromController.urgent)
        assertTrue(fromController.showStopAction)
        assertEquals(6.0, fromController.speedKmh)

        val fromService = map(BeltStatus.Running, 6.0, stop = StopRequest.Failed)
        assertEquals(NotificationMessage.StopFailed, fromService.message)
        assertTrue(fromService.urgent)

        // A pause that did not execute is just as dangerous
        assertEquals(NotificationMessage.StopFailed, map(BeltStatus.Running, 6.0, alerts = listOf(TreadmillEvent.CommandNotExecuted("pause"))).message)
        // A failed speed command is not a reason to alarm
        val speedAlert = map(BeltStatus.Running, 6.0, alerts = listOf(TreadmillEvent.CommandNotExecuted("setSpeed")))
        assertEquals(NotificationMessage.Running, speedAlert.message)
        assertFalse(speedAlert.urgent)
    }

    @Test
    fun `a stop failure is not shown once the belt is not moving`() {
        val m = map(BeltStatus.Idle, 0.0, alerts = listOf(TreadmillEvent.CommandNotExecuted("stop")), stop = StopRequest.Failed)
        assertEquals(NotificationMessage.Idle, m.message)
        assertFalse(m.urgent)
        assertEquals(NotificationMessage.Paused, map(BeltStatus.Paused, 0.0, stop = StopRequest.Failed).message)
    }

    @Test
    fun `stale data with the belt running is urgent too`() {
        val stalled = map(BeltStatus.Running, 8.0, alerts = listOf(TreadmillEvent.DataStalled))
        assertEquals(NotificationMessage.DataStalled, stalled.message)
        assertTrue(stalled.urgent)
        assertTrue(stalled.showStopAction)
        assertEquals(NotificationMessage.DataStalled, map(BeltStatus.Running, 8.0, stale = true).message)
        // Nothing to worry about with the belt at rest
        assertEquals(NotificationMessage.Idle, map(BeltStatus.Idle, 0.0, alerts = listOf(TreadmillEvent.DataStalled)).message)
        // A failed stop outranks it
        assertEquals(
            NotificationMessage.StopFailed,
            map(BeltStatus.Running, 8.0, alerts = listOf(TreadmillEvent.DataStalled, TreadmillEvent.CommandNotExecuted("stop"))).message,
        )
    }

    @Test
    fun `the safety key removed is reported even with old alerts pending`() {
        val m = map(BeltStatus.SafetyKeyRemoved, alerts = listOf(TreadmillEvent.SafetyKeyRemoved, TreadmillEvent.CommandNotExecuted("stop")))
        assertEquals(NotificationMessage.KeyRemoved, m.message)
        assertFalse(m.showStopAction)
        assertFalse(m.urgent)
    }

    // ------------------------------------------------------------------ the request tracked by the service

    @Test
    fun `a stop result maps to the request state`() {
        assertEquals(StopRequest.None, StopRequest.afterResult(CommandResult.Success))
        assertEquals(StopRequest.None, StopRequest.afterResult(CommandResult.NotConnected))
        assertEquals(StopRequest.Failed, StopRequest.afterResult(CommandResult.NotVerified(FailureCode.StillMoving, "x")))
        assertEquals(StopRequest.Failed, StopRequest.afterResult(CommandResult.Rejected(FailureCode.RejectedByTreadmill, "x")))
        assertEquals(StopRequest.Failed, StopRequest.afterResult(CommandResult.NotAllowed(FailureCode.BeltNotRunning, "x")))
    }

    @ParameterizedTest
    @EnumSource(StopRequest::class)
    fun `a failed request is forgotten when the belt is no longer moving, the others are kept`(request: StopRequest) {
        val rest = TreadmillState(status = BeltStatus.Idle, speedKmh = 0.0)
        val moving = TreadmillState(status = BeltStatus.Running, speedKmh = 5.0)
        assertEquals(if (request == StopRequest.Failed) StopRequest.None else request, request.settle(rest))
        assertEquals(request, request.settle(moving))
    }
}
