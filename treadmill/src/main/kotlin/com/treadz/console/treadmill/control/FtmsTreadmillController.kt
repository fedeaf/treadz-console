package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillCapabilities
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.ValueRange
import com.treadz.console.treadmill.ftms.ControlPointResponse
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsCodec
import com.treadz.console.treadmill.ftms.FtmsDeviceInfo
import com.treadz.console.treadmill.ftms.FtmsFeatures
import com.treadz.console.treadmill.ftms.FtmsTransport
import com.treadz.console.treadmill.ftms.GattPacket
import com.treadz.console.treadmill.ftms.MachineStatus
import com.treadz.console.treadmill.ftms.TrainingStatus
import com.treadz.console.treadmill.ftms.TransportDisconnect
import com.treadz.console.treadmill.ftms.TransportException
import com.treadz.console.treadmill.ftms.TreadmillSample
import com.treadz.console.treadmill.ftms.toHex
import com.treadz.console.treadmill.lifecycle.AppVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Timings and thresholds of the safety layer. The defaults are tuned for the real Z-Tech.
 *
 * Every threshold is expressed in **time** (measured with the injected [Clock]), never in number of packets: the
 * packet rate depends on the client (about 1 Hz from a PC, about 2 Hz on Android where each value arrives twice) and
 * the treadmill values only change about once per second.
 */
data class ControllerConfig(
    /** No Treadmill Data packet at all for this long (with the connection open) -> `DataStalled`. */
    val stallTimeoutMs: Long = 5_000,
    val watchdogIntervalMs: Long = 1_000,
    /** The elapsed time has not advanced for this long while the belt is running -> `DataStalled`. */
    val frozenDataMs: Long = 5_000,
    /** Wait for the Control Point indication after writing a command. */
    val controlPointResponseTimeoutMs: Long = 3_000,
    /** After Start/Resume the treadmill runs a ~3-4 s countdown. */
    val startTimeoutMs: Long = 12_000,
    /** After Stop/Pause the 02 status notification should arrive within ~1 s. */
    val stopStatusTimeoutMs: Long = 3_000,
    /** If no data arrives for this long while stopping, the stop is reported as not verified. */
    val noDataTimeoutMs: Long = 5_000,
    /** The speed has not decreased for this long while stopping/pausing -> not verified. */
    val stopNotDecreasingMs: Long = 3_000,
    /** The belt is considered stopped but has not decelerated for this long -> corrected back to Running. */
    val stoppedButMovingMs: Long = 3_000,
    /** The measured value has not got closer to the speed/incline target within this window -> not verified. */
    val approachWindowMs: Long = 5_000,
    /**
     * A speed different from the expected one only counts as a manual (console button) change once it has not
     * changed for this long: while the belt ramps the speed changes about every second.
     */
    val manualChangeSettleMs: Long = 1_500,
    /**
     * After Start/Resume the belt ramps up to its starting speed and speed changes are not reported as manual
     * changes until it has reached and held it. This is the maximum duration of that phase.
     */
    val startRampTimeoutMs: Long = 20_000,
    /**
     * Wait before each reconnection attempt (the last value repeats). It grows to 30 s so that a treadmill that is off
     * or out of range does not keep the radio busy; each attempt also runs one short scan ([locateTimeoutMs]), so the
     * scans stay far below Android's limit of 5 scan starts per 30 s.
     */
    val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 3_000, 5_000, 10_000, 20_000, 30_000),
    /** Before connecting (user connect and every reconnection attempt) the treadmill is looked for with a scan this long. */
    val locateTimeoutMs: Long = 8_000,
    /** After Bluetooth is turned on again the adapter needs a moment before it can scan and connect. */
    val bluetoothOnSettleMs: Long = 1_500,
)

/**
 * [TreadmillController] implementation on top of an [FtmsTransport]: a safety layer that verifies
 * every command against the treadmill data (an OK from the Control Point does not guarantee execution).
 * See the Z-Tech protocol notes, Safety section.
 *
 * Main rules:
 *  - Stop/Pause: the 02 status must arrive and the speed must keep decreasing (at least once every
 *    [ControllerConfig.stopNotDecreasingMs]) down to 0 (no fixed timeout).
 *  - Speed/incline: the measured value approaches the target.
 *  - Safety key (status 03) and its restoration (02 + Idle + all zeros).
 *  - Frozen data -> `DataStalled`; connection loss -> `ConnectionLost` and reconnection.
 *  - The first packet after connecting is stale and is discarded.
 *  - FTMS time/distance targets are never used.
 *
 * `suspend` functions run in [scope] (not on the caller's thread) and are not cancelled when the caller
 * is cancelled: a stop verification is never left half done.
 */
