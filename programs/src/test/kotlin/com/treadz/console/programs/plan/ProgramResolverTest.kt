package com.treadz.console.programs.plan

import com.treadz.console.programs.abs
import com.treadz.console.programs.block
import com.treadz.console.programs.everythingProgram
import com.treadz.console.programs.heartRateProgram
import com.treadz.console.programs.hillTemplate
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.meters
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.norwegian4x4
import com.treadz.console.programs.rel
import com.treadz.console.programs.secs
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.ValueRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgramResolverTest {

    private val params = StartParameters(totalDurationSeconds = 360, easySpeedKmh = 4.0, maxSpeedKmh = 12.0, maxInclinePercent = 10.0)

    private fun ResolvedPlan.speeds() = blocks.map { it.speedKmh }
    private fun ResolvedPlan.inclines() = blocks.map { it.inclinePercent }
    private fun ResolvedPlan.durations() = blocks.map { (it.end as BlockEnd.Duration).seconds }
    private fun ResolvedPlan.warningCodes(code: WarningCode) = warnings.filter { it.code == code }

    private fun resolve(
        p: com.treadz.console.programs.model.Program = hillTemplate(),
        sp: StartParameters = params,
        env: ResolutionEnvironment = ResolutionEnvironment(),
    ) = ProgramResolver.resolve(p, sp, env)

    // ------------------------------------------------------------------ relative mapping

    @Test
    fun `relative speed maps linearly from the easy speed to the maximum speed and incline from flat to the maximum`() {
        val plan = resolve()
        assertEquals(listOf(4.0, 12.0, 8.0, 4.0), plan.speeds())
        assertEquals(listOf(0.0, 10.0, 5.0, 0.0), plan.inclines())
        assertEquals(listOf(60, 120, 120, 60), plan.durations())
        assertTrue(plan.warnings.isEmpty(), "${plan.warnings}")
    }

    @Test
    fun `mapped values are rounded to the treadmill step`() {
        val p = hillTemplate().copy(steps = listOf(block(BlockPhase.Work, secs(300), rel(33.0, 33.0))))
        val plan = resolve(p, StartParameters(easySpeedKmh = 3.0, maxSpeedKmh = 11.0, maxInclinePercent = 10.0))
        assertEquals(5.6, plan.blocks[0].speedKmh) // 3 + 0.33 * 8 = 5.64
        assertEquals(3.0, plan.blocks[0].inclinePercent) // 3.3
        assertTrue(plan.warnings.isEmpty(), "rounding alone is not a warning")
    }

    @Test
    fun `absolute targets are used as they are`() {
        val plan = resolve(norwegian4x4(), StartParameters())
        assertEquals(listOf(6.0, 10.0, 6.0, 10.0, 6.0, 10.0, 6.0, 10.0, 6.0, 5.0), plan.speeds())
        assertEquals(listOf(2.0, 6.0, 2.0, 6.0, 2.0, 6.0, 2.0, 6.0, 2.0, 0.0), plan.inclines())
    }

    @Test
    fun `relative targets need the reference values`() {
        val e = assertThrows(InvalidProgramException::class.java) { resolve(sp = StartParameters(totalDurationSeconds = 360)) }
        assertEquals(setOf("parameters.easySpeedKmh", "parameters.maxSpeedKmh", "parameters.maxInclinePercent"), e.issues.map { it.path }.toSet())
    }

    @Test
    fun `only the reference values the program really uses are required`() {
        fun program(vararg targets: com.treadz.console.programs.model.BlockTarget) =
            hillTemplate().copy(steps = targets.map { block(BlockPhase.Work, secs(120), it) })
        assertEquals(emptySet<IntensityParameter>(), ProgramResolver.requiredParameters(norwegian4x4()))
        assertEquals(setOf(IntensityParameter.EasySpeed), ProgramResolver.requiredParameters(program(rel(0.0, 0.0))))
        assertEquals(setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxIncline), ProgramResolver.requiredParameters(program(rel(0.0, 50.0))))
        assertEquals(setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed), ProgramResolver.requiredParameters(program(rel(50.0, 0.0))))
        assertEquals(setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline), ProgramResolver.requiredParameters(hillTemplate()))

        // A program at the easy speed on an incline resolves without a maximum speed
        val walk = resolve(program(rel(0.0, 100.0)), StartParameters(easySpeedKmh = 4.8, maxInclinePercent = 12.0))
        assertEquals(4.8, walk.blocks[0].speedKmh)
        assertEquals(12.0, walk.blocks[0].inclinePercent)
        // ...but not without the easy speed, and values that are given are still checked
        val e = assertThrows(InvalidProgramException::class.java) { resolve(program(rel(0.0, 100.0)), StartParameters(maxInclinePercent = 12.0)) }
        assertEquals(listOf("parameters.easySpeedKmh"), e.issues.map { it.path })
        val bad = assertThrows(InvalidProgramException::class.java) {
            resolve(program(rel(0.0, 100.0)), StartParameters(easySpeedKmh = 4.8, maxInclinePercent = 12.0, maxSpeedKmh = 30.0))
        }
        assertEquals(listOf("parameters.maxSpeedKmh"), bad.issues.map { it.path })
    }

    @Test
    fun `invalid parameters are reported`() {
        fun paths(sp: StartParameters) = assertThrows(InvalidProgramException::class.java) { resolve(sp = sp) }.issues.map { it.path }
        assertEquals(listOf("parameters.easySpeedKmh"), paths(params.copy(easySpeedKmh = 13.0)))
        assertEquals(listOf("parameters.maxSpeedKmh"), paths(params.copy(maxSpeedKmh = 21.0)))
        assertEquals(listOf("parameters.maxInclinePercent"), paths(params.copy(maxInclinePercent = 19.0)))
        assertEquals(listOf("parameters.totalDurationSeconds"), paths(params.copy(totalDurationSeconds = 30)))
        assertEquals(listOf("parameters.intensityAdjustmentPercent"), paths(params.copy(intensityAdjustmentPercent = 51)))
        assertEquals(listOf("parameters.intensityAdjustmentPercent"), paths(params.copy(intensityAdjustmentPercent = -51)))
    }

    @Test
    fun `an invalid program is rejected with its issues`() {
        val e = assertThrows(InvalidProgramException::class.java) { resolve(hillTemplate().copy(steps = emptyList())) }
        assertEquals("steps", e.issues.single().path)
    }

    // ------------------------------------------------------------------ duration scaling

    @Test
    fun `time blocks scale proportionally to the requested total`() {
        assertEquals(listOf(120, 240, 240, 120), resolve(sp = params.copy(totalDurationSeconds = 720)).durations())
        assertEquals(listOf(30, 60, 60, 30), resolve(sp = params.copy(totalDurationSeconds = 180)).durations())
    }

    @Test
    fun `scaled durations add up exactly to the requested total`() {
        listOf(100, 600, 1_799, 2_701, 3_600, 7_777).forEach { total ->
            val plan = resolve(sp = params.copy(totalDurationSeconds = total))
            assertEquals(total, plan.durations().sum(), "total $total: ${plan.durations()}")
            assertTrue(plan.durations().all { it >= 1 })
            assertEquals(total, plan.estimatedDurationSeconds)
        }
        assertEquals(listOf(100, 200, 200, 100), resolve(sp = params.copy(totalDurationSeconds = 600)).durations())
        assertEquals(listOf(17, 33, 33, 17), resolve(sp = params.copy(totalDurationSeconds = 100)).durations())
    }

    @Test
    fun `without a total duration the written durations are kept`() {
        assertEquals(listOf(60, 120, 120, 60), resolve(sp = params.copy(totalDurationSeconds = null)).durations())
    }

    @Test
    fun `distance blocks are not scaled and the total duration counts only time blocks`() {
        val plan = resolve(
            everythingProgram(),
            StartParameters(totalDurationSeconds = 1_140, easySpeedKmh = 4.0, maxSpeedKmh = 12.0, maxInclinePercent = 10.0),
        )
        assertEquals(BlockEnd.Distance(1000), plan.blocks[0].end)
        val time = plan.blocks.mapNotNull { (it.end as? BlockEnd.Duration)?.seconds }
        assertEquals(1_140, time.sum()) // 570 s as written, doubled
        assertEquals(listOf(180, 120, 180, 120, 180, 120, 240), time)
    }

    @Test
    fun `a total duration needs time blocks to scale`() {
        val p = hillTemplate().copy(steps = listOf(block(BlockPhase.Work, meters(500), abs(6.0))))
        val e = assertThrows(InvalidProgramException::class.java) { resolve(p, StartParameters(totalDurationSeconds = 600)) }
        assertEquals("parameters.totalDurationSeconds", e.issues.single().path)
    }

    // ------------------------------------------------------------------ global adjustment

    @Test
    fun `the global adjustment scales the speed of every block and never the incline`() {
        val base = resolve()
        val up = resolve(sp = params.copy(intensityAdjustmentPercent = 10))
        assertEquals(listOf(4.4, 13.2, 8.8, 4.4), up.speeds())
        assertEquals(listOf(0.0, 10.0, 5.0, 0.0), up.inclines())
        val down = resolve(sp = params.copy(intensityAdjustmentPercent = -20))
        assertEquals(listOf(3.2, 9.6, 6.4, 3.2), down.speeds())
        assertEquals(listOf(0.0, 10.0, 5.0, 0.0), down.inclines())
        assertEquals(base.inclines(), up.inclines())
        assertEquals(base.inclines(), down.inclines())
    }

    @Test
    fun `the adjustment leaves the incline of absolute programs and heart-rate blocks alone`() {
        val absolute = resolve(norwegian4x4(), StartParameters(intensityAdjustmentPercent = 50))
        assertEquals(resolve(norwegian4x4(), StartParameters()).inclines(), absolute.inclines())
        val hr = resolve(heartRateProgram(), StartParameters(intensityAdjustmentPercent = 10))
        assertEquals(resolve(heartRateProgram(), StartParameters()).inclines(), hr.inclines())
    }

    @Test
    fun `the adjustment applies to absolute programs too and is recorded in the plan`() {
        val plan = resolve(norwegian4x4(), StartParameters(intensityAdjustmentPercent = 50))
        assertEquals(listOf(9.0, 15.0, 9.0, 15.0, 9.0, 15.0, 9.0, 15.0, 9.0, 7.5), plan.speeds())
        assertEquals(50, plan.parameters.intensityAdjustmentPercent)
    }

    // ------------------------------------------------------------------ clamping

    @Test
    fun `values are clamped to the safety limits with a warning`() {
        val plan = resolve(env = ResolutionEnvironment(limits = SafetyLimits(maxSpeedKmh = 10.0, maxInclinePercent = 8.0)))
        assertEquals(listOf(4.0, 10.0, 8.0, 4.0), plan.speeds())
        assertEquals(listOf(0.0, 8.0, 5.0, 0.0), plan.inclines())
        val speed = plan.warningCodes(WarningCode.SpeedClamped).single()
        assertEquals(1, speed.blockIndex)
        assertEquals(12.0, speed.requested)
        assertEquals(10.0, speed.applied)
        val incline = plan.warningCodes(WarningCode.InclineClamped).single()
        assertEquals(1, incline.blockIndex)
        assertEquals(8.0, incline.applied)
        assertTrue(speed.message.contains("Block 2"))
    }

    @Test
    fun `values are clamped to the treadmill range`() {
        val env = ResolutionEnvironment(speedRange = ValueRange(1.0, 12.0, 0.1), inclineRange = ValueRange(0.0, 15.0, 1.0))
        val plan = resolve(norwegian4x4(), StartParameters(intensityAdjustmentPercent = 50), env)
        // work 10 km/h * 1.5 = 15 > 12 ; the incline is not adjusted
        assertEquals(12.0, plan.blocks[1].speedKmh)
        assertEquals(4, plan.warningCodes(WarningCode.SpeedClamped).size)
        assertTrue(plan.warningCodes(WarningCode.InclineClamped).isEmpty())
    }

    @Test
    fun `an adjustment that pushes a value over the maximum is clamped, not rejected`() {
        val plan = resolve(sp = params.copy(intensityAdjustmentPercent = 50, maxSpeedKmh = 18.0))
        assertEquals(20.0, plan.blocks[1].speedKmh) // 18 * 1.5 = 27
        assertEquals(1, plan.warningCodes(WarningCode.SpeedClamped).size)
    }

    // ------------------------------------------------------------------ repeats

    @Test
    fun `repeats are expanded with their repetition and source references`() {
        val plan = resolve(norwegian4x4(), StartParameters())
        assertEquals(10, plan.blocks.size)
        assertEquals((0..9).toList(), plan.blocks.map { it.index })
        assertEquals(
            listOf(BlockPhase.WarmUp) + List(4) { listOf(BlockPhase.Work, BlockPhase.Recovery) }.flatten() + BlockPhase.CoolDown,
            plan.blocks.map { it.phase },
        )
        assertNull(plan.blocks[0].repetition)
        assertNull(plan.blocks[0].blockInStep)
        assertEquals(Repetition(1, 4), plan.blocks[1].repetition)
        assertEquals(Repetition(1, 4), plan.blocks[2].repetition)
        assertEquals(Repetition(3, 4), plan.blocks[5].repetition)
        assertEquals(Repetition(4, 4), plan.blocks[8].repetition)
        assertNull(plan.blocks[9].repetition)
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 2), plan.blocks.map { it.stepIndex })
        assertEquals(listOf(null, 0, 1, 0, 1, 0, 1, 0, 1, null), plan.blocks.map { it.blockInStep })
        assertEquals("4 min hard", plan.blocks[1].name)
    }

    @Test
    fun `totals estimate duration and distance`() {
        val plan = resolve(norwegian4x4(), StartParameters())
        assertEquals(600 + 4 * 420 + 300, plan.estimatedDurationSeconds)
        // 6 km/h 600 s + 4 * (10 km/h 240 s + 6 km/h 180 s) + 5 km/h 300 s
        assertEquals(5283, plan.estimatedDistanceMeters)
    }

    @Test
    fun `distance blocks get a duration estimate from their speed`() {
        val plan = resolve(
            everythingProgram(),
            StartParameters(easySpeedKmh = 4.0, maxSpeedKmh = 12.0, maxInclinePercent = 10.0),
        )
        // 20 % of the range = 5.6 km/h ; 1000 m at 5.6 km/h = 643 s
        assertEquals(5.6, plan.blocks[0].speedKmh)
        assertEquals(643, plan.blocks[0].estimatedDurationSeconds)
        assertEquals(1000, plan.blocks[0].let { (it.end as BlockEnd.Distance).meters })
    }

    // ------------------------------------------------------------------ warnings

    @Test
    fun `short blocks produce a warning but are kept`() {
        val plan = resolve(sp = params.copy(totalDurationSeconds = 100))
        assertEquals(listOf(17, 33, 33, 17), plan.durations())
        assertEquals(listOf(0, 1, 2, 3), plan.warningCodes(WarningCode.ShortBlock).map { it.blockIndex })
        assertEquals(17.0, plan.warningCodes(WarningCode.ShortBlock).first().requested)
        val borderline = resolve(sp = params.copy(totalDurationSeconds = 1_440)) // 240 / 480 / 480 / 240
        assertTrue(borderline.warningCodes(WarningCode.ShortBlock).isEmpty())
        val exactly45 = hillTemplate().copy(steps = listOf(block(BlockPhase.Work, secs(45), abs(6.0)), block(BlockPhase.Work, secs(44), abs(6.0))))
        assertEquals(listOf(1), resolve(exactly45, StartParameters()).warningCodes(WarningCode.ShortBlock).map { it.blockIndex })
    }

    // ------------------------------------------------------------------ heart-rate blocks

    @Test
    fun `heart-rate blocks run at their starting values and the plan says so`() {
        val plan = resolve(heartRateProgram(), StartParameters())
        val hr = plan.blocks[1]
        assertEquals(8.0, hr.speedKmh)
        assertEquals(3.0, hr.inclinePercent)
        val g = hr.heartRate!!
        assertEquals(HeartRateAdjust.Speed, g.adjust)
        assertEquals(6.0, g.minimum)
        assertEquals(10.0, g.maximum)
        assertNull(plan.blocks[0].heartRate)
        assertEquals(listOf(1), plan.warningCodes(WarningCode.HeartRateControlUnavailable).map { it.blockIndex })
    }

    @Test
    fun `relative heart-rate targets map their start and bounds`() {
        val plan = resolve(everythingProgram(), StartParameters(easySpeedKmh = 4.0, maxSpeedKmh = 12.0, maxInclinePercent = 10.0))
        val absolute = plan.blocks[1].heartRate!! // incline bounds 2..8, absolute start 9 km/h 4 %
        assertEquals(9.0, plan.blocks[1].speedKmh)
        assertEquals(4.0, plan.blocks[1].inclinePercent)
        assertEquals(2.0, absolute.minimum)
        assertEquals(8.0, absolute.maximum)
        val relative = plan.blocks[2].heartRate!! // speed bounds 10..90 % of 4..12 = 4.8..11.2, start 40 % / 20 %
        assertEquals(7.2, plan.blocks[2].speedKmh)
        assertEquals(2.0, plan.blocks[2].inclinePercent)
        assertEquals(4.8, relative.minimum)
        assertEquals(11.2, relative.maximum)
    }

    @Test
    fun `the global adjustment moves the heart-rate starting values but not the bounds`() {
        val plan = resolve(heartRateProgram(), StartParameters(intensityAdjustmentPercent = 10))
        assertEquals(8.8, plan.blocks[1].speedKmh)
        assertEquals(6.0, plan.blocks[1].heartRate!!.minimum)
        assertEquals(10.0, plan.blocks[1].heartRate!!.maximum)
        // pushed over the upper bound: kept inside it, with a warning
        val over = resolve(heartRateProgram(), StartParameters(intensityAdjustmentPercent = 30))
        assertEquals(10.0, over.blocks[1].speedKmh)
        assertEquals(1, over.warningCodes(WarningCode.SpeedClamped).count { it.blockIndex == 1 })
    }

    // ------------------------------------------------------------------ source and serialisation

    @Test
    fun `the plan keeps the identity of its program`() {
        val p = norwegian4x4(ProgramKind.Personalised, ProgramOrigin("gen", 3, mapOf("a" to "b")))
        val plan = resolve(p, StartParameters())
        assertEquals(PlanSource(p.id, p.name, ProgramKind.Personalised, 1, p.origin), plan.source)
    }

    @Test
    fun `a resolved plan round trips through JSON`() {
        listOf(
            resolve(sp = params.copy(totalDurationSeconds = 100, intensityAdjustmentPercent = -10)),
            resolve(norwegian4x4(ProgramKind.Personalised, ProgramOrigin("gen", 3, mapOf("a" to "b"))), StartParameters()),
            resolve(heartRateProgram(), StartParameters()),
            resolve(everythingProgram(), StartParameters(easySpeedKmh = 4.0, maxSpeedKmh = 12.0, maxInclinePercent = 10.0, intensityAdjustmentPercent = 15)),
            resolve(env = ResolutionEnvironment(limits = SafetyLimits(maxSpeedKmh = 10.0))),
        ).forEach { plan ->
            val text = PlanJson.encode(plan)
            assertEquals(plan, PlanJson.decode(text))
        }
        val text = PlanJson.encode(resolve())
        assertTrue(text.contains("\"estimatedDurationSeconds\"") && text.contains("\"speedKmh\"") && text.contains("\"planSchemaVersion\": 1"))
    }

    @Test
    fun `a plan written with the old adjustIncline field is still read and the field is no longer written`() {
        val plan = resolve(sp = params.copy(intensityAdjustmentPercent = 10))
        val text = PlanJson.encode(plan)
        assertFalse(text.contains("adjustIncline"))
        val old = text.replaceFirst("\"intensityAdjustmentPercent\": 10", "\"intensityAdjustmentPercent\": 10, \"adjustIncline\": true")
        assertTrue(old.contains("adjustIncline"))
        assertEquals(plan, PlanJson.decode(old))
    }

    @Test
    fun `plan JSON ignores unknown fields and rejects newer versions`() {
        val text = PlanJson.encode(resolve()).replaceFirst("\"planSchemaVersion\": 1,", "\"planSchemaVersion\": 1, \"extra\": {\"x\": 1},")
        assertEquals(resolve(), PlanJson.decode(text))
        val newer = PlanJson.encode(resolve()).replace("\"planSchemaVersion\": 1", "\"planSchemaVersion\": 9")
        assertThrows(com.treadz.console.programs.json.ProgramFormatException::class.java) { PlanJson.decode(newer) }
    }
}
