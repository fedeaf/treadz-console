package com.treadz.console.training

import com.treadz.console.Decimals
import com.treadz.console.programs.engine.HeartRateControlConfig
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramLimits
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.model.ValidationIssue
import kotlin.math.roundToInt

/**
 * The heart-rate target of a block as the editor holds it. The speed and the incline of the block are the **starting
 * values**; the control moves [adjust] between [minimum] and [maximum] (text, as typed: km/h or %).
 */
data class HeartRateDraft(
    /** 0-4: Z1-Z5 of [HeartRateZones.STANDARD]; `null` for a zone of the user's own ([customLow], [customHigh]). */
    val standardZone: Int? = 1,
    /** % of the basis, as typed. */
    val customLow: String = "60",
    val customHigh: String = "70",
    /** The zone is a % of the heart-rate reserve instead of the maximum (needs a resting heart rate). */
    val reserve: Boolean = false,
    val adjust: HeartRateAdjust = HeartRateAdjust.Speed,
    val minimum: String = "",
    val maximum: String = "",
    /** The bounds follow the starting value until the user types one of them. */
    val boundsAuto: Boolean = true,
)

/** A block as the editor holds it: the numbers are text, as typed. */
data class BlockDraft(
    val key: Long,
    val phase: BlockPhase = BlockPhase.Work,
    val byDistance: Boolean = false,
    /** `m:ss` (or `h:mm:ss`); a plain number is minutes. */
    val duration: String = "5:00",
    /** Metres. */
    val distance: String = "1000",
    /** km/h. For a heart-rate block, the starting speed. */
    val speed: String = "8.0",
    /** % (whole). For a heart-rate block, the starting incline. */
    val incline: String = "0",
    val name: String = "",
    /** Set when the block's target is a heart-rate zone instead of a speed and an incline. */
    val heartRate: HeartRateDraft? = null,
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

enum class EditorField {
    Name, Description, Steps, RepeatCount, RepeatBlocks, Duration, Distance, Speed, Incline, BlockName,
    ZoneLow, ZoneHigh, HeartRateMin, HeartRateMax,
}

/** Where in the draft a problem is: [step] (index in the draft), [block] inside a repeat (`null` for a single block or the step itself). */
data class FieldRef(val step: Int?, val block: Int?, val field: EditorField)

/** What is wrong with a field. The UI says it in words and gives the limits from [ProgramLimits]. */
enum class EditorProblem {
    Required, NotANumber, NotWhole, OutOfRange, TooLong, Empty, TooMany,

    /** Heart-rate zone: the low end is not below the high end. */
    ZoneOrder,

    /** Heart-rate bounds: the minimum is above the maximum. */
    MinAboveMax,

    /** Heart-rate block: the starting value is outside the bounds. */
    StartOutsideBounds,
}

data class ParsedProgram(val program: Program?, val problems: Map<FieldRef, EditorProblem>)

object ProgramDrafts {

    /** Placeholder id for checking a program that has no id yet. */
    private const val CHECK_ID = "00000000-0000-4000-8000-000000000000"

    /** A heart-rate block shorter than this is too short for the heart rate to respond (a warning only). */
    const val HEART_RATE_MIN_SECONDS = 180

    /** A new program starts with a warm-up, a main part and a cool-down, easy to change. */
    fun starter(nextKey: () -> Long) = ProgramDraft(
        steps = listOf(
            SingleStepDraft(nextKey(), BlockDraft(nextKey(), BlockPhase.WarmUp, duration = "5:00", speed = "5.0", incline = "0")),
            SingleStepDraft(nextKey(), BlockDraft(nextKey(), BlockPhase.Work, duration = "10:00", speed = "8.0", incline = "1")),
            SingleStepDraft(nextKey(), BlockDraft(nextKey(), BlockPhase.CoolDown, duration = "5:00", speed = "4.0", incline = "0")),
        ),
    )

    /**
     * Programs with relative targets cannot be edited here, nor can the personalised ones (their values come from the
     * generator: duplicate one to get a free program that can be edited). Absolute and heart-rate targets (with an absolute
     * start) can.
     */
    fun isEditable(program: Program): Boolean = program.kind != ProgramKind.Personalised && program.steps.all { step ->
        when (step) {
            is Block -> isEditable(step.target)
            is Repeat -> step.blocks.all { isEditable(it.target) }
        }
    }

    private fun isEditable(target: BlockTarget): Boolean = when (target) {
        is BlockTarget.Absolute -> true
        is BlockTarget.Relative -> false
        is BlockTarget.HeartRate -> target.start is BlockTarget.Absolute
    }

    /** The draft of an editable [program] (see [isEditable]). */
    fun draftOf(program: Program, nextKey: () -> Long): ProgramDraft {
        fun block(b: Block): BlockDraft {
            val target = b.target
            val hr = target as? BlockTarget.HeartRate
            val start = (hr?.start ?: target) as BlockTarget.Absolute
            val end = b.end
            return BlockDraft(
                key = nextKey(),
                phase = b.phase,
                byDistance = end is BlockEnd.Distance,
                duration = if (end is BlockEnd.Duration) formatDuration(end.seconds) else "5:00",
                distance = if (end is BlockEnd.Distance) end.meters.toString() else "1000",
                speed = Decimals.format(start.speedKmh),
                incline = start.inclinePercent.roundToInt().toString(),
                name = b.name.orEmpty(),
                heartRate = hr?.let { draftOf(it) },
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

    private fun draftOf(t: BlockTarget.HeartRate): HeartRateDraft {
        val standard = HeartRateZones.STANDARD.indexOfFirst { it.lowPercent == t.zone.lowPercent && it.highPercent == t.zone.highPercent }
        return HeartRateDraft(
            standardZone = standard.takeIf { it >= 0 },
            customLow = t.zone.lowPercent.toString(),
            customHigh = t.zone.highPercent.toString(),
            reserve = t.zone.basis == HeartRateBasis.HeartRateReserve,
            adjust = t.adjust,
            minimum = boundText(t.adjust, t.minimum),
            maximum = boundText(t.adjust, t.maximum),
            // What was stored is kept as it is: the bounds only follow the start value for a block set up here.
            boundsAuto = false,
        )
    }

    private fun boundText(adjust: HeartRateAdjust, value: Double): String =
        if (adjust == HeartRateAdjust.Speed) Decimals.format(value) else value.roundToInt().toString()

    // ---- heart-rate blocks

    /** The heart-rate target a block starts with when the user switches to "Heart rate": zone 2, adjusting the speed. */
    fun heartRateDraftFor(block: BlockDraft, speedMax: Double, inclineMax: Double): HeartRateDraft =
        HeartRateDraft().withAutoBounds(block, speedMax, inclineMax)

    /**
     * The bounds of the adjusted quantity from its starting value, clamped to the treadmill: the speed ±2 km/h; the
     * incline from 3 % below to 5 % above. Unchanged when the starting value is not a number.
     */
    fun HeartRateDraft.withAutoBounds(block: BlockDraft, speedMax: Double, inclineMax: Double): HeartRateDraft {
        if (!boundsAuto) return this
        return when (adjust) {
            HeartRateAdjust.Speed -> {
                val start = Decimals.parse(block.speed) ?: return this
                copy(
                    minimum = Decimals.format(((start - SPEED_MARGIN_KMH).coerceAtLeast(ProgramLimits.MIN_SPEED_KMH) * 10).roundToInt() / 10.0),
                    maximum = Decimals.format(((start + SPEED_MARGIN_KMH).coerceAtMost(speedMax) * 10).roundToInt() / 10.0),
                )
            }
            HeartRateAdjust.Incline -> {
                val start = block.incline.trim().toIntOrNull() ?: return this
                copy(
                    minimum = (start - INCLINE_MARGIN_BELOW).coerceAtLeast(ProgramLimits.MIN_INCLINE_PERCENT.toInt()).toString(),
                    maximum = (start + INCLINE_MARGIN_ABOVE).coerceAtMost(inclineMax.toInt()).toString(),
                )
            }
        }
    }

    private const val SPEED_MARGIN_KMH = 2.0
    private const val INCLINE_MARGIN_BELOW = 3
    private const val INCLINE_MARGIN_ABOVE = 5

    /** The zone a heart-rate draft asks for, or `null` while the typed percentages are not numbers. */
    fun zoneOf(hr: HeartRateDraft): HeartRateZone? {
        val basis = if (hr.reserve) HeartRateBasis.HeartRateReserve else HeartRateBasis.MaxHeartRate
        val standard = hr.standardZone?.let { HeartRateZones.STANDARD.getOrNull(it) }
        if (standard != null) return standard.copy(basis = basis)
        val low = hr.customLow.trim().toIntOrNull() ?: return null
        val high = hr.customHigh.trim().toIntOrNull() ?: return null
        return HeartRateZone(basis, low, high)
    }

    /**
     * How a heart-rate block is controlled: a block inside a repeat that lasts less than 6 minutes is controlled between the
     * repetitions, every other one continuously (the rule of the program runner).
     */
    fun controlMode(inRepeat: Boolean, seconds: Double): HeartRateControlMode =
        if (inRepeat && seconds < HeartRateControlConfig().continuousMinBlockSeconds) HeartRateControlMode.BetweenRepetitions
        else HeartRateControlMode.Continuous

    /** Seconds a block lasts as written; a block that ends by distance at its starting speed. `null` while a number is not valid. */
    fun blockSeconds(b: BlockDraft): Double? =
        if (b.byDistance) {
            val meters = b.distance.trim().toIntOrNull()
            val speed = Decimals.parse(b.speed)
            if (meters == null || speed == null || speed <= 0.0) null else meters / (speed / 3.6)
        } else {
            parseDuration(b.duration)?.toDouble()
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
            val absolute = BlockTarget.Absolute(speed?.let { (it * 10).roundToInt() / 10.0 } ?: ProgramLimits.MIN_SPEED_KMH, (incline ?: 0).toDouble())
            val target: BlockTarget = b.heartRate?.let { hr ->
                fun percent(text: String, field: EditorField): Int {
                    val v = text.trim().toIntOrNull()
                    if (v == null) problems[ref(field)] = if (text.isBlank()) EditorProblem.Required else EditorProblem.NotWhole
                    return v ?: 0
                }
                val zone = if (hr.standardZone != null) {
                    zoneOf(hr) ?: HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70)
                } else {
                    val low = percent(hr.customLow, EditorField.ZoneLow)
                    val high = percent(hr.customHigh, EditorField.ZoneHigh)
                    HeartRateZone(if (hr.reserve) HeartRateBasis.HeartRateReserve else HeartRateBasis.MaxHeartRate, low, high)
                }
                fun bound(text: String, field: EditorField): Double {
                    val v = Decimals.parse(text)
                    if (v == null) {
                        problems[ref(field)] = if (text.isBlank()) EditorProblem.Required else EditorProblem.NotANumber
                    } else if (hr.adjust == HeartRateAdjust.Incline && v != v.roundToInt().toDouble()) {
                        problems[ref(field)] = EditorProblem.NotWhole
                    }
                    return if (hr.adjust == HeartRateAdjust.Speed) ((v ?: ProgramLimits.MIN_SPEED_KMH) * 10).roundToInt() / 10.0 else (v ?: 0.0).roundToInt().toDouble()
                }
                BlockTarget.HeartRate(
                    zone = zone,
                    adjust = hr.adjust,
                    minimum = bound(hr.minimum, EditorField.HeartRateMin),
                    maximum = bound(hr.maximum, EditorField.HeartRateMax),
                    start = absolute,
                )
            } ?: absolute
            return Block(phase = b.phase, end = end, target = target, name = label.ifEmpty { null })
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
                        val draftBlock = when (val s = draft.steps.getOrNull(step)) {
                            is SingleStepDraft -> s.block.takeIf { block == null }
                            is RepeatStepDraft -> block?.let { s.blocks.getOrNull(it) }
                            null -> null
                        }
                        if (rest == "target.bounds") {
                            result += placeBoundsIssue(step, block, draftBlock?.heartRate)
                            continue
                        }
                        val (ref, problem) = when {
                            rest == "count" -> FieldRef(step, null, EditorField.RepeatCount) to EditorProblem.OutOfRange
                            rest == "blocks" -> FieldRef(step, null, EditorField.RepeatBlocks) to
                                ((draft.steps.getOrNull(step) as? RepeatStepDraft)?.blocks?.isEmpty()?.let { if (it) EditorProblem.Empty else EditorProblem.TooMany } ?: EditorProblem.TooMany)
                            rest == "durationSeconds" -> FieldRef(step, block, EditorField.Duration) to EditorProblem.OutOfRange
                            rest == "distanceMeters" -> FieldRef(step, block, EditorField.Distance) to EditorProblem.OutOfRange
                            rest == "target.zone.lowPercent" -> FieldRef(step, block, EditorField.ZoneLow) to EditorProblem.OutOfRange
                            rest == "target.zone.highPercent" -> FieldRef(step, block, EditorField.ZoneHigh) to EditorProblem.OutOfRange
                            rest == "target.zone" -> FieldRef(step, block, EditorField.ZoneHigh) to EditorProblem.ZoneOrder
                            // The starting value is outside the bounds: said next to the starting value of the adjusted quantity.
                            rest == "target.start" -> FieldRef(
                                step, block,
                                if (draftBlock?.heartRate?.adjust == HeartRateAdjust.Incline) EditorField.Incline else EditorField.Speed,
                            ) to EditorProblem.StartOutsideBounds
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

    /** The bounds of a heart-rate block are outside the treadmill's range, or the minimum is above the maximum. */
    private fun placeBoundsIssue(step: Int, block: Int?, hr: HeartRateDraft?): Map<FieldRef, EditorProblem> {
        val min = hr?.minimum?.let(Decimals::parse)
        val max = hr?.maximum?.let(Decimals::parse)
        val (low, high) = if (hr?.adjust == HeartRateAdjust.Incline) {
            ProgramLimits.MIN_INCLINE_PERCENT to ProgramLimits.MAX_INCLINE_PERCENT
        } else {
            ProgramLimits.MIN_SPEED_KMH to ProgramLimits.MAX_SPEED_KMH
        }
        val result = linkedMapOf<FieldRef, EditorProblem>()
        if (min != null && (min < low || min > high)) result[FieldRef(step, block, EditorField.HeartRateMin)] = EditorProblem.OutOfRange
        if (max != null && (max < low || max > high)) result[FieldRef(step, block, EditorField.HeartRateMax)] = EditorProblem.OutOfRange
        if (result.isEmpty()) result[FieldRef(step, block, EditorField.HeartRateMax)] = EditorProblem.MinAboveMax
        return result
    }

    /** Blocks whose planned time is so short that much of it goes to the speed ramp (see `ProgramResolver.SHORT_BLOCK_SECONDS`). */
    fun shortBlocks(draft: ProgramDraft, shortSeconds: Int): Set<Pair<Int, Int?>> =
        blocksWhere(draft) { b -> blockSeconds(b)?.let { it < shortSeconds } == true }

    /** Heart-rate blocks that last less than [HEART_RATE_MIN_SECONDS]: the heart rate has no time to respond. A warning only. */
    fun shortHeartRateBlocks(draft: ProgramDraft): Set<Pair<Int, Int?>> =
        blocksWhere(draft) { b -> b.heartRate != null && blockSeconds(b)?.let { it < HEART_RATE_MIN_SECONDS } == true }

    /** The control mode of every heart-rate block (see [controlMode]). */
    fun controlModes(draft: ProgramDraft): Map<Pair<Int, Int?>, HeartRateControlMode> {
        val result = linkedMapOf<Pair<Int, Int?>, HeartRateControlMode>()
        draft.steps.forEachIndexed { i, step ->
            when (step) {
                is SingleStepDraft -> if (step.block.heartRate != null) result[i to null] = controlMode(false, blockSeconds(step.block) ?: Double.MAX_VALUE)
                is RepeatStepDraft -> step.blocks.forEachIndexed { j, b ->
                    if (b.heartRate != null) result[i to j] = controlMode(true, blockSeconds(b) ?: Double.MAX_VALUE)
                }
            }
        }
        return result
    }

    private fun blocksWhere(draft: ProgramDraft, test: (BlockDraft) -> Boolean): Set<Pair<Int, Int?>> {
        val result = mutableSetOf<Pair<Int, Int?>>()
        draft.steps.forEachIndexed { i, step ->
            when (step) {
                is SingleStepDraft -> if (test(step.block)) result += i to null
                is RepeatStepDraft -> step.blocks.forEachIndexed { j, b -> if (test(b)) result += i to j }
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

/** The − / + of the minimum or the maximum of a heart-rate block: 0.5 km/h for the speed, 1 % for the incline. */
internal fun BlockDraft.stepHeartRateBound(maximum: Boolean, direction: Int): BlockDraft {
    val hr = heartRate ?: return this
    val text = if (maximum) hr.maximum else hr.minimum
    val current = Decimals.parse(text) ?: return this
    val stepped = if (hr.adjust == HeartRateAdjust.Speed) {
        Decimals.format((((current + direction * 0.5) * 10).roundToInt() / 10.0).coerceIn(ProgramLimits.MIN_SPEED_KMH, ProgramLimits.MAX_SPEED_KMH))
    } else {
        (current.roundToInt() + direction).coerceIn(ProgramLimits.MIN_INCLINE_PERCENT.toInt(), ProgramLimits.MAX_INCLINE_PERCENT.toInt()).toString()
    }
    return copy(heartRate = if (maximum) hr.copy(maximum = stepped, boundsAuto = false) else hr.copy(minimum = stepped, boundsAuto = false))
}

/** The − / + of a custom zone's low or high end: 1 % of the basis, 0-100. */
internal fun BlockDraft.stepZone(high: Boolean, direction: Int): BlockDraft {
    val hr = heartRate ?: return this
    val current = (if (high) hr.customHigh else hr.customLow).trim().toIntOrNull() ?: return this
    val stepped = (current + direction).coerceIn(0, 100).toString()
    return copy(heartRate = if (high) hr.copy(customHigh = stepped) else hr.copy(customLow = stepped))
}

internal const val DURATION_STEP_SECONDS = 15
internal const val DISTANCE_STEP_METERS = 100
