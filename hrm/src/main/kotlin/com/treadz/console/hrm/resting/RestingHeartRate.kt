package com.treadz.console.hrm.resting

import com.treadz.console.hrm.SensorContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One heart-rate sample for the resting measurement, from any source (the strap connected to the app or the treadmill
 * console). [bpm] `null` (or 0) means no reading. [rrIntervalsMs] are the RR intervals (ms between beats, oldest first)
 * that arrived with it: only a strap connected to the app provides them.
 */
data class RestingSample(
    val atMillis: Long,
    val bpm: Int?,
    val sensorContact: SensorContact = SensorContact.NotSupported,
    val rrIntervalsMs: List<Int> = emptyList(),
)

/** Rules of the resting heart-rate measurement. Defaults are the decided protocol. */
data class RestingHeartRateConfig(
    /** Total measurement: 3 minutes seated or lying. */
    val durationMs: Long = 180_000,
    /** The value is taken from this final part of the measurement (the first minute is for settling down). */
    val analysisMs: Long = 120_000,
    /** Length of the means compared: the value is the lowest 30-s mean. */
    val meanMs: Long = 30_000,
    /** A 30-s window counts only if at least this fraction of its seconds has a valid sample. */
    val minWindowCoverage: Double = 0.9,
    /** At least this fraction of the seconds of the analysed part must have a valid sample. */
    val minCoverage: Double = 0.9,
    /** No gap without a valid sample longer than this in the analysed part. */
    val maxGapMs: Long = 10_000,
    /** Plausible range of a single sample; outside it the sample is ignored. */
    val minPlausibleBpm: Int = 30,
    val maxPlausibleBpm: Int = 220,
    /** A sample more than this far from the previous valid one within [artifactWindowMs] is an artifact. */
    val maxJumpBpm: Int = 30,
    val artifactWindowMs: Long = 2_000,
    /** More than this fraction of artifacts or contact-lost samples in the analysed part: the readings are unreliable. */
    val maxBadFraction: Double = 0.1,
    /** A resting value outside this range is not a resting heart rate (the person was not at rest, or bad data). */
    val minRestingBpm: Int = 30,
    val maxRestingBpm: Int = 120,
    /** RR intervals outside this range (ms) are artifacts and left out of the RMSSD. */
    val minRrMs: Int = 300,
    val maxRrMs: Int = 2_000,
    /** An RR interval that differs from the previous accepted one by more than this fraction is an artifact or ectopic beat. */
    val maxRrChange: Double = 0.2,
    /** The RMSSD needs at least this many accepted RR intervals in the analysed part (about a minute at rest). */
    val minRrIntervals: Int = 60,
)

/** Why a resting measurement is not valid. */
enum class RestingInvalidReason {
    /** Ended before the 3 minutes. */
    TooShort,

    /** Too few valid samples, or a gap longer than 10 s, in the last 2 minutes. */
    MissingReadings,

    /** Too many artifacts (impossible jumps) or samples without skin contact. */
    UnreliableReadings,

    /** The lowest 30-s mean is outside 30-120 bpm. */
    ImplausibleValue,
}

/**
 * Result of a resting heart-rate measurement.
 *
 * @property bpm The resting heart rate (lowest 30-s mean of the last 2 minutes, rounded) when [valid].
 * @property lowestMeanBpm The unrounded lowest 30-s mean, when it could be computed (also for an invalid result, for
 *   information).
 * @property coverage Fraction of the seconds of the last 2 minutes with a valid sample (0-1).
 * @property longestGapSeconds Longest stretch without a valid sample in the last 2 minutes.
 * @property badSamples Artifacts and contact-lost samples in the last 2 minutes.
 * @property rmssdMs RMSSD (root mean square of successive differences of the RR intervals, ms) of the last 2 minutes,
 *   rounded to 0.1 ms: a measure of the heart-rate variability. `null` without enough clean RR intervals (no strap
 *   connected to the app, or too many artifacts); see [RestingHeartRateConfig.minRrIntervals].
 */
data class RestingHeartRateResult(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val valid: Boolean,
    val bpm: Int?,
    val lowestMeanBpm: Double?,
    val invalidReasons: List<RestingInvalidReason>,
    val coverage: Double,
    val longestGapSeconds: Int,
    val badSamples: Int,
    val samples: Int,
    val rmssdMs: Double? = null,
)

/**
 * Resting heart-rate measurement (pure logic on explicit times): 3 minutes seated or lying; the value is the
 * **lowest 30-second mean within the last 2 minutes**.
 *
 * Sample rules: a sample counts if it is within 30-220 bpm, the strap does not report a lost contact, and it is not an
 * artifact (more than 30 bpm away from the previous valid sample less than 2 s earlier). Samples are grouped by second
 * (repeated packets do not weigh more). A 30-s window counts when ≥ 90 % of its seconds have data.
 *
 * Validity (all must hold): the measurement lasted 3 minutes; ≥ 90 % of the seconds of the last 2 minutes have a valid
 * sample and no gap is longer than 10 s; at most 10 % of the samples of the last 2 minutes are artifacts or
 * contact-lost; the value is within 30-120 bpm.
 *
 * RMSSD: from the RR intervals of the last 2 minutes (not those of samples without skin contact). An interval outside
 * 300-2000 ms, or more than 20 % away from the previous accepted one, is left out, and so is the difference across it.
 * With fewer than 60 accepted intervals there is no RMSSD. It does not affect the validity of the measurement.
 */
