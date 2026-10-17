package com.treadz.console.analysis.zones

import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.analysis.heartrate.HeartRateBands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrainingZonesTest {

    private fun bounds(z: ZoneSet) = z.zones.map { it.lowBpm to it.highBpm }

    @Test
    fun `measured thresholds - the reference user`() {
        val z = TrainingZones.compute(maxBpm = 181, restingBpm = 55, vt1Bpm = 132, vt2Bpm = 151, selfRated = SelfRatedFitness.Intermediate)
        assertEquals(ZoneSource.MeasuredThresholds, z.source)
        assertEquals(listOf(null to 122, 122 to 132, 132 to 151, 151 to 166, 166 to 181), bounds(z))
        assertFalse(z.vt2FromTable)
    }

    @Test
    fun `only VT1 measured - VT2 from the level table`() {
        // Medium level: Z3/Z4 at 80 % of the reserve: 55 + 0.8 * 126 = 155.8 -> 156.
        val z = TrainingZones.compute(maxBpm = 181, restingBpm = 55, vt1Bpm = 132, selfRated = SelfRatedFitness.Intermediate)
        assertEquals(ZoneSource.MeasuredThresholds, z.source)
        assertTrue(z.vt2FromTable)
        assertEquals(listOf(null to 122, 122 to 132, 132 to 156, 156 to 169, 169 to 181), bounds(z))
        // A table VT2 that is not above VT1 falls back to the middle between VT1 and the maximum.
        val high = TrainingZones.compute(maxBpm = 181, restingBpm = 55, vt1Bpm = 160, selfRated = SelfRatedFitness.Beginner)
        assertEquals(171, high.vt2Bpm)
    }

    @Test
    fun `level tables in percent of the reserve`() {
        val low = TrainingZones.compute(maxBpm = 180, restingBpm = 60, selfRated = SelfRatedFitness.Beginner)
        assertEquals(ZoneSource.SelfRatedFitness, low.source)
        assertEquals(FitnessLevel.Low, low.level)
        assertTrue(low.usedReserve)
        // 45, 55, 75, 90 % of a 120 bpm reserve above 60.
        assertEquals(listOf(null to 114, 114 to 126, 126 to 150, 150 to 168, 168 to 180), bounds(low))
        val medium = TrainingZones.compute(180, 60, selfRated = SelfRatedFitness.Intermediate)
        assertEquals(listOf(null to 120, 120 to 132, 132 to 156, 156 to 168, 168 to 180), bounds(medium))
        val high = TrainingZones.compute(180, 60, selfRated = SelfRatedFitness.Advanced)
        assertEquals(listOf(null to 126, 126 to 138, 138 to 162, 162 to 168, 168 to 180), bounds(high))
    }

    @Test
    fun `without a resting heart rate the equivalent percent of the maximum`() {
        val z = TrainingZones.compute(maxBpm = 180, restingBpm = null, selfRated = SelfRatedFitness.Intermediate)
        assertFalse(z.usedReserve)
        // 50, 60, 80, 90 % of the reserve -> 67, 73, 87, 93 % of the maximum.
        assertEquals(listOf(67, 73, 87, 93), listOf(50, 60, 80, 90).map { HeartRateBands.reserveToMaxPercent(it) })
        assertEquals(listOf(null to 121, 121 to 131, 131 to 157, 157 to 167, 167 to 180), bounds(z))
    }

    @Test
    fun `source priority - tested VO2max classification over the self-rated level, low by default`() {
        val tested = TrainingZones.compute(180, 60, testedVo2maxCategory = Vo2MaxCategory.Excellent, selfRated = SelfRatedFitness.Beginner)
        assertEquals(ZoneSource.Vo2maxClassification, tested.source)
        assertEquals(FitnessLevel.High, tested.level)
        assertEquals(FitnessLevel.Low, TrainingZones.compute(180, 60, testedVo2maxCategory = Vo2MaxCategory.Fair).level)
        assertEquals(FitnessLevel.Low, TrainingZones.compute(180, 60, testedVo2maxCategory = Vo2MaxCategory.Poor).level)
        assertEquals(FitnessLevel.Medium, TrainingZones.compute(180, 60, testedVo2maxCategory = Vo2MaxCategory.Good).level)
        assertEquals(FitnessLevel.High, TrainingZones.compute(180, 60, testedVo2maxCategory = Vo2MaxCategory.Superior).level)
        val nothing = TrainingZones.compute(180, 60)
        assertEquals(ZoneSource.Default, nothing.source)
        assertEquals(FitnessLevel.Low, nothing.level)
    }

    @Test
    fun `percent of the reserve or of the maximum of a bpm value`() {
        assertEquals(59, HeartRateBands.percentOf(132, 186, 55))
        assertEquals(73, HeartRateBands.percentOf(132, 181, null))
    }
}
