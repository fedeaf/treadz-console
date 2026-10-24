package com.treadz.console.programs.engine

import com.treadz.console.programs.block
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.programs.personalised.GeneratedSessionRecords
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.CommandOrigin
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.LoadEventType
import com.treadz.console.treadmill.events.LoadQuantity
import com.treadz.console.treadmill.simulator.PhysiologicalHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedBodyInput
import com.treadz.console.treadmill.simulator.SimulatedHeartRateModel
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/**
 * The α1 brake end to end: the simulated treadmill, the controller, the runner and a strap whose RR intervals have a
 * known behaviour. Every reduction must go out as an ordinary heart-rate adjustment through the controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Alpha1BrakeRunnerTest {

    /** How the beats of the synthetic strap vary around the mean RR. */
    private enum class RrStyle {
        /** Alternating and random: DFA-α1 far below 0.7 (as with a high effort for the person). */
        Low,

        /** Slow oscillation with little noise: α1 well above 1 (as at a comfortable effort). */
        High,

        /** Like [Low] with every 15th interval doubled (a missed beat): 6.7 % artifacts, above what is accepted. */
        LowWithArtifacts,
    }

    /**
     * A strap connected to the app, simulated one level above the Bluetooth: one reading per second with the beats of
     * that second. With [kind] = console it reports the heart rate only (no RR), like the console's relay.
     */
    private class SyntheticStrap(
        scope: CoroutineScope,
        private val clock: Clock,
        private val bpm: () -> Int,
        private val style: RrStyle,
        private val kind: HeartRateSourceKind,
    ) : HeartRateSource {
        private val flow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 256)
        private val random = Random(17)
        private var beatIndex = 0
        private var nextBeatAt = clock.nowMillis().toDouble()

        @Volatile
        private var last = HeartRateReading.NONE

        override fun latest() = last
        override val readings: Flow<HeartRateReading> = flow.asSharedFlow()

        init {
            scope.launch {
                while (true) {
                    delay(1_000)
                    emit()
                }
            }
        }

        private fun interval(): Int {
            val base = 60_000.0 / bpm()
            val i = beatIndex++
            val deviation = when (style) {
                RrStyle.Low, RrStyle.LowWithArtifacts -> (if (i % 2 == 0) 15.0 else -15.0) + 15.0 * random.nextGaussian()
                RrStyle.High -> 40.0 * sin(2 * PI * i / 50.0) + 2.0 * random.nextGaussian()
            }
            val rr = base + deviation
            return if (style == RrStyle.LowWithArtifacts && i % 15 == 7) (rr * 2).toInt() else rr.toInt()
        }

        private fun emit() {
            val now = clock.nowMillis()
            val rr = ArrayList<Int>()
            while (nextBeatAt <= now) {
                val ms = interval()
                rr += ms
                nextBeatAt += ms
            }
            val reading = if (kind == HeartRateSourceKind.DirectStrap) {
                HeartRateReading(bpm(), HeartRateValidity.Valid, now, kind, rr)
            } else {
                HeartRateReading(bpm(), HeartRateValidity.Unconfirmed, now, kind)
            }
            last = reading
            flow.tryEmit(reading)
        }
    }

    private class Rig(
        testScope: TestScope,
        style: RrStyle,
        kind: HeartRateSourceKind = HeartRateSourceKind.DirectStrap,
        scripted: Int? = 120,
        val profile: HeartRateProfile = HeartRateProfile(maxBpm = 180, restingBpm = 60),
    ) {
        private val bg = testScope.backgroundScope
        val clock = Clock { T0 + testScope.currentTime }
        val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
        val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
        val physiology = PhysiologicalHeartRateModel(restingBpm = 60, maxBpm = 180, vo2maxMlKgMin = 45.0, noiseBpm = 1)

        @Volatile
        var bodyBpm = 70
        val source = SyntheticStrap(bg, clock, { bodyBpm }, style, kind)
        val runner = ProgramRunner(controller, bg, clock, RunnerConfig(), source)
        val events = CopyOnWriteArrayList<ProgramEvent>()
        val commands = CopyOnWriteArrayList<TraceRecord.CommandIssued>()
        val progress: ProgramProgress get() = runner.progress.value

        init {
            // The simulator's heart-rate model feeds the strap: a fixed rate, or the physiological response to the load.
            sim.heartRateModel = SimulatedHeartRateModel { input: SimulatedBodyInput ->
                bodyBpm = scripted ?: physiology.nextSecond(input)
                0
            }
            controller.addObserver { if (it is TraceRecord.CommandIssued) commands += it }
            bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.events.collect { events += it } }
        }

        suspend fun run(plan: com.treadz.console.programs.plan.ResolvedPlan) {
            assertEquals(com.treadz.console.treadmill.CommandResult.Success, controller.connect(sim.address))
            delay(3_000)
            assertEquals(RunnerResult.Success, runner.start(plan, RunOptions(heartRateProfile = profile)))
            var waited = 0L
            while (progress.state != RunState.Finished) {
                check(waited <= 7_200_000) { "timed out (state=${progress.state}, block=${progress.currentBlockIndex})" }
                delay(500)
                waited += 500
            }
        }

        inline fun <reified T : ProgramEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

        fun brakeEvents() = eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.Alpha1Brake }
    }

    /** 2 min warm-up, [seconds] in [zone] adjusting the incline between 0 and 10 % from 4 % at 5 km/h, 1 min cool-down. */
    private fun plan(zone: HeartRateZone, seconds: Int = 900) = planOf(
        timed(120, 5.0, 4.0, BlockPhase.WarmUp),
        block(BlockPhase.Work, BlockEnd.Duration(seconds), BlockTarget.HeartRate(zone, HeartRateAdjust.Incline, 0.0, 10.0, BlockTarget.Absolute(5.0, 4.0))),
        timed(60, 4.0, 0.0, BlockPhase.CoolDown),
    )

    /** Zone 2: 45-55 % of the reserve = 114-126 bpm. */
    private val zone2 = HeartRateZone(HeartRateBasis.HeartRateReserve, 45, 55)

    /** Zone 3: 70-80 % of the maximum = 126-144 bpm. */
    private val zone3 = HeartRateZone(HeartRateBasis.MaxHeartRate, 70, 80)

    @Test
    fun `low α1 in a zone 2 block - the incline goes down 1 percent at a time through the normal command path`() = runTest {
        val rig = Rig(this, RrStyle.Low)
        rig.run(plan(zone2))

        val brake = rig.brakeEvents()
        // Incline 4 % to 0 % in four steps, each at least 180 s after the previous one, the first after six complete windows.
        assertEquals(listOf(4.0 to 3.0, 3.0 to 2.0, 2.0 to 1.0, 1.0 to 0.0), brake.map { it.fromValue to it.toValue }, "$brake")
        assertTrue(brake.all { it.adjust == HeartRateAdjust.Incline && it.blockIndex == 1 })
        assertTrue(brake[0].programSeconds - 120 in 160..200, "first reduction ${brake[0].programSeconds - 120} s into the block")
        assertTrue(brake.zipWithNext().all { (a, b) -> b.programSeconds - a.programSeconds >= 180 }, "$brake")

        // Each one went out as a verified command of the heart-rate control, and nothing else did.
        val byControl = rig.commands.filter { it.origin == CommandOrigin.HeartRateControl }
        assertEquals(listOf("setIncline", "setIncline", "setIncline", "setIncline"), byControl.map { it.name })
        assertEquals(0, rig.eventsOf<ProgramEvent.ManualChange>().size)
        assertTrue(rig.eventsOf<ProgramEvent.Interrupted>().isEmpty())

        // The block summary and the stored record carry the figures.
        val summary = rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary
        assertEquals(4, summary.alpha1Reductions)
        assertEquals(4, summary.adjustments)
        assertEquals(0, summary.safetyReductions)
        assertEquals(0.0, summary.endValue)
        val mean = checkNotNull(summary.alpha1Mean)
        assertTrue(mean < 0.6, "mean α1 $mean")
        assertTrue(checkNotNull(summary.alpha1Windows) in 60..80, "${summary.alpha1Windows}")
        assertEquals(HeartRateSourceKind.DirectStrap, summary.source)

        // Each reduction is also a load event (and nothing else is), in the final event too.
        val loadEvents = rig.runner.loadEvents.value
        assertEquals(
            brake.map { LoadEvent(it.programSeconds, LoadEventType.Alpha1Reduction, LoadQuantity.Incline, it.toValue) },
            loadEvents.filter { it.type == LoadEventType.Alpha1Reduction },
        )
        assertTrue(loadEvents.all { it.type == LoadEventType.Alpha1Reduction || it.type == LoadEventType.HeartRateIncrease || it.type == LoadEventType.HeartRateDecrease }, "$loadEvents")
        assertEquals(loadEvents, rig.eventsOf<ProgramEvent.Finished>().single().loadEvents)

        val finished = rig.eventsOf<ProgramEvent.Finished>().single()
        val record = GeneratedSessionRecords.build(ProgramOrigin("zone2", 1, emptyMap()), finished, rig.events)
        val stored = checkNotNull(record.blocks.single { it.blockIndex == 1 }.heartRate)
        assertEquals(summary, stored)
        assertEquals(record, GeneratedSessionJson.decode(GeneratedSessionJson.encode(record)))
    }

    @Test
    fun `the simulator's incline follows the order`() = runTest {
        val rig = Rig(this, RrStyle.Low)
        assertEquals(com.treadz.console.treadmill.CommandResult.Success, rig.controller.connect(rig.sim.address))
        delay(3_000)
        rig.runner.start(plan(zone2), RunOptions(heartRateProfile = rig.profile))
        rig.awaitReduction(1)
        delay(30_000)
        assertEquals(3.0, rig.sim.currentInclinePercent)
        assertEquals(3.0, rig.progress.targetInclinePercent)
        rig.awaitReduction(2)
        delay(30_000)
        assertEquals(2.0, rig.sim.currentInclinePercent)
        rig.runner.stop()
    }

    @Test
    fun `high α1 - no reduction, and the mean is recorded`() = runTest {
        val rig = Rig(this, RrStyle.High)
        rig.run(plan(zone2))
        assertTrue(rig.brakeEvents().isEmpty())
        val summary = rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary
        assertEquals(0, summary.alpha1Reductions)
        assertEquals(0, summary.adjustments)
        assertTrue(checkNotNull(summary.alpha1Mean) > 1.0, "${summary.alpha1Mean}")
        assertEquals(4.0, summary.endValue)
    }

    @Test
    fun `more than 5 percent artifacts - the brake is off, the control goes on alone`() = runTest {
        val rig = Rig(this, RrStyle.LowWithArtifacts)
        rig.run(plan(zone2))
        assertTrue(rig.brakeEvents().isEmpty())
        val summary = rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary
        assertNull(summary.alpha1Reductions)
        assertNull(summary.alpha1Mean)
        assertEquals(0, summary.adjustments)
    }

    @Test
    fun `the console's heart rate has no RR - no brake`() = runTest {
        val rig = Rig(this, RrStyle.Low, kind = HeartRateSourceKind.TreadmillConsole)
        rig.run(plan(zone2))
        assertTrue(rig.brakeEvents().isEmpty())
        val summary = rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary
        assertNull(summary.alpha1Reductions)
        assertNull(summary.alpha1Mean)
        assertEquals(HeartRateSourceKind.TreadmillConsole, summary.source)
    }

    @Test
    fun `a zone 3 block never gets the brake - the windows are recorded`() = runTest {
        val rig = Rig(this, RrStyle.Low, scripted = 135)
        rig.run(plan(zone3))
        assertTrue(rig.brakeEvents().isEmpty())
        val summary = rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary
        assertNull(summary.alpha1Reductions)
        assertTrue(checkNotNull(summary.alpha1Mean) < 0.6)
        assertEquals(0, summary.adjustments)
    }

    @Test
    fun `with a measured VT1 the zone ceiling decides`() = runTest {
        // Zone 3 (ceiling 144) but a VT1 of 145: the block counts as easy. Zone 2 (ceiling 126) with a VT1 of 110 does not.
        val easy = Rig(this, RrStyle.Low, scripted = 135, profile = HeartRateProfile(180, 60, vt1Bpm = 145))
        easy.run(plan(zone3, seconds = 400))
        assertTrue(easy.brakeEvents().isNotEmpty())
        val hard = Rig(this, RrStyle.Low, profile = HeartRateProfile(180, 60, vt1Bpm = 110))
        hard.run(plan(zone2, seconds = 400))
        assertTrue(hard.brakeEvents().isEmpty())
    }

    @Test
    fun `a manual change switches the brake off for the rest of the block`() = runTest {
        val rig = Rig(this, RrStyle.Low)
        assertEquals(com.treadz.console.treadmill.CommandResult.Success, rig.controller.connect(rig.sim.address))
        delay(3_000)
        rig.runner.start(plan(zone2), RunOptions(heartRateProfile = rig.profile))
        rig.awaitReduction(1)
        delay(5_000)
        rig.runner.adjustIncline(6.0)
        delay(400_000)
        assertEquals(1, rig.brakeEvents().size)
        assertEquals(HeartRateControlState.OffByManualChange, rig.progress.heartRateControl?.state)
        rig.runner.stop()
    }

    @Test
    fun `the physiological heart-rate model - the brake lowers the load while the heart rate stays in the zone`() = runTest {
        // A zone the simulated body stays in or above while walking, so the heart-rate condition holds.
        val rig = Rig(this, RrStyle.Low, scripted = null)
        rig.run(plan(HeartRateZone(HeartRateBasis.HeartRateReserve, 10, 55), seconds = 600))
        val brake = rig.brakeEvents()
        assertTrue(brake.isNotEmpty(), "${rig.eventsOf<ProgramEvent.HeartRateAdjusted>()}")
        assertTrue(brake.all { it.toValue < it.fromValue })
        assertNotNull(rig.eventsOf<ProgramEvent.HeartRateBlockFinished>().single().summary.alpha1Mean)
    }

    private suspend fun Rig.awaitReduction(count: Int) {
        var waited = 0L
        while (brakeEvents().size < count) {
            check(waited <= 3_600_000) { "no reduction number $count (state=${progress.state})" }
            delay(500)
            waited += 500
        }
    }
}
