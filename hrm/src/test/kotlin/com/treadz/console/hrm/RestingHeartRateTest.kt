package com.treadz.console.hrm

import com.treadz.console.hrm.resting.RestingHeartRateMeter
import com.treadz.console.hrm.resting.RestingHeartRateSession
import com.treadz.console.hrm.resting.RestingInvalidReason
import com.treadz.console.hrm.resting.RestingSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestingHeartRateTest {

    /** Feeds a meter at 2 Hz (each value repeated, like the console) with bpm(t in seconds). */
    private fun feed(meter: RestingHeartRateMeter, untilMs: Long = 180_000, contact: (Long) -> SensorContact = { SensorContact.Detected }, bpm: (Long) -> Int?) {
        var t = 500L
        while (t <= untilMs) {
            meter.add(RestingSample(t, bpm(t / 1000), contact(t)))
            t += 500
        }
    }

    @Test
    fun `the value is the lowest 30-s mean of the last 2 minutes`() {
        val m = RestingHeartRateMeter(0)
        // Minute 1 settles at 50 (ignored); then 70, a quieter 30 s at 58-60 in the middle, 66 at the end.
        feed(m) { s ->
            when {
                s < 60 -> 50
                s in 100 until 130 -> if (s % 2 == 0L) 58 else 60
                s >= 150 -> 66
                else -> 70
            }
        }
        val r = m.result(180_000)
        assertTrue(r.valid, "$r")
        assertEquals(59, r.bpm)
        assertEquals(59.2, r.lowestMeanBpm) // 29 seconds at 59 and one edge second shared with the 70s
        assertEquals(1.0, r.coverage)
        assertEquals(0, r.badSamples)
    }

    @Test
    fun `stopping before 3 minutes is invalid`() {
        val m = RestingHeartRateMeter(0)
        feed(m, untilMs = 150_000) { 60 }
        val r = m.result(150_000)
        assertFalse(r.valid)
        assertEquals(listOf(RestingInvalidReason.TooShort), r.invalidReasons)
        assertNull(r.bpm)
        assertEquals(60.0, r.lowestMeanBpm)
    }

    @Test
    fun `a gap longer than 10 s makes it invalid`() {
        val m = RestingHeartRateMeter(0)
        feed(m) { s -> if (s in 120 until 132) null else 62 }
        val r = m.result(180_000)
        assertFalse(r.valid)
        assertTrue(RestingInvalidReason.MissingReadings in r.invalidReasons, "$r")
        assertEquals(12, r.longestGapSeconds)
    }

    @Test
    fun `short gaps are tolerated`() {
        val m = RestingHeartRateMeter(0)
        feed(m) { s -> if (s % 30 == 0L) null else 62 } // one missing second every 30 s
        val r = m.result(180_000)
        assertTrue(r.valid, "$r")
        assertEquals(62, r.bpm)
    }

    @Test
    fun `contact lost or artifacts make the readings unreliable`() {
        val m = RestingHeartRateMeter(0)
        // 20 % of the seconds of the last 2 minutes without contact (the strap still sends a value).
        feed(m, contact = { t -> if ((t / 1000) % 5 == 0L) SensorContact.NotDetected else SensorContact.Detected }) { 64 }
        val r = m.result(180_000)
        assertFalse(r.valid)
        assertTrue(RestingInvalidReason.UnreliableReadings in r.invalidReasons, "$r")

        val a = RestingHeartRateMeter(0)
        // A spike of +40 bpm every 4 s is an artifact, not a heart rate.
        feed(a) { s -> if (s % 4 == 0L) 104 else 64 }
        val ra = a.result(180_000)
        assertFalse(ra.valid)
        assertTrue(RestingInvalidReason.UnreliableReadings in ra.invalidReasons, "$ra")
    }

    @Test
    fun `isolated artifacts are ignored and do not change the value`() {
        val m = RestingHeartRateMeter(0)
        feed(m) { s -> if (s == 140L) 150 else 61 }
        val r = m.result(180_000)
        assertTrue(r.valid, "$r")
        assertEquals(61, r.bpm)
        assertEquals(2, r.badSamples) // the repeated packet of that second
    }

    @Test
    fun `an implausible resting value is invalid`() {
        val m = RestingHeartRateMeter(0)
        feed(m) { 130 }
        val r = m.result(180_000)
        assertEquals(listOf(RestingInvalidReason.ImplausibleValue), r.invalidReasons)
        assertNull(r.bpm)
    }

    @Test
    fun `no readings at all`() {
        val m = RestingHeartRateMeter(0)
        feed(m) { null }
        val r = m.result(180_000)
        assertFalse(r.valid)
        assertTrue(RestingInvalidReason.MissingReadings in r.invalidReasons)
        assertNull(r.lowestMeanBpm)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    /** Feeds a meter at 1 Hz at 60 bpm, one RR interval per sample from rr(t in seconds) (`null`: none). */
    private fun feedRr(meter: RestingHeartRateMeter, contact: (Long) -> SensorContact = { SensorContact.Detected }, rr: (Long) -> Int?) {
        var t = 1_000L
        while (t <= 180_000) {
            meter.add(RestingSample(t, 60, contact(t), listOfNotNull(rr(t / 1000))))
            t += 1_000
        }
    }

    @Test
    fun `RMSSD of the RR intervals of the last 2 minutes`() {
        val m = RestingHeartRateMeter(0)
        // The first minute is ignored; then alternating 1000 / 1040 ms: every successive difference is 40 ms.
        feedRr(m) { s -> if (s < 60) 700 else if (s % 2 == 0L) 1000 else 1040 }
        val r = m.result(180_000)
        assertTrue(r.valid)
        assertEquals(40.0, r.rmssdMs)
    }

    @Test
    fun `RMSSD leaves out artifacts and ectopic beats with their differences`() {
        val m = RestingHeartRateMeter(0)
        feedRr(m) { s ->
            when (s) {
                90L -> 2_500 // out of range
                120L -> 600 // 40 % shorter than the previous one: ectopic
                else -> if (s % 2 == 0L) 1000 else 1040
            }
        }
        assertEquals(40.0, m.result(180_000).rmssdMs)
    }

    @Test
    fun `no RMSSD without RR intervals, with too few, or from samples without contact`() {
        val none = RestingHeartRateMeter(0)
        feedRr(none) { null }
        assertTrue(none.result(180_000).valid)
        assertNull(none.result(180_000).rmssdMs)

        val few = RestingHeartRateMeter(0)
        // Only 50 intervals in the last 2 minutes (seconds 131-180).
        feedRr(few) { s -> if (s > 130) (if (s % 2 == 0L) 1000 else 1040) else null }
        assertNull(few.result(180_000).rmssdMs)

        val noContact = RestingHeartRateMeter(0)
        feedRr(noContact, contact = { t -> if (t > 100_000) SensorContact.NotDetected else SensorContact.Detected }) { s -> if (s % 2 == 0L) 1000 else 1040 }
        // Only seconds 61-100 count: 40 intervals.
        assertNull(noContact.result(180_000).rmssdMs)
    }

    @Test
    fun `session ends by itself after 3 minutes`() = runTest {
        val flow = MutableSharedFlow<RestingSample>(extraBufferCapacity = 16)
        val session = RestingHeartRateSession(backgroundScope, flow, now = { currentTime })
        session.start()
        delay(1)
        assertTrue(session.progress.value.running)
        repeat(181) {
            flow.emit(RestingSample(currentTime, 58))
            delay(1_000)
        }
        val result = checkNotNull(session.progress.value.result)
        assertTrue(result.valid, "$result")
        assertEquals(58, result.bpm)
        assertFalse(session.progress.value.running)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session cancelled early is invalid`() = runTest {
        val flow = MutableSharedFlow<RestingSample>(extraBufferCapacity = 16)
        val session = RestingHeartRateSession(backgroundScope, flow, now = { currentTime })
        session.start()
        repeat(30) {
            flow.emit(RestingSample(currentTime, 58))
            delay(1_000)
        }
        assertTrue(session.progress.value.remainingSeconds in 150..151)
        session.cancel()
        val result = session.progress.value.result!!
        assertTrue(RestingInvalidReason.TooShort in result.invalidReasons)
    }
}
