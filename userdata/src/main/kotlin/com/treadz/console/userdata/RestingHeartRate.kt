package com.treadz.console.userdata

import com.treadz.console.hrm.resting.RestingHeartRateResult
import kotlinx.coroutines.flow.Flow

/** Where a resting heart rate measurement came from. Stable codes (stored in the database, `manual`/`strap`/`console` in the repository). */
enum class RestingHeartRateSource {
    /** Typed in by the user. */
    Manual,

    /** Measured with a strap connected to the app. */
    Strap,

    /** Measured with the strap paired to the treadmill console. */
    Console,
}

/**
 * One resting heart rate measurement. Only valid measurements are stored (see [RestingHeartRateRepository.add]); a value
 * typed by the user is one more measurement ([RestingHeartRateSource.Manual]).
 */
data class RestingHeartRateMeasurement(
    val id: Long,
    val userId: Long,
    /** When it was taken, epoch ms. */
    val measuredAtMillis: Long,
    val bpm: Int,
    val source: RestingHeartRateSource,
    /** Details of an automatic measurement, when available; `null` for a typed value. */
    val lowestMeanBpm: Double? = null,
    /** Fraction (0-1) of the analysed seconds that had a valid reading. */
    val coverage: Double? = null,
    /** RMSSD of the RR intervals in milliseconds; `null` when it was not computed (no strap with RR intervals). */
    val rmssdMs: Double? = null,
)

/** The data of a measurement to store or to edit. */
data class RestingHeartRateInput(
    val measuredAtMillis: Long,
    val bpm: Int,
    val source: RestingHeartRateSource,
    val lowestMeanBpm: Double? = null,
    val coverage: Double? = null,
    val rmssdMs: Double? = null,
) {
    companion object {
        /**
         * The input for a finished automatic measurement, or `null` when the measurement is not valid (it is not stored).
         * Its time is the end of the measurement.
         */
        fun fromResult(result: RestingHeartRateResult, source: RestingHeartRateSource, rmssdMs: Double? = null): RestingHeartRateInput? {
            val bpm = result.bpm
            if (!result.valid || bpm == null) return null
            return RestingHeartRateInput(
                measuredAtMillis = result.endedAtMillis,
                bpm = bpm,
                source = source,
                lowestMeanBpm = result.lowestMeanBpm,
                coverage = result.coverage,
                rmssdMs = rmssdMs,
            )
        }
    }
}

/** How firm a [RestingHeartRateReference] is. */
enum class ReferenceStatus {
    /** The median of at least [RestingHeartRates.MIN_FOR_ESTABLISHED] measurements of the last 30 days. */
    Established,

    /** The median of only one or two measurements of the last 30 days. */
    Provisional,

    /** No measurement in the last 30 days: this is the latest one, older than that. */
    Stale,
}

/**
 * The resting heart rate to use (zones, estimates): see [RestingHeartRates.reference].
 *
 * @property measurementsCount How many measurements it is based on (1 for [ReferenceStatus.Stale]).
 * @property latestAtMillis Time of the newest measurement it is based on.
 */
data class RestingHeartRateReference(
    val bpm: Int,
    val status: ReferenceStatus,
    val measurementsCount: Int,
    val latestAtMillis: Long,
)

/**
 * Whether a measurement is clearly above the reference computed without it.
 *
 * @property referenceBpm The reference without the measurement, `null` when there is nothing to compare with.
 * @property deltaBpm The measurement minus the reference.
 * @property isElevated [deltaBpm] is at least [RestingHeartRates.ELEVATED_THRESHOLD_BPM]. The UI shows a non-blocking
 *   warning ("tired or ill? take it easy").
 */
data class RestingHeartRateAssessment(
    val measurementBpm: Int,
    val referenceBpm: Int?,
    val referenceStatus: ReferenceStatus?,
    val deltaBpm: Int?,
    val isElevated: Boolean,
)

/** A measurement reduced to what the reference needs. */
data class RestingPoint(val atMillis: Long, val bpm: Int)

