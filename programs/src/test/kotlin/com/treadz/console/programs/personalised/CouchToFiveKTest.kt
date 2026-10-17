package com.treadz.console.programs.personalised

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.personalised.CouchToFiveKDecision.Advance
import com.treadz.console.programs.personalised.CouchToFiveKDecision.ChosenStartWeek
import com.treadz.console.programs.personalised.CouchToFiveKDecision.FirstSession
import com.treadz.console.programs.personalised.CouchToFiveKDecision.PlanCompleted
import com.treadz.console.programs.personalised.CouchToFiveKDecision.RepeatHardEffort
import com.treadz.console.programs.personalised.CouchToFiveKDecision.RepeatManualReduction
import com.treadz.console.programs.personalised.CouchToFiveKDecision.StepBack
import com.treadz.console.programs.plan.ProgramResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CouchToFiveKTest {

    private fun next(history: List<GeneratedSessionRecord>, now: Long = NOW, startWeek: Int? = null) =
        CouchToFiveK.nextSession(history, now, startWeek, maxHeartRateBpm = 180)

    // ------------------------------------------------------------------ sessions

    @Test
    fun `the 27 classic sessions, from 8 x (1 + 1_5) to 30 min continuous`() {
        val expected = mapOf(
            1 to (480 to 720), 4 to (540 to 720), 7 to (540 to 540), 10 to (960 to 330), 13 to (900 to 360),
            14 to (960 to 300), 15 to (1200 to 0), 16 to (1080 to 360), 17 to (1200 to 180), 18 to (1320 to 0),
            19 to (1500 to 0), 22 to (1680 to 0), 25 to (1800 to 0), 27 to (1800 to 0),
        )
        expected.forEach { (s, v) -> assertEquals(v, CouchToFiveK.mainSeconds(s), "session $s") }
        for (s in 1..27) {
            val g = CouchToFiveK.generate(request(startWeek = CouchToFiveK.weekOf(s)))
            if (CouchToFiveK.dayOf(s) != 1) continue
            assertEquals(s, g.couchToFiveK!!.session)
            assertEquals(emptyList<Any>(), ProgramValidator.validate(g.program))
            val (run, walk) = CouchToFiveK.mainSeconds(s)
            assertEquals(600 + run + walk, g.totalDurationSeconds, "session $s")
            ProgramResolver.resolve(g.program)
        }
        assertThrows<IllegalArgumentException> { CouchToFiveK.mainSeconds(28) }
    }

    @Test
    fun `session 1 - walking warm-up and cool-down, 8 x (1 min jog + 1_5 min walk)`() {
        val g = CouchToFiveK.generate(request())
        val sel = g.couchToFiveK!!
        assertEquals(1, sel.session)
        assertEquals(FirstSession, sel.decision)
        assertEquals("Couch to 5K: week 1, day 1", g.program.name)
        val steps = g.program.steps
        assertEquals(3, steps.size)
        val walk = BlockTarget.Absolute(5.0, 1.0)
        assertEquals(Block(BlockPhase.WarmUp, BlockEnd.Duration(300), walk), steps[0])
        assertEquals(Block(BlockPhase.CoolDown, BlockEnd.Duration(300), walk), steps[2])
        val rep = steps[1] as Repeat
        assertEquals(8, rep.count)
        val run = rep.blocks[0]
        assertEquals(BlockEnd.Duration(60), run.end)
        val t = run.target as BlockTarget.HeartRate
        // Jog at 62.5 % of a MAS of 12: 7.5 km/h, never faster; down to walking only above 85 % HRmax.
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 85), t.zone)
        assertEquals(BlockTarget.Absolute(7.5, 1.0), t.start)
        assertEquals(7.5, t.maximum)
        assertEquals(5.0, t.minimum)
        assertEquals(Block(BlockPhase.Recovery, BlockEnd.Duration(90), walk), rep.blocks[1])
        val inputs = g.program.origin!!.inputs
        assertEquals("couch-to-5k", g.program.origin!!.generatorId)
        assertEquals("1", inputs["session"])
        assertEquals("firstSession", inputs["decision"])
        assertEquals("7.5", inputs["jogSpeedKmh"])
    }

    @Test
    fun `runs of 6 min or more are guided in zone 3`() {
        val g = CouchToFiveK.generate(request(history = listOf(c25kRecord(14, NOW - 2 * DAY - HOUR))))
        assertEquals(15, g.couchToFiveK!!.session)
        val run = g.program.steps[1] as Block
        assertEquals(BlockEnd.Duration(1200), run.end)
        val t = run.target as BlockTarget.HeartRate
        // VO2max 42 at 40 is "fair": low level, zone 3 = 55-75 % of the reserve; no resting heart rate: 70-83 % of the
        // maximum.
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 70, 83), t.zone)
        assertEquals(HeartRateAdjust.Speed, t.adjust)
        assertEquals(BlockTarget.Absolute(7.5, 1.0), t.start)
        // The slowest jog (walking 5.0 + 0.5): a run never turns into a walk.
        assertEquals(5.5, t.minimum)
        assertEquals(9.0, t.maximum)
        assertTrue(g.notices.any { it.code == GeneratorNoticeCode.RestingHeartRateMissing }, "${g.notices}")

        val rested = CouchToFiveK.generate(request(profile = measuredProfile(restingBpm = 55), history = listOf(c25kRecord(14, NOW - 2 * DAY - HOUR))))
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 55, 75), ((rested.program.steps[1] as Block).target as BlockTarget.HeartRate).zone)
        // Sessions without continuous runs do not need the resting heart rate.
        assertTrue(CouchToFiveK.generate(request()).notices.none { it.code == GeneratorNoticeCode.RestingHeartRateMissing })
    }

    @Test
    fun `jog speed - estimated values, floor, adjustment, and walking speed from the history`() {
        // 0.625 * 0.9 * 11.77 = 6.62
        assertEquals("6.6", CouchToFiveK.generate(request(profile = estimatedProfile())).program.origin!!.inputs["jogSpeedKmh"])
        val unfit = measuredProfile().copy(fitnessProfile = measuredFitness(vo2max = 28.0, mas = 8.0))
        assertEquals("6", CouchToFiveK.generate(request(profile = unfit)).program.origin!!.inputs["jogSpeedKmh"])
        assertEquals("8.3", CouchToFiveK.generate(request(jogAdjustment = 10)).program.origin!!.inputs["jogSpeedKmh"])
        assertThrows<IllegalArgumentException> { CouchToFiveK.generate(request(jogAdjustment = 25)) }

        val history = listOf(c25kRecord(1, NOW - 3 * DAY, walkFinalSpeed = 5.6))
        val g = CouchToFiveK.generate(request(history = history))
        assertEquals(5.6, g.walkingSpeedKmh)
        assertEquals(BlockTarget.Absolute(5.6, 1.0), (g.program.steps[0] as Block).target)
        // The walking speed asked wins over the history.
        assertEquals(4.8, CouchToFiveK.generate(request(history = history, walkingSpeedKmh = 4.8)).walkingSpeedKmh)
    }

    @Test
    fun `continuous runs start where the heart rate settled in the last one`() {
        val record = sessionRecord(CouchToFiveK.ID, mapOf("session" to "18", "mode" to "run"), NOW - 3 * DAY, listOf(
            blockRecord(0, "0", BlockPhase.WarmUp, speed = 5.0),
            blockRecord(1, "1", BlockPhase.Work, endHr = 140.0, heartRate = hrSummary(1, settled = 7.1, end = 140.0)),
            blockRecord(2, "2", BlockPhase.CoolDown, speed = 5.0),
        ))
        val g = CouchToFiveK.generate(request(history = listOf(record)))
        assertEquals(19, g.couchToFiveK!!.session)
        val t = (g.program.steps[1] as Block).target as BlockTarget.HeartRate
        assertEquals(BlockTarget.Absolute(7.1, 1.0), t.start)
        assertTrue(g.notices.contains(GeneratorNotice(GeneratorNoticeCode.StartedFromHistory, null, 7.1)))
        // Short runs keep the jog speed.
        val record2 = record.copy(origin = record.origin.copy(inputs = mapOf("session" to "3", "mode" to "run")))
        val short = CouchToFiveK.generate(request(history = listOf(record2)))
        val st = ((short.program.steps[1] as Repeat).blocks[0].target as BlockTarget.HeartRate)
        assertEquals(BlockTarget.Absolute(7.5, 1.0), st.start)
        // A between-repetitions settled value is not used for continuous runs.
        val between = record.copy(blocks = listOf(blockRecord(1, "1.0", BlockPhase.Work, heartRate = hrSummary(1, mode = HeartRateControlMode.BetweenRepetitions, settled = 6.8))))
        val g3 = CouchToFiveK.generate(request(history = listOf(between)))
        assertEquals(BlockTarget.Absolute(7.5, 1.0), ((g3.program.steps[1] as Block).target as BlockTarget.HeartRate).start)
    }

    // ------------------------------------------------------------------ progression rules

    @Test
    fun `no history starts at session 1, a chosen week at its first session`() {
        assertEquals(1, next(emptyList()).session)
        assertEquals(FirstSession, next(emptyList()).decision)
        val chosen = next(listOf(c25kRecord(10, NOW - 3 * DAY)), startWeek = 3)
        assertEquals(7, chosen.session)
        assertEquals(ChosenStartWeek, chosen.decision)
        assertThrows<IllegalArgumentException> { next(emptyList(), startWeek = 10) }
    }

    @Test
    fun `completed without reductions and below 85 percent advances`() {
        val s = next(listOf(c25kRecord(4, NOW - 2 * DAY - HOUR, endHr = 152.0)))
        assertEquals(5, s.session)
        assertEquals(Advance, s.decision)
        assertEquals(4, s.previousSession)
        assertEquals(2, s.week)
        assertEquals(2, s.day)
        assertFalse(s.recentSessionWarning)
        assertEquals(0, s.weeksBack)
    }

    @Test
    fun `a heart rate at or above 85 percent at the end of a run repeats the session`() {
        val s = next(listOf(c25kRecord(4, NOW - 3 * DAY, endHr = 153.0)))
        assertEquals(4, s.session)
        assertEquals(RepeatHardEffort, s.decision)
        // The record's own maximum is used when it has one.
        val own = c25kRecord(4, NOW - 3 * DAY, endHr = 153.0).copy(maxHeartRateBpm = 190)
        assertEquals(Advance, next(listOf(own)).decision)
    }

    @Test
    fun `a continuous run ending inside its zone is not too hard, even above 85 percent`() {
        fun continuous(end: Double, zoneHigh: Int) = sessionRecord(CouchToFiveK.ID, mapOf("session" to "15", "mode" to "run"), NOW - 3 * DAY, listOf(
            blockRecord(0, "0", BlockPhase.WarmUp, speed = 5.0),
            blockRecord(1, "1", BlockPhase.Work, endHr = end, heartRate = hrSummary(1, end = end, zoneHighBpm = zoneHigh)),
            blockRecord(2, "2", BlockPhase.CoolDown, speed = 5.0),
        ))
        // 155 bpm >= 85 % of 180 (153), inside a 143-158 zone: advance.
        assertEquals(Advance, next(listOf(continuous(155.0, 158))).decision)
        // Above the zone: too hard.
        assertEquals(RepeatHardEffort, next(listOf(continuous(155.0, 150))).decision)
    }

    @Test
    fun `a heart-rate safety reduction repeats the session`() {
        assertEquals(RepeatHardEffort, next(listOf(c25kRecord(6, NOW - 3 * DAY, safetyReductions = 1))).decision)
    }

    @Test
    fun `a manual speed reduction in a run repeats the session`() {
        val s = next(listOf(c25kRecord(6, NOW - 3 * DAY, reductions = 1)))
        assertEquals(6, s.session)
        assertEquals(RepeatManualReduction, s.decision)
    }

    @Test
    fun `runs without heart rate do not block advancing`() {
        assertEquals(Advance, next(listOf(c25kRecord(6, NOW - 3 * DAY, endHr = null))).decision)
    }

    @Test
    fun `not completed steps one session back, never below 1`() {
        val s = next(listOf(c25kRecord(6, NOW - 3 * DAY, runsCompleted = 2)))
        assertEquals(5, s.session)
        assertEquals(StepBack, s.decision)
        assertEquals(1, next(listOf(c25kRecord(1, NOW - 3 * DAY, runsCompleted = 0, outcome = SessionOutcome.Abandoned))).session)
    }

    @Test
    fun `a stop during the cool-down still counts as completed`() {
        val r = c25kRecord(6, NOW - 3 * DAY, outcome = SessionOutcome.Stopped)
        assertTrue(r.workCompleted)
        assertEquals(Advance, next(listOf(r)).decision)
    }

    @Test
    fun `more than 10 days off goes one week back, more than 21 two weeks`() {
        val ten = next(listOf(c25kRecord(10, NOW - 10 * DAY - 30 * 60_000L)))
        assertEquals(0, ten.weeksBack)
        assertEquals(11, ten.session)
        val eleven = next(listOf(c25kRecord(10, NOW - 11 * DAY)))
        assertEquals(1, eleven.weeksBack)
        assertEquals(8, eleven.session)
        assertEquals(Advance, eleven.decision)
        val month = next(listOf(c25kRecord(10, NOW - 30 * DAY)))
        assertEquals(2, month.weeksBack)
        assertEquals(5, month.session)
        assertEquals(1, next(listOf(c25kRecord(2, NOW - 30 * DAY))).session)
        val g = CouchToFiveK.generate(request(history = listOf(c25kRecord(10, NOW - 30 * DAY))))
        assertTrue(g.notices.any { it.code == GeneratorNoticeCode.LongBreak })
    }

    @Test
    fun `less than 48 h since the last session is only a warning`() {
        val s = next(listOf(c25kRecord(3, NOW - 30 * HOUR)))
        assertTrue(s.recentSessionWarning)
        assertEquals(4, s.session)
        val g = CouchToFiveK.generate(request(history = listOf(c25kRecord(3, NOW - 30 * HOUR))))
        val notice = g.notices.single { it.code == GeneratorNoticeCode.RecentSession }
        assertEquals(29.5, notice.value)
    }

    @Test
    fun `after session 27 the plan is completed and offered again`() {
        val s = next(listOf(c25kRecord(27, NOW - 3 * DAY)))
        assertEquals(27, s.session)
        assertEquals(PlanCompleted, s.decision)
        assertTrue(CouchToFiveK.generate(request(history = listOf(c25kRecord(27, NOW - 3 * DAY)))).notices.any { it.code == GeneratorNoticeCode.PlanCompleted })
    }

    @Test
    fun `the latest record decides, other generators and sessions without runs are ignored`() {
        val history = listOf(
            c25kRecord(8, NOW - 3 * DAY),
            c25kRecord(5, NOW - 10 * DAY),
            sessionRecord("zone2", mapOf("session" to "20"), NOW - DAY, emptyList()),
            c25kRecord(9, NOW - DAY, runs = 0),
        )
        val s = next(history)
        assertEquals(9, s.session)
        assertEquals(8, s.previousSession)
        assertNull(next(listOf(c25kRecord(9, NOW - DAY, runs = 0))).previousSession)
    }

    @Test
    fun `the suggested start week follows the VO2max category`() {
        val profile = measuredProfile()
        fun week(vo2: Double) = CouchToFiveK.suggestedStartWeek(IntensityEstimator.references(profile.copy(fitnessProfile = measuredFitness(vo2max = vo2))), profile)
        // Men 40-49: Fair 38.5, Good 42.4, Excellent 46.4, Superior 52.5
        assertEquals(1, week(30.0))
        assertEquals(1, week(40.0))
        assertEquals(2, week(43.0))
        assertEquals(3, week(47.0))
        assertEquals(4, week(53.0))
        assertEquals(1, CouchToFiveK.suggestedStartWeek(IntensityEstimator.references(estimatedProfile().copy(sex = Sex.Female, selfRatedFitness = null)), estimatedProfile().copy(sex = Sex.Female)))
        assertEquals(2, CouchToFiveK.generate(request(profile = measuredProfile().copy(fitnessProfile = measuredFitness(vo2max = 43.0)))).couchToFiveK!!.suggestedStartWeek)
    }
}
