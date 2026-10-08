package com.treadz.console.programs.model

import com.treadz.console.programs.ID_1
import com.treadz.console.programs.abs
import com.treadz.console.programs.block
import com.treadz.console.programs.everythingProgram
import com.treadz.console.programs.heartRateProgram
import com.treadz.console.programs.hillTemplate
import com.treadz.console.programs.norwegian4x4
import com.treadz.console.programs.rel
import com.treadz.console.programs.secs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgramValidatorTest {

    private fun issues(p: Program) = ProgramValidator.validate(p)

    private fun Program.withSteps(vararg steps: ProgramStep) = copy(steps = steps.toList())

    private fun oneBlock(end: BlockEnd = secs(60), target: BlockTarget = abs(6.0)) =
        norwegian4x4().withSteps(block(BlockPhase.Work, end, target))

    @Test
    fun `the sample programs are valid`() {
        listOf(norwegian4x4(), hillTemplate(), heartRateProgram(), everythingProgram()).forEach {
            assertEquals(emptyList<ValidationIssue>(), issues(it), it.name)
        }
    }

    @Test
    fun `a program needs a uuid, a name and at least one step`() {
        val p = norwegian4x4().copy(id = "not-a-uuid", name = "  ", steps = emptyList())
        val paths = issues(p).map { it.path }
        assertTrue("id" in paths && "name" in paths && "steps" in paths, "$paths")
    }

    @Test
    fun `standard programs may use a slug id and the others need a uuid`() {
        val standard = hillTemplate().copy(id = "standard-rolling-hills")
        assertEquals(emptyList<ValidationIssue>(), issues(standard))
        assertEquals(listOf("id"), issues(standard.copy(id = "standard-")).map { it.path })
        assertEquals(listOf("id"), issues(standard.copy(id = "Standard-Hill")).map { it.path })
        assertEquals(listOf("id"), issues(norwegian4x4().copy(id = "standard-hill")).map { it.path })
    }

    @Test
    fun `name and description have a maximum length`() {
        val p = norwegian4x4().copy(name = "x".repeat(81), description = "y".repeat(1001))
        assertEquals(listOf("name", "description"), issues(p).map { it.path })
    }

    @Test
    fun `durations and distances must be positive and within limits`() {
        assertEquals(listOf("steps[0].durationSeconds"), issues(oneBlock(secs(0))).map { it.path })
        assertEquals(listOf("steps[0].durationSeconds"), issues(oneBlock(secs(-5))).map { it.path })
        assertEquals(listOf("steps[0].durationSeconds", "steps"), issues(oneBlock(secs(13 * 3600))).map { it.path })
        assertEquals(listOf("steps[0].distanceMeters"), issues(oneBlock(BlockEnd.Distance(0))).map { it.path })
        assertEquals(listOf("steps[0].distanceMeters"), issues(oneBlock(BlockEnd.Distance(5))).map { it.path })
        assertEquals(emptyList<ValidationIssue>(), issues(oneBlock(BlockEnd.Distance(10))))
    }

    @Test
    fun `repeat count and content are limited`() {
        fun repeat(count: Int, blocks: List<Block>) = norwegian4x4().withSteps(Repeat(count, blocks))
        val b = block(BlockPhase.Work, secs(60), abs(6.0))
        assertEquals(listOf("steps[0].count"), issues(repeat(0, listOf(b))).map { it.path })
        assertEquals(listOf("steps[0].count"), issues(repeat(51, listOf(b))).map { it.path })
        assertEquals(listOf("steps[0].blocks"), issues(repeat(2, emptyList())).map { it.path })
        assertEquals(listOf("steps[0].blocks"), issues(repeat(2, List(21) { b })).map { it.path })
        assertEquals(emptyList<ValidationIssue>(), issues(repeat(50, List(8) { b })))
        // 50 x 8 = 400 blocks is the maximum once expanded
        assertEquals(listOf("steps"), issues(norwegian4x4().withSteps(Repeat(50, List(8) { b }), b)).map { it.path })
    }

    @Test
    fun `too many steps and too long programs are rejected`() {
        val b = block(BlockPhase.Work, secs(60), abs(6.0))
        assertEquals(listOf("steps"), issues(norwegian4x4().copy(steps = List(101) { b })).map { it.path })
        val long = block(BlockPhase.Work, secs(12 * 3600), abs(6.0))
        assertEquals(listOf("steps"), issues(norwegian4x4().copy(steps = listOf(long, b))).map { it.path })
    }

    @Test
    fun `absolute targets must be inside the treadmill range`() {
        assertEquals(listOf("steps[0].target.speedKmh"), issues(oneBlock(target = abs(0.4))).map { it.path })
        assertEquals(listOf("steps[0].target.speedKmh"), issues(oneBlock(target = abs(20.1))).map { it.path })
        assertEquals(listOf("steps[0].target.inclinePercent"), issues(oneBlock(target = abs(6.0, 18.5))).map { it.path })
        assertEquals(listOf("steps[0].target.inclinePercent"), issues(oneBlock(target = abs(6.0, -1.0))).map { it.path })
        assertEquals(listOf("steps[0].target.speedKmh"), issues(oneBlock(target = abs(Double.NaN))).map { it.path })
        assertEquals(emptyList<ValidationIssue>(), issues(oneBlock(target = abs(20.0, 18.0))))
    }

    @Test
    fun `relative targets are percentages from 0 to 100`() {
        assertEquals(listOf("steps[0].target.speedPercent"), issues(oneBlock(target = rel(101.0))).map { it.path })
        assertEquals(listOf("steps[0].target.inclinePercent"), issues(oneBlock(target = rel(50.0, -0.1))).map { it.path })
        assertEquals(emptyList<ValidationIssue>(), issues(oneBlock(target = rel(0.0, 100.0))))
    }

    @Test
    fun `personalised programs need an origin`() {
        val p = norwegian4x4(kind = ProgramKind.Personalised)
        assertEquals(listOf("origin"), issues(p).map { it.path })
        assertEquals(emptyList<ValidationIssue>(), issues(p.copy(origin = ProgramOrigin("g", 1))))
        assertEquals(listOf("origin.generatorId", "origin.generatorVersion"), issues(p.copy(origin = ProgramOrigin(" ", 0))).map { it.path })
    }

    @Test
    fun `heart-rate zone and bounds are checked`() {
        fun hr(zone: HeartRateZone = HeartRateZone(HeartRateBasis.MaxHeartRate, 70, 80), min: Double = 6.0, max: Double = 10.0, start: BlockTarget.Fixed = abs(8.0)) =
            oneBlock(target = BlockTarget.HeartRate(zone, HeartRateAdjust.Speed, min, max, start))
        assertEquals(emptyList<ValidationIssue>(), issues(hr()))
        assertEquals(listOf("steps[0].target.zone"), issues(hr(zone = HeartRateZone(HeartRateBasis.MaxHeartRate, 80, 70))).map { it.path })
        assertEquals(listOf("steps[0].target.zone.highPercent"), issues(hr(zone = HeartRateZone(HeartRateBasis.MaxHeartRate, 80, 101))).map { it.path })
        assertEquals(listOf("steps[0].target.bounds"), issues(hr(min = 10.0, max = 6.0)).map { it.path })
        assertEquals(listOf("steps[0].target.bounds"), issues(hr(min = 6.0, max = 25.0)).map { it.path })
        assertEquals(listOf("steps[0].target.start"), issues(hr(start = abs(11.0))).map { it.path })
        // a relative start means relative bounds
        assertEquals(emptyList<ValidationIssue>(), issues(hr(min = 20.0, max = 80.0, start = rel(50.0))))
        assertEquals(listOf("steps[0].target.bounds"), issues(hr(min = 20.0, max = 180.0, start = rel(50.0))).map { it.path })
    }

    @Test
    fun `an unknown future schema version is invalid for the model`() {
        assertEquals(listOf("schemaVersion"), issues(norwegian4x4().copy(schemaVersion = 2)).map { it.path })
        assertEquals(listOf("schemaVersion"), issues(norwegian4x4().copy(schemaVersion = 0)).map { it.path })
    }

    @Test
    fun `requireValid throws with every issue`() {
        val e = assertThrows(InvalidProgramException::class.java) {
            ProgramValidator.requireValid(norwegian4x4().copy(id = "x", name = ""))
        }
        assertEquals(2, e.issues.size)
        assertTrue(e.message!!.contains("id") && e.message!!.contains("name"))
    }

    @Test
    fun `expanded block count counts repeats`() {
        assertEquals(1 + 8 + 1, norwegian4x4().expandedBlockCount)
        assertEquals(ID_1, norwegian4x4().id)
    }
}
