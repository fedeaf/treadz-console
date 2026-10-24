package com.treadz.console.programs.personalised

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.heartrate.HeartRateBands
import com.treadz.console.analysis.zones.TrainingZones
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.programs.fitness.ProfileZones
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateZoneBpm
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramLimits
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a generator asks the user (beyond the profile, which every generator reads). */
enum class GeneratorInput { Mode, DurationMinutes, Series, StartWeek, WalkingSpeed, JogSpeedAdjustment }

/**
 * A generator as the UI lists it.
 *
 * @property version Stored in [ProgramOrigin.generatorVersion]; it changes whenever the same inputs would generate a
 *   different program.
 * @property durationMinutes Allowed total duration when [GeneratorInput.DurationMinutes] is an input.
 * @property series Allowed number of series when [GeneratorInput.Series] is an input.
 * @property heartRateGuided Some blocks are heart-rate guided (they need the heart-rate profile to be controlled).
 * @property usesHistory The generator reads [GeneratorRequest.history].
 */
data class GeneratorDescriptor(
    val id: String,
    val version: Int,
    val inputs: Set<GeneratorInput>,
    val modes: Set<ExerciseMode>,
    val durationMinutes: IntRange? = null,
    val defaultDurationMinutes: Int? = null,
    val series: IntRange? = null,
    val defaultSeries: Int? = null,
    val startWeeks: IntRange? = null,
    val heartRateGuided: Boolean,
    val usesHistory: Boolean,
) {
    /** Android string resource names for the UI to define (EN/ES): `generator_<id>_name` / `_description`. */
    val nameResourceKey: String get() = "generator_${id.replace('-', '_')}_name"
    val descriptionResourceKey: String get() = "generator_${id.replace('-', '_')}_description"
}

/** Something the UI should tell the user about a generated program. */
enum class GeneratorNoticeCode {
    /** A reference used was estimated: intensity −10 %; "estimated values: do a test to refine". */
    EstimatedValues,

    /** Height or weight unknown: the VO2max estimate assumed a BMI of 27. */
    BodyDataAssumed,

    /** A heart-rate block ([GeneratorNotice.blockKey]) starts where the heart rate settled last time ([GeneratorNotice.value]). */
    StartedFromHistory,

    /** Walking cannot reach the intensity of a block within its incline limit and 6.5 km/h: it runs at that maximum. */
    WalkingTargetCapped,

    /**
     * No resting heart rate: zones meant as % of the heart-rate reserve use the equivalent % of the maximum for an
     * assumed resting heart rate (see [ProgramGenerators.reserveZone]). Measuring the resting heart rate refines them.
     */
    RestingHeartRateMissing,

    /** Couch to 5K: less than 48 h since the previous session ([GeneratorNotice.value] = hours). A warning only. */
    RecentSession,

    /** Couch to 5K: a long break ([GeneratorNotice.value] = days) moved the plan one or two weeks back. */
    LongBreak,

    /** Couch to 5K: the plan is finished; the last session is offered again. */
    PlanCompleted,
}

data class GeneratorNotice(val code: GeneratorNoticeCode, val blockKey: String? = null, val value: Double? = null)

/**
 * A generated session, ready to preview, store and run (`ProgramResolver.resolve(program)` needs no start parameters:
 * every target is absolute).
 *
 * @property estimatedValues A reference used was estimated and the intensity was lowered by 10 % ([intensityFactor] 0.9).
 * @property walkingSpeedKmh The walking speed used (warm-ups, recoveries, walking sessions).
 * @property couchToFiveK Only for Couch to 5K: which session and why.
 */
data class GeneratedProgram(
    val program: Program,
    val descriptor: GeneratorDescriptor,
    val references: IntensityReferences,
    val estimatedValues: Boolean,
    val intensityFactor: Double,
    val walkingSpeedKmh: Double,
    val notices: List<GeneratorNotice>,
    val couchToFiveK: CouchToFiveKSelection? = null,
) {
    val totalDurationSeconds: Int get() = ProgramValidator.totalDurationSeconds(program).toInt()
}

/** Produces personalised programs. Implementations are pure: the same request always gives the same program. */
interface ProgramGenerator {
    val descriptor: GeneratorDescriptor

    /** Throws [IllegalArgumentException] when a request value is outside what [descriptor] allows. */
    fun generate(request: GeneratorRequest): GeneratedProgram
}

