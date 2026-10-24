package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.fitness.EnergyCounter
import com.treadz.console.analysis.fitness.EnergyExpenditure
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.heartrate.HeartRateBands
import com.treadz.console.analysis.rr.RrCleaning
import com.treadz.console.analysis.samples.RrSeries
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.zones.ZoneSet
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Analysis of one workout from its recorded samples: a pure function ([analyse]) with its own [ALGORITHM_VERSION],
 * stored with the result so that it can be recalculated when the algorithm changes.
 *
 * **Which data each section needs** (a missing section carries its [AnalysisReason], never an exception):
 *  - strap with RR intervals: everything;
 *  - heart rate without RR (console, unknown or imported source): everything except the session α1;
 *  - no heart rate: only the energy.
 *
 * **Timeline** ([WorkoutTimeline]): the samples are resampled to 1 Hz by sample-and-hold, so nothing assumes a sampling
 * rate. *Assumption*: a sample holds for at most 10 s; longer silences are gaps and count for nothing. *Assumption*: the
 * sections measure exercise, so they count only the seconds with the belt **moving** (speed above 0); pauses and the
 * countdown at the start are not exercise (the ACSM equations do not describe them either). The recovery is the one
 * section that looks at the stopped belt. A heart rate outside 30-250 bpm, or frozen (a console or unknown-source value
 * unchanged for more than 5 min, *assumption*), is treated as missing. The whole-session heart-rate sections (zones, TRIMP, efficiency,
 * decoupling) need the heart rate for 80 % of the moving seconds; the local ones need it where they look (fitness index:
 * 80 % of each stretch; recovery: 80 % of the peak window and data at the marks; α1: valid windows), so a session with
 * the strap connected late still gives them.
 *
 * Sources and assumptions of each section:
 *  1. **Energy**: ACSM metabolic equations per second (walking up to 7.5 km/h, running above it; ACSM's Guidelines for
 *     Exercise Testing and Prescription, see [Acsm]); 1 L of oxygen ≈ 5 kcal. Gross kcal = Σ VO2 · weight; net kcal
 *     leaves out the resting 3.5 ml·kg⁻¹·min⁻¹ (1 MET). Independent of the treadmill's kcal.
 *  2. **Time in zones**: seconds with the belt moving and a heart rate in each zone of the zone set given.
 *  3. **TRIMP** (Banister EW. Modeling elite athletic performance. In: MacDougall JD, Wenger HA, Green HJ, eds.
 *     Physiological Testing of the High-Performance Athlete. 2nd ed. Human Kinetics; 1991:403-424): Σ Δt(min) · HRr · k ·
 *     e^(b·HRr), HRr = (HR − resting) / (maximum − resting) limited to 0-1; men k = 0.64, b = 1.92; women k = 0.86,
 *     b = 1.67. Without a resting heart rate, the one assumed for the zones ([HeartRateBands.ASSUMED_RESTING_FRACTION] of
 *     the maximum) is used and flagged.
 *  4. **Efficiency factor and decoupling** (Friel J. The Triathlete's Training Bible, Pa:HR): EF = mean VO2 / mean heart
 *     rate; decoupling = (EF₁ − EF₂) / EF₁ between the two halves of the main block. Using the computed VO2 instead of
 *     the speed makes it valid when the incline changes. *Assumption (no program information)*: the **main block** runs
 *     from the first moving second to the end of the session minus a trailing **cool-down**, detected from the load: the
 *     typical net oxygen demand is the median of the moving seconds after the first 5 min, and the cool-down is the end
 *     of the session where the net demand stays below 80 % of it. The first 5 min are left out (the heart rate is still
 *     rising), the rest must last ≥ 20 min and have 90 % of its seconds moving with a heart rate. *Assumption*: a **long
 *     steady zone 2** session is one that passed all that and has ≥ 75 % of the analysed seconds in zones 1-2 (zone
 *     set of the context); the trends use it to follow the decoupling.
 *  5. **Aerobic fitness index** (trend only, not the profile VO2max): in each stretch of steady load of ≥ 3 min (speed and
 *     incline within 0.3 km/h and 1 % of its start; *assumption*), the last 60 % gives mean VO2 and mean heart rate;
 *     VO2max ≈ 3.5 + (VO2 − 3.5) / HRr, because the % of the heart-rate reserve ≈ the % of the VO2 reserve (Swain DP,
 *     Leutholtz BC. Med Sci Sports Exerc. 1997;29(3):410-414). The median of the stretches with HRr 0.40-0.85 is the
 *     index. It assumes a steady state at the end of the stretch and a resting heart rate (assumed if missing, flagged).
 *  6. **Heart-rate recovery** (as usually measured, from the end of the exercise: Cole CR et al. N Engl J Med.
 *     1999;341(18):1351-1357): the effort is the one that reached the highest mean heart rate of 30 s; the recovery
 *     starts when its load drops, and its value at 60 and 120 s is the mean heart rate of the last 30 s of the effort
 *     minus the mean of 5 s around each mark. *Assumption*: it is only measured when the load drops between 60 s before
 *     that peak window (the heart rate lags behind the load) and 30 s after it, to ≤ 60 % of the net oxygen demand of the minute before, and stays there 15-60 s
 *     later; otherwise a steady effort would be mistaken for a recovery. The context (belt moving or stopped, mean VO2 of
 *     the recovery) lets the trends compare only equal cases.
 *  7. **Session α1** (strap RR only): windows of 120 s every 10 s ([com.treadz.console.analysis.thresholds.Alpha1Windows],
 *     valid with ≥ 100 beats and ≤ 5 % artifacts) with the belt moving during 80 % of the window; time with α1 ≥ 0.75
 *     and below it (each valid window counts its 10 s step around its centre), mean α1. α1 = 0.75 is the population cut
 *     for VT1 (Rogers B, Giles D, Draper N, Hoos O, Gronwald T. Front Physiol. 2021;11:596567; Gronwald T, Rogers B, Hoos
 *     O. Front Physiol. 2020;11:550572): the time below it is the real time below the aerobic threshold of that day.
 *  8. **Signal quality**: always present.
 *  9. **Thresholds implied by the stable stretches** ([ThresholdEstimatesSection], strap RR and a test with an α1-heart-rate
 *     slope in the context): input for the recommendation of a test, never a change of the profile.
 */
object WorkoutAnalyzer {

    /**
     * Version of this algorithm; stored with every analysis, which is recalculated when it changes. 2 (2026-10-23): the
     * section of the thresholds implied by the stable stretches ([ThresholdEstimatesSection]) was added. 3 (2026-10-24):
     * the fitness index only takes stretches up to 75 % of the heart-rate reserve (was 85 %), and the calories come from
     * [EnergyExpenditure] (Looney et al. 2019 walking up to 7 km/h, ACSM running above), as the stored ones.
     */
    const val ALGORITHM_VERSION = 3


    fun analyse(
        samples: List<WorkoutSample>,
        context: WorkoutAnalysisContext,
        rules: AnalysisRules = AnalysisRules(),
    ): WorkoutAnalysis {
        val sorted = if (samples.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis }) samples else samples.sortedBy { it.timeMillis }
        val tl = WorkoutTimeline.build(sorted, rules)
        val rr = RrSeries.fromSamples(sorted)
        val cleaned = if (rr.isEmpty()) null else RrCleaning.clean(rr.map { it.rrMs })
        val signal = signal(tl, rr.size, cleaned?.let { it.artifactCount * 100.0 / it.size })

        val base: AnalysisReason? = when {
            tl.problem != null -> tl.problem
            tl.movingSeconds == 0 -> AnalysisReason.NoBeltMovement
            else -> null
        }
        // Local sections only need some heart rate; whole-session ones need the coverage too.
        val anyHrGate: AnalysisReason? = base ?: if (tl.heartRateSeconds == 0) AnalysisReason.NoHeartRate else null
        val hrGate: AnalysisReason? = anyHrGate ?: if (tl.heartRateSeconds < rules.minHeartRateCoverage * tl.movingSeconds - 1e-9) {
            AnalysisReason.LowHeartRateCoverage
        } else {
            null
        }
        val invalidMax = context.maxHeartRateBpm <= rules.minHeartRateBpm
        val maxGate: AnalysisReason? = hrGate ?: if (invalidMax) AnalysisReason.InvalidMaxHeartRate else null
        val localMaxGate: AnalysisReason? = anyHrGate ?: if (invalidMax) AnalysisReason.InvalidMaxHeartRate else null

        fun <T : Any> gated(reason: AnalysisReason?, compute: () -> Section<T>): Section<T> =
            if (reason != null) Section.missing(reason) else compute()

        return WorkoutAnalysis(
            algorithmVersion = ALGORITHM_VERSION,
            signal = signal,
            energy = gated(base) { energy(tl, context) },
            zoneTime = gated(hrGate) { context.zones?.let { zoneTime(tl, it) } ?: Section.missing(AnalysisReason.NoZones) },
            trimp = gated(maxGate) { trimp(tl, context) },
            efficiency = gated(hrGate) { efficiency(tl) },
            decoupling = gated(hrGate) { AerobicSections.decoupling(tl, context.zones, rules) },
            fitnessIndex = gated(localMaxGate) { AerobicSections.fitnessIndex(tl, context, rules) },
            recovery = gated(anyHrGate) { RecoverySection.compute(tl, rules) },
            alpha1 = gated(anyHrGate) { Alpha1Section.compute(tl, rr, cleaned, rules) },
            thresholdEstimates = gated(localMaxGate) { ThresholdEstimatesSection.compute(tl, rr, cleaned, context, rules) },
        )
    }

    // ------------------------------------------------------------------ sections

    private fun signal(tl: WorkoutTimeline, rrBeats: Int, rrArtifactPercent: Double?): SignalQuality {
        var strap = 0
        var console = 0
        var unknown = 0
        for (k in 0 until tl.size) {
            if (!tl.moving[k] || tl.hr[k] == 0) continue
            when (tl.source[k]) {
                SampleHeartRateSource.Strap -> strap++
                SampleHeartRateSource.Console -> console++
                else -> unknown++
            }
        }
        val firstCovered = tl.covered.indexOfFirst { it }
        val lastCovered = tl.covered.indexOfLast { it }
        val span = if (firstCovered < 0) 0 else lastCovered - firstCovered + 1
        return SignalQuality(
            sampleCount = tl.sampleCount,
            coveredSeconds = tl.coveredSeconds,
            gapSeconds = span - tl.coveredSeconds,
            movingSeconds = tl.movingSeconds,
            heartRateSeconds = tl.heartRateSeconds,
            frozenHeartRateSeconds = tl.frozenHeartRateSeconds,
            heartRateCoverage = if (tl.movingSeconds > 0) tl.heartRateSeconds.toDouble() / tl.movingSeconds else null,
            heartRateSources = HeartRateSourceMix(strap, console, unknown),
            rrBeats = rrBeats,
            rrArtifactPercent = rrArtifactPercent,
        )
    }

    private fun energy(tl: WorkoutTimeline, context: WorkoutAnalysisContext): Section<EnergyAnalysis> {
        var seconds = 0
        var sumVo2 = 0.0
        var sumSpeed = 0.0
        for (k in 0 until tl.size) {
            if (!tl.moving[k]) continue
            seconds++
            sumVo2 += tl.vo2[k]
            sumSpeed += tl.speed[k]
        }
        val mean = sumVo2 / seconds
        val weight = context.weightKg?.takeIf { it > 0.0 }
        // Gross energy with the equations of the stored calories (EnergyExpenditure); net = gross minus 1 MET.
        val gross = weight?.let { kg ->
            val counter = EnergyCounter(kg)
            for (k in 0 until tl.size) if (tl.moving[k]) counter.add(1.0, tl.speed[k], tl.incline[k])
            counter.kcal
        }
        val restKcal = weight?.let { kg -> Acsm.REST_VO2 * seconds / 60.0 * kg / 1000.0 * EnergyExpenditure.KCAL_PER_LITRE_OXYGEN }
        return Section.of(
            EnergyAnalysis(
                movingSeconds = seconds,
                meanSpeedKmh = sumSpeed / seconds,
                distanceKm = sumSpeed / 3600.0,
                meanVo2MlKgMin = mean,
                meanMets = mean / Acsm.REST_VO2,
                percentOfVo2max = context.vo2maxMlKgMin?.takeIf { it > 0.0 }?.let { mean / it * 100.0 },
                grossKcal = gross,
                netKcal = if (gross != null && restKcal != null) gross - restKcal else null,
            ),
        )
    }

    /** Index (0-based) of the zone of [bpm]: the first zone whose upper bound is above it, or the last one. */
    internal fun zoneIndex(zones: ZoneSet, bpm: Int): Int {
        for ((i, z) in zones.zones.withIndex()) if (i == zones.zones.size - 1 || bpm < z.highBpm) return i
        return zones.zones.size - 1
    }

    private fun zoneTime(tl: WorkoutTimeline, zones: ZoneSet): Section<ZoneTimeAnalysis> {
        val seconds = IntArray(zones.zones.size)
        var total = 0
        for (k in 0 until tl.size) {
            if (!tl.moving[k] || tl.hr[k] == 0) continue
            seconds[zoneIndex(zones, tl.hr[k])]++
            total++
        }
        return Section.of(ZoneTimeAnalysis(zones, seconds.toList(), total))
    }

    /** The resting heart rate to use and whether it is assumed. */
    internal fun restingFor(context: WorkoutAnalysisContext): Pair<Int, Boolean> {
        val rest = context.restingHeartRateBpm?.takeIf { it > 0 && it < context.maxHeartRateBpm }
        return if (rest != null) rest to false
        else (context.maxHeartRateBpm * HeartRateBands.ASSUMED_RESTING_FRACTION).roundToInt() to true
    }

    private fun trimp(tl: WorkoutTimeline, context: WorkoutAnalysisContext): Section<TrimpAnalysis> {
        val (rest, assumed) = restingFor(context)
        val (k, b) = when (context.sex) {
            Sex.Male -> 0.64 to 1.92
            Sex.Female -> 0.86 to 1.67
        }
        val reserve = (context.maxHeartRateBpm - rest).toDouble()
        var sum = 0.0
        var seconds = 0
        for (i in 0 until tl.size) {
            if (!tl.moving[i] || tl.hr[i] == 0) continue
            val hrr = ((tl.hr[i] - rest) / reserve).coerceIn(0.0, 1.0)
            sum += hrr * k * exp(b * hrr)
            seconds++
        }
        return Section.of(TrimpAnalysis(trimp = sum / 60.0, minutes = seconds / 60.0, restingHeartRateBpm = rest, restingAssumed = assumed))
    }

    private fun efficiency(tl: WorkoutTimeline): Section<EfficiencyAnalysis> {
        var n = 0
        var sumVo2 = 0.0
        var sumHr = 0.0
        for (k in 0 until tl.size) {
            if (!tl.moving[k] || tl.hr[k] == 0) continue
            n++
            sumVo2 += tl.vo2[k]
            sumHr += tl.hr[k]
        }
        val vo2 = sumVo2 / n
        val hr = sumHr / n
        return Section.of(EfficiencyAnalysis(vo2, hr, vo2 / hr, n))
    }
}
