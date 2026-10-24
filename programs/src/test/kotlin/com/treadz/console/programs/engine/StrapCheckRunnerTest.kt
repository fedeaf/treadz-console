package com.treadz.console.programs.engine

import com.treadz.console.programs.block
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.heartrate.StrapWindowConfig
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.programs.personalised.GeneratedSessionRecords
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.CommandOrigin
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End to end (simulated treadmill, controller, runner): a strap that reads 137 for 30 s while its beats are impossible
 * intervals and the real rate stays at 127 (the audit of 2026-10-24). The control must not move the incline for it, and
 * the status and the block summary must say so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrapCheckRunnerTest {

    /** A strap connected to the app: one reading per second; from [falseFrom] to [falseTo] ms of virtual time it lies. */
    private class FalseStrap(scope: CoroutineScope, private val clock: Clock, private val falseRange: LongRange) : HeartRateSource {
        private val flow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 256)
        private val junk = doubleArrayOf(0.5, 1.4, 0.6, 1.3, 0.55, 1.45)
        private val startedAt = clock.nowMillis()
        private var beat = 0
        private var carry = 0.0

        @Volatile
        private var last = HeartRateReading.NONE
        override fun latest() = last
        override val readings: Flow<HeartRateReading> = flow.asSharedFlow()

        init {
            scope.launch {
                while (true) {
                    delay(1_000)
                    val now = clock.nowMillis()
                    val lying = (now - startedAt) in falseRange
                    carry += 1_000.0
                    val rr = ArrayList<Int>()
                    val ms = 60_000 / 127
                    while (carry >= ms) {
                        val length = if (lying) (ms * junk[beat++ % junk.size]).toInt() else ms
                        carry -= length
                        rr += length
                    }
                    val reading = HeartRateReading(if (lying) 137 else 127, HeartRateValidity.Valid, now, HeartRateSourceKind.DirectStrap, rr)
                    last = reading
                    flow.tryEmit(reading)
                }
            }
        }
    }

    private class Rig(testScope: TestScope, falseRange: LongRange, config: RunnerConfig) {
        private val bg = testScope.backgroundScope
        val clock = Clock { T0 + testScope.currentTime }
        val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
        val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
        val strap = FalseStrap(bg, clock, falseRange)
        val runner = ProgramRunner(controller, bg, clock, config, strap)
        val events = CopyOnWriteArrayList<ProgramEvent>()
        val commands = CopyOnWriteArrayList<TraceRecord.CommandIssued>()
        val progress: ProgramProgress get() = runner.progress.value

        init {
            controller.addObserver { if (it is TraceRecord.CommandIssued) commands += it }
            bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.events.collect { events += it } }
        }

        suspend fun start() {
            assertEquals(CommandResult.Success, controller.connect(sim.address))
            delay(3_000)
            val plan = planOf(
                timed(120, 5.6, 4.0, BlockPhase.WarmUp),
                block(
                    BlockPhase.Work, BlockEnd.Duration(480),
                    BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.HeartRateReserve, 50, 57), HeartRateAdjust.Incline, 0.0, 15.0, BlockTarget.Absolute(5.6, 10.0)),
                ),
                timed(60, 4.0, 0.0, BlockPhase.CoolDown),
            )
            assertEquals(RunnerResult.Success, runner.start(plan, RunOptions(heartRateProfile = HeartRateProfile(maxBpm = 185, restingBpm = 58))))
        }

        suspend fun until(message: String, condition: () -> Boolean) {
            var waited = 0L
            while (!condition()) {
                check(waited <= 3_600_000) { "timed out: $message (state=${progress.state}, block=${progress.currentBlockIndex})" }
                delay(250)
                waited += 250
            }
        }

        inline fun <reified T : ProgramEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
    }

    // The program starts about 3 s after the strap (the connection); the block 120 s into the program. The lie covers
    // 105-140 s into the block, so the decisions at 120 s and 140 s (block clock) see it.
    private val lie = 228_000L..263_000L
    private val noAlpha1 = HeartRateControlConfig(alpha1Brake = Alpha1BrakeConfig(enabled = false))

    @Test
    fun `the false rise changes nothing, the status says deferred and the summary counts it`() = runTest {
        val rig = Rig(this, lie, RunnerConfig(heartRate = noAlpha1))
        rig.start()
        rig.until("a deferred decision") { rig.progress.heartRateControl?.decisionDeferredByStrap == true }
        assertEquals(1, rig.progress.currentBlockIndex)
        assertEquals(HeartRateControlState.Waiting, rig.progress.heartRateControl?.state)
        assertTrue(checkNotNull(rig.progress.heartRateControl?.heartRateMeanBpm) > 130.0, "the control saw the false readings")
        rig.until("finished") { rig.progress.state == RunState.Finished }

        assertTrue(rig.eventsOf<ProgramEvent.HeartRateAdjusted>().isEmpty(), "${rig.eventsOf<ProgramEvent.HeartRateAdjusted>()}")
        assertTrue(rig.commands.none { it.origin == CommandOrigin.HeartRateControl })
        val summary = rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary
        assertEquals(0, summary.adjustments)
        assertEquals(10.0, summary.endValue)
        assertTrue(summary.strapDeferrals >= 1, "$summary")
        assertEquals(summary, rig.eventsOf<ProgramEvent.Finished>().single().heartRateSummaries.single())

        // The count is stored in the session record (only when above 0) and reads back.
        val record = GeneratedSessionRecords.build(ProgramOrigin("zone2", 3, emptyMap()), rig.eventsOf<ProgramEvent.Finished>().single(), rig.events)
        assertEquals(summary, record.blocks.single { it.blockIndex == 1 }.heartRate)
        val text = GeneratedSessionJson.encode(record)
        assertTrue(text.contains("\"strapDeferrals\": ${summary.strapDeferrals}"), text)
        assertEquals(record, GeneratedSessionJson.decode(text))
    }

    @Test
    fun `without the check the same strap makes the control lower the incline`() = runTest {
        val rig = Rig(this, lie, RunnerConfig(heartRate = noAlpha1.copy(strapWindow = StrapWindowConfig(enabled = false))))
        rig.start()
        rig.until("finished") { rig.progress.state == RunState.Finished }
        val lowered = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.AboveZone }
        assertTrue(lowered.isNotEmpty(), "this is what the audit found")
        assertFalse(rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary.strapDeferrals > 0)
    }
}
