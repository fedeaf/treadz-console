package com.treadz.console.programs.heartrate

import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The judgement of strap readings against their RR, on synthetic data: a strap that beats every [rr] ms and reports
 * [bpm], read every 500 ms, one RR per notification.
 */
class StrapWindowJudgeTest {

    /** Feeds [judge] from [fromMs] to [toMs]: readings every 500 ms and one beat per notification, RR given by [rr]. */
    private fun feed(judge: StrapWindowJudge, fromMs: Long, toMs: Long, bpm: Int, firstBeat: Int = 0, rr: (Int) -> Int) {
        var t = fromMs
        var next = fromMs
        var i = firstBeat
        while (t < toMs) {
            t += 500
            judge.addReading(t, bpm)
            while (next < t) {
                val ms = rr(i++)
                next += ms
                judge.addRr(next, listOf(ms))
            }
        }
    }

    @Test
    fun `readings the RR confirm are reliable`() {
        val judge = StrapWindowJudge()
        feed(judge, 0, 60_000, 127) { 472 }
        val j = judge.judge(50_000, 60_000)
        assertEquals(StrapWindowVerdict.Reliable, j.verdict)
        assertFalse(j.isDoubtful)
        assertEquals(0.0, j.artifactFraction)
        assertEquals(127.1, j.rrHeartRateBpm!!, 0.1)
        assertEquals(127.0, j.strapMeanBpm!!, 1e-9)
        assertTrue(j.beats in 20..22, "${j.beats}")
        assertTrue(j.reasons.isEmpty())
    }

    @Test
    fun `a window with too many artifacts is doubtful`() {
        val judge = StrapWindowJudge()
        feed(judge, 0, 50_000, 127) { 472 }
        // 12 s of false readings (137) with beats of 250-390 ms, which are not a heart rate.
        val junk = intArrayOf(250, 390, 300, 380, 260, 350)
        feed(judge, 50_000, 62_000, 137) { junk[it % junk.size] }
        val j = judge.judge(52_000, 62_000)
        assertEquals(StrapWindowVerdict.Doubtful, j.verdict)
        assertTrue(StrapDoubt.TooManyArtifacts in j.reasons, "$j")
        assertTrue(j.artifactFraction!! > 0.10, "$j")
    }

    @Test
    fun `a heart rate that disagrees with the RR by more than 4 bpm is doubtful, up to 4 it is not`() {
        val agree = StrapWindowJudge()
        feed(agree, 0, 30_000, 131) { 472 } // RR say 127.1
        assertEquals(StrapWindowVerdict.Reliable, agree.judge(20_000, 30_000).verdict, "3.9 bpm apart")

        val apart = StrapWindowJudge()
        feed(apart, 0, 30_000, 132) { 472 } // 4.9 apart, clean beats
        val j = apart.judge(20_000, 30_000)
        assertEquals(StrapWindowVerdict.Doubtful, j.verdict)
        assertEquals(setOf(StrapDoubt.RateMismatch), j.reasons)
        assertEquals(0.0, j.artifactFraction)
    }

    @Test
    fun `the share of artifacts is compared with 10 percent - a few bad beats pass, more do not`() {
        // 150 bpm (400 ms): about 24 beats in the 10-s window. A doubled interval (a missed beat) is an artifact.
        fun judged(bad: Int): StrapWindowJudgement {
            val judge = StrapWindowJudge()
            val badAt = listOf(104, 108, 112, 116).take(bad).toSet()
            feed(judge, 0, 60_000, 150) { i -> if (i in badAt) 800 else 400 }
            // The window (40 s, 50 s] holds beats 100-124 or so: the bad ones are inside.
            return judge.judge(40_000, 50_000)
        }
        val none = judged(0)
        assertEquals(StrapWindowVerdict.Reliable, none.verdict)
        val two = judged(2)
        val three = judged(3)
        val four = judged(4)
        assertTrue(two.artifactFraction!! <= 0.10, "$two")
        assertEquals(StrapWindowVerdict.Reliable, two.verdict)
        assertTrue(four.artifactFraction!! > 0.10, "$four")
        assertEquals(StrapWindowVerdict.Doubtful, four.verdict)
        assertTrue(three.artifactFraction!! > 0.10, "$three")
    }

