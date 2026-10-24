package com.treadz.console.programs.personalised

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.programs.fitness.ThresholdEstimate
import com.treadz.console.programs.fitness.ThresholdProfile
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.WarningCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeneratorsTest {

    private fun hr(block: Any?): BlockTarget.HeartRate = (block as Block).target as BlockTarget.HeartRate

    private fun abs(block: Block): BlockTarget.Absolute = block.target as BlockTarget.Absolute

    /** Valid, resolvable without parameters, personalised, with its origin; returns the resolved plan's duration. */
    private fun checkCommon(g: GeneratedProgram, id: String) {
        val p = g.program
        assertEquals(emptyList<Any>(), ProgramValidator.validate(p))
        assertEquals(ProgramKind.Personalised, p.kind)
        assertEquals(PROGRAM_ID, p.id)
        val origin = checkNotNull(p.origin)
        assertEquals(id, origin.generatorId)
        assertEquals(checkNotNull(ProgramGenerators.byId(id)).descriptor.version, origin.generatorVersion)
        for (key in listOf("mode", "sex", "ageYears", "vo2maxMlKgMin", "masKmh", "maxHeartRateBpm", "intensityFactor", "walkingSpeedKmh")) {
            assertTrue(key in origin.inputs, "origin input $key: ${origin.inputs}")
        }
        val plan = ProgramResolver.resolve(p)
        assertEquals(g.totalDurationSeconds, plan.estimatedDurationSeconds)
        assertTrue(plan.warnings.none { it.code == WarningCode.SpeedClamped || it.code == WarningCode.InclineClamped }, "${plan.warnings}")
        assertEquals(p, ProgramJson.decode(ProgramJson.encode(p)))
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun `registry lists the five generators with their inputs`() {
        val ids = ProgramGenerators.all.map { it.descriptor.id }
        assertEquals(listOf("zone2", "tempo", "norwegian-4x4", "intervals-30-30", "couch-to-5k"), ids)
        assertEquals(20..90, ProgramGenerators.byId("zone2")!!.descriptor.durationMinutes)
        assertTrue(GeneratorInput.Series in ProgramGenerators.byId("intervals-30-30")!!.descriptor.inputs)
        assertTrue(GeneratorInput.StartWeek in ProgramGenerators.byId("couch-to-5k")!!.descriptor.inputs)
        assertEquals(setOf(ExerciseMode.Run), CouchToFiveK.descriptor.modes)
        assertFalse(ProgramGenerators.byId("intervals-30-30")!!.descriptor.heartRateGuided)
        assertEquals("generator_norwegian_4x4_name", ProgramGenerators.byId("norwegian-4x4")!!.descriptor.nameResourceKey)
        assertNull(ProgramGenerators.byId("nope"))
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("nope", request()) }
    }

    @Test
    fun `every generator in both modes, measured and estimated, gives a valid resolvable program`() {
        for (gen in ProgramGenerators.all) {
            for (mode in gen.descriptor.modes) {
                for (profile in listOf(measuredProfile(), estimatedProfile(), measuredProfile(restingBpm = 55))) {
                    checkCommon(gen.generate(request(profile, mode)), gen.descriptor.id)
                }
            }
        }
    }

    // ------------------------------------------------------------------ zone 2

    @Test
    fun `zone 2 run - structure, zone, start and bounds`() {
        val g = ProgramGenerators.generate("zone2", request(durationMinutes = 45))
        checkCommon(g, "zone2")
        val steps = g.program.steps
        assertEquals(3, steps.size)
        assertEquals(BlockEnd.Duration(300), (steps[0] as Block).end)
        assertEquals(BlockEnd.Duration(2100), (steps[1] as Block).end)
        assertEquals(BlockEnd.Duration(300), (steps[2] as Block).end)
        assertEquals(2700, g.totalDurationSeconds)
        val t = hr(steps[1])
        // VO2max 42 at 40 is "fair": low level, zone 2 = 45-55 % of the reserve; no resting heart rate: 63-70 % of the
        // maximum.
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 63, 70, profileZone = 2), t.zone)
        assertEquals("vo2maxClassification", g.program.origin!!.inputs["zoneSource"])
        assertEquals("low", g.program.origin!!.inputs["zoneLevel"])
        assertEquals(HeartRateAdjust.Speed, t.adjust)
        // Middle of 113-126 bpm = 66.4 % HRmax -> 45.2 % VO2max -> (18.98 - 3.5) / 38.5 * 12 = 4.8 km/h: the 6 km/h floor.
        assertEquals(BlockTarget.Absolute(6.0, 1.0), t.start)
        assertEquals(5.0, t.minimum)
        assertEquals(9.0, t.maximum)
        assertEquals(BlockTarget.Absolute(6.0, 1.0), abs(steps[0] as Block))
        assertEquals(BlockTarget.Absolute(5.0, 1.0), abs(steps[2] as Block))
        assertFalse(g.estimatedValues)
        assertEquals(1.0, g.intensityFactor)
        assertEquals("45", g.program.origin!!.inputs["durationMinutes"])
        assertEquals("Zone 2 easy run, 45 min", g.program.name)
    }

    @Test
    fun `zone 2 walk - incline guided at the walking speed`() {
        val g = ProgramGenerators.generate("zone2", request(mode = ExerciseMode.Walk, durationMinutes = 20, walkingSpeedKmh = 5.0))
        val t = hr(g.program.steps[1])
        assertEquals(HeartRateAdjust.Incline, t.adjust)
        // 45.19 % of 42 = 18.98: (18.98 - 8.33 - 3.5) / 150 = 4.8 %
        assertEquals(BlockTarget.Absolute(5.0, 5.0), t.start)
        assertEquals(0.0, t.minimum)
        assertEquals(15.0, t.maximum)
        assertEquals(1200, g.totalDurationSeconds)
        assertEquals("walk", g.program.origin!!.inputs["mode"])
    }

    @Test
    fun `zone 2 duration outside 20-90 is refused`() {
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("zone2", request(durationMinutes = 19)) }
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("zone2", request(durationMinutes = 91)) }
        assertEquals(5400, ProgramGenerators.generate("zone2", request(durationMinutes = 90)).totalDurationSeconds)
    }

    @Test
    fun `zone 2 starts where the heart rate settled last time, same mode and block`() {
        val run = sessionRecord("zone2", mapOf("mode" to "run"), NOW - 3 * DAY, listOf(
            blockRecord(1, "1", BlockPhase.Work, heartRate = hrSummary(1, settled = 7.2)),
        ))
        val olderRun = sessionRecord("zone2", mapOf("mode" to "run"), NOW - 10 * DAY, listOf(
            blockRecord(1, "1", BlockPhase.Work, heartRate = hrSummary(1, settled = 6.6)),
        ))
        val walk = sessionRecord("zone2", mapOf("mode" to "walk"), NOW - DAY, listOf(
            blockRecord(1, "1", BlockPhase.Work, speed = 5.0, heartRate = hrSummary(1, adjust = HeartRateAdjust.Incline, settled = 7.0)),
        ))
        val otherGenerator = sessionRecord("tempo", mapOf("mode" to "run"), NOW - DAY, listOf(
            blockRecord(1, "1", BlockPhase.Work, heartRate = hrSummary(1, settled = 11.0)),
        ))
        val g = ProgramGenerators.generate("zone2", request(history = listOf(olderRun, walk, run, otherGenerator)))
        val t = hr(g.program.steps[1])
        assertEquals(BlockTarget.Absolute(7.2, 1.0), t.start)
        assertTrue(g.notices.contains(GeneratorNotice(GeneratorNoticeCode.StartedFromHistory, "1", 7.2)), "${g.notices}")
        assertEquals("7.2", g.program.origin!!.inputs["startFromHistory.1"])
        assertEquals("3", g.program.origin!!.inputs["historySessions"])

        val w = ProgramGenerators.generate("zone2", request(mode = ExerciseMode.Walk, walkingSpeedKmh = 5.0, history = listOf(olderRun, walk, run)))
        assertEquals(BlockTarget.Absolute(5.0, 7.0), hr(w.program.steps[1]).start)
        // At another walking speed the settled incline does not apply.
        val w2 = ProgramGenerators.generate("zone2", request(mode = ExerciseMode.Walk, walkingSpeedKmh = 4.5, history = listOf(walk)))
        assertTrue(w2.notices.none { it.code == GeneratorNoticeCode.StartedFromHistory })
        // Too old: ignored.
        val old = run.copy(startedAtMillis = NOW - 100 * DAY, endedAtMillis = NOW - 100 * DAY)
        val g2 = ProgramGenerators.generate("zone2", request(history = listOf(old)))
        assertEquals(BlockTarget.Absolute(6.0, 1.0), hr(g2.program.steps[1]).start)
    }

    @Test
    fun `a settled value above the usual maximum widens the bounds`() {
        val run = sessionRecord("zone2", mapOf("mode" to "run"), NOW - DAY, listOf(
            blockRecord(1, "1", BlockPhase.Work, heartRate = hrSummary(1, settled = 9.8)),
        ))
        val t = hr(ProgramGenerators.generate("zone2", request(history = listOf(run))).program.steps[1])
        assertEquals(9.8, t.maximum)
        assertEquals(BlockTarget.Absolute(9.8, 1.0), t.start)
    }

    // ------------------------------------------------------------------ estimated values

    @Test
    fun `estimated references lower the intensity by 10 percent and are flagged`() {
        val g = ProgramGenerators.generate("intervals-30-30", request(profile = estimatedProfile()))
        assertTrue(g.estimatedValues)
        assertEquals(0.9, g.intensityFactor)
        assertTrue(g.notices.any { it.code == GeneratorNoticeCode.EstimatedValues })
        // 1.025 * 0.9 * 11.77 = 10.86
        val fast = abs((g.program.steps[1] as Repeat).blocks[0])
        assertEquals(10.9, fast.speedKmh)
        assertEquals("true", g.program.origin!!.inputs["estimatedValues"])
        assertEquals("41.2", g.program.origin!!.inputs["vo2maxMlKgMin"])
        assertEquals("4", g.program.origin!!.inputs["nonExerciseActivityRating"])

        val noBody = ProgramGenerators.generate("zone2", request(profile = estimatedProfile().copy(weightKg = null)))
        assertTrue(noBody.notices.any { it.code == GeneratorNoticeCode.BodyDataAssumed })
    }

    @Test
    fun `an estimated maximum heart rate only counts for heart-rate generators`() {
        val profile = measuredProfile().copy(maxHeartRateOrigin = com.treadz.console.programs.fitness.HeartRateMaxOrigin.Estimated)
        assertFalse(ProgramGenerators.generate("intervals-30-30", request(profile = profile)).estimatedValues)
        assertTrue(ProgramGenerators.generate("norwegian-4x4", request(profile = profile)).estimatedValues)
    }

    // ------------------------------------------------------------------ tempo

    @Test
    fun `tempo picks the structure with the most work that fits`() {
        assertEquals(Triple(2, 8, 10), TempoGenerator.structure(37))
        assertEquals(Triple(2, 10, 14), TempoGenerator.structure(45))
        assertEquals(Triple(3, 8, 12), TempoGenerator.structure(50))
        assertEquals(Triple(3, 10, 16), TempoGenerator.structure(60))
        for (m in 37..60) {
            val g = ProgramGenerators.generate("tempo", request(durationMinutes = m))
            assertEquals(m * 60, g.totalDurationSeconds, "tempo $m min")
        }
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("tempo", request(durationMinutes = 36)) }
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("tempo", request(durationMinutes = 61)) }
    }

    @Test
    fun `tempo run - work at 84 percent MAS guided in zone 4, continuous`() {
        val g = ProgramGenerators.generate("tempo", request(durationMinutes = 45))
        checkCommon(g, "tempo")
        val rep = g.program.steps[1] as Repeat
        assertEquals(2, rep.count)
        val work = rep.blocks[0]
        assertEquals(BlockEnd.Duration(600), work.end)
        val t = hr(work)
        // Low level: zone 4 = 75-90 % of the reserve, 83-93 % of the maximum without a resting heart rate.
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 83, 93, profileZone = 4), t.zone)
        assertEquals(BlockTarget.Absolute(10.1, 1.0), t.start)
        assertEquals(8.4, t.minimum)
        assertEquals(11.0, t.maximum)
        assertEquals(BlockPhase.Recovery, rep.blocks[1].phase)
        assertEquals(BlockEnd.Duration(180), rep.blocks[1].end)
        assertEquals(BlockEnd.Duration(840), (g.program.steps[0] as Block).end)
        // The resolved work blocks are controlled continuously (10 min >= 6 min).
        val plan = ProgramResolver.resolve(g.program)
        assertTrue(plan.blocks.filter { it.phase == BlockPhase.Work }.all { it.estimatedDurationSeconds >= 360 && it.heartRate != null })
    }

    // ------------------------------------------------------------------ 4x4

    @Test
    fun `norwegian 4x4 run - 10 + 4 x (4 + 3) + 5, work at 85-95 percent between repetitions`() {
        val g = ProgramGenerators.generate("norwegian-4x4", request())
        checkCommon(g, "norwegian-4x4")
        assertEquals(43 * 60, g.totalDurationSeconds)
        val rep = g.program.steps[1] as Repeat
        assertEquals(4, rep.count)
        val t = hr(rep.blocks[0])
        assertEquals(BlockEnd.Duration(240), rep.blocks[0].end)
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 85, 95), t.zone)
        // 81.7 % VO2max -> (34.32 - 3.5) / 38.5 * 12 = 9.6 km/h
        assertEquals(BlockTarget.Absolute(9.6, 1.0), t.start)
        assertEquals(7.2, t.minimum)
        assertEquals(12.0, t.maximum)
        assertEquals(BlockTarget.Absolute(6.0, 1.0), abs(rep.blocks[1]))
        assertEquals(BlockEnd.Duration(180), rep.blocks[1].end)
    }

    @Test
    fun `norwegian 4x4 walk at a speed that cannot reach the intensity is capped and flagged`() {
        val fit = measuredProfile().copy(fitnessProfile = measuredFitness(vo2max = 60.0, mas = 17.0))
        val g = ProgramGenerators.generate("norwegian-4x4", request(profile = fit, mode = ExerciseMode.Walk))
        val t = hr((g.program.steps[1] as Repeat).blocks[0])
        assertEquals(HeartRateAdjust.Incline, t.adjust)
        assertEquals(BlockTarget.Absolute(6.5, 15.0), t.start)
        assertTrue(g.notices.any { it.code == GeneratorNoticeCode.WalkingTargetCapped && it.blockKey == "1.0" }, "${g.notices}")
    }

    // ------------------------------------------------------------------ 30/30

    @Test
    fun `intervals 30-30 run - fixed values, no heart rate`() {
        val g = ProgramGenerators.generate("intervals-30-30", request(series = 12))
        checkCommon(g, "intervals-30-30")
        val rep = g.program.steps[1] as Repeat
        assertEquals(12, rep.count)
        assertEquals(BlockTarget.Absolute(12.3, 1.0), abs(rep.blocks[0]))
        assertEquals(BlockTarget.Absolute(6.0, 1.0), abs(rep.blocks[1]))
        assertEquals(BlockEnd.Duration(30), rep.blocks[0].end)
        assertEquals(600 + 720 + 300, g.totalDurationSeconds)
        assertTrue(ProgramResolver.resolve(g.program).blocks.none { it.heartRate != null })
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("intervals-30-30", request(series = 5)) }
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("intervals-30-30", request(series = 31)) }
    }

    @Test
    fun `intervals 30-30 walk - same incline, brisk and slow speeds`() {
        val g = ProgramGenerators.generate("intervals-30-30", request(mode = ExerciseMode.Walk, walkingSpeedKmh = 5.0))
        val rep = g.program.steps[1] as Repeat
        // 1.025 * 42 = 43.05 needs 14.7 % at 6.5 km/h: the set is limited to 8 % (and 6.5 km/h), so it is capped.
        assertEquals(BlockTarget.Absolute(6.5, 8.0), abs(rep.blocks[0]))
        assertEquals(BlockTarget.Absolute(3.5, 8.0), abs(rep.blocks[1]))
        assertTrue(g.notices.any { it.code == GeneratorNoticeCode.WalkingTargetCapped }, "${g.notices}")
        // A less fit walker reaches the intensity below 8 % at the brisk speed.
        val unfit = measuredProfile().copy(fitnessProfile = measuredFitness(vo2max = 24.0, mas = 7.0))
        val easy = ProgramGenerators.generate("intervals-30-30", request(profile = unfit, mode = ExerciseMode.Walk, walkingSpeedKmh = 5.0))
        val easyRep = easy.program.steps[1] as Repeat
        // 1.025 * 24 = 24.6 at 6.5 km/h: (24.6 - 3.5 - 10.83) / 195 = 5.3 %
        assertEquals(BlockTarget.Absolute(6.5, 5.0), abs(easyRep.blocks[0]))
        assertTrue(easy.notices.none { it.code == GeneratorNoticeCode.WalkingTargetCapped }, "${easy.notices}")
    }

    // ------------------------------------------------------------------ heart-rate reserve (Karvonen)

    @Test
    fun `reserve zones without a resting heart rate become the equivalent percent of the maximum`() {
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 73, 80), ProgramGenerators.reserveZone(60, 70, null))
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 80, 87), ProgramGenerators.reserveZone(70, 80, null))
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 87, 93), ProgramGenerators.reserveZone(80, 90, null))
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 60, 70), ProgramGenerators.reserveZone(60, 70, 55))
    }

    @Test
    fun `with a resting heart rate zone 2 and tempo use the heart-rate reserve`() {
        val rested = request(profile = measuredProfile(restingBpm = 55), durationMinutes = 45)
        val z2 = ProgramGenerators.generate("zone2", rested)
        val t = hr(z2.program.steps[1])
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 45, 55, profileZone = 2), t.zone)
        // 111-124 bpm, middle 117.5 = 50 % of the reserve -> 22.75 ml/kg/min -> 19.25 / 38.5 * 12 = 6.0 km/h.
        assertEquals(BlockTarget.Absolute(6.0, 1.0), t.start)
        assertTrue(z2.notices.none { it.code == GeneratorNoticeCode.RestingHeartRateMissing }, "${z2.notices}")
        assertEquals("heartRateReserve", z2.program.origin!!.inputs["heartRateZoneBasis"])
        assertTrue(z2.program.description!!.contains("45-55 % of the heart-rate reserve"), z2.program.description)

        val tempo = ProgramGenerators.generate("tempo", rested)
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 75, 90, profileZone = 4), hr((tempo.program.steps[1] as Repeat).blocks[0]).zone)
    }

    @Test
    fun `without a resting heart rate the UI is told, but not for zones defined on the maximum`() {
        val z2 = ProgramGenerators.generate("zone2", request())
        assertTrue(z2.notices.any { it.code == GeneratorNoticeCode.RestingHeartRateMissing }, "${z2.notices}")
        assertEquals("maxHeartRate", z2.program.origin!!.inputs["heartRateZoneBasis"])
        assertTrue(z2.program.description!!.contains("63-70 % of the maximum heart rate"), z2.program.description)
        val x4 = ProgramGenerators.generate("norwegian-4x4", request())
        assertTrue(x4.notices.none { it.code == GeneratorNoticeCode.RestingHeartRateMissing }, "${x4.notices}")
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 85, 95), hr((x4.program.steps[1] as Repeat).blocks[0]).zone)
    }

    // ------------------------------------------------------------------ naming and inputs

    @Test
    fun `the name given by the UI is used and requests are checked`() {
        assertEquals("My easy run", ProgramGenerators.generate("zone2", request(name = "My easy run")).program.name)
        assertThrows<IllegalArgumentException> { ProgramGenerators.generate("zone2", request(walkingSpeedKmh = 9.0)) }
        assertEquals("5.5", ProgramGenerators.generate("zone2", request(walkingSpeedKmh = 5.5)).program.origin!!.inputs["walkingSpeedKmh"])
    }

    // ------------------------------------------------------------------ zone model and measured thresholds

    /** The measured profile with thresholds VT1 132 (VO2 30.0) and VT2 151 from a high-quality walking test. */
    private fun withThresholds(restingBpm: Int? = 55, vt2: Int? = 151) = measuredProfile(restingBpm).copy(
        heartRate = HeartRateProfile(181, restingBpm),
        fitnessProfile = measuredFitness().copy(
            thresholds = ThresholdProfile(
                vt1 = ThresholdEstimate(132, 30.0, ThresholdQuality.High, emptyList()),
                vt2 = vt2?.let { ThresholdEstimate(it, 38.0, ThresholdQuality.High, emptyList()) },
                history = emptyList(),
            ),
        ),
    )

    @Test
    fun `the level of the zones follows the tested VO2max, else the self-rated fitness`() {
        // VO2max 46.4 at 40 is "excellent": high level, zone 2 = 55-65 % of the reserve.
        val fit = measuredProfile(restingBpm = 55).copy(fitnessProfile = measuredFitness(vo2max = 46.4))
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 55, 65, profileZone = 2), hr(ProgramGenerators.generate("zone2", request(fit)).program.steps[1]).zone)
        // No test: the self-rated level (intermediate: medium, 50-60 %).
        val untested = estimatedProfile().copy(heartRate = HeartRateProfile(180, 55))
        val g = ProgramGenerators.generate("zone2", request(untested))
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 50, 60, profileZone = 2), hr(g.program.steps[1]).zone)
        assertEquals("selfRatedFitness", g.program.origin!!.inputs["zoneSource"])
        assertEquals("medium", g.program.origin!!.inputs["zoneLevel"])
        // Nothing known: low.
        val unknown = untested.copy(selfRatedFitness = null)
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 45, 55, profileZone = 2), hr(ProgramGenerators.generate("zone2", request(unknown)).program.steps[1]).zone)
    }

    @Test
    fun `with measured thresholds the zones come from VT1 and VT2 as whole percentages of the reserve`() {
        val g = ProgramGenerators.generate("zone2", request(withThresholds(), durationMinutes = 45))
        val t = hr(g.program.steps[1])
        // Zone 2 = 122-132 bpm: (122 - 55) / 126 = 53.2 % -> 53; (132 - 55) / 126 = 61.1 % -> 61.
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 53, 61, profileZone = 2), t.zone)
        val inputs = g.program.origin!!.inputs
        assertEquals("measuredThresholds", inputs["zoneSource"])
        assertEquals("132", inputs["vt1Bpm"])
        assertEquals("151", inputs["vt2Bpm"])
        val bpm = HeartRateZones.toBpm(t.zone, HeartRateProfile(181, 55))
        assertEquals(122, bpm.lowBpm)
        assertEquals(132, bpm.highBpm)
        // Tempo in zone 4 = 151-166 bpm; Couch to 5K long runs in zone 3 = 132-151 bpm.
        val tempo = hr((ProgramGenerators.generate("tempo", request(withThresholds())).program.steps[1] as Repeat).blocks[0])
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 76, 88, profileZone = 4), tempo.zone)
        val c25k = CouchToFiveK.generate(request(withThresholds(), history = listOf(c25kRecord(14, NOW - 2 * DAY - HOUR))))
        assertEquals(HeartRateZone(HeartRateBasis.HeartRateReserve, 61, 76, profileZone = 3), ((c25k.program.steps[1] as Block).target as BlockTarget.HeartRate).zone)
        // Without a resting heart rate, as % of the maximum: 122 / 181 = 67 %, 132 / 181 = 73 %; no notice (exact bpm).
        val noRest = ProgramGenerators.generate("zone2", request(withThresholds(restingBpm = null)))
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 67, 73, profileZone = 2), hr(noRest.program.steps[1]).zone)
        assertTrue(noRest.notices.none { it.code == GeneratorNoticeCode.RestingHeartRateMissing }, "${noRest.notices}")
    }

    @Test
    fun `with a measured VT1 the start of a heart-rate block comes from its oxygen demand`() {
        // Zone 2 middle = 127 bpm: VO2 = 3.5 + (30 - 3.5) * (127 - 55) / (132 - 55) = 28.28.
        val vo2 = 3.5 + (30.0 - 3.5) * (127.0 - 55) / (132 - 55)
        val walk = ProgramGenerators.generate("zone2", request(withThresholds(), mode = ExerciseMode.Walk, walkingSpeedKmh = 5.0))
        val w = hr(walk.program.steps[1])
        // Walking at 5 km/h: (28.28 - 3.5 - 8.33) / 150 = 11.0 %.
        assertEquals(BlockTarget.Absolute(5.0, Math.round((vo2 - 3.5 - 5000.0 / 60 * 0.1) / (1.8 * 5000.0 / 60) * 100).toDouble()), w.start)
        assertEquals(11.0, (w.start as BlockTarget.Absolute).inclinePercent)
        assertEquals(FormatFixtures.two(vo2), walk.program.origin!!.inputs["startFromVt1.1"])
        // Running at 1 %: S = (28.28 - 3.5) / (0.2 + 0.009) m/min = 7.1 km/h.
        val run = ProgramGenerators.generate("zone2", request(withThresholds()))
        assertEquals(BlockTarget.Absolute(7.1, 1.0), hr(run.program.steps[1]).start)
        // Without a resting heart rate it is assumed at a third of the maximum (60.3 bpm).
        val noRest = ProgramGenerators.generate("zone2", request(withThresholds(restingBpm = null)))
        val zone = hr(noRest.program.steps[1]).zone
        // The block names zone 2 of the user's zones (122-132, exact), which the run and the start both use.
        assertEquals(2, zone.profileZone)
        val mid = (122 + 132) / 2.0
        val rest = 181.0 / 3
        val vo2NoRest = 3.5 + 26.5 * (mid - rest) / (132 - rest)
        assertEquals(Math.round((vo2NoRest - 3.5) / 0.209 * 0.06 * 10) / 10.0, (hr(noRest.program.steps[1]).start as BlockTarget.Absolute).speedKmh)
        // A settled value from the history keeps priority.
        val history = sessionRecord("zone2", mapOf("mode" to "run"), NOW - DAY, listOf(blockRecord(1, "1", BlockPhase.Work, heartRate = hrSummary(1, settled = 7.6, zoneLowBpm = 122, zoneHighBpm = 132))))
        assertEquals(BlockTarget.Absolute(7.6, 1.0), hr(ProgramGenerators.generate("zone2", request(withThresholds(), history = listOf(history))).program.steps[1]).start)
        // The 4x4 keeps its zone (85-95 % of the maximum) and starts from VT1 too.
        val x4 = hr((ProgramGenerators.generate("norwegian-4x4", request(withThresholds())).program.steps[1] as Repeat).blocks[0])
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 85, 95), x4.zone)
        val x4Mid = HeartRateZones.toBpm(x4.zone, HeartRateProfile(181, 55)).let { (it.lowBpm + it.highBpm) / 2.0 }
        val x4Vo2 = 3.5 + 26.5 * (x4Mid - 55) / (132 - 55)
        assertEquals(Math.round((x4Vo2 - 3.5) / 0.209 * 0.06 * 10) / 10.0, (x4.start as BlockTarget.Absolute).speedKmh)
    }
}

private object FormatFixtures {
    fun two(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
}