/** Pure rules of the resting heart rate history, shared by every implementation. */
object RestingHeartRates {
    /** The reference is the median of the measurements of this many days. */
    const val REFERENCE_DAYS = 30

    /** With fewer measurements than this in the window the reference is [ReferenceStatus.Provisional]. */
    const val MIN_FOR_ESTABLISHED = 3

    /** A measurement this many bpm (or more) above the reference is elevated. */
    const val ELEVATED_THRESHOLD_BPM = 7

    private const val DAY_MS = 86_400_000L

    /**
     * The reference at [nowMillis]:
     *  - the **median** of the measurements of the last 30 days (`[now - 30 days, now]`; the median of an even number
     *    is the mean of the two middle values, rounded half up); [ReferenceStatus.Provisional] with fewer than 3,
     *    [ReferenceStatus.Established] otherwise;
     *  - with none in those 30 days, the **latest** measurement, marked [ReferenceStatus.Stale];
     *  - `null` without any measurement.
     * Measurements later than [nowMillis] are ignored unless there are no others at all.
     */
    fun reference(points: List<RestingPoint>, nowMillis: Long): RestingHeartRateReference? {
        if (points.isEmpty()) return null
        val past = points.filter { it.atMillis <= nowMillis }
        val window = past.filter { it.atMillis >= nowMillis - REFERENCE_DAYS * DAY_MS }
        if (window.isNotEmpty()) {
            val sorted = window.map { it.bpm }.sorted()
            val n = sorted.size
            val median = if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
            return RestingHeartRateReference(
                bpm = median.roundHalfUp(),
                status = if (n >= MIN_FOR_ESTABLISHED) ReferenceStatus.Established else ReferenceStatus.Provisional,
                measurementsCount = n,
                latestAtMillis = window.maxOf { it.atMillis },
            )
        }
        val latest = (past.ifEmpty { points }).maxBy { it.atMillis }
        return RestingHeartRateReference(latest.bpm, ReferenceStatus.Stale, 1, latest.atMillis)
    }

    fun referenceOf(measurements: List<RestingHeartRateMeasurement>, nowMillis: Long): RestingHeartRateReference? =
        reference(measurements.map { RestingPoint(it.measuredAtMillis, it.bpm) }, nowMillis)

    /**
     * The reference as written to the synchronised `profile.json` for people to read: computed as of the newest
     * measurement, so it depends only on synchronised data (never on the clock of the device that writes it). `null`
     * without measurements.
     */
    fun snapshotBpm(points: List<RestingPoint>): Int? =
        if (points.isEmpty()) null else reference(points, points.maxOf { it.atMillis })?.bpm

    /**
     * Compares a measurement with the reference computed **without it**, as of its own time: the other measurements of
     * the 30 days before it. Elevated means at least [ELEVATED_THRESHOLD_BPM] above that reference. A [ReferenceStatus.Stale]
     * reference (nothing in the 30 days before) is not compared: there is no recent baseline.
     *
     * @param measurement The measurement to assess (it may or may not be part of [others]; it is left out by [id]).
     */
    fun assess(measurement: RestingHeartRateMeasurement, others: List<RestingHeartRateMeasurement>): RestingHeartRateAssessment {
        val reference = reference(
            others.filter { it.id != measurement.id && it.measuredAtMillis < measurement.measuredAtMillis }
                .map { RestingPoint(it.measuredAtMillis, it.bpm) },
            measurement.measuredAtMillis,
        )
        val comparable = reference?.takeIf { it.status != ReferenceStatus.Stale }
        val delta = comparable?.let { measurement.bpm - it.bpm }
        return RestingHeartRateAssessment(
            measurementBpm = measurement.bpm,
            referenceBpm = comparable?.bpm,
            referenceStatus = comparable?.status,
            deltaBpm = delta,
            isElevated = delta != null && delta >= ELEVATED_THRESHOLD_BPM,
        )
    }

    /** `true` when [bpm] measured at [atMillis] is elevated with respect to [others] (see [assess]). */
    fun isElevated(bpm: Int, atMillis: Long, others: List<RestingHeartRateMeasurement>): Boolean =
        assess(RestingHeartRateMeasurement(Long.MIN_VALUE, 0, atMillis, bpm, RestingHeartRateSource.Manual), others).isElevated

