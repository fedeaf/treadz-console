package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsTransport
import com.treadz.console.treadmill.ftms.TransportException
import com.treadz.console.treadmill.notNull
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Connecting and reconnecting reliably: scan before connecting (the treadmill uses a random static address that
 * Android only accepts after a scan has seen it), Bluetooth switched off and on, abandoning a reconnection, scan
 * throttling, the device name and the typed failure reasons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReliabilityTest {

    private fun Rig.scanStarts() = sim.scanStartTimes.size

    /** Most scan starts inside any 30 s window. */
    private fun maxScansIn30s(times: List<Long>): Int = times.maxOfOrNull { t -> times.count { it >= t && it < t + 30_000 } } ?: 0

    private fun Rig.bluetoothTrace() = trace.filterIsInstance<TraceRecord.Bluetooth>().map { it.enabled }

    private fun Rig.notes() = trace.filterIsInstance<TraceRecord.Note>().map { it.text }

    // ------------------------------------------------------------------ scan before connecting

    @Test
    fun `the fake refuses a direct connection until a scan has seen the device`() = runTest {
        val rig = Rig(this)
        rig.sim.requireScanBeforeConnect = true
        try {
            rig.sim.connect(rig.sim.address)
            fail("a direct connection to an unseen random static address must fail")
        } catch (e: TransportException) {
            assertEquals(ConnectionProblem.Other, e.problem)
        }
        assertEquals(rig.sim.address, rig.sim.locate(rig.sim.address, 8_000)?.address)
        rig.sim.connect(rig.sim.address)
        assertTrue(rig.sim.isConnected)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `connect scans first, so it works with an address that only connects after a scan`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.sim.requireScanBeforeConnect = true
        assertEquals(CommandResult.Success, rig.connect())
        assertEquals(1, rig.scanStarts(), "one short scan before the connection")
        assertTrue(rig.notes().any { it.startsWith("Scan saw the treadmill") }, "${rig.notes()}")
        assertNull(rig.controller.connectionProblem.value)
    }

    @Test
    fun `the first reconnection after a drop also scans first`() = runTest {
        val rig = Rig(this)
        rig.sim.requireScanBeforeConnect = true
        rig.connect()
        // The app was killed and came back: Android forgot the device
        rig.sim.setBluetoothEnabled(false)
        rig.sim.setBluetoothEnabled(true)
        rig.sim.dropConnection()
        delay(10_000)
        assertTrue(rig.connected, "${rig.controller.connection.value}")
        assertEquals(2, rig.sim.connectCount)
        assertEquals(2, rig.scanStarts())
    }

    @Test
    fun `a treadmill that the scan does not see is still tried directly, and reported as not found when that fails`() = runTest {
        val rig = Rig(this)
        rig.sim.advertising = false
        val before = rig.now
        val r = rig.controller.connect(rig.sim.address)
        val took = rig.now - before
        val rejected = r as CommandResult.Rejected
        assertEquals(FailureCode.ConnectionFailed, rejected.code)
        assertEquals(ConnectionProblem.DeviceNotFound, rejected.problem)
        assertEquals(ConnectionProblem.DeviceNotFound, rig.controller.connectionProblem.value)
        assertTrue(took in 8_000..9_000, "the scan lasts its window, $took ms")
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)

        // Back in range: it connects and the problem is cleared
        rig.sim.advertising = true
        assertEquals(CommandResult.Success, rig.controller.connect(rig.sim.address))
        assertNull(rig.controller.connectionProblem.value)
    }

    @Test
    fun `a scan that does not see the treadmill does not prevent a direct connection that works`() = runTest {
        val rig = Rig(this)
        rig.sim.scanLatencyMs = 20_000 // longer than the scan window: the scan ends without seeing it
        assertEquals(CommandResult.Success, rig.controller.connect(rig.sim.address))
        assertTrue(rig.notes().any { it.startsWith("Scan did not see the treadmill") }, "${rig.notes()}")
    }

    // ------------------------------------------------------------------ device name

    @Test
    fun `the name comes from the scan result and the Device Name is not read`() = runTest {
        val rig = Rig(this)
        rig.sim.scanName = "REEBOK 0517"
        rig.sim.systemKnowsName = false
        assertEquals(CommandResult.Success, rig.connect())
        assertEquals(ConnectionState.Connected(rig.sim.address, "REEBOK 0517"), rig.controller.connection.value)
        assertTrue(rig.trace.filterIsInstance<TraceRecord.RawRead>().none { it.characteristic == FtmsCharacteristic.DeviceName })
    }

    @Test
    fun `without a name from the system the Device Name characteristic is read`() = runTest {
        val rig = Rig(this)
        rig.sim.scanName = null // the advertisement carries no name
        assertEquals(CommandResult.Success, rig.connect())
        assertEquals(ConnectionState.Connected(rig.sim.address, "REEBOK SIM"), rig.controller.connection.value)
        val read = rig.trace.filterIsInstance<TraceRecord.RawRead>().single { it.characteristic == FtmsCharacteristic.DeviceName }
        assertEquals("REEBOK SIM", String(notNull(read.value)))
        val device = rig.trace.filterIsInstance<TraceRecord.Device>().single()
        assertEquals("REEBOK SIM", device.info.name)
        assertEquals("2a00", FtmsCharacteristic.DeviceName.shortName)
    }

    @Test
    fun `a failed or empty Device Name read is harmless`() = runTest {
        for (failing in listOf(true, false)) {
            val rig = Rig(this)
            rig.sim.scanName = null
            rig.sim.gapNameReadFails = failing
            rig.sim.gapNameReadable = false
            assertEquals(CommandResult.Success, rig.connect(), "failing=$failing")
            assertEquals(ConnectionState.Connected(rig.sim.address, null), rig.controller.connection.value)
            assertTrue(rig.controller.capabilities.value != null)
            // And everything else works
            assertEquals(CommandResult.Success, rig.controller.start())
        }
    }

    @Test
    fun `the name survives a reconnection`() = runTest {
        val rig = Rig(this)
        rig.sim.scanName = "REEBOK 0517"
        rig.connect()
        rig.sim.dropConnection()
        delay(10_000)
        assertEquals(ConnectionState.Connected(rig.sim.address, "REEBOK 0517"), rig.controller.connection.value)
    }

    // ------------------------------------------------------------------ Bluetooth off and on

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `Bluetooth off during a session is a connection loss, nothing is attempted while it is off, and it resumes when it is back`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.sim.requireScanBeforeConnect = true
        rig.runAt(5.0, incline = 3.0)
        val scansBefore = rig.scanStarts()

        rig.sim.setBluetoothEnabled(false)
        delay(200)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ConnectionLost), rig.events)
        assertFalse(rig.controller.bluetoothEnabled.value)
        val waiting = rig.controller.connection.value
        assertTrue(waiting is ConnectionState.Reconnecting, "$waiting")
        assertEquals(ConnectionProblem.BluetoothOff, rig.controller.connectionProblem.value)
        assertTrue(rig.controller.state.value.isStale)

        // Two minutes with Bluetooth off: no scans, no attempts, the attempt counter does not move
        delay(120_000)
        assertEquals(scansBefore, rig.scanStarts())
        assertEquals(1, rig.sim.connectCount)
        assertEquals(waiting, rig.controller.connection.value)
        assertEquals(1, rig.events.size)
        assertEquals(CommandResult.NotConnected, rig.controller.stop())

        rig.sim.setBluetoothEnabled(true)
        assertTrue(rig.controller.bluetoothEnabled.value)
        delay(5_000)
        assertTrue(rig.connected, "${rig.controller.connection.value}")
        assertEquals(2, rig.sim.connectCount)
        assertEquals(scansBefore + 1, rig.scanStarts(), "the device was forgotten with the adapter: a scan comes first")
        assertNull(rig.controller.connectionProblem.value)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertFalse(rig.controller.state.value.isStale)
        assertEquals(2, rig.commandsHex.count { it == "00" }, "Request Control again")
        assertEquals(1, rig.events.size, "a single ConnectionLost for the whole episode")
        assertEquals(listOf(true, false, true), rig.bluetoothTrace())
        assertEquals(CommandResult.Success, rig.controller.start())
    }

    @Test
    fun `the Bluetooth change is written in the session recording`() = runTest {
        val rig = Rig(this)
        rig.runAt(3.0)
        rig.sim.setBluetoothEnabled(false)
        delay(200)
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        assertEquals(listOf(false), session.ofType("bluetooth").map { it["enabled"] }.filter { it == false })
        assertEquals("connection_lost", notNull(session.end)["reason"])
        assertEquals(1, session.events("ConnectionLost").size)
    }

    @Test
    fun `Bluetooth off while connected and idle is not a session alert, and the connection comes back`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.setBluetoothEnabled(false)
        delay(30_000)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        assertTrue(rig.controller.connection.value is ConnectionState.Reconnecting)
        rig.sim.setBluetoothEnabled(true)
        delay(5_000)
        assertTrue(rig.connected)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun `Bluetooth off and on while disconnected does nothing`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.controller.disconnect()
        val scans = rig.scanStarts()
        rig.sim.setBluetoothEnabled(false)
        delay(10_000)
        rig.sim.setBluetoothEnabled(true)
        delay(60_000)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertEquals(1, rig.sim.connectCount)
        assertEquals(scans, rig.scanStarts())
        assertNull(rig.controller.connectionProblem.value)
    }

    @Test
    fun `a reconnection in progress pauses with Bluetooth off and resumes when it is back`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.advertising = false // the treadmill is not reachable, so the reconnection keeps trying
        rig.sim.dropConnection()
        delay(20_000)
        assertTrue(rig.scanStarts() >= 2)
        rig.sim.setBluetoothEnabled(false)
        delay(1_000)
        val attempt = (rig.controller.connection.value as ConnectionState.Reconnecting).attempt
        val scans = rig.scanStarts()
        delay(90_000)
        assertEquals(scans, rig.scanStarts(), "no scans while Bluetooth is off")
        assertEquals(attempt, (rig.controller.connection.value as ConnectionState.Reconnecting).attempt)
        assertEquals(ConnectionProblem.BluetoothOff, rig.controller.connectionProblem.value)

        rig.sim.setBluetoothEnabled(true)
        rig.sim.advertising = true
        delay(5_000)
        assertTrue(rig.connected, "${rig.controller.connection.value}")
    }

    @Test
    fun `connect with Bluetooth off fails at once with a typed reason and touches nothing`() = runTest {
        val rig = Rig(this)
        rig.sim.setBluetoothEnabled(false)
        val before = rig.now
        val r = rig.controller.connect(rig.sim.address)
        assertEquals(0L, rig.now - before)
        assertEquals(CommandResult.Rejected(FailureCode.ConnectionFailed, "Bluetooth is off", ConnectionProblem.BluetoothOff), r)
        assertEquals(ConnectionProblem.BluetoothOff, (r as CommandResult.Failure).problem)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertEquals(0, rig.scanStarts())
    }

    @Test
    fun `Bluetooth going off while connecting fails the attempt with the Bluetooth reason`() = runTest {
        val rig = Rig(this)
        rig.sim.advertising = false // the scan runs for its whole window
        val connecting = async { rig.controller.connect(rig.sim.address) }
        delay(2_000)
        assertTrue(rig.controller.connection.value is ConnectionState.Connecting)
        rig.sim.setBluetoothEnabled(false)
        val r = connecting.await() as CommandResult.Rejected
        assertEquals(ConnectionProblem.BluetoothOff, r.problem)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
    }

    @Test
    fun `scan fails with a typed reason when Bluetooth is off`() = runTest {
        val rig = Rig(this)
        rig.sim.setBluetoothEnabled(false)
        try {
            rig.controller.scan().first()
            fail("scan must fail")
        } catch (e: TransportException) {
            assertEquals(ConnectionProblem.BluetoothOff, e.problem)
        }
    }

    // ------------------------------------------------------------------ typed reasons

    /** A transport whose scan is not allowed: the Bluetooth permission is missing. */
    private class NoPermissionTransport(private val inner: SimulatedTreadmill) : FtmsTransport by inner {
        override suspend fun locate(address: String, timeoutMs: Long) =
            throw TransportException("Missing BLUETOOTH_SCAN permission", problem = ConnectionProblem.PermissionMissing)
    }

    @Test
    fun `a missing permission is reported as such by connect, and it is not tried blindly`() = runTest {
        val sim = SimulatedTreadmill(backgroundScope)
        val controller = FtmsTreadmillController(NoPermissionTransport(sim), backgroundScope, Clock { currentTime })
        val r = controller.connect(sim.address) as CommandResult.Rejected
        assertEquals(FailureCode.ConnectionFailed, r.code)
        assertEquals(ConnectionProblem.PermissionMissing, r.problem)
        assertEquals(ConnectionProblem.PermissionMissing, controller.connectionProblem.value)
        assertEquals(0, sim.connectCount)
        assertEquals(ConnectionState.Disconnected, controller.connection.value)
    }

    @Test
    fun `other failures are reported as Other`() = runTest {
        val rig = Rig(this)
        rig.sim.refuseConnections = true
        val r = rig.controller.connect(rig.sim.address) as CommandResult.Rejected
        assertEquals(ConnectionProblem.Other, r.problem) // the scan saw the treadmill, the connection failed anyway
    }

    @Test
    fun `failures that are not connection failures carry no problem`() {
        assertNull((CommandResult.NotAllowed(FailureCode.BeltNotRunning, "x") as CommandResult.Failure).problem)
        assertNull((CommandResult.Rejected(FailureCode.RejectedByTreadmill, "x") as CommandResult.Failure).problem)
        assertEquals(CommandResult.Rejected(FailureCode.NoResponse, "x"), CommandResult.Rejected(FailureCode.NoResponse, "x", null))
    }

    @Test
    fun `the typed reason is written in the command log`() = runTest {
        val rig = Rig(this)
        rig.sim.advertising = false
        rig.controller.connect(rig.sim.address)
        val failed = rig.trace.filterIsInstance<TraceRecord.Note>().map { it.text }
        assertTrue(failed.any { it.startsWith("Failed to connect") }, "$failed")
    }

    // ------------------------------------------------------------------ abandoning

    @Test
    fun `disconnect during a reconnection scan abandons it at once and nothing more is attempted`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.advertising = false
        rig.sim.dropConnection()
        delay(4_000) // the first attempt is inside its 8 s scan
        assertTrue(rig.controller.connection.value is ConnectionState.Reconnecting)
        val scans = rig.scanStarts()

        val before = rig.now
        rig.controller.disconnect()
        assertTrue(rig.now - before < 100, "disconnect must not wait for the scan, took ${rig.now - before} ms")
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertNull(rig.controller.connectionProblem.value)

        delay(180_000)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertEquals(scans, rig.scanStarts())
        assertEquals(1, rig.sim.connectCount)
        assertTrue(rig.events.isEmpty(), "idle: no session alert")
    }

    @Test
    fun `disconnect while waiting for the backoff also leaves Disconnected`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.refuseConnections = true
        rig.sim.dropConnection()
        delay(15_000)
        rig.controller.disconnect()
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        rig.sim.refuseConnections = false
        delay(120_000)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertEquals(1, rig.sim.connectCount)
    }

    @Test
    fun `disconnect while Bluetooth is off ends the pending reconnection`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.setBluetoothEnabled(false)
        delay(5_000)
        rig.controller.disconnect()
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        rig.sim.setBluetoothEnabled(true)
        delay(60_000)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertEquals(1, rig.sim.connectCount)
    }

    @Test
    fun `disconnect during a user-started connection abandons it`() = runTest {
        val rig = Rig(this)
        rig.sim.advertising = false
        val connecting = async { rig.controller.connect(rig.sim.address) }
        delay(2_000)
        assertTrue(rig.controller.connection.value is ConnectionState.Connecting)
        val before = rig.now
        rig.controller.disconnect()
        assertTrue(rig.now - before < 100)
        assertEquals(CommandResult.NotConnected, connecting.await())
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        delay(60_000)
        assertEquals(1, rig.scanStarts())
    }

    @Test
    fun `an explicit connect takes over a pending reconnection without waiting for its scan`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.advertising = false
        rig.sim.dropConnection()
        delay(3_000) // the automatic attempt is scanning
        rig.sim.advertising = true
        val before = rig.now
        assertEquals(CommandResult.Success, rig.controller.connect(rig.sim.address))
        assertTrue(rig.now - before < 2_000, "took ${rig.now - before} ms")
        assertTrue(rig.connected)
        delay(60_000)
        assertEquals(2, rig.sim.connectCount, "no second connection from the abandoned loop")
    }

    @Test
    fun `a user scan is not disturbed while a reconnection is pending`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.sim.advertising = false
        rig.sim.dropConnection()
        delay(3_000)
        rig.sim.advertising = true
        val found = rig.controller.scan().first()
        assertEquals(rig.sim.address, found.address)
        delay(10_000)
        assertTrue(rig.connected, "the pending reconnection is unaffected: ${rig.controller.connection.value}")
    }

    // ------------------------------------------------------------------ scan throttling and backoff

    @Test
    fun `reconnecting to an absent treadmill stays well inside the platform scan limit`() = runTest {
        val rig = Rig(this)
        rig.sim.emulateScanThrottling = true
        rig.connect()
        rig.sim.advertising = false
        rig.sim.dropConnection()
        delay(10 * 60_000L)
        val starts = rig.sim.scanStartTimes
        assertTrue(starts.size >= 5, "it keeps trying: $starts")
        assertEquals(0, rig.sim.throttledScans)
        assertTrue(maxScansIn30s(starts) <= 4, "at most 4 scan starts in any 30 s window: $starts")
        // The wait between attempts grows
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue(gaps.last() >= 30_000, "the backoff ends at ~30 s plus the scan: $gaps")

        // And when the treadmill appears it still gets found (the scans were not throttled)
        rig.sim.advertising = true
        delay(60_000)
        assertTrue(rig.connected, "${rig.controller.connection.value}")
    }

    @Test
    fun `the fake does throttle scans started too often, which is what the real schedule avoids`() = runTest {
        val rig = Rig(this, config = ControllerConfig(reconnectDelaysMs = listOf(100), locateTimeoutMs = 500))
        rig.sim.emulateScanThrottling = true
        rig.connect()
        rig.sim.advertising = false
        rig.sim.dropConnection()
        delay(60_000)
        assertTrue(rig.sim.throttledScans > 0, "scan starts: ${rig.sim.scanStartTimes}")
        assertTrue(maxScansIn30s(rig.sim.scanStartTimes) > 5)
    }

    // ------------------------------------------------------------------ defaults of the contract

    @Test
    fun `the default backoff grows to about 30 s and each attempt scans at most once`() {
        val delays = ControllerConfig().reconnectDelaysMs
        assertEquals(delays.sorted(), delays)
        assertEquals(30_000L, delays.last())
        assertEquals(1_000L, delays.first())
        assertTrue(ControllerConfig().locateTimeoutMs in 5_000..10_000)
    }
}
