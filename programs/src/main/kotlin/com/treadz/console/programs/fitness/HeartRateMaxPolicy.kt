package com.treadz.console.programs.fitness

import kotlin.math.abs

/** Where the effective maximum heart rate comes from. */
enum class HeartRateMaxOrigin {
    /** The user entered it in the profile. */
    Manual,

    /** Measured by a maximal fitness test ([FitnessProfile.hrMax]). */
    Measured,

    /** Estimated from the age (the profile's formula). */
    Estimated,
}

/**
 * The outcome of [HeartRateMaxPolicy.resolve].
 *
 * @property bpm The maximum heart rate to use (safety limits, heart-rate zones, the effort criterion of the tests).
 * @property origin Where [bpm] comes from.
 * @property measured The measured value that was considered (even if it was not adopted); `null` when there is none.
 * @property askUser The UI should ask the user: a manual value exists, a **newer** measured value is available and it
 *   differs from the manual one by more than [HeartRateMaxPolicy.ASK_DIFFERENCE_BPM]. The question is "use the measured
 *   X instead of your Y?". Yes: clear the manual value (the policy then returns the measured one). No: record that the
 *   manual value was confirmed now (see `manualSetAtMillis` of [HeartRateMaxPolicy.resolve]) so the question is not
 *   asked again until a newer test measures something different.
 * @property differenceBpm Measured minus manual, when both exist (also when [askUser] is false).
 */
data class HeartRateMaxDecision(
    val bpm: Int,
    val origin: HeartRateMaxOrigin,
    val measured: HrMaxEstimate?,
    val askUser: Boolean = false,
    val differenceBpm: Int? = null,
)

/**
 * Which maximum heart rate to use (a pure function, no storage).
 *
 * Priority:
 *  1. **Manual**: the value the user entered in the profile always wins. It is never replaced silently.
 *  2. **Measured**: otherwise the measured maximum of the fitness profile ([FitnessProfile.hrMax]: the highest peak of
 *     a maximal-effort test, see [FitnessProfileCalculator]) replaces the estimate. That value is already limited to
 *     tests of the last 365 days by the profile.
 *  3. **Estimated**: otherwise the age-based estimate.
 *
 * **Asking the user.** With a manual value and a measured one, [HeartRateMaxDecision.askUser] is true when the
 * measured value is newer than the manual one and differs from it by **more than [ASK_DIFFERENCE_BPM] (3 bpm)** in
 * either direction: a 5-second peak mean of a real test is accurate to a few bpm, so a smaller difference is noise and
 * not worth a question. "Newer" compares the test date with the moment the manual value was entered or last confirmed
 * (`manualSetAtMillis`); when that moment is unknown (`null`) the manual value counts as older, so the question is
 * asked for any difference. A measured value that is not newer than the manual one never triggers the question: the
 * user entered or confirmed their value knowing it.
 *
 * The caller passes the result (`bpm`) as [FitnessSubject.hrMaxBpm] when it starts a test.
 */
object HeartRateMaxPolicy {

    /** A measured value must differ from the manual one by more than this many bpm for the user to be asked. */
    const val ASK_DIFFERENCE_BPM = 3

    /**
     * @param manualBpm The value entered in the profile, or `null` when the user has not set one.
     * @param manualSetAtMillis When [manualBpm] was entered or last confirmed (epoch ms), or `null` if unknown.
     * @param measured The profile's measured maximum (`null` without a maximal-effort test).
     * @param estimatedBpm The age-based estimate (always available).
     */
    fun resolve(
        manualBpm: Int?,
        manualSetAtMillis: Long?,
        measured: HrMaxEstimate?,
        estimatedBpm: Int,
    ): HeartRateMaxDecision {
        if (manualBpm != null) {
            val difference = measured?.let { it.measuredBpm - manualBpm }
            val newer = measured != null && (manualSetAtMillis == null || measured.testedAtMillis > manualSetAtMillis)
            val ask = newer && difference != null && abs(difference) > ASK_DIFFERENCE_BPM
            return HeartRateMaxDecision(manualBpm, HeartRateMaxOrigin.Manual, measured, askUser = ask, differenceBpm = difference)
        }
        if (measured != null) return HeartRateMaxDecision(measured.measuredBpm, HeartRateMaxOrigin.Measured, measured)
        return HeartRateMaxDecision(estimatedBpm, HeartRateMaxOrigin.Estimated, null)
    }
}
