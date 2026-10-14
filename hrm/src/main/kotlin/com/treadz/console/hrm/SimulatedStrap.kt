package com.treadz.console.hrm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A simulated heart-rate strap (fake [HrmTransport]) for tests and for running the app without a strap. It behaves
 * like a chest strap such as the Coospo H9Z:
 *
 *  - It accepts **one connection**: while [heldByConsole] is true (the treadmill console has it) it does not
 *    advertise and connections fail, which the monitor must treat as "not found yet".
 *  - Once connected and subscribed it notifies the heart rate every [notifyIntervalMs] (1 s), taken from [bpmSource],
 *    with the contact bits ([contactSupported]) and RR intervals consistent with the heart rate ([sendRr]).
 *  - [takeOff]: the electrodes lose contact; it keeps notifying `0` bpm with "contact not detected" (or, without
 *    contact support, keeps repeating the last value, like a strap that cannot tell), and after
 *    [powerOffAfterRemovalSeconds] it switches itself off: the connection drops and it stops advertising until
 *    [putOn].
 *  - [dropConnection]: an unrequested loss (out of range); it keeps advertising.
 *
 * Time and the notification ticks are coroutine delays, so tests run on virtual time.
 */
class SimulatedStrap(
    private val scope: CoroutineScope,
    val address: String = "SI:MU:HR:00:00:01",
    val name: String = "HRM SIM",
    /** Heart rate of the wearer, sampled at every notification. */
    var bpmSource: () -> Int = { 72 },
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) : HrmTransport {

    private val _incoming = MutableSharedFlow<HrmPacket>(extraBufferCapacity = 256)
    override val incoming: SharedFlow<HrmPacket> = _incoming.asSharedFlow()

    private val _disconnections = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val disconnections: SharedFlow<String> = _disconnections.asSharedFlow()

    private val _bluetoothEnabled = MutableStateFlow(true)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val lock = Any()
    private var connected = false
    private var subscribed = mutableSetOf<HrmCharacteristic>()
    private var tickJob: Job? = null
    private var wearing = true
    private var poweredOn = true
    private var secondsOff = 0
    private var lastBpm = 0
    private var energy = 0.0

    /** The treadmill console holds the strap: it does not advertise and refuses connections. */
    @Volatile
    var heldByConsole: Boolean = false

    /** The strap reports its skin contact (bits 1-2 of the flags). */
    @Volatile
    var contactSupported: Boolean = true

    @Volatile
    var sendRr: Boolean = true

    @Volatile
    var sendEnergy: Boolean = false

    @Volatile
    var batteryLevel: Int? = 87

    @Volatile
    var location: BodySensorLocation? = BodySensorLocation.Chest

    @Volatile
    var notifyIntervalMs: Long = 1_000

    @Volatile
    var powerOffAfterRemovalSeconds: Int = 60

    /** Time a scan takes to see the strap. */
    @Volatile
    var scanLatencyMs: Long = 300

    /** `true` = connections fail (e.g. a flaky link). */
    @Volatile
    var refuseConnections: Boolean = false

    /** Number of connection attempts received. */
    @Volatile
    var connectAttempts: Int = 0
        private set

    /** Times (per the time source) of every [locate] call: each one is a scan start on a real device. */
    val locateStartTimes: List<Long> get() = synchronized(lock) { locateStarts.toList() }
    private val locateStarts = mutableListOf<Long>()

    val isConnected: Boolean get() = synchronized(lock) { connected }

    val isAdvertising: Boolean get() = synchronized(lock) { advertisingLocked() }

    private fun advertisingLocked() = poweredOn && !connected && !heldByConsole && _bluetoothEnabled.value

    // ------------------------------------------------------------------ physical actions

    /** The wearer takes the strap off. */
    fun takeOff() = synchronized(lock) {
        wearing = false
        secondsOff = 0
    }

    /** The wearer puts the strap on (and it powers on if it had switched itself off). */
    fun putOn() = synchronized(lock) {
        wearing = true
        poweredOn = true
    }

    /** Unrequested loss (the strap went out of range for a moment); it keeps advertising. */
    fun dropConnection(reason: String = "link lost") {
        val wasConnected = synchronized(lock) {
            val was = connected
            teardownLocked()
            was
        }
        if (wasConnected) _disconnections.tryEmit(reason)
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        _bluetoothEnabled.value = enabled
        if (!enabled) dropConnection("Bluetooth turned off")
    }

    // ------------------------------------------------------------------ transport

    override fun scan(): Flow<DiscoveredStrap> = flow {
        if (!_bluetoothEnabled.value) throw HrmTransportException("Bluetooth off", problem = StrapProblem.BluetoothOff)
        delay(scanLatencyMs)
        while (true) {
            if (isAdvertising) emit(DiscoveredStrap(address, name, -60))
            delay(1_000)
        }
    }

    override suspend fun locate(address: String, timeoutMs: Long): DiscoveredStrap? {
        if (!_bluetoothEnabled.value) throw HrmTransportException("Bluetooth off", problem = StrapProblem.BluetoothOff)
        synchronized(lock) { locateStarts += timeSource() }
        if (!address.equals(this.address, ignoreCase = true)) {
            delay(timeoutMs)
            return null
        }
        var waited = 0L
        delay(scanLatencyMs)
        waited += scanLatencyMs
        while (true) {
            if (isAdvertising) return DiscoveredStrap(this.address, name, -60)
            if (waited >= timeoutMs) return null
            val step = minOf(500L, timeoutMs - waited)
            delay(step)
            waited += step
        }
    }

    override suspend fun connect(address: String) {
        delay(200)
        synchronized(lock) {
            connectAttempts++
            if (!_bluetoothEnabled.value) throw HrmTransportException("Bluetooth off", problem = StrapProblem.BluetoothOff)
            if (!address.equals(this.address, ignoreCase = true) || refuseConnections || !advertisingLocked()) {
                throw HrmTransportException("Connection failed (GATT status 133)")
            }
            connected = true
            subscribed.clear()
        }
    }

    override suspend fun subscribe(characteristic: HrmCharacteristic) {
        delay(50)
        synchronized(lock) {
            if (!connected) throw HrmTransportException("Not connected")
            if (!hasLocked(characteristic)) throw HrmTransportException("No ${characteristic.shortName}")
            subscribed += characteristic
            if (characteristic == HrmCharacteristic.HeartRateMeasurement && tickJob == null) startTicking()
        }
    }

    override fun has(characteristic: HrmCharacteristic): Boolean = synchronized(lock) { hasLocked(characteristic) }

    private fun hasLocked(c: HrmCharacteristic) = when (c) {
        HrmCharacteristic.HeartRateMeasurement -> true
        HrmCharacteristic.BodySensorLocation -> location != null
        HrmCharacteristic.BatteryLevel -> batteryLevel != null
    }

    override suspend fun read(characteristic: HrmCharacteristic): ByteArray? {
        delay(50)
        synchronized(lock) {
            if (!connected) throw HrmTransportException("Not connected")
            return when (characteristic) {
                HrmCharacteristic.BodySensorLocation -> location?.let { byteArrayOf(it.code.toByte()) }
                HrmCharacteristic.BatteryLevel -> batteryLevel?.let { byteArrayOf(it.toByte()) }
                HrmCharacteristic.HeartRateMeasurement -> null
            }
        }
    }

    override suspend fun disconnect() {
        synchronized(lock) { teardownLocked() }
    }

    // ------------------------------------------------------------------ internals

    private fun teardownLocked() {
        connected = false
        subscribed.clear()
        tickJob?.cancel()
        tickJob = null
    }

    private fun startTicking() {
        tickJob = scope.launch {
            while (isActive) {
                delay(notifyIntervalMs)
                var powerOff = false
                synchronized(lock) {
                    if (!connected) return@launch
                    if (!wearing) {
                        secondsOff += (notifyIntervalMs / 1000).toInt().coerceAtLeast(1)
                        if (secondsOff > powerOffAfterRemovalSeconds) powerOff = true
                    }
                    if (!powerOff) _incoming.tryEmit(HrmPacket(HrmCharacteristic.HeartRateMeasurement, nextPacketLocked()))
                }
                if (powerOff) {
                    synchronized(lock) {
                        poweredOn = false
                        teardownLocked()
                    }
                    _disconnections.tryEmit("the strap switched itself off")
                    return@launch
                }
            }
        }
    }

    private fun nextPacketLocked(): ByteArray {
        val contact: SensorContact
        val bpm: Int
        if (wearing) {
            bpm = bpmSource().coerceIn(0, 300)
            lastBpm = bpm
            contact = if (contactSupported) SensorContact.Detected else SensorContact.NotSupported
        } else if (contactSupported) {
            bpm = 0
            contact = SensorContact.NotDetected
        } else {
            bpm = lastBpm
            contact = SensorContact.NotSupported
        }
        energy += bpm * 0.002
        val rr = if (sendRr && wearing && bpm > 0) {
            // As many beats as fit in the notification interval (at least one), consistent with the heart rate.
            val ms = (60_000.0 / bpm).roundToInt()
            val beats = (notifyIntervalMs / ms.toDouble()).roundToInt().coerceAtLeast(1)
            List(beats) { HeartRateCodec.millisToRr(ms) }
        } else {
            emptyList()
        }
        return HeartRateCodec.encodeMeasurement(bpm, contact, if (sendEnergy) energy.toInt() else null, rr)
    }
}
