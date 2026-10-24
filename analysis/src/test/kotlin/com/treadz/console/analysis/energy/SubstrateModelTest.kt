package com.treadz.console.analysis.energy

import com.treadz.console.analysis.fitness.Sex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubstrateModelTest {

    /** The reference profile: resting 60, VT1 131, VT2 155, maximum 185. */
    private val male = FuelAnchors(60, false, 131, 155, false, 185, Sex.Male)
    private val female = male.copy(sex = Sex.Female)

    @Test
    fun `the RER is the anchor value at each anchor`() {
        assertEquals(0.79, SubstrateModel.baseRer(60.0, male), 1e-12)
        assertEquals(0.88, SubstrateModel.baseRer(131.0, male), 1e-12)
        assertEquals(0.97, SubstrateModel.baseRer(155.0, male), 1e-12)
        assertEquals(1.00, SubstrateModel.baseRer(185.0, male), 1e-12)
    }

    @Test
    fun `the RER is flat below resting and above the maximum`() {
        assertEquals(0.79, SubstrateModel.baseRer(40.0, male), 1e-12)
        assertEquals(1.00, SubstrateModel.baseRer(200.0, male), 1e-12)
    }

    @Test
    fun `between the anchors the RER is quadratic below VT1 and linear above`() {
        // Halfway from resting to VT1: x = 0.5, 0.79 + 0.09 * 0.25.
        assertEquals(0.79 + 0.09 * 0.25, SubstrateModel.baseRer(95.5, male), 1e-12)
        assertEquals(0.925, SubstrateModel.baseRer(143.0, male), 1e-12)
        assertEquals(0.985, SubstrateModel.baseRer(170.0, male), 1e-12)
    }

    @Test
    fun `Lusk table values`() {
        assertEquals(1.0, SubstrateModel.fatShare(0.707), 1e-12)
        assertEquals(0.493, SubstrateModel.fatShare(0.85), 5e-4)
        assertEquals(0.0, SubstrateModel.fatShare(1.0), 1e-12)
        // Outside the table the RER is clamped.
        assertEquals(1.0, SubstrateModel.fatShare(0.5), 1e-12)
        assertEquals(0.0, SubstrateModel.fatShare(1.2), 1e-12)
    }

    @Test
    fun `the fat share falls as the heart rate rises`() {
        var previous = 1.0
        for (bpm in 50..200) {
            val share = SubstrateModel.fatShare(SubstrateModel.rer(bpm.toDouble(), male, 30.0))
            assertTrue(share <= previous + 1e-12, "bpm $bpm: $share after $previous")
            previous = share
        }
    }

    @Test
    fun `the duration term lowers the RER by 0_0004 per moving minute and stops at 120 minutes`() {
        val h = 131.0
        assertEquals(0.88, SubstrateModel.rer(h, male, 0.0), 1e-12)
        assertEquals(0.88 - 0.024, SubstrateModel.rer(h, male, 60.0), 1e-12)
        assertEquals(0.88 - 0.048, SubstrateModel.rer(h, male, 120.0), 1e-12)
        assertEquals(SubstrateModel.rer(h, male, 120.0), SubstrateModel.rer(h, male, 600.0), 1e-12)
        // A longer effort burns more fat.
        assertTrue(SubstrateModel.fatShare(SubstrateModel.rer(h, male, 120.0)) > SubstrateModel.fatShare(SubstrateModel.rer(h, male, 0.0)))
    }

    @Test
    fun `women have a lower RER by 0_03 and so a higher fat share`() {
        assertEquals(SubstrateModel.rer(131.0, male, 20.0) - 0.03, SubstrateModel.rer(131.0, female, 20.0), 1e-12)
        assertTrue(SubstrateModel.fatShare(SubstrateModel.rer(131.0, female, 20.0)) > SubstrateModel.fatShare(SubstrateModel.rer(131.0, male, 20.0)))
    }

    @Test
    fun `the RER is clamped to 0_707 - 1_00`() {
        assertEquals(1.0, SubstrateModel.rer(185.0, male, 0.0), 1e-12)
        val extreme = FuelRules(femaleRerOffset = 0.5)
        assertEquals(0.707, SubstrateModel.rer(60.0, female, 0.0, extreme), 1e-12)
        val over = FuelRules(vt2Rer = 1.2, maxRer = 1.3)
        assertEquals(1.0, SubstrateModel.rer(185.0, male, 0.0, over), 1e-12)
    }

    @Test
    fun `anchors must be strictly increasing`() {
        assertTrue(male.increasing)
        assertTrue(!male.copy(restingBpm = 131).increasing)
        assertTrue(!male.copy(vt2Bpm = 131).increasing)
        assertTrue(!male.copy(maxBpm = 155).increasing)
    }
}
