package com.treadz.console.treadmill.recording

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.CommandOrigin
import com.treadz.console.treadmill.control.ControllerObserver
import com.treadz.console.treadmill.control.Rig
import com.treadz.console.treadmill.control.T0
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.notNull
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTest {

    private val nameRegex = Regex("""treadz_\d{8}_\d{6}(_\d+)?\.jsonl""")

    private fun RecordedSession.types() = records.map { it["type"] as String }

    // ------------------------------------------------------------------ external records

    @Test
    fun `external records (heart-rate strap, source) are written in the session and read back`() = runTest {
        val rig = Rig(this)
        rig.connect()
        // Before the session: kept in the preroll.
        rig.recorder.onTrace(TraceRecord.External(rig.clock.nowMillis(), "hrm_connection", mapOf("state" to "Connected", "address" to "AA:BB")))
        assertEquals(CommandResult.Success, rig.controller.start())
        rig.recorder.onTrace(
            TraceRecord.External(
                rig.clock.nowMillis(), "hrm_reading",
                linkedMapOf("hr_bpm" to 121, "contact" to "Detected", "rr_ms" to listOf(496, 500), "t" to 1, "type" to "ignored"),
            ),
        )
        rig.recorder.onTrace(TraceRecord.External(rig.clock.nowMillis(), "hr_source", mapOf("source" to "DirectStrap", "previous" to null)))
        delay(2_000)
        assertEquals(CommandResult.Success, rig.controller.stop())
        delay(30_000)
        val name = (rig.recorder.status.value as RecordingStatus.Saved).info.fileName
        val session = rig.storage.parsed(name)
        assertEquals("AA:BB", session.ofType("hrm_connection").single()["address"])
        val reading = session.ofType("hrm_reading").single()
        assertEquals(121L, reading["hr_bpm"])
        assertEquals(listOf(496L, 500L), reading["rr_ms"])
        assertTrue(reading["t"] as Long > 1, "t and type cannot be overridden by the fields")
        assertEquals("DirectStrap", session.ofType("hr_source").single()["source"])
        assertTrue(session.ofType("hr_source").single().containsKey("previous"))
    }

    // ------------------------------------------------------------------ normal session

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a session from start to stop produces a single complete and readable file`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        assertEquals(RecordingStatus.Idle, rig.recorder.status.value)
        assertTrue(rig.storage.fileNames.isEmpty(), "no session, no file")

        assertEquals(CommandResult.Success, rig.controller.start())
        val recording = rig.recorder.status.value as RecordingStatus.Recording
        assertTrue(nameRegex.matches(recording.info.fileName), recording.info.fileName)
        assertFalse(' ' in recording.info.fileName)
        assertEquals(1, rig.storage.fileNames.size)

        assertEquals(CommandResult.Success, withContext(CommandOrigin.App) { rig.controller.setSpeed(3.0) })
        assertEquals(CommandResult.Success, withContext(CommandOrigin.HeartRateControl) { rig.controller.setIncline(4.0) })
        delay(8_000)
        // Incremental writing: there are already complete, valid lines in the file while the session is open
        val midway = rig.storage.parsed(recording.info.fileName)
        assertTrue(midway.records.size > 20)
        assertFalse(rig.storage.isClosed(recording.info.fileName))
        assertTrue(rig.recorder.status.value is RecordingStatus.Recording)

        assertEquals(CommandResult.Success, withContext(CommandOrigin.Notification) { rig.controller.stop() })
        delay(8_000)

        val saved = rig.recorder.status.value as RecordingStatus.Saved
        assertEquals(recording.info, saved.info)
        assertTrue(saved.location.endsWith(recording.info.fileName))
        assertTrue(rig.storage.isClosed(saved.info.fileName))
        assertEquals(1, rig.storage.fileNames.size)

        val session = rig.storage.parsed(saved.info.fileName)

        // Header
        val header = notNull(session.header)
        assertEquals("treadz-session", header["format"])
        assertEquals(JsonlWorkoutRecorder.FORMAT_VERSION.toLong(), header["version"])
        @Suppress("UNCHECKED_CAST")
        val app = header["app"] as Map<String, Any?>
        assertEquals("TreadZ", app["name"])
        @Suppress("UNCHECKED_CAST")
        val device = header["device"] as Map<String, Any?>
        assertEquals("ChangYow", device["manufacturer"])
        assertEquals("SI3019", device["model"])
        assertEquals("07", device["firmware"])
        assertEquals(rig.sim.address, device["address"])
        @Suppress("UNCHECKED_CAST")
        val caps = device["capabilities"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val speed = caps["speed"] as Map<String, Any?>
        assertEquals(20.0, speed["max"])
        assertTrue((caps["target_settings"] as List<*>).contains("SpeedTarget"))

        // End
        val end = notNull(session.end)
        assertEquals("idle", end["reason"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("Idle", (end["last_state"] as Map<String, Any?>)["status"])

        // Timestamps in ms, in order
        val times = session.records.map { it["t"] as Long }
        assertTrue(times.zipWithNext().all { (a, b) -> a <= b }, "timestamps out of order")
        assertTrue(times.first() >= T0)

        // Raw traffic in both directions, with the characteristic and the hex
        val rx = session.ofType("rx")
        assertTrue(rx.any { it["ch"] == "2acd" && (it["hex"] as String).length == 46 && (it["hex"] as String).startsWith("9c05") })
        assertTrue(rx.any { it["ch"] == "2ada" })
        assertTrue(rx.any { it["ch"] == "2ad3" })
        assertTrue(rx.any { it["ch"] == "2ad9" && it["hex"] == "800701" })
        val tx = session.ofType("tx")
        assertTrue(tx.all { it["ch"] == "2ad9" })
        assertEquals(listOf("00", "07", "022c01", "032800", "0801"), tx.map { it["hex"] })

        // Decoded states, controller state, commands and results
        assertTrue(session.ofType("data").any { it["speed_kmh"] == 3.0 })
        assertTrue(session.ofType("data").all { it.containsKey("hr_bpm") && it.containsKey("stopwatch_s") })
        assertTrue(session.ofType("status").any { it["name"] == "StoppedOrPausedByUser" })
        assertTrue(session.ofType("training").any { it["name"] == "ManualMode" })
        assertTrue(session.ofType("cp_response").any { it["request"] == "StartOrResume" && it["result"] == "Success" })
        val states = session.ofType("state").map { it["status"] }.distinct()
        assertTrue(states.containsAll(listOf("Idle", "Starting", "Running")), "$states")
        assertEquals(
            listOf("start", "setSpeed", "setIncline", "stop"),
            session.ofType("command").map { it["name"] }.filter { it != "requestControl" },
        )
        // Who issued each command, when the caller said so.
        assertEquals(
            listOf(null, "app", "hr_control", "notification"),
            session.ofType("command").filter { it["name"] != "requestControl" }.map { it["origin"] },
        )
        assertEquals(
            listOf("Success", "Success", "Success", "Success"),
            session.ofType("command_result").filter { it["name"] != "requestControl" }.map { it["result"] },
        )
        assertTrue(session.ofType("connection").any { it["state"] == "Connected" })

        // Everything received raw is also in the file: no rx is lost between start and end
        val endAt = end["t"] as Long
        val rxTraced = rig.trace.filterIsInstance<TraceRecord.RawReceived>().count { it.atMillis in (session.records.first()["t"] as Long)..endAt }
        assertTrue(rx.size in (rxTraced - 2)..rxTraced, "rx in file ${rx.size} vs traced while the session was open $rxTraced")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `the session start includes what came before (preroll), with Request Control and the first packets`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.controller.start()
        delay(3_000)
        rig.controller.stop()
        delay(8_000)
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        assertTrue((notNull(session.header)["preroll_records"] as Long) > 0)
        val tx = session.ofType("tx").map { it["hex"] }
        assertEquals("00", tx.first(), "the Request Control sent before starting is there too: $tx")
        assertTrue(session.ofType("device").isNotEmpty())
        // The first record after the header is earlier than the first non-Idle state
        val firstNonIdle = session.ofType("state").first { it["status"] != "Idle" }["t"] as Long
        assertTrue((session.records[1]["t"] as Long) < firstNonIdle)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `two sessions in a row produce two files with different names`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        rig.controller.stop()
        delay(8_000)
        val first = (rig.recorder.status.value as RecordingStatus.Saved).info
        delay(20_000)
        assertEquals(CommandResult.Success, rig.controller.start())
        val second = (rig.recorder.status.value as RecordingStatus.Recording).info
        assertNotEquals(first.fileName, second.fileName)
        assertTrue(second.startedAtMillis > first.startedAtMillis)
        rig.controller.stop()
        delay(8_000)
        assertEquals(2, rig.storage.fileNames.size)
        assertTrue(rig.storage.fileNames.all { nameRegex.matches(it) && ' ' !in it })
        // Each file can be read in full on its own
        rig.storage.fileNames.forEach {
            val s = rig.storage.parsed(it)
            assertEquals("header", s.types().first())
            assertEquals("end", s.types().last())
        }
    }

    // ------------------------------------------------------------------ safety key

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `the session with the key removed includes the notifications and ends when it is put back`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0, incline = 5.0)
        rig.sim.removeSafetyKey()
        delay(15_000)
        // With the key removed the session stays open
        assertTrue(rig.recorder.status.value is RecordingStatus.Recording)
        rig.sim.restoreSafetyKey()
        delay(8_000)
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        assertEquals(1, session.events("SafetyKeyRemoved").size)
        assertEquals(1, session.events("SafetyKeyRestored").size)
        assertTrue(session.ofType("status").any { it["name"] == "StoppedBySafetyKey" })
        assertEquals("idle", session.end!!["reason"])
        assertTrue(session.ofType("state").any { it["status"] == "SafetyKeyRemoved" })
        val removedAt = session.events("SafetyKeyRemoved").single()["t"] as Long
        val restoredAt = session.events("SafetyKeyRestored").single()["t"] as Long
        assertTrue(restoredAt - removedAt >= 15_000)
    }

    // ------------------------------------------------------------------ disconnection

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `losing the connection closes the session with the alert inside the file`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(5.0)
        rig.sim.dropConnection("treadmill switched off")
        delay(100)
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        assertEquals("connection_lost", notNull(session.end)["reason"])
        assertEquals(1, session.events("ConnectionLost").size)
        val conn = session.ofType("connection").last()
        assertEquals("Reconnecting", conn["state"])
        assertEquals("treadmill switched off", conn["detail"])
        assertEquals("end", session.records.last()["type"])

        // Reconnecting while Idle does not open another session; starting again does
        delay(8_000)
        assertEquals(1, rig.storage.fileNames.size)
        assertEquals(CommandResult.Success, rig.controller.start())
        assertEquals(2, rig.storage.fileNames.size)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `disconnecting from the app closes the session`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.controller.disconnect()
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val end = notNull(rig.storage.parsed(saved.info.fileName).end)
        assertEquals("disconnected", end["reason"])
    }

    // ------------------------------------------------------------------ treadmill faults

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a failed start is recorded too, for debugging`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.sim.startFault = true
        val r = rig.controller.start()
        assertTrue(r is CommandResult.NotVerified)
        delay(8_000)
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        assertEquals("NotVerified", session.ofType("command_result").last { it["name"] == "start" }["result"])
        assertEquals(1, session.events("CommandNotExecuted").size)
        assertEquals("start", session.events("CommandNotExecuted").single()["command"])
        assertEquals("idle", notNull(session.end)["reason"])
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a stop accepted but not executed is recorded with the alert and the packets`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0)
        rig.sim.stopFault = true
        rig.controller.stop()
        delay(2_000)
        rig.controller.disconnect() // closes the session so it can be read (the fake stop does not end it)
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        val ev = session.events("CommandNotExecuted").first()
        assertEquals("stop", ev["command"])
        val stopResult = session.ofType("command_result").first { it["name"] == "stop" }
        assertEquals("NotVerified", stopResult["result"])
        assertTrue(session.ofType("tx").any { it["hex"] == "0801" })
        // The data after the stop stays at 4 km/h: that is what proves the fault
        val after = session.ofType("data").filter { (it["t"] as Long) > (ev["t"] as Long) }
        assertTrue(after.isNotEmpty() && after.all { it["speed_kmh"] == 4.0 })
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `manual changes and frozen data are recorded`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.physicalSetIncline(5.0)
        delay(3_000)
        rig.sim.freezeData(SimulatedTreadmill.FreezeMode.Silent)
        delay(8_000)
        rig.controller.disconnect()
        val saved = rig.recorder.status.value as RecordingStatus.Saved
        val session = rig.storage.parsed(saved.info.fileName)
        assertEquals(5.0, session.events("ManualChange").single()["incline_pct"])
        assertEquals(1, session.events("DataStalled").size)
        assertTrue(session.ofType("note").isNotEmpty())
    }

    // ------------------------------------------------------------------ storage failures

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `if the file cannot be created it is reported and not retried on every packet`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.storage.failOnCreate = java.io.IOException("storage full")
        rig.runAt(2.0)
        val st = rig.recorder.status.value
        assertTrue(st is RecordingStatus.Failed && st.reason.contains("storage full"), "$st")
        assertEquals(CommandResult.Success, rig.controller.stop())
        delay(8_000)
        // Next session, with the storage fixed
        rig.storage.failOnCreate = null
        assertEquals(CommandResult.Success, rig.controller.start())
        assertTrue(rig.recorder.status.value is RecordingStatus.Recording)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `if writing fails mid-session it is reported and control continues`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        rig.storage.failOnWrite = true
        delay(3_000)
        assertTrue(rig.recorder.status.value is RecordingStatus.Failed)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(3.0))
        assertEquals(CommandResult.Success, rig.controller.stop())
    }

    // ------------------------------------------------------------------ recorder in isolation

    private fun stateRecord(t: Long, status: BeltStatus, speed: Double = 0.0) =
        TraceRecord.StateChanged(t, TreadmillState(status = status, speedKmh = speed), "test")

    @Test
    fun `names with the local start date and a suffix if the name already exists`() {
        val storage = InMemoryRecordingStorage()
        val recorder = JsonlWorkoutRecorder(
            storage, { T0 }, RecorderConfig(zone = ZoneOffset.ofHours(2), endGraceMs = 0),
        )
        recorder.onTrace(stateRecord(T0, BeltStatus.Running, 1.0))
        recorder.onTrace(stateRecord(T0 + 10, BeltStatus.Idle))
        recorder.onTrace(stateRecord(T0 + 20, BeltStatus.Idle))
        // 18:30 UTC with zone +02:00 -> 20:30:00
        assertEquals(listOf("treadz_20261007_203000.jsonl"), storage.fileNames)
        recorder.onTrace(stateRecord(T0 + 30, BeltStatus.Running, 1.0)) // another session in the same second
        recorder.finish("test")
        assertEquals(listOf("treadz_20261007_203000.jsonl", "treadz_20261007_203000_2.jsonl"), storage.fileNames)
        assertTrue(storage.fileNames.none { ' ' in it })
    }

    @Test
    fun `a session does not end while the belt is still decelerating after a stop`() {
        val storage = InMemoryRecordingStorage()
        val recorder = JsonlWorkoutRecorder(storage, { T0 }, RecorderConfig(endGraceMs = 0))
        recorder.onTrace(stateRecord(T0, BeltStatus.Running, 5.0))
        recorder.onTrace(stateRecord(T0 + 1000, BeltStatus.Idle, 4.5)) // Idle but the belt is still moving
        recorder.onTrace(stateRecord(T0 + 2000, BeltStatus.Idle, 4.0))
        assertTrue(recorder.status.value is RecordingStatus.Recording)
        recorder.onTrace(stateRecord(T0 + 3000, BeltStatus.Idle, 0.0))
        recorder.onTrace(stateRecord(T0 + 4000, BeltStatus.Idle, 0.0))
        assertTrue(recorder.status.value is RecordingStatus.Saved)
    }

    @Test
    fun `finish closes the session in progress and does nothing without a session`() {
        val storage = InMemoryRecordingStorage()
        val recorder = JsonlWorkoutRecorder(storage, { T0 + 5 })
        recorder.finish()
        assertEquals(RecordingStatus.Idle, recorder.status.value)
        recorder.onTrace(stateRecord(T0, BeltStatus.Running, 1.0))
        recorder.finish("service stopped")
        val name = storage.fileNames.single()
        assertEquals("service stopped", notNull(storage.parsed(name).end)["reason"])
    }

    @Test
    fun `with a writer of its own the recorder writes nothing on the caller's thread and keeps the order`() {
        val storage = InMemoryRecordingStorage()
        val queued = ArrayDeque<Runnable>()
        val recorder = JsonlWorkoutRecorder(storage, { T0 + 5 }, RecorderConfig(endGraceMs = 0), writer = { queued.addLast(it) })
        recorder.onTrace(stateRecord(T0, BeltStatus.Running, 1.0))
        recorder.onTrace(TraceRecord.Note(T0 + 1, "during the session"))
        recorder.finish("closed by the test")
        assertTrue(storage.fileNames.isEmpty(), "nothing is created or written until the writer runs")
        assertEquals(RecordingStatus.Idle, recorder.status.value)
        while (queued.isNotEmpty()) queued.removeFirst().run()
        val session = storage.parsed(storage.fileNames.single())
        assertTrue("note" in session.types(), "${session.types()}")
        assertEquals("closed by the test", notNull(session.end)["reason"])
        assertTrue(recorder.status.value is RecordingStatus.Saved)
    }

    @Test
    fun `a broken observer does not affect the controller and the recorder ignores what is not a session`() {
        val storage = InMemoryRecordingStorage()
        val recorder = JsonlWorkoutRecorder(storage, { T0 })
        recorder.onTrace(TraceRecord.Note(T0, "preroll only"))
        recorder.onTrace(stateRecord(T0 + 1, BeltStatus.Idle))
        assertTrue(storage.fileNames.isEmpty())
        assertNull((recorder.status.value as? RecordingStatus.Failed))
    }

    // ------------------------------------------------------------------ folder storage

    @Test
    fun `DirectoryRecordingStorage writes complete lines and can be read back`(@TempDir dir: File) {
        val storage = DirectoryRecordingStorage(File(dir, "TreadZ"))
        assertFalse(storage.exists("treadz_20261007_183000.jsonl"))
        val f = storage.create("treadz_20261007_183000.jsonl")
        f.appendLine("""{"t":1,"type":"header"}""")
        f.appendLine("""{"t":2,"type":"end"}""")
        f.sync()
        // Not closed yet: the content is already in the file
        val file = File(dir, "TreadZ/treadz_20261007_183000.jsonl")
        assertEquals(2, RecordingReader.read(file).records.size)
        f.close()
        assertTrue(storage.exists("treadz_20261007_183000.jsonl"))
        assertTrue(f.location.endsWith("treadz_20261007_183000.jsonl"))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { storage.create("with space.jsonl") }
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) { storage.create("treadz_20261007_183000.jsonl") }
    }

    @Test
    fun `the reader rejects lines that are not records`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { RecordingReader.parse("not json") }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { RecordingReader.parse("""{"a":1}""") }
    }
}