/**
 * The registry of generators, in the order the UI lists them.
 *
 * | id | structure | intensity |
 * |---|---|---|
 * | `zone2` | 5 min + main + 5 min, 20-90 min | main block heart-rate guided in zone 2 of the zone model, continuous |
 * | `tempo` | 10-20 min warm-up + 2-3 × (8-10 min + 3 min) + 5 min, 37-60 min | work guided in zone 4 of the zone model (start from a measured VT1, else 84 % MAS), continuous |
 * | `norwegian-4x4` | 10 + 4 × (4 + 3) + 5 min | work guided at 85-95 % HRmax, between repetitions |
 * | `intervals-30-30` | 10 min + N × (30 s + 30 s) + 5 min, N 6-30 | 30 s at 100-105 % MAS / 30 s easy, fixed values |
 * | `couch-to-5k` | the 27 classic sessions, see [CouchToFiveK] | jog at 60-65 % MAS; long runs guided in zone 3 of the zone model |
 *
 * **Heart-rate zones** come from the zone model ([TrainingZones] through [ProfileZones]): with measured thresholds,
 * zones around VT1 and VT2 in bpm, written in the program as the nearest whole % of the heart-rate reserve (or of the
 * maximum without a resting heart rate); otherwise the %-of-reserve table of the fitness level (the VO2max
 * classification of a test, else the self-rated level, else low), as % of the reserve (Karvonen, HRR) when the resting
 * heart rate is known: % of the reserve matches % of the VO2 reserve (ACSM). The Norwegian 4x4 and the short Couch to
 * 5K runs keep % of the maximum, as their protocols define them. The blocks guided by the zone model name the zone
 * of the user's own zones as well ([HeartRateZone.profileZone]): the run converts it to the exact bpm of the zones in force
 * then, and the whole percentages stay as the fallback for an app that does not know the field or has no zones. See
 * [Generation.modelZone] and [reserveZone].
 *
 * **Start of a heart-rate block with a measured VT1** (and its ACSM VO2): VO2 = 3.5 + (VO2_VT1 − 3.5) ·
 * (HR_target − HR_rest) / (VT1 − HR_rest), with HR_target the middle of the block's zone and HR_rest the resting heart
 * rate (assumed at a third of the maximum when unknown), translated with ACSM to the incline at the walking speed or to
 * the running speed at 1 %; not lowered by the estimated-values factor (it comes from a measurement). Without VT1 the
 * start comes from the VO2max or the MAS as before. A settled value from the history keeps priority.
 *
 * Common rules: running is at 1 %; walking keeps the speed and moves the incline (heart-rate blocks adjust the incline
 * while walking and the speed while running). Warm-ups are at the easy value (running: half the MAS, at least the
 * walking speed); cool-downs at the walking speed. Work speeds while running are at least 6 km/h. When a reference used
 * is estimated every intensity is multiplied by 0.9. A heart-rate block whose history has a `settledValue` (same
 * generator, same mode, same block position, same adjusted quantity, at most [HISTORY_MAX_AGE_DAYS] old, and a zone
 * within [SETTLED_ZONE_TOLERANCE_BPM] bpm of the new block's at each limit) starts there.
 */
object ProgramGenerators {

    const val HISTORY_MAX_AGE_DAYS = 90

    /**
     * A settled value of the history is reused only when the zone that session aimed at is the zone of the block being
     * generated: each limit within this many bpm of the new one, in bpm for the current profile. It absorbs the rounding of
     * zones stored as whole percentages and a small change of the resting heart rate or of VT1, and rejects a different
     * zone (the 132-144 of an older zone 2 against today's 122-131: the load that settles the heart rate there is 8-14 bpm
     * too high for this one).
     */
    const val SETTLED_ZONE_TOLERANCE_BPM = 2

    /**
     * Resting heart rate assumed, as a fraction of the maximum, to turn a % of the reserve into a % of the maximum when
     * the resting heart rate is unknown: a third (60 bpm for a maximum of 180). See [HeartRateBands.ASSUMED_RESTING_FRACTION].
     */
    const val ASSUMED_RESTING_FRACTION = HeartRateBands.ASSUMED_RESTING_FRACTION

    /**
     * The zone [lowPercent]-[highPercent] % of the heart-rate reserve: as such when [restingHeartRateBpm] is known,
     * else the equivalent % of the maximum for a resting heart rate of [ASSUMED_RESTING_FRACTION] of the maximum
     * (60-70 % HRR → 73-80 % HRmax; 70-80 → 80-87; 80-90 → 87-93).
     */
    fun reserveZone(lowPercent: Int, highPercent: Int, restingHeartRateBpm: Int?): HeartRateZone =
        if (restingHeartRateBpm != null) {
            HeartRateZone(HeartRateBasis.HeartRateReserve, lowPercent, highPercent)
        } else {
            HeartRateZone(HeartRateBasis.MaxHeartRate, reserveToMaxPercent(lowPercent), reserveToMaxPercent(highPercent))
        }

    private fun reserveToMaxPercent(percent: Int): Int = HeartRateBands.reserveToMaxPercent(percent)

    /** A zone as description text: "60-70 % of the heart-rate reserve" or "73-80 % of the maximum heart rate". */
    fun zoneText(zone: HeartRateZone): String = "${zone.lowPercent}-${zone.highPercent} % of the " +
        if (zone.basis == HeartRateBasis.HeartRateReserve) "heart-rate reserve" else "maximum heart rate"

    /** Key of a block's position: `"<step>"`, or `"<step>.<block in repeat>"`. */
    fun blockKey(stepIndex: Int, blockInStep: Int?): String = if (blockInStep == null) "$stepIndex" else "$stepIndex.$blockInStep"

