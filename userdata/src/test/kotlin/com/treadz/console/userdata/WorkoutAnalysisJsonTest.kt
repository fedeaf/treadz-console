package com.treadz.console.userdata

import com.treadz.console.analysis.energy.FuelAnchors
import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.FuelAnalysis
import com.treadz.console.analysis.workout.FuelMinute
import com.treadz.console.analysis.workout.FuelZone
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.ThresholdEstimatesAnalysis
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.analysis.workout.WorkoutThresholdEstimate
import com.treadz.console.analysis.workout.WorkoutAnalysisContext
import com.treadz.console.analysis.workout.WorkoutAnalyzer
import com.treadz.console.analysis.fitness.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The JSON column of the analysis cache: the complete analysis survives the round trip, every section, value and reason. */
class WorkoutAnalysisJsonTest {

    private fun roundTrip(a: com.treadz.console.analysis.workout.WorkoutAnalysis) = WorkoutAnalysisJson.decode(WorkoutAnalysisJson.encode(a))

    @Test fun `an analysis with every section and every value survives the round trip`() {
        val a = fullAnalysis()
        assertEquals(a, roundTrip(a))
        // the zones used are inside the document
        val zones = roundTrip(a).zoneTime.value!!.zones
        assertEquals(5, zones.zones.size)
        assertEquals(null, zones.zones.first().lowBpm)
        assertEquals(130, zones.vt1Bpm)
    }

    @Test fun `optional values that are absent stay absent`() {
        val a = sparseAnalysis()
        val back = roundTrip(a)
        assertEquals(a, back)
        assertNull(back.signal.heartRateCoverage)
        assertNull(back.energy.value!!.grossKcal)
        assertNull(back.decoupling.value!!.zone12Fraction)
        assertNull(back.recovery.value!!.drop60Bpm)
        assertEquals(AnalysisReason.NoRrIntervals, back.alpha1.reason)
        assertNull(back.alpha1.value)
    }

    @Test fun `every reason of a missing section survives the round trip`() {
        val reasons = AnalysisReason.entries
        val seen = HashSet<AnalysisReason>()
        // eight sections per analysis: shift the list so that each reason is used in every section
        for (shift in reasons.indices) {
            val a = analysisWithReasons(reasons.drop(shift) + reasons.take(shift))
            val back = roundTrip(a)
            assertEquals(a, back)
            listOf(back.energy, back.zoneTime, back.trimp, back.efficiency, back.decoupling, back.fitnessIndex, back.recovery, back.alpha1).forEach {
                assertNull(it.value)
                seen += it.reason!!
            }
        }
        assertEquals(reasons.toSet(), seen)
    }

    @Test fun `what the analyzer produces round trips`() {
        val samples = neutralSamples(SyntheticTestWorkout.walking())
        val context = WorkoutAnalysisContext(Sex.Male, weightKg = 80.5, restingHeartRateBpm = 60, maxHeartRateBpm = 186)
        val analysis = WorkoutAnalyzer.analyse(samples, context)
        assertNotNull(analysis.energy.value)
        assertNotNull(analysis.trimp.value)
        assertNotNull("a strap with RR: the session alpha1", analysis.alpha1.value)
        assertEquals(analysis, roundTrip(analysis))
        // and without a heart rate or samples: reasons are data
        val none = WorkoutAnalyzer.analyse(emptyList(), context)
        assertEquals(AnalysisReason.NoSamples, none.energy.reason)
        assertEquals(none, roundTrip(none))
        val noHr = WorkoutAnalyzer.analyse(neutralSamples(samplesWithoutHeartRate()), context)
        assertEquals(AnalysisReason.NoHeartRate, noHr.trimp.reason)
        assertEquals(noHr, roundTrip(noHr))
    }

    @Test fun `the thresholds implied by the stable stretches round trip, and older documents read as no reference`() {
        val estimate = WorkoutThresholdEstimate(
            ThresholdKind.Vt1, 1_000L, 400_000L, 6.2, 5.0, 12, 1.07, 122.4, -0.0142, 144.9, 381,
        )
        val a = fullAnalysis().copy(
            thresholdEstimates = Section.of(ThresholdEstimatesAnalysis(2, listOf(estimate, estimate.copy(kind = ThresholdKind.Vt2, impliedBpm = 160.1)))),
        )
        assertEquals(a, roundTrip(a))
        val withoutSection = WorkoutAnalysisJson.encode(fullAnalysis()).replace(Regex(",\\s*\"thresholdEstimates\"\\s*:\\s*\\{[^}]*\\}"), "")
        assertTrue(!withoutSection.contains("thresholdEstimates"))
        assertEquals(AnalysisReason.NoThresholdReference, WorkoutAnalysisJson.decode(withoutSection).thresholdEstimates.reason)
    }

    @Test fun `the fat and carbohydrate split round trips, every reason too, and older documents read as no measured thresholds`() {
        val fuel = FuelAnalysis(
            fatKcal = 120.25, carbKcal = 196.5, fatShare = 0.3796, movingSeconds = 1800, validSeconds = 1650, validCoverage = 0.9167,
            byZone = listOf(FuelZone(1, 0, 0.0, 0.0), FuelZone(2, 1200, 98.5, 110.0), FuelZone(3, 450, 21.75, 86.5)),
            minutes = listOf(FuelMinute(1_000L, 3.5, 6.25), FuelMinute(61_000L, 4.0, 6.0)),
            anchors = FuelAnchors(58, restingAssumed = false, vt1Bpm = 131, vt2Bpm = 149, vt2FromTable = true, maxBpm = 185, sex = Sex.Female),
        )
        val a = fullAnalysis().copy(fuel = Section.of(fuel))
        assertEquals(a, roundTrip(a))
        for (reason in listOf(AnalysisReason.NoWeight, AnalysisReason.NoMeasuredThresholds, AnalysisReason.NoReliableStrapHeartRate)) {
            val missing = fullAnalysis().copy(fuel = Section.missing(reason))
            assertEquals(missing, roundTrip(missing))
        }
        val withoutSection = WorkoutAnalysisJson.encode(fullAnalysis()).replace(Regex(",\\s*\"fuel\"\\s*:\\s*\\{[^}]*\\}"), "")
        assertTrue(!withoutSection.contains("fuel"))
        assertEquals(AnalysisReason.NoMeasuredThresholds, WorkoutAnalysisJson.decode(withoutSection).fuel.reason)
    }

    @Test fun `a document that cannot be read is an error, not a wrong analysis`() {
        val good = WorkoutAnalysisJson.encode(fullAnalysis())
        listOf(
            "",
            "[]",
            "{}",
            good.replace("\"signal\"", "\"signalX\""),
            good.replace("\"BeltStopped\"", "\"Floating\""),
            good.substring(0, good.length / 2),
        ).forEach { text ->
            try {
                WorkoutAnalysisJson.decode(text)
                fail("should not be readable: $text")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, true)
            }
        }
    }
}
