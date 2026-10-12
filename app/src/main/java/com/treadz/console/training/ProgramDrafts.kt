package com.treadz.console.training

import com.treadz.console.Decimals
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramLimits
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.model.ValidationIssue
import kotlin.math.roundToInt

/** A block as the editor holds it: the numbers are text, as typed. */
data class BlockDraft(
    val key: Long,
    val phase: BlockPhase = BlockPhase.Work,
    val byDistance: Boolean = false,
    /** `m:ss` (or `h:mm:ss`); a plain number is minutes. */
    val duration: String = "5:00",
    /** Metres. */
    val distance: String = "1000",
    /** km/h. */
    val speed: String = "8.0",
    /** % (whole). */
    val incline: String = "0",
    val name: String = "",
)

/** A step of the draft: a block, or a group of blocks repeated. */
sealed interface StepDraft {
    val key: Long
}

data class SingleStepDraft(override val key: Long, val block: BlockDraft) : StepDraft

data class RepeatStepDraft(override val key: Long, val count: String, val blocks: List<BlockDraft>) : StepDraft

data class ProgramDraft(
    val name: String = "",
    val description: String = "",
    val steps: List<StepDraft> = emptyList(),
)

enum class EditorField { Name, Description, Steps, RepeatCount, RepeatBlocks, Duration, Distance, Speed, Incline, BlockName }

/** Where in the draft a problem is: [step] (index in the draft), [block] inside a repeat (`null` for a single block or the step itself). */
data class FieldRef(val step: Int?, val block: Int?, val field: EditorField)

/** What is wrong with a field. The UI says it in words and gives the limits from [ProgramLimits]. */
enum class EditorProblem { Required, NotANumber, NotWhole, OutOfRange, TooLong, Empty, TooMany }

data class ParsedProgram(val program: Program?, val problems: Map<FieldRef, EditorProblem>)

object ProgramDrafts {

    /** Placeholder id for checking a program that has no id yet. */
    private const val CHECK_ID = "00000000-0000-4000-8000-000000000000"

    /** A new program starts with a warm-up, a main part and a cool-down, easy to change. */
    fun starter(nextKey: () -> Long) = ProgramDraft(
        steps = listOf(
            SingleStepDraft(nextKey(), BlockDraft(nextKey(), BlockPhase.WarmUp, duration = "5:00", speed = "5.0", incline = "0")),
            SingleStepDraft(nextKey(), BlockDraft(nextKey(), BlockPhase.Work, duration = "10:00", speed = "8.0", incline = "1")),
            SingleStepDraft(nextKey(), BlockDraft(nextKey(), BlockPhase.CoolDown, duration = "5:00", speed = "4.0", incline = "0")),
        ),
    )

    /** Programs with anything but absolute targets cannot be edited here yet (heart-rate and relative targets). */
    fun isEditable(program: Program): Boolean = program.steps.all { step ->
        when (step) {
            is Block -> step.target is BlockTarget.Absolute
            is Repeat -> step.blocks.all { it.target is BlockTarget.Absolute }
        }
    }

    /** The draft of an editable [program] (see [isEditable]). */
    fun draftOf(program: Program, nextKey: () -> Long): ProgramDraft {
        fun block(b: Block): BlockDraft {
            val target = b.target as BlockTarget.Absolute
            val end = b.end
            return BlockDraft(
                key = nextKey(),
                phase = b.phase,
                byDistance = end is BlockEnd.Distance,
                duration = if (end is BlockEnd.Duration) formatDuration(end.seconds) else "5:00",
                distance = if (end is BlockEnd.Distance) end.meters.toString() else "1000",
                speed = Decimals.format(target.speedKmh),
                incline = target.inclinePercent.roundToInt().toString(),
                name = b.name.orEmpty(),
            )
        }
        return ProgramDraft(
            name = program.name,
            description = program.description.orEmpty(),
            steps = program.steps.map { step ->
                when (step) {
                    is Block -> SingleStepDraft(nextKey(), block(step))
                    is Repeat -> RepeatStepDraft(nextKey(), step.count.toString(), step.blocks.map(::block))
                }
            },
        )
    }

