package com.treadz.console.userdata.workout

data class WorkoutObserverConfig(
    /** Buffered samples are written at least this often while a session runs (and always when it ends). */
    val flushIntervalMs: Long = 3_000,
    /**
     * Two states closer than this are one sample, unless the belt status changed between them. The treadmill
     * sends ~1 Hz; this only drops extra emissions that do not come from a data packet.
     */
    val minSampleSpacingMs: Long = 400,
    /** Samples kept in memory if the database keeps failing; the oldest are dropped beyond this. */
    val maxBufferedSamples: Int = 20_000,
)
