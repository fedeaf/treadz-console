package com.treadz.console.treadmill.simulator

import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.ValueRange
import com.treadz.console.treadmill.ftms.ControlPointResult
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsCodec
import com.treadz.console.treadmill.ftms.FtmsTransport
import com.treadz.console.treadmill.ftms.GattPacket
import com.treadz.console.treadmill.ftms.TransportDisconnect
import com.treadz.console.treadmill.ftms.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Simulated Reebok Z-Tech treadmill as an [FtmsTransport]. It mimics the real behaviour documented
 * in the protocol notes and verified against the treadmill:
 *
 *  - Start/Resume: ~3.4 s countdown, starts at 0.5 km/h and 0 %; Training Status ManualMode.
 *  - Speed ramps at 0.5 km/h per second (up and down). The reported speed is always a multiple of the speed step
 *    (0.1 km/h), like the real treadmill: ramp rates that are not a multiple of the step (e.g. 0.45 km/h per second)
 *    give steps of 0.4 and 0.5 alternately.
 *  - Incline: a target value that changes instantly, **only with the belt running**.
 *  - Pause: decelerates, keeps time/distance and lowers the incline to 0. Stop: decelerates and resets everything.
 *  - Safety key: hard stop, status 03, the incline does NOT go down; when put back, everything goes to 0.
 *  - BLE loss: the belt stops; on reconnect, the first packet is stale data.
 *  - Duplicated status notifications with value 0000 after every change.
 *
 * Used by the tests (with virtual time) and to run the UI without a treadmill. Besides the simulated physical
 * controls ([removeSafetyKey], [physicalSetSpeed]…), it can inject faults ([stopFault], [freezeData]…).
 *
 * It also models how the connection can fail on a phone: a Bluetooth adapter that can be turned off and on
 * ([setBluetoothEnabled]), a treadmill that is not advertising ([advertising]), a random static address that only
 * connects after a scan has seen the device ([requireScanBeforeConnect]), the platform's scan throttling
 * ([emulateScanThrottling]) and a name that the system may not know ([systemKnowsName], [gapNameReadable]).
 *
 * The treadmill values only change once per second; [DataRate] chooses how often packets are sent.
 *
 * @param scope scope where the data clock and the responses run (with `delay`, so it honours virtual time).
 * @param dataRate packet rate: 1 Hz like the PC client, or 2 Hz with every value repeated like Android (the default).
 */
