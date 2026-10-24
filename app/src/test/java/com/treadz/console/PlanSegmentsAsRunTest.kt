package com.treadz.console

import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.programs.engine.BlockStart
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.training.PlanSegment
import com.treadz.console.training.asRun
import com.treadz.console.training.blockStartsOf
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventQuantity
import com.treadz.console.userdata.WorkoutEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan placed on the real clock of a run: skipped, extended and revisited blocks, live and finished. */
class PlanSegmentsAsRunTest {

    /** Warm-up 5 min, work 31 min ("+1" six times), cool-down 5 min. */
    private val plan = listOf(
        PlanSegment(0, 0, 300, 5.6, 0.0, BlockPhase.WarmUp),
        PlanSegment(1, 300, 2160, 5.6, 10.0, BlockPhase.Work),
        PlanSegment(2, 2160, 2460, 5.6, 0.0, BlockPhase.CoolDown),
    )

    private fun List<PlanSegment>.bounds() = map { Triple(it.index, it.startSeconds, it.endSeconds) }

    @Test fun `without starts the plan is drawn as planned`() {
        assertEquals(plan, plan.asRun(emptyList()))
    }

    @Test fun `a skipped warm-up moves the rest of the plan back`() {
        val live = plan.asRun(listOf(BlockStart(0, 0), BlockStart(1, 142)), lastElapsedSeconds = 1300)
        assertEquals(listOf(Triple(0, 0, 142), Triple(1, 142, 2002), Triple(2, 2002, 2302)), live.bounds())
        // the targets of each block are kept
        assertEquals(10.0, live[1].inclinePercent, 0.0)
    }

    @Test fun `the current block grows while it runs past its planned length`() {
        val live = plan.asRun(listOf(BlockStart(0, 0)), lastElapsedSeconds = 340)
        assertEquals(listOf(Triple(0, 0, 340), Triple(1, 340, 2200), Triple(2, 2200, 2500)), live.bounds())
    }

    @Test fun `a finished run ends where it ended`() {
        val done = plan.asRun(listOf(BlockStart(0, 0), BlockStart(1, 142), BlockStart(2, 2002)), endSeconds = 2298)
        assertEquals(listOf(Triple(0, 0, 142), Triple(1, 142, 2002), Triple(2, 2002, 2298)), done.bounds())
        // stopped during the work block: the cool-down that was not done follows
        val stopped = plan.asRun(listOf(BlockStart(0, 0), BlockStart(1, 300)), endSeconds = 1000)
        assertEquals(listOf(Triple(0, 0, 300), Triple(1, 300, 1000), Triple(2, 1000, 1300)), stopped.bounds())
    }

    @Test fun `a block run again appears again`() {
        val back = plan.asRun(listOf(BlockStart(0, 0), BlockStart(1, 300), BlockStart(0, 330), BlockStart(1, 400)), lastElapsedSeconds = 10)
        assertEquals(listOf(0, 1, 0, 1, 2), back.map { it.index })
        assertEquals(listOf(Triple(0, 0, 300), Triple(1, 300, 330), Triple(0, 330, 400), Triple(1, 400, 2260), Triple(2, 2260, 2560)), back.bounds())
    }

    @Test fun `the starts of a recorded workout come from its events, else from its session record`() {
        val events = listOf(
            WorkoutEvent(25, WorkoutEventType.ManualChange, WorkoutEventQuantity.Incline, 1.0),
            WorkoutEvent(142, WorkoutEventType.BlockStarted, block = 1),
            WorkoutEvent(2002, WorkoutEventType.BlockStarted, block = 2),
        )
        assertEquals(listOf(BlockStart(0, 0), BlockStart(1, 142), BlockStart(2, 2002)), blockStartsOf(events, null))

        val base = GeneratedFixtures.record(generatorId = "zone2", startedAtMillis = 0)
        val work = base.blocks.single()
        val record = base.copy(
            blocks = listOf(
                work.copy(blockIndex = 0, phase = BlockPhase.WarmUp, elapsedSeconds = 142),
                work.copy(blockIndex = 1, elapsedSeconds = 1860),
                work.copy(blockIndex = 2, phase = BlockPhase.CoolDown, elapsedSeconds = 300),
            ),
        )
        assertEquals(listOf(BlockStart(0, 0), BlockStart(1, 142), BlockStart(2, 2002)), blockStartsOf(events.take(1), record))
        assertTrue(blockStartsOf(events.take(1), null).isEmpty())
    }
}
