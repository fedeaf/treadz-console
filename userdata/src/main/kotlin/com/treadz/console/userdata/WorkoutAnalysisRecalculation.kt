package com.treadz.console.userdata

import com.treadz.console.analysis.energy.FuelTracker
import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.DatedValue
import com.treadz.console.analysis.trends.TestPoint
import com.treadz.console.analysis.workout.FuelSection
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysisContext
import com.treadz.console.analysis.workout.WorkoutAnalyzer
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.ProfileZones
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.heartrate.HeartRateProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the analysis context of a user's workout is built from: the stored data of the user, read once per recalculation.
 * [weights] are the live entries in their order (placeholder weight included: it is the weight the app uses for the
 * generic profile), [tests] the live results, [resting] the live resting heart rate measurements.
 */
internal class AnalysisUserInputs(
    val profile: UserProfile,
    val weights: List<WeightEntry>,
    val tests: List<FitnessTestResult>,
    val resting: List<RestingPoint>,
)

/**
 * The context of the analysis of a workout **as of the workout's date**: a pure function of the stored data of the user
 * and the workout's start time (and the device's time zone, for "the day"), never of the clock, so recalculating gives
 * the same context. The rule:
 *  - **sex**: the profile's;
 *  - **weight**: the latest entry dated on or before the workout's day (ordered as [WeightEntry] says), else the earliest
 *    one after it, else `null`;
 *  - **fitness profile**: `FitnessProfiles.live` of the valid results that **started before the workout**, evaluated at
 *    the workout's start time (a test taken after the workout, or the test the workout itself is, does not take part);
 *  - **maximum heart rate**: `FitnessProfiles.heartRateMax(profile, thatLiveProfile, workoutDay)`;
 *  - **resting heart rate**: [FitnessRecalculationInputs.restingAt] at the workout's start (the reference of the
 *    measurements up to then, else the earliest measurement), else the profile's own value;
 *  - **zones**: `ProfileZones.compute(thatLiveProfile, HeartRateProfile(max, resting), the profile's fitness level as the
 *    self-rated one, the age on that day, sex)`;
 *  - **VO2max**: that live profile's VO2max value;
 *  - **α1 slope**: the `slopePerBpm` of the thresholds of the latest valid test that started before the workout and has
 *    a VT1 of a quality that counts in the profile (`high` or `medium`), for the thresholds implied by the workout's stable
 *    stretches; VT2 is the one of the zones.
 *
 * *Accepted limitation*: the values that live in the profile itself have no history (the manual maximum heart rate, the
 * fitness level, the birth date and sex), so their **current** value is used for every date. The same goes for the
 * profile's own resting value, which is only a fallback (the profile derives it from the measurements, so it exists only
 * when [FitnessRecalculationInputs.restingAt] found one).
 */
internal object WorkoutAnalysisContexts {

    fun build(inputs: AnalysisUserInputs, workoutStartMillis: Long, zone: ZoneId): WorkoutAnalysisContext {
        val profile = inputs.profile
        val day = dayOf(workoutStartMillis, zone)
        val live = FitnessProfiles.live(
            inputs.tests.filter { it.validity == Validity.Valid && it.startedAtMillis < workoutStartMillis },
            profile.sex, profile.birthDate, workoutStartMillis, zone,
        )
        val maxBpm = FitnessProfiles.heartRateMax(profile, live, day).bpm
        val resting = FitnessRecalculationInputs.restingAt(inputs.resting, workoutStartMillis) ?: profile.restingHeartRateBpm
        val sex = profile.sex.toFitness()
        val zones: ZoneSet = ProfileZones.compute(live, HeartRateProfile(maxBpm, resting), selfRatedOf(profile.fitnessLevel), profile.ageOn(day), sex)
        return WorkoutAnalysisContext(
            sex = sex,
            weightKg = weightOn(inputs.weights, day),
            restingHeartRateBpm = resting,
            maxHeartRateBpm = maxBpm,
            zones = zones,
            vo2maxMlKgMin = live.vo2max?.valueMlKgMin,
            alpha1SlopePerBpm = alpha1SlopeBefore(inputs.tests, workoutStartMillis),
        )
    }

    /**
     * The tracker of the live fat/carbohydrate split of a workout of [userId] that starts at [workoutStartMillis], from
     * the context of [build] at that time: the one the workout is analysed with when it ends (only the tests that started
     * before it count), so live and stored use the same anchors. `null` without a user (or the user is gone) or when the
     * profile does not allow a split.
     */
    suspend fun fuelTracker(source: AnalysisSource, userId: Long?, workoutStartMillis: Long, zone: ZoneId): FuelTracker? =
        userId?.let { source.inputs(it) }?.let { FuelSection.tracker(build(it, workoutStartMillis, zone)) }

