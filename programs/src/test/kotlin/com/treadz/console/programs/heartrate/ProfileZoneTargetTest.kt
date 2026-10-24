package com.treadz.console.programs.heartrate

import com.treadz.console.analysis.zones.FitnessLevel
import com.treadz.console.analysis.zones.ZoneRange
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.programs.heartRateProgram
import com.treadz.console.programs.json.ProgramFormatException
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A heart-rate target that names a zone of the user's own zones, converted to bpm with the zones in force. */
class ProfileZoneTargetTest {

    private val zones = ZoneSet(
        zones = listOf(
            ZoneRange(1, null, 121), ZoneRange(2, 121, 131), ZoneRange(3, 131, 149), ZoneRange(4, 149, 165), ZoneRange(5, 165, 187),
        ),
        source = ZoneSource.MeasuredThresholds,
        level = FitnessLevel.Medium,
        usedReserve = false,
        vt1Bpm = 131,
        vt2Bpm = 149,
    )
    private val withZones = HeartRateProfile(maxBpm = 187, restingBpm = 57, vt1Bpm = 131, zones = zones)

    @Test
    fun `a profile zone is the user's zone of that number`() {
        assertEquals(HeartRateZoneBpm(121, 131, HeartRateBasis.MaxHeartRate, fromProfile = true), HeartRateZones.toBpm(HeartRateZone.ofProfile(2), withZones))
        assertEquals(HeartRateZoneBpm(165, 187, HeartRateBasis.MaxHeartRate, fromProfile = true), HeartRateZones.toBpm(HeartRateZone.ofProfile(5), withZones))
        // Zone 1 has no lower limit in the zone model: the standard lower percentage (50 % of the maximum).
        assertEquals(HeartRateZoneBpm(94, 121, HeartRateBasis.MaxHeartRate, fromProfile = true), HeartRateZones.toBpm(HeartRateZone.ofProfile(1), withZones))
    }

    @Test
    fun `without the user's zones it is the standard zone of the same number`() {
        val z2 = HeartRateZone.ofProfile(2)
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70, profileZone = 2), z2)
        assertEquals(HeartRateZoneBpm(112, 131, HeartRateBasis.MaxHeartRate), HeartRateZones.toBpm(z2, withZones.copy(zones = null)))
        // A plain zone ignores the user's zones.
        assertEquals(HeartRateZoneBpm(112, 131, HeartRateBasis.MaxHeartRate), HeartRateZones.toBpm(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), withZones))
        assertThrows(IllegalArgumentException::class.java) { HeartRateZone.ofProfile(6) }
    }

    @Test
    fun `the profile zone round trips in the program document and older documents have none`() {
        val plain = heartRateProgram()
        val block = plain.steps[1] as Block
        val target = block.target as BlockTarget.HeartRate
        val withProfileZone = plain.copy(steps = plain.steps.toMutableList().also { it[1] = block.copy(target = target.copy(zone = HeartRateZone.ofProfile(2))) })
        val text = ProgramJson.encode(withProfileZone)
        assertTrue(text.contains("\"profileZone\": 2"))
        assertTrue(text.contains("\"schemaVersion\": 1"), "an older app reads it as the standard percentages")
        assertEquals(withProfileZone, ProgramJson.decode(text))
        assertFalse(ProgramJson.encode(plain).contains("profileZone"))
        assertNull(((ProgramJson.decode(ProgramJson.encode(plain)).steps[1] as Block).target as BlockTarget.HeartRate).zone.profileZone)
        assertThrows(ProgramFormatException::class.java) { ProgramJson.decode(text.replace("\"profileZone\": 2", "\"profileZone\": 7")) }
    }
}
