package com.treadz.console.programs.model

import com.treadz.console.treadmill.control.SafetyClamp

/** One problem found in a program. [path] says where (`steps[2].blocks[0].end.seconds`), [message] what is wrong. */
data class ValidationIssue(val path: String, val message: String) {
    override fun toString(): String = "$path: $message"
}

/** Thrown when something that must be valid is not. [issues] lists everything that was found. */
class InvalidProgramException(val issues: List<ValidationIssue>) :
    IllegalArgumentException(issues.joinToString("; ", prefix = "Invalid program: "))

/** Limits of what a program may contain. */
object ProgramLimits {
    const val MAX_NAME_LENGTH = 80
    const val MAX_BLOCK_NAME_LENGTH = 60
    const val MAX_DESCRIPTION_LENGTH = 1_000
    const val MAX_STEPS = 100
    const val MIN_REPEAT_COUNT = 1
    const val MAX_REPEAT_COUNT = 50
    const val MAX_BLOCKS_PER_REPEAT = 20
    const val MAX_EXPANDED_BLOCKS = 400
    const val MIN_DURATION_SECONDS = 1
    const val MAX_DURATION_SECONDS = 12 * 3_600
    const val MIN_DISTANCE_METERS = 10
    const val MAX_DISTANCE_METERS = 100_000
    const val MAX_ORIGIN_INPUTS = 50

    /** Nominal Z-Tech ranges. The treadmill's own capabilities are applied when the program is resolved. */
    val MIN_SPEED_KMH: Double get() = SafetyClamp.DEFAULT_SPEED_RANGE.min
    val MAX_SPEED_KMH: Double get() = SafetyClamp.DEFAULT_SPEED_RANGE.max
    val MIN_INCLINE_PERCENT: Double get() = SafetyClamp.DEFAULT_INCLINE_RANGE.min
    val MAX_INCLINE_PERCENT: Double get() = SafetyClamp.DEFAULT_INCLINE_RANGE.max
}

/** Checks a [Program] and reports every problem with its location. */
object ProgramValidator {

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val STANDARD_ID_REGEX = Regex("^standard-[a-z0-9]+(-[a-z0-9]+)*$")

    fun validate(program: Program): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun issue(path: String, message: String) {
            issues += ValidationIssue(path, message)
        }

        val idOk = UUID_REGEX.matches(program.id) || (program.kind == ProgramKind.Standard && STANDARD_ID_REGEX.matches(program.id))
        if (!idOk) issue("id", "must be a UUID, or a 'standard-...' slug for standard programs (was '${program.id}')")
        if (program.name.isBlank()) issue("name", "must not be blank")
        if (program.name.length > ProgramLimits.MAX_NAME_LENGTH) issue("name", "must have at most ${ProgramLimits.MAX_NAME_LENGTH} characters")
        program.description?.let {
            if (it.length > ProgramLimits.MAX_DESCRIPTION_LENGTH) issue("description", "must have at most ${ProgramLimits.MAX_DESCRIPTION_LENGTH} characters")
        }
        if (program.schemaVersion !in 1..Program.SCHEMA_VERSION) {
            issue("schemaVersion", "must be between 1 and ${Program.SCHEMA_VERSION} (was ${program.schemaVersion})")
        }

        val origin = program.origin
        if (program.kind == ProgramKind.Personalised && origin == null) {
            issue("origin", "is required for personalised programs")
        }
        if (origin != null) {
            if (origin.generatorId.isBlank()) issue("origin.generatorId", "must not be blank")
            if (origin.generatorVersion < 1) issue("origin.generatorVersion", "must be at least 1")
            if (origin.inputs.size > ProgramLimits.MAX_ORIGIN_INPUTS) issue("origin.inputs", "must have at most ${ProgramLimits.MAX_ORIGIN_INPUTS} entries")
            origin.inputs.keys.filter { it.isBlank() }.forEach { _ -> issue("origin.inputs", "keys must not be blank") }
        }

