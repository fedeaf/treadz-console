package com.treadz.console.hrm

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class StrapMonitorTest {

    private class Rig(scope: TestScope) {
        val bg = scope.backgroundScope
        var hr = 120
        val strap = SimulatedStrap(bg, bpmSource = { hr }, timeSource = { scope.currentTime })
        val monitor = StrapHeartRateMonitor(strap, bg, now = { scope.currentTime })
        val trace = CopyOnWriteArrayList<HrmTraceRecord>()
        val states = CopyOnWriteArrayList<StrapConnectionState>()

        init {
            monitor.addObserver { trace += it }
            bg.launch(start = CoroutineStart.UNDISPATCHED) { monitor.connection.collect { states += it } }
        }

        val connected get() = monitor.connection.value is StrapConnectionState.Connected
    }

    @Test
    fun `connects to the strap and delivers readings with RR intervals`() = runTest {
        val rig = Rig(this)
        assertEquals(StrapConnectionState.Inactive, rig.monitor.connection.value)
        rig.monitor.use(rig.strap.address.lowercase())
        delay(3_000)
        assertTrue(rig.connected, "${rig.monitor.connection.value}")
        assertEquals(StrapConnectionState.Connected(rig.strap.address, rig.strap.name), rig.monitor.connection.value)
        val r = checkNotNull(rig.monitor.latest.value)
        assertEquals(120, r.bpm)
        assertEquals(SensorContact.Detected, r.sensorContact)
        assertEquals(listOf(500, 500), r.rrIntervalsMs)
        assertTrue(r.isUsable)
        assertEquals(87, rig.monitor.batteryPercent.value)
        assertEquals(BodySensorLocation.Chest, rig.monitor.sensorLocation.value)
        assertNull(rig.monitor.problem.value)

        rig.hr = 130
        val next = rig.monitor.readings.first()
        assertEquals(130, next.bpm)
        assertTrue(rig.trace.any { it is HrmTraceRecord.RawReceived })
        assertTrue(rig.trace.any { it is HrmTraceRecord.Reading && it.reading.bpm == 130 })
        rig.monitor.release()
        assertEquals(StrapConnectionState.Inactive, rig.monitor.connection.value)
        assertFalse(rig.strap.isConnected)
        assertNull(rig.monitor.latest.value)
    }

    @Test
    fun `a strap held by the console is searched for silently and taken when it is released`() = runTest {
        val rig = Rig(this)
        rig.strap.heldByConsole = true
        rig.monitor.use(rig.strap.address)
        delay(120_000)
        assertEquals(StrapConnectionState.Searching(rig.strap.address), rig.monitor.connection.value)
        assertNull(rig.monitor.problem.value, "a strap held by the console is not a problem")
        assertEquals(0, rig.strap.connectAttempts, "no connection is attempted to a strap that does not advertise")
        assertTrue(rig.states.none { it is StrapConnectionState.Connecting })

        rig.strap.heldByConsole = false
        delay(31_000)
        assertTrue(rig.connected)
    }

    @Test
    fun `search scans keep within one start per 30 seconds`() = runTest {
        val rig = Rig(this)
        rig.strap.heldByConsole = true
        rig.monitor.use(rig.strap.address)
        delay(300_000)
        val starts = rig.strap.locateStartTimes
        for (i in 1 until starts.size) assertTrue(starts[i] - starts[i - 1] >= 30_000, "$starts")
        assertTrue(starts.size >= 9, "$starts")
    }

    @Test
    fun `strap taken off - contact lost, then it switches off and is searched for again`() = runTest {
        val rig = Rig(this)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        rig.strap.takeOff()
        delay(2_000)
        val r = rig.monitor.latest.value!!
        assertEquals(SensorContact.NotDetected, r.sensorContact)
        assertEquals(0, r.bpm)
        assertFalse(r.isUsable)

        delay(65_000)
        assertEquals(StrapConnectionState.Searching(rig.strap.address), rig.monitor.connection.value)
        assertNull(rig.monitor.latest.value)
        assertNull(rig.monitor.problem.value)

        rig.strap.putOn()
        delay(31_000)
        assertTrue(rig.connected)
        assertTrue(rig.monitor.latest.value!!.isUsable)
    }

    @Test
    fun `reconnects by itself after a connection loss`() = runTest {
        val rig = Rig(this)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        rig.strap.dropConnection()
        delay(100)
        assertFalse(rig.connected)
        delay(3_000)
        assertTrue(rig.connected)
        assertTrue(rig.trace.any { it is HrmTraceRecord.Connection && it.detail?.contains("connection lost") == true })
    }

    @Test
    fun `stale readings are dropped`() = runTest {
        val rig = Rig(this)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        assertNotNull(rig.monitor.latest.value)
        rig.strap.notifyIntervalMs = 60_000 // the strap stops notifying (for the next minute)
        delay(8_000)
        assertNull(rig.monitor.latest.value)
        assertTrue(rig.connected)
    }

    @Test
    fun `bluetooth off is the only reported problem`() = runTest {
        val rig = Rig(this)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        rig.strap.setBluetoothEnabled(false)
        delay(2_000)
        assertEquals(StrapProblem.BluetoothOff, rig.monitor.problem.value)
        assertFalse(rig.connected)
        rig.strap.setBluetoothEnabled(true)
        delay(3_000)
        assertNull(rig.monitor.problem.value)
        assertTrue(rig.connected)
    }

    @Test
    fun `changing or clearing the address drops the strap`() = runTest {
        val rig = Rig(this)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        assertTrue(rig.connected)
        rig.monitor.use("AA:BB:CC:DD:EE:FF")
        delay(1_000)
        assertFalse(rig.strap.isConnected)
        assertEquals(StrapConnectionState.Searching("AA:BB:CC:DD:EE:FF"), rig.monitor.connection.value)
        rig.monitor.use(null)
        delay(100)
        assertEquals(StrapConnectionState.Inactive, rig.monitor.connection.value)
        assertNull(rig.monitor.address.value)
    }

    @Test
    fun `a strap without contact support repeats its last value when taken off`() = runTest {
        val rig = Rig(this)
        rig.strap.contactSupported = false
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        assertEquals(SensorContact.NotSupported, rig.monitor.latest.value!!.sensorContact)
        rig.strap.takeOff()
        rig.hr = 150
        delay(2_000)
        assertEquals(120, rig.monitor.latest.value!!.bpm)
    }

    @Test
    fun `pairing scan sees an advertising strap only`() = runTest {
        val rig = Rig(this)
        val seen = rig.monitor.scan().take(1).toList()
        assertEquals(rig.strap.address, seen.single().address)
        rig.strap.setBluetoothEnabled(false)
        assertThrows<HrmTransportException> { rig.monitor.scan().first() }
    }
}
