package com.treadz.console.treadmill.ftms

import com.treadz.console.treadmill.ValueRange
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import com.treadz.console.treadmill.notNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hex packets in these tests are copied from the log of a real session against the
 * Z-Tech (the time of each one is given). The exceptions, marked as "synthetic", come from the
 * specification or from the documented values.
 */
class FtmsCodecTest {

    private fun sample(hex: String): TreadmillSample = notNull(FtmsCodec.decodeSample(hex.hexToBytes()), "does not decode $hex")

    // ---------------------------------------------------------------- Treadmill Data

    @Test
    fun `Treadmill Data when Idle, all zeros (01_02_05)`() {
        val s = sample("9c05000000000000000000000000000000000000000000")
        assertEquals(TreadmillSample(0.0, 0, 0.0, 0, 0, 0, null), s)
        assertTrue(s.isAllZero)
    }

    @Test
    fun `first second after starting, speed 0_5 (01_05_11)`() {
        val s = sample("9c05320000000000000000000000000000000001000100")
        assertEquals(0.5, s.speedKmh)
        assertEquals(1, s.elapsedSeconds)
        assertEquals(1, s.stopwatchSeconds)
    }

    @Test
    fun `kcal per hour and per minute fields are the stopwatch minutes and seconds (01_06_36)`() {
        // Bytes 17..19 = 01 00 1a -> "kcal/h"=1, "kcal/min"=26 -> 1 min 26 s = 86 s; the time field says 86.
        val packet = notNull(FtmsCodec.decodeTreadmillData("9c0564001400001400000000000000020001001a005600".hexToBytes()))
        assertEquals(1, packet.energyPerHourRaw)
        assertEquals(26, packet.energyPerMinuteRaw)
        val s = FtmsCodec.toSample(packet)!!
        assertEquals(86, s.stopwatchSeconds)
        assertEquals(86, s.elapsedSeconds)
        assertEquals(1.0, s.speedKmh)
        assertEquals(20, s.distanceMeters)
        assertEquals(2.0, s.inclinePercent)
        assertEquals(2, s.calories)
    }

    @Test
    fun `distance and elevation (01_05_55 and fields always zero)`() {
        val packet = notNull(FtmsCodec.decodeTreadmillData("9c0564000a00001400000000000000010000002d002d00".hexToBytes()))
        assertEquals(10, packet.totalDistanceMeters)
        assertEquals(0.0, packet.positiveElevationGainMeters)
        assertEquals(0.0, packet.negativeElevationGainMeters)
        assertEquals(0.0, packet.rampAngleDegrees)
        assertEquals(0x059C, packet.flags)
    }

    @Test
    fun `speed and incline at other values (01_07_12 and 01_12_58)`() {
        val a = sample("9c056e001400003200000000000000030001002c006800")
        assertEquals(1.1, a.speedKmh)
        assertEquals(5.0, a.inclinePercent)
        val b = sample("9c05f4018601005a000000000000002600080030001002")
        assertEquals(5.0, b.speedKmh)
        assertEquals(390, b.distanceMeters)
        assertEquals(9.0, b.inclinePercent)
        assertEquals(38, b.calories)
    }

    @Test
    fun `a distance target turns the distance field into a countdown (01_20_35)`() {
        val s = sample("9c053200f4010000000000000000000000000002000200")
        assertEquals(500, s.distanceMeters)
    }

    @Test
    fun `a time target turns time and stopwatch into a countdown (01_12_58)`() {
        val s = sample("9c05f4018601005a000000000000002600080030001002")
        // 8 min 48 s and the time field = 528 s: both decrease, it is not real elapsed time.
        assertEquals(528, s.stopwatchSeconds)
        assertEquals(528, s.elapsedSeconds)
    }

    @Test
    fun `heart rate 0 means no monitor and the real heart rate is kept (01_28_36 and 01_28_37)`() {
        assertNull(sample("9c05000000000000000000000000000000000000000000").heartRateBpm)
        assertEquals(87, sample("9c05000000000000000000000000000000000000570000").heartRateBpm)
        assertEquals(84, sample("9c05000000000000000000000000000000000000540000").heartRateBpm)
    }

    @Test
    fun `safety key stop, speed 0 with incline and time frozen (01_36_23)`() {
        val s = sample("9c0500000a000032000000000000000100000014001400")
        assertEquals(0.0, s.speedKmh)
        assertEquals(5.0, s.inclinePercent)
        assertEquals(20, s.elapsedSeconds)
        assertFalse(s.isAllZero)
    }

    @Test
    fun `the two-byte 0100 packet that arrives on subscribe carries no speed (01_01_48)`() {
        val packet = notNull(FtmsCodec.decodeTreadmillData("0100".hexToBytes()))
        assertNull(packet.speedKmh)
        assertNull(FtmsCodec.toSample(packet))
    }

