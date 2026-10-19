package com.treadz.console.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.treadz.console.R
import com.treadz.console.programs.engine.InterruptionReason
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessInstruction
import com.treadz.console.programs.fitness.FitnessRefusal
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.SteadyStateStatus
import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.PlanWarning
import com.treadz.console.programs.plan.WarningCode
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.training.EditorField
import com.treadz.console.training.EditorProblem
import com.treadz.console.training.ParameterProblem
import com.treadz.console.training.StartBlocker
import com.treadz.console.training.StartOutcome
import com.treadz.console.training.TestBlocker
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.model.ProgramLimits

// ---------------------------------------------------------------------------------------------------------------
// Programs

/**
 * Resource of the translated name of a standard program. The keys are the ones the catalogue publishes
 * (`StandardProgram.nameResourceKey`); a test checks that every standard program has its pair.
 */
fun standardNameRes(key: String): Int = when (key) {
    "hill" -> R.string.standard_program_hill_name
    "rolling-hills" -> R.string.standard_program_rolling_hills_name
    "intervals" -> R.string.standard_program_intervals_name
    "pyramid" -> R.string.standard_program_pyramid_name
    "progressive" -> R.string.standard_program_progressive_name
    "fat-burn" -> R.string.standard_program_fat_burn_name
    "incline-walk" -> R.string.standard_program_incline_walk_name
    "glutes" -> R.string.standard_program_glutes_name
    else -> R.string.program_unknown_name
}

fun standardDescriptionRes(key: String): Int = when (key) {
    "hill" -> R.string.standard_program_hill_description
    "rolling-hills" -> R.string.standard_program_rolling_hills_description
    "intervals" -> R.string.standard_program_intervals_description
    "pyramid" -> R.string.standard_program_pyramid_description
    "progressive" -> R.string.standard_program_progressive_description
    "fat-burn" -> R.string.standard_program_fat_burn_description
    "incline-walk" -> R.string.standard_program_incline_walk_description
    "glutes" -> R.string.standard_program_glutes_description
    else -> R.string.program_unknown_name
}

/** The name to show for a program: translated for the standard ones, as written for the user's. */
@Composable
@ReadOnlyComposable
fun programDisplayName(programId: String?, name: String?): String {
    val standard = programId?.let { StandardPrograms.byId(it) }
    return when {
        standard != null -> stringResource(standardNameRes(standard.key))
        !name.isNullOrBlank() -> name
        else -> stringResource(R.string.program_unknown_name)
    }
}

@Composable
@ReadOnlyComposable
fun BlockPhase.label(): String = stringResource(
    when (this) {
        BlockPhase.WarmUp -> R.string.phase_warm_up
        BlockPhase.Work -> R.string.phase_work
        BlockPhase.Recovery -> R.string.phase_recovery
        BlockPhase.CoolDown -> R.string.phase_cool_down
        BlockPhase.Other -> R.string.phase_other
    },
)

@Composable
@ReadOnlyComposable
fun ProgramKind.label(): String = stringResource(
    when (this) {
        ProgramKind.Standard -> R.string.program_kind_standard
        ProgramKind.Free -> R.string.program_kind_free
        ProgramKind.Personalised -> R.string.program_kind_personalised
    },
)

/** Warning of a resolved plan, translated by its code. */
@Composable
@ReadOnlyComposable
fun PlanWarning.text(): String {
    val block = (blockIndex ?: 0) + 1
    return when (code) {
        WarningCode.ShortBlock -> stringResource(R.string.warning_short_block, block, (requested ?: 0.0).toInt())
        WarningCode.SpeedClamped -> stringResource(R.string.warning_speed_clamped, block, Format.speed(requested ?: 0.0), Format.speed(applied ?: 0.0))
        WarningCode.InclineClamped -> stringResource(R.string.warning_incline_clamped, block, Format.incline(requested ?: 0.0), Format.incline(applied ?: 0.0))
        WarningCode.HeartRateControlUnavailable -> stringResource(R.string.warning_heart_rate_control, block)
    }
}