    val all: List<ProgramGenerator> = listOf(Zone2Generator, TempoGenerator, Norwegian4x4Generator, Intervals3030Generator, CouchToFiveK)

    fun byId(id: String): ProgramGenerator? = all.firstOrNull { it.descriptor.id == id }

    fun generate(id: String, request: GeneratorRequest): GeneratedProgram =
        (byId(id) ?: throw IllegalArgumentException("Unknown generator '$id'")).generate(request)
}

// ---------------------------------------------------------------------------------------------- shared machinery

/** One generation: references, intensity factor, notices and the origin inputs being collected. */
internal class Generation(
    val request: GeneratorRequest,
    val descriptor: GeneratorDescriptor,
    usesVo2max: Boolean,
    usesMas: Boolean,
    usesMaxHeartRate: Boolean,
    walkingSpeedFallback: () -> Double? = { null },
) {
    val mode: ExerciseMode = if (ExerciseMode.Walk in descriptor.modes && GeneratorInput.Mode in descriptor.inputs) request.mode else ExerciseMode.Run
    val refs: IntensityReferences = IntensityEstimator.references(request.profile)
    val estimated: Boolean = refs.estimatedFor(usesVo2max, usesMas, usesMaxHeartRate)
    val factor: Double = if (estimated) 1.0 - IntensityEstimator.ESTIMATED_INTENSITY_REDUCTION else 1.0
    val notices = mutableListOf<GeneratorNotice>()
    val inputs = linkedMapOf<String, String>()
    val history: List<GeneratedSessionRecord> = request.history.filter { it.generatorId == descriptor.id }.sortedBy { it.startedAtMillis }

    val walkingSpeed: Double = run {
        val asked = request.walkingSpeedKmh
        if (asked != null) {
            require(asked.isFinite() && asked in 1.0..8.0) { "walkingSpeedKmh must be between 1 and 8 km/h (was $asked)" }
        }
        round1(asked ?: walkingSpeedFallback() ?: IntensityTranslator.DEFAULT_WALKING_SPEED_KMH)
    }

    init {
        val p = request.profile
        require(p.ageYears in 10..100) { "ageYears must be between 10 and 100 (was ${p.ageYears})" }
        if (estimated) notices += GeneratorNotice(GeneratorNoticeCode.EstimatedValues)
        if (refs.vo2maxEstimated && refs.jackson?.bmiAssumed == true) notices += GeneratorNotice(GeneratorNoticeCode.BodyDataAssumed)
        inputs["mode"] = mode.wire()
        inputs["sex"] = p.sex.wire()
        inputs["ageYears"] = p.ageYears.toString()
        p.heightCm?.let { inputs["heightCm"] = fmt(it) }
        p.weightKg?.let { inputs["weightKg"] = fmt(it) }
        inputs["selfRatedFitness"] = p.selfRatedFitness?.wire() ?: "unknown"
        inputs["vo2maxMlKgMin"] = fmt(refs.vo2maxMlKgMin)
        inputs["vo2maxEstimated"] = refs.vo2maxEstimated.toString()
        refs.jackson?.let {
            inputs["nonExerciseBmi"] = fmt(it.bmi)
            inputs["nonExerciseActivityRating"] = it.physicalActivityRating.toString()
        }
        inputs["masKmh"] = fmt(refs.masKmh)
        inputs["masEstimated"] = refs.masEstimated.toString()
        inputs["maxHeartRateBpm"] = refs.maxHeartRateBpm.toString()
        inputs["maxHeartRateOrigin"] = p.maxHeartRateOrigin.wire()
        refs.restingHeartRateBpm?.let { inputs["restingHeartRateBpm"] = it.toString() }
        inputs["estimatedValues"] = estimated.toString()
        inputs["intensityFactor"] = fmt(factor)
        inputs["walkingSpeedKmh"] = fmt(walkingSpeed)
        inputs["historySessions"] = history.size.toString()
    }

    // ---- values

    /** Running work speed: at least 6 km/h. */
    fun runSpeed(v: Double): Double = clampSpeed(max(v, IntensityTranslator.MIN_RUNNING_SPEED_KMH))

    /** Warm-up / easy target: running at half the MAS (at least the walking speed) and 1 %; walking flat. */
    fun easy(): BlockTarget.Absolute = when (mode) {
        ExerciseMode.Run -> BlockTarget.Absolute(clampSpeed(max(walkingSpeed, 0.5 * factor * refs.masKmh)), IntensityTranslator.RUNNING_INCLINE_PERCENT)
        ExerciseMode.Walk -> BlockTarget.Absolute(clampSpeed(walkingSpeed), 0.0)
    }

    /** Cool-down target: the walking speed (1 % in running sessions, flat otherwise). */
    fun coolDown(): BlockTarget.Absolute =
        BlockTarget.Absolute(clampSpeed(walkingSpeed), if (mode == ExerciseMode.Run) IntensityTranslator.RUNNING_INCLINE_PERCENT else 0.0)

    fun walking(
        vo2: Double,
        speed: Double = walkingSpeed,
        key: String? = null,
        maxInclinePercent: Double = IntensityTranslator.MAX_WALKING_INCLINE_PERCENT,
    ): TreadmillTarget {
        val t = IntensityTranslator.walking(vo2, speed, maxInclinePercent)
        if (t.capped && notices.none { it.code == GeneratorNoticeCode.WalkingTargetCapped && it.blockKey == key }) {
            notices += GeneratorNotice(GeneratorNoticeCode.WalkingTargetCapped, key)
        }
        return t
    }

    /** A zone in % of the heart-rate reserve (see [ProgramGenerators.reserveZone]); notes when the reserve is unknown. */
    fun reserveZone(lowPercent: Int, highPercent: Int): HeartRateZone {
        val zone = ProgramGenerators.reserveZone(lowPercent, highPercent, refs.restingHeartRateBpm)
        inputs["heartRateZoneBasis"] = zone.basis.wire()
        if (zone.basis != HeartRateBasis.HeartRateReserve && notices.none { it.code == GeneratorNoticeCode.RestingHeartRateMissing }) {
            notices += GeneratorNotice(GeneratorNoticeCode.RestingHeartRateMissing)
        }
        return zone
    }

    /** The zone model of this user (see [ProfileZones]). */
    val zones: ZoneSet by lazy {
        val p = request.profile
        ProfileZones.compute(p.fitnessProfile, p.heartRate, p.selfRatedFitness, p.ageYears, p.sex)
    }

    /**
     * The heart-rate profile with the zone model, to turn a program zone into bpm exactly as the run will (a zone that
     * names a zone of the user's own zones is that zone, not its rounded percentages).
     */
    private val heartRateWithZones: HeartRateProfile by lazy { request.profile.heartRate.copy(zones = zones) }

    /** [zone] in bpm as the run will resolve it. */
    fun toBpm(zone: HeartRateZone): HeartRateZoneBpm = HeartRateZones.toBpm(zone, heartRateWithZones)

    /** VT1 of the profile with its oxygen demand, when the zones come from measured thresholds. */
    private val vt1: Pair<Int, Double>? by lazy {
        val t = request.profile.fitnessProfile?.thresholds?.vt1
        val vo2 = t?.vo2MlKgMin
        if (zones.source == ZoneSource.MeasuredThresholds && t != null && vo2 != null) t.heartRateBpm to vo2 else null
    }

    /**
     * Zone [number] (2-4) of the zone model as a program zone. It names that zone of the user's own zones
     * ([HeartRateZone.profileZone]), so the run uses its exact bpm (the top of zone 2 is VT1 itself, not VT1 rounded
     * through a whole percentage of the reserve), and carries the percentages as the fallback for an app that does not know
     * the field or has no zones: with measured thresholds the bpm bounds as the nearest whole % of the reserve (with a
     * resting heart rate) or of the maximum; otherwise the level table in % of the reserve ([reserveZone], which notes a
     * missing resting heart rate; the table gives whole percentages already, so both ways give the same bpm).
     */
    fun modelZone(number: Int): HeartRateZone {
        val z = zones
        inputs["zoneSource"] = z.source.wire()
        inputs["zoneLevel"] = z.level.wire()
        if (z.source != ZoneSource.MeasuredThresholds) {
            val b = checkNotNull(TrainingZones.RESERVE_BOUNDARIES[z.level])
            return reserveZone(b[number - 2], b[number - 1]).copy(profileZone = number)
        }
        inputs["vt1Bpm"] = z.vt1Bpm.toString()
        inputs["vt2Bpm"] = z.vt2Bpm.toString()
        if (z.vt2FromTable) inputs["vt2FromTable"] = "true"
        val range = z.zone(number)
        val max = request.profile.heartRate.maxBpm
        val rest = refs.restingHeartRateBpm
        val low = HeartRateBands.percentOf(checkNotNull(range.lowBpm), max, rest).coerceIn(0, 99)
        val high = HeartRateBands.percentOf(range.highBpm, max, rest).coerceIn(low + 1, 100)
        val zone = HeartRateZone(if (rest != null) HeartRateBasis.HeartRateReserve else HeartRateBasis.MaxHeartRate, low, high, profileZone = number)
        inputs["heartRateZoneBasis"] = zone.basis.wire()
        return zone
    }

    /**
     * The start oxygen demand of a heart-rate block in [zone] from the measured VT1 (see [ProgramGenerators]), or `null`
     * without a measured VT1 with its VO2.
     */
    fun vt1StartVo2(zone: HeartRateZone, key: String): Double? {
        val (vt1Bpm, vt1Vo2) = vt1 ?: return null
        val max = request.profile.heartRate.maxBpm
        val rest = refs.restingHeartRateBpm?.toDouble() ?: (max * ASSUMED_RESTING)
        if (vt1Bpm <= rest) return null
        val bpm = toBpm(zone)
        val target = (bpm.lowBpm + bpm.highBpm) / 2.0
        val vo2 = Acsm.REST_VO2 + (vt1Vo2 - Acsm.REST_VO2) * (target - rest) / (vt1Bpm - rest)
        inputs["startFromVt1.$key"] = fmt(vo2)
        return maxOf(Acsm.REST_VO2, vo2)
    }

    /** Start target of a heart-rate block from the measured VT1: walking incline or running speed (ACSM). */
    fun vt1Start(zone: HeartRateZone, key: String): TreadmillTarget? {
        val vo2 = vt1StartVo2(zone, key) ?: return null
        return when (mode) {
            ExerciseMode.Walk -> walking(vo2, key = key)
            ExerciseMode.Run -> TreadmillTarget(runSpeed(Acsm.runningSpeedFor(vo2, IntensityTranslator.RUNNING_INCLINE_PERCENT / 100.0)), IntensityTranslator.RUNNING_INCLINE_PERCENT, vo2)
        }
    }

    /** The middle of [zone], in bpm for this user, as a fraction of the maximum heart rate (for start values). */
    fun midFractionOfMax(zone: HeartRateZone): Double {
        val bpm = toBpm(zone)
        return (bpm.lowBpm + bpm.highBpm) / 2.0 / refs.maxHeartRateBpm
    }

    // ---- history

    /**
     * The latest settled value of the block [key] in this generator's history (same mode, same adjusted quantity; for
     * the incline, the same walking speed), at most [ProgramGenerators.HISTORY_MAX_AGE_DAYS] old, from a session whose
     * zone is the one of the new block: its limits (`zoneLowBpm` and `zoneHighBpm` of the block's heart-rate summary) each within
     * [ProgramGenerators.SETTLED_ZONE_TOLERANCE_BPM] of [zone], in bpm for the current profile. A block of another zone is
     * skipped (an older one may still match); with none, the caller starts as it would without history.
     */
    fun settled(key: String, adjust: HeartRateAdjust, fixedSpeedKmh: Double?, zone: HeartRateZone): Double? {
        val cutoff = request.nowMillis - ProgramGenerators.HISTORY_MAX_AGE_DAYS * DAY_MS
        val wanted = toBpm(zone)
        for (r in history.asReversed()) {
            if (r.startedAtMillis < cutoff || r.origin.inputs["mode"] != mode.wire()) continue
            val v = r.blocks.lastOrNull { b ->
                val hr = b.heartRate
                b.key == key && hr != null && hr.adjust == adjust && hr.settledValue != null &&
                    abs(hr.zoneLowBpm - wanted.lowBpm) <= ProgramGenerators.SETTLED_ZONE_TOLERANCE_BPM &&
                    abs(hr.zoneHighBpm - wanted.highBpm) <= ProgramGenerators.SETTLED_ZONE_TOLERANCE_BPM &&
                    (fixedSpeedKmh == null || abs(b.finalSpeedKmh - fixedSpeedKmh) <= 0.05)
            }?.heartRate?.settledValue
            if (v != null) return v
        }
        return null
    }

    // ---- heart-rate blocks

    /**
     * A heart-rate block. Running: the speed moves between [minimum] and [maximum] from [start] at 1 %. Walking: the
     * incline moves (0 % to at least 15 %) at the fixed speed of [walk]. A settled value from the history replaces the
     * start (and widens the bounds if needed).
     */
    fun runningHeartRateBlock(phase: BlockPhase, seconds: Int, key: String, zone: HeartRateZone, start: Double, minimum: Double, maximum: Double): Block {
        val fromHistory = settled(key, HeartRateAdjust.Speed, null, zone)?.let { clampSpeed(it) }
        val s = fromHistory ?: clampSpeed(start)
        fromHistory?.let { noteHistory(key, it) }
        val lo = clampSpeed(min(minimum, s))
        val hi = clampSpeed(max(maximum, s))
        return Block(phase, BlockEnd.Duration(seconds), BlockTarget.HeartRate(zone, HeartRateAdjust.Speed, lo, hi, BlockTarget.Absolute(s, IntensityTranslator.RUNNING_INCLINE_PERCENT)))
    }

    fun walkingHeartRateBlock(phase: BlockPhase, seconds: Int, key: String, zone: HeartRateZone, walk: TreadmillTarget): Block {
        val speed = clampSpeed(walk.speedKmh)
        val fromHistory = settled(key, HeartRateAdjust.Incline, speed, zone)?.let { clampIncline(it) }
        val start = fromHistory ?: clampIncline(walk.inclinePercent)
        fromHistory?.let { noteHistory(key, it) }
        val hi = clampIncline(max(IntensityTranslator.MAX_WALKING_INCLINE_PERCENT, start))
        return Block(phase, BlockEnd.Duration(seconds), BlockTarget.HeartRate(zone, HeartRateAdjust.Incline, 0.0, hi, BlockTarget.Absolute(speed, start)))
    }

    private fun noteHistory(key: String, value: Double) {
        notices += GeneratorNotice(GeneratorNoticeCode.StartedFromHistory, key, value)
        inputs["startFromHistory.$key"] = fmt(value)
    }

    // ---- result

    fun result(steps: List<ProgramStep>, defaultName: String, description: String, couchToFiveK: CouchToFiveKSelection? = null): GeneratedProgram {
        val program = Program(
            id = request.programId,
            name = (request.name?.takeIf { it.isNotBlank() } ?: defaultName).take(ProgramLimits.MAX_NAME_LENGTH),
            kind = ProgramKind.Personalised,
            steps = steps,
            description = description.take(ProgramLimits.MAX_DESCRIPTION_LENGTH),
            origin = ProgramOrigin(descriptor.id, descriptor.version, LinkedHashMap(inputs)),
        )
        ProgramValidator.requireValid(program)
        return GeneratedProgram(program, descriptor, refs, estimated, factor, walkingSpeed, notices.toList(), couchToFiveK)
    }

    companion object {
        const val DAY_MS = 86_400_000L

        private const val ASSUMED_RESTING = ProgramGenerators.ASSUMED_RESTING_FRACTION

        fun clampSpeed(v: Double): Double = round1(v.coerceIn(ProgramLimits.MIN_SPEED_KMH, ProgramLimits.MAX_SPEED_KMH))

        fun clampIncline(v: Double): Double = v.coerceIn(ProgramLimits.MIN_INCLINE_PERCENT, ProgramLimits.MAX_INCLINE_PERCENT).roundToInt().toDouble()

        fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

        /** Number as origin text: `5`, `5.5`, `5.25`. */
        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

        fun Enum<*>.wire(): String = name.replaceFirstChar { it.lowercaseChar() }

        fun timed(phase: BlockPhase, seconds: Int, target: BlockTarget): Block = Block(phase, BlockEnd.Duration(seconds), target)
    }
}