    /**
     * Reads the draft. Problems with the text of a field are reported with the field; if there are none, the
     * [ProgramValidator] checks the whole program and its issues are placed on the draft by their path.
     */
    fun parse(draft: ProgramDraft, id: String = "", kind: ProgramKind = ProgramKind.Free): ParsedProgram {
        val problems = linkedMapOf<FieldRef, EditorProblem>()
        val name = draft.name.trim()
        if (name.isEmpty()) problems[FieldRef(null, null, EditorField.Name)] = EditorProblem.Required
        if (name.length > ProgramLimits.MAX_NAME_LENGTH) problems[FieldRef(null, null, EditorField.Name)] = EditorProblem.TooLong
        if (draft.description.length > ProgramLimits.MAX_DESCRIPTION_LENGTH) problems[FieldRef(null, null, EditorField.Description)] = EditorProblem.TooLong
        if (draft.steps.isEmpty()) problems[FieldRef(null, null, EditorField.Steps)] = EditorProblem.Empty

        fun block(b: BlockDraft, step: Int, inRepeat: Int?): Block {
            fun ref(field: EditorField) = FieldRef(step, inRepeat, field)
            val end: BlockEnd = if (b.byDistance) {
                val meters = b.distance.trim().toIntOrNull()
                if (meters == null) problems[ref(EditorField.Distance)] = if (b.distance.isBlank()) EditorProblem.Required else EditorProblem.NotWhole
                BlockEnd.Distance(meters ?: ProgramLimits.MIN_DISTANCE_METERS)
            } else {
                val seconds = parseDuration(b.duration)
                if (seconds == null) problems[ref(EditorField.Duration)] = if (b.duration.isBlank()) EditorProblem.Required else EditorProblem.NotANumber
                BlockEnd.Duration(seconds ?: ProgramLimits.MIN_DURATION_SECONDS)
            }
            val speed = Decimals.parse(b.speed)
            if (speed == null) problems[ref(EditorField.Speed)] = if (b.speed.isBlank()) EditorProblem.Required else EditorProblem.NotANumber
            val incline = b.incline.trim().toIntOrNull()
            if (incline == null) problems[ref(EditorField.Incline)] = if (b.incline.isBlank()) EditorProblem.Required else EditorProblem.NotWhole
            val label = b.name.trim()
            if (label.length > ProgramLimits.MAX_BLOCK_NAME_LENGTH) problems[ref(EditorField.BlockName)] = EditorProblem.TooLong
            return Block(
                phase = b.phase,
                end = end,
                target = BlockTarget.Absolute(speed?.let { (it * 10).roundToInt() / 10.0 } ?: ProgramLimits.MIN_SPEED_KMH, (incline ?: 0).toDouble()),
                name = label.ifEmpty { null },
            )
        }

        val steps: List<ProgramStep> = draft.steps.mapIndexed { i, step ->
            when (step) {
                is SingleStepDraft -> block(step.block, i, null)
                is RepeatStepDraft -> {
                    val count = step.count.trim().toIntOrNull()
                    if (count == null) problems[FieldRef(i, null, EditorField.RepeatCount)] = if (step.count.isBlank()) EditorProblem.Required else EditorProblem.NotWhole
                    if (step.blocks.isEmpty()) problems[FieldRef(i, null, EditorField.RepeatBlocks)] = EditorProblem.Empty
                    if (step.blocks.size > ProgramLimits.MAX_BLOCKS_PER_REPEAT) problems[FieldRef(i, null, EditorField.RepeatBlocks)] = EditorProblem.TooMany
                    Repeat(count ?: 1, step.blocks.mapIndexed { j, b -> block(b, i, j) })
                }
            }
        }
        val program = Program(
            id = id.ifEmpty { CHECK_ID },
            name = name.ifEmpty { "-" },
            kind = kind,
            steps = steps,
            description = draft.description.trim().ifEmpty { null },
        )
        if (problems.isEmpty()) {
            problems += placeIssues(ProgramValidator.validate(program), draft)
        }
        return ParsedProgram(program.takeIf { problems.isEmpty() }?.copy(id = id), problems)
    }

    private val STEP_PATH = Regex("""^steps\[(\d+)](?:\.blocks\[(\d+)])?(?:\.(.*))?$""")