@Composable
@ReadOnlyComposable
fun Refusal.text(): String = stringResource(
    when (this) {
        Refusal.AlreadyActive -> R.string.refusal_already_active
        Refusal.NotActive -> R.string.refusal_not_active
        Refusal.WrongState -> R.string.refusal_wrong_state
        Refusal.NotConnected -> R.string.refusal_not_connected
        Refusal.SafetyKeyRemoved -> R.string.refusal_safety_key
        Refusal.BeltBusy -> R.string.refusal_belt_busy
        Refusal.InvalidPlan -> R.string.refusal_invalid_plan
        Refusal.DataNotCurrent -> R.string.refusal_data_not_current
        Refusal.InvalidValue -> R.string.refusal_invalid_value
    },
)

@Composable
@ReadOnlyComposable
fun InterruptionReason.text(): String = stringResource(
    when (this) {
        InterruptionReason.SafetyKeyRemoved -> R.string.interruption_safety_key
        InterruptionReason.ConnectionLost -> R.string.interruption_connection_lost
        InterruptionReason.BeltChangedExternally -> R.string.interruption_belt_changed
        InterruptionReason.CommandNotExecuted -> R.string.interruption_command_not_executed
        InterruptionReason.CommandFailed -> R.string.interruption_command_failed
        InterruptionReason.DataStalled -> R.string.interruption_data_stalled
    },
)

@Composable
@ReadOnlyComposable
fun RunOutcome.text(): String = stringResource(
    when (this) {
        RunOutcome.Completed -> R.string.outcome_completed
        RunOutcome.StoppedByUser -> R.string.outcome_stopped
        RunOutcome.Abandoned -> R.string.outcome_abandoned
    },
)

@Composable
@ReadOnlyComposable
fun RunState.label(): String = stringResource(
    when (this) {
        RunState.Idle -> R.string.run_state_idle
        RunState.Starting -> R.string.run_state_starting
        RunState.Running -> R.string.run_state_running
        RunState.Paused -> R.string.run_state_paused
        RunState.Interrupted -> R.string.run_state_interrupted
        RunState.Stopping -> R.string.run_state_stopping
        RunState.Finished -> R.string.run_state_finished
    },
)

/** What the user sees when a start did not work. */
@Composable
fun StartOutcome.text(): String = when (this) {
    StartOutcome.Started -> ""
    StartOutcome.Busy -> stringResource(R.string.start_outcome_busy)
    is StartOutcome.ProgramRefused -> stringResource(R.string.start_program_refused, refusal.text())
    is StartOutcome.TestRefused -> refusal.text()
    is StartOutcome.CommandFailed -> when (val r = result) {
        com.treadz.console.treadmill.CommandResult.Success -> ""
        com.treadz.console.treadmill.CommandResult.NotConnected -> stringResource(R.string.start_failed_not_connected)
        is com.treadz.console.treadmill.CommandResult.Failure -> stringResource(R.string.start_failed, failureText(r.code))
    }
}

@Composable
@ReadOnlyComposable
fun StartBlocker.text(): String = stringResource(
    when (this) {
        StartBlocker.NoUser -> R.string.start_hint_no_user
        StartBlocker.NotConnected -> R.string.start_hint_disconnected
        StartBlocker.BeltNotReady -> R.string.program_blocker_belt
        StartBlocker.SessionActive -> R.string.program_blocker_session
        StartBlocker.InvalidValues -> R.string.program_blocker_values
    },
)

@Composable
@ReadOnlyComposable
fun ParameterProblem.text(parameter: IntensityParameter, speedMin: Double, speedMax: Double, inclineMax: Double): String = when (this) {
    ParameterProblem.Blank -> stringResource(R.string.err_required)
    ParameterProblem.NotANumber -> stringResource(R.string.err_not_a_number)
    ParameterProblem.OutOfRange -> when (parameter) {
        IntensityParameter.MaxIncline -> stringResource(R.string.err_incline_range, 0, inclineMax.toInt())
        else -> stringResource(R.string.err_speed_range, Format.speed(speedMin), Format.speed(speedMax))
    }
    ParameterProblem.EasyAboveMax -> stringResource(R.string.err_easy_above_max)
}

// ---------------------------------------------------------------------------------------------------------------
// Editor

