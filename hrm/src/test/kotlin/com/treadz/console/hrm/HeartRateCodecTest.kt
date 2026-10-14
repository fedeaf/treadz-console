package com.treadz.console.hrm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HeartRateCodecTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `u8 heart rate without contact support`() {
        val m = HeartRateCodec.decodeMeasurement(hex("0048"))
        assertEquals(72, m.bpm)
        assertEquals(SensorContact.NotSupported, m.sensorContact)
        assertNull(m.energyExpendedKj)
        assertEquals(emptyList<Int>(), m.rrIntervalsMs)
    }

    @Test
    fun `contact bits`() {
        assertEquals(SensorContact.NotSupported, HeartRateCodec.decodeMeasurement(hex("0248")).sensorContact) // bit 1 alone: not supported
        assertEquals(SensorContact.NotDetected, HeartRateCodec.decodeMeasurement(hex("0400")).sensorContact)
        assertEquals(SensorContact.Detected, HeartRateCodec.decodeMeasurement(hex("0648")).sensorContact)
    }

    @Test
    fun `u16 heart rate, energy and several RR intervals`() {
        // flags 0x1F: u16 HR, contact detected, energy, RR. HR 0x0096 = 150, energy 0x0102 = 258 kJ, RR 410 and 1024.
        val m = HeartRateCodec.decodeMeasurement(hex("1f" + "9600" + "0201" + "9a01" + "0004"))
        assertEquals(150, m.bpm)
        assertEquals(SensorContact.Detected, m.sensorContact)
        assertEquals(258, m.energyExpendedKj)
        assertEquals(listOf(410, 1024), m.rrIntervalsRaw)
        assertEquals(listOf(400, 1000), m.rrIntervalsMs) // 410/1024 s = 400.4 ms, 1024/1024 s = 1000 ms
    }

    @Test
    fun `typical chest strap packet with one RR interval`() {
        // flags 0x16: u8 HR, contact detected, RR present. 0x4E = 78 bpm, RR 0x0315 = 789 → 770.5 ms.
        val m = HeartRateCodec.decodeMeasurement(hex("164e1503"))
        assertEquals(78, m.bpm)
        assertEquals(listOf(771), m.rrIntervalsMs)
    }

    @Test
    fun `malformed packets are rejected`() {
        assertThrows<HrmFormatException> { HeartRateCodec.decodeMeasurement(ByteArray(0)) }
        assertThrows<HrmFormatException> { HeartRateCodec.decodeMeasurement(hex("01")) } // u16 HR missing
        assertThrows<HrmFormatException> { HeartRateCodec.decodeMeasurement(hex("0848")) } // energy missing
        assertThrows<HrmFormatException> { HeartRateCodec.decodeMeasurement(hex("104800")) } // odd RR bytes
    }

    @Test
    fun `encode and decode are symmetric`() {
        val packet = HeartRateCodec.encodeMeasurement(300, SensorContact.Detected, 12, listOf(205, 210))
        val m = HeartRateCodec.decodeMeasurement(packet)
        assertEquals(300, m.bpm)
        assertEquals(SensorContact.Detected, m.sensorContact)
        assertEquals(12, m.energyExpendedKj)
        assertEquals(listOf(205, 210), m.rrIntervalsRaw)
        assertEquals("0648", HeartRateCodec.encodeMeasurement(72, SensorContact.Detected).toHrmHex())
        assertEquals("0400", HeartRateCodec.encodeMeasurement(0, SensorContact.NotDetected).toHrmHex())
    }

    @Test
    fun `body sensor location and battery`() {
        assertEquals(BodySensorLocation.Chest, HeartRateCodec.decodeBodySensorLocation(hex("01")))
        assertEquals(BodySensorLocation.Unknown, HeartRateCodec.decodeBodySensorLocation(hex("09")))
        assertEquals(87, HeartRateCodec.decodeBatteryLevel(hex("57")))
        assertThrows<HrmFormatException> { HeartRateCodec.decodeBatteryLevel(hex("ff")) }
        assertThrows<HrmFormatException> { HeartRateCodec.decodeBatteryLevel(ByteArray(0)) }
    }

    @Test
    fun `rr conversions`() {
        assertEquals(1000, HeartRateCodec.rrToMillis(1024))
        assertEquals(1024, HeartRateCodec.millisToRr(1000))
        assertEquals(500, HeartRateCodec.rrToMillis(HeartRateCodec.millisToRr(500)))
    }
}
