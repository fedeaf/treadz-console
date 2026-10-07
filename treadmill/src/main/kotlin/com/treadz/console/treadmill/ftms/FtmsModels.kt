package com.treadz.console.treadmill.ftms

import com.treadz.console.treadmill.ValueRange

/**
 * GATT characteristics the app may touch. It is a closed list on purpose: neither the iConsole channel
 * (`FFF0`/`FFF1`/`FFF2`) nor the OTA service (`02f00000-…-fe00`) can be addressed from here.
 */
enum class FtmsCharacteristic(val shortUuid: Int, val serviceShortUuid: Int) {
    TreadmillData(0x2ACD, FtmsUuids.FTMS_SERVICE),
    MachineStatus(0x2ADA, FtmsUuids.FTMS_SERVICE),
    TrainingStatus(0x2AD3, FtmsUuids.FTMS_SERVICE),
    ControlPoint(0x2AD9, FtmsUuids.FTMS_SERVICE),
    MachineFeature(0x2ACC, FtmsUuids.FTMS_SERVICE),
    SupportedSpeedRange(0x2AD4, FtmsUuids.FTMS_SERVICE),
    SupportedInclinationRange(0x2AD5, FtmsUuids.FTMS_SERVICE),
    ManufacturerName(0x2A29, FtmsUuids.DEVICE_INFORMATION_SERVICE),
    ModelNumber(0x2A24, FtmsUuids.DEVICE_INFORMATION_SERVICE),
    HardwareRevision(0x2A27, FtmsUuids.DEVICE_INFORMATION_SERVICE),
    FirmwareRevision(0x2A26, FtmsUuids.DEVICE_INFORMATION_SERVICE);

    /** Short UUID in hex (e.g. `2acd`), as written in the recordings. */
    val shortName: String get() = "%04x".format(shortUuid)

    val fullUuid: String get() = FtmsUuids.full(shortUuid)

    companion object {
        fun fromShortUuid(value: Int): FtmsCharacteristic? = entries.firstOrNull { it.shortUuid == value }
    }
}

object FtmsUuids {
    const val FTMS_SERVICE = 0x1826
    const val DEVICE_INFORMATION_SERVICE = 0x180A

    /** Client Characteristic Configuration Descriptor (CCCD) for notifications/indications. */
    const val CCCD = 0x2902

    fun full(shortUuid: Int): String = "0000%04x-0000-1000-8000-00805f9b34fb".format(shortUuid)
}

/** Notification or indication received from the treadmill, uninterpreted. */
class GattPacket(val characteristic: FtmsCharacteristic, val value: ByteArray) {
    override fun toString(): String = "GattPacket(${characteristic.shortName}, ${value.toHex()})"
}

/** Treadmill Data packet (0x2ACD) exactly as FTMS describes it, without firmware corrections. */
data class TreadmillDataPacket(
    val flags: Int,
    val speedKmh: Double?,
    val averageSpeedKmh: Double?,
    val totalDistanceMeters: Int?,
    val inclinePercent: Double?,
    val rampAngleDegrees: Double?,
    val positiveElevationGainMeters: Double?,
    val negativeElevationGainMeters: Double?,
    val instantaneousPace: Double?,
    val averagePace: Double?,
    val totalEnergyKcal: Int?,
    /** "Energy per hour" according to FTMS. **On this treadmill it is the stopwatch minutes.** */
    val energyPerHourRaw: Int?,
    /** "Energy per minute" according to FTMS. **On this treadmill it is the stopwatch seconds.** */
    val energyPerMinuteRaw: Int?,
    val heartRateRaw: Int?,
    val metabolicEquivalent: Double?,
    val elapsedSeconds: Int?,
    val remainingSeconds: Int?,
    val forceOnBeltNewtons: Int?,
    val powerWatts: Int?,
    /** Leftover bytes after the fields indicated by the flags (there should be none). */
    val trailingBytes: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is TreadmillDataPacket &&
        copy(trailingBytes = ByteArray(0)) == other.copy(trailingBytes = ByteArray(0)) &&
        trailingBytes.contentEquals(other.trailingBytes)

    override fun hashCode(): Int = copy(trailingBytes = ByteArray(0)).hashCode() * 31 + trailingBytes.contentHashCode()
}

