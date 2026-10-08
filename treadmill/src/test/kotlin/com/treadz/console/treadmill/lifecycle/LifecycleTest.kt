package com.treadz.console.treadmill.lifecycle

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Rig
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.notNull
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app lifecycle on virtual time: nothing is scanned or (re)connected in the background, the reconnection resumes
 * when the app is back, the connection-lost notice goes away after a few seconds, an idle treadmill is let go after a
 * while in the background, and exiting is refused while the belt is not idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleTest {

    private class Fixture(val rig: Rig, val lifecycle: AppLifecycleManager, private val scheduler: TestCoroutineScheduler) {
        val controller get() = rig.controller
        val sim get() = rig.sim

        /** Every value of `serviceWanted`, in order. */
        val serviceWanted = CopyOnWriteArrayList<Boolean>()
        val exitRequests = CopyOnWriteArrayList<Unit>()
        val notices = CopyOnWriteArrayList<ConnectionLossNotice?>()

        /** The collectors run on the test dispatcher: let them see what has just happened. */
        fun settle() = scheduler.runCurrent()

        val wantsService: Boolean get() = run { settle(); serviceWanted.lastOrNull() ?: false }
        fun background() = lifecycle.setAppInForeground(false)
        fun foreground() = lifecycle.setAppInForeground(true)
        val scans: Int get() = sim.scanStartTimes.size
        fun exitCount(): Int { settle(); return exitRequests.size }
    }

    private fun TestScope.fixture(
        rate: DataRate = DataRate.Hz2Repeated,
        config: LifecycleConfig = LifecycleConfig(),
    ): Fixture {
        val rig = Rig(this, rate)
        val lifecycle = AppLifecycleManager(rig.controller, rig.controller, backgroundScope, config)
        return Fixture(rig, lifecycle, testScheduler).also { f ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { lifecycle.serviceWanted.collect { f.serviceWanted += it } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { lifecycle.exitRequests.collect { f.exitRequests += it } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { lifecycle.lossNotice.collect { f.notices += it } }
        }
    }

    private fun Fixture.notes() = rig.trace.filterIsInstance<TraceRecord.Note>().map { it.text }

    // ------------------------------------------------------------------ visibility is reported

    @Test
    fun `the app is in the foreground until the UI says otherwise`() = runTest {
        val f = fixture()
        assertTrue(f.lifecycle.appInForeground.value)
        f.background()
        assertFalse(f.lifecycle.appInForeground.value)
        assertFalse(f.controller.appInForeground.value)
        f.foreground()
        assertTrue(f.lifecycle.appInForeground.value)
    }

    // ------------------------------------------------------------------ the foreground service follows the connection

    @Test
    fun `the service is wanted only while connected`() = runTest {
        val f = fixture()
        assertFalse(f.wantsService)
        // Connecting does not count (the Connecting state shows during the scan and the handshake)
        val connecting = async { f.rig.connect() }
        delay(100)
        assertTrue(f.controller.connection.value is ConnectionState.Connecting)
        assertFalse(f.wantsService, "no notification while connecting")
        assertEquals(CommandResult.Success, connecting.await())
        assertTrue(f.wantsService)
        // Connection loss in the foreground: reconnecting, no notification
        f.sim.dropConnection()
        delay(100)
        assertTrue(f.controller.connection.value is ConnectionState.Reconnecting)
        assertFalse(f.wantsService, "no notification while reconnecting")
        assertNull(f.lifecycle.lossNotice.value, "the loss notice is only for the background")
        delay(10_000)
        assertTrue(f.rig.connected)
        assertTrue(f.wantsService)
        f.controller.disconnect()
        assertFalse(f.wantsService)
        // A scan alone never asks for the service
        val seen = CopyOnWriteArrayList<DiscoveredTreadmill>()
        val scanning = launch { f.controller.scan().collect { seen += it } }
        delay(1_000)
        assertFalse(f.wantsService)
        scanning.cancel()
        f.settle()
        assertEquals(listOf(false, true, false, true, false), f.serviceWanted.toList())
    }

    @Test
    fun `no service while Bluetooth is off`() = runTest {
        val f = fixture()
        f.rig.connect()
        assertTrue(f.wantsService)
        f.sim.setBluetoothEnabled(false)
        delay(200)
        assertFalse(f.wantsService)
        delay(60_000)
        assertFalse(f.wantsService)
        f.sim.setBluetoothEnabled(true)
        delay(8_000)
        assertTrue(f.rig.connected)
        assertTrue(f.wantsService)
    }

    // ------------------------------------------------------------------ no scans and no connections in the background

    @Test
    fun `a user scan stops when the app goes to the background and one requested there does not scan`() = runTest {
        val f = fixture()
        val seen = CopyOnWriteArrayList<DiscoveredTreadmill>()
        var completed = false
        val scanning = launch {
            f.controller.scan().collect { seen += it }
            completed = true
        }
        delay(2_500)
        assertTrue(seen.isNotEmpty(), "the simulator advertises the treadmill")
        assertFalse(completed)
        assertEquals(1, f.scans)
        val sightingsBefore = seen.size

        f.background()
        delay(100)
        assertTrue(completed, "the scan Flow ends by itself")
        scanning.join()
        val after = seen.size
        delay(5_000)
        assertEquals(after, seen.size)
        assertTrue(after >= sightingsBefore)

        // Requested while in the background: ends at once, without starting a scan
        val again = f.controller.scan().toList()
        assertTrue(again.isEmpty())
        assertEquals(1, f.scans, "no scan was started in the background")
        assertTrue(f.notes().any { it.startsWith("scan() refused") }, "${f.notes()}")

        f.foreground()
        val back = launch { f.controller.scan().collect { seen += it } }
        delay(1_000)
        assertEquals(2, f.scans, "scanning works again in the foreground")
        back.cancel()
    }

    @Test
    fun `connect is refused in the background without scanning`() = runTest {
        val f = fixture()
        f.background()
        val r = f.controller.connect(f.sim.address)
        assertTrue(r is CommandResult.NotAllowed && r.code == FailureCode.ConnectionFailed, "$r")
        assertEquals(0, f.scans)
        assertEquals(0, f.sim.connectCount)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        f.foreground()
        assertEquals(CommandResult.Success, f.controller.connect(f.sim.address))
    }

    @Test
    fun `a connection attempt in progress is abandoned when the app goes to the background`() = runTest {
        val f = fixture()
        f.sim.scanLatencyMs = 5_000 // the treadmill takes its time to show up
        val connecting = async { f.controller.connect(f.sim.address) }
        delay(1_000)
        assertTrue(f.controller.connection.value is ConnectionState.Connecting)
        f.background()
        assertEquals(CommandResult.NotConnected, connecting.await())
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        delay(30_000)
        assertEquals(0, f.sim.connectCount, "it never got to connect")
        assertEquals(1, f.scans)
        assertFalse(f.wantsService)
    }

    @Test
    fun `an established connection is left alone in the background`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        f.background()
        delay(60_000)
        assertTrue(f.rig.connected)
        assertEquals(BeltStatus.Running, f.controller.state.value.status)
        assertEquals(5.0, f.controller.state.value.speedKmh, 0.001)
        assertTrue(f.wantsService)
        // Commands still work from the notification
        assertEquals(CommandResult.Success, f.controller.stop())
        assertEquals(BeltStatus.Idle, f.controller.state.value.status)
    }

    @Test
    fun `a connection lost in the background is not retried, and resumes when the app is back`() = runTest {
        val f = fixture()
        f.sim.requireScanBeforeConnect = true
        f.rig.connect()
        val scans = f.scans
        f.background()
        f.sim.dropConnection()
        delay(500)
        val waiting = f.controller.connection.value
        assertTrue(waiting is ConnectionState.Reconnecting, "$waiting")
        assertTrue(f.notes().any { it.startsWith("Reconnection paused: the app is in the background") }, "${f.notes()}")

        delay(10 * 60_000)
        assertEquals(scans, f.scans, "no scans in the background")
        assertEquals(1, f.sim.connectCount, "no connection attempts in the background")
        assertEquals(waiting, f.controller.connection.value, "the attempt counter does not move")

        f.foreground()
        delay(500)
        assertTrue(f.notes().any { it.startsWith("App is back in the foreground: resuming") }, "${f.notes()}")
        delay(10_000)
        assertTrue(f.rig.connected, "${f.controller.connection.value}")
        assertEquals(f.sim.address, (f.controller.connection.value as ConnectionState.Connected).address)
        assertEquals(2, f.sim.connectCount)
        assertEquals(scans + 1, f.scans, "one scan for the resumed attempt")
        assertTrue(f.wantsService)
    }

    @Test
    fun `a reconnection that is running when the app goes to the background is paused, even in the middle of a scan`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.sim.advertising = false // it will not be found: the attempts keep failing
        f.sim.dropConnection()
        delay(2_500) // first attempt: backoff done, the scan window is open
        assertTrue(f.controller.connection.value is ConnectionState.Reconnecting)
        val scansWhenPaused = f.scans
        f.background()
        delay(20_000)
        val connects = f.sim.connectCount
        delay(5 * 60_000)
        assertEquals(scansWhenPaused, f.scans, "the scan in progress was cancelled and no other started")
        assertEquals(connects, f.sim.connectCount)
        assertTrue(f.controller.connection.value is ConnectionState.Reconnecting)

        f.sim.advertising = true
        f.foreground()
        delay(15_000)
        assertTrue(f.rig.connected, "${f.controller.connection.value}")
    }

    @Test
    fun `stopping the reconnection while it is paused in the background abandons it`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        f.sim.dropConnection()
        delay(1_000)
        f.controller.disconnect()
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        f.foreground()
        delay(60_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value, "coming back does not reconnect after a disconnect()")
        assertEquals(1, f.sim.connectCount)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a workout ended by a loss in the background is recorded as a lost connection and the alert stays pending`(rate: DataRate) = runTest {
        val f = fixture(rate)
        f.rig.runAt(5.0)
        f.background()
        f.sim.dropConnection("out of range")
        delay(200)
        val saved = f.rig.recorder.status.value as RecordingStatus.Saved
        val session = f.rig.storage.parsed(saved.info.fileName)
        assertEquals("connection_lost", notNull(session.end)["reason"])
        assertEquals(1, session.events("ConnectionLost").size)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ConnectionLost), f.controller.pendingAlerts.value)
        // The pause and the notice change nothing about it
        delay(60_000)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ConnectionLost), f.controller.pendingAlerts.value)
        assertEquals(saved, f.rig.recorder.status.value)
    }

    // ------------------------------------------------------------------ connection lost in the background: the notice

    @Test
    fun `a loss in the background while running says the treadmill stopped by itself and goes away after 10 s`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        f.background()
        f.sim.dropConnection()
        delay(100)
        assertEquals(ConnectionLossNotice.BeltStoppedByItself, f.lifecycle.lossNotice.value)
        assertTrue(f.wantsService, "the notification stays for the notice")
        delay(9_000)
        assertEquals(ConnectionLossNotice.BeltStoppedByItself, f.lifecycle.lossNotice.value)
        assertTrue(f.wantsService)
        delay(1_100)
        assertNull(f.lifecycle.lossNotice.value)
        assertFalse(f.wantsService, "the service and the notification are gone after 10 s")
        // And it does not come back by itself while the reconnection is paused
        delay(5 * 60_000)
        assertFalse(f.wantsService)
        f.settle()
        assertEquals(listOf(null, ConnectionLossNotice.BeltStoppedByItself, null), f.notices.toList())
    }

    @Test
    fun `a loss in the background with the belt idle gets the neutral notice`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        f.sim.dropConnection()
        delay(100)
        assertEquals(ConnectionLossNotice.Neutral, f.lifecycle.lossNotice.value)
        delay(10_100)
        assertNull(f.lifecycle.lossNotice.value)
        assertFalse(f.wantsService)
    }

    @Test
    fun `the notice time is configurable`() = runTest {
        val f = fixture(config = LifecycleConfig(backgroundLossNoticeMs = 2_000))
        f.rig.connect()
        f.background()
        f.sim.dropConnection()
        delay(1_000)
        assertTrue(f.wantsService)
        delay(1_500)
        assertFalse(f.wantsService)
    }

    @Test
    fun `a loss in the foreground has no notice, even if the app goes to the background right after`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        f.sim.dropConnection()
        delay(100)
        assertNull(f.lifecycle.lossNotice.value)
        assertFalse(f.wantsService)
        f.background()
        delay(30_000)
        assertNull(f.lifecycle.lossNotice.value)
        assertFalse(f.wantsService)
    }

    @Test
    fun `the notice goes away at once when the app comes back`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        f.background()
        f.sim.dropConnection()
        delay(3_000)
        assertTrue(f.wantsService)
        f.foreground()
        delay(10)
        assertNull(f.lifecycle.lossNotice.value)
        assertFalse(f.wantsService, "while reconnecting there is no notification")
        delay(10_000)
        assertTrue(f.rig.connected)
        assertTrue(f.wantsService)
    }

    @Test
    fun `a user disconnect in the background gives no loss notice`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        f.controller.disconnect()
        delay(100)
        assertNull(f.lifecycle.lossNotice.value)
        assertFalse(f.wantsService)
    }

    // ------------------------------------------------------------------ idle auto-disconnect

    @Test
    fun `connected, in the background and idle for 10 minutes disconnects cleanly`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        delay(9 * 60_000 + 55_000)
        assertTrue(f.rig.connected, "not before the 10 minutes")
        delay(10_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        assertFalse(f.sim.isConnected)
        assertFalse(f.wantsService, "the notification is gone")
        assertNull(f.lifecycle.lossNotice.value, "a clean disconnect is not a loss")
        assertTrue(f.events.none { it is TreadmillEvent.ConnectionLost })
        assertTrue(f.notes().any { it.startsWith("Idle in the background for 600 s") }, "${f.notes()}")
        delay(60 * 60_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value, "nothing reconnects it")
        assertEquals(1, f.sim.connectCount)
    }

    private val Fixture.events get() = rig.events

    @Test
    fun `coming to the foreground resets the idle timer`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        delay(9 * 60_000)
        f.foreground()
        delay(5 * 60_000)
        f.background()
        delay(9 * 60_000 + 30_000)
        assertTrue(f.rig.connected, "9.5 minutes since the second time in the background")
        delay(40_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
    }

    @Test
    fun `activity resets the idle timer, and the count starts again when the belt is idle`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        delay(8 * 60_000)
        assertEquals(CommandResult.Success, f.controller.start())
        delay(5_000)
        assertEquals(CommandResult.Success, f.controller.stop())
        assertEquals(BeltStatus.Idle, f.controller.state.value.status)
        delay(9 * 60_000 + 30_000)
        assertTrue(f.rig.connected, "the 8 minutes before the workout do not count")
        delay(60_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
    }

    @Test
    fun `it never disconnects while the belt is not idle`() = runTest {
        val f = fixture()
        f.rig.runAt(4.0)
        f.background()
        delay(60 * 60_000)
        assertTrue(f.rig.connected)
        assertEquals(BeltStatus.Running, f.controller.state.value.status)

        // Paused: the session is still open on the treadmill
        assertEquals(CommandResult.Success, f.controller.pause())
        delay(60 * 60_000)
        assertTrue(f.rig.connected)
        assertEquals(BeltStatus.Paused, f.controller.state.value.status)
    }

    @Test
    fun `with the safety key removed it does not disconnect`() = runTest {
        val f = fixture()
        f.rig.runAt(4.0)
        f.background()
        f.sim.removeSafetyKey()
        delay(30 * 60_000)
        assertTrue(f.rig.connected)
        assertEquals(BeltStatus.SafetyKeyRemoved, f.controller.state.value.status)
    }

    @Test
    fun `a belt that is still decelerating is not idle, the count starts when it reaches 0`() = runTest {
        val f = fixture(config = LifecycleConfig(backgroundIdleDisconnectMs = 10_000))
        f.rig.runAt(8.0)
        f.background()
        val stop = async { f.controller.stop() }
        delay(1_500)
        val state = f.controller.state.value
        assertTrue(state.speedKmh > 0.0, "still decelerating: ${state.speedKmh}")
        assertEquals(CommandResult.Success, stop.await())
        assertEquals(0.0, f.controller.state.value.speedKmh, 0.001)
        delay(9_000)
        assertTrue(f.rig.connected)
        delay(2_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
    }

    @Test
    fun `the idle time is configurable and the foreground never disconnects`() = runTest {
        val f = fixture(config = LifecycleConfig(backgroundIdleDisconnectMs = 5_000))
        f.rig.connect()
        delay(60 * 60_000)
        assertTrue(f.rig.connected, "in the foreground there is no idle disconnect")
        f.background()
        delay(4_000)
        assertTrue(f.rig.connected)
        delay(1_500)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
    }

    @Test
    fun `a session closed by the idle disconnect leaves the recording intact`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        assertEquals(CommandResult.Success, f.controller.stop())
        delay(5_000)
        val saved = f.rig.recorder.status.value as RecordingStatus.Saved
        f.background()
        delay(11 * 60_000)
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        val session = f.rig.storage.parsed(saved.info.fileName)
        assertEquals("idle", notNull(session.end)["reason"])
        assertEquals("end", session.records.last()["type"])
    }

    // ------------------------------------------------------------------ exit

    @Test
    fun `exit is refused while the belt is not idle and changes nothing`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        assertEquals(ExitResult.Refused(ExitRefusedReason.BeltNotIdle), f.lifecycle.exitApp())
        assertTrue(f.rig.connected)
        assertEquals(BeltStatus.Running, f.controller.state.value.status)
        assertTrue((f.exitCount() == 0))
        assertTrue(f.wantsService)

        // Starting, paused
        assertEquals(CommandResult.Success, f.controller.pause())
        assertEquals(ExitResult.Refused(ExitRefusedReason.BeltNotIdle), f.lifecycle.exitApp())
        assertTrue(f.rig.connected)
        assertTrue((f.exitCount() == 0))
    }

    @Test
    fun `exit is refused while the belt is starting or still decelerating, and with the key removed`() = runTest {
        val f = fixture()
        f.rig.connect()
        val start = async { f.controller.start() }
        delay(500)
        assertEquals(ExitResult.Refused(ExitRefusedReason.BeltNotIdle), f.lifecycle.exitApp(), "countdown")
        assertEquals(CommandResult.Success, start.await())
        f.controller.setSpeed(8.0)
        delay(20_000)
        val stop = async { f.controller.stop() }
        delay(1_200)
        assertTrue(f.controller.state.value.speedKmh > 0.0)
        assertEquals(ExitResult.Refused(ExitRefusedReason.BeltNotIdle), f.lifecycle.exitApp(), "decelerating")
        assertEquals(CommandResult.Success, stop.await())
        assertTrue(f.rig.connected)
        assertTrue((f.exitCount() == 0))

        f.sim.removeSafetyKey()
        delay(1_000)
        assertEquals(ExitResult.Refused(ExitRefusedReason.BeltNotIdle), f.lifecycle.exitApp(), "key removed")
        assertTrue(f.rig.connected)
    }

    @Test
    fun `exit with the belt idle disconnects, removes the service and announces the exit`() = runTest {
        val f = fixture()
        f.rig.connect()
        assertTrue(f.wantsService)
        assertEquals(ExitResult.Exiting, f.lifecycle.exitApp())
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        assertFalse(f.sim.isConnected)
        assertFalse(f.wantsService)
        assertEquals(1, f.exitCount())
        delay(60_000)
        assertEquals(1, f.sim.connectCount, "nothing reconnects")
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        assertTrue(f.notes().any { it.startsWith("Exit requested") })
    }

    @Test
    fun `exit after a workout that ended with a stop is allowed`() = runTest {
        val f = fixture()
        f.rig.runAt(5.0)
        assertEquals(CommandResult.Success, f.controller.stop())
        assertEquals(ExitResult.Exiting, f.lifecycle.exitApp())
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
    }

    @Test
    fun `exit while not connected abandons the reconnection or the attempt`() = runTest {
        val f = fixture()
        // Disconnected
        assertEquals(ExitResult.Exiting, f.lifecycle.exitApp())
        assertEquals(1, f.exitCount())

        // Reconnecting after a loss with the belt moving: it stopped by itself, exit is allowed
        f.rig.runAt(5.0)
        f.sim.advertising = false
        f.sim.dropConnection()
        delay(3_000)
        assertTrue(f.controller.connection.value is ConnectionState.Reconnecting)
        assertEquals(ExitResult.Exiting, f.lifecycle.exitApp())
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        val scans = f.scans
        delay(2 * 60_000)
        assertEquals(scans, f.scans, "the reconnection is gone")
        assertEquals(2, f.exitCount())
    }

    @Test
    fun `exit works in the background too (the notification action)`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.background()
        assertEquals(ExitResult.Exiting, f.lifecycle.exitApp())
        assertEquals(ConnectionState.Disconnected, f.controller.connection.value)
        assertEquals(1, f.exitCount())
        assertFalse(f.wantsService)
    }

    @Test
    fun `an exit is not replayed to a collector that arrives later`() = runTest {
        val f = fixture()
        f.rig.connect()
        f.lifecycle.exitApp()
        val late = CopyOnWriteArrayList<Unit>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.lifecycle.exitRequests.collect { late += it } }
        delay(1_000)
        assertTrue(late.isEmpty(), "a later launch of the app must not close itself")
    }
}
