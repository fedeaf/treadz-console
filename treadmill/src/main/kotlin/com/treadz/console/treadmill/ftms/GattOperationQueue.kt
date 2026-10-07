package com.treadz.console.treadmill.ftms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Queue that serialises GATT operations: Android only allows one pending operation per connection
 * (read, write or descriptor write); issuing another before the previous callback arrives
 * loses it. The logic does not depend on Android so it can be tested on the PC: the BLE implementation
 * calls [execute] with the code that starts the operation and [complete]/[abort] from its callbacks.
 */
class GattOperationQueue(private val defaultTimeoutMs: Long = 5_000) {

    class Result(val status: Int, val value: ByteArray?)

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: CompletableDeferred<Result>? = null

    /**
     * Runs an operation and waits for its callback. Concurrent operations wait their turn.
     *
     * @param issue starts the operation; returns `false` if the system rejects it.
     * @throws TransportException if it cannot start, fails (GATT status other than 0), times out or is aborted.
     */
    suspend fun execute(description: String, timeoutMs: Long = defaultTimeoutMs, issue: () -> Boolean): Result =
        mutex.withLock {
            val d = CompletableDeferred<Result>()
            synchronized(lock) { pending = d }
            try {
                val started = try {
                    issue()
                } catch (e: SecurityException) {
                    throw TransportException("No permission for $description", e)
                }
                if (!started) throw TransportException("The system rejected the operation: $description")
                val r = withTimeoutOrNull(timeoutMs) { d.await() }
                    ?: throw TransportException("Timed out: $description")
                if (r.status != 0) throw TransportException("$description failed (GATT status ${r.status})")
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

    /** Fails the operation in progress (e.g. disconnection). The following ones proceed normally. */
    fun abort(cause: Throwable) {
        val d = synchronized(lock) { pending } ?: return
        d.completeExceptionally(if (cause is TransportException) cause else TransportException(cause.message ?: "aborted", cause))
    }
}
