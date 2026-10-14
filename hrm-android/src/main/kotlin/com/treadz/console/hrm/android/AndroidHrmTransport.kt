package com.treadz.console.hrm.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.treadz.console.hrm.DiscoveredStrap
import com.treadz.console.hrm.HrmCharacteristic
import com.treadz.console.hrm.HrmGattQueue
import com.treadz.console.hrm.HrmPacket
import com.treadz.console.hrm.HrmScanThrottle
import com.treadz.console.hrm.HrmTransport
import com.treadz.console.hrm.HrmTransportException
import com.treadz.console.hrm.HrmUuids
import com.treadz.console.hrm.StrapProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real BLE transport for heart-rate straps on top of `BluetoothGatt`.
 *
 * - Scans are filtered by the Heart Rate service (0x180D). There is a single platform scan at a time, shared by the
 *   user's pairing scan and the monitor's search ([acquireScan]); starts are throttled ([HrmScanThrottle], one per 30 s,
 *   leaving room for the treadmill's own scans within Android's limit of 5 per 30 s).
 * - **Connecting goes through a scan**: [locate] remembers the `BluetoothDevice` of the scan result, which carries the
 *   right address type (a strap's address may be random static); [connect] uses it. A strap held by the treadmill
 *   console does not advertise: [locate] simply returns `null`.
 * - Only the Heart Rate service (0x2A37 measurement, 0x2A38 body sensor location) and the Battery service (0x2A19)
 *   are resolved. Nothing is ever written to the strap except the notification descriptors (CCCD).
 * - A single GATT operation in flight ([HrmGattQueue]).
 * - The Bluetooth adapter state is tracked with `ACTION_STATE_CHANGED`; turning it off is reported as a connection loss.
 *
 * It cannot be tested on the PC (no device): the logic lives in `:hrm`.
 */
@SuppressLint("MissingPermission") // permissions are checked before scanning and connecting; failures become typed exceptions
class AndroidHrmTransport(context: Context) : HrmTransport {

    private val appContext = context.applicationContext
    private val adapter get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _incoming = MutableSharedFlow<HrmPacket>(extraBufferCapacity = 256)
    override val incoming: SharedFlow<HrmPacket> = _incoming.asSharedFlow()

    private val _disconnections = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val disconnections: SharedFlow<String> = _disconnections.asSharedFlow()

    private val _bluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val queue = HrmGattQueue()
    private val characteristics = ConcurrentHashMap<HrmCharacteristic, BluetoothGattCharacteristic>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready = false
    @Volatile private var closingByUs = false
    @Volatile private var connected: CompletableDeferred<Unit>? = null
    @Volatile private var servicesDiscovered: CompletableDeferred<Unit>? = null
    @Volatile private var disconnected: CompletableDeferred<Unit>? = null

    // ------------------------------------------------------------------ permissions and adapter

    private fun checkScanAllowed() {
        if (!HrmPermissions.hasScan(appContext)) {
            throw HrmTransportException("Missing BLUETOOTH_SCAN permission", problem = StrapProblem.PermissionMissing)
        }
        if (adapter?.isEnabled != true) throw HrmTransportException("Bluetooth off or unavailable", problem = StrapProblem.BluetoothOff)
    }

    private fun checkConnectAllowed() {
        if (!HrmPermissions.hasConnect(appContext)) {
            throw HrmTransportException("Missing BLUETOOTH_CONNECT permission", problem = StrapProblem.PermissionMissing)
        }
        if (adapter?.isEnabled != true) throw HrmTransportException("Bluetooth off or unavailable", problem = StrapProblem.BluetoothOff)
    }

    private fun onAdapterOff() {
        sightings.clear()
        synchronized(scanLock) {
            activeScanner = null
            scanning = false
        }
        scanEvents.tryEmit(ScanEvent.Failed(HrmTransportException("Bluetooth turned off", problem = StrapProblem.BluetoothOff)))
        gatt?.let { onLinkLost(it, "Bluetooth turned off") }
    }

    // ------------------------------------------------------------------ scanning

    private sealed interface ScanEvent {
        class Seen(val found: DiscoveredStrap) : ScanEvent
        class Failed(val error: HrmTransportException) : ScanEvent
    }

    private class Sighting(val device: BluetoothDevice, val name: String?, val rssi: Int, val atMs: Long) {
        fun found() = DiscoveredStrap(device.address, name, rssi)
    }

    private val scanEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val sightings = ConcurrentHashMap<String, Sighting>()
    private val throttle = HrmScanThrottle()
    private val scanLock = Any()
    private var scanUsers = 0
    private var scanning = false
    private var activeScanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.scanRecord?.deviceName ?: result.device.name
            } catch (_: SecurityException) {
                null
            }
            val sighting = Sighting(result.device, name, result.rssi, SystemClock.elapsedRealtime())
            sightings[normalize(result.device.address)] = sighting
            scanEvents.tryEmit(ScanEvent.Seen(sighting.found()))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Strap scan failed (code $errorCode)")
            synchronized(scanLock) {
                activeScanner = null
                scanning = false
            }
            scanEvents.tryEmit(ScanEvent.Failed(HrmTransportException("Scan failed (code $errorCode)")))
        }
    }

    /** One more user of the platform scan; starts it if nobody else is scanning. Pair with [releaseScan]. */
    private fun acquireScan() {
        synchronized(scanLock) {
            checkScanAllowed()
            if (!scanning) {
                val scanner = adapter?.bluetoothLeScanner
                    ?: throw HrmTransportException("Bluetooth off or unavailable", problem = StrapProblem.BluetoothOff)
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid(HrmUuids.HEART_RATE_SERVICE))).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
                try {
                    scanner.startScan(listOf(filter), settings, scanCallback)
                } catch (e: SecurityException) {
                    throw HrmTransportException("Missing BLUETOOTH_SCAN permission", e, StrapProblem.PermissionMissing)
                } catch (e: IllegalStateException) {
                    throw HrmTransportException("Bluetooth off or unavailable", e, StrapProblem.BluetoothOff)
                }
                throttle.recordStart(SystemClock.elapsedRealtime())
                activeScanner = scanner
                scanning = true
            }
            scanUsers++
        }
    }

    private fun releaseScan() {
        synchronized(scanLock) {
            if (scanUsers > 0) scanUsers--
            if (scanUsers == 0 && scanning) {
                val scanner = activeScanner
                activeScanner = null
                scanning = false
                if (scanner != null) runCatching { scanner.stopScan(scanCallback) }
            }
        }
    }

    /** Waits until starting a scan keeps within the throttle (no wait when a scan is already running: it is joined). */
    private suspend fun awaitScanSlot() {
        val wait = synchronized(scanLock) { if (scanning) 0L else throttle.waitMs(SystemClock.elapsedRealtime()) }
        if (wait > 0) {
            Log.i(TAG, "Delaying the strap scan $wait ms to respect the platform's scan throttling")
            delay(wait)
        }
    }

    override fun scan(): Flow<DiscoveredStrap> = flow {
        checkScanAllowed()
        awaitScanSlot()
        var acquired = false
        try {
            scanEvents.onSubscription { acquireScan(); acquired = true }.collect { event ->
                when (event) {
                    is ScanEvent.Seen -> emit(event.found)
                    is ScanEvent.Failed -> throw event.error
                }
            }
        } finally {
            if (acquired) releaseScan()
        }
    }

    override suspend fun locate(address: String, timeoutMs: Long): DiscoveredStrap? {
        val key = normalize(address)
        sightings[key]?.takeIf { SystemClock.elapsedRealtime() - it.atMs <= SIGHTING_FRESH_MS }?.let { return it.found() }
        checkScanAllowed()
        awaitScanSlot()
        var acquired = false
        try {
            return withTimeoutOrNull(timeoutMs) {
                scanEvents
                    .onSubscription { acquireScan(); acquired = true }
                    .mapNotNull { event ->
                        when (event) {
                            is ScanEvent.Seen -> event.found.takeIf { normalize(it.address) == key }
                            is ScanEvent.Failed -> throw event.error
                        }
                    }
                    .first()
            }
        } finally {
            if (acquired) releaseScan()
        }
    }

    // ------------------------------------------------------------------ connection

    override suspend fun connect(address: String) {
        teardown()
        closingByUs = false
        checkConnectAllowed()
        val seen = sightings[normalize(address)]
            ?: throw HrmTransportException("The strap $address has not been seen by a scan")
        val connectedD = CompletableDeferred<Unit>().also { connected = it }
        val g = try {
            seen.device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw HrmTransportException("Missing BLUETOOTH_CONNECT permission", e, StrapProblem.PermissionMissing)
        } ?: throw HrmTransportException("Could not start the GATT connection")
        gatt = g
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectedD.await() } ?: throw HrmTransportException("Timed out connecting to $address")
            delay(SERVICE_DISCOVERY_DELAY_MS)
            val servicesD = CompletableDeferred<Unit>().also { servicesDiscovered = it }
            if (!g.discoverServices()) throw HrmTransportException("Could not start service discovery")
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { servicesD.await() } ?: throw HrmTransportException("Timed out discovering services")
            resolveCharacteristics(g)
            ready = true
        } catch (e: Throwable) {
            withContext(NonCancellable) { teardown() }
            throw e
        }
    }

    /** Only the Heart Rate and Battery services. */
    private fun resolveCharacteristics(g: BluetoothGatt) {
        characteristics.clear()
        val hr = g.getService(uuid(HrmUuids.HEART_RATE_SERVICE))
            ?: throw HrmTransportException("The device does not offer the Heart Rate service (0x180D)")
        val battery = g.getService(uuid(HrmUuids.BATTERY_SERVICE))
        for (c in HrmCharacteristic.entries) {
            val service = when (c.serviceShortUuid) {
                HrmUuids.HEART_RATE_SERVICE -> hr
                HrmUuids.BATTERY_SERVICE -> battery
                else -> null
            } ?: continue
            service.getCharacteristic(uuid(c.shortUuid))?.let { characteristics[c] = it }
        }
        if (!characteristics.containsKey(HrmCharacteristic.HeartRateMeasurement)) {
            throw HrmTransportException("Missing the Heart Rate Measurement characteristic")
        }
    }

    override fun has(characteristic: HrmCharacteristic): Boolean = characteristics.containsKey(characteristic)

    override suspend fun subscribe(characteristic: HrmCharacteristic) {
        val g = gatt ?: throw HrmTransportException("No connection to the strap")
        val ch = characteristics[characteristic] ?: throw HrmTransportException("The strap does not have ${characteristic.shortName}")
        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            throw HrmTransportException("${characteristic.shortName} does not notify")
        }
        if (!g.setCharacteristicNotification(ch, true)) throw HrmTransportException("Could not enable ${characteristic.shortName}")
        val cccd = ch.getDescriptor(uuid(HrmUuids.CCCD)) ?: throw HrmTransportException("${characteristic.shortName} has no CCCD")
        queue.execute("subscribe to ${characteristic.shortName}") {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }
    }

    override suspend fun read(characteristic: HrmCharacteristic): ByteArray? {
        val g = gatt ?: throw HrmTransportException("No connection to the strap")
        val ch = characteristics[characteristic] ?: return null
        return queue.execute("read ${characteristic.shortName}") { g.readCharacteristic(ch) }.value
    }

    override suspend fun disconnect() {
        teardown()
    }

    private suspend fun teardown() {
        val g = gatt ?: return
        closingByUs = true
        ready = false
        queue.abort(HrmTransportException("Connection closed"))
        val d = CompletableDeferred<Unit>().also { disconnected = it }
        runCatching { g.disconnect() }
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { d.await() }
        runCatching { g.close() }
        if (gatt === g) gatt = null
        characteristics.clear()
    }

    // ------------------------------------------------------------------ callbacks (binder threads)

    private val callback = object : BluetoothGattCallback() {

        private fun stale(g: BluetoothGatt): Boolean = gatt.let { it != null && it !== g }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (stale(g)) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { g.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    if (status == BluetoothGatt.GATT_SUCCESS) connected?.complete(Unit)
                    else connected?.completeExceptionally(HrmTransportException("Connection failed (GATT status $status)"))
                BluetoothProfile.STATE_DISCONNECTED -> onLinkLost(g, "GATT status $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered?.complete(Unit)
            else servicesDiscovered?.completeExceptionally(HrmTransportException("Service discovery failed (status $status)"))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            queue.complete(status, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            queue.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val short = shortUuidOf(characteristic.uuid) ?: return
            val c = HrmCharacteristic.fromShortUuid(short) ?: return
            _incoming.tryEmit(HrmPacket(c, value.copyOf()))
        }
    }

    private fun onLinkLost(g: BluetoothGatt, reason: String) {
        val wasReady = ready
        val byUs = closingByUs
        ready = false
        val error = HrmTransportException("Disconnected ($reason)")
        queue.abort(error)
        connected?.completeExceptionally(error)
        servicesDiscovered?.completeExceptionally(error)
        runCatching { g.close() }
        disconnected?.complete(Unit)
        if (gatt === g) gatt = null
        characteristics.clear()
        if (wasReady && !byUs) _disconnections.tryEmit(reason)
    }

    // Last in the class body: the receiver may fire as soon as it is registered.
    init {
        appContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> _bluetoothEnabled.value = true
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            _bluetoothEnabled.value = false
                            onAdapterOff()
                        }
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "TreadZHrm"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val DISCONNECT_TIMEOUT_MS = 2_000L
        const val SERVICE_DISCOVERY_DELAY_MS = 300L

        /** A sighting this recent makes a new scan before connecting unnecessary. */
        const val SIGHTING_FRESH_MS = 10_000L

        fun uuid(short: Int): UUID = UUID.fromString(HrmUuids.full(short))

        fun normalize(address: String) = address.trim().uppercase(Locale.ROOT)

        fun shortUuidOf(uuid: UUID): Int? {
            val s = uuid.toString().lowercase(Locale.ROOT)
            return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).toIntOrNull(16) else null
        }
    }
}
