package com.treadz.console.programs.personalised

import com.treadz.console.programs.engine.BlockChangeReason
import com.treadz.console.programs.engine.HeartRateBlockSummary
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.plan.ResolvedBlock
import kotlin.math.roundToInt

/** How a generated session ended. */
enum class SessionOutcome {
    /** Every block ran to its end (or the last one was skipped). */
    Completed,

    /** The user stopped the program before the end. */
    Stopped,

    /** The user gave up an interrupted program. */
    Abandoned,
}

/** A heart-rate reading with its time (epoch ms), from the workout's samples. */
data class TimedHeartRate(val atMillis: Long, val bpm: Int)

/**
 * What happened in one block of a generated session. A block that was started again (skip back, restart) has one
 * record per attempt.
 *
 * @property key Position of the block in the program: `"<step>"` for a single block, `"<step>.<block in repeat>"` inside
 *   a repeat (see [ProgramGenerators.blockKey]). Generators use it to find "the same block" in their history.
 * @property completed The block ran to its end ([BlockChangeReason.Completed]); false when it was skipped or the program
 *   ended inside it.
 * @property finalSpeedKmh What the engine targeted at the end of the block (heart-rate corrections and manual changes
 *   included).
 * @property manualSpeedReductions Manual changes (console or app) that lowered the speed in this block.
 * @property endHeartRateBpm Mean heart rate of the last 10 s of the block: from the heart-rate control's summary, or
 *   from the workout's samples for a block without heart-rate control.
 * @property heartRate The heart-rate control's summary of a heart-rate block (with its `settledValue`).
 */
data class SessionBlockRecord(
    val blockIndex: Int,
    val key: String,
    val phase: BlockPhase,
    val repetition: Int?,
    val completed: Boolean,
    val elapsedSeconds: Int,
    val plannedSeconds: Int,
    val plannedSpeedKmh: Double,
    val plannedInclinePercent: Double,
    val finalSpeedKmh: Double,
    val finalInclinePercent: Double,
    val manualSpeedReductions: Int,
    val manualChanges: Int,
    val endHeartRateBpm: Double?,
    val heartRate: HeartRateBlockSummary?,
)

/**
 * The record of one session of a generated program, kept next to the workout (JSON: [GeneratedSessionJson]). The
 * generators read a list of these to adapt the next session (start where the heart rate settled, Couch to 5K
 * progression).
 *
 * @property origin The generated program's origin (generator, version and every input as text; Couch to 5K adds its
 *   `session` number).
 * @property maxHeartRateBpm The maximum heart rate of the run's heart-rate profile, `null` without one.
 * @property workBlocksPlanned Number of [BlockPhase.Work] blocks of the plan; [workBlocksCompleted] how many of them ran
 *   to their end.
 */
