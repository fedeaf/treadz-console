package com.treadz.console.programs.personalised

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.programs.fitness.ProfileZones
import com.treadz.console.programs.fitness.ThresholdEstimate
import com.treadz.console.programs.fitness.ThresholdProfile
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateZoneBpm
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolutionEnvironment
import com.treadz.console.programs.plan.StartParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Findings 3 and 5 of the audit of the zone-2 workout of 2026-10-24: a settled value is reused only from a session with the
 * same zone, and the generated blocks name the zone of the user's own zones so the run uses its exact bpm. Synthetic data:
 * maximum 185, resting 58, VT1 131 and VT2 149 (zone 2 = 121-131, the zone the whole-percentage bounds 50-57 % round to
 * 122-130).
 */
class ProfileZoneAndSettledTest {

    private fun hr(step: Any?): BlockTarget.HeartRate = (step as Block).target as BlockTarget.HeartRate

    private val profile = measuredProfile().copy(
        heartRate = HeartRateProfile(185, 58),
        fitnessProfile = measuredFitness().copy(
            thresholds = ThresholdProfile(
                vt1 = ThresholdEstimate(131, 29.0, ThresholdQuality.High, emptyList()),
                vt2 = ThresholdEstimate(149, 38.0, ThresholdQuality.High, emptyList()),
                history = emptyList(),
            ),
        ),
    )

    /** The heart-rate profile of a run with the user's zones in force, as the app builds it. */
    private val running = run {
        val base = profile.heartRate
        val zones = ProfileZones.compute(profile.fitnessProfile, base, profile.selfRatedFitness, profile.ageYears, profile.sex)
        base.copy(vt1Bpm = zones.vt1Bpm, zones = zones)
    }

    private fun zone2(history: List<GeneratedSessionRecord> = emptyList(), speed: Double = 5.6) =
        ProgramGenerators.generate("zone2", request(profile, mode = ExerciseMode.Walk, durationMinutes = 35, walkingSpeedKmh = speed, history = history))

    // ------------------------------------------------------------------ finding 5

    @Test
    fun `the top of zone 2 is VT1 itself and the floor is 10 bpm under it`() {
        val z = checkNotNull(running.zones)
        assertEquals(131, z.zone(2).highBpm)
        assertEquals(121, z.zone(2).lowBpm)
        assertEquals(131, z.zone(3).lowBpm)
    }

