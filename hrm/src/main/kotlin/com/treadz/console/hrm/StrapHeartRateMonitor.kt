package com.treadz.console.hrm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** Timings of the [StrapHeartRateMonitor]. */
data class StrapMonitorConfig(
    /** How long each search scan looks for the strap before pausing. */
    val searchScanMs: Long = 20_000,
    /** Pause between two search scans (scan + pause ≥ 30 s keeps within one scan start per 30 s). */
    val searchPauseMs: Long = 10_000,
    /** Wait after a failed connection attempt before searching again. */
    val retryAfterFailureMs: Long = 5_000,
    /** Without a notification for this long the current reading is dropped ([HeartRateMonitor.latest] = null). */
    val staleAfterMs: Long = 5_000,
)

/**
 * [HeartRateMonitor] over an [HrmTransport]: searches for the strap, connects, subscribes to the Heart Rate
 * Measurement, reads the body sensor location and the battery, and reconnects automatically after a loss.
 *
 * The search is a scan filtered by address (the strap's address may be random static: Android needs to have seen it in
 * a scan to connect). A strap that is not found is searched for again after a pause, forever, without reporting
 * anything: it may be held by the treadmill console, which is normal.
 *
 * Time comes from [now] and every wait is a coroutine delay, so tests run on virtual time.
 */