    /**
     * The id of the manual measurement that stands for the resting heart rate a profile had before the history existed
     * (schema 5 migration, and profile files written before it). It is a function of the user's id, so every device
     * that converts the same profile creates the very same record and they never duplicate it.
     */
    fun legacyMeasurementUuid(userUuid: String): String = "resting-$userUuid"

    private fun Double.roundHalfUp(): Int = kotlin.math.floor(this + 0.5).toInt()

    /** Problems of a measurement to store, with the stable codes of [ValidationCode] on [InputField.RestingHeartRate]. */
    internal fun validate(input: RestingHeartRateInput, nowMillis: Long): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun add(code: ValidationCode) {
            val issue = ValidationIssue(InputField.RestingHeartRate, code)
            if (issue !in issues) issues += issue
        }
        if (input.bpm !in UserDataLimits.RESTING_HEART_RATE_BPM) add(ValidationCode.OutOfRange)
        if (input.measuredAtMillis > nowMillis + FUTURE_TOLERANCE_MS) add(ValidationCode.InFuture)
        if (input.measuredAtMillis < EARLIEST_MILLIS) add(ValidationCode.OutOfRange)
        input.lowestMeanBpm?.let { if (!it.isFinite() || it !in 20.0..220.0) add(ValidationCode.OutOfRange) }
        input.coverage?.let { if (!it.isFinite() || it !in 0.0..1.0) add(ValidationCode.OutOfRange) }
        input.rmssdMs?.let { if (!it.isFinite() || it < 0.0 || it > MAX_RMSSD_MS) add(ValidationCode.OutOfRange) }
        return issues
    }

    private const val FUTURE_TOLERANCE_MS = 5 * 60_000L
    private const val EARLIEST_MILLIS = -2_208_988_800_000L // 1900-01-01
    private const val MAX_RMSSD_MS = 1_000.0
}

/**
 * The resting heart rate history of each user. It is the source of truth of the resting heart rate: the profile's
 * [UserProfile.restingHeartRateBpm] is its [reference]. Measurements are synchronised
 * (`users/<name>-<uuid>/resting-heart-rate.csv`) with the same rules as the weight entries: last edit wins, deletions
 * are tombstones.
 */
interface RestingHeartRateRepository {

    /** The measurements of the user, oldest first (a chart's order); a measurement of the same instant goes by creation. Empty for an unknown user. */
    fun measurements(userId: Long): Flow<List<RestingHeartRateMeasurement>>

    /**
     * The reference ([RestingHeartRates.reference]) at the current time, `null` while the user has no measurement.
     * Recomputed when the data changes, not as time passes.
     */
    fun reference(userId: Long): Flow<RestingHeartRateReference?>

    /**
     * Stores a measurement. Problems are reported on [InputField.RestingHeartRate]: [ValidationCode.OutOfRange] (bpm outside
     * [UserDataLimits.RESTING_HEART_RATE_BPM], impossible details or a time before 1900) and [ValidationCode.InFuture]
     * (later than five minutes from now). Only valid measurements are stored: for the result of a measurement session
     * use [RestingHeartRateInput.fromResult], which gives `null` for an invalid one. [DataResult.NotFound] if the user
     * does not exist.
     */
    suspend fun add(userId: Long, input: RestingHeartRateInput): DataResult<RestingHeartRateMeasurement>

    /** Replaces the data of a measurement (same validation as [add]). [DataResult.NotFound] if it does not exist. */
    suspend fun update(measurementId: Long, input: RestingHeartRateInput): DataResult<RestingHeartRateMeasurement>

    suspend fun delete(measurementId: Long): DataResult<Unit>

    /**
     * Whether the measurement is elevated with respect to the reference computed without it ([RestingHeartRates.assess]);
     * `null` if it does not exist. Call it right after [add] to decide whether to show the non-blocking warning.
     */
    suspend fun assessment(measurementId: Long): RestingHeartRateAssessment?
}