// ---------------------------------------------------------------------------------------------- generators

/**
 * Zone 2 easy run or walk: 5 min + main block heart-rate guided in zone 2 of the zone model (continuous) + 5 min.
 * Version 2: zone 2 from the zone model (it was 60-70 % of the reserve) and the start from a measured VT1. Version 3: the
 * zone names zone 2 of the user's own zones (exact bpm: the top is VT1 itself) and a settled value of the history is used
 * only when its zone matches.
 */
internal object Zone2Generator : ProgramGenerator {
    override val descriptor = GeneratorDescriptor(
        id = "zone2",
        version = 3,
        inputs = setOf(GeneratorInput.Mode, GeneratorInput.DurationMinutes, GeneratorInput.WalkingSpeed),
        modes = setOf(ExerciseMode.Walk, ExerciseMode.Run),
        durationMinutes = 20..90,
        defaultDurationMinutes = 45,
        heartRateGuided = true,
        usesHistory = true,
    )

    private const val MAIN_KEY = "1"

    override fun generate(request: GeneratorRequest): GeneratedProgram {
        val minutes = request.durationMinutes ?: checkNotNull(descriptor.defaultDurationMinutes)
        require(minutes in checkNotNull(descriptor.durationMinutes)) { "durationMinutes must be within ${descriptor.durationMinutes} (was $minutes)" }
        val g = Generation(request, descriptor, usesVo2max = request.mode == ExerciseMode.Walk, usesMas = request.mode == ExerciseMode.Run, usesMaxHeartRate = true)
        val mainSeconds = (minutes - 10) * 60
        g.inputs["durationMinutes"] = minutes.toString()
        val zone = g.modelZone(2)
        // Start at the middle of the zone: from the measured VT1, else from the VO2max.
        val fromVt1 = g.vt1Start(zone, MAIN_KEY)
        val fraction = IntensityTranslator.vo2maxFractionForHeartRate(g.midFractionOfMax(zone), g.refs) * g.factor
        val main = when (g.mode) {
            ExerciseMode.Run -> {
                val start = fromVt1?.speedKmh ?: g.runSpeed(IntensityTranslator.running(fraction * g.refs.vo2maxMlKgMin, g.refs).speedKmh)
                g.runningHeartRateBlock(
                    BlockPhase.Work, mainSeconds, MAIN_KEY, zone, start,
                    minimum = min(g.walkingSpeed, start),
                    maximum = max(start + 1.0, 0.75 * g.refs.masKmh),
                )
            }
            ExerciseMode.Walk -> g.walkingHeartRateBlock(
                BlockPhase.Work, mainSeconds, MAIN_KEY, zone, fromVt1 ?: g.walking(fraction * g.refs.vo2maxMlKgMin, key = MAIN_KEY),
            )
        }
        val steps = listOf(
            Generation.timed(BlockPhase.WarmUp, 300, g.easy()),
            main,
            Generation.timed(BlockPhase.CoolDown, 300, g.coolDown()),
        )
        val what = if (g.mode == ExerciseMode.Run) "run" else "walk"
        return g.result(
            steps,
            defaultName = "Zone 2 easy $what, $minutes min",
            description = "5 min warm-up, ${minutes - 10} min heart-rate guided in zone 2 (${ProgramGenerators.zoneText(zone)}), 5 min cool-down.",
        )
    }
}

