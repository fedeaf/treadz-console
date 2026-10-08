package com.treadz.console.programs.engine

import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.treadmill.TreadmillState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeartRateSourceTest {
    @Test
    fun `zero or missing means no heart rate monitor`() {
        assertEquals(HeartRateReading(null, HeartRateValidity.Invalid, 5), ControllerHeartRateSource.map(TreadmillState(heartRateBpm = null, updatedAtMillis = 5)))
        assertEquals(HeartRateReading(null, HeartRateValidity.Invalid, 5), ControllerHeartRateSource.map(TreadmillState(heartRateBpm = 0, updatedAtMillis = 5)))
    }

    @Test
    fun `a value relayed by the console is plausible but unconfirmed`() {
        assertEquals(HeartRateReading(75, HeartRateValidity.Unconfirmed, 9), ControllerHeartRateSource.map(TreadmillState(heartRateBpm = 75, updatedAtMillis = 9)))
    }

    @Test
    fun `stale data is never valid`() {
        assertEquals(HeartRateValidity.Invalid, ControllerHeartRateSource.map(TreadmillState(heartRateBpm = 75, isStale = true)).validity)
    }
}
