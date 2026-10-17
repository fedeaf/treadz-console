package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FitnessProfileCalculatorTest {

    // ------------------------------------------------------------------ fitness profile

    private val day = 86_400_000L
    private val now = FT0 + 400 * day

    private fun ebbeling(id: String, daysAgo: Int, vo2: Double, flags: List<FitnessTestFlag> = emptyList(), validity: Validity = Validity.Valid) =
        sampleResult(FitnessTestType.EbbelingWalk, id, now - daysAgo * day, flags, validity).let {
            it.copy(outputs = it.outputs?.copy(vo2maxMlKgMin = vo2))
        }

    private fun maxTest(
        id: String,
        daysAgo: Int,
        mas: Double,
        effort: EffortAssessment = EffortAssessment.Maximal,
        peak: Int? = 182,
        flags: List<FitnessTestFlag> = emptyList(),
        lowerBound: Boolean = false,
    ) = sampleResult(FitnessTestType.MaxIncrementalRun, id, now - daysAgo * day, flags).let {
        it.copy(
            outputs = it.outputs?.copy(
                vo2maxMlKgMin = FitnessFormulas.round1(3.5 * mas), masKmh = mas, effort = effort, hrMaxCandidateBpm = peak,
                masIsLowerBound = lowerBound, vo2maxIsLowerBound = lowerBound,
            ),
        )
    }

    @Test
    fun `VO2max is weighted by inverse variance and recency`() {
        val results = listOf(ebbeling("a", 0, 40.0), maxTest("b", 90, 46.0 / 3.5))
        val p = FitnessProfileCalculator.compute(results, now, 40, Sex.Male)
        val wa = 1.0 / (4.85 * 4.85)
        val wb = 0.5 / (4.0 * 4.0) // one half-life old
        val expected = (wa * 40.0 + wb * 46.0) / (wa + wb)
        assertEquals(42.54, expected, 0.01, "hand computation")
        val vo2 = p.vo2max!!
        assertEquals(FitnessFormulas.round1(expected), vo2.valueMlKgMin)
        // sqrt(wa^2*4.85^2 + wb^2*4^2) / (wa + wb) = 3.27, floored at the best single SEE (4.0).
        assertEquals(4.0, vo2.seeMlKgMin)
        assertEquals(listOf("a", "b"), vo2.sources.map { it.resultId })
        assertEquals(FitnessFormulas.round2(wa / (wa + wb)), vo2.sources[0].weight)
        assertEquals(90, vo2.sources[1].ageDays)
        assertEquals(Vo2MaxCategory.Good, vo2.classification!!.category)
    }

    @Test
    fun `the combined uncertainty shrinks with independent methods but never below the best single SEE`() {
        val p = FitnessProfileCalculator.compute(listOf(ebbeling("a", 0, 40.0), ebbeling("b", 0, 42.0), ebbeling("c", 0, 44.0)), now)
        assertEquals(42.0, p.vo2max!!.valueMlKgMin)
        assertEquals(4.85, p.vo2max!!.seeMlKgMin, "three tests of the same method do not average out a personal bias")
        assertNull(p.vo2max!!.classification, "no age and sex given")
    }

    @Test
    fun `invalid, too old and future results`() {
        val results = listOf(
            ebbeling("valid", 10, 40.0),
            ebbeling("invalid", 1, 60.0, listOf(FitnessTestFlag.NoSteadyState), Validity.Invalid),
            ebbeling("old", 366, 60.0),
            ebbeling("future", -2, 40.0),
        )
        val p = FitnessProfileCalculator.compute(results, now)
        assertEquals(listOf("future", "valid"), p.vo2max!!.sources.map { it.resultId })
        assertEquals(0, p.vo2max!!.sources[0].ageDays, "a test dated in the future counts as today")
        assertEquals(40.0, p.vo2max!!.valueMlKgMin)
        assertNull(FitnessProfileCalculator.compute(listOf(ebbeling("old", 400, 50.0)), now).vo2max)
    }

    @Test
    fun `results with warnings weigh less`() {
        val p = FitnessProfileCalculator.compute(
            listOf(ebbeling("plain", 0, 40.0), ebbeling("warned", 0, 50.0, listOf(FitnessTestFlag.OutsideValidatedPopulation))),
            now,
        )
        val w1 = 1 / (4.85 * 4.85)
        val w2 = 1 / (4.85 * 1.5 * 4.85 * 1.5)
        assertEquals(FitnessFormulas.round1((w1 * 40 + w2 * 50) / (w1 + w2)), p.vo2max!!.valueMlKgMin)
        assertEquals(FitnessFormulas.round2(4.85 * 1.5), p.vo2max!!.sources.first { it.resultId == "warned" }.seeMlKgMin)
    }

    @Test
    fun `MAS comes from the latest maximal test, or is estimated from VO2max`() {
        val withMax = FitnessProfileCalculator.compute(
            listOf(maxTest("old", 60, 12.0), maxTest("new", 5, 12.5, lowerBound = true, effort = EffortAssessment.NotMaximal), ebbeling("e", 1, 40.0)),
            now,
        )
        assertEquals(MasEstimate(12.5, false, true, "new", now - 5 * day, EffortAssessment.NotMaximal), withMax.mas)

        val estimated = FitnessProfileCalculator.compute(listOf(ebbeling("e", 1, 42.0)), now)
        assertEquals(MasEstimate(12.0, true, false, null, null, null), estimated.mas)
        assertNull(FitnessProfileCalculator.compute(emptyList(), now).mas)
    }

    @Test
    fun `measured maximum heart rate only from maximal efforts`() {
        val p = FitnessProfileCalculator.compute(
            listOf(
                maxTest("a", 30, 12.0, EffortAssessment.Maximal, peak = 181),
                maxTest("b", 10, 12.0, EffortAssessment.Maximal, peak = 184),
                maxTest("c", 5, 12.0, EffortAssessment.NotMaximal, peak = 190),
                maxTest("d", 2, 12.0, EffortAssessment.Unknown, peak = null),
            ),
            now,
        )
        assertEquals(HrMaxEstimate(184, "b", now - 10 * day), p.hrMax)
        assertNull(FitnessProfileCalculator.compute(listOf(maxTest("c", 5, 12.0, EffortAssessment.NotMaximal)), now).hrMax)
    }

    @Test
    fun `a measured maximum far below the age estimate is not adopted`() {
        val age = sampleResult(FitnessTestType.MaxIncrementalRun).inputs.ageYears
        val floor = FitnessFormulas.tanakaHrMax(age) - 25
        assertNull(FitnessProfileCalculator.compute(listOf(maxTest("low", 1, 12.0, peak = floor - 1)), now).hrMax)
        assertEquals(floor, FitnessProfileCalculator.compute(listOf(maxTest("ok", 1, 12.0, peak = floor)), now).hrMax?.measuredBpm)
        // The result still counts for VO2max and MAS.
        assertEquals(12.0, FitnessProfileCalculator.compute(listOf(maxTest("low", 1, 12.0, peak = floor - 1)), now).mas?.valueKmh)
    }

    // ------------------------------------------------------------------ JSON

    @Test
    fun `results survive a JSON round trip`() {
        for (r in listOf(sampleResult(FitnessTestType.EbbelingWalk), sampleResult(FitnessTestType.MaxIncrementalRun))) {
            val text = FitnessTestJson.encode(r)
            assertEquals(r, FitnessTestJson.decode(text))
            assertTrue(text.contains("\"schemaVersion\": 1"))
        }
        val invalid = sampleResult(FitnessTestType.EbbelingWalk, flags = listOf(FitnessTestFlag.HeartRateLost), validity = Validity.Invalid).copy(outputs = null)
        assertEquals(invalid, FitnessTestJson.decode(FitnessTestJson.encode(invalid)))
    }

    @Test
    fun `the result document is readable`() {
        val text = FitnessTestJson.encode(sampleResult(FitnessTestType.EbbelingWalk, flags = listOf(FitnessTestFlag.SteadyStateExtended)))
        assertTrue(text.contains("\"testType\": \"ebbelingWalk\""), text)
        assertTrue(text.contains("\"startedAt\": \"2026-10-08T10:00:00Z\""), text)
        assertTrue(text.contains("{\"code\": \"steadyStateExtended\", \"severity\": \"info\"}"), text)
        assertTrue(text.contains("\"vo2maxMlKgMin\": 41.0"), text)
        assertTrue(text.contains("\"classification\": {\"category\": \"fair\""), text)
        // One minute per line.
        assertTrue(text.lines().any { it.trim().startsWith("{\"phase\": \"stage2\", \"minute\": 1,") }, text)
        assertFalse(text.contains("null"), "absent values are left out")
    }

    @Test
    fun `unknown flags are dropped and newer versions rejected`() {
        val text = FitnessTestJson.encode(sampleResult(FitnessTestType.EbbelingWalk))
            .replace("\"flags\": []", "\"flags\": [{\"code\": \"somethingNew\", \"severity\": \"info\"}]")
        assertEquals(emptyList<FitnessTestFlag>(), FitnessTestJson.decode(text).flags)
        val newer = FitnessTestJson.encode(sampleResult(FitnessTestType.EbbelingWalk)).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val e = runCatching { FitnessTestJson.decode(newer) }.exceptionOrNull()
        assertTrue(e is com.treadz.console.programs.json.ProgramFormatException && e.path == "schemaVersion", "$e")
        val bad = FitnessTestJson.encode(sampleResult(FitnessTestType.EbbelingWalk)).replace("2026-10-08T10:00:00Z", "yesterday")
        assertEquals("startedAt", (runCatching { FitnessTestJson.decode(bad) }.exceptionOrNull() as com.treadz.console.programs.json.ProgramFormatException).path)
    }

    @Test
    fun `the fitness profile survives a JSON round trip`() {
        val p = FitnessProfileCalculator.compute(
            listOf(maxTest("m", 3, 13.0), ebbeling("e", 20, 42.0)),
            now, 40, Sex.Male,
        )
        val text = FitnessTestJson.encodeProfile(p)
        assertEquals(p, FitnessTestJson.decodeProfile(text))
        assertTrue(text.contains("\"halfLifeDays\": 90.0"), text)
        assertTrue(text.contains("\"estimated\": false"), text)
        val empty = FitnessProfileCalculator.compute(emptyList(), now)
        assertEquals(empty, FitnessTestJson.decodeProfile(FitnessTestJson.encodeProfile(empty)))
    }
}