/**
 * Tempo / threshold: warm-up (10-20 min, easy) + N × (L min in zone 4, starting from a measured VT1 or else at 84 % MAS, + 3 min recovery) + 5 min, with N 2-3 and L
 * 8-10 min: the combination with the most work whose warm-up fits 10-20 min. The work blocks are guided in zone 4 of the
 * zone model (continuous: they last 6 min or more) starting at 84 % MAS, or from a measured VT1. Version 2: zone 4 from
 * the zone model (it was 80-90 % of the reserve) and the start from a measured VT1.
 */
internal object TempoGenerator : ProgramGenerator {
    override val descriptor = GeneratorDescriptor(
        id = "tempo",
        version = 3,
        inputs = setOf(GeneratorInput.Mode, GeneratorInput.DurationMinutes, GeneratorInput.WalkingSpeed),
        modes = setOf(ExerciseMode.Walk, ExerciseMode.Run),
        durationMinutes = 37..60,
        defaultDurationMinutes = 45,
        heartRateGuided = true,
        usesHistory = true,
    )

    private const val WORK_KEY = "1.0"
    private const val RECOVERY_MINUTES = 3
    private const val COOL_DOWN_MINUTES = 5

    /** Candidates (series, work minutes), most work first. */
    private val CANDIDATES = listOf(3 to 10, 3 to 9, 3 to 8, 2 to 10, 2 to 9, 2 to 8)

