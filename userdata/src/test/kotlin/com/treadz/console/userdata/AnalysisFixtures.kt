package com.treadz.console.userdata

import com.treadz.console.analysis.samples.WorkoutSample as AnalysisSample
import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.DecouplingAnalysis
import com.treadz.console.analysis.workout.EfficiencyAnalysis
import com.treadz.console.analysis.workout.EnergyAnalysis
import com.treadz.console.analysis.workout.FitnessIndexAnalysis
import com.treadz.console.analysis.workout.HeartRateSourceMix
import com.treadz.console.analysis.workout.RecoveryAnalysis
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.SessionAlpha1
import com.treadz.console.analysis.workout.SignalQuality
import com.treadz.console.analysis.workout.TrimpAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalyzer
import com.treadz.console.analysis.workout.ZoneTimeAnalysis
import com.treadz.console.analysis.zones.FitnessLevel as ZoneLevel
import com.treadz.console.analysis.zones.ZoneRange
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus

/**
 * One-second samples of a steady workout with a strap (RR intervals included): [seconds] of [speedKmh] at [incline], the
 * heart rate [bpm] plus one beat every two minutes (so that it never looks frozen). Synthetic.
 */
fun steadySamples(
    startedAt: Long = T0,
    seconds: Int = 600,
    bpm: Int = 140,
    speedKmh: Double = 8.0,
    incline: Double = 1.0,
    source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap,
): List<WorkoutSample> = (0 until seconds).map { s ->
    val hr = if (source == null && bpm == 0) null else bpm + s / 120
    WorkoutSample(
        seq = s, timestampMillis = startedAt + s * 1000L, elapsedSeconds = s + 1, speedKmh = speedKmh, targetSpeedKmh = speedKmh,
        inclinePercent = incline, distanceMeters = (s * speedKmh / 3.6).toInt(), calories = s / 20, heartRateBpm = hr,
        beltStatus = BeltStatus.Running, heartRateSource = if (hr == null) null else source,
        rrIntervalsMs = if (hr != null && source == HeartRateSourceKind.DirectStrap) listOf(60_000 / hr) else emptyList(),
    )
}

/** Samples of a workout without any heart rate. */
fun samplesWithoutHeartRate(startedAt: Long = T0, seconds: Int = 300) = steadySamples(startedAt, seconds, bpm = 0, source = null)

private fun mix() = HeartRateSourceMix(strapSeconds = 590, consoleSeconds = 6, unknownSeconds = 4)

private fun zones(source: ZoneSource = ZoneSource.MeasuredThresholds, vt2FromTable: Boolean = false) = ZoneSet(
    zones = listOf(
        ZoneRange(1, null, 109), ZoneRange(2, 110, 129), ZoneRange(3, 130, 154), ZoneRange(4, 155, 170), ZoneRange(5, 171, 185),
    ),
    source = source, level = ZoneLevel.Medium, usedReserve = true, vt1Bpm = 130, vt2Bpm = 155, vt2FromTable = vt2FromTable,
)

/** An analysis with every section present, every optional value set (so the JSON round trip covers all fields). */
fun fullAnalysis(version: Int = WorkoutAnalyzer.ALGORITHM_VERSION) = WorkoutAnalysis(
    algorithmVersion = version,
    signal = SignalQuality(
        sampleCount = 600, coveredSeconds = 598, gapSeconds = 2, movingSeconds = 590, heartRateSeconds = 580, frozenHeartRateSeconds = 10,
        heartRateCoverage = 0.983, heartRateSources = mix(), rrBeats = 1300, rrArtifactPercent = 1.25,
    ),
    energy = Section.of(EnergyAnalysis(590, 8.25, 1.35, 21.4, 6.1, 52.5, 123.4, 99.9)),
    zoneTime = Section.of(ZoneTimeAnalysis(zones(), listOf(10, 120, 300, 100, 50), 580)),
    trimp = Section.of(TrimpAnalysis(61.5, 9.83, 58, restingAssumed = true)),
    efficiency = Section.of(EfficiencyAnalysis(21.4, 141.5, 0.1512, 580)),
    decoupling = Section.of(DecouplingAnalysis(1500, 1200, 0.155, 0.15, 3.2, 0.82, longSteadyZone2 = true)),
    fitnessIndex = Section.of(FitnessIndexAnalysis(41.7, 2, 3, listOf(40.9, 42.5), restingAssumed = false)),
    recovery = Section.of(RecoveryAnalysis(171.5, 1_791_374_400_000L + 540_000, 28.0, 41.5, RecoveryContext.BeltStopped, 3.5)),
    alpha1 = Section.of(SessionAlpha1(40, 35, 300, 50, 0.81)),
)

/** The same shape with only the optional values missing (all of them `null`) and another zone source. */
fun sparseAnalysis() = WorkoutAnalysis(
    algorithmVersion = WorkoutAnalyzer.ALGORITHM_VERSION,
    signal = SignalQuality(10, 10, 0, 10, 0, 0, heartRateCoverage = null, heartRateSources = HeartRateSourceMix(0, 0, 0), rrBeats = 0, rrArtifactPercent = null),
    energy = Section.of(EnergyAnalysis(10, 8.0, 0.02, 20.0, 5.7, null, null, null)),
    zoneTime = Section.of(ZoneTimeAnalysis(zones(ZoneSource.Default, vt2FromTable = true), listOf(0, 0, 0, 0, 0), 0)),
    trimp = Section.missing(AnalysisReason.NoHeartRate),
    efficiency = Section.missing(AnalysisReason.NoHeartRate),
    decoupling = Section.of(DecouplingAnalysis(1500, 1200, 0.155, 0.15, -1.0, null, longSteadyZone2 = false)),
    fitnessIndex = Section.missing(AnalysisReason.NoHeartRate),
    recovery = Section.of(RecoveryAnalysis(150.0, 5L, null, null, RecoveryContext.BeltMoving, 12.0)),
    alpha1 = Section.missing(AnalysisReason.NoRrIntervals),
)

/** An analysis whose eight sections are missing, each for the reason of [reasons] (cyclically). */
fun analysisWithReasons(reasons: List<AnalysisReason>): WorkoutAnalysis {
    fun reason(i: Int) = reasons[i % reasons.size]
    return WorkoutAnalysis(
        algorithmVersion = WorkoutAnalyzer.ALGORITHM_VERSION,
        signal = SignalQuality(0, 0, 0, 0, 0, 0, null, HeartRateSourceMix(0, 0, 0), 0, null),
        energy = Section.missing(reason(0)),
        zoneTime = Section.missing(reason(1)),
        trimp = Section.missing(reason(2)),
        efficiency = Section.missing(reason(3)),
        decoupling = Section.missing(reason(4)),
        fitnessIndex = Section.missing(reason(5)),
        recovery = Section.missing(reason(6)),
        alpha1 = Section.missing(reason(7)),
    )
}

/** The neutral samples of [stored], as the recalculation maps them. */
fun neutralSamples(stored: List<WorkoutSample>): List<AnalysisSample> = FitnessRecalculationInputs.samples(stored)