class FtmsTreadmillController(
    private val transport: FtmsTransport,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val config: ControllerConfig = ControllerConfig(),
    observers: List<ControllerObserver> = emptyList(),
) : TreadmillController, AppVisibility {

    // ------------------------------------------------------------------ public API (flows)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _state = MutableStateFlow(TreadmillState())
    override val state: StateFlow<TreadmillState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow<TreadmillCapabilities?>(null)
    override val capabilities: StateFlow<TreadmillCapabilities?> = _capabilities.asStateFlow()

    private val _events = MutableSharedFlow<TreadmillEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TreadmillEvent> = _events.asSharedFlow()

    private val _pendingAlerts = MutableStateFlow<List<TreadmillAlert>>(emptyList())
    override val pendingAlerts: StateFlow<List<TreadmillAlert>> = _pendingAlerts.asStateFlow()

    override fun acknowledgeAlert(alert: TreadmillAlert) {
        val removed = synchronized(lock) {
            val before = _pendingAlerts.value
            _pendingAlerts.value = before - alert
            alert in before
        }
        if (removed) trace(TraceRecord.Note(now(), "Alert acknowledged by the user: $alert"))
    }

    private val _limits = MutableStateFlow(SafetyLimits())
    override val limits: StateFlow<SafetyLimits> = _limits.asStateFlow()

    private val _deviceInfo = MutableStateFlow<FtmsDeviceInfo?>(null)

    /** Device data read on connect (extra, not part of the UI contract). */
    val deviceInfo: StateFlow<FtmsDeviceInfo?> = _deviceInfo.asStateFlow()

    override fun setLimits(limits: SafetyLimits) {
        _limits.value = limits
        trace(TraceRecord.Note(now(), "Safety limits: speed<=${limits.maxSpeedKmh}, incline<=${limits.maxInclinePercent}"))
    }

    override val bluetoothEnabled: StateFlow<Boolean> get() = transport.bluetoothEnabled

    private val _connectionProblem = MutableStateFlow<ConnectionProblem?>(null)
    override val connectionProblem: StateFlow<ConnectionProblem?> = _connectionProblem.asStateFlow()

    /**
     * Never scans in the background: a scan requested then completes at once without sights, and a scan in progress
     * ends when the app goes to the background.
     */
    override fun scan(): Flow<DiscoveredTreadmill> = flow {
        if (!_appInForeground.value) {
            trace(TraceRecord.Note(now(), "scan() refused: the app is in the background"))
            return@flow
        }
        val stopped = whileInForeground { transport.scan().collect { emit(it) } } == null
        if (stopped) trace(TraceRecord.Note(now(), "Scan stopped: the app went to the background"))
    }

    // ------------------------------------------------------------------ app visibility

    private val _appInForeground = MutableStateFlow(true)
    override val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    /**
     * Visibility reported by the UI. In the background nothing is scanned and nothing is (re)connected: a connection
     * attempt in progress is abandoned, a reconnection pauses (it resumes by itself, to the same treadmill, when the
     * app is back in the foreground) and [scan] ends. An established connection is not touched.
     */
    override fun setAppInForeground(foreground: Boolean) {
        val changed = _appInForeground.getAndUpdate { foreground } != foreground
        if (!changed) return
        trace(TraceRecord.Note(now(), if (foreground) "App in the foreground" else "App in the background: no scans, no (re)connections"))
        if (!foreground) connectHandshake?.cancel()
    }

    override fun traceNote(text: String) = trace(TraceRecord.Note(now(), text))

    private class BackgroundPause : Exception()

    /**
     * Runs [block] until it finishes or the app goes to the background; in the second case [block] is cancelled and
     * `null` is returned. The result of a [block] returning `null` itself is indistinguishable, so use it with
     * non-null results (or [Unit]).
     */
    private suspend fun <T : Any> whileInForeground(block: suspend () -> T): T? = try {
        coroutineScope {
            val watcher = launch {
                _appInForeground.first { !it }
                throw BackgroundPause()
            }
            try {
                block()
            } finally {
                watcher.cancel()
            }
        }
    } catch (_: BackgroundPause) {
        null
    }

    // ------------------------------------------------------------------ internal state

    private val observers = observers.toMutableList()
    private val lock = Any()
    private val commandMutex = Mutex()
    private val connectMutex = Mutex()

    /** Belt state machine (guarded by [lock]). */
    private var status = BeltStatus.Idle
    private var lastSample: TreadmillSample? = null
    private var discardNextSample = false
    private var intent = Intent.None

    /** What the app or the physical buttons have set: the speed/incline the treadmill is heading to. */
    private var expectedSpeed: Double? = null
    private var expectedIncline: Double? = null
    private var speedCommandInFlight = false
    private var inclineCommandInFlight = false

    /**
     * The speed is moving away from [expectedSpeed] without an app command in flight: the console buttons are changing
     * it and the new target is not known until the speed settles. [TreadmillState.targetSpeedKmh] is `null` meanwhile.
     */
    private var speedTargetUnknown = false

    /** Targets when pausing, to send them again on resume. */
    private var savedSpeed: Double? = null
    private var savedIncline: Double? = null

    /** The Start/Resume in progress is a resume (if it fails, the belt stays Paused instead of Idle). */
    private var launchIsResume = false

    private var keyStopSeen = false
    private var keyIdleSeen = false
    private var keyZerosSeen = false

    private var lastPacketAt = 0L

    /** The published values are not current (connection lost/closed or frozen data). */
    private var stale = false
    private var silentStallReported = false
    private var frozenStallReported = false
    private var lastElapsed = -1

    /** Time at which the elapsed time last advanced (or the belt entered Running). */
    private var elapsedChangedAt = 0L

    /** Time at which the current speed value first showed up. */
    private var speedChangedAt = 0L

    /** Since when the belt is considered stopped but is not decelerating (-1 = not in that situation). */
    private var stoppedButMovingSince = -1L

    /** The belt is ramping up after Start/Resume: speed changes are not manual changes yet. */
    private var startRampActive = false
    private var startRampSince = 0L

    @Volatile private var userDisconnected = false
    private var targetAddress: String? = null
    @Volatile private var reconnectJob: Job? = null
    private var lastBluetoothTraced: Boolean? = null

    /** Handshake of the user-started connect in progress, so that [disconnect] can abandon it. */
    @Volatile private var connectHandshake: Job? = null

    /** The last scan saw the treadmill (used to tell "not found" from other connection failures). */
    @Volatile private var lastLocateSeen: Boolean? = null
    private var nameFromReadable: String? = null
    private var knownSpeedRange: ValueRange = SafetyClamp.DEFAULT_SPEED_RANGE
    private var knownInclineRange: ValueRange = SafetyClamp.DEFAULT_INCLINE_RANGE
    private var nextCommandId = 1L

    /** Id of the latest stop command: older commands still waiting in the queue are dropped (a stop jumps the queue). */
    private var lastStopCommandId = 0L

    /** Stop/pause calls currently in flight by name: a second call joins the first instead of sending the command again. */
    private val inFlight = HashMap<String, Deferred<CommandResult>>()

    private enum class Intent { None, Stopping, Pausing }

    // Observation log: what has arrived so far, so that verifications can walk through it.
    private sealed interface Obs {
        val seq: Long

        class Data(override val seq: Long, val sample: TreadmillSample, val at: Long) : Obs
        class Status(override val seq: Long, val status: MachineStatus) : Obs
        class Training(override val seq: Long) : Obs
        class Control(override val seq: Long, val response: ControlPointResponse) : Obs
        class Lost(override val seq: Long) : Obs
        /** A stop or pause command was issued: whatever is being verified gives way to it. */
        class Preempt(override val seq: Long, val command: String) : Obs
    }

    private class Cursor(var seq: Long)

    private val obsLog = ArrayDeque<Obs>()
    private var obsCounter = 0L
    private val lastSeq = MutableStateFlow(0L)

    init {
        // UNDISPATCHED: the subscription is in place before anyone can connect.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { packet ->
                try {
                    handleIncoming(packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trace(TraceRecord.Note(now(), "Error processing ${packet.characteristic.shortName}: $e"))
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.disconnections.collect { handleTransportDisconnect(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.bluetoothEnabled.collect { handleBluetoothState(it) }
        }
        scope.launch {
            while (isActive) {
                delay(config.watchdogIntervalMs)
                checkSilence()
            }
        }
    }

    fun addObserver(observer: ControllerObserver) {
        synchronized(lock) { observers += observer }
    }

    // ------------------------------------------------------------------ connection

    override suspend fun connect(address: String): CommandResult = scope.async {
        if (!_appInForeground.value) {
            trace(TraceRecord.Note(now(), "connect($address) refused: the app is in the background"))
            return@async CommandResult.NotAllowed(FailureCode.ConnectionFailed, "The app is in the background: not connecting")
        }
        if (!transport.bluetoothEnabled.value) {
            trace(TraceRecord.Note(now(), "connect($address) refused: Bluetooth is off"))
            _connectionProblem.value = ConnectionProblem.BluetoothOff
            return@async CommandResult.Rejected(FailureCode.ConnectionFailed, "Bluetooth is off", ConnectionProblem.BluetoothOff)
        }
        // A pending reconnection gives way to the explicit request (and releases the connection lock quickly).
        userDisconnected = false
        reconnectJob?.cancelAndJoin()
        connectMutex.withLock {
            val current = _connection.value
            if (current is ConnectionState.Connected && current.address == address) return@withLock CommandResult.Success
            userDisconnected = false
            targetAddress = address
            setConnection(ConnectionState.Connecting(address), null)
            try {
                handshakeAbandonable(address)
            } catch (e: CancellationException) {
                // disconnect() or the app going to the background abandoned this attempt (the caller itself is still active),
                // or the caller was cancelled.
                withContext(NonCancellable) { runCatching { transport.disconnect() } }
                if (!currentCoroutineContext().isActive) throw e
                trace(TraceRecord.Note(now(), "Connection attempt to $address abandoned (disconnect() or the app went to the background)"))
                _capabilities.value = null
                setConnection(ConnectionState.Disconnected, "connection attempt abandoned")
                return@withLock CommandResult.NotConnected
            } catch (e: Exception) {
                trace(TraceRecord.Note(now(), "Failed to connect to $address: $e"))
                runCatching { transport.disconnect() }
                _capabilities.value = null
                val problem = problemOf(e)
                _connectionProblem.value = problem
                setConnection(ConnectionState.Disconnected, "connection failed: ${e.message}")
                return@withLock CommandResult.Rejected(
                    FailureCode.ConnectionFailed,
                    "Could not connect: ${e.message ?: e::class.simpleName}",
                    problem,
                )
            }
            _connectionProblem.value = null
            requestControlLocked()
        }
    }.await()

    /**
     * Runs the handshake as its own job so that [disconnect] can cancel it without cancelling the caller. A failure
     * is rethrown to the caller instead of propagating to [scope].
     */
    private suspend fun handshakeAbandonable(address: String) {
        val outcome = CompletableDeferred<Throwable?>()
        val job = scope.launch {
            try {
                handshake(address)
                outcome.complete(null)
            } catch (e: CancellationException) {
                outcome.cancel(e)
                throw e
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        connectHandshake = job
        try {
            outcome.await()?.let { throw it }
        } finally {
            if (connectHandshake === job) connectHandshake = null
        }
    }

    /** Stable reason for a failed attempt: what the transport said, or "not found" if the scan never saw the treadmill. */
    private fun problemOf(e: Throwable): ConnectionProblem {
        val p = (e as? TransportException)?.problem ?: ConnectionProblem.Other
        if (p != ConnectionProblem.Other) return p
        if (!transport.bluetoothEnabled.value) return ConnectionProblem.BluetoothOff
        return if (lastLocateSeen == false) ConnectionProblem.DeviceNotFound else ConnectionProblem.Other
    }

    /** Connect, read capabilities, subscribe and get the controller ready. Throws if anything essential fails. */
    private suspend fun handshake(address: String) {
        locate(address)
        transport.connect(address)
        resetForNewConnection()
        val (info, caps) = readDeviceInfo(address)
        _deviceInfo.value = info
        _capabilities.value = caps
        trace(TraceRecord.Device(now(), info, caps))
        // Same subscription order as the client that was tested against the real treadmill.
        transport.subscribe(FtmsCharacteristic.TreadmillData)
        transport.subscribe(FtmsCharacteristic.MachineStatus)
        transport.subscribe(FtmsCharacteristic.TrainingStatus)
        transport.subscribe(FtmsCharacteristic.ControlPoint)
        synchronized(lock) { lastPacketAt = now() }
        setConnection(ConnectionState.Connected(address, nameFromReadable ?: transport.deviceName), null)
    }

    /**
     * Short scan for the treadmill before connecting. The system only knows the address type (the treadmill uses a
     * random static address) and the name once a scan has seen the device; connecting blindly by address fails.
     * Not seeing the treadmill is not fatal (the transport still tries a direct connection), but it is remembered to
     * report [ConnectionProblem.DeviceNotFound] if that fails too. Bluetooth off or a missing permission abort the attempt.
     */
    private suspend fun locate(address: String) {
        lastLocateSeen = null
        val seen = try {
            transport.locate(address, config.locateTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            if (e.problem == ConnectionProblem.BluetoothOff || e.problem == ConnectionProblem.PermissionMissing) throw e
            trace(TraceRecord.Note(now(), "Scan for $address failed, trying a direct connection: ${e.message}"))
            null
        }
        lastLocateSeen = seen != null
        trace(
            TraceRecord.Note(
                now(),
                if (seen != null) "Scan saw the treadmill $address (name=${seen.name}, rssi=${seen.rssi})"
                else "Scan did not see the treadmill $address within ${config.locateTimeoutMs} ms: trying a direct connection",
            ),
        )
    }

    private fun resetForNewConnection() {
        synchronized(lock) {
            status = BeltStatus.Idle
            lastSample = null
            // The first packet after (re)connecting is stale data from the previous session.
            discardNextSample = true
            intent = Intent.None
            expectedSpeed = null
            expectedIncline = null
            speedTargetUnknown = false
            speedCommandInFlight = false
            inclineCommandInFlight = false
            savedSpeed = null
            savedIncline = null
            keyStopSeen = false
            keyIdleSeen = false
            keyZerosSeen = false
            silentStallReported = false
            frozenStallReported = false
            lastElapsed = -1
            elapsedChangedAt = now()
            speedChangedAt = now()
            stoppedButMovingSince = -1L
            startRampActive = false
            stale = false
            _state.value = TreadmillState()
            trace(TraceRecord.StateChanged(now(), _state.value, "new-connection"))
        }
    }

    private suspend fun readDeviceInfo(address: String): Pair<FtmsDeviceInfo, TreadmillCapabilities> {
        val manufacturer = readString(FtmsCharacteristic.ManufacturerName)
        val model = readString(FtmsCharacteristic.ModelNumber)
        val firmware = readString(FtmsCharacteristic.FirmwareRevision)
        val hardware = readString(FtmsCharacteristic.HardwareRevision)
        val featureBytes = readRaw(FtmsCharacteristic.MachineFeature)
        val features: FtmsFeatures? = featureBytes?.let(FtmsCodec::decodeFeatures)
        val speed = readRaw(FtmsCharacteristic.SupportedSpeedRange)?.let(FtmsCodec::decodeSpeedRange)
        val incline = readRaw(FtmsCharacteristic.SupportedInclinationRange)?.let(FtmsCodec::decodeInclineRange)
        // Android has no name when it connects without having seen the device in a scan: ask the device itself
        // (standard Device Name characteristic). Last on purpose, and a failed read is harmless.
        nameFromReadable = if (transport.deviceName.isNullOrBlank()) {
            readString(FtmsCharacteristic.DeviceName).also {
                trace(TraceRecord.Note(now(), "Name unknown to the system: Device Name read gave ${it ?: "nothing"}"))
            }
        } else {
            null
        }
        val defaults = speed == null || incline == null
        if (defaults) trace(TraceRecord.Note(now(), "Could not read the ranges: using the documented ones (0.5-20 km/h, 0-18 %)"))
        knownSpeedRange = speed ?: SafetyClamp.DEFAULT_SPEED_RANGE
        knownInclineRange = incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE
        val info = FtmsDeviceInfo(
            address = address,
            name = nameFromReadable ?: transport.deviceName,
            manufacturer = manufacturer,
            model = model,
            firmware = firmware,
            hardware = hardware,
            features = features,
            featuresHex = featureBytes?.toHex(),
            speedRange = knownSpeedRange,
            inclineRange = knownInclineRange,
            rangesAreDefaults = defaults,
        )
        return info to TreadmillCapabilities(knownSpeedRange, knownInclineRange, manufacturer, model, firmware)
    }

    private suspend fun readRaw(c: FtmsCharacteristic): ByteArray? {
        val value = try {
            transport.read(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace(TraceRecord.Note(now(), "Could not read ${c.shortName}: $e"))
            null
        }
        trace(TraceRecord.RawRead(now(), c, value))
        return value
    }

    private suspend fun readString(c: FtmsCharacteristic): String? = readRaw(c)?.let(FtmsCodec::decodeString)

    /** Request Control (mandatory after connecting). Called with [connectMutex] held. */
    private suspend fun requestControlLocked(): CommandResult =
        commandMutex.withLock { requestControlBody() }

    private suspend fun requestControlBody(): CommandResult {
        val sent = sendCommand("requestControl", FtmsCodec.requestControl())
        return when (sent) {
            is Sent.Fail -> sent.result
            is Sent.Ok -> sent.response.let {
                when {
                    it == null -> CommandResult.NotVerified(FailureCode.ControlNotGranted, "The treadmill did not answer Request Control")
                    it.isSuccess -> CommandResult.Success
                    else -> CommandResult.Rejected(FailureCode.ControlNotGranted, "Request Control rejected: ${it.result}")
                }
            }
        }
    }

    override suspend fun disconnect() {
        scope.async {
            // Abandon whatever is in progress first (a pending reconnection, a scan, a connection attempt): they hold
            // the connection lock, and waiting for them to finish on their own could take a long time.
            userDisconnected = true
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            connectHandshake?.cancelAndJoin()
            connectMutex.withLock {
                userDisconnected = true
                val wasConnected = _connection.value is ConnectionState.Connected
                val moving = synchronized(lock) { status == BeltStatus.Running || status == BeltStatus.Starting }
                if (wasConnected && moving) {
                    // Closing BLE with the belt running stops it abruptly and with a beep: stop it first.
                    trace(TraceRecord.Note(now(), "disconnect() with the belt running: stopping before closing"))
                    runCatching { stop() }
                }
                runCatching { transport.disconnect() }
                _capabilities.value = null
                markStale("disconnected")
                _connectionProblem.value = null
                setConnection(ConnectionState.Disconnected, "disconnect requested")
                appendObs { Obs.Lost(it) }
            }
        }.await()
    }

    private fun handleTransportDisconnect(d: TransportDisconnect) {
        val address: String
        val wasActive: Boolean
        synchronized(lock) {
            val c = _connection.value
            if (userDisconnected || c !is ConnectionState.Connected) {
                trace(TraceRecord.Note(now(), "Disconnection ignored (${d.reason}): connection in state $c"))
                return
            }
            address = c.address
            wasActive = status != BeltStatus.Idle
            _capabilities.value = null
            markStale("connection-lost")
            // The event is traced before the connection change: the latter closes the session recording.
            if (wasActive) trace(TraceRecord.Event(now(), TreadmillEvent.ConnectionLost))
            setConnection(ConnectionState.Reconnecting(address, 1), d.reason)
            appendObs { Obs.Lost(it) }
        }
        if (wasActive) deliver(TreadmillEvent.ConnectionLost)
        startReconnect(address)
    }

    /**
     * Reconnection loop. Each attempt waits its backoff ([ControllerConfig.reconnectDelaysMs]), looks for the treadmill
     * with a short scan and connects. While Bluetooth is off it does not try at all (no counted attempts, no scans): it
     * waits for the adapter and goes on right after it is back. The same while the app is in the background: an attempt
     * in progress is cancelled, nothing is scanned or tried, and the loop starts over (from the first attempt, to the
     * same treadmill) when the app comes back to the foreground. [disconnect] cancels it at any point.
     */
    private fun startReconnect(address: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 1
            while (isActive && !userDisconnected) {
                if (!_appInForeground.value) {
                    setConnection(ConnectionState.Reconnecting(address, attempt), null)
                    trace(TraceRecord.Note(now(), "Reconnection paused: the app is in the background"))
                    _appInForeground.first { it }
                    trace(TraceRecord.Note(now(), "App is back in the foreground: resuming the reconnection"))
                    attempt = 1
                }
                setConnection(ConnectionState.Reconnecting(address, attempt), null)
                val step = whileInForeground { reconnectAttempt(address, attempt) }
                when {
                    step == null -> {
                        // The app went to the background. If the handshake had just completed, the connection stands.
                        if (_connection.value is ConnectionState.Connected) {
                            connectMutex.withLock { requestControlLocked() }
                            return@launch
                        }
                        trace(TraceRecord.Note(now(), "Reconnection attempt $attempt cancelled: the app went to the background"))
                    }
                    step == ReconnectStep.Done -> {
                        connectMutex.withLock { requestControlLocked() }
                        return@launch
                    }
                    step == ReconnectStep.Abort -> return@launch
                    step == ReconnectStep.NextAttempt -> attempt++
                    else -> Unit
                }
            }
        }
    }

    private enum class ReconnectStep { Done, Abort, SameAttempt, NextAttempt }

    /** One reconnection attempt: backoff (or wait for Bluetooth), scan, connect and handshake. */
    private suspend fun reconnectAttempt(address: String, attempt: Int): ReconnectStep {
        if (!transport.bluetoothEnabled.value) {
            _connectionProblem.value = ConnectionProblem.BluetoothOff
            trace(TraceRecord.Note(now(), "Reconnection paused: Bluetooth is off"))
            transport.bluetoothEnabled.first { it }
            trace(TraceRecord.Note(now(), "Bluetooth is back on: resuming the reconnection"))
            delay(config.bluetoothOnSettleMs)
        } else {
            val delays = config.reconnectDelaysMs
            delay(delays[minOf(attempt - 1, delays.lastIndex)])
        }
        if (userDisconnected) return ReconnectStep.Abort
        if (!transport.bluetoothEnabled.value) return ReconnectStep.SameAttempt
        try {
            connectMutex.withLock {
                if (userDisconnected) return ReconnectStep.Abort
                handshake(address)
                _connectionProblem.value = null
            }
            return ReconnectStep.Done
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
            throw e
        } catch (e: Exception) {
            trace(TraceRecord.Note(now(), "Reconnection $attempt failed: $e"))
            runCatching { transport.disconnect() }
            val problem = problemOf(e)
            _connectionProblem.value = problem
            // A failure caused by Bluetooth going off is not a failed attempt: the loop waits for it above.
            return if (problem != ConnectionProblem.BluetoothOff) ReconnectStep.NextAttempt else ReconnectStep.SameAttempt
        }
    }

    private fun handleBluetoothState(enabled: Boolean) {
        val changed = enabled != lastBluetoothTraced
        lastBluetoothTraced = enabled
        if (changed) trace(TraceRecord.Bluetooth(now(), enabled))
        if (enabled || !changed) return
        // Turning Bluetooth off drops the link: same as any unrequested connection loss (the transport reports it as
        // well, whichever comes first wins and the other is ignored).
        handleTransportDisconnect(TransportDisconnect("Bluetooth turned off"))
    }

    private fun setConnection(state: ConnectionState, detail: String?) {
        _connection.value = state
        trace(TraceRecord.Connection(now(), state, detail))
    }

    // ------------------------------------------------------------------ incoming data

    private fun handleIncoming(packet: GattPacket) {
        val at = now()
        trace(TraceRecord.RawReceived(at, packet.characteristic, packet.value))
        when (packet.characteristic) {
            FtmsCharacteristic.TreadmillData -> onTreadmillData(at, packet)
            FtmsCharacteristic.MachineStatus -> {
                val s = FtmsCodec.decodeMachineStatus(packet.value)
                if (s == null) malformed(at, packet, "Empty Machine Status") else onMachineStatus(at, s)
            }
            FtmsCharacteristic.TrainingStatus -> {
                val t = FtmsCodec.decodeTrainingStatus(packet.value)
                if (t == null) malformed(at, packet, "Training Status too short") else onTrainingStatus(at, t)
            }
            FtmsCharacteristic.ControlPoint -> {
                val r = FtmsCodec.decodeControlPointResponse(packet.value)
                if (r == null) {
                    malformed(at, packet, "Unrecognised Control Point response")
                } else {
                    synchronized(lock) {
                        trace(TraceRecord.ControlResponse(at, r))
                        appendObs { Obs.Control(it, r) }
                    }
                }
            }
            else -> trace(TraceRecord.Note(at, "Unexpected packet from ${packet.characteristic.shortName}"))
        }
    }

    private fun malformed(at: Long, packet: GattPacket, reason: String) {
        trace(TraceRecord.Malformed(at, packet.characteristic, packet.value, reason))
    }

    private fun onTreadmillData(at: Long, packet: GattPacket) {
        val decoded = FtmsCodec.decodeTreadmillData(packet.value)
        if (decoded == null) {
            malformed(at, packet, "Truncated Treadmill Data (${packet.value.size} bytes; MTU too small?)")
            return
        }
        val sample = FtmsCodec.toSample(decoded)
        val events = mutableListOf<TreadmillEvent>()
        synchronized(lock) {
            // Any Treadmill Data proves the data channel is still alive.
            lastPacketAt = at
            silentStallReported = false
            if (sample == null) {
                trace(TraceRecord.Note(at, "Treadmill Data without speed (flags=0x%04x): ignored".format(decoded.flags)))
                return
            }
            if (discardNextSample) {
                discardNextSample = false
                trace(TraceRecord.Sample(at, sample, discarded = true))
                trace(TraceRecord.Note(at, "First packet after connecting discarded (stale data)"))
                return
            }
            trace(TraceRecord.Sample(at, sample, discarded = false))
            processSample(at, sample, events)
            lastSample = sample
            // A new packet makes the data current again, unless the elapsed time is still frozen.
            stale = frozenStallReported
            publishState(at, "data")
            appendObs { Obs.Data(it, sample, at) }
        }
        events.forEach(::emitEvent)
    }

    /** State machine transitions driven by Treadmill Data. Called with [lock] held. */
    private fun processSample(at: Long, s: TreadmillSample, events: MutableList<TreadmillEvent>) {
        val prev = lastSample
        val prevSpeed = prev?.speedKmh ?: 0.0
        if (prev == null || abs(s.speedKmh - prev.speedKmh) >= SPEED_EPS) speedChangedAt = at
        when (status) {
            BeltStatus.Idle, BeltStatus.Paused, BeltStatus.Starting -> {
                // After Stop/Pause the belt keeps decelerating (speed>0 but decreasing): that is not starting.
                // Starting is a rising speed.
                if (s.speedKmh > 0.0 && s.speedKmh > prevSpeed + SPEED_EPS) {
                    enterRunning(s, observedBaseline = prev == null)
                } else {
                    if (status != BeltStatus.Starting && s.speedKmh > 0.0 && prev != null && s.speedKmh >= prevSpeed - SPEED_EPS) {
                        // Considered stopped, but the belt is not decelerating: the data wins.
                        if (stoppedButMovingSince < 0) stoppedButMovingSince = at
                        if (at - stoppedButMovingSince >= config.stoppedButMovingMs) {
                            trace(TraceRecord.Note(at, "State ${status.name} but the belt is still at ${s.speedKmh} km/h without decelerating for ${at - stoppedButMovingSince} ms: corrected to Running"))
                            enterRunning(s, observedBaseline = true)
                        }
                    } else {
                        stoppedButMovingSince = -1L
                    }
                    if (status != BeltStatus.Running && status != BeltStatus.Idle && s.isAllZero && prev != null && prev.elapsedSeconds > 0) {
                        // The treadmill reset the session without us receiving the Idle notification.
                        becomeIdle()
                    }
                }
            }

            BeltStatus.Running -> {
                if (s.isAllZero && prev != null && prev.elapsedSeconds > 0) {
                    becomeIdle()
                } else {
                    detectManualChanges(at, s, events)
                    detectFrozen(at, s, events)
                }
            }

            BeltStatus.SafetyKeyRemoved -> {
                if (s.isAllZero) keyZerosSeen = true
                maybeRestoreKey(events)
            }
        }
        if (status != BeltStatus.Running) {
            elapsedChangedAt = at
            frozenStallReported = false
        }
    }

    private fun enterRunning(s: TreadmillSample, observedBaseline: Boolean) {
        status = BeltStatus.Running
        speedTargetUnknown = false
        intent = Intent.None
        stoppedButMovingSince = -1L
        if (observedBaseline) {
            // Connected with the belt already running (or it did not stop): what we see is the baseline.
            expectedSpeed = s.speedKmh
            expectedIncline = s.inclinePercent
            startRampActive = false
        } else {
            // Start/Resume: the treadmill starts at its minimum speed and 0 % (it does not restore previous values).
            expectedSpeed = knownSpeedRange.min
            expectedIncline = 0.0
            // The belt is still ramping up to that speed (it may even still be at 0): not manual changes.
            startRampActive = true
            startRampSince = now()
        }
        elapsedChangedAt = now()
        lastElapsed = s.elapsedSeconds
    }

    private fun becomeIdle() {
        status = BeltStatus.Idle
        intent = Intent.None
        startRampActive = false
        expectedSpeed = null
        expectedIncline = null
        speedTargetUnknown = false
        savedSpeed = null
        savedIncline = null
    }

    private fun becomePaused() {
        status = BeltStatus.Paused
        startRampActive = false
        savedSpeed = expectedSpeed
        savedIncline = expectedIncline
        expectedIncline = 0.0 // the treadmill lowers the incline to 0 when pausing
    }

    /** Changes made with the physical buttons: they show up in Treadmill Data within < 1 s. Called with [lock] held. */
    private fun detectManualChanges(at: Long, s: TreadmillSample, events: MutableList<TreadmillEvent>) {
        if (intent != Intent.None) return
        var manualSpeed: Double? = null
        var manualIncline: Double? = null

        // The incline reported by the treadmill is the target value and changes instantly.
        val ei = expectedIncline
        if (!inclineCommandInFlight && ei != null && abs(s.inclinePercent - ei) >= INCLINE_EPS) {
            expectedIncline = s.inclinePercent
            manualIncline = s.inclinePercent
        }

        // The real speed ramps up and down (~0.5 km/h per second): it only counts as a manual change
        // once it has settled (unchanged for a while) on a value different from the expected one.
        val es = expectedSpeed
        val settled = at - speedChangedAt >= config.manualChangeSettleMs
        if (startRampActive) {
            // After Start/Resume the belt ramps up to its starting speed: not manual changes until it has
            // reached that speed and held it (or the ramp phase has taken too long).
            val reached = es != null && abs(s.speedKmh - es) < SPEED_TOLERANCE
            if (reached && settled) {
                startRampActive = false
            } else if (at - startRampSince >= config.startRampTimeoutMs) {
                startRampActive = false
                trace(TraceRecord.Note(at, "Start ramp did not settle within ${config.startRampTimeoutMs} ms: manual change detection enabled"))
            }
        } else if (!speedCommandInFlight && es != null) {
            if (abs(s.speedKmh - es) < SPEED_TOLERANCE) {
                speedTargetUnknown = false
            } else if (settled) {
                expectedSpeed = s.speedKmh
                speedTargetUnknown = false
                manualSpeed = s.speedKmh
            } else if (!speedTargetUnknown) {
                // Still ramping. Heading towards the target of an app command is normal; leaving it is the console.
                val previous = lastSample?.speedKmh
                val movingAway = previous != null && (s.speedKmh - previous) * (es - s.speedKmh) < -SPEED_EPS * SPEED_EPS
                if (movingAway) {
                    speedTargetUnknown = true
                    trace(TraceRecord.Note(at, "Speed ${s.speedKmh} is moving away from the target $es without an app command: console buttons, target unknown until it settles"))
                }
            }
        }
        if (manualSpeed != null || manualIncline != null) {
            events += TreadmillEvent.ManualChange(manualSpeed, manualIncline)
        }
    }

    private fun detectFrozen(at: Long, s: TreadmillSample, events: MutableList<TreadmillEvent>) {
        if (s.elapsedSeconds != lastElapsed) {
            elapsedChangedAt = at
            frozenStallReported = false
        }
        lastElapsed = s.elapsedSeconds
        if (at - elapsedChangedAt >= config.frozenDataMs && !frozenStallReported) {
            frozenStallReported = true
            events += TreadmillEvent.DataStalled
        }
    }

    private fun onMachineStatus(at: Long, s: MachineStatus) {
        val events = mutableListOf<TreadmillEvent>()
        var reRequestControl = false
        synchronized(lock) {
            trace(TraceRecord.Status(at, s))
            when (s) {
                is MachineStatus.StoppedOrPaused -> when (status) {
                    BeltStatus.Running, BeltStatus.Starting, BeltStatus.Paused -> {
                        // We know whether it was us: Stop -> Idle, Pause -> Paused. Otherwise assume a pause
                        // until the Idle Training Status arrives (the treadmill does not tell pause from stop).
                        if (intent == Intent.Stopping || (status == BeltStatus.Starting && !launchIsResume)) {
                            becomeIdle()
                        } else if (status != BeltStatus.Paused) {
                            becomePaused()
                        }
                    }

                    BeltStatus.SafetyKeyRemoved -> {
                        keyStopSeen = true
                        maybeRestoreKey(events)
                    }

                    BeltStatus.Idle -> Unit
                }

                is MachineStatus.StoppedBySafetyKey -> enterSafetyKey(events)
                is MachineStatus.ControlPermissionLost -> reRequestControl = true
                else -> Unit // 04/05/06/0D/0E: informational only, the values are read from Treadmill Data
            }
            publishState(at, "status:${s.displayName}")
            appendObs { Obs.Status(it, s) }
        }
        events.forEach(::emitEvent)
        if (reRequestControl) scope.launch { commandMutex.withLock { requestControlBody() } }
    }

    private fun onTrainingStatus(at: Long, t: TrainingStatus) {
        val events = mutableListOf<TreadmillEvent>()
        synchronized(lock) {
            trace(TraceRecord.Training(at, t))
            when {
                t.isIdle -> when (status) {
                    BeltStatus.Running, BeltStatus.Paused, BeltStatus.Starting -> becomeIdle()
                    BeltStatus.SafetyKeyRemoved -> {
                        keyIdleSeen = true
                        maybeRestoreKey(events)
                    }

                    BeltStatus.Idle -> Unit
                }

                t.isManualMode -> if (status == BeltStatus.Idle || status == BeltStatus.Starting || status == BeltStatus.Paused) {
                    enterRunning(lastSample ?: TreadmillSample(0.0, 0, 0.0, 0, 0, null, null), observedBaseline = false)
                }
            }
            publishState(at, "training:${t.name}")
            appendObs { Obs.Training(it) }
        }
        events.forEach(::emitEvent)
    }

    private fun enterSafetyKey(events: MutableList<TreadmillEvent>) {
        if (status == BeltStatus.SafetyKeyRemoved) return
        status = BeltStatus.SafetyKeyRemoved
        intent = Intent.None
        startRampActive = false
        keyStopSeen = false
        keyIdleSeen = false
        keyZerosSeen = false
        expectedSpeed = null
        expectedIncline = null
        speedTargetUnknown = false
        savedSpeed = null
        savedIncline = null
        events += TreadmillEvent.SafetyKeyRemoved
    }

    /** Key restored: status 02 and (Training Idle or all zeros). Called with [lock] held. */
    private fun maybeRestoreKey(events: MutableList<TreadmillEvent>) {
        if (status != BeltStatus.SafetyKeyRemoved) return
        if (keyStopSeen && (keyIdleSeen || keyZerosSeen)) {
            becomeIdle()
            events += TreadmillEvent.SafetyKeyRestored
        }
    }

    private fun publishState(at: Long, reason: String) {
        val s = lastSample
        _state.value = TreadmillState(
            status = status,
            speedKmh = s?.speedKmh ?: 0.0,
            targetSpeedKmh = if (speedTargetUnknown) null else expectedSpeed,
            inclinePercent = s?.inclinePercent ?: 0.0,
            distanceMeters = s?.distanceMeters ?: 0,
            elapsedSeconds = s?.elapsedSeconds ?: 0,
            calories = s?.calories ?: 0,
            heartRateBpm = s?.heartRateBpm,
            updatedAtMillis = at,
            isStale = stale,
        )
        trace(TraceRecord.StateChanged(at, _state.value, reason))
    }

    /** Marks the published values as not current. Called with [lock] held. */
    private fun markStale(reason: String) {
        if (stale) return
        stale = true
        _state.value = _state.value.copy(isStale = true)
        trace(TraceRecord.StateChanged(now(), _state.value, "stale:$reason"))
    }

    private fun checkSilence() {
        var emit = false
        synchronized(lock) {
            if (_connection.value is ConnectionState.Connected && !silentStallReported &&
                now() - lastPacketAt > config.stallTimeoutMs
            ) {
                silentStallReported = true
                emit = true
                markStale("no-data")
                trace(TraceRecord.Note(now(), "No Treadmill Data for ${now() - lastPacketAt} ms"))
            }
        }
        if (emit) emitEvent(TreadmillEvent.DataStalled)
    }

    // ------------------------------------------------------------------ commands

    override suspend fun start(): CommandResult = runCommand("start", emptyMap(), priority = false) { startBody() }

    override suspend fun pause(): CommandResult = joinOrRun("pause", priority = true) { pauseBody() }

    override suspend fun resume(): CommandResult = runCommand("resume", emptyMap(), priority = false) { resumeBody() }

    override suspend fun stop(): CommandResult = joinOrRun("stop", priority = true) { stopBody() }

    override suspend fun setSpeed(kmh: Double): CommandResult =
        runCommand("setSpeed", mapOf("requestedKmh" to kmh), priority = false) { setSpeedBody(kmh) }

    override suspend fun setIncline(percent: Double): CommandResult =
        runCommand("setIncline", mapOf("requestedPercent" to percent), priority = false) { setInclineBody(percent) }

    /**
     * Stop and pause are idempotent while in flight: a second tap (or a second caller) gets the result of the one
     * already running instead of sending the command to the treadmill again. A call made after it finished starts a
     * new command, so a failed stop can be retried.
     */
    private suspend fun joinOrRun(name: String, priority: Boolean, body: suspend () -> CommandResult): CommandResult {
        val origin = currentCoroutineContext()[CommandOrigin]
        var joinedId = 0L
        val deferred = synchronized(lock) {
            val existing = inFlight[name]
            if (existing != null) {
                joinedId = nextCommandId++
                existing
            } else {
                launchCommand(name, emptyMap(), priority, origin, body).also { created ->
                    inFlight[name] = created
                    created.invokeOnCompletion { synchronized(lock) { if (inFlight[name] === created) inFlight.remove(name) } }
                }
            }
        }
        if (joinedId == 0L) return deferred.await()
        trace(TraceRecord.CommandIssued(now(), joinedId, name, mapOf("joinedInFlight" to true), origin))
        val result = deferred.await()
        trace(TraceRecord.CommandFinished(now(), joinedId, name, result))
        return result
    }

    private suspend fun runCommand(
        name: String,
        args: Map<String, Any?>,
        priority: Boolean,
        body: suspend () -> CommandResult,
    ): CommandResult = launchCommand(name, args, priority, currentCoroutineContext()[CommandOrigin], body).await()

    /**
     * Registers a command (id, trace, and for a stop/pause the preemption of whatever is being verified) at the moment
     * of the call, so the order of the calls is the order of the queue, and runs it in [scope].
     */
    private fun launchCommand(
        name: String,
        args: Map<String, Any?>,
        priority: Boolean,
        origin: CommandOrigin?,
        body: suspend () -> CommandResult,
    ): Deferred<CommandResult> {
        val id = synchronized(lock) {
            val id = nextCommandId++
            if (priority) {
                if (name == "stop") lastStopCommandId = id
                appendObs { Obs.Preempt(it, name) }
            }
            id
        }
        trace(TraceRecord.CommandIssued(now(), id, name, args, origin))
        return scope.async {
            val result = try {
                commandMutex.withLock {
                    when {
                        _connection.value !is ConnectionState.Connected -> CommandResult.NotConnected
                        // A stop was issued after this command while it waited for the queue: the stop goes first.
                        name != "stop" && synchronized(lock) { lastStopCommandId > id } -> {
                            trace(TraceRecord.Note(now(), "$name dropped: a stop was issued after it"))
                            CommandResult.NotVerified(FailureCode.Preempted, "Superseded by a stop command")
                        }
                        else -> body()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CommandResult.NotVerified(FailureCode.InternalError, "Internal error in $name: $e")
            }
            trace(TraceRecord.CommandFinished(now(), id, name, result))
            val critical = when (result) {
                is CommandResult.NotVerified -> result.code != FailureCode.Preempted
                is CommandResult.Rejected -> name == "stop" || name == "pause"
                else -> false
            }
            if (critical) emitEvent(TreadmillEvent.CommandNotExecuted(name))
            result
        }
    }

    // -- Start

    private suspend fun startBody(): CommandResult {
        val (st, speed) = synchronized(lock) { status to (lastSample?.speedKmh ?: 0.0) }
        when (st) {
            BeltStatus.Running -> return CommandResult.NotAllowed(FailureCode.AlreadyRunning, "The belt is already running")
            BeltStatus.Starting -> return CommandResult.NotAllowed(FailureCode.AlreadyStarting, "The treadmill is already starting")
            BeltStatus.SafetyKeyRemoved -> return CommandResult.NotAllowed(FailureCode.SafetyKeyRemoved, "Safety key removed: put it back")
            BeltStatus.Paused -> return resumeBody()
            BeltStatus.Idle -> if (speed > SPEED_EPS) {
                return CommandResult.NotAllowed(FailureCode.BeltStillStopping, "The belt is still stopping")
            }
        }
        return launchBelt("start", resumeTargets = false)
    }

    private suspend fun resumeBody(): CommandResult {
        val (st, speed) = synchronized(lock) { status to (lastSample?.speedKmh ?: 0.0) }
        if (st != BeltStatus.Paused) return CommandResult.NotAllowed(FailureCode.NothingToResume, "There is no paused session to resume")
        if (speed > SPEED_EPS) return CommandResult.NotAllowed(FailureCode.BeltStillStopping, "The belt is still stopping")
        return launchBelt("resume", resumeTargets = true)
    }

    /** Start/Resume: command 07, the treadmill countdown and, only once the belt is moving, re-apply the targets. */
    private suspend fun launchBelt(name: String, resumeTargets: Boolean): CommandResult {
        val (targetSpeed, targetIncline) = synchronized(lock) {
            val pair = savedSpeed to savedIncline
            launchIsResume = resumeTargets
            status = BeltStatus.Starting
            publishState(now(), "command:$name")
            pair
        }
        val sent = sendCommand(name, FtmsCodec.startOrResume())
        val ok = when (sent) {
            is Sent.Fail -> return revertStarting(sent.result)
            is Sent.Ok -> sent
        }
        val response = ok.response
        if (response == null) {
            return revertStarting(CommandResult.NotVerified(FailureCode.NoResponse, "The treadmill did not answer the start command"))
        }
        if (!response.isSuccess) return revertStarting(CommandResult.Rejected(FailureCode.RejectedByTreadmill, "Start rejected: ${response.result}"))

        awaitMoving(ok.cursor, config.startTimeoutMs)?.let { return revertStarting(it) }

        if (resumeTargets) {
            // The treadmill does not restore speed/incline on resume: they are sent again, but only once running.
            val speedGoal = targetSpeed
            if (speedGoal != null && speedGoal > knownSpeedRange.min + SPEED_EPS) {
                val r = setSpeedBody(speedGoal)
                if (r !is CommandResult.Success) return r
            }
            val inclineGoal = targetIncline
            if (inclineGoal != null && inclineGoal > 0.0) {
                val r = setInclineBody(inclineGoal)
                if (r !is CommandResult.Success) return r
            }
        }
        return CommandResult.Success
    }

    private fun revertStarting(result: CommandResult): CommandResult {
        // If a stop command interrupted the start, the belt stays Starting (the treadmill may still start
        // after its countdown) and the Stop requires the 02 status to count as done.
        if (result is CommandResult.NotVerified && result.code == FailureCode.Preempted) return result
        synchronized(lock) {
            if (status == BeltStatus.Starting) {
                // If it was a resume, the session is still paused on the treadmill; otherwise Idle.
                status = if (launchIsResume) BeltStatus.Paused else BeltStatus.Idle
                publishState(now(), "command:start-failed")
            }
        }
        return result
    }

    /** `null` = the belt is running (Running state and speed > 0); otherwise the failure reason. */
    private suspend fun awaitMoving(cursor: Cursor, timeoutMs: Long): CommandResult? {
        val deadline = now() + timeoutMs
        while (true) {
            if (synchronized(lock) { status == BeltStatus.Running && (lastSample?.speedKmh ?: 0.0) > 0.0 }) return null
            val obs = nextObs(cursor, deadline - now())
                ?: return CommandResult.NotVerified(FailureCode.NotStarted, "The belt did not start after ${timeoutMs / 1000} s")
            when (obs) {
                is Obs.Lost -> return CommandResult.NotConnected
                is Obs.Preempt -> return CommandResult.NotVerified(FailureCode.Preempted, "Interrupted by a ${obs.command} command")
                is Obs.Status -> if (obs.status is MachineStatus.StoppedBySafetyKey) {
                    return CommandResult.NotAllowed(FailureCode.SafetyKeyRemoved, "Safety key removed during the start")
                }
                else -> Unit
            }
            if (synchronized(lock) { status == BeltStatus.Idle || status == BeltStatus.Paused }) {
                return CommandResult.NotVerified(FailureCode.NotStarted, "The treadmill stopped during the start")
            }
        }
    }

    // -- Pause and Stop

    private suspend fun pauseBody(): CommandResult {
        val st = synchronized(lock) { status }
        if (st != BeltStatus.Running) return CommandResult.NotAllowed(FailureCode.BeltNotRunning, "Pausing is only possible with the belt running")
        return stopOrPause(name = "pause", bytes = FtmsCodec.pause(), newIntent = Intent.Pausing, requireStatus = true)
    }

    private suspend fun stopBody(): CommandResult {
        val (st, speed) = synchronized(lock) { status to (lastSample?.speedKmh ?: 0.0) }
        if (st == BeltStatus.SafetyKeyRemoved) {
            // The key has already stopped the belt; nothing is sent to a console showing E-07.
            trace(TraceRecord.Note(now(), "Stop with the key removed: the belt is already stopped, nothing sent"))
            return CommandResult.Success
        }
        val alreadyStopped = speed <= 0.0 && (st == BeltStatus.Idle || st == BeltStatus.Paused)
        return stopOrPause(name = "stop", bytes = FtmsCodec.stop(), newIntent = Intent.Stopping, requireStatus = !alreadyStopped)
    }

    private suspend fun stopOrPause(name: String, bytes: ByteArray, newIntent: Intent, requireStatus: Boolean): CommandResult {
        synchronized(lock) { intent = newIntent }
        try {
            val sent = sendCommand(name, bytes)
            val ok = when (sent) {
                is Sent.Fail -> return sent.result
                is Sent.Ok -> sent
            }
            val response = ok.response
            if (response != null && !response.isSuccess) {
                return CommandResult.Rejected(FailureCode.RejectedByTreadmill, "$name rejected: ${response.result}")
            }
            // No Control Point response is no reason to give up: what matters is what the belt does.
            if (response == null) trace(TraceRecord.Note(now(), "$name: no Control Point response, verifying with the data"))
            if (!requireStatus) return CommandResult.Success
            return verifyStopped(name, ok.cursor)
        } finally {
            synchronized(lock) { intent = Intent.None }
        }
    }

    /**
     * Stop/Pause verified with the data (no fixed timeout: deceleration depends on the speed):
     * the 02 (or 03) status arrives and the speed keeps decreasing (never [ControllerConfig.stopNotDecreasingMs] without
     * a decrease) down to 0. Time-based, not packet-based: at 2 Hz each value arrives twice.
     */
    private suspend fun verifyStopped(name: String, cursor: Cursor): CommandResult {
        val sentAt = now()
        // A stop given while the belt is already pausing: the 02 status of the pause has been seen and the belt is
        // already decelerating, so the stop only has to see the deceleration through.
        var sawStatus = name == "stop" && synchronized(lock) { status == BeltStatus.Paused }
        var lastSpeed = synchronized(lock) { lastSample?.speedKmh ?: 0.0 }
        var lastDecreaseAt = sentAt
        while (true) {
            val wait = if (sawStatus) config.noDataTimeoutMs
            else minOf(config.noDataTimeoutMs, sentAt + config.stopStatusTimeoutMs - now())
            val obs = nextObs(cursor, wait)
            if (obs == null) {
                return if (!sawStatus && now() >= sentAt + config.stopStatusTimeoutMs) {
                    if (lastSpeed > 0.0 && lastDecreaseAt <= sentAt) {
                        // Neither the status nor any deceleration: what the user needs to know is that the belt is still moving.
                        CommandResult.NotVerified(
                            FailureCode.StillMoving,
                            "$name: the speed is not decreasing ($lastSpeed km/h, no stop status (02) after ${now() - sentAt} ms): press STOP or pull the key",
                        )
                    } else {
                        CommandResult.NotVerified(FailureCode.StopNotConfirmed, "$name: the treadmill accepted the command but the stop status (02) never arrived")
                    }
                } else {
                    CommandResult.NotVerified(FailureCode.DataLost, "$name: data stopped arriving while the belt was stopping")
                }
            }
            when (obs) {
                // A stop gives way to nothing, but it does cut short a pause that is still decelerating.
                is Obs.Preempt -> if (name == "pause" && obs.command == "stop") {
                    return CommandResult.NotVerified(FailureCode.Preempted, "pause: interrupted by a stop command")
                }
                is Obs.Lost -> return CommandResult.NotVerified(FailureCode.ConnectionLost, "$name: connection lost while stopping")
                is Obs.Status -> if (obs.status is MachineStatus.StoppedOrPaused || obs.status is MachineStatus.StoppedBySafetyKey) {
                    sawStatus = true
                }
                is Obs.Data -> {
                    val v = obs.sample.speedKmh
                    if (v <= 0.0) {
                        if (sawStatus) return CommandResult.Success
                        // Speed 0 but no 02 status: wait for it until its deadline.
                    } else {
                        if (v < lastSpeed - SPEED_EPS) {
                            lastDecreaseAt = obs.at
                        } else if (obs.at - lastDecreaseAt >= config.stopNotDecreasingMs) {
                            return CommandResult.NotVerified(
                                FailureCode.StillMoving,
                                "$name: the speed is not decreasing ($v km/h for ${obs.at - lastDecreaseAt} ms): press STOP or pull the key",
                            )
                        }
                    }
                    lastSpeed = v
                }
                else -> Unit
            }
        }
    }

    // -- Speed and incline

    private suspend fun setSpeedBody(requestedKmh: Double): CommandResult {
        if (requestedKmh.isNaN()) return CommandResult.NotAllowed(FailureCode.InvalidValue, "Invalid speed")
        if (synchronized(lock) { status } != BeltStatus.Running) {
            return CommandResult.NotAllowed(FailureCode.BeltNotRunning, "The speed can only be changed with the belt running")
        }
        val target = SafetyClamp.speed(requestedKmh, knownSpeedRange, _limits.value)
        if (target != requestedKmh) trace(TraceRecord.Note(now(), "Speed clamped: $requestedKmh -> $target km/h"))
        synchronized(lock) { speedCommandInFlight = true }
        try {
            val sent = sendCommand("setSpeed", FtmsCodec.setSpeed(target))
            val ok = when (sent) {
                is Sent.Fail -> return sent.result
                is Sent.Ok -> sent
            }
            val response = ok.response ?: return CommandResult.NotVerified(FailureCode.NoResponse, "The treadmill did not answer the speed command")
            if (!response.isSuccess) return CommandResult.Rejected(FailureCode.RejectedByTreadmill, "Speed rejected: ${response.result}")
            val result = verifyApproach(ok.cursor, target, tolerance = SPEED_TOLERANCE) { it.speedKmh }
            if (result is CommandResult.Success) synchronized(lock) { expectedSpeed = target; speedTargetUnknown = false; publishState(now(), "target-speed") }
            return result
        } finally {
            synchronized(lock) { speedCommandInFlight = false }
        }
    }

    private suspend fun setInclineBody(requestedPercent: Double): CommandResult {
        if (requestedPercent.isNaN()) return CommandResult.NotAllowed(FailureCode.InvalidValue, "Invalid incline")
        if (synchronized(lock) { status } != BeltStatus.Running) {
            // When stopped the treadmill answers OK but does nothing.
            return CommandResult.NotAllowed(FailureCode.BeltNotRunning, "The incline can only be changed with the belt running")
        }
        val target = SafetyClamp.incline(requestedPercent, knownInclineRange, _limits.value)
        if (target != requestedPercent) trace(TraceRecord.Note(now(), "Incline clamped: $requestedPercent -> $target %"))
        synchronized(lock) { inclineCommandInFlight = true }
        try {
            val sent = sendCommand("setIncline", FtmsCodec.setIncline(target))
            val ok = when (sent) {
                is Sent.Fail -> return sent.result
                is Sent.Ok -> sent
            }
            val response = ok.response ?: return CommandResult.NotVerified(FailureCode.NoResponse, "The treadmill did not answer the incline command")
            if (!response.isSuccess) return CommandResult.Rejected(FailureCode.RejectedByTreadmill, "Incline rejected: ${response.result}")
            val result = verifyApproach(ok.cursor, target, tolerance = INCLINE_EPS) { it.inclinePercent }
            if (result is CommandResult.Success) synchronized(lock) { expectedIncline = target; publishState(now(), "target-incline") }
            return result
        } finally {
            synchronized(lock) { inclineCommandInFlight = false }
        }
    }

    /**
     * The measured value reaches the target or gets closer to it. The incline arrives within 1-2 packets and the speed
     * ramps (~0.5 km/h/s), so seeing it approach is enough; the rest of the ramp is not awaited.
     */
    private suspend fun verifyApproach(
        cursor: Cursor,
        target: Double,
        tolerance: Double,
        measure: (TreadmillSample) -> Double,
    ): CommandResult {
        val baseline = synchronized(lock) { lastSample?.let(measure) } ?: target
        val startedAt = now()
        while (true) {
            val obs = nextObs(cursor, config.noDataTimeoutMs)
                ?: return CommandResult.NotVerified(FailureCode.DataLost, "No data arriving to verify the change")
            when (obs) {
                is Obs.Lost -> return CommandResult.NotConnected
                is Obs.Preempt -> return CommandResult.NotVerified(FailureCode.Preempted, "Interrupted by a ${obs.command} command")
                is Obs.Status -> when (obs.status) {
                    is MachineStatus.StoppedBySafetyKey -> return CommandResult.NotVerified(FailureCode.SafetyKeyRemoved, "Safety key removed")
                    is MachineStatus.StoppedOrPaused -> return CommandResult.NotVerified(FailureCode.BeltStopped, "The belt stopped")
                    else -> Unit
                }
                is Obs.Data -> {
                    val v = measure(obs.sample)
                    if (abs(v - target) <= tolerance) return CommandResult.Success
                    if (abs(v - target) < abs(baseline - target) - SPEED_EPS) return CommandResult.Success
                    if (obs.at - startedAt >= config.approachWindowMs) {
                        return CommandResult.NotVerified(
                            FailureCode.TargetNotReached,
                            "The treadmill accepted the command but the value is not approaching the target ($v vs $target after ${obs.at - startedAt} ms)",
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ sending

    private sealed interface Sent {
        class Ok(val cursor: Cursor, val response: ControlPointResponse?) : Sent
        class Fail(val result: CommandResult) : Sent
    }

    /**
     * Writes to the Control Point and waits for its indication. The returned cursor starts before the write.
     *
     * A stop or pause issued meanwhile ends the wait of any other command at once ([FailureCode.Preempted]): it holds the
     * command lock, and an indication that never comes would otherwise delay the stop by the whole response timeout.
     * Only a stop itself and Request Control (without control nothing can be stopped) wait for their indication.
     * The command it interrupted may still be unanswered, and the FTMS specification lets the treadmill refuse a write
     * meanwhile: a stop whose write fails is written again every [STOP_REWRITE_INTERVAL_MS] until the response timeout.
     */
    private suspend fun sendCommand(name: String, bytes: ByteArray): Sent {
        val cursor = Cursor(lastSeq.value)
        val responseCursor = Cursor(cursor.seq)
        val writeDeadline = now() + config.controlPointResponseTimeoutMs
        while (true) {
            trace(TraceRecord.RawSent(now(), FtmsCharacteristic.ControlPoint, bytes))
            try {
                transport.writeControlPoint(bytes)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace(TraceRecord.Note(now(), "Failed to write $name: $e"))
                val connected = _connection.value is ConnectionState.Connected
                if (connected && name == "stop" && now() + STOP_REWRITE_INTERVAL_MS < writeDeadline) {
                    delay(STOP_REWRITE_INTERVAL_MS)
                    continue
                }
                return Sent.Fail(
                    if (!connected) CommandResult.NotConnected
                    else CommandResult.NotVerified(FailureCode.WriteFailed, "Error writing to the Control Point: ${e.message}"),
                )
            }
        }
        val op = bytes[0].toInt() and 0xFF
        val deadline = now() + config.controlPointResponseTimeoutMs
        var response: ControlPointResponse? = null
        while (response == null) {
            val obs = nextObs(responseCursor, deadline - now()) ?: break
            when (obs) {
                is Obs.Control -> if (obs.response.requestOpCode == op) response = obs.response
                is Obs.Lost -> return Sent.Fail(CommandResult.NotConnected)
                is Obs.Preempt -> if (name != "stop" && name != "requestControl") {
                    trace(TraceRecord.Note(now(), "$name: stopped waiting for its Control Point response, a ${obs.command} command was issued"))
                    return Sent.Fail(CommandResult.NotVerified(FailureCode.Preempted, "Interrupted by a ${obs.command} command"))
                }
                else -> Unit
            }
        }
        return Sent.Ok(cursor, response)
    }

    // ------------------------------------------------------------------ observation log

    private fun appendObs(factory: (Long) -> Obs) {
        synchronized(lock) {
            val obs = factory(++obsCounter)
            obsLog.addLast(obs)
            while (obsLog.size > OBS_CAPACITY) obsLog.removeFirst()
            lastSeq.value = obs.seq
        }
    }

    private fun findAfter(seq: Long): Obs? = synchronized(lock) { obsLog.firstOrNull { it.seq > seq } }

    /** Next observation after the cursor, or `null` if [timeoutMs] passes without any. */
    private suspend fun nextObs(cursor: Cursor, timeoutMs: Long): Obs? {
        findAfter(cursor.seq)?.let { cursor.seq = it.seq; return it }
        if (timeoutMs <= 0) return null
        withTimeoutOrNull(timeoutMs) { lastSeq.first { it > cursor.seq } } ?: return null
        val obs = findAfter(cursor.seq) ?: return null
        cursor.seq = obs.seq
        return obs
    }

    // ------------------------------------------------------------------ utilities

    private fun now(): Long = clock.nowMillis()

    private fun emitEvent(event: TreadmillEvent) {
        trace(TraceRecord.Event(now(), event))
        deliver(event)
    }

    /** Publishes an already traced event: alerts stay pending until the user acknowledges them. */
    private fun deliver(event: TreadmillEvent) {
        synchronized(lock) {
            val pending = _pendingAlerts.value
            when {
                event is TreadmillAlert && event !in pending -> _pendingAlerts.value = pending + event
                event is TreadmillEvent.SafetyKeyRestored -> _pendingAlerts.value = pending - TreadmillEvent.SafetyKeyRemoved
            }
        }
        _events.tryEmit(event)
    }

    private fun trace(record: TraceRecord) {
        val list = synchronized(lock) { observers.toList() }
        for (o in list) {
            try {
                o.onTrace(record)
            } catch (_: Throwable) {
                // A broken recorder must not affect treadmill control.
            }
        }
    }

    private companion object {
        /** The treadmill reports speeds in multiples of 0.1 km/h and never jitters: two values are the same below this. */
        const val SPEED_EPS = 0.05

        /**
         * A measured speed counts as "at" an expected/target speed within this distance. Below the 0.1 km/h step of
         * the treadmill: a single step is a different speed (a 0.1 km/h command is only verified once the data shows
         * it, and a single 0.1 km/h button press on the console is a manual change).
         */
        const val SPEED_TOLERANCE = 0.05
        const val INCLINE_EPS = 0.05
        const val OBS_CAPACITY = 512

        /** Time between two writes of a stop the treadmill refused (see [sendCommand]). */
        const val STOP_REWRITE_INTERVAL_MS = 250L
    }
}