    /** (series, work minutes, warm-up minutes) for a total of [minutes]. */
    fun structure(minutes: Int): Triple<Int, Int, Int> {
        for ((n, l) in CANDIDATES) {
            val warm = minutes - COOL_DOWN_MINUTES - n * (l + RECOVERY_MINUTES)
            if (warm in 10..20) return Triple(n, l, warm)
        }
        throw IllegalArgumentException("No tempo structure fits $minutes min")
    }

    override fun generate(request: GeneratorRequest): GeneratedProgram {
        val minutes = request.durationMinutes ?: checkNotNull(descriptor.defaultDurationMinutes)
        require(minutes in checkNotNull(descriptor.durationMinutes)) { "durationMinutes must be within ${descriptor.durationMinutes} (was $minutes)" }
        val g = Generation(request, descriptor, usesVo2max = request.mode == ExerciseMode.Walk, usesMas = request.mode == ExerciseMode.Run, usesMaxHeartRate = true)
        val (n, l, warm) = structure(minutes)
        g.inputs["durationMinutes"] = minutes.toString()
        g.inputs["series"] = n.toString()
        g.inputs["workMinutes"] = l.toString()
        val mas = g.refs.masKmh
        val zone = g.modelZone(4)
        val fromVt1 = g.vt1Start(zone, WORK_KEY)
        val work = when (g.mode) {
            ExerciseMode.Run -> {
                val start = fromVt1?.speedKmh ?: g.runSpeed(0.84 * g.factor * mas)
                g.runningHeartRateBlock(
                    BlockPhase.Work, l * 60, WORK_KEY, zone, start,
                    minimum = min(start, max(g.walkingSpeed, 0.70 * g.factor * mas)),
                    maximum = max(start + 0.5, 0.92 * mas),
                )
            }
            ExerciseMode.Walk -> g.walkingHeartRateBlock(
                BlockPhase.Work, l * 60, WORK_KEY, zone,
                fromVt1 ?: g.walking(0.84 * g.factor * g.refs.vo2maxMlKgMin, key = WORK_KEY),
            )
        }
        val recovery = Generation.timed(BlockPhase.Recovery, RECOVERY_MINUTES * 60, g.easy())
        val steps = listOf(
            Generation.timed(BlockPhase.WarmUp, warm * 60, g.easy()),
            Repeat(n, listOf(work, recovery)),
            Generation.timed(BlockPhase.CoolDown, COOL_DOWN_MINUTES * 60, g.coolDown()),
        )
        return g.result(
            steps,
            defaultName = "Tempo $n × $l min",
            description = "$warm min warm-up, $n × ($l min at threshold, heart-rate guided in " +
                "${ProgramGenerators.zoneText(zone)} + 3 min easy), 5 min cool-down.",
        )
    }
}

