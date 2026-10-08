package com.treadz.console.userdata.workout

data class WorkoutObserverConfig(
    /** Buffered samples are written at least this often while a session runs (and always when it ends). */
    val flushIntervalMs: Long = 3_000,
    /**
     * Two states closer than this are one sample, unless the belt status changed between them. The treadmill
     * sends ~1 Hz; this only drops extra emissions that do not come from a data packet.
     */
    val minSampleSpacingMs: Long = 400,
    /**
     * After a stop the belt reports `Idle` while it still decelerates (about 0.5 km/h per second) and the session
     * stays open until it reports speed 0. If, in that state, the speed has not changed for this long, the belt is
     * considered stopped (or the data stuck) and the session ends with the last sample it has.
     */
    val idleStallMs: Long = 5_000,
    /** Longest the session waits for the speed to reach 0 after the belt first reported `Idle` while moving. */
    val idleMaxMs: Long = 60_000,
    /**
     * A link announced with [WorkoutHistoryObserver.linkNextSession] while no session runs waits at most this long for
     * the session to start; later it is dropped (the program or test never got the belt moving).
     */
    val linkValidityMs: Long = 60_000,
    /** Samples kept in memory if the database keeps failing; the oldest are dropped beyond this. */
    val maxBufferedSamples: Int = 20_000,
)