    @Test
    fun `a generated zone 2 block names zone 2 of the user's zones and runs with its exact bpm`() {
        val t = hr(zone2().program.steps[1])
        // The whole percentages stay as the fallback...
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 50, 57, profileZone = 2), t.zone)
        assertEquals(HeartRateZoneBpm(122, 130, HeartRateBasis.HeartRateReserve), HeartRateZones.toBpm(t.zone, profile.heartRate), "an app without the user's zones: 122-130 as before")
        // ...and the run, with the user's zones, uses 121-131: the top is VT1, one bpm more than the rounded percentage gave.
        assertEquals(HeartRateZoneBpm(121, 131, HeartRateBasis.MaxHeartRate, fromProfile = true), HeartRateZones.toBpm(t.zone, running))
    }

    @Test
    fun `tempo and Couch to 5K name their zones too, the 4x4 and the 30-30 keep theirs`() {
        val tempo = ProgramGenerators.generate("tempo", request(profile, mode = ExerciseMode.Walk, durationMinutes = 45, walkingSpeedKmh = 5.6))
        assertEquals(4, hr((tempo.program.steps[1] as Repeat).blocks[0]).zone.profileZone)
        val c25k = CouchToFiveK.generate(request(profile, history = listOf(c25kRecord(14, NOW - 2 * DAY - HOUR))))
        assertEquals(3, hr(c25k.program.steps[1]).zone.profileZone)
        val x4 = ProgramGenerators.generate("norwegian-4x4", request(profile, mode = ExerciseMode.Walk, walkingSpeedKmh = 5.6))
        assertNull(hr((x4.program.steps[1] as Repeat).blocks[0]).zone.profileZone)
    }

    @Test
    fun `without thresholds the level table gives the same bpm either way, and the block still names the zone`() {
        val table = measuredProfile(restingBpm = 58).copy(heartRate = HeartRateProfile(185, 58))
        val t = hr(ProgramGenerators.generate("zone2", request(table, mode = ExerciseMode.Walk, walkingSpeedKmh = 5.6)).program.steps[1])
        assertEquals(2, t.zone.profileZone)
        val zones = ProfileZones.compute(table.fitnessProfile, table.heartRate, table.selfRatedFitness, table.ageYears, table.sex)
        val exact = HeartRateZones.toBpm(t.zone, table.heartRate.copy(zones = zones))
        val fallback = HeartRateZones.toBpm(t.zone, table.heartRate)
        assertEquals(fallback.lowBpm, exact.lowBpm)
        assertEquals(fallback.highBpm, exact.highBpm)
    }

    @Test
    fun `the profile zone and the fallback percentages are kept by the program document and by the plan copy`() {
        val g = zone2()
        val text = ProgramJson.encode(g.program)
        assertTrue(text.contains("\"profileZone\": 2") && text.contains("\"lowPercent\": 50") && text.contains("\"highPercent\": 57"), text)
        assertEquals(g.program, ProgramJson.decode(text))
        assertEquals(3, g.program.origin!!.generatorVersion)

        val plan = ProgramResolver.resolve(g.program, StartParameters(), ResolutionEnvironment())
        val guidance = checkNotNull(plan.blocks.single { it.phase == BlockPhase.Work }.heartRate)
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 50, 57, profileZone = 2), guidance.zone)
        val copy = PlanJson.decode(PlanJson.encode(plan))
        assertEquals(plan, copy)
        assertEquals(2, checkNotNull(copy.blocks.single { it.phase == BlockPhase.Work }.heartRate).zone.profileZone)
    }

    @Test
    fun `the generators that changed have a new version`() {
        assertEquals(listOf("zone2" to 3, "tempo" to 3, "norwegian-4x4" to 3, "intervals-30-30" to 1, "couch-to-5k" to 3), ProgramGenerators.all.map { it.descriptor.id to it.descriptor.version })
    }

    // ------------------------------------------------------------------ finding 3

    private fun walkRecord(daysAgo: Int, low: Int, high: Int, settled: Double? = 7.9, speed: Double = 5.6) =
        sessionRecord(
            "zone2", mapOf("mode" to "walk"), NOW - daysAgo * DAY,
            listOf(blockRecord(1, "1", BlockPhase.Work, speed = speed, heartRate = hrSummary(1, adjust = HeartRateAdjust.Incline, settled = settled, zoneLowBpm = low, zoneHighBpm = high))),
        )

    private fun start(g: GeneratedProgram) = (hr(g.program.steps[1]).start as BlockTarget.Absolute).inclinePercent

    @Test
    fun `a settled value from the same zone is used - the zone of the audit's workout, 122-130, against 121-131`() {
        val g = zone2(listOf(walkRecord(1, 122, 130)))
        assertEquals(8.0, start(g))
        assertEquals("8", g.program.origin!!.inputs["startFromHistory.1"])
        assertTrue(g.notices.any { it.code == GeneratorNoticeCode.StartedFromHistory && it.value == 8.0 })
    }

    @Test
    fun `a settled value from another zone is ignored and the start comes from VT1 as without history`() {
        val none = zone2()
        assertEquals(9.0, start(none), "from the VT1 (VO2 29.0 at 5.6 km/h)")
        // The 17-10 session: zone 132-144, settled 9.9 (here 7.9 to tell it from the start).
        val other = zone2(listOf(walkRecord(7, 132, 144)))
        assertEquals(start(none), start(other))
        assertTrue(other.notices.none { it.code == GeneratorNoticeCode.StartedFromHistory })
        assertNull(other.program.origin!!.inputs["startFromHistory.1"])
        assertEquals("1", other.program.origin!!.inputs["historySessions"], "the session is in the history, just not used")
    }

    @Test
    fun `each limit must be within 2 bpm - 2 is accepted, 3 is not, either limit`() {
        // The new zone is 121-131.
        for ((low, high) in listOf(119 to 129, 123 to 133, 119 to 133, 121 to 131)) {
            assertEquals(8.0, start(zone2(listOf(walkRecord(1, low, high)))), "$low-$high")
        }
        for ((low, high) in listOf(118 to 131, 124 to 131, 121 to 128, 121 to 134, 112 to 140)) {
            assertEquals(9.0, start(zone2(listOf(walkRecord(1, low, high)))), "$low-$high")
        }
    }

    @Test
    fun `an older session of the same zone is used when the newest one is of another`() {
        val newest = walkRecord(1, 132, 144, settled = 9.0)
        val older = walkRecord(5, 122, 130, settled = 7.0)
        assertEquals(7.0, start(zone2(listOf(older, newest))))
        // Without a settled value the session is skipped as before.
        assertEquals(7.0, start(zone2(listOf(older, walkRecord(1, 121, 131, settled = null)))))
    }

    @Test
    fun `the zone is the one of the new block - a session with the same zone of another generator's block position does not match`() {
        val wrongKey = sessionRecord(
            "zone2", mapOf("mode" to "walk"), NOW - DAY,
            listOf(blockRecord(2, "2", BlockPhase.Work, speed = 5.6, heartRate = hrSummary(2, adjust = HeartRateAdjust.Incline, settled = 6.0, zoneLowBpm = 121, zoneHighBpm = 131))),
        )
        assertEquals(9.0, start(zone2(listOf(wrongKey))))
    }

    @Test
    fun `Couch to 5K reuses a settled run speed only from a session with the same zone 3`() {
        // Zone 3 of the profile is 131-149.
        fun record(low: Int, high: Int) = sessionRecord(
            CouchToFiveK.ID, mapOf("session" to "18", "mode" to "run"), NOW - 3 * DAY,
            listOf(
                blockRecord(0, "0", BlockPhase.WarmUp, speed = 5.0),
                blockRecord(1, "1", BlockPhase.Work, endHr = 140.0, heartRate = hrSummary(1, settled = 7.1, end = 140.0, zoneLowBpm = low, zoneHighBpm = high)),
                blockRecord(2, "2", BlockPhase.CoolDown, speed = 5.0),
            ),
        )
        val same = CouchToFiveK.generate(request(profile, history = listOf(record(130, 150))))
        assertEquals(BlockTarget.Absolute(7.1, 1.0), hr(same.program.steps[1]).start)
        val other = CouchToFiveK.generate(request(profile, history = listOf(record(108, 126))))
        assertTrue((hr(other.program.steps[1]).start as BlockTarget.Absolute).speedKmh != 7.1)
        assertTrue(other.notices.none { it.code == GeneratorNoticeCode.StartedFromHistory })
    }
}
