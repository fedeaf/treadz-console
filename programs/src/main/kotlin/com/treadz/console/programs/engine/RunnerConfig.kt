package com.treadz.console.programs.engine

/** Timings and tolerances of the [ProgramRunner]. Time values are measured with the injected clock. */
data class RunnerConfig(
    /** Longest the engine waits before looking at the clock again (block ends are timed exactly regardless). */
    val tickMs: Long = 250,
    /** A speed within this distance of what the treadmill already targets is not sent again. */
    val speedToleranceKmh: Double = 0.05,
    /** An incline within this distance of what the treadmill already shows is not sent again. */
    val inclineTolerancePercent: Double = 0.5,
    /**
     * "Previous block" restarts the current block when it has been running at least this long (like the previous
     * button of a media player); earlier than that it goes back to the previous block.
     */
    val previousRestartsBlockAfterMs: Long = 5_000,
    /** Rules of the heart-rate-guided blocks. */
    val heartRate: HeartRateControlConfig = HeartRateControlConfig(),
)
