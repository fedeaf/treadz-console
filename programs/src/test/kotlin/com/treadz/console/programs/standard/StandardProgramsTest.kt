package com.treadz.console.programs.standard

import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.WarningCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardProgramsTest {

    private val all = StandardPrograms.all

    private fun StandardProgram.plan(total: Int = defaultTotalDurationSeconds, adjust: Int = 0): ResolvedPlan =
        ProgramResolver.resolve(program, defaultParameters.copy(totalDurationSeconds = total, intensityAdjustmentPercent = adjust))

    private fun ResolvedPlan.seconds() = blocks.map { (it.end as BlockEnd.Duration).seconds }

    @Test
    fun `there are eight programs with unique stable ids and keys`() {
        assertEquals(
            listOf("standard-hill", "standard-rolling-hills", "standard-intervals", "standard-pyramid", "standard-progressive", "standard-fat-burn", "standard-incline-walk", "standard-glutes"),
            all.map { it.id },
        )
        assertEquals(all.size, all.map { it.key }.toSet().size)
        all.forEach {
            assertEquals("standard-${it.key}", it.id)
            assertEquals(ProgramKind.Standard, it.program.kind)
            assertEquals(it, StandardPrograms.byId(it.id))
        }
        assertEquals(null, StandardPrograms.byId("standard-nope"))
    }

    @Test
    fun `resource keys follow the key`() {
        val rolling = StandardPrograms.byId("standard-rolling-hills")!!
        assertEquals("standard_program_rolling_hills_name", rolling.nameResourceKey)
        assertEquals("standard_program_rolling_hills_description", rolling.descriptionResourceKey)
        assertEquals(all.size, all.map { it.nameResourceKey }.toSet().size)
    }

    @Test
    fun `every template is valid and survives JSON`() {
        all.forEach {
            assertEquals(emptyList<Any>(), ProgramValidator.validate(it.program), it.id)
            assertEquals(it.program, ProgramJson.decode(ProgramJson.encode(it.program)))
        }
    }

    @Test
    fun `at the default duration the blocks are real seconds, long enough and without warnings`() {
        all.forEach { sp ->
            val plan = sp.plan()
            assertEquals(1_800, plan.seconds().sum(), sp.id)
            assertEquals(1_800, plan.estimatedDurationSeconds)
            assertTrue(plan.seconds().all { it >= 75 }, "${sp.id}: ${plan.seconds()}")
            assertEquals(emptyList<Any>(), plan.warnings, sp.id)
        }
    }

    @Test
    fun `every template starts with a 5 minute warm-up and ends with a 5 minute cool-down`() {
        all.forEach { sp ->
            val plan = sp.plan()
            val warm = plan.blocks.takeWhile { it.phase == BlockPhase.WarmUp }
            val cool = plan.blocks.takeLastWhile { it.phase == BlockPhase.CoolDown }
            assertTrue(warm.isNotEmpty() && cool.isNotEmpty(), sp.id)
            assertEquals(300, warm.sumOf { (it.end as BlockEnd.Duration).seconds }, sp.id)
            assertEquals(300, cool.sumOf { (it.end as BlockEnd.Duration).seconds }, sp.id)
            assertTrue(plan.blocks.drop(warm.size).dropLast(cool.size).none { it.phase == BlockPhase.WarmUp || it.phase == BlockPhase.CoolDown })
        }
    }

    @Test
    fun `every template resolves across its whole duration range with no short blocks`() {
        all.forEach { sp ->
            assertEquals(1_200, sp.minTotalDurationSeconds)
            assertTrue(sp.defaultTotalDurationSeconds in sp.minTotalDurationSeconds..sp.maxTotalDurationSeconds)
            assertEquals(0, (sp.maxTotalDurationSeconds - sp.minTotalDurationSeconds) % sp.durationStepSeconds)
            for (total in sp.minTotalDurationSeconds..sp.maxTotalDurationSeconds step sp.durationStepSeconds) {
                val plan = sp.plan(total)
                assertEquals(total, plan.seconds().sum(), "${sp.id} at $total")
                assertFalse(plan.warnings.any { it.code == WarningCode.ShortBlock }, "${sp.id} at $total: ${plan.warnings}")
                assertTrue(plan.warnings.isEmpty(), "${sp.id} at $total: ${plan.warnings}")
            }
        }
    }

    @Test
    fun `declared parameters are exactly what the template uses and the defaults cover them`() {
        val expected = mapOf(
            "standard-hill" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline),
            "standard-rolling-hills" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline),
            "standard-intervals" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline),
            "standard-pyramid" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline),
            "standard-progressive" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline),
            "standard-fat-burn" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline),
            "standard-incline-walk" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxIncline),
            "standard-glutes" to setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxIncline),
        )
        all.forEach { sp ->
            assertEquals(expected.getValue(sp.id), sp.uses, sp.id)
            val d = sp.defaultParameters
            assertEquals(sp.defaultTotalDurationSeconds, d.totalDurationSeconds)
            assertEquals(IntensityParameter.EasySpeed in sp.uses, d.easySpeedKmh != null, sp.id)
            assertEquals(IntensityParameter.MaxSpeed in sp.uses, d.maxSpeedKmh != null, sp.id)
            assertEquals(IntensityParameter.MaxIncline in sp.uses, d.maxInclinePercent != null, sp.id)
            // a value the program does not use may still be given and changes nothing
            if (IntensityParameter.MaxSpeed !in sp.uses) {
                val extra = ProgramResolver.resolve(sp.program, d.copy(maxSpeedKmh = 15.0))
                assertEquals(sp.plan().blocks.map { it.speedKmh }, extra.blocks.map { it.speedKmh }, sp.id)
            }
        }
    }

    @Test
    fun `a program that does not use the maximum speed runs at the easy speed`() {
        val walk = StandardPrograms.byId("standard-incline-walk")!!.plan()
        assertTrue(walk.blocks.all { it.speedKmh == 4.8 })
        val main = walk.blocks.single { it.phase == BlockPhase.Work }
        assertEquals(12.0, main.inclinePercent)
        assertEquals(listOf(2.0, 5.0, 7.0, 10.0), walk.blocks.filter { it.phase == BlockPhase.WarmUp }.map { it.inclinePercent })
        assertEquals(0.0, walk.blocks.last().inclinePercent)
    }

    @Test
    fun `hill climbs to the top, holds about a tenth of the main part and comes down symmetrically`() {
        val plan = StandardPrograms.byId("standard-hill")!!.plan()
        val main = plan.blocks.filter { it.phase == BlockPhase.Work }
        val inclines = main.map { it.inclinePercent }
        assertEquals(listOf(2.0, 4.0, 6.0, 8.0, 10.0, 8.0, 6.0, 4.0, 2.0), inclines)
        assertEquals(inclines, inclines.reversed())
        assertEquals(120, (main[4].end as BlockEnd.Duration).seconds)
        assertTrue(main.map { it.speedKmh }.distinct().size == 1)
    }

    @Test
    fun `rolling hills alternate rises and falls`() {
        val inclines = StandardPrograms.byId("standard-rolling-hills")!!.plan().blocks.filter { it.phase == BlockPhase.Work }.map { it.inclinePercent }
        val rises = inclines.zipWithNext { a, b -> b > a }
        assertEquals(List(9) { it % 2 == 0 }, rises)
        assertTrue(inclines.max() - inclines.min() >= 7.0)
    }

    @Test
    fun `intervals use a repeat of hard and easy`() {
        val sp = StandardPrograms.byId("standard-intervals")!!
        assertTrue(sp.program.steps.any { it is com.treadz.console.programs.model.Repeat && it.count == 5 })
        val plan = sp.plan()
        val hard = plan.blocks.filter { it.phase == BlockPhase.Work }
        val easy = plan.blocks.filter { it.phase == BlockPhase.Recovery }
        assertEquals(5, hard.size)
        assertEquals(5, easy.size)
        assertEquals(setOf(11.6), hard.map { it.speedKmh }.toSet()) // 5 + 0.95 * 7 = 11.65, which falls just under the half step
        assertEquals(setOf(6.8), easy.map { it.speedKmh }.toSet()) // 5 + 0.25 * 7
        assertTrue(plan.blocks.filter { it.repetition != null }.all { it.repetition!!.count == 5 })
    }

    @Test
    fun `pyramid and progressive speeds go the way their names say`() {
        val pyramid = StandardPrograms.byId("standard-pyramid")!!.plan().blocks.filter { it.phase == BlockPhase.Work }.map { it.speedKmh }
        assertEquals(pyramid, pyramid.reversed())
        assertEquals(pyramid.max(), pyramid[4])
        assertEquals(10.0, pyramid.max())
        assertTrue(pyramid.take(5).zipWithNext().all { (a, b) -> b > a })
        val progressive = StandardPrograms.byId("standard-progressive")!!.plan().blocks.filter { it.phase == BlockPhase.Work }.map { it.speedKmh }
        assertTrue(progressive.zipWithNext().all { (a, b) -> b > a }, "$progressive")
        assertEquals(11.0, progressive.last())
    }

    @Test
    fun `fat burn spends two thirds of the main part at a steady demanding effort`() {
        val plan = StandardPrograms.byId("standard-fat-burn")!!.plan()
        val main = plan.blocks.filter { it.phase == BlockPhase.Work }
        val steady = main.filter { it.speedKmh == main.maxOf { b -> b.speedKmh } }
        assertEquals(800, steady.sumOf { (it.end as BlockEnd.Duration).seconds })
        assertEquals(1_200, main.sumOf { (it.end as BlockEnd.Duration).seconds })
        assertTrue(steady.map { it.inclinePercent }.distinct().size > 1, "small incline variations")
    }

    @Test
    fun `glutes keep a high incline at the easy speed`() {
        val main = StandardPrograms.byId("standard-glutes")!!.plan().blocks.filter { it.phase == BlockPhase.Work }
        assertTrue(main.all { it.speedKmh == 3.5 })
        assertTrue(main.all { it.inclinePercent in 8.0..12.0 })
        assertTrue(main.map { it.inclinePercent }.distinct().size > 2)
    }

    @Test
    fun `the global adjustment works on a standard program`() {
        val plan = StandardPrograms.byId("standard-intervals")!!.plan(adjust = 10)
        assertEquals(12.8, plan.blocks.first { it.phase == BlockPhase.Work }.speedKmh) // 11.65 * 1.1 = 12.8
    }
}
