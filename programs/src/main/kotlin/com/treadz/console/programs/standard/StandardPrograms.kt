package com.treadz.console.programs.standard

import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.StartParameters

/**
 * A standard program: a relative template shipped in the app's code, plus what the UI needs to ask before running it.
 *
 * The program's [id][Program.id] (`standard-hill`, ...) and [key] are stable: the workout history and the per-user
 * last-used parameters refer to them. The names and descriptions in [Program] are English reference texts only; the UI
 * shows its own translated strings, looked up with [nameResourceKey] and [descriptionResourceKey].
 *
 * The template durations are written in real seconds for [defaultTotalDurationSeconds]; any other total is reached by
 * scaling the time-based blocks (see `ProgramResolver`). Speed percentages are positions in the user's easy-to-max
 * speed range and incline percentages are fractions of the chosen maximum incline.
 */
data class StandardProgram(
    /** Stable short key (`hill`, `rolling-hills`...). */
    val key: String,
    val program: Program,
    val defaultTotalDurationSeconds: Int,
    val minTotalDurationSeconds: Int,
    val maxTotalDurationSeconds: Int,
    /** The duration picker moves in multiples of this. */
    val durationStepSeconds: Int,
    /** Defaults of the reference values the program [uses]; the others are `null`. */
    val defaultParameters: StartParameters,
) {
    val id: String get() = program.id

    /** The reference values the user has to be asked for. Duration and intensity adjustment are always asked. */
    val uses: Set<IntensityParameter> = ProgramResolver.requiredParameters(program)

    /** Android string resource names for the UI to define (EN/ES): `standard_program_<key>_name` / `_description`. */
    val nameResourceKey: String get() = "standard_program_${key.replace('-', '_')}_name"
    val descriptionResourceKey: String get() = "standard_program_${key.replace('-', '_')}_description"
}

/**
 * The catalogue of standard programs. Each one is 5 minutes of warm-up, a main part of 20 minutes and 5 minutes of
 * cool-down at the default duration of 30 minutes; no block is shorter than 75 seconds at that duration.
 *
 * | id | English name | main part |
 * |---|---|---|
 * | `standard-hill` | Hill | incline climbs in 20 % steps to 100 %, holds 2 min, comes down; speed 40 % |
 * | `standard-rolling-hills` | Rolling hills | 10 rises and falls of varying height (15-100 %); speed 45 % |
 * | `standard-intervals` | Intervals | 5 x [2 min at 95 % speed + 2 min at 25 %] |
 * | `standard-pyramid` | Pyramid | speed steps 30-50-70-85-100-85-70-50-30 %; incline 25 % |
 * | `standard-progressive` | Progressive | ten 2 min segments, speed 30 % rising to 100 %; incline 15 % |
 * | `standard-fat-burn` | Fat burn | 3 min 45-60 % build-up, 13 min steady at 72 % with incline 20-40 %, 3 min easing |
 * | `standard-incline-walk` | Incline walk | easy speed throughout; incline climbs in the warm-up, then 100 % for 20 min |
 * | `standard-glutes` | Glutes / strength | easy speed; incline 75-100 % with small variations |
 */
object StandardPrograms {

    const val DEFAULT_TOTAL_SECONDS = 1_800
    private const val WARM_UP_SECONDS = 300
    private const val COOL_DOWN_SECONDS = 300
    private const val MAIN_SECONDS = DEFAULT_TOTAL_SECONDS - WARM_UP_SECONDS - COOL_DOWN_SECONDS

    private fun block(phase: BlockPhase, seconds: Int, speedPercent: Double, inclinePercent: Double = 0.0) =
        Block(phase, BlockEnd.Duration(seconds), BlockTarget.Relative(speedPercent, inclinePercent))

    private fun work(seconds: Int, speed: Double, incline: Double) = block(BlockPhase.Work, seconds, speed, incline)

    private fun warmUp(vararg incline: Double): List<ProgramStep> =
        if (incline.isEmpty()) listOf(block(BlockPhase.WarmUp, WARM_UP_SECONDS, 0.0))
        else incline.map { block(BlockPhase.WarmUp, WARM_UP_SECONDS / incline.size, 0.0, it) }

    private fun coolDown(): List<ProgramStep> = listOf(block(BlockPhase.CoolDown, COOL_DOWN_SECONDS, 0.0))

