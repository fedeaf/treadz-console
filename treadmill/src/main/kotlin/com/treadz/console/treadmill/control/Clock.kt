package com.treadz.console.treadmill.control

/** Injectable clock: tests replace it with virtual time. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
