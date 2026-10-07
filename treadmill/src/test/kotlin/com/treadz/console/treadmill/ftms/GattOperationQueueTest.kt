package com.treadz.console.treadmill.ftms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationQueueTest {

    @Test
    fun `an operation returns the callback value`() = runTest {
        val q = GattOperationQueue()
        val r = async { q.execute("read") { true } }
        runCurrent()
        assertTrue(q.complete(0, byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), r.await().value)
    }

    @Test
    fun `operations are serialised, the second does not start until the first one ends`() = runTest {
        val q = GattOperationQueue()
        val issued = mutableListOf<String>()
        val a = async { q.execute("A") { issued += "A"; true } }
        val b = async { q.execute("B") { issued += "B"; true } }
        runCurrent()
        assertEquals(listOf("A"), issued)
        q.complete(0)
        runCurrent()
        assertEquals(listOf("A", "B"), issued)
        q.complete(0)
        a.await(); b.await()
    }

    @Test
    fun `a non-zero GATT status is an error`() = runTest {
        val q = GattOperationQueue()
        val r = async { runCatching { q.execute("write") { true } } }
        runCurrent()
        q.complete(133)
        val e = r.await().exceptionOrNull()
        assertTrue(e is TransportException && e.message!!.contains("133"), "$e")
    }

    @Test
    fun `if the system rejects the operation it fails immediately and the queue stays free`() = runTest {
        val q = GattOperationQueue()
        assertThrows(TransportException::class.java) { kotlinx.coroutines.runBlocking { q.execute("x") { false } } }
        val ok = async { q.execute("y") { true } }
        runCurrent()
        q.complete(0)
        assertEquals(0, ok.await().status)
    }

    @Test
    fun `timeout when the callback does not arrive, and the next operation works`() = runTest {
        val q = GattOperationQueue(defaultTimeoutMs = 1_000)
        val a = async { runCatching { q.execute("A") { true } } }
        runCurrent()
        advanceTimeBy(1_001)
        assertTrue(a.await().exceptionOrNull() is TransportException)
        // A late callback for A cannot complete B
        val b = async { q.execute("B") { true } }
        runCurrent()
        q.complete(0, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), b.await().value)
    }

    @Test
    fun `abort fails the operation in progress`() = runTest {
        val q = GattOperationQueue()
        val a = async { runCatching { q.execute("A") { true } } }
        runCurrent()
        q.abort(TransportException("disconnected"))
        assertEquals("disconnected", a.await().exceptionOrNull()?.message)
        assertFalse(q.complete(0), "nothing is left pending")
    }

    @Test
    fun `complete without a pending operation is ignored`() {
        assertFalse(GattOperationQueue().complete(0))
    }

    @Test
    fun `a SecurityException when starting becomes a transport error`() = runTest {
        val q = GattOperationQueue()
        val r = async { runCatching { q.execute("read") { throw SecurityException("missing BLUETOOTH_CONNECT") } } }
        delay(1)
        assertTrue(r.await().exceptionOrNull() is TransportException)
    }
}