        if (program.steps.isEmpty()) issue("steps", "must contain at least one step")
        if (program.steps.size > ProgramLimits.MAX_STEPS) issue("steps", "must have at most ${ProgramLimits.MAX_STEPS} steps")

        program.steps.forEachIndexed { i, step ->
            val path = "steps[$i]"
            when (step) {
                is Block -> validateBlock(step, path, ::issue)
                is Repeat -> {
                    if (step.count !in ProgramLimits.MIN_REPEAT_COUNT..ProgramLimits.MAX_REPEAT_COUNT) {
                        issue("$path.count", "must be between ${ProgramLimits.MIN_REPEAT_COUNT} and ${ProgramLimits.MAX_REPEAT_COUNT} (was ${step.count})")
                    }
                    if (step.blocks.isEmpty()) issue("$path.blocks", "must contain at least one block")
                    if (step.blocks.size > ProgramLimits.MAX_BLOCKS_PER_REPEAT) {
                        issue("$path.blocks", "must have at most ${ProgramLimits.MAX_BLOCKS_PER_REPEAT} blocks")
                    }
                    step.blocks.forEachIndexed { j, b -> validateBlock(b, "$path.blocks[$j]", ::issue) }
                }
            }
        }

        if (program.expandedBlockCount > ProgramLimits.MAX_EXPANDED_BLOCKS) {
            issue("steps", "expands to more than ${ProgramLimits.MAX_EXPANDED_BLOCKS} blocks")
        }
        val seconds = totalDurationSeconds(program)
        if (seconds > ProgramLimits.MAX_DURATION_SECONDS) {
            issue("steps", "the time-based blocks add up to more than ${ProgramLimits.MAX_DURATION_SECONDS / 3_600} hours")
        }
        return issues
    }

    /** Throws [InvalidProgramException] if [program] has any problem. */
    fun requireValid(program: Program): Program {
        val issues = validate(program)
        if (issues.isNotEmpty()) throw InvalidProgramException(issues)
        return program
    }

    /** Sum of the time-based blocks after expanding repeats (distance-based blocks are not counted). */
    internal fun totalDurationSeconds(program: Program): Long = program.steps.sumOf { step ->
        when (step) {
            is Block -> (step.end as? BlockEnd.Duration)?.seconds?.toLong() ?: 0L
            is Repeat -> step.count.toLong() * step.blocks.sumOf { (it.end as? BlockEnd.Duration)?.seconds?.toLong() ?: 0L }
        }
    }

    private fun validateBlock(block: Block, path: String, issue: (String, String) -> Unit) {
        block.name?.let {
            if (it.isBlank()) issue("$path.name", "must not be blank when present")
            if (it.length > ProgramLimits.MAX_BLOCK_NAME_LENGTH) issue("$path.name", "must have at most ${ProgramLimits.MAX_BLOCK_NAME_LENGTH} characters")
        }
        when (val end = block.end) {
            is BlockEnd.Duration -> if (end.seconds !in ProgramLimits.MIN_DURATION_SECONDS..ProgramLimits.MAX_DURATION_SECONDS) {
                issue("$path.durationSeconds", "must be between ${ProgramLimits.MIN_DURATION_SECONDS} and ${ProgramLimits.MAX_DURATION_SECONDS} seconds (was ${end.seconds})")
            }
            is BlockEnd.Distance -> if (end.meters !in ProgramLimits.MIN_DISTANCE_METERS..ProgramLimits.MAX_DISTANCE_METERS) {
                issue("$path.distanceMeters", "must be between ${ProgramLimits.MIN_DISTANCE_METERS} and ${ProgramLimits.MAX_DISTANCE_METERS} meters (was ${end.meters})")
            }
        }
        validateTarget(block.target, "$path.target", issue)
    }

    private fun validateTarget(target: BlockTarget, path: String, issue: (String, String) -> Unit) {
        when (target) {
            is BlockTarget.Absolute -> {
                absoluteSpeed(target.speedKmh, "$path.speedKmh", issue)
                absoluteIncline(target.inclinePercent, "$path.inclinePercent", issue)
            }
            is BlockTarget.Relative -> {
                percent(target.speedPercent, "$path.speedPercent", issue)
                percent(target.inclinePercent, "$path.inclinePercent", issue)
            }
            is BlockTarget.HeartRate -> {
                val z = target.zone
                if (z.lowPercent !in 0..99) issue("$path.zone.lowPercent", "must be between 0 and 99 (was ${z.lowPercent})")
                if (z.highPercent !in 1..100) issue("$path.zone.highPercent", "must be between 1 and 100 (was ${z.highPercent})")
                if (z.lowPercent >= z.highPercent) issue("$path.zone", "lowPercent must be below highPercent")
                validateTarget(target.start, "$path.start", issue)
                val relative = target.start is BlockTarget.Relative
                val low: Double
                val high: Double
                val label: String
                when (target.adjust) {
                    HeartRateAdjust.Speed -> {
                        low = if (relative) 0.0 else ProgramLimits.MIN_SPEED_KMH
                        high = if (relative) 100.0 else ProgramLimits.MAX_SPEED_KMH
                        label = if (relative) "speed percent" else "speed km/h"
                    }
                    HeartRateAdjust.Incline -> {
                        low = if (relative) 0.0 else ProgramLimits.MIN_INCLINE_PERCENT
                        high = if (relative) 100.0 else ProgramLimits.MAX_INCLINE_PERCENT
                        label = if (relative) "incline percent of the range" else "incline percent"
                    }
                }
                val boundsOk = listOf(target.minimum, target.maximum).all { it.isFinite() && it in low..high }
                if (!boundsOk) issue("$path.bounds", "minimum and maximum must be between $low and $high ($label)")
                else if (target.minimum > target.maximum) issue("$path.bounds", "minimum must not be above maximum")
                else {
                    val startValue = startValueOf(target)
                    if (startValue.isFinite() && (startValue < target.minimum - 1e-9 || startValue > target.maximum + 1e-9)) {
                        issue("$path.start", "the starting value ($startValue) must be inside the bounds [${target.minimum}, ${target.maximum}]")
                    }
                }
            }
        }
    }

    /** The starting value of the quantity that a heart-rate block adjusts, in the scale of its bounds. */
    internal fun startValueOf(t: BlockTarget.HeartRate): Double = when (val s = t.start) {
        is BlockTarget.Absolute -> if (t.adjust == HeartRateAdjust.Speed) s.speedKmh else s.inclinePercent
        is BlockTarget.Relative -> if (t.adjust == HeartRateAdjust.Speed) s.speedPercent else s.inclinePercent
    }

    private fun absoluteSpeed(v: Double, path: String, issue: (String, String) -> Unit) {
        if (!v.isFinite() || v < ProgramLimits.MIN_SPEED_KMH || v > ProgramLimits.MAX_SPEED_KMH) {
            issue(path, "must be between ${ProgramLimits.MIN_SPEED_KMH} and ${ProgramLimits.MAX_SPEED_KMH} km/h (was $v)")
        }
    }

    private fun absoluteIncline(v: Double, path: String, issue: (String, String) -> Unit) {
        if (!v.isFinite() || v < ProgramLimits.MIN_INCLINE_PERCENT || v > ProgramLimits.MAX_INCLINE_PERCENT) {
            issue(path, "must be between ${ProgramLimits.MIN_INCLINE_PERCENT} and ${ProgramLimits.MAX_INCLINE_PERCENT} % (was $v)")
        }
    }

    private fun percent(v: Double, path: String, issue: (String, String) -> Unit) {
        if (!v.isFinite() || v < 0.0 || v > 100.0) issue(path, "must be between 0 and 100 % (was $v)")
    }
}
