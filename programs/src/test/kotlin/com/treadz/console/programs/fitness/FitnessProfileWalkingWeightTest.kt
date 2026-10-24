package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The VO2max of the maximal walking test weighs half while a valid maximal running test counts in the profile. */
class FitnessProfileWalkingWeightTest {

    private val day = 86_400_000L
    private val now = FT0 + 400 * day

    private fun walk(id: String, daysAgo: Int, vo2: Double, effort: EffortAssessment = EffortAssessment.Maximal, lowerBound: Boolean = false) =
        sampleResult(FitnessTestType.MaxHeartRateWalk, id, now - daysAgo * day).copy(
            outputs = FitnessTestOutputs(
                vo2, 3.5, Vo2MaxMethod.AcsmWalkRamp, lowerBound, Vo2MaxNorms.classify(vo2, 40, Sex.Male),
                masKmh = FitnessFormulas.round2(vo2 / 3.5), masIsLowerBound = lowerBound, hrMaxCandidateBpm = 183, effort = effort, masIsEstimated = true,
            ),
            schemaVersion = FitnessTestResult.SCHEMA_VERSION,
        )

    private fun run(id: String, daysAgo: Int, vo2: Double, effort: EffortAssessment = EffortAssessment.Maximal, lowerBound: Boolean = false) =
        sampleResult(FitnessTestType.MaxIncrementalRun, id, now - daysAgo * day).let {
            it.copy(outputs = it.outputs!!.copy(vo2maxMlKgMin = vo2, effort = effort, vo2maxIsLowerBound = lowerBound, masIsLowerBound = lowerBound))
        }

    @Test
    fun `with a valid running test the walking test weighs half - 44_1 walking and 40_9 running`() {
        val p = FitnessProfileCalculator.compute(listOf(walk("w", 5, 44.1), run("r", 5, 40.9)), now, 40, Sex.Male)
        val v = p.vo2max!!
        val walking = v.sources.single { it.resultId == "w" }
        assertEquals(0.5, walking.weightFactor)
        assertEquals(1.0, v.sources.single { it.resultId == "r" }.weightFactor)
        // Weights 0.5 / 3.5² and 1 / 4.0²: the running test, with the larger SEE, still counts for more.
        val wWalk = 0.5 / (3.5 * 3.5)
        val wRun = 1.0 / (4.0 * 4.0)
        assertEquals(FitnessFormulas.round1((wWalk * 44.1 + wRun * 40.9) / (wWalk + wRun)), v.valueMlKgMin)
        assertTrue(walking.weight < 0.5, "${walking.weight}")
        assertEquals(0.5, p.rules.walkingFactorWithRunning)
    }

    @Test
    fun `no halving without a valid maximal running test in the profile`() {
        // A running test older than the maximum age is out of the profile.
        for (running in listOf(
            run("old", 366, 40.9),
            run("bound", 5, 40.9, lowerBound = true),
            run("notMaximal", 5, 40.9, effort = EffortAssessment.NotMaximal),
            run("unknown", 5, 40.9, effort = EffortAssessment.Unknown),
        )) {
            val p = FitnessProfileCalculator.compute(listOf(walk("w", 5, 44.1), running), now, 40, Sex.Male)
            assertEquals(1.0, p.vo2max!!.sources.single { it.resultId == "w" }.weightFactor, running.id)
        }
        // A running test of 200 days still counts in the profile: the walking test is halved.
        val older = FitnessProfileCalculator.compute(listOf(walk("w", 5, 44.1), run("r", 200, 40.9)), now, 40, Sex.Male)
        assertEquals(0.5, older.vo2max!!.sources.single { it.resultId == "w" }.weightFactor)
        // Two walking tests and nothing else: both full.
        val twoWalks = FitnessProfileCalculator.compute(listOf(walk("a", 5, 44.1), walk("b", 20, 42.0)), now)
        assertTrue(twoWalks.vo2max!!.sources.all { it.weightFactor == 1.0 })
    }

    @Test
    fun `an invalid running test does not count, and Ebbeling keeps its own factor`() {
        val invalid = run("r", 5, 40.9).copy(validity = Validity.Invalid, outputs = null)
        val p = FitnessProfileCalculator.compute(listOf(walk("w", 5, 44.1), invalid), now, 40, Sex.Male)
        assertEquals(1.0, p.vo2max!!.sources.single().weightFactor)
        val all = FitnessProfileCalculator.compute(
            listOf(walk("w", 5, 44.1), run("r", 5, 40.9), sampleResult(FitnessTestType.EbbelingWalk, "e", now - 5 * day)),
            now, 40, Sex.Male,
        )
        assertEquals(0.5, all.vo2max!!.sources.single { it.resultId == "w" }.weightFactor)
        assertEquals(0.25, all.vo2max!!.sources.single { it.resultId == "e" }.weightFactor)
        assertEquals(1.0, all.vo2max!!.sources.single { it.resultId == "r" }.weightFactor)
    }

    @Test
    fun `the factor is in the rules of the stored profile and survives the JSON`() {
        val p = FitnessProfileCalculator.compute(listOf(walk("w", 5, 44.1), run("r", 5, 40.9)), now, 40, Sex.Male)
        val json = FitnessTestJson.encodeProfile(p)
        assertTrue(json.contains("\"walkingFactorWithRunning\": 0.5"), json)
        assertTrue(json.contains("\"weightFactor\": 0.5"), json)
        assertEquals(p, FitnessTestJson.decodeProfile(json))
        // A profile written before the factor existed reads with the default.
        val old = json.lines().filterNot { it.contains("walkingFactorWithRunning") }.joinToString("\n").replace(Regex(""""discrepancyFactor": 1.5,"""), """"discrepancyFactor": 1.5""")
        assertEquals(0.5, FitnessTestJson.decodeProfile(old).rules.walkingFactorWithRunning)
    }
}
