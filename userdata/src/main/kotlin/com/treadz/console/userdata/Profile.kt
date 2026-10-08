package com.treadz.console.userdata

import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

/**
 * Biological sex, used by calorie and heart-rate formulas. The enum names are stable codes: they are stored
 * and must never be renamed; the UI translates them.
 */
enum class Sex { Male, Female }

/** Self-declared fitness level. Stable codes, see [Sex]. */
enum class FitnessLevel { Beginner, Intermediate, Advanced }

/** What the user wants to achieve. Stable codes, see [Sex]. */
enum class Goal { LoseWeight, MaintainWeight, ImproveFitness, BuildEndurance }

/**
 * A local user profile. The current weight is not part of it: it is the latest entry of the weight log
 * ([WeightRepository.latestWeight]).
 *
 * Age is derived from [birthDate], so the functions that depend on it take the reference date explicitly
 * (the UI passes today's date) and stay pure.
 */
data class UserProfile(
    val id: Long,
    val name: String,
    val sex: Sex,
    val heightCm: Int,
    val birthDate: LocalDate,
    val restingHeartRateBpm: Int? = null,
    /** Maximum heart rate set by the user (e.g. measured). `null` means "use the estimate". */
    val maxHeartRateBpm: Int? = null,
    val fitnessLevel: FitnessLevel? = null,
    val goal: Goal? = null,
    val createdAtMillis: Long = 0,
    /**
     * `true` for the generic profile created automatically (see [GenericProfile]): it has an empty [name] and
     * placeholder data, so the treadmill can be used right away. The UI shows a translated label (e.g. "User")
     * instead of the empty name and offers to personalise it. It stops being generic when it gets a name.
     */
    val isGeneric: Boolean = false,
    /**
     * When [maxHeartRateBpm] was entered or last confirmed by the user (epoch ms); `null` when there is no manual value
     * or when it predates this field (an existing value without a time counts as **older** than any test, see
     * `HeartRateMaxPolicy`). The repository sets it: to "now" when a manual value is set or changed, cleared with the
     * value, and refreshed by [FitnessTestRepository.keepManualHeartRateMax]. Synchronised with the profile.
     */
    val maxHeartRateSetAtMillis: Long? = null,
) {
    /** Age in whole years on [date] (never negative). */
    fun ageOn(date: LocalDate): Int = Period.between(birthDate, date).years.coerceAtLeast(0)

    /** Maximum heart rate estimated from the age on [date], see [HeartRateEstimates.maxFromAge]. */
    fun estimatedMaxHeartRateBpm(date: LocalDate): Int = HeartRateEstimates.maxFromAge(ageOn(date))

    /**
     * The manual maximum heart rate on [date], or else the age-based estimate.
     *
     * **Superseded.** It knows nothing about the maximum heart rate measured by the fitness tests. From milestone 5
     * on, the value to use for zones, safety limits and tests is the decision of the policy:
     * [FitnessTestRepository.heartRateMax] (manual, else measured, else estimated; with the question for the user when
     * a newer test disagrees with the manual value). This function keeps working as before for existing callers.
     */
    fun effectiveMaxHeartRateBpm(date: LocalDate): Int = maxHeartRateBpm ?: estimatedMaxHeartRateBpm(date)

    /** `true` when [effectiveMaxHeartRateBpm] is an estimate (the user did not set a maximum heart rate). */
    val isMaxHeartRateEstimated: Boolean get() = maxHeartRateBpm == null
}

/** Editable profile data, used to create and to edit a profile. */
data class ProfileInput(
    val name: String,
    val sex: Sex,
    val heightCm: Int,
    val birthDate: LocalDate,
    val restingHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val fitnessLevel: FitnessLevel? = null,
    val goal: Goal? = null,
)

/**
 * The generic default profile. A fresh database (first install) contains exactly this one profile, active, so the
 * treadmill can be used before anybody sets up a profile; it is also recreated when the last profile is deleted.
 *
 * It has an empty name, [Sex.Male], [HEIGHT_CM] and a birth date [AGE_YEARS] years before today; its only weight
 * entry is a placeholder of [WEIGHT_KG] ([WeightEntry.isPlaceholder]). Everything else is `null`.
 * It is personalised with [ProfileRepository.personalize], which also replaces the placeholder weight.
 */
object GenericProfile {
    const val HEIGHT_CM = 180
    const val AGE_YEARS = 40
    const val WEIGHT_KG = 80.0

    /** The profile data of the generic profile as of [today]. */
    fun input(today: LocalDate) = ProfileInput(
        name = "",
        sex = Sex.Male,
        heightCm = HEIGHT_CM,
        birthDate = today.minusYears(AGE_YEARS.toLong()),
    )
}

object HeartRateEstimates {
    /**
     * Maximum heart rate estimated from the age with the Tanaka formula: `208 - 0.7 * age`, rounded to
     * the nearest bpm (age 40 gives 180). It is more accurate than the classic `220 - age` for adults, whose
     * error grows with age. It is only an estimate: the user can enter a measured value.
     */
    fun maxFromAge(ageYears: Int): Int = (208.0 - 0.7 * ageYears).roundToInt()
}
