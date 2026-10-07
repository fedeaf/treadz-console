package com.treadz.console.userdata

import java.time.LocalDate

/** Plausible ranges accepted by the validation. Inclusive. */
object UserDataLimits {
    const val NAME_MAX_LENGTH = 40
    val HEIGHT_CM = 100..230
    val AGE_YEARS = 10..100
    val RESTING_HEART_RATE_BPM = 25..120
    val MAX_HEART_RATE_BPM = 100..230
    const val MIN_WEIGHT_KG = 25.0
    const val MAX_WEIGHT_KG = 280.0
    val EARLIEST_WEIGHT_DATE: LocalDate = LocalDate.of(1900, 1, 1)
}

/** Input field a [ValidationIssue] refers to. Stable codes. */
enum class InputField {
    Name, Height, BirthDate, RestingHeartRate, MaxHeartRate,
    WeightDate, Weight, TargetWeight,
}

/** Why a field was rejected. Stable codes: the UI turns them into texts. */
enum class ValidationCode {
    /** Required value missing or only spaces. */
    Blank,

    /** Longer than [UserDataLimits.NAME_MAX_LENGTH]. */
    TooLong,

    /** Another profile already has this name (compared ignoring case and surrounding spaces). */
    NameTaken,

    /** Outside the plausible range (see [UserDataLimits]) or not a finite number. */
    OutOfRange,

    /** A date later than today. */
    InFuture,

    /** The resting heart rate must be lower than the maximum heart rate. Reported on `RestingHeartRate`. */
    RestingNotBelowMax,
}

data class ValidationIssue(val field: InputField, val code: ValidationCode)

/** Result of a write operation. */
sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>

    /** The input was rejected; nothing was changed. Never empty. */
    data class Invalid(val issues: List<ValidationIssue>) : DataResult<Nothing>

    /** The profile, entry or workout does not exist (e.g. it was deleted meanwhile). */
    data object NotFound : DataResult<Nothing>

    /** The database failed; nothing was changed. [reason] is only for the log. */
    data class StorageError(val reason: String) : DataResult<Nothing>
}

/** Pure validation rules, shared by every implementation. [ValidationCode.NameTaken] is checked by the repository. */
object Validation {

    /**
     * @param allowBlankName `true` only when the profile being saved is the generic one and stays generic
     * (see [GenericProfile]); every other profile needs a name.
     */
    fun profile(input: ProfileInput, today: LocalDate, allowBlankName: Boolean = false): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val name = input.name.trim()
        if (name.isEmpty()) {
            if (!allowBlankName) issues += ValidationIssue(InputField.Name, ValidationCode.Blank)
        } else if (name.length > UserDataLimits.NAME_MAX_LENGTH) {
            issues += ValidationIssue(InputField.Name, ValidationCode.TooLong)
        }
        if (input.heightCm !in UserDataLimits.HEIGHT_CM) {
            issues += ValidationIssue(InputField.Height, ValidationCode.OutOfRange)
        }
        if (input.birthDate.isAfter(today)) {
            issues += ValidationIssue(InputField.BirthDate, ValidationCode.InFuture)
        } else {
            val age = java.time.Period.between(input.birthDate, today).years
            if (age !in UserDataLimits.AGE_YEARS) issues += ValidationIssue(InputField.BirthDate, ValidationCode.OutOfRange)
        }
        val resting = input.restingHeartRateBpm
        val max = input.maxHeartRateBpm
        if (resting != null && resting !in UserDataLimits.RESTING_HEART_RATE_BPM) {
            issues += ValidationIssue(InputField.RestingHeartRate, ValidationCode.OutOfRange)
        }
        if (max != null && max !in UserDataLimits.MAX_HEART_RATE_BPM) {
            issues += ValidationIssue(InputField.MaxHeartRate, ValidationCode.OutOfRange)
        }
        if (resting != null && max != null && resting in UserDataLimits.RESTING_HEART_RATE_BPM &&
            max in UserDataLimits.MAX_HEART_RATE_BPM && resting >= max
        ) {
            issues += ValidationIssue(InputField.RestingHeartRate, ValidationCode.RestingNotBelowMax)
        }
        return issues
    }

    fun weightEntry(date: LocalDate, weightKg: Double, today: LocalDate): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (date.isAfter(today)) {
            issues += ValidationIssue(InputField.WeightDate, ValidationCode.InFuture)
        } else if (date.isBefore(UserDataLimits.EARLIEST_WEIGHT_DATE)) {
            issues += ValidationIssue(InputField.WeightDate, ValidationCode.OutOfRange)
        }
        if (!isPlausibleWeight(weightKg)) issues += ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)
        return issues
    }

    fun targetWeight(weightKg: Double): List<ValidationIssue> =
        if (isPlausibleWeight(weightKg)) emptyList() else listOf(ValidationIssue(InputField.TargetWeight, ValidationCode.OutOfRange))

    private fun isPlausibleWeight(kg: Double) =
        kg.isFinite() && kg in UserDataLimits.MIN_WEIGHT_KG..UserDataLimits.MAX_WEIGHT_KG

    /** Name as stored and compared for uniqueness. */
    internal fun normalizedName(name: String): String = name.trim()

    internal fun nameKey(name: String): String = normalizedName(name).lowercase(java.util.Locale.ROOT)
}
