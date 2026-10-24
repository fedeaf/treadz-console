package com.treadz.console.analysis.energy

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.heartrate.StrapWindowConfig

/**
 * The constants of the fat/carbohydrate model ([SubstrateModel], [FuelTracker]); the defaults are the documented
 * method, tests may change them. The RER (respiratory exchange ratio) is the ratio of CO2 produced to O2 consumed:
 * 0.707 when only fat is burned, 1.00 when only carbohydrate is.
 *
 * **Why this shape** (an estimate, never a measurement): the person's own thresholds anchor the curve, so it follows
 * their fitness without a VO2max term; it is lowered a little by duration and, for women, by sex. *Age is not a term*:
 * the age range of the data of the adjustment model ends around 33 years, so extrapolating it to other ages would be a
 * guess; anchoring on the person's own thresholds already reflects fitness.
 *
 * @property restingRer RER at and below the resting heart rate. 0.79 puts about 70 % of the energy in fat, the top of
 *   the target fractions of Polar's published energy-source model at light intensity (Polar, Energy sources white
 *   paper, 2020: about 60-70 % fat).
 * @property vt1Rer RER at the first ventilatory threshold (VT1). 0.88 puts about 40 % of the energy in fat there, in the
 *   40-50 % that Polar's white paper gives at the aerobic threshold. Maximal fat oxidation occurs at or just below VT1
 *   (Achten J, Gleeson M, Jeukendrup AE. Determination of the exercise intensity that elicits maximal fat oxidation.
 *   Med Sci Sports Exerc 2002;34:92-97; Achten J, Jeukendrup AE. Maximal fat oxidation during exercise in trained men.
 *   Int J Sports Med 2003;24:603-608; Jeukendrup AE, Wallis GA. Measurement of substrate oxidation during exercise by
 *   means of gas exchange measurements. Int J Sports Med 2005;26 Suppl 1:S28-S37).
 * @property vt2Rer RER at the second threshold (VT2): fat is down to about 10-15 % of the energy (Polar white paper).
 * @property maxRer RER at the maximum heart rate and above (carbohydrate only).
 * @property rerPerMovingMinute RER lost for each minute of moving time since the start of the workout: fat oxidation
 *   rises with the duration of prolonged exercise as glycogen falls (Rothschild JA, Kilding AE, Stewart T, Plews DJ.
 *   Factors influencing substrate oxidation during submaximal cycling: a modelling analysis. Sports Med
 *   2022;52:2775-2795; the duration term of its model II, 0.0004 per minute).
 * @property durationCapMinutes The duration term stops growing after this many minutes, so that it is not extrapolated
 *   to very long efforts (the longest programme is 90 min).
 * @property femaleRerOffset RER subtracted for women: women oxidise more fat at the same relative intensity (same
 *   source, which adds +0.0328 for men; the difference between the sexes is about 0.03).
 * @property minRer Lower clamp of the RER, and the RER of pure fat oxidation: 0.707 (Lusk's table of the non-protein
 *   respiratory quotient).
 * @property maxRerClamp Upper clamp of the RER, and the RER of pure carbohydrate oxidation: 1.00.
 * @property fatKcalPerLitreOxygen Energy per litre of oxygen when only fat is burned (Lusk's table, RQ 0.707: 4.686
 *   kcal/L).
 * @property carbKcalPerLitreOxygen Energy per litre of oxygen when only carbohydrate is burned (Lusk's table, RQ 1.00:
 *   5.047 kcal/L). Protein is ignored (small and not measurable here).
 * @property windowSeconds Length of the windows in which the strap readings are checked against the strap's own RR
 *   intervals ([com.treadz.console.analysis.heartrate.StrapWindowJudge]); consecutive from the first sample.
 * @property smoothingSeconds The heart rate the model uses is the mean of the valid seconds in this many last seconds
 *   (the RER follows the heart rate with a lag of its own and a single reading is noisy).
 * @property judgeContextSeconds RR history kept before a window when it is judged, as context for the artifact
 *   correction.
 * @property strapWindow Thresholds of the strap check.
 */
data class FuelRules(
    val restingRer: Double = 0.79,
    val vt1Rer: Double = 0.88,
    val vt2Rer: Double = 0.97,
    val maxRer: Double = 1.00,
    val rerPerMovingMinute: Double = 0.0004,
    val durationCapMinutes: Double = 120.0,
    val femaleRerOffset: Double = 0.03,
    val minRer: Double = 0.707,
    val maxRerClamp: Double = 1.00,
    val fatKcalPerLitreOxygen: Double = 4.686,
    val carbKcalPerLitreOxygen: Double = 5.047,
    val windowSeconds: Int = 10,
    val smoothingSeconds: Int = 30,
    val judgeContextSeconds: Int = 30,
    val strapWindow: StrapWindowConfig = StrapWindowConfig(),
)

/**
 * The heart-rate anchors of the person's RER curve, as of the workout's date (so the stored analysis explains itself).
 * They must be strictly increasing for a split to exist: [restingBpm] < [vt1Bpm] < [vt2Bpm] < [maxBpm].
 *
 * @property restingBpm Resting heart rate; [restingAssumed] = it is not the person's but the one assumed for the zones
 *   (`HeartRateBands.ASSUMED_RESTING_FRACTION` of the maximum).
 * @property vt1Bpm First threshold (measured); [vt2Bpm] the second one, [vt2FromTable] = it was not measured but taken
 *   from the zone table of the person's level.
 * @property maxBpm The effective maximum heart rate.
 */
data class FuelAnchors(
    val restingBpm: Int,
    val restingAssumed: Boolean,
    val vt1Bpm: Int,
    val vt2Bpm: Int,
    val vt2FromTable: Boolean,
    val maxBpm: Int,
    val sex: Sex,
) {
    /** True when [restingBpm] < [vt1Bpm] < [vt2Bpm] < [maxBpm]. */
    val increasing: Boolean get() = restingBpm < vt1Bpm && vt1Bpm < vt2Bpm && vt2Bpm < maxBpm
}
