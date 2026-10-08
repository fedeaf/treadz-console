package com.treadz.console.treadmill.simulator

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Rig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedHeartRateTest {

    private fun walk(model: PhysiologicalHeartRateModel, seconds: Int, speed: Double, incline: Double, running: Boolean = true): List<Int> =
        (0 until seconds).map { model.nextSecond(SimulatedBodyInput(speed, incline, running, it.toLong())) }

    @Test
    fun `ACSM oxygen demand for walking and running`() {
        // Walking 4.8 km/h = 80 m/min: 0.1*80 + 3.5 = 11.5; at 5 %: + 1.8*80*0.05 = 7.2.
        assertEquals(11.5, PhysiologicalHeartRateModel.vo2Demand(4.8, 0.0), 1e-9)
        assertEquals(18.7, PhysiologicalHeartRateModel.vo2Demand(4.8, 5.0), 1e-9)
        // Running 12 km/h = 200 m/min at 1 %: 0.2*200 + 0.9*200*0.01 + 3.5 = 45.3.
        assertEquals(45.3, PhysiologicalHeartRateModel.vo2Demand(12.0, 1.0), 1e-9)
    }

    @Test
    fun `heart rate settles at the percent-of-reserve value with a lag`() {
        val model = PhysiologicalHeartRateModel(restingBpm = 70, maxBpm = 180, vo2maxMlKgMin = 45.0, noiseBpm = 0)
        val hr = walk(model, 300, 4.8, 5.0)
        // 70 + 110 * (18.7 - 3.5) / (45 - 3.5) = 110.3
        assertEquals(110, hr.last())
        assertTrue(hr[30] in 90..100, "about 63 % of the way after one time constant: ${hr[30]}")
        assertTrue(hr.zipWithNext().all { (a, b) -> b >= a }, "rises monotonically without noise")
        // Stopped: back towards rest.
        val rest = walk(model, 600, 0.0, 0.0, running = false)
        assertEquals(70, rest.last())
    }

    @Test
    fun `demand above VO2max saturates at the maximum and counts the time`() {
        val model = PhysiologicalHeartRateModel(restingBpm = 60, maxBpm = 185, vo2maxMlKgMin = 50.0, noiseBpm = 0)
        val hr = walk(model, 240, 15.0, 1.0)
        assertEquals(185, hr.last())
        assertEquals(240, model.secondsAboveVo2max)
        walk(model, 1, 8.0, 1.0)
        assertEquals(0, model.secondsAboveVo2max)
    }

    @Test
    fun `noise is deterministic`() {
        val a = walk(PhysiologicalHeartRateModel(noiseBpm = 2, seed = 3), 120, 5.0, 0.0)
        val b = walk(PhysiologicalHeartRateModel(noiseBpm = 2, seed = 3), 120, 5.0, 0.0)
        assertEquals(a, b)
        assertTrue(a.toSet().size > 5)
    }

    @Test
    fun `strap taken off - plausible values for a while, then zero`() {
        val model = PhysiologicalHeartRateModel(strapOffPlausibleSeconds = 60)
        walk(model, 120, 6.0, 5.0)
        model.takeOffStrap()
        val off = walk(model, 90, 6.0, 5.0)
        assertTrue(off.take(60).all { it in 73..80 }, "$off")
        assertTrue(off.drop(60).all { it == 0 })
        model.putOnStrap()
        assertTrue(walk(model, 1, 6.0, 5.0).single() > 100)
        assertTrue(walk(PhysiologicalHeartRateModel(strapPaired = false), 10, 5.0, 0.0).all { it == 0 })
    }

    @Test
    fun `the simulator relays the model through Treadmill Data`() = runTest {
        val rig = Rig(this)
        val model = PhysiologicalHeartRateModel(restingBpm = 70, noiseBpm = 0)
        rig.sim.heartRateModel = model
        rig.connectAndStart()
        assertEquals(CommandResult.Success, rig.controller.setSpeed(6.0))
        delay(240_000)
        val expected = 70 + 110 * (PhysiologicalHeartRateModel.vo2Demand(6.0, 0.0) - 3.5) / (45.0 - 3.5)
        assertEquals(expected, rig.controller.state.value.heartRateBpm!!.toDouble(), 1.0)
        rig.sim.setHeartRate(0)
        delay(2_000)
        assertEquals(null, rig.controller.state.value.heartRateBpm, "a fixed value detaches the model; 0 = no monitor")
    }
}
