package com.treadz.console.programs.personalised

import com.treadz.console.programs.engine.BlockChangeReason
import com.treadz.console.programs.engine.EndAction
import com.treadz.console.programs.engine.HeartRateAdjustReason
import com.treadz.console.programs.engine.HeartRateRig
import com.treadz.console.programs.engine.ManualChangeSource
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.engine.RunOptions
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.json.ProgramFormatException
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.ProgramResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class GeneratedSessionRecordTest {

    private val origin = ProgramOrigin(CouchToFiveK.ID, 1, mapOf("session" to "1", "mode" to "run"))

    private val program = Program(
        PROGRAM_ID, "Test", ProgramKind.Personalised,
        listOf(
            Block(BlockPhase.WarmUp, BlockEnd.Duration(60), BlockTarget.Absolute(5.0, 1.0)),
            Repeat(
                2,
                listOf(
                    Block(
                        BlockPhase.Work, BlockEnd.Duration(60),
                        BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 85), HeartRateAdjust.Speed, 5.0, 7.5, BlockTarget.Absolute(7.5, 1.0)),
                    ),
                    Block(BlockPhase.Recovery, BlockEnd.Duration(60), BlockTarget.Absolute(5.0, 1.0)),
                ),
            ),
            Block(BlockPhase.CoolDown, BlockEnd.Duration(60), BlockTarget.Absolute(5.0, 1.0)),
        ),
        origin = origin,
    )

    private val plan = ProgramResolver.resolve(program)
    private val t0 = NOW

    private fun at(s: Int) = t0 + s * 1000L

    /**
     * Warm-up with a manual increase; run 1 with a manual reduction; walk; run 2 starting corrected (announced before its
     * BlockStarted) and skipped; walk 2 stopped after 10 s.
     */
    private fun events(): Pair<ProgramEvent.Finished, List<ProgramEvent>> {
        val b = plan.blocks
        val finished = ProgramEvent.Finished(
            at(250), 250, RunOutcome.StoppedByUser, EndAction.StopBelt, null,
            listOf(hrSummary(1, end = 150.0), hrSummary(3, end = 160.0)),
        )
        val list = listOf(
            ProgramEvent.Started(at(0), 0, plan, RunOptions(heartRateProfile = HeartRateProfile(180))),
            ProgramEvent.BlockStarted(at(0), 0, 0, b[0], BlockChangeReason.ProgramStart),
            ProgramEvent.ManualChange(at(10), 10, 0, 5.5, null, ManualChangeSource.Console),
            ProgramEvent.BlockCompleted(at(60), 60, 0, 60, 85, BlockChangeReason.Completed),
            ProgramEvent.BlockStarted(at(60), 60, 1, b[1], BlockChangeReason.Completed),
            ProgramEvent.ManualChange(at(70), 70, 1, 7.0, null, ManualChangeSource.App),
            ProgramEvent.BlockCompleted(at(120), 120, 1, 60, 120, BlockChangeReason.Completed),
            ProgramEvent.BlockStarted(at(120), 120, 2, b[2], BlockChangeReason.Completed),
            ProgramEvent.BlockCompleted(at(180), 180, 2, 60, 83, BlockChangeReason.Completed),
            ProgramEvent.HeartRateAdjusted(at(180), 180, 3, HeartRateAdjust.Speed, 7.5, 7.3, HeartRateAdjustReason.NextRepetition, null),
            ProgramEvent.BlockStarted(at(180), 180, 3, b[3], BlockChangeReason.Completed),
            ProgramEvent.BlockCompleted(at(240), 240, 3, 60, 120, BlockChangeReason.SkippedNext),
            ProgramEvent.BlockStarted(at(240), 240, 4, b[4], BlockChangeReason.SkippedNext),
            finished,
        )
        return finished to list
    }

    private val samples = (0..250).map { s -> TimedHeartRate(at(s), if (s <= 60) 100 else if (s <= 180) 120 else 130) }

    @Test
    fun `the record keeps outcome, manual reductions, end heart rates and heart-rate summaries`() {
        val (finished, events) = events()
        val r = GeneratedSessionRecords.build(origin, finished, events, samples)
        assertEquals(SessionOutcome.Stopped, r.outcome)
        assertEquals(origin, r.origin)
        assertEquals(PROGRAM_ID, r.programId)
        assertEquals(at(0), r.startedAtMillis)
        assertEquals(at(250), r.endedAtMillis)
        assertEquals(180, r.maxHeartRateBpm)
        assertEquals(1, r.manualSpeedReductions)
        assertEquals(2, r.manualChanges)
        assertEquals(2, r.workBlocksPlanned)
        assertEquals(1, r.workBlocksCompleted)
        assertFalse(r.workCompleted)
        assertEquals(listOf(0, 1, 2, 3, 4), r.blocks.map { it.blockIndex })
        assertEquals(listOf("0", "1.0", "1.1", "1.0", "1.1"), r.blocks.map { it.key })

        val warm = r.blocks[0]
        assertTrue(warm.completed)
        assertEquals(0, warm.manualSpeedReductions)
        assertEquals(5.5, warm.finalSpeedKmh)
        assertEquals(100.0, warm.endHeartRateBpm)

        val run1 = r.blocks[1]
        assertTrue(run1.completed)
        assertEquals(1, run1.manualSpeedReductions)
        assertEquals(7.5, run1.plannedSpeedKmh)
        assertEquals(7.0, run1.finalSpeedKmh)
        assertEquals(150.0, run1.endHeartRateBpm, "from the heart-rate summary")
        assertEquals(1, run1.heartRate!!.blockIndex)
        assertEquals(1, run1.repetition)

        assertEquals(120.0, r.blocks[2].endHeartRateBpm, "from the samples")

        val run2 = r.blocks[3]
        assertFalse(run2.completed, "skipped")
        assertEquals(7.3, run2.finalSpeedKmh, "the corrected start of the repetition")
        assertEquals(160.0, run2.endHeartRateBpm)
        assertEquals(2, run2.repetition)

        val last = r.blocks[4]
        assertFalse(last.completed)
        assertEquals(10, last.elapsedSeconds)
        assertEquals(130.0, last.endHeartRateBpm)
        assertNull(last.heartRate)
    }

    @Test
    fun `without samples blocks without heart-rate control have no end heart rate`() {
        val (finished, events) = events()
        val r = GeneratedSessionRecords.build(origin, finished, events)
        assertNull(r.blocks[0].endHeartRateBpm)
        assertEquals(150.0, r.blocks[1].endHeartRateBpm)
    }

    @Test
    fun `the Started event is required`() {
        val (finished, events) = events()
        assertThrows<IllegalArgumentException> { GeneratedSessionRecords.build(origin, finished, events.drop(1)) }
    }

    @Test
    fun `JSON round trip, and a newer version is rejected`() {
        val (finished, events) = events()
        val r = GeneratedSessionRecords.build(origin, finished, events, samples)
        val text = GeneratedSessionJson.encode(r)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"outcome\": \"stopped\""))
        assertTrue(text.contains("\"startedAt\": \"2026-10-14T18:00:00Z\""))
        assertEquals(r, GeneratedSessionJson.decode(text))
        assertThrows<ProgramFormatException> { GeneratedSessionJson.decode(text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
        assertThrows<ProgramFormatException> { GeneratedSessionJson.decode("[]") }
    }

    // ------------------------------------------------------------------ end to end on the simulator

    @Test
    fun `couch to 5K session 1 on the simulator - record and next session`() = runTest {
        val generated = CouchToFiveK.generate(request())
        val plan = ProgramResolver.resolve(generated.program)
        val rig = HeartRateRig(this, profile = HeartRateProfile(180))
        rig.connect()
        assertEquals(RunnerResult.Success, rig.start(plan))
        rig.awaitFinished()
        val finished = rig.eventsOf<ProgramEvent.Finished>().single()
        val record = GeneratedSessionRecords.build(checkNotNull(plan.source.origin), finished, rig.events.toList())

        assertEquals(SessionOutcome.Completed, record.outcome)
        assertEquals(8, record.workBlocksPlanned)
        assertEquals(8, record.workBlocksCompleted)
        assertTrue(record.workCompleted)
        assertEquals(0, record.manualSpeedReductions)
        val runs = record.blocks.filter { it.phase == BlockPhase.Work }
        assertEquals(8, runs.size)
        assertTrue(runs.all { it.endHeartRateBpm != null && it.heartRate != null }, "$runs")
        assertTrue(runs.all { it.finalSpeedKmh <= 7.5 }, "short runs are never faster than the jog")
        assertEquals("1", record.origin.inputs["session"])
        assertEquals(record, GeneratedSessionJson.decode(GeneratedSessionJson.encode(record)))

        val next = CouchToFiveK.generate(request(history = listOf(record), nowMillis = record.endedAtMillis + 2 * DAY + HOUR))
        val hard = runs.any { checkNotNull(it.endHeartRateBpm) >= 153.0 || it.heartRate!!.safetyReductions > 0 }
        val expected = if (hard) CouchToFiveKDecision.RepeatHardEffort else CouchToFiveKDecision.Advance
        assertEquals(expected, next.couchToFiveK!!.decision, "$runs")
        assertEquals(if (hard) 1 else 2, next.couchToFiveK!!.session)
    }

    @Test
    fun `zone 2 on the simulator - the next session starts where the heart rate settled`() = runTest {
        val generated = ProgramGenerators.generate("zone2", request(durationMinutes = 30))
        val plan = ProgramResolver.resolve(generated.program)
        val rig = HeartRateRig(this, profile = HeartRateProfile(180))
        rig.connect()
        assertEquals(RunnerResult.Success, rig.start(plan))
        rig.awaitFinished()
        val record = GeneratedSessionRecords.build(checkNotNull(plan.source.origin), rig.eventsOf<ProgramEvent.Finished>().single(), rig.events.toList())
        val main = record.blocks.single { it.key == "1" }
        val settled = checkNotNull(main.heartRate?.settledValue) { "$main" }

        val next = ProgramGenerators.generate("zone2", request(durationMinutes = 30, history = listOf(record), nowMillis = record.endedAtMillis + DAY))
        val target = (next.program.steps[1] as Block).target as BlockTarget.HeartRate
        assertEquals(BlockTarget.Absolute(settled, 1.0), target.start)
        assertTrue(next.notices.any { it.code == GeneratorNoticeCode.StartedFromHistory && it.blockKey == "1" })
    }
}
