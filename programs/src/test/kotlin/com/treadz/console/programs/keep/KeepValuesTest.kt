package com.treadz.console.programs.keep

import com.treadz.console.programs.ID_1
import com.treadz.console.programs.abs
import com.treadz.console.programs.block
import com.treadz.console.programs.engine.BlockChangeReason
import com.treadz.console.programs.engine.EndAction
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.engine.RunOptions
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.meters
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.model.ValidationIssue
import com.treadz.console.programs.norwegian4x4
import com.treadz.console.programs.personalised.GeneratedSessionRecords
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.ResolutionEnvironment
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.plan.WarningCode
import com.treadz.console.programs.secs
import com.treadz.console.treadmill.recording.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Keep" values: a block that leaves its speed or incline empty starts with the value in force (model, JSON, validation, resolver). */
class KeepValuesTest {

    private fun keep(speed: Double? = null, incline: Double? = null) = BlockTarget.Absolute(speed, incline)

    private fun program(vararg steps: ProgramStep) = Program(ID_1, "Keep", ProgramKind.Free, steps.toList())

    private fun hr(adjust: HeartRateAdjust, min: Double, max: Double, start: BlockTarget.Absolute) =
        BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), adjust, min, max, start)

    /** Heart-rate walk, then a cool-down that carries on with whatever the control left, lowering nothing by itself. */
    private fun coolDownKeeps() = program(
        block(BlockPhase.WarmUp, secs(300), abs(5.0, 1.0)),
        block(BlockPhase.Work, secs(1200), hr(HeartRateAdjust.Speed, 5.0, 8.0, abs(7.0, 3.0))),
        block(BlockPhase.CoolDown, secs(300), keep()),
    )

    private fun issues(p: Program): List<ValidationIssue> = ProgramValidator.validate(p)

    private fun targetMap(text: String, step: Int, inRepeat: Int? = null): Map<*, *> {
        val s = (Json.parseObject(text)["steps"] as List<*>)[step] as Map<*, *>
        val b = if (inRepeat == null) s else (s["blocks"] as List<*>)[inRepeat] as Map<*, *>
        return b["target"] as Map<*, *>
    }

    // ------------------------------------------------------------------ model

    @Test
    fun `a target says which quantities it keeps`() {
        assertFalse(abs(6.0, 1.0).keepsSpeed || abs(6.0, 1.0).keepsIncline)
        assertTrue(keep(incline = 1.0).keepsSpeed)
        assertFalse(keep(incline = 1.0).keepsIncline)
        assertTrue(keep(speed = 6.0).keepsIncline)
        val h = hr(HeartRateAdjust.Speed, 5.0, 8.0, keep(incline = 1.0))
        assertTrue(h.keepsSpeed)
        assertFalse(h.keepsIncline)
        assertFalse(BlockTarget.Relative(10.0, 10.0).keepsSpeed)
    }

    @Test
    fun `the schema version is the lowest one that holds the program`() {
        assertEquals(1, norwegian4x4().schemaVersion)
        assertEquals(2, coolDownKeeps().schemaVersion)
        assertFalse(norwegian4x4().usesKeep)
        assertTrue(coolDownKeeps().usesKeep)
        val inRepeat = program(block(BlockPhase.Work, secs(60), abs(8.0)), Repeat(2, listOf(block(BlockPhase.Work, secs(60), abs(9.0)), block(BlockPhase.Recovery, secs(60), keep(incline = 0.0)))))
        assertEquals(2, inRepeat.schemaVersion)
    }

    // ------------------------------------------------------------------ JSON

    @Test
    fun `a kept value is an absent field and the document is version 2`() {
        val p = coolDownKeeps()
        val text = ProgramJson.encode(p)
        assertEquals(p, ProgramJson.decode(text))
        assertEquals(text, ProgramJson.encode(ProgramJson.decode(text)))
        assertEquals(2L, Json.parseObject(text)["schemaVersion"])
        // The cool-down keeps both: nothing but the type
        assertEquals(mapOf("type" to "absolute"), targetMap(text, 2))
        // The heart-rate block's start is complete
        assertEquals(setOf("type", "speedKmh", "inclinePercent"), (targetMap(text, 1)["start"] as Map<*, *>).keys)
    }

    @Test
    fun `only one quantity may be absent`() {
        val p = program(block(BlockPhase.Work, secs(60), abs(8.0, 2.0)), block(BlockPhase.CoolDown, secs(60), keep(incline = 0.0)), block(BlockPhase.CoolDown, secs(60), keep(speed = 4.5)))
        val text = ProgramJson.encode(p)
        assertEquals(p, ProgramJson.decode(text))
        assertEquals(setOf("type", "inclinePercent"), targetMap(text, 1).keys)
        assertEquals(setOf("type", "speedKmh"), targetMap(text, 2).keys)
    }

    @Test
    fun `kept values inside repeats and in the start of a heart-rate block round trip`() {
        val p = program(
            block(BlockPhase.WarmUp, secs(120), abs(6.0, 1.0)),
            Repeat(
                3,
                listOf(
                    block(BlockPhase.Work, secs(90), hr(HeartRateAdjust.Incline, 0.0, 8.0, keep(speed = 8.0))),
                    block(BlockPhase.Recovery, secs(60), keep(incline = 0.0)),
                ),
            ),
        )
        val text = ProgramJson.encode(p)
        assertEquals(p, ProgramJson.decode(text))
        val start = targetMap(text, 1, 0)["start"] as Map<*, *>
        assertEquals(setOf("type", "speedKmh"), start.keys)
        assertEquals(2L, Json.parseObject(text)["schemaVersion"])
    }

    @Test
    fun `a program that keeps nothing is still written as version 1, byte for byte as before`() {
        val p = program(block(BlockPhase.Work, secs(60), abs(8.0, 2.0)))
        val expected = """
            {
              "schemaVersion": 1,
              "id": "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01",
              "name": "Keep",
              "kind": "free",
              "steps": [
                {
                  "type": "block",
                  "phase": "work",
                  "durationSeconds": 60,
                  "target": {"type": "absolute", "speedKmh": 8.0, "inclinePercent": 2.0}
                }
              ]
            }
        """.trimIndent()
        assertEquals(expected, ProgramJson.encode(p).trimEnd())
        assertEquals(1, ProgramJson.decode(expected).schemaVersion)
    }

    @Test
    fun `the version follows the content even when the program object carries an older one`() {
        val old = coolDownKeeps().copy(schemaVersion = 1)
        assertTrue(ProgramJson.encode(old).contains("\"schemaVersion\": 2"))
    }

    @Test
    fun `a program that no longer keeps anything is written as version 1 again`() {
        val kept = coolDownKeeps()
        val explicit = kept.copy(steps = kept.steps.map { step ->
            if (step is Block && step.target is BlockTarget.Absolute) step.copy(target = BlockTarget.Absolute(5.0, 0.0)) else step
        })
        assertEquals(2, explicit.schemaVersion, "copy keeps the version it was built with")
        assertFalse(explicit.usesKeep)
        assertTrue(ProgramJson.encode(explicit).contains("\"schemaVersion\": 1"))
    }

    @Test
    fun `documents written before keep values still parse`() {
        val text = """{"schemaVersion":1,"id":"$ID_1","name":"Old","kind":"free","steps":[
            {"phase":"work","durationSeconds":60,"target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]}"""
        val p = ProgramJson.decode(text)
        assertEquals(1, p.schemaVersion)
        assertEquals(BlockTarget.Absolute(8.0, 2.0), (p.steps[0] as Block).target)
    }

    @Test
    fun `a document newer than this code is rejected`() {
        val text = ProgramJson.encode(coolDownKeeps()).replace("\"schemaVersion\": 2", "\"schemaVersion\": ${Program.SCHEMA_VERSION + 1}")
        val e = assertThrows(com.treadz.console.programs.json.ProgramFormatException::class.java) { ProgramJson.decode(text) }
        assertTrue(e.message!!.contains("newer"), e.message)
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun `the first block cannot keep a value`() {
        val both = program(block(BlockPhase.Work, secs(60), keep()), block(BlockPhase.Work, secs(60), abs(8.0)))
        assertEquals(listOf("steps[0].target.speedKmh", "steps[0].target.inclinePercent"), issues(both).map { it.path })
        assertTrue(issues(both).all { it.message == ProgramValidator.FIRST_BLOCK_KEEPS })

        val speedOnly = program(block(BlockPhase.Work, secs(60), keep(incline = 1.0)))
        assertEquals(listOf("steps[0].target.speedKmh"), issues(speedOnly).map { it.path })
        val inclineOnly = program(block(BlockPhase.Work, secs(60), keep(speed = 6.0)))
        assertEquals(listOf("steps[0].target.inclinePercent"), issues(inclineOnly).map { it.path })
    }

    @Test
    fun `the first block of a leading repeat cannot keep either`() {
        val p = program(Repeat(2, listOf(block(BlockPhase.Work, secs(60), keep(incline = 2.0)), block(BlockPhase.Recovery, secs(60), keep()))))
        assertEquals(listOf("steps[0].blocks[0].target.speedKmh"), issues(p).map { it.path })
    }

    @Test
    fun `the first block cannot keep through a heart-rate start`() {
        val p = program(block(BlockPhase.Work, secs(600), hr(HeartRateAdjust.Speed, 5.0, 8.0, keep(incline = 2.0))))
        assertEquals(listOf("steps[0].target.start.speedKmh"), issues(p).map { it.path })
    }

    @Test
    fun `the resolver refuses a program whose first block keeps`() {
        val p = program(block(BlockPhase.Work, secs(60), keep(speed = 6.0)))
        val e = assertThrows(InvalidProgramException::class.java) { ProgramResolver.resolve(p) }
        assertEquals("steps[0].target.inclinePercent", e.issues.single().path)
    }

    @Test
    fun `any other block may keep, inside repeats too`() {
        val p = program(
            block(BlockPhase.WarmUp, secs(60), abs(6.0, 1.0)),
            block(BlockPhase.Work, secs(60), keep()),
            Repeat(
                2,
                listOf(
                    block(BlockPhase.Work, secs(60), keep(incline = 4.0)),
                    block(BlockPhase.Recovery, secs(60), keep(speed = 5.0)),
                    block(BlockPhase.Work, secs(300), hr(HeartRateAdjust.Speed, 5.0, 8.0, keep())),
                ),
            ),
            block(BlockPhase.CoolDown, secs(60), keep()),
        )
        assertEquals(emptyList<ValidationIssue>(), issues(p))
        // A repeat that does not open the program may open with a kept value as well
        assertEquals(emptyList<ValidationIssue>(), issues(coolDownKeeps()))
    }

    @Test
    fun `a kept start does not take part in the bounds check, a given one still does`() {
        fun p(start: BlockTarget.Absolute) = program(
            block(BlockPhase.WarmUp, secs(60), abs(6.0)),
            block(BlockPhase.Work, secs(600), hr(HeartRateAdjust.Speed, 5.0, 8.0, start)),
        )
        assertEquals(emptyList<ValidationIssue>(), issues(p(keep(incline = 1.0))))
        assertEquals(listOf("steps[1].target.start"), issues(p(abs(9.0, 1.0))).map { it.path })
    }

    @Test
    fun `values that are given are still checked`() {
        val p = program(block(BlockPhase.WarmUp, secs(60), abs(6.0)), block(BlockPhase.Work, secs(60), keep(speed = 99.0)))
        assertEquals(listOf("steps[1].target.speedKmh"), issues(p).map { it.path })
    }

    // ------------------------------------------------------------------ resolver

    private fun resolve(p: Program, sp: StartParameters = StartParameters(), env: ResolutionEnvironment = ResolutionEnvironment()): ResolvedPlan =
        ProgramResolver.resolve(p, sp, env)

    @Test
    fun `a kept value is expected to be the one the previous block ends with`() {
        val plan = resolve(
            program(
                block(BlockPhase.Work, secs(600), abs(8.0, 3.0)),
                block(BlockPhase.Work, secs(300), keep(incline = 0.0)),
                block(BlockPhase.CoolDown, secs(300), keep()),
            ),
        )
        assertEquals(listOf(8.0, 8.0, 8.0), plan.blocks.map { it.speedKmh })
        assertEquals(listOf(3.0, 0.0, 0.0), plan.blocks.map { it.inclinePercent })
        assertEquals(listOf(false, true, true), plan.blocks.map { it.keepsSpeed })
        assertEquals(listOf(false, false, true), plan.blocks.map { it.keepsIncline })
        assertTrue(plan.warnings.isEmpty(), "${plan.warnings}")
    }

    @Test
    fun `the global adjustment and the clamps apply to the planned values, not twice to a kept one`() {
        val p = program(block(BlockPhase.Work, secs(600), abs(10.0, 2.0)), block(BlockPhase.CoolDown, secs(300), keep()))
        val plan = resolve(p, StartParameters(intensityAdjustmentPercent = -20))
        assertEquals(listOf(8.0, 8.0), plan.blocks.map { it.speedKmh })
        // A safety limit lowers the first value; the kept one follows it, without a warning of its own
        val limited = resolve(p, env = ResolutionEnvironment(limits = com.treadz.console.treadmill.SafetyLimits(maxSpeedKmh = 9.0)))
        assertEquals(listOf(9.0, 9.0), limited.blocks.map { it.speedKmh })
        assertEquals(listOf(0), limited.warnings.filter { it.code == WarningCode.SpeedClamped }.map { it.blockIndex })
    }

    @Test
    fun `after a heart-rate block the expected value is its starting one`() {
        val plan = resolve(coolDownKeeps())
        assertEquals(7.0, plan.blocks[1].speedKmh)
        assertEquals(7.0, plan.blocks[2].speedKmh)
        assertEquals(3.0, plan.blocks[2].inclinePercent)
        assertTrue(plan.blocks[2].keepsSpeed && plan.blocks[2].keepsIncline)
        assertNull(plan.blocks[2].heartRate)
    }

    @Test
    fun `a heart-rate block that keeps its start is expected to begin brought within its bounds`() {
        val p = program(
            block(BlockPhase.WarmUp, secs(120), abs(12.0, 1.0)),
            block(BlockPhase.Work, secs(600), hr(HeartRateAdjust.Speed, 7.0, 9.0, keep(incline = 2.0))),
            block(BlockPhase.Work, secs(600), hr(HeartRateAdjust.Incline, 0.0, 6.0, keep(speed = 6.0))),
        )
        val plan = resolve(p)
        val bounded = plan.blocks[1]
        assertEquals(9.0, bounded.speedKmh)
        assertTrue(bounded.keepsSpeed && !bounded.keepsIncline)
        assertEquals(2.0, bounded.inclinePercent)
        assertEquals(7.0, bounded.heartRate!!.minimum)
        // Incline kept in the next one: the 2 % of the previous block, inside 0..6
        assertEquals(2.0, plan.blocks[2].inclinePercent)
        assertTrue(plan.blocks[2].keepsIncline && !plan.blocks[2].keepsSpeed)
        assertTrue(plan.warnings.none { it.code == WarningCode.SpeedClamped || it.code == WarningCode.InclineClamped }, "${plan.warnings}")
    }

    @Test
    fun `kept values inside a repeat follow the previous block of each repetition`() {
        val p = program(
            block(BlockPhase.WarmUp, secs(120), abs(6.0, 1.0)),
            Repeat(
                2,
                listOf(
                    block(BlockPhase.Work, secs(60), keep(incline = 5.0)),
                    block(BlockPhase.Work, secs(60), abs(9.0, 4.0)),
                    block(BlockPhase.Recovery, secs(60), keep()),
                ),
            ),
        )
        val plan = resolve(p)
        assertEquals(listOf(6.0, 6.0, 9.0, 9.0, 9.0, 9.0, 9.0), plan.blocks.map { it.speedKmh })
        assertEquals(listOf(1.0, 5.0, 4.0, 4.0, 5.0, 4.0, 4.0), plan.blocks.map { it.inclinePercent })
        assertEquals(listOf(false, true, false, true, true, false, true), plan.blocks.map { it.keepsSpeed })
    }

    @Test
    fun `estimates use the expected values`() {
        val p = program(
            block(BlockPhase.Work, secs(600), abs(9.0, 1.0)),
            block(BlockPhase.Work, meters(1000), keep(incline = 0.0)),
            block(BlockPhase.CoolDown, secs(300), keep()),
        )
        val plan = resolve(p)
        // 1000 m at the expected 9 km/h
        assertEquals(400, plan.blocks[1].estimatedDurationSeconds)
        assertEquals(1300, plan.estimatedDurationSeconds)
        assertEquals(Math.round(9.0 / 3.6 * 900 + 1000.0).toInt(), plan.estimatedDistanceMeters)
    }

    @Test
    fun `required reference values do not change for programs that keep`() {
        assertTrue(ProgramResolver.requiredParameters(coolDownKeeps()).isEmpty())
    }

    // ------------------------------------------------------------------ plan JSON

    @Test
    fun `a plan with kept blocks is version 2 and says which values are expected`() {
        val plan = resolve(coolDownKeeps())
        assertEquals(2, plan.planSchemaVersion)
        assertEquals(2, plan.source.programSchemaVersion)
        val text = PlanJson.encode(plan)
        assertEquals(plan, PlanJson.decode(text))
        assertEquals(text, PlanJson.encode(PlanJson.decode(text)))
        val root = Json.parseObject(text)
        assertEquals(2L, root["planSchemaVersion"])
        val blocks = (root["blocks"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(null, null, true), blocks.map { it["keepsSpeed"] })
        assertEquals(listOf(null, null, true), blocks.map { it["keepsIncline"] })
        assertEquals(7.0, blocks[2]["speedKmh"], "the expected value stays in the copy")
    }

    @Test
    fun `a plan that keeps nothing stays version 1 and has no flags`() {
        val plan = resolve(norwegian4x4())
        assertEquals(1, plan.planSchemaVersion)
        val text = PlanJson.encode(plan)
        assertTrue(text.contains("\"planSchemaVersion\": 1"))
        assertFalse(text.contains("keeps"))
        assertTrue(PlanJson.decode(text).blocks.none { it.keepsSpeed || it.keepsIncline })
    }

    @Test
    fun `a plan newer than this code is rejected`() {
        val text = PlanJson.encode(resolve(coolDownKeeps())).replace("\"planSchemaVersion\": 2", "\"planSchemaVersion\": ${ResolvedPlan.PLAN_SCHEMA_VERSION + 1}")
        assertThrows(com.treadz.console.programs.json.ProgramFormatException::class.java) { PlanJson.decode(text) }
    }

    // ------------------------------------------------------------------ session record

    @Test
    fun `the session record starts a kept block from the targets it really began with`() {
        val plan = resolve(program(block(BlockPhase.Work, secs(60), abs(8.0, 2.0)), block(BlockPhase.CoolDown, secs(60), keep())))
        val t0 = 1_000_000L
        val finished = ProgramEvent.Finished(t0 + 120_000, 120, RunOutcome.Completed, EndAction.StopBelt, null, emptyList())
        val events = listOf(
            ProgramEvent.Started(t0, 0, plan, RunOptions()),
            ProgramEvent.BlockStarted(t0, 0, 0, plan.blocks[0], BlockChangeReason.ProgramStart, 8.0, 2.0),
            ProgramEvent.BlockCompleted(t0 + 60_000, 60, 0, 60, 120, BlockChangeReason.Completed),
            // Lowered by hand during the first block: the cool-down carries on from there
            ProgramEvent.BlockStarted(t0 + 60_000, 60, 1, plan.blocks[1], BlockChangeReason.Completed, 6.5, 1.0),
            ProgramEvent.BlockCompleted(t0 + 120_000, 120, 1, 60, 100, BlockChangeReason.Completed),
            finished,
        )
        val record = GeneratedSessionRecords.build(com.treadz.console.programs.model.ProgramOrigin("gen", 1), finished, events)
        val cool = record.blocks[1]
        assertEquals(8.0, cool.plannedSpeedKmh, "the plan only expected the previous value")
        assertEquals(6.5, cool.finalSpeedKmh)
        assertEquals(1.0, cool.finalInclinePercent)
        assertEquals("1", cool.key)
    }
}