class StrapHeartRateMonitor(
    private val transport: HrmTransport,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val config: StrapMonitorConfig = StrapMonitorConfig(),
) : HeartRateMonitor {

    private val _connection = MutableStateFlow<StrapConnectionState>(StrapConnectionState.Inactive)
    override val connection: StateFlow<StrapConnectionState> = _connection.asStateFlow()

    private val _problem = MutableStateFlow<StrapProblem?>(null)
    override val problem: StateFlow<StrapProblem?> = _problem.asStateFlow()

    private val _latest = MutableStateFlow<StrapReading?>(null)
    override val latest: StateFlow<StrapReading?> = _latest.asStateFlow()

    private val _readings = MutableSharedFlow<StrapReading>(extraBufferCapacity = 256)
    override val readings: SharedFlow<StrapReading> = _readings.asSharedFlow()

    private val _battery = MutableStateFlow<Int?>(null)
    override val batteryPercent: StateFlow<Int?> = _battery.asStateFlow()

    private val _location = MutableStateFlow<BodySensorLocation?>(null)
    override val sensorLocation: StateFlow<BodySensorLocation?> = _location.asStateFlow()

    private val _address = MutableStateFlow<String?>(null)
    override val address: StateFlow<String?> = _address.asStateFlow()

    private val observers = CopyOnWriteArrayList<HrmObserver>()
    private val lock = Any()
    private var job: Job? = null

    /** Completed by the disconnection collector when the current link is lost. */
    @Volatile
    private var linkLost: CompletableDeferred<String>? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.incoming.collect(::onPacket) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.disconnections.collect { linkLost?.complete(it) } }
    }

    override fun scan(): Flow<DiscoveredStrap> = transport.scan()

    override fun use(address: String?) {
        val wanted = address?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (wanted == _address.value) return
            _address.value = wanted
            val previous = job
            job = scope.launch {
                previous?.cancelAndJoin()
                if (wanted == null) {
                    setState(StrapConnectionState.Inactive, "no strap wanted")
                    _problem.value = null
                } else {
                    run(wanted)
                }
            }
        }
    }

    override suspend fun release() {
        use(null)
        synchronized(lock) { job }?.join()
    }

    override fun addObserver(observer: HrmObserver) {
        observers += observer
    }

    override fun removeObserver(observer: HrmObserver) {
        observers -= observer
    }

    // ------------------------------------------------------------------ connection loop

    private suspend fun run(address: String) {
        var linked = false
        try {
            while (true) {
                if (!transport.bluetoothEnabled.value) {
                    _problem.value = StrapProblem.BluetoothOff
                    setState(StrapConnectionState.Searching(address), "Bluetooth is off")
                    transport.bluetoothEnabled.first { it }
                    _problem.value = null
                }
                setState(StrapConnectionState.Searching(address), null)
                val seen = try {
                    transport.locate(address, config.searchScanMs)
                } catch (e: HrmTransportException) {
                    onFailure("search", e)
                    delay(config.retryAfterFailureMs)
                    continue
                }
                _problem.value = null
                if (seen == null) {
                    trace(HrmTraceRecord.Note(now(), "strap $address not seen in ${config.searchScanMs} ms (off, out of range or held by another device)"))
                    delay(config.searchPauseMs)
                    continue
                }

                setState(StrapConnectionState.Connecting(address), "seen (rssi ${seen.rssi})")
                val lost = CompletableDeferred<String>()
                linkLost = lost
                try {
                    linked = true
                    transport.connect(address)
                    transport.subscribe(HrmCharacteristic.HeartRateMeasurement)
                    readOptional(HrmCharacteristic.BodySensorLocation)
                    readOptional(HrmCharacteristic.BatteryLevel)
                    if (transport.has(HrmCharacteristic.BatteryLevel)) {
                        try {
                            transport.subscribe(HrmCharacteristic.BatteryLevel)
                        } catch (e: HrmTransportException) {
                            trace(HrmTraceRecord.Note(now(), "battery notifications not available: ${e.message}"))
                        }
                    }
                } catch (e: HrmTransportException) {
                    onFailure("connect", e)
                    withContext(NonCancellable) { safeDisconnect() }
                    linked = false
                    delay(config.retryAfterFailureMs)
                    continue
                }
                if (lost.isCompleted) {
                    // Lost while subscribing.
                    linked = false
                    clearLink()
                    trace(HrmTraceRecord.Note(now(), "connection lost while setting up: ${lost.getCompleted()}"))
                    continue
                }
                setState(StrapConnectionState.Connected(address, seen.name), null)
                val reason = watch(lost)
                linked = false
                clearLink()
                setState(StrapConnectionState.Searching(address), "connection lost: $reason")
            }
        } finally {
            linkLost = null
            if (linked) withContext(NonCancellable) { safeDisconnect() }
            clearLink()
        }
    }

    /** While connected: drops a reading that went stale. Returns the reason when the link is lost. */
    private suspend fun watch(lost: CompletableDeferred<String>): String {
        while (true) {
            val reason = withTimeoutOrNull(1_000) { lost.await() }
            if (reason != null) return reason
            val r = _latest.value
            if (r != null && now() - r.atMillis > config.staleAfterMs) {
                _latest.value = null
                trace(HrmTraceRecord.Note(now(), "no heart-rate notification for ${now() - r.atMillis} ms: reading dropped"))
            }
        }
    }

    private suspend fun readOptional(c: HrmCharacteristic) {
        if (!transport.has(c)) {
            trace(HrmTraceRecord.RawRead(now(), c, null))
            return
        }
        val value = try {
            transport.read(c)
        } catch (e: HrmTransportException) {
            trace(HrmTraceRecord.Note(now(), "reading ${c.shortName} failed: ${e.message}"))
            return
        }
        trace(HrmTraceRecord.RawRead(now(), c, value))
        if (value != null) decodeSide(c, value)
    }

    private suspend fun safeDisconnect() {
        try {
            transport.disconnect()
        } catch (e: Exception) {
            trace(HrmTraceRecord.Note(now(), "disconnect failed: ${e.message}"))
        }
    }

    private fun clearLink() {
        _latest.value = null
        _battery.value = null
        _location.value = null
    }

    private fun onFailure(what: String, e: HrmTransportException) {
        _problem.value = e.problem
        trace(HrmTraceRecord.Note(now(), "$what failed: ${e.message}${e.problem?.let { " ($it)" } ?: ""}"))
    }

    private fun setState(state: StrapConnectionState, detail: String?) {
        if (_connection.value == state && detail == null) return
        _connection.value = state
        trace(HrmTraceRecord.Connection(now(), state, detail))
    }

    // ------------------------------------------------------------------ packets

    private fun onPacket(packet: HrmPacket) {
        val t = now()
        trace(HrmTraceRecord.RawReceived(t, packet.characteristic, packet.value))
        when (packet.characteristic) {
            HrmCharacteristic.HeartRateMeasurement -> {
                val m = try {
                    HeartRateCodec.decodeMeasurement(packet.value)
                } catch (e: HrmFormatException) {
                    trace(HrmTraceRecord.Malformed(t, packet.characteristic, packet.value, e.message ?: "malformed"))
                    return
                }
                val reading = StrapReading(m.bpm, m.sensorContact, m.rrIntervalsMs, m.energyExpendedKj, t)
                _latest.value = reading
                _readings.tryEmit(reading)
                trace(HrmTraceRecord.Reading(t, reading, m.rrIntervalsRaw))
            }
            else -> decodeSide(packet.characteristic, packet.value)
        }
    }

    private fun decodeSide(c: HrmCharacteristic, value: ByteArray) {
        try {
            when (c) {
                HrmCharacteristic.BatteryLevel -> _battery.value = HeartRateCodec.decodeBatteryLevel(value)
                HrmCharacteristic.BodySensorLocation -> _location.value = HeartRateCodec.decodeBodySensorLocation(value)
                HrmCharacteristic.HeartRateMeasurement -> Unit
            }
        } catch (e: HrmFormatException) {
            trace(HrmTraceRecord.Malformed(now(), c, value, e.message ?: "malformed"))
        }
    }

    private fun trace(record: HrmTraceRecord) {
        for (o in observers) {
            try {
                o.onTrace(record)
            } catch (_: Exception) {
                // An observer must never break the connection logic.
            }
        }
    }
}
