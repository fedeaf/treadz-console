package com.treadz.console.hrm

/** What the strap says about the contact of its electrodes with the skin (Heart Rate Measurement flags, bits 1-2). */
enum class SensorContact {
    /** The strap does not report contact: a value cannot be confirmed (nor ruled out) by the strap itself. */
    NotSupported,

    /** The strap reports no skin contact: the heart-rate value is not a measurement. */
    NotDetected,

    /** The strap reports skin contact. */
    Detected,
}

/** Body Sensor Location characteristic (0x2A38). */
enum class BodySensorLocation(val code: Int) {
    Other(0), Chest(1), Wrist(2), Finger(3), Hand(4), EarLobe(5), Foot(6), Unknown(-1);

    companion object {
        fun fromCode(code: Int): BodySensorLocation = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * A decoded Heart Rate Measurement notification (0x2A37).
 *
 * @property bpm Heart rate in beats per minute as sent (a strap without contact may send 0 or a stale value).
 * @property energyExpendedKj Cumulative energy in kJ when the strap includes it.
 * @property rrIntervalsMs RR intervals (time between beats) carried by this notification, oldest first, converted from
 *   1/1024 s to milliseconds (rounded). Empty when the strap sends none.
 * @property rrIntervalsRaw The same intervals in the original 1/1024 s units (no rounding), for the debug recording.
 */
data class HeartRateMeasurement(
    val bpm: Int,
    val sensorContact: SensorContact,
    val energyExpendedKj: Int? = null,
    val rrIntervalsMs: List<Int> = emptyList(),
    val rrIntervalsRaw: List<Int> = emptyList(),
)

/**
 * One reading of the strap connected to the app, as the rest of the app sees it.
 *
 * @property atMillis When it was received (epoch ms of the monitor's clock).
 */
data class StrapReading(
    val bpm: Int,
    val sensorContact: SensorContact,
    val rrIntervalsMs: List<Int>,
    val energyExpendedKj: Int?,
    val atMillis: Long,
) {
    /** The value can be used as a heart rate: non-zero and the strap does not report a lost contact. */
    val isUsable: Boolean get() = bpm > 0 && sensorContact != SensorContact.NotDetected
}

/** A strap seen by a scan. */
data class DiscoveredStrap(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * Connection to the user's strap. There are no error states: a strap that cannot be found is simply searched for
 * again (the treadmill console may be holding it: some straps accept one connection only and stop advertising while
 * they have one; that is normal and must not produce messages).
 */
sealed interface StrapConnectionState {
    /** No strap is wanted (no address set). */
    data object Inactive : StrapConnectionState

    /** Looking for the strap (not seen yet, or seen and lost). It may be off, out of range or held by the console. */
    data class Searching(val address: String) : StrapConnectionState

    data class Connecting(val address: String) : StrapConnectionState

    data class Connected(val address: String, val name: String?) : StrapConnectionState
}

/**
 * Why the monitor cannot even look for the strap. Only conditions the user can fix are reported; a strap that is not
 * found is not a problem.
 */
enum class StrapProblem {
    BluetoothOff,

    /** A Bluetooth permission (scan or connect) is missing: the UI asks for it. */
    PermissionMissing,
}
