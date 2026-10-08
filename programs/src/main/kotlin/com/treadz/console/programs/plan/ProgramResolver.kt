package com.treadz.console.programs.plan

import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.model.ValidationIssue
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.ValueRange
import com.treadz.console.treadmill.control.SafetyClamp
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the treadmill and the user allow: the ranges read from the treadmill and the active safety limits. */
data class ResolutionEnvironment(
    val speedRange: ValueRange = SafetyClamp.DEFAULT_SPEED_RANGE,
    val inclineRange: ValueRange = SafetyClamp.DEFAULT_INCLINE_RANGE,
    val limits: SafetyLimits = SafetyLimits(),
)

/**
 * Turns a [Program] plus the [StartParameters] into a concrete [ResolvedPlan].
 *
 * Steps, in order:
 *  1. The program and the parameters are validated ([InvalidProgramException] lists every problem).
 *  2. Repeats are expanded: `Repeat(4, [a, b])` becomes `a b a b a b a b`, each block remembering its repetition.
 *  3. Time-based blocks are scaled when [StartParameters.totalDurationSeconds] is given: every duration is multiplied
 *     by `total / sum of durations`. The rounding is cumulative (each block boundary is rounded, not each duration),
 *     so the scaled durations add up to exactly the requested total; no block gets less than one second.
 *     Distance-based blocks are not scaled.
 *  4. Relative targets become absolute: speed `easy + p/100 * (max - easy)`, incline `p/100 * maxIncline`.
 *  5. The **global intensity adjustment** of `a` percent multiplies the **speed** of every block by `1 + a/100`.
 *     The incline is never changed by it (the treadmill's 1 % steps make a percentage adjustment too coarse, and the
 *     user chose to move only the speed). It applies after step 4 (so it also moves relative and absolute targets
 *     alike, warm-up and cool-down included) and before the clamping of step 6. For heart-rate blocks it applies to
 *     the starting speed only; the bounds of the heart-rate control are not changed, and the starting incline is not
 *     adjusted.
 *  6. Every speed and incline is clamped to the treadmill range and to the [SafetyLimits] and rounded to the
 *     treadmill step (0.1 km/h and 1 % on the Z-Tech, ties rounding up) with [SafetyClamp]. A value that had to move
 *     by more than half a step produces a warning; plain rounding does not.
 *  7. Warnings are added for blocks too short to be useful ([SHORT_BLOCK_SECONDS]) and for heart-rate blocks, which
 *     still run at their starting values.
 */
object ProgramResolver {

    /** A block shorter than this loses a large part of its time to the speed ramp (~0.5 km/h per second). */
    const val SHORT_BLOCK_SECONDS = 45