/** A complete, hand-made result for the pure tests. */
fun sampleResult(
    type: FitnessTestType,
    id: String = RESULT_ID,
    startedAt: Long = FT0,
    flags: List<FitnessTestFlag> = emptyList(),
    validity: Validity = Validity.Valid,
): FitnessTestResult {
    val inputs = FitnessTestInputs(40, Sex.Male, 80.5, 180, 180, HeartRateSourceKind.TreadmillConsole, 4.8, 4.8, maxSpeedLimitKmh = 16.0)
    return when (type) {
        FitnessTestType.EbbelingWalk -> FitnessTestResult(
            id = id, testType = type, protocolVersion = 1,
            startedAtMillis = startedAt, effortEndedAtMillis = startedAt + 480_000, endedAtMillis = startedAt + 660_500,
            validity = validity, flags = flags, endReason = FitnessEndReason.Completed, inputs = inputs,
            measurements = EbbelingMeasurements(
                90, 126, 153, 4.8, 2.98, 240, 240, 0, 1,
                listOf(SpeedAdjustment(60, 4.4, 4.8, 88.5)), 95.4, 113.6, 1.8, 18.2,
            ),
            minutes = listOf(
                MinuteRecord(FitnessTestPhase.Stage1, 1, 60, 60, 4.8, 0.0, 92.0),
                MinuteRecord(FitnessTestPhase.Stage2, 1, 300, 60, 4.8, 5.0, 110.5),
                MinuteRecord(FitnessTestPhase.CoolDown, 3, 660, 30, 4.0, 0.0, null),
            ),
            coolDownSeconds = 180,
            outputs = FitnessTestOutputs(41.0, 4.85, Vo2MaxMethod.Ebbeling1991, classification = Vo2MaxNorms.classify(41.0, 40, Sex.Male)),
        )
        FitnessTestType.MaxIncrementalRun -> FitnessTestResult(
            id = id, testType = type, protocolVersion = 1,
            startedAtMillis = startedAt, effortEndedAtMillis = startedAt + 1_020_000, endedAtMillis = startedAt + 1_330_000,
            validity = validity, flags = flags, endReason = FitnessEndReason.Exhausted, endDetail = "detail", inputs = inputs.copy(heartRateSource = null),
            measurements = MaxTestMeasurements(
                300, 720, 8.0, 0.5, 60, 1.0, 20.0, false,
                listOf(StageRecord(1, 8.0, 1.0, 60, true, 128.0), StageRecord(2, 8.5, 1.0, 20, false, null)),
                8.0, 20, 185.0, 0.98,
            ),
            minutes = listOf(MinuteRecord(FitnessTestPhase.Incremental, 1, 360, 60, 8.0, 1.0, 128.0)),
            coolDownSeconds = 300,
            outputs = FitnessTestOutputs(
                47.3, 4.0, Vo2MaxMethod.LegerMercier1984, false, Vo2MaxNorms.classify(47.3, 40, Sex.Male),
                masKmh = 13.5, masIsLowerBound = false, hrMaxCandidateBpm = 185, effort = EffortAssessment.Maximal,
            ),
        )
        FitnessTestType.MaxHeartRateWalk -> FitnessTestResult(
            id = id, testType = type, protocolVersion = 1,
            startedAtMillis = startedAt, effortEndedAtMillis = startedAt + 780_000, endedAtMillis = startedAt + 960_000,
            validity = validity, flags = flags, endReason = FitnessEndReason.Exhausted,
            inputs = inputs.copy(heartRateSource = HeartRateSourceKind.DirectStrap, requestedStartSpeedKmh = 5.5, startSpeedKmh = 5.5),
            measurements = MaxHeartRateWalkMeasurements(
                120, 660, 5.5, 5.0, 1.0, 18.0, 0.2, 20.0, 60, false,
                listOf(StageRecord(1, 5.5, 5.0, 60, true, 131.0), StageRecord(11, 5.5, 15.0, 0, false, null)),
                183.0, 0.97, false, 4.5, 183, EffortAssessment.Maximal,
            ),
            minutes = listOf(MinuteRecord(FitnessTestPhase.Incremental, 1, 180, 60, 5.5, 5.0, 131.0)),
            coolDownSeconds = 180,
            outputs = null,
        )
    }
}
