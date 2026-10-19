package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.samples.RrInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RealTimeAlpha1Test {

    private val start = 5_000_000L

    /**
     * Feeds [beats] one strap notification per second (all the beats that ended in that second, like a real strap), up
     * to [untilMillis], which must be a whole second.
     */
    private fun RealTimeAlpha1.feedUntil(beats: List<RrInterval>, untilMillis: Long, from: Int = 0): Int {
        var i = from
        var second = (beats.getOrNull(i)?.timeMillis ?: return i) / 1000 + 1
        while (i < beats.size && beats[i].timeMillis <= untilMillis) {
            val group = ArrayList<Int>()
            while (i < beats.size && beats[i].timeMillis <= second * 1000 && beats[i].timeMillis <= untilMillis) group += beats[i++].rrMs
            if (group.isNotEmpty()) append(second * 1000, group)
            second++
        }
        return i
    }

    @Test
    fun `recovers the alpha1 of stationary series`() {
        for ((alpha, seed) in listOf(1.1 to 11L, 0.75 to 12L, 0.5 to 13L)) {
            val beats = SyntheticRr.ramp(start, 400, 120.0, 120.0, alpha, alpha, seed = seed)
            val estimator = RealTimeAlpha1()
            val at = start + 300_000L
            estimator.feedUntil(beats, at)
            val e = estimator.evaluate(at)
            assertTrue(e.valid, "alpha $alpha: $e")
            assertEquals(alpha, checkNotNull(e.alpha1), 0.1, "alpha $alpha")
            assertEquals(at - 120_000L, e.windowStartMillis)
            assertTrue(e.beats in 220..260, "${e.beats}")
            assertEquals(0.0, e.artifactFraction)
            assertEquals(120.0, checkNotNull(e.heartRateBpm), 8.0)
        }
    }

    @Test
    fun `gives exactly what the window analysis gives for the same beats`() {
        val beats = SyntheticRr.ramp(start, 400, 130.0, 130.0, 0.9, 0.9, seed = 21)
        val estimator = RealTimeAlpha1()
        val at = start + 250_000L
        estimator.feedUntil(beats, at)
        val e = estimator.evaluate(at)
        assertNotNull(e.alpha1)
        val reference = Alpha1Windows.compute(beats, emptyList(), at - 120_000L, at).single()
        // A notification stamps its beats back from its arrival, so beat times differ by under a second from the real
        // ones: the beat counts agree within a few beats and α1 within the noise of the estimate.
        assertEquals(checkNotNull(reference.alpha1), checkNotNull(e.alpha1), 0.1)
        assertTrue(abs(reference.beats - e.beats) <= 3, "${reference.beats} vs ${e.beats}")
    }

    @Test
    fun `beats given one by one at their own time are the analysis windows exactly`() {
        val beats = SyntheticRr.ramp(start, 400, 130.0, 130.0, 0.9, 0.9, seed = 21)
        val estimator = RealTimeAlpha1()
        val at = start + 250_000L
        for (b in beats) if (b.timeMillis <= at) estimator.append(b.timeMillis, listOf(b.rrMs))
        val e = estimator.evaluate(at)
        val reference = Alpha1Windows.compute(beats, emptyList(), at - 120_000L, at).single()
        assertEquals(reference.alpha1, e.alpha1)
        assertEquals(reference.beats, e.beats)
        assertEquals(reference.artifactFraction, e.artifactFraction)
        assertEquals(reference.heartRateBpm, e.heartRateBpm)
    }

    @Test
    fun `a dropout in the middle of the window makes it not valid`() {
        // 140 bpm with a 40-s hole between 3:00 and 3:40: at 4:20 the window still has well over 100 beats.
        val beats = SyntheticRr.ramp(start, 400, 140.0, 140.0, 0.9, 0.9, seed = 31)
            .filterNot { it.timeMillis in (start + 180_000L)..(start + 220_000L) }
        val estimator = RealTimeAlpha1()
        val at = start + 260_000L
        for (b in beats) if (b.timeMillis <= at) estimator.append(b.timeMillis, listOf(b.rrMs))
        val e = estimator.evaluate(at)
        assertTrue(e.beats >= 100, "${e.beats}")
        assertFalse(e.valid)
        assertNull(e.alpha1)
        // Once the hole has left the window, the evaluation is valid again.
        val later = start + 360_000L
        for (b in beats) if (b.timeMillis in (at + 1)..later) estimator.append(b.timeMillis, listOf(b.rrMs))
        assertTrue(estimator.evaluate(later).valid)
    }

    @Test
    fun `polls every 10 seconds`() {
        val beats = SyntheticRr.ramp(start, 300, 120.0, 120.0, 1.0, 1.0, seed = 5)
        val estimator = RealTimeAlpha1()
        var fed = 0
        val evaluations = ArrayList<Alpha1Evaluation>()
        var t = start
        while (t <= start + 200_000L) {
            fed = estimator.feedUntil(beats, t, fed)
            for (quarter in 0 until 4) estimator.poll(t + quarter * 250L)?.let { evaluations += it }
            t += 1_000
        }
        // 200 s polled every 250 ms: an evaluation at the first poll and then every 10 s.
        assertEquals(21, evaluations.size)
        assertEquals(List(21) { start + it * 10_000L }, evaluations.map { it.atMillis })
        // The first beats arrive after the start; the window counts as covered from about 105 s on (not before: a window of 90 s
        // has 180 beats at 120 bpm but is not a 120-s window).
        assertTrue(evaluations.takeWhile { it.atMillis < start + 100_000L }.none { it.valid })
        assertTrue(evaluations.filter { it.atMillis >= start + 130_000L }.all { it.valid }, evaluations.joinToString("\n"))
        assertTrue(evaluations.first().alpha1 == null)
    }

    @Test
    fun `a late poll evaluates once and does not catch up`() {
        val estimator = RealTimeAlpha1()
        assertNotNull(estimator.poll(start))
        assertNull(estimator.poll(start + 9_999L))
        assertNotNull(estimator.poll(start + 60_000L))
        assertNull(estimator.poll(start + 69_999L))
        assertNotNull(estimator.poll(start + 70_000L))
    }

    @Test
    fun `artifacts - tolerated up to 5 percent, reported, invalid above`() {
        val clean = SyntheticRr.ramp(start, 400, 120.0, 120.0, 0.9, 0.9, seed = 31)
        val at = start + 300_000L
        // Every n-th interval is doubled (a missed beat): 1/n of the beats are artifacts.
        fun withArtifacts(every: Int) = clean.mapIndexed { i, b -> if (i % every == 7) RrInterval(b.timeMillis, b.rrMs * 2) else b }
        fun evaluation(beats: List<RrInterval>): Alpha1Evaluation {
            val estimator = RealTimeAlpha1()
            for (b in beats) if (b.timeMillis <= at) estimator.append(b.timeMillis, listOf(b.rrMs))
            return estimator.evaluate(at)
        }
        val few = evaluation(withArtifacts(40))
        assertTrue(few.valid, "$few")
        assertTrue(few.artifactFraction in 0.015..0.035, "${few.artifactFraction}")
        val some = evaluation(withArtifacts(25))
        assertTrue(some.valid, "$some")
        assertTrue(some.artifactFraction in 0.03..0.05, "${some.artifactFraction}")
        val many = evaluation(withArtifacts(14))
        assertFalse(many.valid, "$many")
        assertNull(many.alpha1)
        assertTrue(many.artifactFraction > 0.05, "${many.artifactFraction}")
    }

    @Test
    fun `missing RR - not valid after the strap stops delivering`() {
        val beats = SyntheticRr.ramp(start, 400, 120.0, 120.0, 1.0, 1.0, seed = 7)
        val estimator = RealTimeAlpha1()
        val last = start + 200_000L
        for (b in beats) if (b.timeMillis <= last) estimator.append(b.timeMillis, listOf(b.rrMs))
        assertTrue(estimator.evaluate(last).valid)
        assertTrue(estimator.evaluate(last + 10_000L).valid, "a gap of 10 s is tolerated")
        val stale = estimator.evaluate(last + 16_000L)
        assertFalse(stale.valid)
        assertNull(stale.alpha1)
        assertTrue(stale.beats >= 100, "the window still holds beats, but they are stale: $stale")
        // Long after: no beats left in the window.
        val gone = estimator.evaluate(last + 200_000L)
        assertFalse(gone.valid)
        assertEquals(0, gone.beats)
        assertEquals(1.0, gone.artifactFraction)
        assertNull(gone.heartRateBpm)
        // The strap comes back: valid again once the window holds enough beats.
        val resumed = SyntheticRr.ramp(last + 200_000L, 200, 120.0, 120.0, 1.0, 1.0, seed = 8)
        val now = last + 200_000L + 150_000L
        for (b in resumed) if (b.timeMillis <= now) estimator.append(b.timeMillis, listOf(b.rrMs))
        assertTrue(estimator.evaluate(now).valid)
        // ... but not while its first beats are less than 2 min old.
        assertFalse(estimator.evaluate(last + 200_000L + 60_000L).valid)
    }

    @Test
    fun `the buffer is bounded and reset forgets everything`() {
        val beats = SyntheticRr.ramp(start, 1_200, 150.0, 150.0, 0.8, 0.8, seed = 9)
        val estimator = RealTimeAlpha1()
        var maxHeld = 0
        for (b in beats) {
            estimator.append(b.timeMillis, listOf(b.rrMs))
            maxHeld = maxOf(maxHeld, estimator.bufferedBeats)
        }
        // 130 s of beats at 150 bpm is about 325.
        assertTrue(maxHeld <= 340, "$maxHeld")
        assertTrue(estimator.evaluate(beats.last().timeMillis).valid)
        estimator.reset()
        assertEquals(0, estimator.bufferedBeats)
        assertFalse(estimator.evaluate(beats.last().timeMillis).valid)
        assertNotNull(estimator.poll(beats.last().timeMillis))
        estimator.reset()
        assertNotNull(estimator.poll(beats.last().timeMillis + 1))
    }

    @Test
    fun `an empty notification changes nothing`() {
        val estimator = RealTimeAlpha1()
        estimator.append(start, emptyList())
        assertEquals(0, estimator.bufferedBeats)
    }
}