    /** See the class documentation (α1 slope). */
    fun alpha1SlopeBefore(tests: List<FitnessTestResult>, workoutStartMillis: Long): Double? =
        tests.filter { r ->
            val t = r.thresholds
            r.validity == Validity.Valid && r.startedAtMillis < workoutStartMillis && t?.vt1 != null && t.metrics != null &&
                (t.quality == ThresholdQuality.High || t.quality == ThresholdQuality.Medium)
        }.maxWithOrNull(compareBy({ it.startedAtMillis }, { it.id }))?.thresholds?.metrics?.slopePerBpm

    /** The latest weight on or before [day], else the earliest after it, else `null`. [weights] are in [WeightEntry] order. */
    fun weightOn(weights: List<WeightEntry>, day: LocalDate): Double? =
        (weights.lastOrNull { it.date <= day } ?: weights.firstOrNull())?.weightKg

    fun dayOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun selfRatedOf(level: FitnessLevel?): SelfRatedFitness? = when (level) {
        FitnessLevel.Beginner -> SelfRatedFitness.Beginner
        FitnessLevel.Intermediate -> SelfRatedFitness.Intermediate
        FitnessLevel.Advanced -> SelfRatedFitness.Advanced
        null -> null
    }
}

/** A cheap signature of the samples of a workout (they never change once it has finished). */
internal data class SampleSignature(val count: Int, val firstMillis: Long?, val lastMillis: Long?)

/**
 * The fingerprint of the inputs of an analysis: a hash of the **resulting context** (see [WorkoutAnalysisContexts]: sex,
 * weight, resting and maximum heart rate, VO2max and the zones with their source), the workout's start and the
 * [SampleSignature] of its samples (format 2 adds the α1 slope and VT2). Because it covers the context a past workout is analysed with, a new test, weight,
 * resting measurement or profile change recomputes exactly the workouts whose context it alters (and any other change,
 * such as renaming the user or a test taken after the workout, none). The algorithm version is stored next to it.
 */
internal object WorkoutAnalysisFingerprint {

    /** Bump to recompute every analysis because the fingerprint itself changed. */
    private const val FORMAT = 2