    @Test
    fun `a truncated packet is not decoded`() {
        assertNull(FtmsCodec.decodeTreadmillData("9c053200".hexToBytes()))
        assertNull(FtmsCodec.decodeTreadmillData(ByteArray(0)))
        // 20 bytes: what would arrive with the default MTU (23) instead of the full 23 bytes.
        assertNull(FtmsCodec.decodeTreadmillData("9c05320000000000000000000000000000000001".hexToBytes()))
    }

    @Test
    fun `leftover bytes are kept`() {
        val packet = notNull(FtmsCodec.decodeTreadmillData("9c05000000000000000000000000000000000000000000aabb".hexToBytes()))
        assertEquals("aabb", packet.trailingBytes.toHex())
    }

    @Test
    fun `every sample packet is encoded the same way the treadmill decodes it`() {
        val hex = "9c0564001400001400000000000000020001001a005600"
        val packet = FtmsCodec.encodeTreadmillData(1.0, 20, 2.0, 2, 86, 0)
        assertEquals(hex, packet.toHex())
    }

    // ---------------------------------------------------------------- Machine Status

    @Test
    fun `Machine Status 02 stop or pause (01_01_49)`() {
        val s = FtmsCodec.decodeMachineStatus("0200".hexToBytes())
        assertEquals(MachineStatus.StoppedOrPaused(0), s)
    }

    @Test
    fun `Machine Status 03 safety key (01_33_21)`() {
        assertTrue(FtmsCodec.decodeMachineStatus("03".hexToBytes()) is MachineStatus.StoppedBySafetyKey)
    }

    @Test
    fun `Machine Status target speed and its duplicated notification with value 0000 (01_05_26)`() {
        assertEquals(MachineStatus.TargetSpeedChanged(1.0), FtmsCodec.decodeMachineStatus("056400".hexToBytes()))
        assertEquals(MachineStatus.TargetSpeedChanged(3.0), FtmsCodec.decodeMachineStatus("052c01".hexToBytes()))
        // The 0000 duplicate carries no value.
        assertEquals(MachineStatus.TargetSpeedChanged(null), FtmsCodec.decodeMachineStatus("050000".hexToBytes()))
    }

    @Test
    fun `Machine Status target incline and its duplicate (01_05_31)`() {
        assertEquals(MachineStatus.TargetInclineChanged(2.0), FtmsCodec.decodeMachineStatus("061400".hexToBytes()))
        assertEquals(MachineStatus.TargetInclineChanged(5.0), FtmsCodec.decodeMachineStatus("063200".hexToBytes()))
        assertEquals(MachineStatus.TargetInclineChanged(null), FtmsCodec.decodeMachineStatus("060000".hexToBytes()))
    }

    @Test
    fun `Machine Status target distance and time (01_16_07 and 01_11_45)`() {
        assertEquals(MachineStatus.TargetDistanceChanged(500), FtmsCodec.decodeMachineStatus("0df40100".hexToBytes()))
        assertEquals(MachineStatus.TargetTimeChanged(600), FtmsCodec.decodeMachineStatus("0e5802".hexToBytes()))
    }

    @Test
    fun `Machine Status unknown or empty`() {
        val other = FtmsCodec.decodeMachineStatus("1234".hexToBytes()) as MachineStatus.Other
        assertEquals(0x12, other.opCode)
        assertNull(FtmsCodec.decodeMachineStatus(ByteArray(0)))
    }

    // ---------------------------------------------------------------- Training Status

    @Test
    fun `Training Status Idle and ManualMode (01_01_49 and 01_05_09)`() {
        val idle = FtmsCodec.decodeTrainingStatus("0101".hexToBytes())!!
        assertTrue(idle.isIdle)
        assertEquals("Idle", idle.name)
        val manual = FtmsCodec.decodeTrainingStatus("010d".hexToBytes())!!
        assertTrue(manual.isManualMode)
        assertEquals("ManualMode", manual.name)
        assertNull(FtmsCodec.decodeTrainingStatus("01".hexToBytes()))
    }

    // ---------------------------------------------------------------- Control Point

    @Test
    fun `Control Point responses (01_02_03, 01_05_05, 01_06_36, 01_05_26, 01_05_31)`() {
        fun resp(hex: String) = notNull(FtmsCodec.decodeControlPointResponse(hex.hexToBytes()))
        assertEquals(ControlPointResponse(0x00, 1), resp("800001"))
        assertEquals("StartOrResume", resp("800701").requestName)
        assertEquals("StopOrPause", resp("800801").requestName)
        assertEquals("SetSpeed", resp("800201").requestName)
        assertEquals("SetIncline", resp("800301").requestName)
        assertTrue(resp("800701").isSuccess)
        assertEquals(ControlPointResult.NotSupported, resp("800702").result)
        assertEquals(ControlPointResult.ControlNotPermitted, resp("800705").result)
        assertNull(FtmsCodec.decodeControlPointResponse("0701".hexToBytes()))
        assertNull(FtmsCodec.decodeControlPointResponse("8007".hexToBytes()))
    }

