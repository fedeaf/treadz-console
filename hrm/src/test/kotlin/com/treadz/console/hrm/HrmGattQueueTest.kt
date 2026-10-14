package com.treadz.console.hrm

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HrmGattQueueTest {

    @Test
    fun `operations run one at a time and get their callback`() = runTest {
        val q = HrmGattQueue()
        val issued = mutableListOf<String>()
        val a = async { q.execute("a") { issued += "a"; true } }
        val b = async { q.execute("b") { issued += "b"; true } }
        delay(10)
        assertEquals(listOf("a"), issued)
        q.complete(0, byteArrayOf(1))
        assertEquals(1, a.await().value!![0].toInt())
        delay(10)
        assertEquals(listOf("a", "b"), issued)
        q.complete(0)
        b.await()
    }

    @Test
    fun `failures, timeouts and aborts become transport exceptions`() = runTest {
        val q = HrmGattQueue(defaultTimeoutMs = 1_000)
        assertThrows<HrmTransportException> { q.execute("rejected") { false } }
        assertThrows<HrmTransportException> { q.execute("timeout") { true } }
        launch { delay(10); q.complete(133) }
        assertThrows<HrmTransportException> { q.execute("gatt error") { true } }
        launch { delay(10); q.abort(IllegalStateException("gone")) }
        assertThrows<HrmTransportException> { q.execute("aborted") { true } }
        val e = assertThrows<HrmTransportException> { q.execute("no permission") { throw SecurityException("x") } }
        assertEquals(StrapProblem.PermissionMissing, e.problem)
    }

    @Test
    fun `scan throttle allows one start per window`() {
        val t = HrmScanThrottle()
        assertEquals(0, t.waitMs(0))
        t.recordStart(0)
        assertEquals(30_000, t.waitMs(0))
        assertEquals(10_000, t.waitMs(20_000))
        assertTrue(t.waitMs(30_000) == 0L)
    }
}
