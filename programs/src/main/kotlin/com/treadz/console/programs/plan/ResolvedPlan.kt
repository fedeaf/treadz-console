package com.treadz.console.programs.plan

import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin

/**
 * What the user chooses when starting a program.
 *
 * Every field is optional; what is required depends on the program (see `ProgramResolver.requiredParameters`): a
 * relative target needs [easySpeedKmh], a relative speed above 0 % also [maxSpeedKmh], a relative incline above 0 %
 * [maxInclinePercent]. The history keeps a copy of these values with the plan.
 */
data class StartParameters(
    /**
     * Total duration of the time-based blocks. When present, the durations of those blocks are scaled proportionally
     * so that they add up to exactly this value (distance-based blocks are left as they are). When `null` the program
     * runs with the durations it was written with.
     */
    val totalDurationSeconds: Int? = null,
    /** Speed that 0 % of a relative speed maps to. */
    val easySpeedKmh: Double? = null,
    /** Speed that 100 % of a relative speed maps to. */
    val maxSpeedKmh: Double? = null,
    /** Incline that 100 % of a relative incline maps to (0 % is flat). */
    val maxInclinePercent: Double? = null,
    /**
     * Global intensity adjustment, in percent, applied to the **speed** of every block of any program kind; the
     * incline is never scaled (see [ProgramResolver] for the exact rule). Chosen at the start and not changed during
     * the session.
     *
     * Documents written by earlier versions may carry an `adjustIncline` field: it is ignored when reading (the
     * incline is not adjusted any more) and no longer written.
     */
    val intensityAdjustmentPercent: Int = 0,
) {
    companion object {
        const val MIN_ADJUSTMENT_PERCENT = -50
        const val MAX_ADJUSTMENT_PERCENT = 50
        const val MIN_TOTAL_DURATION_SECONDS = 60
    }
}

/** The reference values of [StartParameters] that a relative program may need. */
enum class IntensityParameter { EasySpeed, MaxSpeed, MaxIncline }

/** Where a plan came from: a snapshot of the program's identity (the program itself may be edited or deleted later). */
data class PlanSource(
    val programId: String,
    val programName: String,
    val kind: ProgramKind,
    val programSchemaVersion: Int,
    val origin: ProgramOrigin?,
)

/**
 * A program ready to run: a flat list of concrete blocks (absolute km/h and %, repeats expanded, clamped to the
 * treadmill range and to the safety limits). The engine only executes plans. It is self-contained and serialisable
 * ([PlanJson]) so the workout history can keep a copy of what was planned.
 */
data class ResolvedPlan(
    val source: PlanSource,
    val parameters: StartParameters,
    val blocks: List<ResolvedBlock>,
    /**
     * Estimated total duration: time blocks as planned, distance blocks at their target speed. The speed ramp
     * (~0.5 km/h per second) is not taken into account.
     */
    val estimatedDurationSeconds: Int,
    /** Estimated total distance: time blocks at their target speed, distance blocks as planned. */
    val estimatedDistanceMeters: Int,
    val warnings: List<PlanWarning>,
    val planSchemaVersion: Int = PLAN_SCHEMA_VERSION,
) {
    companion object {
        const val PLAN_SCHEMA_VERSION = 1
    }
}

/** A concrete block of a [ResolvedPlan]. */
data class ResolvedBlock(
    /** Position in the flat list, 0-based. */
    val index: Int,
    val phase: BlockPhase,
    val name: String?,
    val end: BlockEnd,
    val speedKmh: Double,
    val inclinePercent: Double,
    /**
     * Set for blocks that ask for heart-rate control. [speedKmh] and [inclinePercent] are the starting values; the
     * runner then adjusts one of them ([HeartRateGuidance.adjust]) between the guidance bounds. A short block inside a
     * repeat (< 6 min) is controlled between repetitions, any other one continuously.
     */
    val heartRate: HeartRateGuidance?,
    /** Index of the source step in `Program.steps`. */
    val stepIndex: Int,
    /** Index inside the source `Repeat`, `null` for a block that was not inside a repeat. */
    val blockInStep: Int?,
    /** Which repetition this block belongs to, `null` outside repeats. */
    val repetition: Repetition?,
    /** Planned duration; for distance blocks it is derived from the target speed. */
    val estimatedDurationSeconds: Int,
)

/** Repetition [iteration] (1-based) of [count]. */
data class Repetition(val iteration: Int, val count: Int)

/** Heart-rate part of a resolved block. Bounds are absolute: km/h for [HeartRateAdjust.Speed], % for incline. */
data class HeartRateGuidance(
    val zone: HeartRateZone,
    val adjust: HeartRateAdjust,
    val minimum: Double,
    val maximum: Double,
)

enum class WarningCode {
    /** A speed was outside what the treadmill or the safety limits allow and was changed. */
    SpeedClamped,

    /** An incline was outside what the treadmill or the safety limits allow and was changed. */
    InclineClamped,

    /** The block is so short that much of it is spent on the speed ramp (~0.5 km/h per second). */
    ShortBlock,

    /**
     * No longer produced (heart-rate control exists since milestone 5f). Kept so that plans stored by earlier versions
     * can still be read.
     */
    HeartRateControlUnavailable,
}

/** Something worth telling the user about a plan. [message] is an English text for logs; UIs translate by [code]. */
data class PlanWarning(
    val code: WarningCode,
    /** Index of the [ResolvedBlock] concerned, or `null` for the plan as a whole. */
    val blockIndex: Int?,
    /** The value asked for (clamping warnings) or the block duration in seconds ([WarningCode.ShortBlock]). */
    val requested: Double?,
    /** The value used (clamping warnings). */
    val applied: Double?,
    val message: String,
)
