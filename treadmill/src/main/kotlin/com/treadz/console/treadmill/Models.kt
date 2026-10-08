package com.treadz.console.treadmill

data class DiscoveredTreadmill(
    val address: String,
    val name: String?,
    val rssi: Int,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val address: String, val name: String?) : ConnectionState

    /** Connection lost without the user closing it; the implementation tries to reconnect. */
    data class Reconnecting(val address: String, val attempt: Int) : ConnectionState
}

enum class BeltStatus {
    /** Stopped, no session on the treadmill. */
    Idle,

    /** Start command accepted; the treadmill is running its countdown. */
    Starting,
    Running,
    Paused,

    /** Emergency stop by the safety key (console shows E-07) until it is put back. */
    SafetyKeyRemoved,
}

/**
 * Snapshot of the treadmill state. Values are already corrected for the firmware quirks
 * ("kcal/h" and "kcal/min" fields that are really minutes and seconds, countdowns with targets, etc.).
 */
data class TreadmillState(
    val status: BeltStatus = BeltStatus.Idle,
    /** Real belt speed (includes the ~0.5 km/h per second ramp). */
    val speedKmh: Double = 0.0,
    /**
     * Target speed the treadmill is heading to: the one an app command asked for, or the one the console buttons
     * settled on (it is published once the speed stops changing, together with a `ManualChange`). `null` when unknown,
     * in particular **while the console buttons are changing the speed and the belt is still ramping**: a UI must not
     * draw a "target" arrow then.
     */
    val targetSpeedKmh: Double? = null,
    /** Incline according to the treadmill (the target value, not the measured platform position). */
    val inclinePercent: Double = 0.0,
    val distanceMeters: Int = 0,
    val elapsedSeconds: Int = 0,
    val calories: Int = 0,
    /** Heart rate forwarded by the console from the chest strap; `null` without a heart rate monitor (the treadmill sends 0). */
    val heartRateBpm: Int? = null,
    /** Time of the last received packet (epoch ms), to detect frozen data. */
    val updatedAtMillis: Long = 0,
    /**
     * The values are not current: connection lost or closed, or frozen data. The last known values are kept
     * (showing the belt as stopped could be false), and the UI should present them as stale.
     */
    val isStale: Boolean = false,
)

data class ValueRange(val min: Double, val max: Double, val step: Double)

data class TreadmillCapabilities(
    val speed: ValueRange,
    val incline: ValueRange,
    val manufacturer: String?,
    val model: String?,
    val firmware: String?,
)

data class SafetyLimits(
    val maxSpeedKmh: Double? = null,
    val maxInclinePercent: Double? = null,
)

/**
 * Why a scan, a connection attempt or an automatic reconnection could not be done. Stable values, for the UI to
 * translate (the exception and `reason` texts are English and only meant for the log).
 */
enum class ConnectionProblem {
    /** The Bluetooth adapter is off (or the device has none). Nothing is attempted until it is turned on. */
    BluetoothOff,

    /** A Bluetooth permission (scan or connect) is missing: the UI has to ask the user for it. */
    PermissionMissing,

    /** The treadmill was not seen by a scan and could not be reached directly: off, out of range or busy. */
    DeviceNotFound,

    /** Anything else (the details are in the recording). */
    Other,
}

/** Stable failure reason, for the UI to translate. Each result's `reason` text is only for the log. */
enum class FailureCode {
    /** The BLE connection could not be established or the essential treadmill data could not be read. */
    ConnectionFailed,

    /** The treadmill did not grant control (Request Control rejected or unanswered). */
    ControlNotGranted,

    AlreadyRunning,
    AlreadyStarting,
    SafetyKeyRemoved,

    /** The belt is still stopping; wait until it stops. */
    BeltStillStopping,

    /** Resume without any paused session. */
    NothingToResume,

    /** The command is only possible with the belt running. */
    BeltNotRunning,
    InvalidValue,

    /** The treadmill did not answer the command on the Control Point. */
    NoResponse,

    /** The treadmill rejected the command (FTMS result code ≠ OK). */
    RejectedByTreadmill,

    /** Error writing the command to the treadmill. */
    WriteFailed,

    /** The belt never started moving, or it stopped during the start. */
    NotStarted,

    /** Stop/pause accepted but the stop notification never arrived. */
    StopNotConfirmed,

    /** Stop/pause accepted but the speed is not decreasing. */
    StillMoving,

    /** Speed/incline accepted but the value is not approaching the target. */
    TargetNotReached,

    /** The belt stopped while a change was being applied. */
    BeltStopped,

    /** Data stopped arriving while the command was being verified. */
    DataLost,

    /** The connection was lost while the command was being verified. */
    ConnectionLost,

    /** A stop or pause command interrupted this command. */
    Preempted,
    InternalError,
}

sealed interface CommandResult {
    /** The treadmill accepted the command and the data confirm it is carried out. */
    data object Success : CommandResult

    data object NotConnected : CommandResult

    /** Any result other than [Success] or [NotConnected]. */
    sealed interface Failure : CommandResult {
        val code: FailureCode
        val reason: String

        /**
         * For a failed connection ([FailureCode.ConnectionFailed]): why it failed, to show the right message and
         * action (turn Bluetooth on, grant a permission, get closer to the treadmill). `null` for everything else.
         */
        val problem: ConnectionProblem? get() = null
    }

    /** The treadmill rejected the command, or the connection failed (then [problem] says why, when known). */
    data class Rejected(
        override val code: FailureCode,
        override val reason: String,
        override val problem: ConnectionProblem? = null,
    ) : Failure

    /** The treadmill answered OK (or did not answer) but the data do not confirm the command in the expected time. */
    data class NotVerified(override val code: FailureCode, override val reason: String) : Failure

    /** The command makes no sense in the current state (e.g. incline with the belt stopped). */
    data class NotAllowed(override val code: FailureCode, override val reason: String) : Failure
}

sealed interface TreadmillEvent {
    /** Key removed: emergency stop. The platform stays inclined until the key is put back. */
    data object SafetyKeyRemoved : TreadmillAlert

    /** Key put back: the treadmill clears the error, lowers the incline to 0 and resets the session. */
    data object SafetyKeyRestored : TreadmillEvent

    /** Connection lost during a session: the treadmill stops by itself and its session is lost. */
    data object ConnectionLost : TreadmillAlert

    /** A command accepted by the treadmill was not carried out (e.g. an ignored stop). Critical alert. */
    data class CommandNotExecuted(val command: String) : TreadmillAlert

    /** Data stopped arriving with the connection open. Critical alert. */
    data object DataStalled : TreadmillAlert

    /** The user changed speed or incline with the console's physical buttons. */
    data class ManualChange(val speedKmh: Double?, val inclinePercent: Double?) : TreadmillEvent
}

/** Safety event the user must acknowledge; it stays in [TreadmillController.pendingAlerts] until then. */
sealed interface TreadmillAlert : TreadmillEvent
