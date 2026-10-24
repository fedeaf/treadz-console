package com.treadz.console.analysis.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnergyExpenditureTest {

    @Test
    fun `walking follows Looney et al 2019 - level and uphill`() {
        // S = 1.5556 m/s: 1.44 + 1.94 S^0.43 + 0.24 S^4 = 5.191 W/kg
        assertEquals(5.191, EnergyExpenditure.walkingWattsPerKg(5.6, 0.0), 0.001)
        // + 0.34 S G (1 - 1.05^(1 - 1.1^(G + 32))) at 7 %: 8.371 W/kg
        assertEquals(8.371, EnergyExpenditure.walkingWattsPerKg(5.6, 7.0), 0.001)
        // 88.9 kg: W/kg * kg * 60 / 4184 kcal per minute
        assertEquals(6.618, EnergyExpenditure.kcalPerMinute(5.6, 0.0, 88.9), 0.001)
        assertEquals(10.672, EnergyExpenditure.kcalPerMinute(5.6, 7.0, 88.9), 0.001)
        assertEquals(12.871, EnergyExpenditure.kcalPerMinute(5.6, 10.0, 88.9), 0.001)
    }

    @Test
    fun `walking up to 7 km per hour, the ACSM running equation above, nothing with the belt stopped`() {
        val walking = EnergyExpenditure.walkingWattsPerKg(7.0, 1.0) * 70 * 60 / 4184
        assertEquals(walking, EnergyExpenditure.kcalPerMinute(7.0, 1.0, 70.0), 1e-9)
        // 7.1 km/h = 118.33 m/min, 1 %: 3.5 + 23.667 + 1.065 = 28.232 ml/kg/min * 70 / 1000 * 5
        assertEquals(9.881, EnergyExpenditure.kcalPerMinute(7.1, 1.0, 70.0), 0.001)
        assertEquals(0.0, EnergyExpenditure.kcalPerMinute(0.0, 5.0, 70.0))
        assertEquals(0.0, EnergyExpenditure.kcalPerMinute(5.0, 5.0, 0.0))
    }

    @Test
    fun `the counter adds every stretch for its duration`() {
        val c = EnergyCounter(88.9)
        c.add(600.0, 5.6, 7.0)
        c.add(30.0, 0.0, 7.0)
        c.add(-5.0, 5.6, 7.0)
        assertEquals(106.72, c.kcal, 0.01)
    }
}
