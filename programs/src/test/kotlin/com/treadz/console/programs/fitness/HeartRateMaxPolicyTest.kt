package com.treadz.console.programs.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeartRateMaxPolicyTest {

    private val day = 86_400_000L
    private val estimated = 180
    private fun measured(bpm: Int, at: Long = 100 * day) = HrMaxEstimate(bpm, "result-1", at)

    @Test
    fun `no manual value and no test - the estimate`() {
        val d = HeartRateMaxPolicy.resolve(manualBpm = null, manualSetAtMillis = null, measured = null, estimatedBpm = estimated)
        assertEquals(HeartRateMaxDecision(180, HeartRateMaxOrigin.Estimated, null), d)
        assertFalse(d.askUser)
    }

    @Test
    fun `no manual value - the measured maximum replaces the estimate silently, higher or lower`() {
        val lower = HeartRateMaxPolicy.resolve(null, null, measured(168), estimated)
        assertEquals(168, lower.bpm)
        assertEquals(HeartRateMaxOrigin.Measured, lower.origin)
        assertEquals(measured(168), lower.measured)
        assertFalse(lower.askUser)
        assertNull(lower.differenceBpm)
        val higher = HeartRateMaxPolicy.resolve(null, 5 * day, measured(191), estimated)
        assertEquals(191, higher.bpm)
        assertEquals(HeartRateMaxOrigin.Measured, higher.origin)
        assertFalse(higher.askUser)
    }

    @Test
    fun `a manual value wins over the measured one and the estimate`() {
        val onlyManual = HeartRateMaxPolicy.resolve(185, 10 * day, null, estimated)
        assertEquals(HeartRateMaxDecision(185, HeartRateMaxOrigin.Manual, null), onlyManual)
        val both = HeartRateMaxPolicy.resolve(185, 10 * day, measured(170), estimated)
        assertEquals(185, both.bpm)
        assertEquals(HeartRateMaxOrigin.Manual, both.origin)
        assertEquals(-15, both.differenceBpm)
    }

    @Test
    fun `asks when a newer measured value differs by more than 3 bpm, in either direction`() {
        assertTrue(HeartRateMaxPolicy.resolve(185, 10 * day, measured(170, 100 * day), estimated).askUser)
        assertTrue(HeartRateMaxPolicy.resolve(185, 10 * day, measured(192, 100 * day), estimated).askUser)
        assertTrue(HeartRateMaxPolicy.resolve(185, 10 * day, measured(189, 100 * day), estimated).askUser, "4 bpm is more than 3")
        assertTrue(HeartRateMaxPolicy.resolve(185, 10 * day, measured(181, 100 * day), estimated).askUser)
        val d = HeartRateMaxPolicy.resolve(185, 10 * day, measured(170, 100 * day), estimated)
        assertEquals(measured(170, 100 * day), d.measured)
        assertEquals(-15, d.differenceBpm)
    }

    @Test
    fun `does not ask for a difference of 3 bpm or less`() {
        assertFalse(HeartRateMaxPolicy.resolve(185, 10 * day, measured(188), estimated).askUser)
        assertFalse(HeartRateMaxPolicy.resolve(185, 10 * day, measured(182), estimated).askUser)
        assertFalse(HeartRateMaxPolicy.resolve(185, 10 * day, measured(185), estimated).askUser)
        assertEquals(3, HeartRateMaxPolicy.resolve(185, 10 * day, measured(188), estimated).differenceBpm)
    }

    @Test
    fun `does not ask when the manual value is newer than the test or was confirmed after it`() {
        assertFalse(HeartRateMaxPolicy.resolve(185, 200 * day, measured(170, 100 * day), estimated).askUser, "entered after the test")
        assertFalse(HeartRateMaxPolicy.resolve(185, 100 * day, measured(170, 100 * day), estimated).askUser, "same moment: not newer")
        // "Keep my value": the UI stores the confirmation time, the same test no longer triggers the question...
        val confirmedAt = 120 * day
        assertFalse(HeartRateMaxPolicy.resolve(185, confirmedAt, measured(170, 100 * day), estimated).askUser)
        // ...but a later test that measures something different does.
        assertTrue(HeartRateMaxPolicy.resolve(185, confirmedAt, measured(171, 130 * day), estimated).askUser)
    }

    @Test
    fun `an unknown manual date counts as older - any difference above 3 bpm asks`() {
        assertTrue(HeartRateMaxPolicy.resolve(185, null, measured(170), estimated).askUser)
        assertFalse(HeartRateMaxPolicy.resolve(185, null, measured(186), estimated).askUser)
    }

    @Test
    fun `answering yes clears the manual value and the measured one is used`() {
        val before = HeartRateMaxPolicy.resolve(185, 10 * day, measured(170), estimated)
        assertTrue(before.askUser)
        val after = HeartRateMaxPolicy.resolve(null, null, before.measured, estimated)
        assertEquals(170, after.bpm)
        assertEquals(HeartRateMaxOrigin.Measured, after.origin)
        assertFalse(after.askUser)
    }

    @Test
    fun `takes the measured value from the fitness profile`() {
        val profile = FitnessProfile(computedAtMillis = 0, vo2max = null, mas = null, hrMax = measured(172))
        val d = HeartRateMaxPolicy.resolve(null, null, profile.hrMax, estimated)
        assertEquals(172, d.bpm)
        assertEquals(HeartRateMaxOrigin.Measured, d.origin)
    }
}