/** Text of a problem of the program editor, next to its field. The limits come from [ProgramLimits]. */
@Composable
@ReadOnlyComposable
fun EditorProblem.text(field: EditorField, adjust: com.treadz.console.programs.model.HeartRateAdjust? = null): String = when (this) {
    EditorProblem.Required -> stringResource(R.string.err_required)
    EditorProblem.NotANumber -> stringResource(if (field == EditorField.Duration) R.string.err_duration_format else R.string.err_not_a_number)
    EditorProblem.NotWhole -> stringResource(R.string.err_whole_number)
    EditorProblem.Empty -> stringResource(if (field == EditorField.RepeatBlocks) R.string.err_repeat_empty else R.string.err_program_empty)
    EditorProblem.TooMany -> stringResource(
        if (field == EditorField.RepeatBlocks) R.string.err_repeat_too_many else R.string.err_program_too_many,
        if (field == EditorField.RepeatBlocks) ProgramLimits.MAX_BLOCKS_PER_REPEAT else ProgramLimits.MAX_STEPS,
    )
    EditorProblem.TooLong -> stringResource(
        R.string.err_too_long,
        when (field) {
            EditorField.Name -> ProgramLimits.MAX_NAME_LENGTH
            EditorField.Description -> ProgramLimits.MAX_DESCRIPTION_LENGTH
            else -> ProgramLimits.MAX_BLOCK_NAME_LENGTH
        },
    )
    EditorProblem.ZoneOrder -> stringResource(R.string.err_zone_order)
    EditorProblem.MinAboveMax -> stringResource(R.string.err_min_above_max)
    EditorProblem.StartOutsideBounds -> stringResource(R.string.err_start_outside_bounds)
    EditorProblem.FirstBlockKeeps -> stringResource(R.string.err_first_block_keeps)
    EditorProblem.OutOfRange -> when (field) {
        EditorField.ZoneLow -> stringResource(R.string.err_percent_range, 0, 99)
        EditorField.ZoneHigh -> stringResource(R.string.err_percent_range, 1, 100)
        EditorField.HeartRateMin, EditorField.HeartRateMax ->
            if (adjust == com.treadz.console.programs.model.HeartRateAdjust.Incline) {
                stringResource(R.string.err_incline_range, ProgramLimits.MIN_INCLINE_PERCENT.toInt(), ProgramLimits.MAX_INCLINE_PERCENT.toInt())
            } else {
                stringResource(R.string.err_speed_range, Format.speed(ProgramLimits.MIN_SPEED_KMH), Format.speed(ProgramLimits.MAX_SPEED_KMH))
            }
        EditorField.Speed -> stringResource(R.string.err_speed_range, Format.speed(ProgramLimits.MIN_SPEED_KMH), Format.speed(ProgramLimits.MAX_SPEED_KMH))
        EditorField.Incline -> stringResource(R.string.err_incline_range, ProgramLimits.MIN_INCLINE_PERCENT.toInt(), ProgramLimits.MAX_INCLINE_PERCENT.toInt())
        EditorField.Duration -> stringResource(R.string.err_duration_range, ProgramLimits.MAX_DURATION_SECONDS / 3_600)
        EditorField.Distance -> stringResource(R.string.err_distance_range, ProgramLimits.MIN_DISTANCE_METERS, ProgramLimits.MAX_DISTANCE_METERS)
        EditorField.RepeatCount -> stringResource(R.string.err_repeat_count_range, ProgramLimits.MIN_REPEAT_COUNT, ProgramLimits.MAX_REPEAT_COUNT)
        else -> stringResource(R.string.err_program_invalid)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Fitness tests

@Composable
@ReadOnlyComposable
fun FitnessTestType.label(): String = stringResource(
    when (this) {
        FitnessTestType.EbbelingWalk -> R.string.fitness_test_ebbeling
        FitnessTestType.MaxIncrementalRun -> R.string.fitness_test_max
        FitnessTestType.MaxHeartRateWalk -> R.string.fitness_test_max_hr
    },
)

@Composable
@ReadOnlyComposable
fun FitnessTestType.summary(): String = stringResource(
    when (this) {
        FitnessTestType.EbbelingWalk -> R.string.fitness_test_ebbeling_summary
        FitnessTestType.MaxIncrementalRun -> R.string.fitness_test_max_summary
        FitnessTestType.MaxHeartRateWalk -> R.string.fitness_test_max_hr_summary
    },
)

@Composable
@ReadOnlyComposable
fun FitnessTestPhase.label(): String = stringResource(
    when (this) {
        FitnessTestPhase.Idle -> R.string.test_phase_idle
        FitnessTestPhase.Starting -> R.string.test_phase_starting
        FitnessTestPhase.Stage1 -> R.string.test_phase_stage1
        FitnessTestPhase.Stage2 -> R.string.test_phase_stage2
        FitnessTestPhase.WarmUp -> R.string.test_phase_warm_up
        FitnessTestPhase.Incremental -> R.string.test_phase_incremental
        FitnessTestPhase.CoolDown -> R.string.test_phase_cool_down
        FitnessTestPhase.Stopping -> R.string.test_phase_stopping
        FitnessTestPhase.Finished -> R.string.test_phase_finished
    },
)

@Composable
@ReadOnlyComposable
fun FitnessInstruction.text(): String = stringResource(
    when (this) {
        FitnessInstruction.BeltStarting -> R.string.instruction_belt_starting
        FitnessInstruction.WalkSpeedMayChange -> R.string.instruction_walk_may_change
        FitnessInstruction.WalkSpeedFixed -> R.string.instruction_walk_fixed
        FitnessInstruction.WalkUphill -> R.string.instruction_walk_uphill
        FitnessInstruction.WalkUphillExtended -> R.string.instruction_walk_uphill_extended
        FitnessInstruction.WarmUpWalk -> R.string.instruction_warm_up_walk
        FitnessInstruction.WarmUpJog -> R.string.instruction_warm_up_jog
        FitnessInstruction.RunUntilExhausted -> R.string.instruction_run_until_exhausted
        FitnessInstruction.WalkUphillUntilExhausted -> R.string.instruction_walk_uphill_until_exhausted
        FitnessInstruction.HoldTopSpeed -> R.string.instruction_hold_top_speed
        FitnessInstruction.CoolDownWalk -> R.string.instruction_cool_down_walk
        FitnessInstruction.Stopping -> R.string.instruction_stopping
        FitnessInstruction.Finished -> R.string.instruction_finished
    },
)

@Composable
@ReadOnlyComposable
fun SteadyStateStatus.label(): String = stringResource(
    when (this) {
        SteadyStateStatus.Pending -> R.string.steady_pending
        SteadyStateStatus.Steady -> R.string.steady_steady
        SteadyStateStatus.Extended -> R.string.steady_extended
        SteadyStateStatus.Failed -> R.string.steady_failed
    },
)

@Composable
@ReadOnlyComposable
fun FitnessRefusal.text(): String = stringResource(
    when (this) {
        FitnessRefusal.AlreadyActive -> R.string.test_refusal_already_active
        FitnessRefusal.NotActive -> R.string.test_refusal_not_active
        FitnessRefusal.WrongState -> R.string.refusal_wrong_state
        FitnessRefusal.NotConnected -> R.string.refusal_not_connected
        FitnessRefusal.SafetyKeyRemoved -> R.string.refusal_safety_key
        FitnessRefusal.BeltBusy -> R.string.refusal_belt_busy
        FitnessRefusal.DataNotCurrent -> R.string.refusal_data_not_current
        FitnessRefusal.NoHeartRate -> R.string.test_refusal_no_heart_rate
        FitnessRefusal.InvalidSubject -> R.string.test_refusal_invalid_subject
        FitnessRefusal.LimitsTooLow -> R.string.test_refusal_limits_too_low
    },
)

@Composable
@ReadOnlyComposable
fun TestBlocker.text(): String = stringResource(
    when (this) {
        TestBlocker.NoUser -> R.string.start_hint_no_user
        TestBlocker.NotConnected -> R.string.start_hint_disconnected
        TestBlocker.BeltNotAtRest -> R.string.test_blocker_belt
        TestBlocker.SessionActive -> R.string.program_blocker_session
        TestBlocker.NoHeartRate -> R.string.test_refusal_no_heart_rate
        TestBlocker.InvalidSpeed -> R.string.test_blocker_speed
        TestBlocker.InvalidIncline -> R.string.test_blocker_incline
    },
)

@Composable
@ReadOnlyComposable
fun FitnessTestFlag.text(): String = stringResource(
    when (this) {
        FitnessTestFlag.OutsideValidatedPopulation -> R.string.flag_outside_population
        FitnessTestFlag.StartSpeedClamped -> R.string.flag_start_speed_clamped
        FitnessTestFlag.EndedByUser -> R.string.flag_ended_by_user
        FitnessTestFlag.StoppedByUser -> R.string.flag_stopped_by_user
        FitnessTestFlag.ManualChange -> R.string.flag_manual_change
        FitnessTestFlag.Interrupted -> R.string.flag_interrupted
        FitnessTestFlag.CoolDownIncomplete -> R.string.flag_cool_down_incomplete
        FitnessTestFlag.SpeedAdjustedToHeartRateBand -> R.string.flag_speed_adjusted_auto
        FitnessTestFlag.SpeedAdjustedByUser -> R.string.flag_speed_adjusted_user
        FitnessTestFlag.HeartRateBelowBand -> R.string.flag_hr_below_band
        FitnessTestFlag.HeartRateAboveBand -> R.string.flag_hr_above_band
        FitnessTestFlag.SteadyStateExtended -> R.string.flag_steady_extended
        FitnessTestFlag.NoSteadyState -> R.string.flag_no_steady_state
        FitnessTestFlag.HeartRateNotResponsive -> R.string.flag_hr_not_responsive
        FitnessTestFlag.HeartRateFlat -> R.string.flag_hr_flat
        FitnessTestFlag.HeartRateLost -> R.string.flag_hr_lost
        FitnessTestFlag.HeartRateAboveSafetyLimit -> R.string.flag_hr_above_limit
        FitnessTestFlag.SpeedCapReached -> R.string.flag_speed_cap
        FitnessTestFlag.IncrementalPhaseTooShort -> R.string.flag_incremental_too_short
        FitnessTestFlag.ShortIncrementalPhase -> R.string.flag_incremental_short
        FitnessTestFlag.PeakHeartRateLow -> R.string.flag_peak_hr_low
        FitnessTestFlag.MaximalByHeartRatePlateau -> R.string.flag_maximal_by_plateau
        FitnessTestFlag.EndedWithStop -> R.string.flag_ended_with_stop
        FitnessTestFlag.NoValidHeartRate -> R.string.flag_no_valid_hr
        FitnessTestFlag.HeartRateUnreliable -> R.string.flag_hr_unreliable
    },
)

@Composable
@ReadOnlyComposable
fun FitnessEndReason.text(): String = stringResource(
    when (this) {
        FitnessEndReason.Completed -> R.string.end_reason_completed
        FitnessEndReason.Exhausted -> R.string.end_reason_exhausted
        FitnessEndReason.EndedByUser -> R.string.end_reason_ended_by_user
        FitnessEndReason.StoppedByUser -> R.string.end_reason_stopped_by_user
        FitnessEndReason.HeartRateAboveLimit -> R.string.end_reason_hr_above_limit
        FitnessEndReason.HeartRateLost -> R.string.end_reason_hr_lost
        FitnessEndReason.HeartRateBandUnreachable -> R.string.end_reason_band_unreachable
        FitnessEndReason.NoSteadyState -> R.string.end_reason_no_steady_state
        FitnessEndReason.ManualChange -> R.string.end_reason_manual_change
        FitnessEndReason.SafetyKeyRemoved -> R.string.end_reason_safety_key
        FitnessEndReason.ConnectionLost -> R.string.end_reason_connection_lost
        FitnessEndReason.BeltStoppedExternally -> R.string.end_reason_belt_stopped
        FitnessEndReason.CommandNotExecuted -> R.string.end_reason_command_not_executed
        FitnessEndReason.CommandFailed -> R.string.end_reason_command_failed
        FitnessEndReason.DataStalled -> R.string.end_reason_data_stalled
    },
)

@Composable
@ReadOnlyComposable
fun Vo2MaxCategory.label(): String = stringResource(
    when (this) {
        Vo2MaxCategory.Superior -> R.string.vo2_superior
        Vo2MaxCategory.Excellent -> R.string.vo2_excellent
        Vo2MaxCategory.Good -> R.string.vo2_good
        Vo2MaxCategory.Fair -> R.string.vo2_fair
        Vo2MaxCategory.Poor -> R.string.vo2_poor
    },
)

@Composable
@ReadOnlyComposable
fun EffortAssessment.label(): String = stringResource(
    when (this) {
        EffortAssessment.Maximal -> R.string.effort_maximal
        EffortAssessment.NotMaximal -> R.string.effort_not_maximal
        EffortAssessment.Unknown -> R.string.effort_unknown
    },
)

@Composable
@ReadOnlyComposable
fun HeartRateMaxOrigin.label(): String = stringResource(
    when (this) {
        HeartRateMaxOrigin.Manual -> R.string.hrmax_origin_manual
        HeartRateMaxOrigin.Measured -> R.string.hrmax_origin_measured
        HeartRateMaxOrigin.Estimated -> R.string.hrmax_origin_estimated
    },
)