/**
 * Norwegian 4x4: 10 min warm-up + 4 × (4 min at 85-95 % HRmax + 3 min active recovery) + 5 min. The work blocks are
 * heart-rate guided between repetitions, starting at the intensity of 90 % HRmax (from a measured VT1 when there is one;
 * version 2). Version 3: a settled value of the history is used only when its zone matches.
 */
internal object Norwegian4x4Generator : ProgramGenerator {
    override val descriptor = GeneratorDescriptor(
        id = "norwegian-4x4",
        version = 3,
        inputs = setOf(GeneratorInput.Mode, GeneratorInput.WalkingSpeed),
        modes = setOf(ExerciseMode.Walk, ExerciseMode.Run),
        heartRateGuided = true,
        usesHistory = true,
    )

    private val ZONE = HeartRateZone(HeartRateBasis.MaxHeartRate, 85, 95)
    private const val WORK_KEY = "1.0"

    override fun generate(request: GeneratorRequest): GeneratedProgram {
        val g = Generation(request, descriptor, usesVo2max = request.mode == ExerciseMode.Walk, usesMas = request.mode == ExerciseMode.Run, usesMaxHeartRate = true)
        val fraction = IntensityTranslator.vo2maxFractionForHeartRate(0.90, g.refs) * g.factor
        val fromVt1 = g.vt1Start(ZONE, WORK_KEY)
        val mas = g.refs.masKmh
        val work = when (g.mode) {
            ExerciseMode.Run -> {
                val start = fromVt1?.speedKmh ?: g.runSpeed(IntensityTranslator.running(fraction * g.refs.vo2maxMlKgMin, g.refs).speedKmh)
                g.runningHeartRateBlock(
                    BlockPhase.Work, 240, WORK_KEY, ZONE, start,
                    minimum = min(start, max(g.walkingSpeed, 0.6 * g.factor * mas)),
                    maximum = max(start + 0.5, mas),
                )
            }
            ExerciseMode.Walk -> g.walkingHeartRateBlock(BlockPhase.Work, 240, WORK_KEY, ZONE, fromVt1 ?: g.walking(fraction * g.refs.vo2maxMlKgMin, key = WORK_KEY))
        }
        val steps = listOf(
            Generation.timed(BlockPhase.WarmUp, 600, g.easy()),
            Repeat(4, listOf(work, Generation.timed(BlockPhase.Recovery, 180, g.easy()))),
            Generation.timed(BlockPhase.CoolDown, 300, g.coolDown()),
        )
        return g.result(
            steps,
            defaultName = "Norwegian 4x4",
            description = "10 min warm-up, 4 × (4 min at 85-95 % of the maximum heart rate + 3 min easy), 5 min cool-down.",
        )
    }
}

