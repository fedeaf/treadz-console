package com.treadz.console.treadmill.ftms

/**
 * Keeps an app inside Android's scan throttling: more than 5 scan starts within 30 seconds make the system silently
 * return nothing. Every real scan start is recorded here (whoever asked for it) and the automatic ones ask for how
 * long to wait before starting. The default limit is one below Android's, as a safety margin.
 *
 * Pure logic with an explicit clock, so it is tested on the PC.
 */
class ScanRateLimiter(
    private val maxStarts: Int = 4,
    private val windowMs: Long = 30_000,
) {
    private val starts = ArrayDeque<Long>()

    init {
        require(maxStarts >= 1 && windowMs > 0)
    }

    /** Records that a scan was started at [nowMs]. */
    @Synchronized
    fun recordStart(nowMs: Long) {
        prune(nowMs)
        starts.addLast(nowMs)
    }

    /** Milliseconds to wait from [nowMs] until another scan may start without exceeding the limit (0 = go ahead). */
    @Synchronized
    fun waitMs(nowMs: Long): Long {
        prune(nowMs)
        if (starts.size < maxStarts) return 0
        // The (size - maxStarts + 1)-th oldest start must leave the window.
        val blocking = starts[starts.size - maxStarts]
        return (blocking + windowMs - nowMs).coerceAtLeast(0)
    }

    private fun prune(nowMs: Long) {
        while (starts.isNotEmpty() && starts.first() <= nowMs - windowMs) starts.removeFirst()
    }
}
