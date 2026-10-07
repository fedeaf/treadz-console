package com.treadz.console.treadmill.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsTransport
import com.treadz.console.treadmill.ftms.FtmsUuids
import com.treadz.console.treadmill.ftms.GattOperationQueue
import com.treadz.console.treadmill.ftms.GattPacket
import com.treadz.console.treadmill.ftms.TransportDisconnect
import com.treadz.console.treadmill.ftms.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real BLE transport on top of `BluetoothGatt`.
 *
 * - Scanning filtered by the FTMS service (0x1826).
 * - A single GATT operation in flight ([GattOperationQueue]).
 * - Only the [FtmsCharacteristic] characteristics (FTMS and Device Information) are resolved: the
 *   `FFF0` (iConsole) and OTA services are never touched, even if the treadmill advertises them.
 * - MTU 247 first: Treadmill Data is 23 bytes and with the default MTU (23) it would arrive truncated to 20.
 *
 * It cannot be tested on the PC (no device): the interesting logic lives in `:treadmill`.
 */
@SuppressLint("MissingPermission") // the UI requests BLUETOOTH_SCAN/CONNECT before connecting; permission failures become TransportException
class AndroidBleTransport(context: Context) : FtmsTransport {

    private val appContext = context.applicationContext
    private val adapter get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _incoming = MutableSharedFlow<GattPacket>(extraBufferCapacity = 512)
    override val incoming: SharedFlow<GattPacket> = _incoming.asSharedFlow()

    private val _disconnections = MutableSharedFlow<TransportDisconnect>(extraBufferCapacity = 16)
    override val disconnections: SharedFlow<TransportDisconnect> = _disconnections.asSharedFlow()

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

    // ------------------------------------------------------------------ scanning

    override fun scan(): Flow<DiscoveredTreadmill> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(TransportException("Bluetooth off or unavailable"))
            awaitClose()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    result.scanRecord?.deviceName ?: result.device.name
                } catch (_: SecurityException) {
                    null
                }
                trySend(DiscoveredTreadmill(result.device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(TransportException("Scan failed (code $errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid(FtmsUuids.FTMS_SERVICE))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            close(TransportException("Missing BLUETOOTH_SCAN permission", e))
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    // ------------------------------------------------------------------ connection

    override suspend fun connect(address: String) {
        teardown()
        closingByUs = false
        val device = try {
            adapter?.getRemoteDevice(address) ?: throw TransportException("Bluetooth unavailable")
        } catch (e: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address: $address", e)
        }
        val connectedD = CompletableDeferred<Unit>().also { connected = it }
        val g = try {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw TransportException("Missing BLUETOOTH_CONNECT permission", e)
        } ?: throw TransportException("Could not start the GATT connection")
        gatt = g
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectedD.await() }
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
            deviceName = try { g.device.name } catch (_: SecurityException) { null }
            ready = true
        } catch (e: Throwable) {
            teardown()
            throw e
        }
    }

    /** Only FTMS and Device Information. FFF0 (iConsole) and OTA are not even looked at. */
    private fun resolveCharacteristics(g: BluetoothGatt) {
        characteristics.clear()
        val ftms = g.getService(uuid(FtmsUuids.FTMS_SERVICE))
            ?: throw TransportException("The treadmill does not offer the FTMS service (0x1826)")
        val dis = g.getService(uuid(FtmsUuids.DEVICE_INFORMATION_SERVICE))
        for (c in FtmsCharacteristic.entries) {
            val service = when (c.serviceShortUuid) {
                FtmsUuids.FTMS_SERVICE -> ftms
                FtmsUuids.DEVICE_INFORMATION_SERVICE -> dis
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

                BluetoothProfile.STATE_DISCONNECTED -> onLinkLost(g, status)
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

    private fun onLinkLost(g: BluetoothGatt, status: Int) {
        val wasReady = ready
        val byUs = closingByUs
        ready = false
        val error = TransportException("Disconnected (GATT status $status)")
        queue.abort(error)
        connected?.completeExceptionally(error)
        servicesDiscovered?.completeExceptionally(error)
        mtuChanged?.complete(Unit)
        runCatching { g.close() }
        disconnected?.complete(Unit)
        if (gatt === g) gatt = null
        characteristics.clear()
        // Only unrequested losses are reported; the controller decides what to do (ConnectionLost and reconnection).
        if (wasReady && !byUs) _disconnections.tryEmit(TransportDisconnect("GATT status $status"))
    }

    private companion object {
        const val TAG = "TreadZBle"
        const val REQUESTED_MTU = 247
        const val CONNECT_TIMEOUT_MS = 15_000L
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
