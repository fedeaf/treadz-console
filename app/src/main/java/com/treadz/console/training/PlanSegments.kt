package com.treadz.console.training

import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.plan.HeartRateGuidance
import com.treadz.console.programs.plan.ResolvedPlan

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
) {
    val durationSeconds: Int get() = endSeconds - startSeconds
}

/** The blocks of the plan one after the other on a time line starting at 0. */
fun ResolvedPlan.segments(): List<PlanSegment> {
    var start = 0
    return blocks.map { b ->
        PlanSegment(b.index, start, start + b.estimatedDurationSeconds, b.speedKmh, b.inclinePercent, b.phase, b.heartRate).also { start = it.endSeconds }
    }
}

/**
 * Where the program is on the plan's time line, in seconds: the start of the current block plus the share of the block
 * done so far (for a distance block the estimated remaining time gives the share).
 */
fun ProgramProgress.positionSeconds(segments: List<PlanSegment>): Double? {
    val segment = segments.getOrNull(currentBlockIndex) ?: return null
    val total = blockElapsedSeconds + blockRemainingSeconds
    val share = if (total <= 0) 0.0 else (blockElapsedSeconds.toDouble() / total).coerceIn(0.0, 1.0)
    return segment.startSeconds + share * segment.durationSeconds
}