class SimulatedTreadmill(
    private val scope: CoroutineScope,
    val address: String = "SI:MU:LA:TE:D0:01",
    /** Advertised BLE name. */
    val name: String = "REEBOK SIM",
    private val speedRange: ValueRange = ValueRange(0.5, 20.0, 0.1),
    private val inclineRange: ValueRange = ValueRange(0.0, 18.0, 1.0),
    val dataRate: DataRate = DataRate.Hz2Repeated,
    /** Time source for [scanStartTimes] and the scan throttling model (tests pass their virtual clock). */
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) : FtmsTransport {

    /**
     * Treadmill Data packet rate. In both cases the treadmill state (speed, time, distance...) advances once per
     * second; instant changes (incline, safety key) show up in the next packet.
     */
    enum class DataRate(val packetIntervalMs: Long, val repeatsEachValue: Boolean) {
        /** One packet per second, each with a new value (what the PC client saw). */
        Hz1(1_000, false),

        /** One packet every 500 ms; each value arrives twice (what an Android client sees). */
        Hz2Repeated(500, true),
    }

    /** What the simulator does with Treadmill Data packets when the data is frozen. */
    enum class FreezeMode {
        /** Stops sending Treadmill Data (the connection stays open). */
        Silent,

        /** Keeps sending, but always the same packet. */
        Repeat,
    }

    private enum class Mode { Idle, Countdown, Running, Paused, Stopping, KeyRemoved }

    private val _incoming = MutableSharedFlow<GattPacket>(extraBufferCapacity = 1024)
    override val incoming: SharedFlow<GattPacket> = _incoming.asSharedFlow()

    private val _disconnections = MutableSharedFlow<TransportDisconnect>(extraBufferCapacity = 16)
    override val disconnections: SharedFlow<TransportDisconnect> = _disconnections.asSharedFlow()

    private val lock = Any()

    // ---- treadmill state
    private var mode = Mode.Idle
    /** Reported speed: always on the speed-step grid. */
    private var speed = 0.0

    /** Exact (not rounded) speed the ramp works with. */
    private var exactSpeed = 0.0
    private var targetSpeed = 0.0
    private var incline = 0.0
    private var distanceMeters = 0.0
    private var kcal = 0.0
    private var seconds = 0
    private var heartRate = 0
    private var countdownIsResume = false

    // ---- connection
    private var connected = false
    private val subscribed = mutableSetOf<FtmsCharacteristic>()
    private var controlGranted = false
    private var tickJob: Job? = null
    private var burstJob: Job? = null
    private var lastDataPacket: ByteArray? = null
    private var pendingTrainingIdle = false

    // ---- options and faults
    /** Delay between the write and the Control Point indication (on the real treadmill: ~80 ms). */
    var controlPointLatencyMs: Long = 80

    /** Countdown duration after Start/Resume. */
    var countdownMs: Long = 3_400

    /** Speed ramp in km/h per second when accelerating (and when decelerating, unless [decelKmhPerSecond] is set). */
    var rampKmhPerSecond: Double = 0.5

    /** Deceleration in km/h per second after Stop/Pause; `null` = same as [rampKmhPerSecond]. */
    var decelKmhPerSecond: Double? = null

    /**
     * Random variation (+/- this many ms, deterministic seed) of the interval between data packets. The real
     * treadmill, seen from Android, sends packets every 390-590 ms; use 100 to mimic it.
     */
    var packetJitterMs: Long = 0

    /** `true` = accepts Stop and Pause (answers OK) **without executing them**, like the treadmill in the iConsole state. */
    var stopFault: Boolean = false

    /** `true` = Stop/Pause send the 02 status notification but the belt keeps its speed (the most treacherous fault). */
    var stopStatusOnlyFault: Boolean = false

    /** `true` = accepts SetSpeed (answers OK) without changing anything. */
    var speedFault: Boolean = false

    /** `true` = accepts SetIncline (answers OK) without changing anything. */
    var inclineFault: Boolean = false

    /** `true` = accepts Start (answers OK) but the belt never starts. */
    var startFault: Boolean = false

    /** Forced Control Point result per op code (e.g. `0x07 to OperationFailed`), without executing the command. */
    val forcedResults: MutableMap<Int, ControlPointResult> = java.util.concurrent.ConcurrentHashMap()

    /** If `true`, `connect()` fails (treadmill off or out of range). */
    var refuseConnections: Boolean = false

    /** The treadmill is advertising: scans see it. `false` = off or out of range (a direct connection fails too). */
    var advertising: Boolean = true

    /**
     * `true` = like Android with a random static address: `connect()` by address fails (GATT status 133) unless a scan
     * has seen the device since Bluetooth was last turned on. A [locate] or [scan] that sees it satisfies the condition.
     */
    var requireScanBeforeConnect: Boolean = false

    /** Name carried by the scan results (`null` = the advertisement has no name). */
    var scanName: String? = name

    /** `false` = the system does not know the name when the connection was made without a scan (Android gives `null`). */
    var systemKnowsName: Boolean = true

    /** Whether the Device Name characteristic (0x2A00) can be read; `false` = the read returns nothing. */
    var gapNameReadable: Boolean = true

    /** `true` = reading the Device Name fails with an error. */
    var gapNameReadFails: Boolean = false

    /** Time a scan takes to see an advertising treadmill. */
    var scanLatencyMs: Long = 200

    /**
     * `true` = Android scan throttling: from the 6th scan start within 30 s the scan silently returns nothing.
     * Whoever starts scans too often finds the treadmill "not advertising".
     */
    var emulateScanThrottling: Boolean = false

    /** Times (per [timeSource]) of every scan start ([locate] calls and [scan] collections). */
    val scanStartTimes: List<Long> get() = synchronized(lock) { scanStarts.toList() }
    private val scanStarts = mutableListOf<Long>()

    /** Number of scans that were silently throttled. */
    var throttledScans: Int = 0
        private set

    private val _bluetoothEnabled = MutableStateFlow(true)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private var seenByScan = false
    private var connectedName: String? = null
    override val deviceName: String? get() = connectedName

    /** If `true`, on reconnect the first data packet is the last one sent before the drop. */
    var staleFirstPacket: Boolean = true

    private var freeze: FreezeMode? = null
    private val jitterRandom = java.util.Random(42)

    /** Every packet written to the Control Point (for test assertions). */
    val receivedCommands: List<ByteArray> get() = synchronized(lock) { commandLog.toList() }
    private val commandLog = mutableListOf<ByteArray>()

    /** Time/distance target commands (0x0C/0x0D): the app must never send them. */
    val forbiddenCommands: List<ByteArray>
        get() = synchronized(lock) { commandLog.filter { it.isNotEmpty() && (it[0].toInt() == 0x0C || it[0].toInt() == 0x0D) } }

    var connectCount: Int = 0
        private set

    // ------------------------------------------------------------------ state view (tests)

    val currentSpeedKmh: Double get() = synchronized(lock) { speed }
    val currentInclinePercent: Double get() = synchronized(lock) { incline }
    val isBeltMoving: Boolean get() = synchronized(lock) { speed > 0.0 }
    val isConnected: Boolean get() = synchronized(lock) { connected }

    // ------------------------------------------------------------------ FtmsTransport

    override fun scan(): Flow<DiscoveredTreadmill> = flow {
        requireBluetooth()
        val visible = registerScanStart()
        while (scope.isActive) {
            requireBluetooth()
            if (advertising && visible) {
                synchronized(lock) { seenByScan = true }
                emit(DiscoveredTreadmill(address, scanName, rssi = -55))
            }
            delay(1_000)
        }
    }

    override suspend fun locate(address: String, timeoutMs: Long): DiscoveredTreadmill? {
        requireBluetooth()
        val visible = registerScanStart()
        if (address != this.address || !advertising || !visible || scanLatencyMs > timeoutMs) {
            delay(timeoutMs)
            requireBluetooth()
            return null
        }
        delay(scanLatencyMs)
        requireBluetooth()
        synchronized(lock) { seenByScan = true }
        return DiscoveredTreadmill(address, scanName, rssi = -55)
    }

    private fun requireBluetooth() {
        if (!_bluetoothEnabled.value) throw TransportException("Bluetooth off or unavailable", problem = ConnectionProblem.BluetoothOff)
    }

    /** Records a scan start; `false` if the platform would throttle it. */
    private fun registerScanStart(): Boolean = synchronized(lock) {
        val now = timeSource()
        scanStarts += now
        val inWindow = scanStarts.count { it > now - 30_000 }
        val throttled = emulateScanThrottling && inWindow > 5
        if (throttled) throttledScans++
        !throttled
    }

    /** Turns the simulated Bluetooth adapter off or on. Turning it off drops the connection, if any. */
    fun setBluetoothEnabled(enabled: Boolean) {
        if (_bluetoothEnabled.value == enabled) return
        _bluetoothEnabled.value = enabled
        if (!enabled) {
            synchronized(lock) { seenByScan = false }
            dropConnection("Bluetooth turned off")
        }
    }

    override suspend fun connect(address: String) {
        requireBluetooth()
        delay(50)
        requireBluetooth()
        synchronized(lock) {
            if (refuseConnections || address != this.address || !advertising) throw TransportException("Could not connect to $address")
            if (requireScanBeforeConnect && !seenByScan) {
                throw TransportException("Connection failed (GATT status 133): unknown address type, no scan has seen $address")
            }
            connectedName = if (seenByScan) scanName else if (systemKnowsName) name else null
            connected = true
            connectCount++
            subscribed.clear()
            controlGranted = false
        }
    }

    override suspend fun subscribe(characteristic: FtmsCharacteristic) {
        delay(10)
        val startBurst = synchronized(lock) {
            if (!connected) throw TransportException("Not connected")
            subscribed += characteristic
            subscribed.containsAll(REQUIRED_SUBSCRIPTIONS) && burstJob == null
        }
        if (startBurst) startInitialBurst()
    }

    override suspend fun read(characteristic: FtmsCharacteristic): ByteArray? {
        delay(10)
        synchronized(lock) { if (!connected) throw TransportException("Not connected") }
        return when (characteristic) {
            FtmsCharacteristic.ManufacturerName -> "ChangYow".toByteArray()
            FtmsCharacteristic.ModelNumber -> "SI3019".toByteArray()
            FtmsCharacteristic.FirmwareRevision -> "07".toByteArray()
            FtmsCharacteristic.HardwareRevision -> "3D.00.1F.01".toByteArray()
            FtmsCharacteristic.DeviceName -> when {
                gapNameReadFails -> throw TransportException("Read of the Device Name failed")
                gapNameReadable -> name.toByteArray()
                else -> null
            }
            // Distance, incline, elevation, steps, energy, HR and time; speed and incline targets (and more).
            FtmsCharacteristic.MachineFeature -> byteArrayOf(0x5C, 0x16, 0, 0, 0xF3.toByte(), 0x1F, 0, 0)
            FtmsCharacteristic.SupportedSpeedRange -> FtmsCodec.encodeSpeedRange(speedRange)
            FtmsCharacteristic.SupportedInclinationRange -> FtmsCodec.encodeInclineRange(inclineRange)
            else -> null
        }
    }

    override suspend fun writeControlPoint(value: ByteArray) {
        delay(5)
        synchronized(lock) {
            if (!connected) throw TransportException("Not connected")
            commandLog += value.copyOf()
        }
        scope.launch {
            delay(controlPointLatencyMs)
            handleCommand(value)
        }
    }

    override suspend fun disconnect() {
        synchronized(lock) { teardown() }
    }

    // ------------------------------------------------------------------ physical controls and faults

    /** Removes the safety key: hard stop, status 03, the incline stays where it was. */
    fun removeSafetyKey() {
        synchronized(lock) {
            if (mode == Mode.KeyRemoved) return
            mode = Mode.KeyRemoved
            zeroSpeed()
            targetSpeed = 0.0
            emitData()
            emitStatus(byteArrayOf(0x03))
        }
    }

    /** Puts the key back: the error is cleared, the platform goes down to 0 % and the session resets. */
    fun restoreSafetyKey() {
        synchronized(lock) {
            if (mode != Mode.KeyRemoved) return
            resetSession()
            mode = Mode.Idle
            emitStatus(byteArrayOf(0x02, 0x00))
            emitTraining(TRAINING_IDLE)
        }
    }

    /** BLE connection loss: the belt stops by itself and, on reconnect, the session is lost. */
    fun dropConnection(reason: String = "connection lost") {
        synchronized(lock) {
            if (!connected) return
            teardown()
            // The treadmill stops and beeps; the session is lost. The state is rebuilt on reconnect.
            val wasRunning = mode != Mode.Idle
            if (wasRunning) {
                resetSession()
                mode = Mode.Idle
            }
            _disconnections.tryEmit(TransportDisconnect(reason))
        }
    }

    /** Console speed button: changes the target (the real speed ramps up or down). */
    fun physicalSetSpeed(kmh: Double) {
        synchronized(lock) { if (mode == Mode.Running) targetSpeed = snap(kmh.coerceIn(speedRange.min, speedRange.max)) }
    }

    /** Console incline button: immediate change, with a status notification without value. */
    fun physicalSetIncline(percent: Double) {
        synchronized(lock) {
            if (mode != Mode.Running) return
            incline = percent.coerceIn(inclineRange.min, inclineRange.max)
            emitStatus(byteArrayOf(0x06, 0x00, 0x00))
        }
    }

    /** Console STOP button. */
    fun physicalStop() {
        synchronized(lock) { beginStop() }
    }

    /** Console START button. */
    fun physicalStart() {
        synchronized(lock) { if (mode == Mode.Idle || mode == Mode.Paused) beginCountdown(resume = mode == Mode.Paused) }
    }

    fun setHeartRate(bpm: Int) {
        synchronized(lock) { heartRate = bpm }
    }

    /** Freezes the data with the connection open. `null` unfreezes it. */
    fun freezeData(mode: FreezeMode?) {
        synchronized(lock) { freeze = mode }
    }

    // ------------------------------------------------------------------ internals

    private fun teardown() {
        connected = false
        subscribed.clear()
        controlGranted = false
        tickJob?.cancel()
        tickJob = null
        burstJob?.cancel()
        burstJob = null
    }

    /** What the treadmill sends on subscribe: a data packet (stale if we come from a drop) and the statuses. */
    private fun startInitialBurst() {
        synchronized(lock) {
            burstJob = scope.launch {
                delay(100)
                synchronized(lock) {
                    val stale = lastDataPacket
                    val first = if (staleFirstPacket && stale != null) stale else byteArrayOf(0x01, 0x00)
                    _incoming.tryEmit(GattPacket(FtmsCharacteristic.TreadmillData, first))
                }
                delay(400)
                synchronized(lock) {
                    emitStatus(byteArrayOf(0x02, 0x00))
                    emitTraining(TRAINING_IDLE)
                }
                startTicking()
            }
        }
    }

    private fun startTicking() {
        synchronized(lock) {
            tickJob?.cancel()
            tickJob = scope.launch {
                var packet = 0L
                while (isActive) {
                    val jitter = if (packetJitterMs > 0) jitterRandom.nextInt(2 * packetJitterMs.toInt() + 1) - packetJitterMs else 0L
                    delay(dataRate.packetIntervalMs + jitter)
                    packet++
                    // At 2 Hz every second packet advances the physics; the others repeat the previous values.
                    val advance = !dataRate.repeatsEachValue || packet % 2 == 0L
                    synchronized(lock) { tick(advance) }
                }
            }
        }
    }

    private fun tick(advance: Boolean) {
        if (!connected) return
        if (advance) advancePhysics()
        emitData()
    }

    /** One second of treadmill life. */
    private fun advancePhysics() {
        when (mode) {
            Mode.Running -> {
                moveSpeed(targetSpeed, rampKmhPerSecond)
                seconds++
                distanceMeters += speed / 3.6
                kcal += speed / 3.6 * 0.07 * (1 + incline / 10)
            }

            Mode.Paused, Mode.Stopping -> {
                if (pendingTrainingIdle) {
                    pendingTrainingIdle = false
                    emitTraining(TRAINING_IDLE)
                }
                moveSpeed(0.0, decelKmhPerSecond ?: rampKmhPerSecond)
                if (speed == 0.0 && mode == Mode.Stopping) {
                    resetSession()
                    mode = Mode.Idle
                }
            }

            Mode.Idle, Mode.Countdown, Mode.KeyRemoved -> Unit
        }
    }

    /** Moves the exact speed towards [target] by at most [step] and reports it rounded to the speed-step grid. */
    private fun moveSpeed(target: Double, step: Double) {
        val d = target - exactSpeed
        exactSpeed = (if (abs(d) <= step) target else exactSpeed + step * (if (d > 0) 1 else -1)).coerceAtLeast(0.0)
        speed = snap(exactSpeed)
    }

    private fun zeroSpeed() {
        speed = 0.0
        exactSpeed = 0.0
    }

    /** Rounds to the treadmill's speed step (0.1 km/h): the real one never reports anything else. */
    private fun snap(kmh: Double): Double {
        val step = speedRange.step
        if (step <= 0.0) return kmh
        return Math.round(Math.round(kmh / step) * step * 100.0) / 100.0
    }

    private fun resetSession() {
        zeroSpeed()
        targetSpeed = 0.0
        incline = 0.0
        distanceMeters = 0.0
        kcal = 0.0
        seconds = 0
        pendingTrainingIdle = false
    }

    private fun emitData() {
        val packet = FtmsCodec.encodeTreadmillData(
            speedKmh = speed,
            distanceMeters = (distanceMeters.toInt() / 10) * 10, // real resolution: 10 m
            inclinePercent = incline,
            calories = kcal.toInt(),
            stopwatchSeconds = seconds,
            heartRate = heartRate,
        )
        lastDataPacket = packet
        when (freeze) {
            FreezeMode.Silent -> return
            FreezeMode.Repeat -> {
                _incoming.tryEmit(GattPacket(FtmsCharacteristic.TreadmillData, frozenPacket ?: packet.also { frozenPacket = it }))
                return
            }

            null -> frozenPacket = null
        }
        if (FtmsCharacteristic.TreadmillData in subscribed) _incoming.tryEmit(GattPacket(FtmsCharacteristic.TreadmillData, packet))
    }

    private var frozenPacket: ByteArray? = null

    private fun emitStatus(bytes: ByteArray) {
        if (FtmsCharacteristic.MachineStatus in subscribed) _incoming.tryEmit(GattPacket(FtmsCharacteristic.MachineStatus, bytes))
    }

    private fun emitTraining(bytes: ByteArray) {
        if (FtmsCharacteristic.TrainingStatus in subscribed) _incoming.tryEmit(GattPacket(FtmsCharacteristic.TrainingStatus, bytes))
    }

    private fun emitResponse(op: Int, result: ControlPointResult) {
        _incoming.tryEmit(GattPacket(FtmsCharacteristic.ControlPoint, FtmsCodec.encodeControlPointResponse(op, result)))
    }

    private fun beginStop() {
        when (mode) {
            Mode.Running, Mode.Countdown, Mode.Paused -> {
                targetSpeed = 0.0
                countdownJob?.cancel()
                countdownJob = null
                mode = Mode.Stopping
                incline = 0.0
                pendingTrainingIdle = true
                emitStatus(byteArrayOf(0x02, 0x00))
            }

            else -> Unit
        }
    }

    private fun beginPause() {
        if (mode != Mode.Running) return
        targetSpeed = 0.0
        mode = Mode.Paused
        incline = 0.0
        emitStatus(byteArrayOf(0x02, 0x00))
    }

    private var countdownJob: Job? = null

    private fun beginCountdown(resume: Boolean) {
        if (startFault) return
        mode = Mode.Countdown
        countdownIsResume = resume
        countdownJob?.cancel()
        countdownJob = scope.launch {
            delay(countdownMs)
            synchronized(lock) {
                if (mode != Mode.Countdown) return@launch
                mode = Mode.Running
                targetSpeed = speedRange.min
                incline = 0.0
                if (countdownIsResume) {
                    emitStatus(byteArrayOf(0x06, 0x00, 0x00))
                    emitStatus(byteArrayOf(0x05, 0x00, 0x00))
                } else {
                    emitStatus(byteArrayOf(0x05, 0x00, 0x00))
                    emitTraining(TRAINING_MANUAL)
                }
            }
        }
    }

    private fun handleCommand(value: ByteArray) {
        synchronized(lock) {
            if (!connected || value.isEmpty()) return
            val op = value[0].toInt() and 0xFF
            val notPermitted = op != FtmsCodec.OP_REQUEST_CONTROL && !controlGranted
            if (notPermitted) {
                emitResponse(op, ControlPointResult.ControlNotPermitted)
                return
            }
            forcedResults[op]?.let {
                emitResponse(op, it)
                return
            }
            when (op) {
                FtmsCodec.OP_REQUEST_CONTROL -> {
                    controlGranted = true
                    emitResponse(op, ControlPointResult.Success)
                }

                FtmsCodec.OP_START_RESUME -> {
                    emitResponse(op, ControlPointResult.Success)
                    when (mode) {
                        Mode.Idle -> beginCountdown(resume = false)
                        Mode.Paused -> beginCountdown(resume = true)
                        else -> Unit
                    }
                }

                FtmsCodec.OP_STOP_PAUSE -> {
                    emitResponse(op, ControlPointResult.Success)
                    if (stopStatusOnlyFault) {
                        emitStatus(byteArrayOf(0x02, 0x00))
                    } else if (!stopFault) {
                        if (value.size > 1 && value[1].toInt() == 0x02) beginPause() else beginStop()
                    }
                }

                FtmsCodec.OP_SET_SPEED -> {
                    val raw = if (value.size >= 3) (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8) else -1
                    val kmh = raw / 100.0
                    if (raw < 0 || kmh < speedRange.min - 1e-9 || kmh > speedRange.max + 1e-9) {
                        emitResponse(op, ControlPointResult.InvalidParameter)
                        return
                    }
                    emitResponse(op, ControlPointResult.Success)
                    if (mode == Mode.Running && !speedFault) {
                        targetSpeed = snap(kmh)
                        emitStatus(byteArrayOf(0x05, value[1], value[2]))
                        scheduleDuplicateNotice(byteArrayOf(0x05, 0x00, 0x00))
                    }
                }

                FtmsCodec.OP_SET_INCLINE -> {
                    val raw = if (value.size >= 3) ((value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)).toShort().toInt() else Int.MIN_VALUE
                    val percent = raw / 10.0
                    if (raw == Int.MIN_VALUE || percent < inclineRange.min - 1e-9 || percent > inclineRange.max + 1e-9) {
                        emitResponse(op, ControlPointResult.InvalidParameter)
                        return
                    }
                    emitResponse(op, ControlPointResult.Success)
                    // When stopped it answers OK but does nothing.
                    if (mode == Mode.Running && !inclineFault) {
                        incline = percent
                        emitStatus(byteArrayOf(0x06, value[1], value[2]))
                        scheduleDuplicateNotice(byteArrayOf(0x06, 0x00, 0x00))
                    }
                }

                // Distance/time targets: the treadmill accepts them (and turns the fields into countdowns).
                // The app must never send them; they are recorded in [forbiddenCommands].
                0x0C, 0x0D -> emitResponse(op, ControlPointResult.Success)

                else -> emitResponse(op, ControlPointResult.NotSupported)
            }
        }
    }

    /** The treadmill repeats every change notification with value 0000 about 450 ms later. */
    private fun scheduleDuplicateNotice(bytes: ByteArray) {
        scope.launch {
            delay(450)
            synchronized(lock) { if (connected) emitStatus(bytes) }
        }
    }

    private companion object {
        val TRAINING_IDLE = byteArrayOf(0x01, 0x01)
        val TRAINING_MANUAL = byteArrayOf(0x01, 0x0D)
        val REQUIRED_SUBSCRIPTIONS = setOf(
            FtmsCharacteristic.TreadmillData,
            FtmsCharacteristic.MachineStatus,
            FtmsCharacteristic.TrainingStatus,
            FtmsCharacteristic.ControlPoint,
        )
    }
}