    /** Throws [InvalidProgramException] if the program or the parameters are not valid. */
    fun resolve(
        program: Program,
        parameters: StartParameters = StartParameters(),
        environment: ResolutionEnvironment = ResolutionEnvironment(),
    ): ResolvedPlan {
        ProgramValidator.requireValid(program)
        val expanded = expand(program)
        val parameterIssues = validateParameters(parameters, expanded, environment)
        if (parameterIssues.isNotEmpty()) throw InvalidProgramException(parameterIssues)

        val durations = scaledDurations(expanded, parameters.totalDurationSeconds)
        val warnings = mutableListOf<PlanWarning>()
        val ctx = Context(parameters, environment, warnings)

        val blocks = expanded.mapIndexed { index, e ->
            val block = e.block
            val (speed, incline, guidance) = resolveTarget(index, block.target, ctx)
            val end = when (val end = block.end) {
                is BlockEnd.Duration -> BlockEnd.Duration(durations.getValue(index))
                is BlockEnd.Distance -> end
            }
            val estimated = when (end) {
                is BlockEnd.Duration -> end.seconds
                is BlockEnd.Distance -> (end.meters / (speed / 3.6)).roundToInt()
            }
            if (estimated < SHORT_BLOCK_SECONDS) {
                warnings += PlanWarning(
                    WarningCode.ShortBlock, index, estimated.toDouble(), null,
                    "Block ${index + 1} lasts about $estimated s: much of it goes to the speed ramp (~0.5 km/h per second)",
                )
            }
            if (guidance != null) {
                warnings += PlanWarning(
                    WarningCode.HeartRateControlUnavailable, index, null, null,
                    "Block ${index + 1} asks for heart-rate control, which is not available yet: it runs at its starting values",
                )
            }
            ResolvedBlock(
                index = index,
                phase = block.phase,
                name = block.name,
                end = end,
                speedKmh = speed,
                inclinePercent = incline,
                heartRate = guidance,
                stepIndex = e.stepIndex,
                blockInStep = e.blockInStep,
                repetition = e.repetition,
                estimatedDurationSeconds = estimated,
            )
        }

        val meters = blocks.sumOf { b ->
            when (val end = b.end) {
                is BlockEnd.Duration -> b.speedKmh / 3.6 * end.seconds
                is BlockEnd.Distance -> end.meters.toDouble()
            }
        }
        return ResolvedPlan(
            source = PlanSource(program.id, program.name, program.kind, program.schemaVersion, program.origin),
            parameters = parameters,
            blocks = blocks,
            estimatedDurationSeconds = blocks.sumOf { it.estimatedDurationSeconds },
            estimatedDistanceMeters = meters.roundToInt(),
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------ expansion

    private class Expanded(val block: Block, val stepIndex: Int, val blockInStep: Int?, val repetition: Repetition?)

    private fun expand(program: Program): List<Expanded> = buildList {
        program.steps.forEachIndexed { stepIndex, step ->
            when (step) {
                is Block -> add(Expanded(step, stepIndex, null, null))
                is Repeat -> for (iteration in 1..step.count) {
                    step.blocks.forEachIndexed { j, b -> add(Expanded(b, stepIndex, j, Repetition(iteration, step.count))) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ parameters

    private fun validateParameters(p: StartParameters, expanded: List<Expanded>, env: ResolutionEnvironment): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun issue(path: String, message: String) {
            issues += ValidationIssue("parameters.$path", message)
        }

        val required = requiredParameters(expanded.map { it.block })

        p.totalDurationSeconds?.let {
            if (it < StartParameters.MIN_TOTAL_DURATION_SECONDS) {
                issue("totalDurationSeconds", "must be at least ${StartParameters.MIN_TOTAL_DURATION_SECONDS} seconds (was $it)")
            }
            if (expanded.none { e -> e.block.end is BlockEnd.Duration }) {
                issue("totalDurationSeconds", "the program has no time-based blocks to scale")
            }
        }
        if (p.intensityAdjustmentPercent !in StartParameters.MIN_ADJUSTMENT_PERCENT..StartParameters.MAX_ADJUSTMENT_PERCENT) {
            issue(
                "intensityAdjustmentPercent",
                "must be between ${StartParameters.MIN_ADJUSTMENT_PERCENT} and ${StartParameters.MAX_ADJUSTMENT_PERCENT} (was ${p.intensityAdjustmentPercent})",
            )
        }

        val sr = env.speedRange
        val ir = env.inclineRange
        val easy = p.easySpeedKmh
        val max = p.maxSpeedKmh
        val maxIncline = p.maxInclinePercent
        if (IntensityParameter.EasySpeed in required && easy == null) issue("easySpeedKmh", "is required because the program has relative targets")
        if (IntensityParameter.MaxSpeed in required && max == null) issue("maxSpeedKmh", "is required because the program has relative speeds above the easy speed")
        if (IntensityParameter.MaxIncline in required && maxIncline == null) issue("maxInclinePercent", "is required because the program has relative inclines above 0 %")
        // Values that were given are checked even when the program does not need them.
        if (easy != null && (!easy.isFinite() || easy < sr.min || easy > sr.max)) {
            issue("easySpeedKmh", "must be between ${sr.min} and ${sr.max} km/h (was $easy)")
        }
        if (max != null && (!max.isFinite() || max < sr.min || max > sr.max)) {
            issue("maxSpeedKmh", "must be between ${sr.min} and ${sr.max} km/h (was $max)")
        }
        if (easy != null && max != null && easy > max) issue("easySpeedKmh", "must not be above maxSpeedKmh")
        if (maxIncline != null && (!maxIncline.isFinite() || maxIncline < ir.min || maxIncline > ir.max)) {
            issue("maxInclinePercent", "must be between ${ir.min} and ${ir.max} % (was $maxIncline)")
        }
        return issues
    }

    /**
     * The reference values the user has to provide to run [program]: a relative target needs the easy speed; the maximum
     * speed and the maximum incline are only needed when some relative value is above 0 %. Absolute programs need none.
     * Values that are not required may be left `null` in [StartParameters].
     */
    fun requiredParameters(program: Program): Set<IntensityParameter> =
        requiredParameters(program.steps.flatMap { step -> if (step is Block) listOf(step) else (step as Repeat).blocks })

    private fun requiredParameters(blocks: List<Block>): Set<IntensityParameter> {
        val result = mutableSetOf<IntensityParameter>()
        fun scan(t: BlockTarget) {
            when (t) {
                is BlockTarget.Absolute -> Unit
                is BlockTarget.Relative -> {
                    result += IntensityParameter.EasySpeed
                    if (t.speedPercent > 0.0) result += IntensityParameter.MaxSpeed
                    if (t.inclinePercent > 0.0) result += IntensityParameter.MaxIncline
                }
                is BlockTarget.HeartRate -> {
                    scan(t.start)
                    if (t.start is BlockTarget.Relative && (t.minimum > 0.0 || t.maximum > 0.0)) {
                        result += if (t.adjust == HeartRateAdjust.Speed) IntensityParameter.MaxSpeed else IntensityParameter.MaxIncline
                    }
                }
            }
        }
        blocks.forEach { scan(it.target) }
        return result
    }

    // ------------------------------------------------------------------ durations

    /** Duration of each time-based block (by index in the expanded list), scaled to [total] when it is not null. */
    private fun scaledDurations(expanded: List<Expanded>, total: Int?): Map<Int, Int> {
        val result = LinkedHashMap<Int, Int>()
        val original = expanded.mapIndexedNotNull { i, e -> (e.block.end as? BlockEnd.Duration)?.let { i to it.seconds } }
        if (total == null) {
            original.forEach { (i, s) -> result[i] = s }
            return result
        }
        val sum = original.sumOf { it.second.toLong() }
        var cumulative = 0L
        var previousBoundary = 0L
        for ((i, seconds) in original) {
            cumulative += seconds
            // Round half up in integer arithmetic: exact, so the last boundary is exactly [total].
            val boundary = (cumulative * total * 2 + sum) / (2 * sum)
            result[i] = (boundary - previousBoundary).toInt().coerceAtLeast(1)
            previousBoundary = boundary
        }
        return result
    }

    // ------------------------------------------------------------------ targets

    private class Context(val p: StartParameters, val env: ResolutionEnvironment, val warnings: MutableList<PlanWarning>) {
        val factor = 1.0 + p.intensityAdjustmentPercent / 100.0
    }

    private data class Resolved(val speed: Double, val incline: Double, val guidance: HeartRateGuidance?)

    private fun resolveTarget(index: Int, target: BlockTarget, ctx: Context): Resolved = when (target) {
        is BlockTarget.Fixed -> {
            val speed = finalSpeed(index, mapSpeed(target, ctx) * ctx.factor, ctx)
            val incline = finalIncline(index, mapIncline(target, ctx), ctx)
            Resolved(speed, incline, null)
        }
        is BlockTarget.HeartRate -> resolveHeartRate(index, target, ctx)
    }

    private fun resolveHeartRate(index: Int, t: BlockTarget.HeartRate, ctx: Context): Resolved {
        val relative = t.start is BlockTarget.Relative
        // Bounds are mapped to absolute values and rounded to the treadmill step, but not adjusted by the global %.
        fun mapBound(v: Double): Double = when (t.adjust) {
            HeartRateAdjust.Speed -> {
                val raw = if (relative) mapSpeed(BlockTarget.Relative(v, 0.0), ctx) else v
                SafetyClamp.speed(raw, ctx.env.speedRange, ctx.env.limits)
            }
            HeartRateAdjust.Incline -> {
                val raw = if (relative) mapIncline(BlockTarget.Relative(0.0, v), ctx) else v
                SafetyClamp.incline(raw, ctx.env.inclineRange, ctx.env.limits)
            }
        }
        val min = mapBound(t.minimum)
        val max = maxOf(min, mapBound(t.maximum))

        var speed = finalSpeed(index, mapSpeed(t.start, ctx) * ctx.factor, ctx)
        var incline = finalIncline(index, mapIncline(t.start, ctx), ctx)
        // The adjusted starting value must stay inside the bounds of the control.
        when (t.adjust) {
            HeartRateAdjust.Speed -> if (speed < min || speed > max) {
                val clamped = speed.coerceIn(min, max)
                ctx.warnings += clampWarning(WarningCode.SpeedClamped, index, speed, clamped, "starting speed", "km/h", "outside the bounds of the heart-rate control")
                speed = clamped
            }
            HeartRateAdjust.Incline -> if (incline < min || incline > max) {
                val clamped = incline.coerceIn(min, max)
                ctx.warnings += clampWarning(WarningCode.InclineClamped, index, incline, clamped, "starting incline", "%", "outside the bounds of the heart-rate control")
                incline = clamped
            }
        }
        return Resolved(speed, incline, HeartRateGuidance(t.zone, t.adjust, min, max))
    }

    private fun mapSpeed(t: BlockTarget.Fixed, ctx: Context): Double = when (t) {
        is BlockTarget.Absolute -> t.speedKmh
        is BlockTarget.Relative -> {
            val easy = ctx.p.easySpeedKmh!!
            // Only missing when every relative speed is 0 % (see requiredParameters)
            val max = ctx.p.maxSpeedKmh ?: easy
            easy + t.speedPercent / 100.0 * (max - easy)
        }
    }

    private fun mapIncline(t: BlockTarget.Fixed, ctx: Context): Double = when (t) {
        is BlockTarget.Absolute -> t.inclinePercent
        is BlockTarget.Relative -> t.inclinePercent / 100.0 * (ctx.p.maxInclinePercent ?: 0.0)
    }

    private fun finalSpeed(index: Int, wanted: Double, ctx: Context): Double {
        val applied = SafetyClamp.speed(wanted, ctx.env.speedRange, ctx.env.limits)
        if (abs(applied - wanted) > ctx.env.speedRange.step / 2 + 1e-9) {
            ctx.warnings += clampWarning(WarningCode.SpeedClamped, index, wanted, applied, "speed", "km/h", "outside what the treadmill or the safety limits allow")
        }
        return applied
    }

    private fun finalIncline(index: Int, wanted: Double, ctx: Context): Double {
        val applied = SafetyClamp.incline(wanted, ctx.env.inclineRange, ctx.env.limits)
        if (abs(applied - wanted) > ctx.env.inclineRange.step / 2 + 1e-9) {
            ctx.warnings += clampWarning(WarningCode.InclineClamped, index, wanted, applied, "incline", "%", "outside what the treadmill or the safety limits allow")
        }
        return applied
    }

    private fun clampWarning(code: WarningCode, index: Int, requested: Double, applied: Double, what: String, unit: String, why: String) =
        PlanWarning(
            code, index, requested, applied,
            "Block ${index + 1}: $what ${"%.2f".format(java.util.Locale.ROOT, requested)} $unit is $why, using ${"%.2f".format(java.util.Locale.ROOT, applied)} $unit",
        )
}
