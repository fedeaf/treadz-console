package com.treadz.console.training

import com.treadz.console.programs.engine.BlockStart
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.programs.plan.HeartRateGuidance
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventType

/** One block of a plan placed on a time line, for the charts. Distance blocks use their estimated duration. */
data class PlanSegment(
    val index: Int,
    val startSeconds: Int,
    val endSeconds: Int,
    val speedKmh: Double,
    val inclinePercent: Double,
    val phase: BlockPhase,
    /** Set for a block guided by heart rate: [speedKmh] and [inclinePercent] are then its starting values. */
    val heartRate: HeartRateGuidance? = null,
    /** The block keeps the speed in force when it starts: [speedKmh] is only the expected value (drawn as such). */
    val keepsSpeed: Boolean = false,
    /** Same as [keepsSpeed] for the incline. */
    val keepsIncline: Boolean = false,
) {
    val durationSeconds: Int get() = endSeconds - startSeconds
}

/** The blocks of the plan one after the other on a time line starting at 0. */
fun ResolvedPlan.segments(): List<PlanSegment> {
    var start = 0
    return blocks.map { b ->
        PlanSegment(
            b.index, start, start + b.estimatedDurationSeconds, b.speedKmh, b.inclinePercent, b.phase, b.heartRate,
            keepsSpeed = b.keepsSpeed, keepsIncline = b.keepsIncline,
        ).also { start = it.endSeconds }
    }
}

/**
 * The plan placed on the real clock of the run, from when each block really started ([starts], in the order they ran):
 * a block that ran lasts until the next one started (a skipped block is short, a revisited one appears again); the last
 * one that ran lasts until [endSeconds] when the run is over, otherwise its planned duration from its start, or longer
 * while it runs past it ([lastElapsedSeconds]); the blocks still to come follow with their planned durations. Without
 * [starts] the plan is returned as planned.
 */
fun List<PlanSegment>.asRun(starts: List<BlockStart>, lastElapsedSeconds: Int? = null, endSeconds: Int? = null): List<PlanSegment> {
    val ran = starts.filter { s -> s.blockIndex in indices }
    if (ran.isEmpty()) return this
    val result = ArrayList<PlanSegment>(size + ran.size)
    ran.forEachIndexed { k, s ->
        val segment = this[s.blockIndex]
        val end = when {
            k < ran.lastIndex -> ran[k + 1].programSeconds
            endSeconds != null -> endSeconds
            else -> s.programSeconds + maxOf(segment.durationSeconds, lastElapsedSeconds ?: 0)
        }
        result += segment.copy(startSeconds = s.programSeconds, endSeconds = maxOf(s.programSeconds, end))
    }
    var start = result.last().endSeconds
    for (segment in drop(ran.last().blockIndex + 1)) {
        result += segment.copy(startSeconds = start, endSeconds = start + segment.durationSeconds)
        start += segment.durationSeconds
    }
    return result
}

/**
 * When each block of a recorded workout started, for [asRun]: the `block_started` events (the first block at 0), else,
 * for a generated session recorded before them, the blocks of its record one after the other; empty when neither says it.
 */
fun blockStartsOf(events: List<WorkoutEvent>, record: GeneratedSessionRecord?): List<BlockStart> {
    val fromEvents = events.filter { it.type == WorkoutEventType.BlockStarted }.mapNotNull { e -> e.block?.let { BlockStart(it, e.seconds) } }
    if (fromEvents.isNotEmpty()) return listOf(BlockStart(0, 0)) + fromEvents
    val blocks = record?.blocks.orEmpty()
    if (blocks.isEmpty()) return emptyList()
    var start = 0
    return blocks.map { b -> BlockStart(b.blockIndex, start).also { start += b.elapsedSeconds } }
}
