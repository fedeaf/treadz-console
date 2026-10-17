package com.treadz.console.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.treadz.console.R
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserDataLimits
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.ValidationCode
import com.treadz.console.userdata.WorkoutEndReason

/** Name to show for a profile: the generic profile has an empty name and gets a translated label. */
@Composable
@ReadOnlyComposable
fun UserProfile.displayName(): String = if (isGeneric || name.isBlank()) stringResource(R.string.generic_user_label) else name

@Composable
@ReadOnlyComposable
fun Sex.label(): String = stringResource(
    when (this) {
        Sex.Male -> R.string.sex_male
        Sex.Female -> R.string.sex_female
    },
)

@Composable
@ReadOnlyComposable
fun FitnessLevel.label(): String = stringResource(
    when (this) {
        FitnessLevel.Beginner -> R.string.fitness_beginner
        FitnessLevel.Intermediate -> R.string.fitness_intermediate
        FitnessLevel.Advanced -> R.string.fitness_advanced
    },
)

/** The line under a fitness level that says what it means. */
@Composable
@ReadOnlyComposable
fun FitnessLevel.help(): String = stringResource(
    when (this) {
        FitnessLevel.Beginner -> R.string.fitness_beginner_help
        FitnessLevel.Intermediate -> R.string.fitness_intermediate_help
        FitnessLevel.Advanced -> R.string.fitness_advanced_help
    },
)

@Composable
@ReadOnlyComposable
fun Goal.label(): String = stringResource(
    when (this) {
        Goal.LoseWeight -> R.string.goal_lose_weight
        Goal.MaintainWeight -> R.string.goal_maintain_weight
        Goal.ImproveFitness -> R.string.goal_improve_fitness
        Goal.BuildEndurance -> R.string.goal_build_endurance
    },
)

@Composable
@ReadOnlyComposable
fun WorkoutEndReason.label(): String = stringResource(
    when (this) {
        WorkoutEndReason.Stopped -> R.string.end_stopped
        WorkoutEndReason.SafetyKeyRemoved -> R.string.end_key_removed
        WorkoutEndReason.ConnectionLost -> R.string.end_connection_lost
        WorkoutEndReason.Disconnected -> R.string.end_disconnected
        WorkoutEndReason.Closed -> R.string.end_closed
        WorkoutEndReason.Interrupted -> R.string.end_interrupted
    },
)

/**
 * Text for a validation problem, shown next to its field. The ranges come from [UserDataLimits], so the message
 * always matches the rule. Raw codes are never shown.
 */
@Composable
@ReadOnlyComposable
fun validationText(field: InputField, code: ValidationCode): String = when (code) {
    ValidationCode.Blank -> stringResource(
        when (field) {
            InputField.Name -> R.string.err_name_blank
            InputField.Height -> R.string.err_height_blank
            InputField.BirthDate -> R.string.err_birth_blank
            InputField.Weight, InputField.TargetWeight -> R.string.err_weight_blank
            else -> R.string.err_required
        },
    )
    ValidationCode.TooLong -> stringResource(R.string.err_too_long, UserDataLimits.NAME_MAX_LENGTH)
    ValidationCode.NameTaken -> stringResource(R.string.err_name_taken)
    ValidationCode.InFuture -> stringResource(R.string.err_in_future)
    ValidationCode.RestingNotBelowMax -> stringResource(R.string.err_resting_not_below_max)
    ValidationCode.OutOfRange -> when (field) {
        InputField.Height -> stringResource(R.string.err_height_range, UserDataLimits.HEIGHT_CM.first, UserDataLimits.HEIGHT_CM.last)
        InputField.BirthDate -> stringResource(R.string.err_age_range, UserDataLimits.AGE_YEARS.first, UserDataLimits.AGE_YEARS.last)
        InputField.RestingHeartRate ->
            stringResource(R.string.err_bpm_range, UserDataLimits.RESTING_HEART_RATE_BPM.first, UserDataLimits.RESTING_HEART_RATE_BPM.last)
        InputField.MaxHeartRate ->
            stringResource(R.string.err_bpm_range, UserDataLimits.MAX_HEART_RATE_BPM.first, UserDataLimits.MAX_HEART_RATE_BPM.last)
        InputField.Weight, InputField.TargetWeight ->
            stringResource(R.string.err_weight_range, UserDataLimits.MIN_WEIGHT_KG.toInt(), UserDataLimits.MAX_WEIGHT_KG.toInt())
        InputField.WeightDate -> stringResource(R.string.err_date_invalid)
        InputField.Name -> stringResource(R.string.err_required)
    }
}

/** The language the user reads: the first of the system locales (not `Locale.getDefault()`, which may lag behind). */
@Composable
@ReadOnlyComposable
fun currentLocale(): java.util.Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