/**
 * Short 30/30 intervals: 10 min warm-up + N × (30 s fast + 30 s easy) + 5 min, fixed values (no heart-rate guidance).
 * Running: 30 s at 102.5 % MAS and 30 s at half the MAS (at least the walking speed), at 1 %. Walking: the incline is
 * fixed for the set (the incline motor is too slow for 30-s changes) and at most [WALKING_MAX_INCLINE_PERCENT], so the
 * easy half is a real recovery; 30 s at a brisk speed (walking speed + 1.5, raised up to 6.5 km/h when the incline
 * limit is not enough) with the incline that gives 102.5 % of VO2max, and 30 s at walking speed − 1.5 (at least
 * 3 km/h).
 */
internal object Intervals3030Generator : ProgramGenerator {
    override val descriptor = GeneratorDescriptor(
        id = "intervals-30-30",
        version = 1,
        inputs = setOf(GeneratorInput.Mode, GeneratorInput.Series, GeneratorInput.WalkingSpeed),
        modes = setOf(ExerciseMode.Walk, ExerciseMode.Run),
        series = 6..30,
        defaultSeries = 12,
        heartRateGuided = false,
        usesHistory = false,
    )

    private const val INTENSITY = 1.025

    /** Incline limit of the walking set: both halves share it. */
    const val WALKING_MAX_INCLINE_PERCENT = 8.0

    override fun generate(request: GeneratorRequest): GeneratedProgram {
        val n = request.series ?: checkNotNull(descriptor.defaultSeries)
        require(n in checkNotNull(descriptor.series)) { "series must be within ${descriptor.series} (was $n)" }
        val g = Generation(request, descriptor, usesVo2max = request.mode == ExerciseMode.Walk, usesMas = request.mode == ExerciseMode.Run, usesMaxHeartRate = false)
        g.inputs["series"] = n.toString()
        val (fast, slow) = when (g.mode) {
            ExerciseMode.Run -> BlockTarget.Absolute(g.runSpeed(INTENSITY * g.factor * g.refs.masKmh), IntensityTranslator.RUNNING_INCLINE_PERCENT) to g.easy()
            ExerciseMode.Walk -> {
                val brisk = min(g.walkingSpeed + 1.5, max(g.walkingSpeed, IntensityTranslator.MAX_WALKING_SPEED_KMH))
                val t = g.walking(INTENSITY * g.factor * g.refs.vo2maxMlKgMin, brisk, key = "1.0", maxInclinePercent = WALKING_MAX_INCLINE_PERCENT)
                val incline = Generation.clampIncline(t.inclinePercent)
                BlockTarget.Absolute(Generation.clampSpeed(t.speedKmh), incline) to
                    BlockTarget.Absolute(Generation.clampSpeed(max(IntensityTranslator.MIN_WALKING_SPEED_KMH, g.walkingSpeed - 1.5)), incline)
            }
        }
        val steps = listOf(
            Generation.timed(BlockPhase.WarmUp, 600, g.easy()),
            Repeat(n, listOf(Generation.timed(BlockPhase.Work, 30, fast), Generation.timed(BlockPhase.Recovery, 30, slow))),
            Generation.timed(BlockPhase.CoolDown, 300, g.coolDown()),
        )
        return g.result(
            steps,
            defaultName = "Intervals 30/30 × $n",
            description = "10 min warm-up, $n × (30 s fast at 100-105 % of the maximal aerobic speed + 30 s easy), 5 min cool-down.",
        )
    }
}