data class GeneratedSessionRecord(
    val origin: ProgramOrigin,
    val programId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val outcome: SessionOutcome,
    val programSeconds: Int,
    val maxHeartRateBpm: Int?,
    val manualSpeedReductions: Int,
    val manualChanges: Int,
    val workBlocksPlanned: Int,
    val workBlocksCompleted: Int,
    val blocks: List<SessionBlockRecord>,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val generatorId: String get() = origin.generatorId

    /** Every work block ran to its end (a stop during the cool-down still counts). */
    val workCompleted: Boolean get() = workBlocksPlanned > 0 && workBlocksCompleted >= workBlocksPlanned

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Builds a [GeneratedSessionRecord] from a finished run (a pure function). */
object GeneratedSessionRecords {

    /** Window of the end-of-block heart rate taken from samples (the same 10 s the control uses). */
    private const val END_WINDOW_MS = 10_000L

    private const val EPS = 0.05

    /**
     * @param origin The generated program's origin (normally `plan.source.origin`).
     * @param finished The runner's final event.
     * @param events Every event of the run, in order; it must contain the [ProgramEvent.Started] event.
     * @param heartRateSamples The workout's heart-rate samples, used for the end heart rate of blocks without
     *   heart-rate control (optional).
     */
    fun build(
        origin: ProgramOrigin,
        finished: ProgramEvent.Finished,
        events: List<ProgramEvent>,
        heartRateSamples: List<TimedHeartRate> = emptyList(),
    ): GeneratedSessionRecord {
        val started = events.filterIsInstance<ProgramEvent.Started>().firstOrNull()
            ?: throw IllegalArgumentException("The events of the run must contain the Started event")
        val plan = started.plan

        class Open(val block: ResolvedBlock, val startedAt: Long, val startSeconds: Int) {
            var speed = block.speedKmh
            var incline = block.inclinePercent
            var reductions = 0
            var changes = 0
        }

        val records = mutableListOf<SessionBlockRecord>()
        val pendingInitial = mutableMapOf<Int, Pair<HeartRateAdjust, Double>>()
        val summaries = finished.heartRateSummaries.toMutableList()
        var open: Open? = null

        fun close(o: Open, completed: Boolean, atMillis: Long, elapsedSeconds: Int) {
            val summaryIndex = summaries.indexOfFirst { it.blockIndex == o.block.index }
            val summary = if (o.block.heartRate != null && summaryIndex >= 0) summaries.removeAt(summaryIndex) else null
            val endHr = summary?.endHeartRateBpm ?: endHeartRate(heartRateSamples, atMillis)
            records += SessionBlockRecord(
                blockIndex = o.block.index,
                key = ProgramGenerators.blockKey(o.block.stepIndex, o.block.blockInStep),
                phase = o.block.phase,
                repetition = o.block.repetition?.iteration,
                completed = completed,
                elapsedSeconds = elapsedSeconds,
                plannedSeconds = o.block.estimatedDurationSeconds,
                plannedSpeedKmh = o.block.speedKmh,
                plannedInclinePercent = o.block.inclinePercent,
                finalSpeedKmh = o.speed,
                finalInclinePercent = o.incline,
                manualSpeedReductions = o.reductions,
                manualChanges = o.changes,
                endHeartRateBpm = endHr,
                heartRate = summary,
            )
        }

        for (e in events) {
            when (e) {
                is ProgramEvent.BlockStarted -> {
                    open?.let { close(it, false, e.atMillis, e.programSeconds - it.startSeconds) }
                    val o = Open(e.block, e.atMillis, e.programSeconds)
                    pendingInitial.remove(e.blockIndex)?.let { (adjust, value) ->
                        if (adjust == HeartRateAdjust.Speed) o.speed = value else o.incline = value
                    }
                    open = o
                }
                is ProgramEvent.HeartRateAdjusted -> {
                    val o = open
                    if (o != null && o.block.index == e.blockIndex) {
                        if (e.adjust == HeartRateAdjust.Speed) o.speed = e.toValue else o.incline = e.toValue
                    } else {
                        // The start of the next repetition is announced before its BlockStarted event.
                        pendingInitial[e.blockIndex] = e.adjust to e.toValue
                    }
                }
                is ProgramEvent.ManualChange -> {
                    val o = open
                    if (o != null && o.block.index == e.blockIndex) {
                        o.changes++
                        e.speedKmh?.let {
                            if (it < o.speed - EPS) o.reductions++
                            o.speed = it
                        }
                        e.inclinePercent?.let { o.incline = it }
                    }
                }
                is ProgramEvent.BlockCompleted -> {
                    val o = open
                    if (o != null && o.block.index == e.blockIndex) {
                        close(o, e.reason == BlockChangeReason.Completed, e.atMillis, e.elapsedSeconds)
                        open = null
                    }
                }
                else -> Unit
            }
        }
        open?.let { close(it, false, finished.atMillis, finished.programSeconds - it.startSeconds) }

        val workPlanned = plan.blocks.count { it.phase == BlockPhase.Work }
        val workDone = records.filter { it.phase == BlockPhase.Work && it.completed }.map { it.blockIndex }.toSet().size
        return GeneratedSessionRecord(
            origin = origin,
            programId = plan.source.programId,
            startedAtMillis = started.atMillis,
            endedAtMillis = finished.atMillis,
            outcome = when (finished.outcome) {
                RunOutcome.Completed -> SessionOutcome.Completed
                RunOutcome.StoppedByUser -> SessionOutcome.Stopped
                RunOutcome.Abandoned -> SessionOutcome.Abandoned
            },
            programSeconds = finished.programSeconds,
            maxHeartRateBpm = started.options.heartRateProfile?.maxBpm,
            manualSpeedReductions = records.sumOf { it.manualSpeedReductions },
            manualChanges = records.sumOf { it.manualChanges },
            workBlocksPlanned = workPlanned,
            workBlocksCompleted = workDone,
            blocks = records,
        )
    }

    private fun endHeartRate(samples: List<TimedHeartRate>, endMillis: Long): Double? {
        val window = samples.filter { it.atMillis > endMillis - END_WINDOW_MS && it.atMillis <= endMillis && it.bpm > 0 }
        if (window.isEmpty()) return null
        return (window.sumOf { it.bpm }.toDouble() / window.size * 10).roundToInt() / 10.0
    }
}