    private fun define(
        key: String,
        name: String,
        description: String,
        steps: List<ProgramStep>,
        easySpeedKmh: Double,
        maxSpeedKmh: Double,
        maxInclinePercent: Double,
        maxTotalMinutes: Int = 60,
    ): StandardProgram {
        val program = Program(
            id = "standard-$key",
            name = name,
            kind = ProgramKind.Standard,
            steps = steps,
            description = description,
        )
        val seconds = program.steps.sumOf { s ->
            when (s) {
                is Block -> (s.end as BlockEnd.Duration).seconds
                is Repeat -> s.count * s.blocks.sumOf { (it.end as BlockEnd.Duration).seconds }
            }
        }
        check(seconds == DEFAULT_TOTAL_SECONDS) { "$key lasts $seconds s, expected $DEFAULT_TOTAL_SECONDS" }
        val uses = ProgramResolver.requiredParameters(program)
        return StandardProgram(
            key = key,
            program = program,
            defaultTotalDurationSeconds = DEFAULT_TOTAL_SECONDS,
            minTotalDurationSeconds = 20 * 60,
            maxTotalDurationSeconds = maxTotalMinutes * 60,
            durationStepSeconds = 300,
            defaultParameters = StartParameters(
                totalDurationSeconds = DEFAULT_TOTAL_SECONDS,
                easySpeedKmh = easySpeedKmh.takeIf { IntensityParameter.EasySpeed in uses },
                maxSpeedKmh = maxSpeedKmh.takeIf { IntensityParameter.MaxSpeed in uses },
                maxInclinePercent = maxInclinePercent.takeIf { IntensityParameter.MaxIncline in uses },
            ),
        )
    }

    private val hill = define(
        "hill", "Hill", "The incline climbs step by step to the top, holds for a couple of minutes and comes back down.",
        warmUp() +
            listOf(20.0, 40.0, 60.0, 80.0).map { work(135, 40.0, it) } +
            work(120, 40.0, 100.0) +
            listOf(80.0, 60.0, 40.0, 20.0).map { work(135, 40.0, it) } +
            coolDown(),
        easySpeedKmh = 4.0, maxSpeedKmh = 8.0, maxInclinePercent = 10.0,
    )

    private val rollingHills = define(
        "rolling-hills", "Rolling hills", "Rises and falls of different height at a moderate speed.",
        warmUp() +
            listOf(25.0, 60.0, 15.0, 80.0, 40.0, 100.0, 30.0, 70.0, 20.0, 50.0).map { work(120, 45.0, it) } +
            coolDown(),
        easySpeedKmh = 4.0, maxSpeedKmh = 8.0, maxInclinePercent = 10.0,
    )

    private val intervals = define(
        "intervals", "Intervals", "Five rounds of two hard minutes and two easy minutes.",
        warmUp() +
            Repeat(
                5,
                listOf(
                    block(BlockPhase.Work, 120, 95.0, 25.0),
                    block(BlockPhase.Recovery, 120, 25.0, 0.0),
                ),
            ) +
            coolDown(),
        easySpeedKmh = 5.0, maxSpeedKmh = 12.0, maxInclinePercent = 8.0,
    )

    private val pyramid = define(
        "pyramid", "Pyramid", "The speed steps up to your maximum and back down, on a gentle incline.",
        warmUp() +
            listOf(30.0, 50.0, 70.0, 85.0).map { work(120, it, 25.0) } +
            work(240, 100.0, 25.0) +
            listOf(85.0, 70.0, 50.0, 30.0).map { work(120, it, 25.0) } +
            coolDown(),
        easySpeedKmh = 5.0, maxSpeedKmh = 10.0, maxInclinePercent = 8.0,
    )

    private val progressive = define(
        "progressive", "Progressive", "Every two minutes a little faster than before, finishing strong.",
        warmUp() +
            listOf(30.0, 36.0, 42.0, 48.0, 54.0, 60.0, 68.0, 76.0, 86.0, 100.0).map { work(120, it, 15.0) } +
            coolDown(),
        easySpeedKmh = 5.0, maxSpeedKmh = 11.0, maxInclinePercent = 8.0,
    )

    private val fatBurn = define(
        "fat-burn", "Fat burn", "A quick build-up to a long, steady and fairly demanding effort with small incline changes.",
        warmUp() +
            listOf(work(100, 45.0, 10.0), work(100, 60.0, 20.0)) +
            listOf(20.0, 40.0, 20.0, 40.0).map { work(200, 72.0, it) } +
            work(200, 55.0, 20.0) +
            coolDown(),
        easySpeedKmh = 5.0, maxSpeedKmh = 9.0, maxInclinePercent = 8.0, maxTotalMinutes = 90,
    )

    private val inclineWalk = define(
        "incline-walk", "Incline walk", "Walk at your easy speed on a steep incline, reached gradually in the warm-up.",
        warmUp(20.0, 40.0, 60.0, 80.0) +
            work(MAIN_SECONDS, 0.0, 100.0) +
            coolDown(),
        easySpeedKmh = 4.8, maxSpeedKmh = 8.0, maxInclinePercent = 12.0,
    )

    private val glutes = define(
        "glutes", "Glutes / strength", "A slow walk on a high incline with small variations.",
        warmUp() +
            listOf(75.0, 85.0, 95.0, 100.0, 85.0, 95.0, 100.0, 90.0, 100.0, 80.0).map { work(120, 0.0, it) } +
            coolDown(),
        easySpeedKmh = 3.5, maxSpeedKmh = 8.0, maxInclinePercent = 12.0,
    )

    /** In the order the UI lists them. */
    val all: List<StandardProgram> = listOf(hill, rollingHills, intervals, pyramid, progressive, fatBurn, inclineWalk, glutes)

    fun byId(id: String): StandardProgram? = all.firstOrNull { it.id == id }
}
