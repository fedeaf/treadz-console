package com.treadz.console.treadmill.android

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
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.ftms.BleAddress
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsTransport
import com.treadz.console.treadmill.ftms.FtmsUuids
import com.treadz.console.treadmill.ftms.GattOperationQueue
import com.treadz.console.treadmill.ftms.GattPacket
import com.treadz.console.treadmill.ftms.ScanRateLimiter
import com.treadz.console.treadmill.ftms.TransportDisconnect
import com.treadz.console.treadmill.ftms.TransportException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Real BLE transport on top of `BluetoothGatt`.
 *
 * - Scanning filtered by the FTMS service (0x1826). There is a single platform scan at a time, shared by whoever
 *   needs it (the user's "find treadmills" and the short scan that precedes every connection): see [acquireScan].
 * - **Connecting goes through a scan.** The treadmill's address is a *random static* one (the two top bits are set).
 *   `getRemoteDevice(address)` makes Android assume a public address, and `connectGatt` then fails unless a scan has
 *   just seen the device (after killing the app, or after turning Bluetooth off and on, nothing is cached). So
 *   [locate] runs a short scan filtered by address, and [connect] uses the `BluetoothDevice` of the scan result, which
 *   has the right address type and the name. If the scan does not see the device, [connect] still tries
 *   `getRemoteLeDevice` with the address type deduced from the address ([BleAddress.isRandomStatic]).
 * - Android throttles apps that start more than 5 scans in 30 s (they silently get nothing): every scan start is
 *   recorded in a [ScanRateLimiter] and [locate] waits if starting one more would exceed its (lower) limit.
 * - The Bluetooth adapter state is tracked with `ACTION_STATE_CHANGED` ([bluetoothEnabled]); turning it off is
 *   reported as a connection loss and fails scans and connections with [ConnectionProblem.BluetoothOff].
 * - A single GATT operation in flight ([GattOperationQueue]).
 * - Only the [FtmsCharacteristic] characteristics (FTMS, Device Information and the standard Device Name) are
 *   resolved: the `FFF0` (iConsole) and OTA services are never touched, even if the treadmill advertises them.
 * - MTU 247 first: Treadmill Data is 23 bytes and with the default MTU (23) it would arrive truncated to 20.
 *
 * It cannot be tested on the PC (no device): the interesting logic lives in `:treadmill`.
 */
@SuppressLint("MissingPermission") // permissions are checked before scanning and connecting; failures become typed TransportExceptions
class AndroidBleTransport(context: Context) : FtmsTransport {

    private val appContext = context.applicationContext
    private val adapter get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _incoming = MutableSharedFlow<GattPacket>(extraBufferCapacity = 512)
    override val incoming: SharedFlow<GattPacket> = _incoming.asSharedFlow()

    private val _disconnections = MutableSharedFlow<TransportDisconnect>(extraBufferCapacity = 16)
    override val disconnections: SharedFlow<TransportDisconnect> = _disconnections.asSharedFlow()

    private val _bluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    @Volatile
    override var deviceName: String? = null
        private set

    private val queue = GattOperationQueue()
    private val characteristics = ConcurrentHashMap<FtmsCharacteristic, BluetoothGattCharacteristic>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready = false
    @Volatile private var closingByUs = false
    @Volatile private var connected: CompletableDeferred<Unit>? = null
    @Volatile private var servicesDiscovered: CompletableDeferred<Unit>? = null
    @Volatile private var mtuChanged: CompletableDeferred<Unit>? = null
    @Volatile private var disconnected: CompletableDeferred<Unit>? = null

    /** Everything Android knew about the nearby devices goes away with the adapter: forget it, and drop the link. */
    private fun onAdapterOff() {
        sightings.clear()
        synchronized(scanLock) {
            activeScanCallback = null
            activeScanner = null
        }
        scanEvents.tryEmit(ScanEvent.Failed(TransportException("Bluetooth turned off", problem = ConnectionProblem.BluetoothOff)))
        // A connection may not get its own callback when the adapter goes away: report the loss ourselves.
        gatt?.let { onLinkLost(it, "Bluetooth turned off") }
    }

    private fun checkScanAllowed() {
        if (!BlePermissions.hasScan(appContext)) {
            throw TransportException("Missing BLUETOOTH_SCAN permission", problem = ConnectionProblem.PermissionMissing)
        }
        if (adapter?.isEnabled != true) throw TransportException("Bluetooth off or unavailable", problem = ConnectionProblem.BluetoothOff)
    }

    private fun checkConnectAllowed() {
        if (!BlePermissions.hasConnect(appContext)) {
            throw TransportException("Missing BLUETOOTH_CONNECT permission", problem = ConnectionProblem.PermissionMissing)
        }
        if (adapter?.isEnabled != true) throw TransportException("Bluetooth off or unavailable", problem = ConnectionProblem.BluetoothOff)
    }

    // ------------------------------------------------------------------ scanning

    private sealed interface ScanEvent {
        class Seen(val found: DiscoveredTreadmill) : ScanEvent
        class Failed(val error: TransportException) : ScanEvent
    }

    /** What a scan saw: the `BluetoothDevice` carries the address type that `connectGatt` needs. */
    private class Sighting(val device: BluetoothDevice, val name: String?, val rssi: Int, val atMs: Long) {
        fun found() = DiscoveredTreadmill(device.address, name, rssi)
    }

    private val scanEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val sightings = ConcurrentHashMap<String, Sighting>()
    private val limiter = ScanRateLimiter()
    private val scanLock = Any()
    private var scanUsers = 0
    private var activeScanCallback: ScanCallback? = null
    private var activeScanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.scanRecord?.deviceName ?: result.device.name
            } catch (_: SecurityException) {
                null
            }
            val sighting = Sighting(result.device, name, result.rssi, SystemClock.elapsedRealtime())
            sightings[BleAddress.normalize(result.device.address)] = sighting
            scanEvents.tryEmit(ScanEvent.Seen(sighting.found()))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed (code $errorCode)")
            synchronized(scanLock) {
                activeScanCallback = null
                activeScanner = null
            }
            scanEvents.tryEmit(ScanEvent.Failed(TransportException("Scan failed (code $errorCode)")))
        }
    }

    /**
     * One more user of the platform scan: starts it if nobody else is scanning. Every call must be paired with
     * [releaseScan]. Throws a typed [TransportException] if scanning is not possible.
     */
    private fun acquireScan() {
        synchronized(scanLock) {
            checkScanAllowed()
            if (activeScanCallback == null) {
                val scanner = adapter?.bluetoothLeScanner
                    ?: throw TransportException("Bluetooth off or unavailable", problem = ConnectionProblem.BluetoothOff)
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid(FtmsUuids.FTMS_SERVICE))).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                try {
                    scanner.startScan(listOf(filter), settings, scanCallback)
                } catch (e: SecurityException) {
                    throw TransportException("Missing BLUETOOTH_SCAN permission", e, ConnectionProblem.PermissionMissing)
                } catch (e: IllegalStateException) {
                    throw TransportException("Bluetooth off or unavailable", e, ConnectionProblem.BluetoothOff)
                }
                limiter.recordStart(SystemClock.elapsedRealtime())
                activeScanner = scanner
                activeScanCallback = scanCallback
            }
            scanUsers++
        }
    }

    private fun releaseScan() {
        synchronized(scanLock) {
            if (scanUsers > 0) scanUsers--
            if (scanUsers == 0) {
                val scanner = activeScanner
                activeScanCallback = null
                activeScanner = null
                if (scanner != null) runCatching { scanner.stopScan(scanCallback) }
            }
        }
    }

    override fun scan(): Flow<DiscoveredTreadmill> = flow {
        var acquired = false
        try {
            // The scan starts once this collector is subscribed, so no result is missed.
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

    override suspend fun locate(address: String, timeoutMs: Long): DiscoveredTreadmill? {
        val key = BleAddress.normalize(address)
        sightings[key]?.takeIf { SystemClock.elapsedRealtime() - it.atMs <= SIGHTING_FRESH_MS }?.let { return it.found() }
        checkScanAllowed()
        // If a scan is already running (the user's, or another locate) it is joined: no new start, nothing to throttle.
        val wait = synchronized(scanLock) { if (activeScanCallback != null) 0L else limiter.waitMs(SystemClock.elapsedRealtime()) }
        if (wait > 0) {
            Log.i(TAG, "Delaying the scan $wait ms to respect the platform's scan throttling")
            delay(wait)
        }
        var acquired = false
        try {
            val found = withTimeoutOrNull(timeoutMs) {
                scanEvents
                    .onSubscription { acquireScan(); acquired = true }
                    .mapNotNull { event ->
                        when (event) {
                            is ScanEvent.Seen -> event.found.takeIf { BleAddress.normalize(it.address) == key }
                            is ScanEvent.Failed -> throw event.error
                        }
                    }
                    .first()
            }
            Log.i(TAG, if (found != null) "Scan saw $address (${found.name})" else "Scan did not see $address in $timeoutMs ms")
            return found
        } finally {
            if (acquired) releaseScan()
        }
    }

    // ------------------------------------------------------------------ connection

    override suspend fun connect(address: String) {
        teardown()
        closingByUs = false
        checkConnectAllowed()
        val seen = sightings[BleAddress.normalize(address)]
        // The device object from a scan result knows its address type; otherwise state it ourselves.
        val device = seen?.device ?: remoteLeDevice(address)
        val connectedD = CompletableDeferred<Unit>().also { connected = it }
        val g = try {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw TransportException("Missing BLUETOOTH_CONNECT permission", e, ConnectionProblem.PermissionMissing)
        } ?: throw TransportException("Could not start the GATT connection")
        gatt = g
        try {
            val timeout = if (seen != null) CONNECT_TIMEOUT_MS else CONNECT_UNSEEN_TIMEOUT_MS
            withTimeoutOrNull(timeout) { connectedD.await() }
                ?: throw TransportException("Timed out connecting to $address")

            // MTU before discovering services and subscribing. A failure is not serious (on Android 14+ the system negotiates it).
            val mtuD = CompletableDeferred<Unit>().also { mtuChanged = it }
            if (g.requestMtu(REQUESTED_MTU)) withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuD.await() }

            delay(SERVICE_DISCOVERY_DELAY_MS)
            val servicesD = CompletableDeferred<Unit>().also { servicesDiscovered = it }
            if (!g.discoverServices()) throw TransportException("Could not start service discovery")
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { servicesD.await() }
                ?: throw TransportException("Timed out discovering services")

            resolveCharacteristics(g)
            // The name from the scan, else the system's; if both are missing the controller reads the Device Name.
            deviceName = seen?.name ?: try { g.device.name } catch (_: SecurityException) { null }
            ready = true
        } catch (e: Throwable) {
            // Also when cancelled (the user abandoned the attempt): nothing is left half open.
            withContext(NonCancellable) { teardown() }
            throw e
        }
    }

    private fun remoteLeDevice(address: String): BluetoothDevice {
        val bluetooth = adapter ?: throw TransportException("Bluetooth unavailable", problem = ConnectionProblem.BluetoothOff)
        val type = if (BleAddress.isRandomStatic(address)) BluetoothDevice.ADDRESS_TYPE_RANDOM else BluetoothDevice.ADDRESS_TYPE_PUBLIC
        return try {
            bluetooth.getRemoteLeDevice(address, type)
        } catch (e: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address: $address", e)
        }
    }

    /** Only FTMS, Device Information and Generic Access (Device Name). FFF0 (iConsole) and OTA are not even looked at. */
    private fun resolveCharacteristics(g: BluetoothGatt) {
        characteristics.clear()
        val ftms = g.getService(uuid(FtmsUuids.FTMS_SERVICE))
            ?: throw TransportException("The treadmill does not offer the FTMS service (0x1826)")
        val dis = g.getService(uuid(FtmsUuids.DEVICE_INFORMATION_SERVICE))
        val gap = g.getService(uuid(FtmsUuids.GENERIC_ACCESS_SERVICE))
        for (c in FtmsCharacteristic.entries) {
            val service = when (c.serviceShortUuid) {
                FtmsUuids.FTMS_SERVICE -> ftms
                FtmsUuids.DEVICE_INFORMATION_SERVICE -> dis
                FtmsUuids.GENERIC_ACCESS_SERVICE -> gap
                else -> null
            } ?: continue
            service.getCharacteristic(uuid(c.shortUuid))?.let { characteristics[c] = it }
        }
        for (required in listOf(FtmsCharacteristic.TreadmillData, FtmsCharacteristic.ControlPoint)) {
            if (!characteristics.containsKey(required)) throw TransportException("Missing FTMS characteristic ${required.shortName}")
        }
    }

    override suspend fun subscribe(characteristic: FtmsCharacteristic) {
        val g = requireGatt()
        val ch = characteristics[characteristic] ?: throw TransportException("The treadmill does not have ${characteristic.shortName}")
        if (!g.setCharacteristicNotification(ch, true)) throw TransportException("Could not enable ${characteristic.shortName}")
        val cccd = ch.getDescriptor(uuid(FtmsUuids.CCCD)) ?: throw TransportException("${characteristic.shortName} has no CCCD")
        // The Control Point only supports indications; the rest, notifications.
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        queue.execute("subscribe to ${characteristic.shortName}") {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        }
    }

    override suspend fun read(characteristic: FtmsCharacteristic): ByteArray? {
        val g = requireGatt()
        val ch = characteristics[characteristic] ?: return null
        return queue.execute("read ${characteristic.shortName}") { g.readCharacteristic(ch) }.value
    }

    override suspend fun writeControlPoint(value: ByteArray) {
        val g = requireGatt()
        val ch = characteristics[FtmsCharacteristic.ControlPoint] ?: throw TransportException("No Control Point")
        queue.execute("write to the Control Point") {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        }
    }

    override suspend fun disconnect() {
        teardown()
    }

    private fun requireGatt(): BluetoothGatt = gatt ?: throw TransportException("No connection to the treadmill")

    /** Closes the current GATT (if any), waiting briefly for the system to confirm the disconnection. */
    private suspend fun teardown() {
        val g = gatt ?: return
        closingByUs = true
        ready = false
        queue.abort(TransportException("Connection closed"))
        val d = CompletableDeferred<Unit>().also { disconnected = it }
        runCatching { g.disconnect() }
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { d.await() }
        // If the callback did not arrive, close anyway; if it did, close() was already called and repeating it is harmless.
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
                    else connected?.completeExceptionally(TransportException("Connection failed (GATT status $status)"))

                BluetoothProfile.STATE_DISCONNECTED -> onLinkLost(g, "GATT status $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU = $mtu (status $status)")
            mtuChanged?.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered?.complete(Unit)
            else servicesDiscovered?.completeExceptionally(TransportException("Service discovery failed (status $status)"))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            queue.complete(status, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            queue.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            queue.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val short = shortUuidOf(characteristic.uuid) ?: return
            val c = FtmsCharacteristic.fromShortUuid(short) ?: return
            _incoming.tryEmit(GattPacket(c, value.copyOf()))
        }
    }

    private fun onLinkLost(g: BluetoothGatt, reason: String) {
        val wasReady = ready
        val byUs = closingByUs
        ready = false
        val error = TransportException("Disconnected ($reason)")
        queue.abort(error)
        connected?.completeExceptionally(error)
        servicesDiscovered?.completeExceptionally(error)
        mtuChanged?.complete(Unit)
        runCatching { g.close() }
        disconnected?.complete(Unit)
        if (gatt === g) gatt = null
        characteristics.clear()
        // Only unrequested losses are reported; the controller decides what to do (ConnectionLost and reconnection).
        if (wasReady && !byUs) _disconnections.tryEmit(TransportDisconnect(reason))
    }

    // Last in the class body: the receiver may fire as soon as it is registered, and everything it uses must exist by then.
    init {
        // The transport lives as long as the process (it is a singleton of the backend), so the receiver is never unregistered.
        appContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> {
                            Log.i(TAG, "Bluetooth is on")
                            _bluetoothEnabled.value = true
                        }
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            if (_bluetoothEnabled.value) Log.i(TAG, "Bluetooth is turning off")
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
        const val TAG = "TreadZBle"
        const val REQUESTED_MTU = 247
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Direct connection to a device no scan has seen: if it is not there, do not make the user wait long. */
        const val CONNECT_UNSEEN_TIMEOUT_MS = 10_000L

        /** A scan sighting this recent makes a new scan before connecting unnecessary (e.g. the user just scanned). */
        const val SIGHTING_FRESH_MS = 15_000L
        const val MTU_TIMEOUT_MS = 3_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val DISCONNECT_TIMEOUT_MS = 2_000L
        const val SERVICE_DISCOVERY_DELAY_MS = 300L

        fun uuid(short: Int): UUID = UUID.fromString(FtmsUuids.full(short))

        /** `0000xxxx-0000-1000-8000-00805f9b34fb` -> `xxxx`; `null` if it is not a 16-bit base UUID. */
        fun shortUuidOf(uuid: UUID): Int? {
            val s = uuid.toString().lowercase()
            return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).toIntOrNull(16) else null
        }
    }
}
