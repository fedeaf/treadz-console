package com.treadz.console.programs.engine

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.events.LoadEvent

/** Phase of a program run. */
enum class RunState {
    /** No program loaded (initial state). */
    Idle,

    /** The belt is starting (or resuming after a pause); the program clock is not running yet. */
    Starting,

    /** The program clock runs and the engine drives the treadmill. */
    Running,

    /** Paused by the user: the clock is stopped and the belt is paused. */
    Paused,

    /**
     * Something stopped the program (see [Interruption]). The engine issues no commands and never restarts the belt
     * on its own; the user continues or abandons the program explicitly.
     */
    Interrupted,

    /** The program is ending (last block done or stop requested) and the belt is being stopped. */
    Stopping,

    /** The run is over; [ProgramProgress.outcome] says how. */
    Finished,
}

enum class RunOutcome {
    /** Every block was completed (or the last one was skipped). */
    Completed,

    /** The user stopped the program before the end. */
    StoppedByUser,

    /** The user gave up an interrupted program without stopping anything. */
    Abandoned,
}

/** What the engine does with the belt when the last block ends. */
enum class EndAction {
    /** Stop the belt (the default). */
    StopBelt,

    /** Leave the belt running so the user carries on manually. */
    HandOverToManual,
}

data class RunOptions(
    val endAction: EndAction = EndAction.StopBelt,
    /**
     * The user's heart-rate references (effective maximum, resting heart rate). Needed by heart-rate-guided blocks:
     * without it (or with an unusable maximum) they run at their start values and their control is
     * [HeartRateControlState.Unavailable].
     */
    val heartRateProfile: HeartRateProfile? = null,
)

enum class InterruptionReason {
    /** The safety key was pulled. */
    SafetyKeyRemoved,

    /** The Bluetooth connection to the treadmill was lost. */
    ConnectionLost,

    /** The belt stopped or paused (or was started again) without the engine asking, e.g. from the console or the app's own buttons. */
    BeltChangedExternally,

    /** The treadmill accepted a command that it did not carry out (see [Interruption.detail]). */
    CommandNotExecuted,

    /** A command from the engine failed (see [Interruption.detail]). */
    CommandFailed,

    /** The treadmill data stopped arriving or froze. */
    DataStalled,
}

data class Interruption(
    val reason: InterruptionReason,
    /** English text for logs: which command, which result. */
    val detail: String?,
    val blockIndex: Int,
    val atMillis: Long,
)

/** Where a manual change came from. */
enum class ManualChangeSource {
    /** The physical buttons of the treadmill console. */
    Console,

    /** The app's own speed/incline controls, through [ProgramRunner.adjustSpeed] / [ProgramRunner.adjustIncline]. */
    App,
}

/** Why the engine moved to a block. */
enum class BlockChangeReason { ProgramStart, Completed, SkippedNext, SkippedPrevious, Restarted }

/** What the UI shows during a run. All time values are program time: the time the belt was running for the program. */
data class ProgramProgress(
    val state: RunState = RunState.Idle,
    /** Set when [state] is [RunState.Finished]. */
    val outcome: RunOutcome? = null,
    /** Set when [state] is [RunState.Interrupted]. */
    val interruption: Interruption? = null,
    val plan: ResolvedPlan? = null,
    /** Index of the current block in the plan, -1 without a plan. */
    val currentBlockIndex: Int = -1,
    val currentBlock: ResolvedBlock? = null,
    val nextBlock: ResolvedBlock? = null,
    val blockElapsedSeconds: Int = 0,
    /** Time left in the block: exact for time-based blocks, an estimate at the target speed for distance-based ones. */
    val blockRemainingSeconds: Int = 0,
    val blockElapsedMeters: Int = 0,
    /** Distance left in a distance-based block; `null` for time-based blocks. */
    val blockRemainingMeters: Int? = null,
    val totalElapsedSeconds: Int = 0,
    /** Estimated time to the end of the program (the rest of the current block plus the planned rest). */
    val totalRemainingSeconds: Int = 0,
    val totalElapsedMeters: Int = 0,
    /** What the engine currently wants: the block's values, or the user's manual change inside the block. */
    val targetSpeedKmh: Double? = null,
    val targetInclinePercent: Double? = null,
    /** True when the user changed speed or incline manually inside this block (kept until the next block). */
    val manualOverride: Boolean = false,
    /** What the treadmill reports now. */
    val actualSpeedKmh: Double = 0.0,
    val actualInclinePercent: Double = 0.0,
    val heartRateBpm: Int? = null,
    val heartRateValidity: HeartRateValidity = HeartRateValidity.Invalid,
    /** Where the heart rate comes from (strap connected to the app, console), `null` without heart rate. */
    val heartRateSource: HeartRateSourceKind? = null,
    /** Heart-rate control of the current block; `null` when the block is not heart-rate guided. */
    val heartRateControl: HeartRateControlStatus? = null,
    /** Summaries of the heart-rate blocks finished so far, in order (see [HeartRateBlockSummary.settledValue]). */
    val heartRateSummaries: List<HeartRateBlockSummary> = emptyList(),
)

/** Events of a run, in order, for the recorder and the history. [atMillis] is the injected clock's epoch time. */
sealed interface ProgramEvent {
    val atMillis: Long