    fun of(startedAtMillis: Long, samples: SampleSignature, context: WorkoutAnalysisContext): String {
        val text = buildString {
            append("f").append(FORMAT)
            append("|start=").append(startedAtMillis)
            append("|n=").append(samples.count).append(",").append(samples.firstMillis).append(",").append(samples.lastMillis)
            append("|sex=").append(context.sex.name)
            append("|w=").append(context.weightKg)
            append("|rest=").append(context.restingHeartRateBpm)
            append("|max=").append(context.maxHeartRateBpm)
            append("|vo2=").append(context.vo2maxMlKgMin)
            append("|a1slope=").append(context.alpha1SlopePerBpm)
            append("|vt2=").append(context.vt2Bpm)
            append("|zones=")
            context.zones?.let { z ->
                append(z.zones.joinToString(",") { "${it.number}:${it.lowBpm}:${it.highBpm}" })
                append(";").append(z.source.name).append(";").append(z.level.name).append(";").append(z.usedReserve)
                append(";").append(z.vt1Bpm).append(";").append(z.vt2Bpm).append(";").append(z.vt2FromTable)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }
}

/** A finished workout of a user and what is stored of its analysis. */
internal class AnalysisCandidate(
    val workoutId: Long,
    val userId: Long,
    val startedAtMillis: Long,
    val samples: SampleSignature,
    val storedVersion: Int?,
    val storedFingerprint: String?,
)

/** A computed analysis ready to be stored. */
internal class AnalysisRecord(
    val workoutId: Long,
    val fingerprint: String,
    val computedAtMillis: Long,
    val analysis: WorkoutAnalysis,
)

/** The storage the shared recalculation works on (Room, in memory). */
internal interface AnalysisSource {
    /** Removes the analyses of workouts that are gone, deleted or without an owner. */
    suspend fun pruneOrphans()

    /** The finished, live workouts that belong to a user, with what is stored of their analysis (cheap: no samples loaded). */
    suspend fun candidates(): List<AnalysisCandidate>

    /** The stored data of a user, or `null` if the user is gone. */
    suspend fun inputs(userId: Long): AnalysisUserInputs?

    /** The samples of one workout, in order. */
    suspend fun samples(workoutId: Long): List<WorkoutSample>

    /** Stores the analysis; `false` if the workout no longer exists (it was deleted meanwhile). */
    suspend fun store(record: AnalysisRecord): Boolean
}

internal class StoredWorkoutAnalysisRecalculator(
    private val source: AnalysisSource,
    private val zone: ZoneId,
    private val nowMillis: () -> Long,
    /** Emits whenever something the analyses depend on may have changed (and once at the start); only changes may repeat. */
    private val changes: Flow<Any?>,
) : WorkoutAnalysisRecalculator {
    private val lock = Mutex()

    /**
     * One workout at a time: the candidates are read without samples, the context is built (and fingerprinted) from the
     * user's data, which is read once per user and run, and only a workout whose stored analysis is missing, of another
     * algorithm version or of another fingerprint has its samples loaded and analysed. A failure on one workout
     * (which would be a bug, missing data is never one) leaves it without an analysis and does not stop the others.
     */
    override suspend fun recalculate(): Int = lock.withLock {
        source.pruneOrphans()
        val users = HashMap<Long, AnalysisUserInputs?>()
        var computed = 0
        for (candidate in source.candidates()) {
            if (candidate.samples.count == 0) continue
            val inputs = users.getOrPut(candidate.userId) { source.inputs(candidate.userId) } ?: continue
            try {
                val context = WorkoutAnalysisContexts.build(inputs, candidate.startedAtMillis, zone)
                val fingerprint = WorkoutAnalysisFingerprint.of(candidate.startedAtMillis, candidate.samples, context)
                if (candidate.storedVersion == WorkoutAnalyzer.ALGORITHM_VERSION && candidate.storedFingerprint == fingerprint) continue
                val samples = FitnessRecalculationInputs.samples(source.samples(candidate.workoutId))
                val analysis = WorkoutAnalyzer.analyse(samples, context)
                if (source.store(AnalysisRecord(candidate.workoutId, fingerprint, nowMillis(), analysis))) computed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This workout stays as it was; the others do not depend on it and the next run tries again.
            }
        }
        computed
    }

    @OptIn(FlowPreview::class)
    override suspend fun keepUpToDate(debounceMillis: Long): Nothing {
        changes.debounce(debounceMillis).collect {
            try {
                recalculate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Derived data only: the next change (or the next start) tries again.
            }
        }
        awaitCancellation()
    }
}

/** Mapping of the stored data to the inputs of the trends of `:analysis` (shared by every implementation). */
internal object PerformanceInputsMapper {

    fun epochDay(millis: Long, zone: ZoneId): Long = WorkoutAnalysisContexts.dayOf(millis, zone).toEpochDay()

    fun of(
        tests: List<FitnessTestResult>,
        resting: List<RestingHeartRateMeasurement>,
        weights: List<WeightEntry>,
        zone: ZoneId,
    ): PerformanceInputs = PerformanceInputs(
        tests = tests
            .filter { it.validity == Validity.Valid && (it.outputs != null || it.thresholds?.vt1 != null || it.thresholds?.vt2 != null) }
            .sortedWith(compareBy({ it.startedAtMillis }, { it.id }))
            .map { t ->
                TestPoint(
                    epochDay = epochDay(t.startedAtMillis, zone),
                    vo2max = t.outputs?.vo2maxMlKgMin,
                    vo2maxSee = t.outputs?.vo2maxSeeMlKgMin,
                    vo2maxIsLowerBound = t.outputs?.vo2maxIsLowerBound ?: false,
                    vt1Bpm = t.thresholds?.vt1?.heartRateBpm,
                    vt2Bpm = t.thresholds?.vt2?.heartRateBpm,
                    thresholdQuality = t.thresholds?.quality,
                )
            },
        restingHeartRate = resting.sortedBy { it.measuredAtMillis }.map { DatedValue(epochDay(it.measuredAtMillis, zone), it.bpm.toDouble()) },
        rmssd = resting.sortedBy { it.measuredAtMillis }.mapNotNull { m -> m.rmssdMs?.let { DatedValue(epochDay(m.measuredAtMillis, zone), it) } },
        weights = weights.filter { !it.isPlaceholder }
            .sortedWith(compareBy({ it.date }, { it.createdAtMillis }, { it.id }))
            .map { DatedValue(it.date.toEpochDay(), it.weightKg) },
    )
}
