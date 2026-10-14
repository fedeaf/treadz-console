package com.treadz.console.hrm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 16-bit UUIDs of the standard services used for heart-rate straps. */
object HrmUuids {
    const val HEART_RATE_SERVICE = 0x180D
    const val HEART_RATE_MEASUREMENT = 0x2A37
    const val BODY_SENSOR_LOCATION = 0x2A38
    const val BATTERY_SERVICE = 0x180F
    const val BATTERY_LEVEL = 0x2A19
    const val CCCD = 0x2902

    /** Full UUID from the Bluetooth base: `0000xxxx-0000-1000-8000-00805f9b34fb`. */
    fun full(short: Int): String = "0000%04x-0000-1000-8000-00805f9b34fb".format(short)
}

/** The only characteristics the strap code ever addresses. Nothing is written to the strap except CCCDs. */
enum class HrmCharacteristic(val serviceShortUuid: Int, val shortUuid: Int, val shortName: String) {
    HeartRateMeasurement(HrmUuids.HEART_RATE_SERVICE, HrmUuids.HEART_RATE_MEASUREMENT, "2A37"),
    BodySensorLocation(HrmUuids.HEART_RATE_SERVICE, HrmUuids.BODY_SENSOR_LOCATION, "2A38"),
    BatteryLevel(HrmUuids.BATTERY_SERVICE, HrmUuids.BATTERY_LEVEL, "2A19"),
    ;

    companion object {
        fun fromShortUuid(short: Int): HrmCharacteristic? = entries.firstOrNull { it.shortUuid == short }
    }
}

/** A notification received from the strap. */
class HrmPacket(val characteristic: HrmCharacteristic, val value: ByteArray)

/**
 * Abstract GATT operations on a heart-rate strap, so the connection logic ([StrapHeartRateMonitor]) does not depend on
 * Android: there is a `BluetoothGatt` implementation in `:hrm-android` and a [SimulatedStrap] for tests and demos.
 *
 * Implementation contract (the same as the treadmill transport):
 *  - [incoming] is hot and does not lose packets in bursts.
 *  - [disconnections] emits **only** unrequested connection losses (never after [disconnect]).
 *  - `suspend` functions throw [HrmTransportException] on failure, have their own timeouts and are cancellable
 *    (cancelling a [connect] or [locate] leaves nothing half open).
 */
interface HrmTransport {

    val incoming: SharedFlow<HrmPacket>

    /** Unrequested connection losses (the reason is English text for the log). */
    val disconnections: SharedFlow<String>

    /** Whether the Bluetooth adapter is on. Transports that cannot be switched off report `true`. */
    val bluetoothEnabled: StateFlow<Boolean> get() = HRM_ALWAYS_ENABLED

    /** Scans for nearby straps (filtering by the 0x180D service). Stops when the collection is cancelled. */
    fun scan(): Flow<DiscoveredStrap>

    /**
     * Looks for the strap with [address] with a scan that ends as soon as it is seen, or after [timeoutMs]. Returns
     * `null` if it was not seen (not an error: it may be off, out of range or held by another device). Throws
     * [HrmTransportException] with a [StrapProblem] when scanning is impossible.
     */
    suspend fun locate(address: String, timeoutMs: Long): DiscoveredStrap?

    /** Connects and discovers services; fails if the device has no Heart Rate service. */
    suspend fun connect(address: String)

    /** Enables notifications of [characteristic]. */
    suspend fun subscribe(characteristic: HrmCharacteristic)

    /** `true` when the connected strap has [characteristic]. */
    fun has(characteristic: HrmCharacteristic): Boolean

    /** Reads a characteristic; `null` if the strap does not have it. */
    suspend fun read(characteristic: HrmCharacteristic): ByteArray?

    /** Closes the connection at our request (also abandons a [connect] in progress). */
    suspend fun disconnect()
}

/** Transport failure. [problem] is set when it is something the user can fix. */
class HrmTransportException(message: String, cause: Throwable? = null, val problem: StrapProblem? = null) : Exception(message, cause)

private val HRM_ALWAYS_ENABLED: StateFlow<Boolean> = MutableStateFlow(true)

/**
 * Serialises GATT operations: Android allows a single pending operation per connection. The Android transport calls
 * [execute] with the code that starts the operation and [complete]/[abort] from its callbacks. Pure logic, tested on
 * the PC.
 */
class HrmGattQueue(private val defaultTimeoutMs: Long = 5_000) {

    class Result(val status: Int, val value: ByteArray?)

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: CompletableDeferred<Result>? = null

    /**
     * Runs an operation and waits for its callback; concurrent operations wait their turn.
     * @throws HrmTransportException if it cannot start, fails (GATT status other than 0), times out or is aborted.
     */
    suspend fun execute(description: String, timeoutMs: Long = defaultTimeoutMs, issue: () -> Boolean): Result =
        mutex.withLock {
            val d = CompletableDeferred<Result>()
            synchronized(lock) { pending = d }
            try {
                val started = try {
                    issue()
                } catch (e: SecurityException) {
                    throw HrmTransportException("No permission for $description", e, StrapProblem.PermissionMissing)
                }
                if (!started) throw HrmTransportException("The system rejected the operation: $description")
                val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: throw HrmTransportException("Timed out: $description")
                if (r.status != 0) throw HrmTransportException("$description failed (GATT status ${r.status})")
                r
            } finally {
                synchronized(lock) { if (pending === d) pending = null }
            }
        }

    /** Call from the callback of the operation in progress. `false` if none was pending. */
    fun complete(status: Int, value: ByteArray? = null): Boolean {
        val d = synchronized(lock) { pending } ?: return false
        return d.complete(Result(status, value))
    }

    /** Fails the operation in progress (e.g. disconnection). */
    fun abort(cause: Throwable) {
        val d = synchronized(lock) { pending } ?: return
        d.completeExceptionally(if (cause is HrmTransportException) cause else HrmTransportException(cause.message ?: "aborted", cause))
    }
}

/**
 * Keeps the strap's scans inside Android's throttling (more than 5 scan starts in 30 s silently return nothing). The
 * treadmill transport keeps its own count with a limit of 4, so the strap allows 1 start per window by default: the
 * two together stay within the platform's 5. Pure logic with an explicit clock.
 */
class HrmScanThrottle(private val maxStarts: Int = 1, private val windowMs: Long = 30_000) {
    private val starts = ArrayDeque<Long>()

    @Synchronized
    fun recordStart(nowMs: Long) {
        prune(nowMs)
        starts.addLast(nowMs)
    }

    /** Milliseconds to wait from [nowMs] before another scan may start (0 = go ahead). */
    @Synchronized
    fun waitMs(nowMs: Long): Long {
        prune(nowMs)
        if (starts.size < maxStarts) return 0
        return (starts[starts.size - maxStarts] + windowMs - nowMs).coerceAtLeast(0)
    }

    private fun prune(nowMs: Long) {
        while (starts.isNotEmpty() && starts.first() <= nowMs - windowMs) starts.removeFirst()
    }
}