    @Test
    fun `commands encoded the same as in the log (CP 00, 07, 0802, 0801, 026400, 022c01, 02c800, 031400, 033200)`() {
        assertEquals("00", FtmsCodec.requestControl().toHex())
        assertEquals("07", FtmsCodec.startOrResume().toHex())
        assertEquals("0802", FtmsCodec.pause().toHex())
        assertEquals("0801", FtmsCodec.stop().toHex())
        assertEquals("026400", FtmsCodec.setSpeed(1.0).toHex())
        assertEquals("022c01", FtmsCodec.setSpeed(3.0).toHex())
        assertEquals("02c800", FtmsCodec.setSpeed(2.0).toHex())
        assertEquals("031400", FtmsCodec.setIncline(2.0).toHex())
        assertEquals("033200", FtmsCodec.setIncline(5.0).toHex())
    }

    @Test
    fun `speed is encoded without floating point errors`() {
        assertEquals("025700", FtmsCodec.setSpeed(0.87).toHex()) // 87 = 0x57
        assertEquals("02d007", FtmsCodec.setSpeed(20.0).toHex()) // 2000 = 0x07d0
        assertEquals("020a00", FtmsCodec.setSpeed(0.1).toHex())
        assertEquals("02f401", FtmsCodec.setSpeed(5.0 + 0.0000000001).toHex()) // 500 = 0x01f4
    }

    @Test
    fun `there are no encoders for the time and distance targets (0C and 0D)`() {
        // The log shows that 0D/0C turn time/distance into countdowns: the codec does not offer them.
        val methods = FtmsCodec::class.java.declaredMethods.map { it.name }
        assertFalse(methods.any { it.contains("Target", ignoreCase = true) })
        assertEquals("SetTargetedDistance", FtmsCodec.commandName(0x0C)) // only so they can be named in traces
    }

    // ---------------------------------------------------------------- Feature and ranges

    @Test
    fun `documented speed and incline ranges (synthetic)`() {
        // 0.5-20.0 km/h step 0.1 and 0-18 % step 1, encoded according to FTMS.
        val speed = FtmsCodec.decodeSpeedRange("3200d0070a00".hexToBytes())
        assertEquals(ValueRange(0.5, 20.0, 0.1), speed)
        val incline = FtmsCodec.decodeInclineRange("0000b4000a00".hexToBytes())
        assertEquals(ValueRange(0.0, 18.0, 1.0), incline)
        assertArrayEquals("3200d0070a00".hexToBytes(), FtmsCodec.encodeSpeedRange(ValueRange(0.5, 20.0, 0.1)))
        assertArrayEquals("0000b4000a00".hexToBytes(), FtmsCodec.encodeInclineRange(ValueRange(0.0, 18.0, 1.0)))
        assertNull(FtmsCodec.decodeSpeedRange("3200".hexToBytes()))
    }

    @Test
    fun `Feature decodes both 32-bit fields (synthetic)`() {
        val f = FtmsCodec.decodeFeatures("5c16000003030000".hexToBytes())!!
        assertEquals(0x165CL, f.machineFeatures)
        assertEquals(0x0303L, f.targetSettingFeatures)
        assertTrue("TotalDistance" in f.machineFeatureNames)
        assertTrue("HeartRate" in f.machineFeatureNames)
        assertTrue("SpeedTarget" in f.targetSettingNames)
        assertTrue("InclinationTarget" in f.targetSettingNames)
        assertNull(FtmsCodec.decodeFeatures("5c160000".hexToBytes()))
    }

    @Test
    fun `Device Information strings`() {
        assertEquals("ChangYow", FtmsCodec.decodeString("ChangYow".toByteArray()))
        assertEquals("SI3019", FtmsCodec.decodeString(byteArrayOf(*"SI3019".toByteArray(), 0, 0)))
        assertNull(FtmsCodec.decodeString(ByteArray(0)))
    }

    @Test
    fun `full and short UUIDs`() {
        assertEquals("2acd", FtmsCharacteristic.TreadmillData.shortName)
        assertEquals("00002ad9-0000-1000-8000-00805f9b34fb", FtmsCharacteristic.ControlPoint.fullUuid)
        assertEquals(FtmsCharacteristic.MachineStatus, FtmsCharacteristic.fromShortUuid(0x2ADA))
        // The forbidden channels do not exist in the enum.
        assertNull(FtmsCharacteristic.fromShortUuid(0xFFF1))
        assertNull(FtmsCharacteristic.fromShortUuid(0xFFF2))
    }
}