class RestingHeartRateMeter(
    val startedAtMillis: Long,
    private val config: RestingHeartRateConfig = RestingHeartRateConfig(),
) {
    private class Entry(val t: Long, val bpm: Int?, val bad: Boolean)

    private val entries = ArrayList<Entry>()

    /** RR intervals with the time of the sample they came with. */
    private val rrIntervals = ArrayList<Pair<Long, Int>>()
    private var lastValidT: Long? = null
    private var lastValidBpm: Int? = null

    val endsAtMillis: Long get() = startedAtMillis + config.durationMs

    fun add(sample: RestingSample) {
        val t = sample.atMillis
        if (t < startedAtMillis || t > endsAtMillis) return
        if (entries.isNotEmpty() && t < entries.last().t) return
        val bpm = sample.bpm
        val noContact = sample.sensorContact == SensorContact.NotDetected
        if (!noContact) sample.rrIntervalsMs.forEach { rrIntervals += t to it }
        if (bpm == null || bpm <= 0) {
            entries += Entry(t, null, bad = noContact)
            return
        }
        val implausible = bpm < config.minPlausibleBpm || bpm > config.maxPlausibleBpm
        val prevT = lastValidT
        val prevBpm = lastValidBpm
        val artifact = prevT != null && prevBpm != null && t - prevT <= config.artifactWindowMs && abs(bpm - prevBpm) > config.maxJumpBpm
        if (noContact || implausible || artifact) {
            entries += Entry(t, null, bad = true)
            return
        }
        entries += Entry(t, bpm, bad = false)
        lastValidT = t
        lastValidBpm = bpm
    }

    /** Seconds left at [now]. */
    fun remainingSeconds(now: Long): Int = ceil((endsAtMillis - now).coerceAtLeast(0) / 1000.0).toInt()

    /** The current lowest 30-s mean of the analysed part (for a live display), or `null`. */
    fun currentLowestMean(now: Long): Double? = analyse(minOf(now, endsAtMillis)).lowest

    fun result(endedAt: Long): RestingHeartRateResult {
        val end = minOf(endedAt, endsAtMillis)
        val a = analyse(end)
        val reasons = mutableListOf<RestingInvalidReason>()
        if (endedAt < endsAtMillis) reasons += RestingInvalidReason.TooShort
        if (a.coverage < config.minCoverage || a.longestGapMs > config.maxGapMs) reasons += RestingInvalidReason.MissingReadings
        if (a.samples > 0 && a.bad.toDouble() / a.samples > config.maxBadFraction) reasons += RestingInvalidReason.UnreliableReadings
        val lowest = a.lowest
        if (lowest == null) {
            if (RestingInvalidReason.MissingReadings !in reasons) reasons += RestingInvalidReason.MissingReadings
        } else if (lowest < config.minRestingBpm || lowest > config.maxRestingBpm) {
            reasons += RestingInvalidReason.ImplausibleValue
        }
        val valid = reasons.isEmpty()
        return RestingHeartRateResult(
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAt,
            valid = valid,
            bpm = if (valid) lowest?.roundToInt() else null,
            lowestMeanBpm = lowest?.let { (it * 10).roundToInt() / 10.0 },
            invalidReasons = reasons,
            coverage = (a.coverage * 100).roundToInt() / 100.0,
            longestGapSeconds = (a.longestGapMs / 1000).toInt(),
            badSamples = a.bad,
            samples = a.samples,
            rmssdMs = rmssd(end)?.let { (it * 10).roundToInt() / 10.0 },
        )
    }

    /** RMSSD of the RR intervals of the analysed part (end − 2 min, end] (see the class description). */
    private fun rmssd(end: Long): Double? {
        val from = maxOf(startedAtMillis, end - config.analysisMs)
        var previous: Int? = null
        var accepted = 0
        var sumSquares = 0.0
        var differences = 0
        for ((t, rr) in rrIntervals) {
            if (t <= from || t > end) continue
            if (rr < config.minRrMs || rr > config.maxRrMs) {
                previous = null
                continue
            }
            val p = previous
            if (p != null && abs(rr - p) > config.maxRrChange * p) {
                previous = null
                continue
            }
            accepted++
            if (p != null) {
                val d = (rr - p).toDouble()
                sumSquares += d * d
                differences++
            }
            previous = rr
        }
        if (accepted < config.minRrIntervals || differences == 0) return null
        return sqrt(sumSquares / differences)
    }

    private class Analysis(val lowest: Double?, val coverage: Double, val longestGapMs: Long, val bad: Int, val samples: Int)

    /** The analysed part is (end − 2 min, end]. Per-second buckets: (from + i s, from + (i + 1) s]. */
    private fun analyse(end: Long): Analysis {
        val from = maxOf(startedAtMillis, end - config.analysisMs)
        val seconds = ((end - from) / 1000).toInt()
        if (seconds <= 0) return Analysis(null, 0.0, end - from, 0, 0)
        val sums = DoubleArray(seconds)
        val counts = IntArray(seconds)
        var bad = 0
        var samples = 0
        var lastValid = from
        var longestGap = 0L
        for (e in entries) {
            if (e.t <= from || e.t > end) continue
            samples++
            if (e.bad) bad++
            val v = e.bpm ?: continue
            longestGap = maxOf(longestGap, e.t - lastValid)
            lastValid = e.t
            val i = ((e.t - from - 1) / 1000).toInt().coerceIn(0, seconds - 1)
            sums[i] += v.toDouble()
            counts[i]++
        }
        longestGap = maxOf(longestGap, end - lastValid)
        val filled = counts.count { it > 0 }
        val window = (config.meanMs / 1000).toInt()
        var lowest: Double? = null
        if (seconds >= window) {
            for (start in 0..seconds - window) {
                var n = 0
                var sum = 0.0
                for (i in start until start + window) {
                    if (counts[i] > 0) {
                        n++
                        sum += sums[i] / counts[i]
                    }
                }
                if (n >= window * config.minWindowCoverage) {
                    val m = sum / n
                    if (lowest == null || m < lowest) lowest = m
                }
            }
        }
        return Analysis(lowest, filled.toDouble() / seconds, longestGap, bad, samples)
    }
}

