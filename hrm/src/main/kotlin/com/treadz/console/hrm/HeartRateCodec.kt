package com.treadz.console.hrm

import kotlin.math.roundToInt

/** A packet that does not follow the Heart Rate service format. */
class HrmFormatException(message: String) : IllegalArgumentException(message)

/**
 * Codec of the standard Heart Rate service (0x180D) and of the Battery Level characteristic.
 *
 * Heart Rate Measurement (0x2A37), little endian:
 * ```
 * flags (u8)
 *   bit 0    heart-rate value format: 0 = u8, 1 = u16
 *   bits 1-2 sensor contact: 0b0x = not supported, 0b10 = supported but not detected, 0b11 = detected
 *   bit 3    energy expended present (u16, kJ)
 *   bit 4    RR intervals present (one or more u16, 1/1024 s)
 * heart rate (u8 or u16) | energy expended (u16)? | RR interval (u16)*
 * ```
 */
object HeartRateCodec {

    private const val FLAG_HR_U16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10

    /** Converts an RR interval from 1/1024 s to milliseconds. */
    fun rrToMillis(raw: Int): Int = (raw * 1000.0 / 1024.0).roundToInt()

    /** Converts milliseconds to the 1/1024 s unit of the RR field. */
    fun millisToRr(ms: Int): Int = (ms * 1024.0 / 1000.0).roundToInt()

    fun decodeMeasurement(value: ByteArray): HeartRateMeasurement {
        if (value.isEmpty()) throw HrmFormatException("Empty Heart Rate Measurement")
        val flags = u8(value, 0)
        var offset = 1
        val bpm: Int
        if (flags and FLAG_HR_U16 != 0) {
            need(value, offset, 2, "heart rate (u16)")
            bpm = u16(value, offset)
            offset += 2
        } else {
            need(value, offset, 1, "heart rate (u8)")
            bpm = u8(value, offset)
            offset += 1
        }
        val contact = when {
            flags and FLAG_CONTACT_SUPPORTED == 0 -> SensorContact.NotSupported
            flags and FLAG_CONTACT_DETECTED != 0 -> SensorContact.Detected
            else -> SensorContact.NotDetected
        }
        var energy: Int? = null
        if (flags and FLAG_ENERGY != 0) {
            need(value, offset, 2, "energy expended")
            energy = u16(value, offset)
            offset += 2
        }
        val raw = mutableListOf<Int>()
        if (flags and FLAG_RR != 0) {
            if ((value.size - offset) % 2 != 0) throw HrmFormatException("RR intervals: odd number of bytes (${value.size - offset})")
            while (offset + 1 < value.size) {
                raw += u16(value, offset)
                offset += 2
            }
        }
        return HeartRateMeasurement(
            bpm = bpm,
            sensorContact = contact,
            energyExpendedKj = energy,
            rrIntervalsMs = raw.map(::rrToMillis),
            rrIntervalsRaw = raw.toList(),
        )
    }

    /** Builds a Heart Rate Measurement packet (used by the simulated strap and the tests). */
    fun encodeMeasurement(
        bpm: Int,
        contact: SensorContact,
        energyExpendedKj: Int? = null,
        rrIntervalsRaw: List<Int> = emptyList(),
    ): ByteArray {
        require(bpm in 0..0xFFFF) { "bpm out of range: $bpm" }
        val wide = bpm > 0xFF
        var flags = if (wide) FLAG_HR_U16 else 0
        flags = flags or when (contact) {
            SensorContact.NotSupported -> 0
            SensorContact.NotDetected -> FLAG_CONTACT_SUPPORTED
            SensorContact.Detected -> FLAG_CONTACT_SUPPORTED or FLAG_CONTACT_DETECTED
        }
        if (energyExpendedKj != null) flags = flags or FLAG_ENERGY
        if (rrIntervalsRaw.isNotEmpty()) flags = flags or FLAG_RR
        val out = ArrayList<Byte>()
        out += flags.toByte()
        if (wide) putU16(out, bpm) else out += bpm.toByte()
        energyExpendedKj?.let { putU16(out, it.coerceIn(0, 0xFFFF)) }
        rrIntervalsRaw.forEach { putU16(out, it.coerceIn(0, 0xFFFF)) }
        return out.toByteArray()
    }

    /** Body Sensor Location (0x2A38): one byte. */
    fun decodeBodySensorLocation(value: ByteArray): BodySensorLocation {
        if (value.isEmpty()) throw HrmFormatException("Empty Body Sensor Location")
        return BodySensorLocation.fromCode(u8(value, 0))
    }

    /** Battery Level (0x2A19): one byte, 0-100 %. */
    fun decodeBatteryLevel(value: ByteArray): Int {
        if (value.isEmpty()) throw HrmFormatException("Empty Battery Level")
        val level = u8(value, 0)
        if (level > 100) throw HrmFormatException("Battery level out of range: $level")
        return level
    }

    private fun need(value: ByteArray, offset: Int, count: Int, what: String) {
        if (value.size < offset + count) throw HrmFormatException("Packet too short for $what (${value.size} bytes)")
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun putU16(out: MutableList<Byte>, v: Int) {
        out += (v and 0xFF).toByte()
        out += ((v shr 8) and 0xFF).toByte()
    }
}

/** Lower-case hex without separators (`0a1f`), as in the debug recordings. */
fun ByteArray.toHrmHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
