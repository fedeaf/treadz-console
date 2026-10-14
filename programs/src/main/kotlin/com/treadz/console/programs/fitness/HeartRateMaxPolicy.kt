package com.treadz.console.programs.fitness

import kotlin.math.abs

/** Where the effective maximum heart rate comes from. */
enum class HeartRateMaxOrigin {
    /** The user entered it in the profile. */
    Manual,

    /** Measured by a maximal fitness test, running or walking ([FitnessProfile.hrMax]). */
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
 * A heart-rate peak seen in a workout that is clearly higher than the maximum heart rate in use, see
 * [HeartRateMaxPolicy.observedPeakOffer].
 *
 * @property peakBpm The workout's highest 5-s mean of plausible readings.
 * @property currentBpm The effective maximum it beats.
 * @property workoutId The workout it was seen in, when known.
 */
data class ObservedPeakOffer(
    val peakBpm: Int,
    val currentBpm: Int,
    val workoutId: Long?,
    val peakAtMillis: Long,
) {
    val differenceBpm: Int get() = peakBpm - currentBpm
}

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

    /** An observed peak must be above the effective maximum by more than this many bpm to be offered. */
    const val OBSERVED_PEAK_MARGIN_BPM = 3

    /**
     * Whether to offer a heart-rate peak observed in a workout as the new maximum heart rate. A real maximum is a
     * ceiling: a plausible heart rate above it means the maximum in use is too low (typically the age estimate, which is
     * often 10-15 bpm off). The UI asks "use X as your maximum heart rate?". **Yes**: store X as the manual maximum
     * (with its date), so it wins and only a higher peak asks again. **No**: remember when the user answered
     * ([answeredAtMillis]) so the same peak is not offered again.
     *
     * The offer is made when the peak is more than [OBSERVED_PEAK_MARGIN_BPM] above [decision]'s value and newer than
     * both the user's last answer and, for a manual maximum, the moment it was entered or confirmed (the user set it
     * knowing that peak). Readings above the maximum + 15 bpm are rejected as impossible while recording, so a maximum
     * that is far too low rises in steps of at most about 15 bpm.
     *
     * @param decision The effective maximum ([resolve]).
     * @param peakBpm The highest observed peak of the last 365 days, `null` without one.
     * @param manualSetAtMillis When the manual maximum was entered or last confirmed (epoch ms), `null` if unknown.
     * @param answeredAtMillis When the user last declined an observed peak (epoch ms), `null` if never.
     */
    fun observedPeakOffer(
        decision: HeartRateMaxDecision,
        peakBpm: Int?,
        peakAtMillis: Long?,
        workoutId: Long? = null,
        manualSetAtMillis: Long? = null,
        answeredAtMillis: Long? = null,
    ): ObservedPeakOffer? {
        if (peakBpm == null || peakAtMillis == null) return null
        if (peakBpm <= decision.bpm + OBSERVED_PEAK_MARGIN_BPM) return null
        if (answeredAtMillis != null && peakAtMillis <= answeredAtMillis) return null
        if (decision.origin == HeartRateMaxOrigin.Manual && manualSetAtMillis != null && peakAtMillis <= manualSetAtMillis) return null
        return ObservedPeakOffer(peakBpm, decision.bpm, workoutId, peakAtMillis)
    }

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