    /** Puts the issues of the validator (or of the repository) on the fields of the draft they are about. */
    fun placeIssues(issues: List<ValidationIssue>, draft: ProgramDraft): Map<FieldRef, EditorProblem> {
        val result = linkedMapOf<FieldRef, EditorProblem>()
        for (issue in issues) {
            val path = issue.path
            when {
                path == "name" -> result[FieldRef(null, null, EditorField.Name)] = if (draft.name.isBlank()) EditorProblem.Required else EditorProblem.TooLong
                path == "description" -> result[FieldRef(null, null, EditorField.Description)] = EditorProblem.TooLong
                path == "steps" -> result[FieldRef(null, null, EditorField.Steps)] = if (draft.steps.isEmpty()) EditorProblem.Empty else EditorProblem.TooMany
                else -> {
                    val m = STEP_PATH.matchEntire(path)
                    if (m == null) {
                        // Anything else (the repository's own paths, a stored program's id...) is about the program as a whole.
                        result[FieldRef(null, null, EditorField.Steps)] = EditorProblem.OutOfRange
                    } else {
                        val step = m.groupValues[1].toInt()
                        val block = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                        val rest = m.groupValues[3]
                        val (ref, problem) = when {
                            rest == "count" -> FieldRef(step, null, EditorField.RepeatCount) to EditorProblem.OutOfRange
                            rest == "blocks" -> FieldRef(step, null, EditorField.RepeatBlocks) to
                                ((draft.steps.getOrNull(step) as? RepeatStepDraft)?.blocks?.isEmpty()?.let { if (it) EditorProblem.Empty else EditorProblem.TooMany } ?: EditorProblem.TooMany)
                            rest == "durationSeconds" -> FieldRef(step, block, EditorField.Duration) to EditorProblem.OutOfRange
                            rest == "distanceMeters" -> FieldRef(step, block, EditorField.Distance) to EditorProblem.OutOfRange
                            rest.endsWith("speedKmh") -> FieldRef(step, block, EditorField.Speed) to EditorProblem.OutOfRange
                            rest.endsWith("inclinePercent") -> FieldRef(step, block, EditorField.Incline) to EditorProblem.OutOfRange
                            rest == "name" -> FieldRef(step, block, EditorField.BlockName) to EditorProblem.TooLong
                            else -> FieldRef(step, block, EditorField.Steps) to EditorProblem.OutOfRange
                        }
                        result[ref] = problem
                    }
                }
            }
        }
        return result
    }

    /** Blocks whose planned time is so short that much of it goes to the speed ramp (see `ProgramResolver.SHORT_BLOCK_SECONDS`). */
    fun shortBlocks(draft: ProgramDraft, shortSeconds: Int): Set<Pair<Int, Int?>> {
        val result = mutableSetOf<Pair<Int, Int?>>()
        fun short(b: BlockDraft): Boolean {
            val seconds = if (b.byDistance) {
                val meters = b.distance.trim().toIntOrNull()
                val speed = Decimals.parse(b.speed)
                if (meters == null || speed == null || speed <= 0.0) return false
                meters / (speed / 3.6)
            } else {
                parseDuration(b.duration)?.toDouble() ?: return false
            }
            return seconds < shortSeconds
        }
        draft.steps.forEachIndexed { i, step ->
            when (step) {
                is SingleStepDraft -> if (short(step.block)) result += i to null
                is RepeatStepDraft -> step.blocks.forEachIndexed { j, b -> if (short(b)) result += i to j }
            }
        }
        return result
    }

    /** `4:30`, `1:05:00` or a plain number of minutes. */
    fun parseDuration(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return when (numbers.size) {
            1 -> numbers[0] * 60
            2 -> if (numbers[1] >= 60) null else numbers[0] * 60 + numbers[1]
            else -> if (numbers[1] >= 60 || numbers[2] >= 60) null else numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Small steps for the − / + buttons of the editor

internal fun BlockDraft.stepSpeed(direction: Int): BlockDraft {
    val current = Decimals.parse(speed) ?: return this
    val value = ((current + direction * 0.5) * 10).roundToInt() / 10.0
    return copy(speed = Decimals.format(value.coerceIn(ProgramLimits.MIN_SPEED_KMH, ProgramLimits.MAX_SPEED_KMH)))
}

internal fun BlockDraft.stepIncline(direction: Int): BlockDraft {
    val current = incline.trim().toIntOrNull() ?: return this
    return copy(incline = (current + direction).coerceIn(ProgramLimits.MIN_INCLINE_PERCENT.toInt(), ProgramLimits.MAX_INCLINE_PERCENT.toInt()).toString())
}

internal fun BlockDraft.stepDuration(direction: Int): BlockDraft {
    val current = ProgramDrafts.parseDuration(duration) ?: return this
    val stepped = (current + direction * DURATION_STEP_SECONDS).coerceIn(DURATION_STEP_SECONDS, ProgramLimits.MAX_DURATION_SECONDS)
    return copy(duration = ProgramDrafts.formatDuration(stepped))
}

internal fun BlockDraft.stepDistance(direction: Int): BlockDraft {
    val current = distance.trim().toIntOrNull() ?: return this
    return copy(distance = (current + direction * DISTANCE_STEP_METERS).coerceIn(DISTANCE_STEP_METERS, ProgramLimits.MAX_DISTANCE_METERS).toString())
}

internal const val DURATION_STEP_SECONDS = 15
internal const val DISTANCE_STEP_METERS = 100