/** What the UI shows while measuring the resting heart rate. */
data class RestingHeartRateProgress(
    val running: Boolean = false,
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    /** The last sample's heart rate, `null` without a reading. */
    val currentBpm: Int? = null,
    /** Lowest 30-s mean so far within the analysed part (`null` until there is one). */
    val lowestMeanBpm: Double? = null,
    /** Set when the measurement is over (completed or cancelled). */
    val result: RestingHeartRateResult? = null,
)

/**
 * Runs a resting measurement on a flow of samples: it starts at once, ends by itself after 3 minutes (the result is in
 * [progress]) or when [cancel] is called (result with [RestingInvalidReason.TooShort]). Times come from [now]; the
 * waits are coroutine delays (virtual time in tests).
 */
class RestingHeartRateSession(
    private val scope: CoroutineScope,
    private val samples: Flow<RestingSample>,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val config: RestingHeartRateConfig = RestingHeartRateConfig(),
) {
    private val _progress = MutableStateFlow(RestingHeartRateProgress())
    val progress: StateFlow<RestingHeartRateProgress> = _progress.asStateFlow()

    private val lock = Any()
    private var meter: RestingHeartRateMeter? = null
    private var lastBpm: Int? = null
    private var jobs = emptyList<Job>()

    /** Starts (or restarts) the measurement now. */
    fun start() {
        synchronized(lock) {
            jobs.forEach { it.cancel() }
            val m = RestingHeartRateMeter(now(), config)
            meter = m
            lastBpm = null
            publish(m)
            jobs = listOf(
                scope.launch {
                    samples.collect { s ->
                        synchronized(lock) {
                            if (meter !== m || _progress.value.result != null) return@collect
                            m.add(s)
                            lastBpm = s.bpm?.takeIf { it > 0 }
                            publish(m)
                        }
                    }
                },
                scope.launch {
                    while (isActive) {
                        val left = m.endsAtMillis - now()
                        if (left <= 0) break
                        delay(minOf(1_000L, left))
                        synchronized(lock) { if (meter === m && _progress.value.result == null) publish(m) }
                    }
                    finish(m, now())
                },
            )
        }
    }

    /** Ends the measurement early: the result is invalid ([RestingInvalidReason.TooShort]). */
    fun cancel() {
        val m = synchronized(lock) { meter } ?: return
        finish(m, now())
    }

    private fun finish(m: RestingHeartRateMeter, at: Long) {
        synchronized(lock) {
            if (meter !== m || _progress.value.result != null) return
            val r = m.result(at)
            _progress.value = _progress.value.copy(running = false, result = r, remainingSeconds = 0)
            jobs.forEach { it.cancel() }
        }
    }

    private fun publish(m: RestingHeartRateMeter) {
        val t = now()
        _progress.value = RestingHeartRateProgress(
            running = true,
            elapsedSeconds = ((t - m.startedAtMillis) / 1000).toInt().coerceAtLeast(0),
            remainingSeconds = m.remainingSeconds(t),
            currentBpm = lastBpm,
            lowestMeanBpm = m.currentLowestMean(t)?.let { (it * 10).roundToInt() / 10.0 },
        )
    }
}
