package com.treadz.console.treadmill.ftms

import com.treadz.console.treadmill.ValueRange
import kotlin.math.roundToInt

/**
 * Encoding of Control Point commands and decoding of everything the treadmill sends (FTMS 0x1826).
 * The formats are verified on the real treadmill.
 *
 * This class does not implement the FTMS distance (0x0C) or time (0x0D) targets: on this treadmill
 * they turn the distance/time fields into countdowns and garble the data.
 */
object FtmsCodec {

    const val OP_REQUEST_CONTROL = 0x00
    const val OP_SET_SPEED = 0x02
    const val OP_SET_INCLINE = 0x03
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_RESPONSE = 0x80

    private const val STOP_PARAM = 0x01
    private const val PAUSE_PARAM = 0x02

    // ---------------------------------------------------------------- commands

    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())

    fun startOrResume(): ByteArray = byteArrayOf(OP_START_RESUME.toByte())

    fun stop(): ByteArray = byteArrayOf(OP_STOP_PAUSE.toByte(), STOP_PARAM.toByte())

    fun pause(): ByteArray = byteArrayOf(OP_STOP_PAUSE.toByte(), PAUSE_PARAM.toByte())

    /** `02 <u16 LE, 0.01 km/h>` */
    fun setSpeed(kmh: Double): ByteArray {
        val raw = (kmh * 100.0).roundToInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(OP_SET_SPEED.toByte(), raw.toByte(), (raw shr 8).toByte())
    }

    /** `03 <s16 LE, 0.1 %>` */
    fun setIncline(percent: Double): ByteArray {
        val raw = (percent * 10.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return byteArrayOf(OP_SET_INCLINE.toByte(), raw.toByte(), (raw shr 8).toByte())
    }

    fun commandName(opCode: Int): String = when (opCode) {
        0x00 -> "RequestControl"
        0x01 -> "Reset"
        0x02 -> "SetSpeed"
        0x03 -> "SetIncline"
        0x04 -> "SetResistance"
        0x05 -> "SetPower"
        0x06 -> "SetHeartRate"
        0x07 -> "StartOrResume"
        0x08 -> "StopOrPause"
        0x09 -> "SetTargetedEnergy"
        0x0A -> "SetTargetedSteps"
        0x0B -> "SetTargetedStrides"
        0x0C -> "SetTargetedDistance"
        0x0D -> "SetTargetedTime"
        else -> "Op0x%02x".format(opCode)
    }

    // ------------------------------------------------------------ decoding

    /** Treadmill Data (0x2ACD). `null` if the packet is truncated. */
    fun decodeTreadmillData(bytes: ByteArray): TreadmillDataPacket? {
        val r = Reader(bytes)
        val flags = r.u16() ?: return null
        fun has(bit: Int) = flags and (1 shl bit) != 0

        var speed: Double? = null
        var avgSpeed: Double? = null
        var distance: Int? = null
        var incline: Double? = null
        var ramp: Double? = null
        var elevPos: Double? = null
        var elevNeg: Double? = null
        var pace: Double? = null
        var avgPace: Double? = null
        var energy: Int? = null
        var energyH: Int? = null
        var energyM: Int? = null
        var hr: Int? = null
        var met: Double? = null
        var elapsed: Int? = null
        var remaining: Int? = null
        var force: Int? = null
        var power: Int? = null

        // Bit 0 is "More Data": when 0, the instantaneous speed is present.
        if (!has(0)) speed = (r.u16() ?: return null) / 100.0
        if (has(1)) avgSpeed = (r.u16() ?: return null) / 100.0
        if (has(2)) distance = r.u24() ?: return null
        if (has(3)) {
            incline = (r.s16() ?: return null) / 10.0
            ramp = (r.s16() ?: return null) / 10.0
        }
        if (has(4)) {
            elevPos = (r.u16() ?: return null) / 10.0
            elevNeg = (r.u16() ?: return null) / 10.0
        }
        if (has(5)) pace = (r.u8() ?: return null) / 10.0
        if (has(6)) avgPace = (r.u8() ?: return null) / 10.0
        if (has(7)) {
            energy = r.u16() ?: return null
            energyH = r.u16() ?: return null
            energyM = r.u8() ?: return null
        }
        if (has(8)) hr = r.u8() ?: return null
        if (has(9)) met = (r.u8() ?: return null) / 10.0
        if (has(10)) elapsed = r.u16() ?: return null
        if (has(11)) remaining = r.u16() ?: return null
        if (has(12)) {
            force = r.s16() ?: return null
            power = r.s16() ?: return null
        }
        return TreadmillDataPacket(
            flags = flags,
            speedKmh = speed,
            averageSpeedKmh = avgSpeed,
            totalDistanceMeters = distance,
            inclinePercent = incline,
            rampAngleDegrees = ramp,
            positiveElevationGainMeters = elevPos,
            negativeElevationGainMeters = elevNeg,
            instantaneousPace = pace,
            averagePace = avgPace,
            totalEnergyKcal = energy,
            energyPerHourRaw = energyH,
            energyPerMinuteRaw = energyM,
            heartRateRaw = hr,
            metabolicEquivalent = met,
            elapsedSeconds = elapsed,
            remainingSeconds = remaining,
            forceOnBeltNewtons = force,
            powerWatts = power,
            trailingBytes = r.rest(),
        )
    }

    /**
     * Applies the firmware quirks. Returns `null` if the packet carries no speed
     * (e.g. the 2-byte `0100` packet the treadmill sends right after subscribing).
     */
    fun toSample(packet: TreadmillDataPacket): TreadmillSample? {
        val speed = packet.speedKmh ?: return null
        val minutes = packet.energyPerHourRaw
        val seconds = packet.energyPerMinuteRaw
        val stopwatch = if (minutes != null && seconds != null && seconds < 60) minutes * 60 + seconds else null
        val hr = packet.heartRateRaw?.takeIf { it > 0 }
        return TreadmillSample(
            speedKmh = speed,
            distanceMeters = packet.totalDistanceMeters ?: 0,
            inclinePercent = packet.inclinePercent ?: 0.0,
            calories = packet.totalEnergyKcal ?: 0,
            elapsedSeconds = packet.elapsedSeconds ?: stopwatch ?: 0,
            stopwatchSeconds = stopwatch,
            heartRateBpm = hr,
        )
    }

    fun decodeSample(bytes: ByteArray): TreadmillSample? = decodeTreadmillData(bytes)?.let(::toSample)

    /** Fitness Machine Status (0x2ADA). `null` if empty. */
    fun decodeMachineStatus(bytes: ByteArray): MachineStatus? {
        if (bytes.isEmpty()) return null
        val op = bytes[0].toInt() and 0xFF
        val r = Reader(bytes, 1)
        return when (op) {
            0x01 -> MachineStatus.Reset()
            0x02 -> MachineStatus.StoppedOrPaused(r.u8())
            0x03 -> MachineStatus.StoppedBySafetyKey()
            0x04 -> MachineStatus.StartedOrResumed()
            // The value 0000 is the treadmill's duplicated notification: no useful value.
            0x05 -> MachineStatus.TargetSpeedChanged(r.u16()?.takeIf { it != 0 }?.let { it / 100.0 })
            0x06 -> MachineStatus.TargetInclineChanged(r.s16()?.takeIf { it != 0 }?.let { it / 10.0 })
            0x0D -> MachineStatus.TargetDistanceChanged(r.u24())
            0x0E -> MachineStatus.TargetTimeChanged(r.u16())
            0xFF -> MachineStatus.ControlPermissionLost()
            else -> MachineStatus.Other(op, bytes.copyOfRange(1, bytes.size))
        }
    }

    /** Training Status (0x2AD3): flags, status and optional string. */
    fun decodeTrainingStatus(bytes: ByteArray): TrainingStatus? {
        if (bytes.size < 2) return null
        val text = if (bytes.size > 2) String(bytes, 2, bytes.size - 2, Charsets.UTF_8) else null
        return TrainingStatus(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, text)
    }

    /** Control Point response: `80 <opcode> <result> [parameters]`. `null` if it is not one. */
    fun decodeControlPointResponse(bytes: ByteArray): ControlPointResponse? {
        if (bytes.size < 3 || (bytes[0].toInt() and 0xFF) != OP_RESPONSE) return null
        return ControlPointResponse(
            requestOpCode = bytes[1].toInt() and 0xFF,
            resultCode = bytes[2].toInt() and 0xFF,
            params = bytes.copyOfRange(3, bytes.size),
        )
    }

    /** Fitness Machine Feature (0x2ACC). */
    fun decodeFeatures(bytes: ByteArray): FtmsFeatures? {
        val r = Reader(bytes)
        val machine = r.u32() ?: return null
        val target = r.u32() ?: return null
        return FtmsFeatures(machine, target)
    }

    /** Supported Speed Range (0x2AD4): minimum, maximum and increment, u16 in 0.01 km/h. */
    fun decodeSpeedRange(bytes: ByteArray): ValueRange? {
        val r = Reader(bytes)
        val min = r.u16() ?: return null
        val max = r.u16() ?: return null
        val step = r.u16() ?: return null
        return ValueRange(min / 100.0, max / 100.0, step / 100.0)
    }

    /** Supported Inclination Range (0x2AD5): minimum and maximum s16, increment u16, in 0.1 %. */
    fun decodeInclineRange(bytes: ByteArray): ValueRange? {
        val r = Reader(bytes)
        val min = r.s16() ?: return null
        val max = r.s16() ?: return null
        val step = r.u16() ?: return null
        return ValueRange(min / 10.0, max / 10.0, step / 10.0)
    }

    /** UTF-8 string from Device Information (strips trailing zeros). */
    fun decodeString(bytes: ByteArray): String? {
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
    }

    // ----------------------------------------------------------------- reverse encoding
    // Used by the simulator and the tests: produces exactly what the treadmill would send.

    /** Treadmill Data with the fixed `0x059C` flags of the real treadmill (23 bytes). */
    fun encodeTreadmillData(
        speedKmh: Double,
        distanceMeters: Int,
        inclinePercent: Double,
        calories: Int,
        stopwatchSeconds: Int,
        heartRate: Int,
        elapsedSeconds: Int = stopwatchSeconds,
    ): ByteArray {
        val w = Writer()
        w.u16(0x059C)
        w.u16((speedKmh * 100).roundToInt())
        w.u24(distanceMeters)
        w.s16((inclinePercent * 10).roundToInt())
        w.s16(0) // ramp angle
        w.u16(0) // positive elevation gain
        w.u16(0) // negative elevation gain
        w.u16(calories)
        w.u16(stopwatchSeconds / 60) // "kcal/h": minutes
        w.u8(stopwatchSeconds % 60) // "kcal/min": seconds
        w.u8(heartRate)
        w.u16(elapsedSeconds)
        return w.toByteArray()
    }

    fun encodeSpeedRange(range: ValueRange): ByteArray = Writer().apply {
        u16((range.min * 100).roundToInt()); u16((range.max * 100).roundToInt()); u16((range.step * 100).roundToInt())
    }.toByteArray()

    fun encodeInclineRange(range: ValueRange): ByteArray = Writer().apply {
        s16((range.min * 10).roundToInt()); s16((range.max * 10).roundToInt()); u16((range.step * 10).roundToInt())
    }.toByteArray()

    fun encodeControlPointResponse(requestOp: Int, result: ControlPointResult): ByteArray =
        byteArrayOf(OP_RESPONSE.toByte(), requestOp.toByte(), result.code.toByte())

    // ----------------------------------------------------------------- binary utilities

    private class Reader(private val bytes: ByteArray, private var pos: Int = 0) {
        fun u8(): Int? = if (pos + 1 <= bytes.size) bytes[pos++].toInt() and 0xFF else null
        fun u16(): Int? {
            if (pos + 2 > bytes.size) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }
        fun s16(): Int? = u16()?.toShort()?.toInt()
        fun u24(): Int? {
            if (pos + 3 > bytes.size) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16)
            pos += 3
            return v
        }
        fun u32(): Long? {
            if (pos + 4 > bytes.size) return null
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
            pos += 4
            return v
        }
        fun rest(): ByteArray = if (pos < bytes.size) bytes.copyOfRange(pos, bytes.size) else ByteArray(0)
    }

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun s16(v: Int) = u16(v)
        fun u24(v: Int) { u16(v); out.write((v shr 16) and 0xFF) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
