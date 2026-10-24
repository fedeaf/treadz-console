package com.treadz.console.analysis.energy

import com.treadz.console.analysis.fitness.Sex

/**
 * The fat/carbohydrate model as pure functions (constants in [FuelRules]). An **estimate** of the share of the energy
 * that comes from fat, from the heart rate relative to the person's own thresholds:
 *
 *  1. **RER from the heart rate** `h`, anchored on resting R, VT1, VT2 and maximum M (`FuelAnchors`):
 *     `h ≤ R` → 0.79; `R < h ≤ VT1` → `0.79 + 0.09·x²` with `x = (h − R)/(VT1 − R)` (the RER hardly moves at low
 *     intensity and falls fast near VT1); `VT1 < h ≤ VT2` → linear 0.88 → 0.97; `VT2 < h ≤ M` → linear 0.97 → 1.00;
 *     `h > M` → 1.00.
 *  2. **Adjustments**: minus 0.0004 per minute of moving time since the start (capped at 120 min), minus 0.03 for women;
 *     clamped to 0.707-1.00.
 *  3. **Fat share of the energy** with Lusk's table of the non-protein respiratory quotient: the fat fraction of the
 *     oxygen `f = (1 − RER)/(1 − 0.707)` and `share = f·4.686 / (f·4.686 + (1 − f)·5.047)` (kcal per litre of oxygen
 *     of fat and of carbohydrate). The carbohydrate share is the rest; protein is ignored.
 *
 * Reference values the defaults reproduce for resting 60, VT1 131, VT2 155, maximum 185, male (Polar, Energy sources
 * white paper 2020): 30 min at 100 bpm 62 % fat (60-70 %); 60/120 min at VT1 43/47 % (40-50 %); 60/120 min at VT2
 * 14/17 % (10-20 %); 30 min at 95 % of the maximum 5 % (5-15 %).
 */
object SubstrateModel {

    /** The RER at [heartRateBpm] on the curve of the [anchors], before the duration and sex adjustments. */
    fun baseRer(heartRateBpm: Double, anchors: FuelAnchors, rules: FuelRules = FuelRules()): Double {
        val r = anchors.restingBpm.toDouble()
        val vt1 = anchors.vt1Bpm.toDouble()
        val vt2 = anchors.vt2Bpm.toDouble()
        val m = anchors.maxBpm.toDouble()
        val h = heartRateBpm
        return when {
            h <= r -> rules.restingRer
            h <= vt1 -> {
                val x = (h - r) / (vt1 - r)
                rules.restingRer + (rules.vt1Rer - rules.restingRer) * x * x
            }
            h <= vt2 -> rules.vt1Rer + (rules.vt2Rer - rules.vt1Rer) * (h - vt1) / (vt2 - vt1)
            h <= m -> rules.vt2Rer + (rules.maxRer - rules.vt2Rer) * (h - vt2) / (m - vt2)
            else -> rules.maxRer
        }
    }

    /**
     * The RER at [heartRateBpm] after [movingMinutes] of moving time since the start of the workout, for the sex of the
     * [anchors], clamped to [FuelRules.minRer]..[FuelRules.maxRerClamp]. The anchors must be [FuelAnchors.increasing].
     */
    fun rer(heartRateBpm: Double, anchors: FuelAnchors, movingMinutes: Double, rules: FuelRules = FuelRules()): Double {
        val duration = rules.rerPerMovingMinute * movingMinutes.coerceIn(0.0, rules.durationCapMinutes)
        val sex = if (anchors.sex == Sex.Female) rules.femaleRerOffset else 0.0
        return (baseRer(heartRateBpm, anchors, rules) - duration - sex).coerceIn(rules.minRer, rules.maxRerClamp)
    }

    /** Share (0-1) of the energy that comes from fat at [rer] (Lusk's table); the rest comes from carbohydrate. */
    fun fatShare(rer: Double, rules: FuelRules = FuelRules()): Double {
        val clamped = rer.coerceIn(rules.minRer, rules.maxRerClamp)
        val f = (rules.maxRerClamp - clamped) / (rules.maxRerClamp - rules.minRer)
        val fat = f * rules.fatKcalPerLitreOxygen
        val carb = (1.0 - f) * rules.carbKcalPerLitreOxygen
        return fat / (fat + carb)
    }
}
