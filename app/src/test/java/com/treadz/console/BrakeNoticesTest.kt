package com.treadz.console

import com.treadz.console.programs.engine.HeartRateAdjustReason
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.training.BrakeNotices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The short notice each time the real-time α1 brake lowers the load of a program: on for 6 seconds, like the other short notices. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrakeNoticesTest {

    private fun adjusted(reason: HeartRateAdjustReason) = ProgramEvent.HeartRateAdjusted(
        atMillis = 0, programSeconds = 600, blockIndex = 1, adjust = HeartRateAdjust.Incline, fromValue = 6.0, toValue = 5.0, reason = reason, heartRateBpm = 131.0,
    )

    @Test fun anAlpha1ReductionShowsTheNoticeForSixSecondsAndItGoesByItself() = runTest {
        val events = MutableSharedFlow<ProgramEvent>(extraBufferCapacity = 16)
        val notices = BrakeNotices(backgroundScope, events)
        runCurrent()
        assertNull(notices.notice.value)

        events.emit(adjusted(HeartRateAdjustReason.Alpha1Brake))
        runCurrent()
        assertNotNull(notices.notice.value)
        advanceTimeBy(5_900)
        assertNotNull(notices.notice.value)
        advanceTimeBy(200)
        runCurrent()
        assertNull(notices.notice.value)
        assertEquals(6_000L, BrakeNotices.VISIBLE_MILLIS)
    }

    @Test fun anotherReductionIsAnotherNoticeAndRestartsTheTime() = runTest {
        val events = MutableSharedFlow<ProgramEvent>(extraBufferCapacity = 16)
        val notices = BrakeNotices(backgroundScope, events)
        runCurrent()
        events.emit(adjusted(HeartRateAdjustReason.Alpha1Brake))
        runCurrent()
        val first = notices.notice.value
        advanceTimeBy(4_000)
        events.emit(adjusted(HeartRateAdjustReason.Alpha1Brake))
        runCurrent()
        val second = notices.notice.value
        assertNotEquals(first, second)
        // The first one would have ended at 6 s; the second keeps it on until 10 s.
        advanceTimeBy(3_000)
        assertNotNull(notices.notice.value)
        advanceTimeBy(3_100)
        runCurrent()
        assertNull(notices.notice.value)
    }

    @Test fun otherAdjustmentsAndOtherEventsSayNothingAndDoNotHideTheNotice() = runTest {
        val events = MutableSharedFlow<ProgramEvent>(extraBufferCapacity = 16)
        val notices = BrakeNotices(backgroundScope, events)
        runCurrent()
        for (reason in HeartRateAdjustReason.entries.filter { it != HeartRateAdjustReason.Alpha1Brake }) {
            events.emit(adjusted(reason))
            runCurrent()
            assertNull("$reason", notices.notice.value)
        }
        events.emit(adjusted(HeartRateAdjustReason.Alpha1Brake))
        runCurrent()
        val shown = notices.notice.value
        events.emit(adjusted(HeartRateAdjustReason.AboveZone))
        events.emit(ProgramEvent.Paused(0, 10, 1))
        runCurrent()
        assertEquals(shown, notices.notice.value)
    }
}
