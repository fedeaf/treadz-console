package com.treadz.console.analysis.estimate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EstimateCombinerTest {

    private val rules = CombinationRules(halfLifeDays = 90.0, warningSeeFactor = 1.5)

    @Test
    fun `no inputs give no estimate`() {
        assertNull(EstimateCombiner.combine(emptyList(), rules))
    }

    @Test
    fun `a single input is returned as it is with its own SEE and full weight`() {
        val c = EstimateCombiner.combine(listOf(EstimateInput(40.0, 4.85, 30.0)), rules)!!
        assertEquals(40.0, c.value, 1e-12)
        assertEquals(4.85, c.see, 1e-12)
        assertEquals(listOf(CombinedInput(4.85, 1.0)), c.inputs)
    }

    @Test
    fun `inputs are weighted by inverse variance and recency`() {
        val c = EstimateCombiner.combine(
            listOf(EstimateInput(40.0, 4.85, 0.0), EstimateInput(46.0, 4.0, 90.0)),
            rules,
        )!!
        val wa = 1.0 / (4.85 * 4.85)
        val wb = 0.5 / (4.0 * 4.0) // one half-life old
        assertEquals((wa * 40.0 + wb * 46.0) / (wa + wb), c.value, 1e-12)
        assertEquals(wa / (wa + wb), c.inputs[0].weight, 1e-12)
        assertEquals(wb / (wa + wb), c.inputs[1].weight, 1e-12)
        assertEquals(1.0, c.inputs.sumOf { it.weight }, 1e-12)
        // The spread (3.27) is below the best single SEE, which is the floor.
        assertEquals(4.0, c.see, 1e-12)
    }

    @Test
    fun `a warning multiplies the SEE and lowers the weight`() {
        val c = EstimateCombiner.combine(
            listOf(EstimateInput(40.0, 4.85, 0.0), EstimateInput(50.0, 4.85, 0.0, warned = true)),
            rules,
        )!!
        assertEquals(4.85, c.inputs[0].seeUsed, 1e-12)
        assertEquals(4.85 * 1.5, c.inputs[1].seeUsed, 1e-12)
        val w1 = 1 / (4.85 * 4.85)
        val w2 = 1 / (4.85 * 1.5 * 4.85 * 1.5)
        assertEquals((w1 * 40 + w2 * 50) / (w1 + w2), c.value, 1e-12)
    }

    @Test
    fun `the uncertainty never goes below the smallest SEE used`() {
        // Weights 1/4 and 1/100: the spread, sqrt(w1^2*4 + w2^2*100) / (w1 + w2) = 1.96, is below the best SEE of 2.
        val c = EstimateCombiner.combine(listOf(EstimateInput(40.0, 2.0, 0.0), EstimateInput(44.0, 10.0, 0.0)), rules)!!
        assertEquals(2.0, c.see, 1e-12)
    }

    @Test
    fun `the order of the inputs is kept in the result`() {
        val c = EstimateCombiner.combine(
            listOf(EstimateInput(1.0, 1.0, 180.0), EstimateInput(2.0, 1.0, 0.0), EstimateInput(3.0, 1.0, 90.0)),
            rules,
        )!!
        assertEquals(listOf(0.25 / 1.75, 1.0 / 1.75, 0.5 / 1.75), c.inputs.map { it.weight })
    }
}