/**
 * Treadmill Data with the firmware quirks already corrected:
 *  - "kcal/h" and "kcal/min" are the stopwatch minutes and seconds -> [stopwatchSeconds].
 *  - Heart rate 0 = no heart rate monitor -> `null`.
 *  - Distance has a real resolution of 10 m (left as is).
 */
data class TreadmillSample(
    val speedKmh: Double,
    val distanceMeters: Int,
    val inclinePercent: Double,
    val calories: Int,
    val elapsedSeconds: Int,
    /** Stopwatch rebuilt from the "kcal/h" (min) and "kcal/min" (s) fields; `null` if they are missing. */
    val stopwatchSeconds: Int?,
    val heartRateBpm: Int?,
) {
    /** All zeros except the heart rate: what the treadmill sends when Idle or when resetting the session. */
    val isAllZero: Boolean
        get() = speedKmh == 0.0 && distanceMeters == 0 && calories == 0 && elapsedSeconds == 0 &&
            inclinePercent == 0.0
}

/** Fitness Machine Status (0x2ADA). */
sealed interface MachineStatus {
    /** FTMS op code of the notification. */
    val opCode: Int

    data class Reset(override val opCode: Int = 0x01) : MachineStatus

    /** `param`: 0x01 stop, 0x02 pause according to FTMS; this treadmill always sends 0x00 (it does not distinguish). */
    data class StoppedOrPaused(val param: Int?, override val opCode: Int = 0x02) : MachineStatus

    data class StoppedBySafetyKey(override val opCode: Int = 0x03) : MachineStatus
    data class StartedOrResumed(override val opCode: Int = 0x04) : MachineStatus

    /** `kmh == null` in the duplicated notification with value 0000 the treadmill sends after every change. */
    data class TargetSpeedChanged(val kmh: Double?, override val opCode: Int = 0x05) : MachineStatus

    /** `percent == null` in the duplicated notification with value 0000 (or in manual changes, which carry no value). */
    data class TargetInclineChanged(val percent: Double?, override val opCode: Int = 0x06) : MachineStatus
    data class TargetDistanceChanged(val meters: Int?, override val opCode: Int = 0x0D) : MachineStatus
    data class TargetTimeChanged(val seconds: Int?, override val opCode: Int = 0x0E) : MachineStatus
    data class ControlPermissionLost(override val opCode: Int = 0xFF) : MachineStatus

    /** Any other notification (resistance, power, heart rate, energy, step, stride targets…). */
    class Other(override val opCode: Int, val params: ByteArray) : MachineStatus {
        override fun toString(): String = "Other(op=0x%02x, params=%s)".format(opCode, params.toHex())
    }

    val displayName: String
        get() = when (this) {
            is Reset -> "Reset"
            is StoppedOrPaused -> "StoppedOrPausedByUser"
            is StoppedBySafetyKey -> "StoppedBySafetyKey"
            is StartedOrResumed -> "StartedOrResumedByUser"
            is TargetSpeedChanged -> "TargetSpeedChanged"
            is TargetInclineChanged -> "TargetInclineChanged"
            is TargetDistanceChanged -> "TargetDistanceChanged"
            is TargetTimeChanged -> "TargetTimeChanged"
            is ControlPermissionLost -> "ControlPermissionLost"
            is Other -> "Other"
        }
}

/** Training Status (0x2AD3). */
data class TrainingStatus(val flags: Int, val code: Int, val text: String?) {
    val name: String get() = TRAINING_NAMES[code] ?: "Unknown($code)"
    val isIdle: Boolean get() = code == IDLE
    val isManualMode: Boolean get() = code == MANUAL_MODE

