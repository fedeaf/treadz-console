package com.treadz.console.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.treadz.console.R
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.CouchToFiveKDecision
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.CouchToFiveKSelection
import com.treadz.console.programs.personalised.GeneratorNotice
import com.treadz.console.programs.personalised.GeneratorNoticeCode
import com.treadz.console.programs.personalised.SessionOutcome
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.training.GeneratorProblem
import com.treadz.console.training.positionKey

/**
 * Resources of the translated name and description of a generator. They are named after the generator's own keys
 * (`GeneratorDescriptor.nameResourceKey`); a test checks that every generator of the registry has its pair.
 */
fun generatorNameRes(id: String): Int = when (id) {
    "zone2" -> R.string.generator_zone2_name
    "tempo" -> R.string.generator_tempo_name
    "norwegian-4x4" -> R.string.generator_norwegian_4x4_name
    "intervals-30-30" -> R.string.generator_intervals_30_30_name
    "couch-to-5k" -> R.string.generator_couch_to_5k_name
    else -> R.string.generator_unknown_name
}

fun generatorDescriptionRes(id: String): Int = when (id) {
    "zone2" -> R.string.generator_zone2_description
    "tempo" -> R.string.generator_tempo_description
    "norwegian-4x4" -> R.string.generator_norwegian_4x4_description
    "intervals-30-30" -> R.string.generator_intervals_30_30_description
    "couch-to-5k" -> R.string.generator_couch_to_5k_description
    else -> R.string.generator_unknown_name
}

/** The tag of a workout that came from a generator: its name, with the session number for Couch to 5K. */
@Composable
@ReadOnlyComposable
fun generatorTagText(generatorId: String, couchSession: Int?): String {
    val name = stringResource(generatorNameRes(generatorId))
    return if (generatorId == CouchToFiveK.ID && couchSession != null) stringResource(R.string.couch_tag_session, name, couchSession) else name
}

@Composable
@ReadOnlyComposable
fun ExerciseMode.label(): String = stringResource(if (this == ExerciseMode.Walk) R.string.mode_walk else R.string.mode_run)

@Composable
@ReadOnlyComposable
fun HeartRateAdjust.label(): String = stringResource(if (this == HeartRateAdjust.Speed) R.string.hr_adjust_speed else R.string.hr_adjust_incline)

@Composable
@ReadOnlyComposable
fun GeneratorProblem.text(): String = stringResource(
    when (this) {
        GeneratorProblem.AgeOutOfRange -> R.string.generator_problem_age
        GeneratorProblem.WalkingSpeed -> R.string.generator_problem_walking_speed
        GeneratorProblem.Rejected -> R.string.generator_problem_rejected
    },
)

@Composable
@ReadOnlyComposable
fun CouchToFiveKDecision.text(): String = stringResource(
    when (this) {
        CouchToFiveKDecision.FirstSession -> R.string.couch_decision_first
        CouchToFiveKDecision.ChosenStartWeek -> R.string.couch_decision_chosen
        CouchToFiveKDecision.Advance -> R.string.couch_decision_advance
        CouchToFiveKDecision.RepeatHardEffort -> R.string.couch_decision_repeat_hard
        CouchToFiveKDecision.RepeatManualReduction -> R.string.couch_decision_repeat_manual
        CouchToFiveKDecision.StepBack -> R.string.couch_decision_step_back
        CouchToFiveKDecision.PlanCompleted -> R.string.couch_decision_completed
    },
)

@Composable
@ReadOnlyComposable
fun SessionOutcome.label(): String = stringResource(
    when (this) {
        SessionOutcome.Completed -> R.string.session_outcome_completed
        SessionOutcome.Stopped -> R.string.session_outcome_stopped
        SessionOutcome.Abandoned -> R.string.session_outcome_abandoned
    },
)

/** A value of the quantity a heart-rate block adjusts, with its unit. */
@Composable
@ReadOnlyComposable
fun adjustedValueText(adjust: HeartRateAdjust, value: Double): String =
    if (adjust == HeartRateAdjust.Speed) stringResource(R.string.value_kmh, Format.speed(value)) else stringResource(R.string.value_percent, Format.incline(value))

/**
 * What a notice of a generated program says. Notices about a block name it by its position in [plan]; the ones about
 * Couch to 5K read how far the plan went back from its [couch] selection.
 */
@Composable
@ReadOnlyComposable
fun GeneratorNotice.text(plan: ResolvedPlan?, couch: CouchToFiveKSelection?): String {
    val block = blockKey?.let { key -> plan?.blocks?.firstOrNull { it.positionKey == key } }
    return when (code) {
        GeneratorNoticeCode.EstimatedValues -> stringResource(R.string.notice_estimated_values)
        GeneratorNoticeCode.BodyDataAssumed -> stringResource(R.string.notice_body_data)
        GeneratorNoticeCode.StartedFromHistory -> {
            val adjust = block?.heartRate?.adjust ?: HeartRateAdjust.Speed
            val shown = adjustedValueText(adjust, value ?: 0.0)
            if (block != null) stringResource(R.string.notice_started_from_history_block, block.index + 1, shown)
            else stringResource(R.string.notice_started_from_history, shown)
        }
        GeneratorNoticeCode.WalkingTargetCapped ->
            if (block != null) stringResource(R.string.notice_walking_capped_block, block.index + 1) else stringResource(R.string.notice_walking_capped)
        GeneratorNoticeCode.RestingHeartRateMissing -> stringResource(R.string.notice_resting_missing)
        GeneratorNoticeCode.RecentSession -> stringResource(R.string.notice_recent_session, Math.round(value ?: 0.0).toInt())
        GeneratorNoticeCode.LongBreak -> {
            val weeks = couch?.weeksBack ?: 1
            stringResource(if (weeks >= 2) R.string.notice_long_break_two else R.string.notice_long_break_one, Math.round(value ?: 0.0).toInt())
        }
        GeneratorNoticeCode.PlanCompleted -> stringResource(R.string.notice_plan_completed, CouchToFiveK.SESSIONS)
    }
}

/**
 * The name a generated program is saved with: the translated generator name, with the mode and the main input when the
 * generator has them ("Zone 2 easy run · Running · 45 min"). Couch to 5K keeps the plain name: its sessions are workouts.
 */
fun generatorProgramName(context: android.content.Context, descriptor: com.treadz.console.programs.personalised.GeneratorDescriptor, form: com.treadz.console.training.GeneratorForm): String {
    val base = context.getString(generatorNameRes(descriptor.id))
    if (descriptor.id == CouchToFiveK.ID) return base
    val inputs = descriptor.inputs
    return buildList {
        add(base)
        if (com.treadz.console.programs.personalised.GeneratorInput.Mode in inputs && descriptor.modes.size > 1) {
            add(context.getString(if (form.mode == ExerciseMode.Walk) R.string.mode_walk else R.string.mode_run))
        }
        if (com.treadz.console.programs.personalised.GeneratorInput.DurationMinutes in inputs) form.durationMinutes?.let { add(context.getString(R.string.duration_minutes, it)) }
        if (com.treadz.console.programs.personalised.GeneratorInput.Series in inputs) form.series?.let { add(context.getString(R.string.generator_series_count, it)) }
    }.joinToString(" · ")
}