    /** Program time (seconds the program clock had run) when the event happened. */
    val programSeconds: Int

    data class Started(override val atMillis: Long, override val programSeconds: Int, val plan: ResolvedPlan, val options: RunOptions) : ProgramEvent

    data class BlockStarted(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val block: ResolvedBlock,
        val reason: BlockChangeReason,
        /**
         * The speed the engine targets as the block starts: the planned one, or the one in force for a block that keeps
         * its speed ([ResolvedBlock.keepsSpeed]) or that eases (recovery and cool-down never go above it). `null` only
         * in events built by hand.
         */
        val targetSpeedKmh: Double? = null,
        /** Same as [targetSpeedKmh] for the incline. */
        val targetInclinePercent: Double? = null,
    ) : ProgramEvent

    data class BlockCompleted(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val elapsedSeconds: Int,
        val meters: Int,
        val reason: BlockChangeReason,
    ) : ProgramEvent

    /**
     * The user made the current block longer ("+1": [addedSeconds] to a time block or [addedMeters] to a distance
     * block). [block] is the block as it is now and [plan] the whole plan with it: what the history keeps from now on.
     */
    data class BlockExtended(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val addedSeconds: Int?,
        val addedMeters: Int?,
        val block: ResolvedBlock,
        val plan: ResolvedPlan,
    ) : ProgramEvent

    /** The engine sent the targets of a block (only the quantities that differed from the treadmill's). */
    data class TargetsApplied(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val speedKmh: Double?,
        val inclinePercent: Double?,
    ) : ProgramEvent

    /** The user changed speed and/or incline (see [source]); the engine leaves it until the next block. */
    data class ManualChange(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val speedKmh: Double?,
        val inclinePercent: Double?,
        val source: ManualChangeSource = ManualChangeSource.Console,
    ) : ProgramEvent

    data class Paused(override val atMillis: Long, override val programSeconds: Int, val blockIndex: Int) : ProgramEvent

    data class Resumed(override val atMillis: Long, override val programSeconds: Int, val blockIndex: Int) : ProgramEvent

    data class Interrupted(override val atMillis: Long, override val programSeconds: Int, val interruption: Interruption) : ProgramEvent

    /** The user continued an interrupted program. */
    data class Continued(override val atMillis: Long, override val programSeconds: Int, val blockIndex: Int) : ProgramEvent

    data class Finished(
        override val atMillis: Long,
        override val programSeconds: Int,
        val outcome: RunOutcome,
        val endAction: EndAction?,
        /** Result of the final stop command, `null` if none was sent. */
        val stopResult: CommandResult?,
        /** One summary per heart-rate block that ran (also when the program did not complete). */
        val heartRateSummaries: List<HeartRateBlockSummary> = emptyList(),
        /** The load events of the whole run, the same list as [ProgramRunner.loadEvents] at this moment. */
        val loadEvents: List<LoadEvent> = emptyList(),
    ) : ProgramEvent

    /** The heart-rate control changed the adjusted quantity of the block ([adjust]) from [fromValue] to [toValue]. */
    data class HeartRateAdjusted(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val adjust: HeartRateAdjust,
        val fromValue: Double,
        val toValue: Double,
        val reason: HeartRateAdjustReason,
        /** The 10-s mean the decision was based on. */
        val heartRateBpm: Double?,
    ) : ProgramEvent

    /**
     * Safety alert: more than 10 s above the ceiling (zone top + 8 bpm). [reduced] says whether the adjusted quantity
     * was lowered (it may already be at the block's minimum, or the control may be off after a manual change).
     */
    data class HeartRateCeilingExceeded(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val heartRateBpm: Int,
        val ceilingBpm: Int,
        val reduced: Boolean,
    ) : ProgramEvent

    /** Safety alert: the heart rate reached the effective maximum; the block went to its recovery value ([reduced]). */
    data class HeartRateMaxReached(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val heartRateBpm: Int,
        val maxBpm: Int,
        val reduced: Boolean,
    ) : ProgramEvent

    /** The state of the block's heart-rate control changed. */
    data class HeartRateControlChanged(
        override val atMillis: Long,
        override val programSeconds: Int,
        val blockIndex: Int,
        val state: HeartRateControlState,
    ) : ProgramEvent

    /** A heart-rate block ended (or the program ended inside it). */
    data class HeartRateBlockFinished(
        override val atMillis: Long,
        override val programSeconds: Int,
        val summary: HeartRateBlockSummary,
    ) : ProgramEvent
}

/** Result of the runner's user actions. */
sealed interface RunnerResult {
    data object Success : RunnerResult

    /** The action does not apply in the current state. */
    data class NotAllowed(val refusal: Refusal, val message: String) : RunnerResult

    /** The treadmill (through the controller) did not carry out a command the action needed. */
    data class CommandFailed(val result: CommandResult) : RunnerResult
}

enum class Refusal {
    /** A program is already running. */
    AlreadyActive,

    /** There is no active program. */
    NotActive,

    /** The action needs another state (e.g. pause needs a running program). */
    WrongState,
    NotConnected,
    SafetyKeyRemoved,

    /** The belt is starting or stopping: try again in a moment. */
    BeltBusy,
    InvalidPlan,

    /** The treadmill data is not current (stale or frozen). */
    DataNotCurrent,

    /** The value is not a number. */
    InvalidValue,
}
