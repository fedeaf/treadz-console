package com.treadz.console.programs.personalised

import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The α1 figures of a heart-rate block in the stored session record. */
class Alpha1RecordTest {

    private fun record(alpha1Reductions: Int?, alpha1Mean: Double?, alpha1Windows: Int?) = sessionRecord(
        "zone2", mapOf("durationMinutes" to "45"), NOW,
        listOf(
            blockRecord(0, "0", BlockPhase.WarmUp),
            blockRecord(
                1, "1", BlockPhase.Work,
                heartRate = hrSummary(1, adjust = HeartRateAdjust.Incline, settled = 5.0, end = 118.0, alpha1Reductions = alpha1Reductions, alpha1Mean = alpha1Mean, alpha1Windows = alpha1Windows),
            ),
        ),
    )

    @Test
    fun `the figures survive a JSON round trip`() {
        val r = record(alpha1Reductions = 2, alpha1Mean = 0.62, alpha1Windows = 41)
        val text = GeneratedSessionJson.encode(r)
        assertTrue(text.contains("\"alpha1Reductions\": 2"), text)
        assertTrue(text.contains("\"alpha1Mean\": 0.62"), text)
        assertTrue(text.contains("\"alpha1Windows\": 41"), text)
        val back = GeneratedSessionJson.decode(text)
        assertEquals(r, back)
        val summary = checkNotNull(back.blocks[1].heartRate)
        assertEquals(2, summary.alpha1Reductions)
        assertEquals(0.62, summary.alpha1Mean)
        assertEquals(41, summary.alpha1Windows)
    }

    @Test
    fun `an active brake that never fired is zero, not absent`() {
        val r = record(alpha1Reductions = 0, alpha1Mean = 1.08, alpha1Windows = 60)
        val back = GeneratedSessionJson.decode(GeneratedSessionJson.encode(r))
        assertEquals(0, back.blocks[1].heartRate!!.alpha1Reductions)
        assertEquals(r, back)
    }

    @Test
    fun `without the figures nothing is written, and records written before them still read`() {
        val r = record(alpha1Reductions = null, alpha1Mean = null, alpha1Windows = null)
        val text = GeneratedSessionJson.encode(r)
        assertFalse(text.contains("alpha1"), text)
        val back = GeneratedSessionJson.decode(text)
        assertEquals(r, back)
        val summary = checkNotNull(back.blocks[1].heartRate)
        assertNull(summary.alpha1Reductions)
        assertNull(summary.alpha1Mean)
        assertNull(summary.alpha1Windows)
        // Only some of them present.
        val partial = GeneratedSessionJson.decode(
            GeneratedSessionJson.encode(record(alpha1Reductions = 1, alpha1Mean = null, alpha1Windows = null)),
        )
        assertEquals(1, partial.blocks[1].heartRate!!.alpha1Reductions)
        assertNull(partial.blocks[1].heartRate!!.alpha1Mean)
    }
}