    @Test
    fun `too few beats, or no RR at all, is not judged`() {
        val sparse = StrapWindowJudge()
        feed(sparse, 0, 10_000, 127) { 472 }
        // A window of 2 s holds 4 beats: fewer than the 6 needed.
        val j = sparse.judge(8_000, 10_000)
        assertEquals(StrapWindowVerdict.NotJudged, j.verdict)
        assertFalse(j.isDoubtful)
        assertTrue(j.beats < 6)

        // Console heart rate: readings only.
        val console = StrapWindowJudge()
        for (t in 500..30_000 step 500) console.addReading(t.toLong(), 137)
        val c = console.judge(20_000, 30_000)
        assertEquals(StrapWindowVerdict.NotJudged, c.verdict)
        assertEquals(0, c.beats)

        // Switched off.
        val off = StrapWindowJudge(StrapWindowConfig(enabled = false))
        feed(off, 0, 30_000, 140) { 472 }
        assertEquals(StrapWindowVerdict.NotJudged, off.judge(20_000, 30_000).verdict)

        // Nothing at all.
        assertEquals(StrapWindowVerdict.NotJudged, StrapWindowJudge().judge(0, 10_000).verdict)
    }

    @Test
    fun `a window with RR but no readings is judged by its artifacts only`() {
        val judge = StrapWindowJudge()
        var t = 0L
        repeat(60) { t += 472; judge.addRr(t, listOf(472)) }
        val j = judge.judge(10_000, 20_000)
        assertEquals(StrapWindowVerdict.Reliable, j.verdict)
        assertNull(j.strapMeanBpm)
        assertNotNull(j.rrHeartRateBpm)
    }

    @Test
    fun `the beats of a notification are placed back from its arrival, as the RR series does`() {
        val judge = StrapWindowJudge()
        // One notification at 10 000 with two beats: they end at 10 000 and 9 528. Nothing before.
        judge.addRr(10_000, listOf(472, 472))
        assertEquals(2, judge.beatsHeld)
        val inside = judge.judge(9_000, 10_000)
        assertEquals(2, inside.beats)
        assertEquals(1, judge.judge(9_600, 10_000).beats, "only the second beat is after 9 600")
    }

    @Test
    fun `trimBefore bounds what is held and keeps the later windows judgeable`() {
        val judge = StrapWindowJudge()
        feed(judge, 0, 120_000, 127) { 472 }
        val before = judge.readingsHeld
        judge.trimBefore(60_000)
        assertTrue(judge.readingsHeld < before / 2 + 2, "${judge.readingsHeld} of $before")
        assertTrue(judge.beatsHeld < 135)
        assertEquals(StrapWindowVerdict.Reliable, judge.judge(110_000, 120_000).verdict)
        feed(judge, 120_000, 130_000, 127) { 472 }
        assertEquals(StrapWindowVerdict.Reliable, judge.judge(120_000, 130_000).verdict)
        judge.reset()
        assertEquals(0, judge.readingsHeld)
        assertEquals(0, judge.beatsHeld)
    }

    @Test
    fun `from stored samples - only the samples of a strap connected to the app count`() {
        fun sample(t: Long, source: SampleHeartRateSource?, rr: List<Int>) =
            WorkoutSample(t, 6.0, 1.0, 130, source, rr)
        // One sample per second with two beats of 460 ms (130 bpm), a strap: judged and reliable.
        val strap = (1..30).map { sample(it * 1_000L, SampleHeartRateSource.Strap, listOf(461, 461)) }
        val judged = StrapWindowJudge.fromSamples(strap).judge(20_000, 30_000)
        assertEquals(StrapWindowVerdict.Reliable, judged.verdict)
        assertEquals(130.0, judged.strapMeanBpm!!, 1e-9)

        // The same RR labelled as the console's or unknown: ignored, nothing to judge.
        for (source in listOf(SampleHeartRateSource.Console, SampleHeartRateSource.Unknown, null)) {
            val other = strap.map { it.copy(heartRateSource = source) }
            assertEquals(StrapWindowVerdict.NotJudged, StrapWindowJudge.fromSamples(other).judge(20_000, 30_000).verdict, "$source")
        }

        // A strap whose readings run 10 bpm above their RR (the stored artifact of the audit): doubtful. Any order.
        val false137 = strap.map { it.copy(heartRateBpm = 140) }.reversed()
        val d = StrapWindowJudge.fromSamples(false137).judge(20_000, 30_000)
        assertTrue(d.isDoubtful)
        assertEquals(setOf(StrapDoubt.RateMismatch), d.reasons)

        // Samples without a heart rate or without RR do not break it.
        val holes = strap.mapIndexed { i, s -> if (i % 3 == 0) s.copy(heartRateBpm = null, rrIntervalsMs = emptyList()) else s }
        assertNotNull(StrapWindowJudge.fromSamples(holes).judge(20_000, 30_000).strapMeanBpm)
    }
}