    companion object {
        const val IDLE = 1
        const val MANUAL_MODE = 13
        private val TRAINING_NAMES = mapOf(
            0 to "Other", 1 to "Idle", 2 to "WarmingUp", 3 to "LowIntensityInterval",
            4 to "HighIntensityInterval", 5 to "RecoveryInterval", 6 to "Isometric", 7 to "HeartRateControl",
            8 to "FitnessTest", 9 to "SpeedOutsideLow", 10 to "SpeedOutsideHigh", 11 to "CoolDown",
            12 to "WattControl", 13 to "ManualMode", 14 to "PreWorkout", 15 to "PostWorkout",
        )
    }
}

enum class ControlPointResult(val code: Int) {
    Success(0x01),
    NotSupported(0x02),
    InvalidParameter(0x03),
    OperationFailed(0x04),
    ControlNotPermitted(0x05),
    Unknown(-1);

    companion object {
        fun fromCode(code: Int): ControlPointResult = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Control Point response (indication): `80 <opcode> <result>`. */
data class ControlPointResponse(val requestOpCode: Int, val resultCode: Int, val params: ByteArray = ByteArray(0)) {
    val result: ControlPointResult get() = ControlPointResult.fromCode(resultCode)
    val isSuccess: Boolean get() = resultCode == ControlPointResult.Success.code
    val requestName: String get() = FtmsCodec.commandName(requestOpCode)

    override fun equals(other: Any?): Boolean = other is ControlPointResponse &&
        requestOpCode == other.requestOpCode && resultCode == other.resultCode && params.contentEquals(other.params)

    override fun hashCode(): Int = (requestOpCode * 31 + resultCode) * 31 + params.contentHashCode()
}

/** Fitness Machine Feature (0x2ACC): two 32-bit fields. */
data class FtmsFeatures(val machineFeatures: Long, val targetSettingFeatures: Long) {
    val machineFeatureNames: List<String> get() = namesOf(machineFeatures, MACHINE_FEATURE_NAMES)
    val targetSettingNames: List<String> get() = namesOf(targetSettingFeatures, TARGET_SETTING_NAMES)

    private fun namesOf(bits: Long, names: List<String>): List<String> =
        names.indices.filter { bits and (1L shl it) != 0L }.map { names[it] }

    companion object {
        val MACHINE_FEATURE_NAMES = listOf(
            "AverageSpeed", "Cadence", "TotalDistance", "Inclination", "ElevationGain", "Pace", "StepCount",
            "Resistance", "StrideCount", "ExpendedEnergy", "HeartRate", "MetabolicEquivalent", "ElapsedTime",
            "RemainingTime", "PowerMeasurement", "ForceOnBelt", "UserDataRetention",
        )
        val TARGET_SETTING_NAMES = listOf(
            "SpeedTarget", "InclinationTarget", "ResistanceTarget", "PowerTarget", "HeartRateTarget",
            "ExpendedEnergyTarget", "StepNumberTarget", "StrideNumberTarget", "DistanceTarget",
            "TrainingTimeTarget", "TimeInTwoHrZones", "TimeInThreeHrZones", "TimeInFiveHrZones",
            "IndoorBikeSimulation", "WheelCircumference", "SpinDownControl", "TargetedCadence",
        )
    }
}

/** Device data read on connect (for the recording header). */
data class FtmsDeviceInfo(
    val address: String,
    val name: String?,
    val manufacturer: String?,
    val model: String?,
    val firmware: String?,
    val hardware: String?,
    val features: FtmsFeatures?,
    val featuresHex: String?,
    val speedRange: ValueRange?,
    val inclineRange: ValueRange?,
    /** `true` if the ranges could not be read and the documented ones are used. */
    val rangesAreDefaults: Boolean,
)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "Hex string with odd length: $this" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
