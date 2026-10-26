package com.treadz.console.analysis.energy

import com.treadz.console.analysis.heartrate.StrapWindowJudge
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.AnalysisRules
import com.treadz.console.analysis.workout.FuelAnalysis
import com.treadz.console.analysis.workout.FuelMinute
import com.treadz.console.analysis.workout.FuelZone
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.WorkoutAnalyzer
import com.treadz.console.analysis.zones.ZoneSet
import kotlin.math.roundToInt

/**
 * What a [FuelTracker] has decided so far. The numbers cover the seconds already decided, which lag the latest sample by
 * up to one window ([FuelRules.windowSeconds]) plus the sample still open.
 *
 * @property fatShare Share (0-1) of the energy decided so far that came from fat; `null` before the first valid second.
 * @property fatKcal Gross kcal assigned to fat, [carbKcal] to carbohydrate.
 * @property movingSeconds Seconds with the belt moving decided so far; [validSeconds] of them with a valid strap heart
 *   rate (see [FuelTracker]).
 * @property validCoverage [validSeconds] / [movingSeconds]; `null` without moving seconds.
 * @property currentlyValid The latest decided second had a valid heart rate (the UI dims the figures when it did not).
 * @property showable The one place of the "show it" rule: there is a share and the valid heart rate covers at least
 *   [AnalysisRules.minHeartRateCoverage] of the moving seconds. The UI shows the split only when this is true.
 */
data class FuelSnapshot(
    val fatShare: Double?,
    val fatKcal: Double,
    val carbKcal: Double,
    val movingSeconds: Int,
    val validSeconds: Int,
    val validCoverage: Double?,
    val currentlyValid: Boolean,
    val showable: Boolean,
)

/**
 * Splits the energy of a workout between fat and carbohydrate as the samples arrive ([SubstrateModel] has the model).
 * The live display and the stored analysis use **the same tracker fed with the same samples one by one**, so they give
 * the same numbers: the batch path ([com.treadz.console.analysis.workout.FuelSection.compute]) is this class.
 *
 * **Grid**: the same 1 Hz grid as the workout timeline: a sample holds for at most [AnalysisRules.maxHoldSeconds], the
 * last one for the length of the interval before it, ticks are `ceil` of the offset from the first sample, and the belt
 * is moving when the speed is above 0. A sample is closed when the next one arrives.
 *
 * **Energy** of a moving second: [com.treadz.console.analysis.energy.EnergyExpenditure.kcalPerMinute] / 60; the total
 * is the gross kcal of the workout's energy section.
 *
 * **Valid heart rate** of a second: its sample has a heart rate within [AnalysisRules.minHeartRateBpm]..
 * [AnalysisRules.maxHeartRateBpm] from a strap connected to the app ([SampleHeartRateSource.Strap]), and the window that
 * contains the second is not doubtful for [StrapWindowJudge]. Windows are consecutive of [FuelRules.windowSeconds],
 * `(t0 + 10·w s, t0 + 10·(w+1) s]` from the first sample time `t0`; the second at tick `k` (instant `t0 + 1000·k`) is in
 * window `(k − 1)/10` (the tick 0 in window 0). The judge is fed every strap sample's reading and every sample's strap RR
 * intervals ([WorkoutSample.hasStrapRr], placed back from the sample time). **A window is judged once, when it has closed** (the first sample after its end has
 * arrived, with that sample's beats), with the data received up to then, so the artifact correction never sees later
 * beats and the live and the batch results are identical. A window "not judged" (fewer than 6 beats, no RR) counts as
 * valid. Seconds wait in a queue until their window is judged: this is the lag of the snapshot.
 *
 * **Model heart rate** of a valid second: the mean of the heart rates of the valid moving seconds in the last
 * [FuelRules.smoothingSeconds] seconds (that second included). Seconds with the belt stopped neither count as valid nor
 * enter the mean (a paused belt has a recovering heart rate that says nothing about the work). The duration term is
 * the moving time before the second.
 *
 * **Seconds without a valid heart rate** take the last valid share; the moving seconds before the first valid one wait
 * with their energy unassigned and take the first valid share when it arrives (so the final numbers are the same as
 * if the first share had been known from the start).
 *
 * Samples must come in time order (one out of order is ignored). Not thread-safe: the caller guards it.
 *
 * @param zones The zone set for the per-zone totals of [finish] (zone of the model heart rate); `null` = none.
 */
class FuelTracker(
    val anchors: FuelAnchors,
    weightKg: Double,
    private val rules: FuelRules = FuelRules(),
    private val analysisRules: AnalysisRules = AnalysisRules(),
    private val zones: ZoneSet? = null,
) {
    init {
        require(weightKg > 0.0) { "The weight must be positive" }
        require(anchors.increasing) { "The anchors must be strictly increasing: $anchors" }
    }

    private val weight = weightKg

    private class Tick(
        val k: Int,
        /** The window of this second. */
        val window: Int,
        val moving: Boolean,
        val speedKmh: Double,
        val inclinePercent: Double,
        /** The strap heart rate if the reading is usable (a strap, in range), else 0. */
        val strapBpm: Int,
    )

    private class Reading(val k: Int, val bpm: Int)

    private class Leading(val minute: Int, val kcal: Double)

    // ---- the samples
    private var sampleCount = 0
    private var t0 = 0L
    private var lastTimeMillis = 0L
    private var pending: WorkoutSample? = null
    private var lastIntervalMillis = 0L
    private var implausible = false
    private var finished = false
    private var result: Section<FuelAnalysis>? = null

    // ---- the windows
    private val judge = StrapWindowJudge(rules.strapWindow)
    private val queue = ArrayDeque<Tick>()
    private var judgedWindow = -1
    private var judgedDoubtful = false

    // ---- the decided seconds
    private var movingSeconds = 0
    private var validSeconds = 0
    private var currentlyValid = false
    private var fatKcal = 0.0
    private var carbKcal = 0.0
    private var firstMovingTick = -1
    private var lastShare: Double? = null
    private val recent = ArrayDeque<Reading>()
    private var recentSum = 0L
    private val leading = ArrayList<Leading>()
    private val minuteFat = ArrayList<Double>()
    private val minuteCarb = ArrayList<Double>()
    private val zoneSeconds = IntArray(zones?.zones?.size ?: 0)
    private val zoneFat = DoubleArray(zoneSeconds.size)
    private val zoneCarb = DoubleArray(zoneSeconds.size)

    /** Adds the next sample (time order). */
    fun add(sample: WorkoutSample) {
        if (finished || implausible) return
        if (sampleCount > 0 && sample.timeMillis < lastTimeMillis) return
        if (sampleCount == 0) {
            t0 = sample.timeMillis
        } else if (sample.timeMillis - t0 > analysisRules.maxSpanSeconds * 1000L) {
            implausible = true
            return
        }
        if (sample.heartRateSource == SampleHeartRateSource.Strap) sample.heartRateBpm?.takeIf { it > 0 }?.let { judge.addReading(sample.timeMillis, it) }
        if (sample.hasStrapRr) judge.addRr(sample.timeMillis, sample.rrIntervalsMs)
        pending?.let { previous ->
            val duration = minOf(sample.timeMillis - previous.timeMillis, analysisRules.maxHoldSeconds * 1000L)
            lastIntervalMillis = duration
            enqueue(previous, duration)
        }
        pending = sample
        sampleCount++
        lastTimeMillis = sample.timeMillis
        decideClosedWindows(sample.timeMillis)
    }

    /** What has been decided so far. */
    fun snapshot(): FuelSnapshot {
        val total = fatKcal + carbKcal
        val share = if (lastShare != null && total > 0.0) fatKcal / total else null
        return FuelSnapshot(
            fatShare = share,
            fatKcal = fatKcal,
            carbKcal = carbKcal,
            movingSeconds = movingSeconds,
            validSeconds = validSeconds,
            validCoverage = if (movingSeconds > 0) validSeconds.toDouble() / movingSeconds else null,
            currentlyValid = currentlyValid,
            showable = share != null && coverageEnough(),
        )
    }

    /**
     * Closes the last sample and the last window and returns the section: the totals, the split by zone and by minute and
     * the anchors, or why there is no split ([AnalysisReason.NoSamples], [AnalysisReason.ImplausibleDuration],
     * [AnalysisReason.NoBeltMovement], [AnalysisReason.NoReliableStrapHeartRate]). Later calls return the same result and
     * later samples are ignored.
     */
    fun finish(): Section<FuelAnalysis> {
        result?.let { return it }
        finished = true
        val section = build()
        result = section
        return section
    }

    private fun build(): Section<FuelAnalysis> {
        if (implausible) return Section.missing(AnalysisReason.ImplausibleDuration)
        if (sampleCount < 2) return Section.missing(AnalysisReason.NoSamples)
        val last = checkNotNull(pending)
        val endMillis = last.timeMillis + lastIntervalMillis
        if (endMillis - t0 > analysisRules.maxSpanSeconds * 1000L) return Section.missing(AnalysisReason.ImplausibleDuration)
        if (endMillis <= t0) return Section.missing(AnalysisReason.NoSamples)
        enqueue(last, lastIntervalMillis)
        pending = null
        decideQueue { true }
        if (movingSeconds == 0) return Section.missing(AnalysisReason.NoBeltMovement)
        if (!coverageEnough()) return Section.missing(AnalysisReason.NoReliableStrapHeartRate)
        val minutes = (0 until minuteFat.size).map { j ->
            FuelMinute(startMillis = t0 + 1000L * (firstMovingTick + 60L * j), fatKcal = minuteFat[j], carbKcal = minuteCarb[j])
        }
        val byZone = zones?.zones?.mapIndexed { i, zone -> FuelZone(zone.number, zoneSeconds[i], zoneFat[i], zoneCarb[i]) } ?: emptyList()
        val total = fatKcal + carbKcal
        return Section.of(
            FuelAnalysis(
                fatKcal = fatKcal,
                carbKcal = carbKcal,
                fatShare = if (total > 0.0) fatKcal / total else 0.0,
                movingSeconds = movingSeconds,
                validSeconds = validSeconds,
                validCoverage = validSeconds.toDouble() / movingSeconds,
                byZone = byZone,
                minutes = minutes,
                anchors = anchors,
            ),
        )
    }

    private fun coverageEnough(): Boolean =
        movingSeconds > 0 && validSeconds >= analysisRules.minHeartRateCoverage * movingSeconds - 1e-9

    // ------------------------------------------------------------------ the grid

    /** Puts the seconds of [sample], held for [durationMillis], in the queue (the grid of the workout timeline). */
    private fun enqueue(sample: WorkoutSample, durationMillis: Long) {
        if (durationMillis <= 0L) return
        val from = ceilSeconds(sample.timeMillis - t0)
        val to = ceilSeconds(sample.timeMillis + durationMillis - t0)
        val bpm = sample.heartRateBpm
            ?.takeIf { it in analysisRules.minHeartRateBpm..analysisRules.maxHeartRateBpm && sample.heartRateSource == SampleHeartRateSource.Strap }
            ?: 0
        val moving = sample.speedKmh > 0.0
        for (k in from until to) {
            val window = if (k <= 0) 0 else (k - 1) / rules.windowSeconds
            queue.addLast(Tick(k, window, moving, sample.speedKmh, sample.inclinePercent, bpm))
        }
    }

    /** Decides the seconds of every window that has closed when a sample at [timeMillis] arrives. */
    private fun decideClosedWindows(timeMillis: Long) {
        val windowMillis = rules.windowSeconds * 1000L
        // Window w ends at t0 + windowMillis·(w + 1); it has closed when a sample arrives after that.
        val closedUpTo = Math.floorDiv(timeMillis - t0 + windowMillis - 1, windowMillis).toInt() - 2
        if (closedUpTo < 0) return
        decideQueue { it.window <= closedUpTo }
        judge.trimBefore(t0 + windowMillis * (closedUpTo + 1) - rules.judgeContextSeconds * 1000L)
    }

    private inline fun decideQueue(ready: (Tick) -> Boolean) {
        while (queue.isNotEmpty() && ready(queue.first())) {
            val tick = queue.removeFirst()
            decide(tick, tick.strapBpm > 0 && doubtful(tick.window))
        }
    }

    /** Whether window [w] is doubtful; judged the first time it is asked for, with the data received so far. */
    private fun doubtful(w: Int): Boolean {
        if (w != judgedWindow) {
            val windowMillis = rules.windowSeconds * 1000L
            judgedDoubtful = judge.judge(t0 + windowMillis * w, t0 + windowMillis * (w + 1)).isDoubtful
            judgedWindow = w
        }
        return judgedDoubtful
    }

    // ------------------------------------------------------------------ the split

    private fun decide(tick: Tick, doubtful: Boolean) {
        val valid = tick.strapBpm > 0 && !doubtful
        currentlyValid = valid
        if (!tick.moving) return
        if (firstMovingTick < 0) firstMovingTick = tick.k
        val minute = (tick.k - firstMovingTick) / 60
        val movedMinutes = movingSeconds / 60.0
        movingSeconds++
        val kcal = EnergyExpenditure.kcalPerMinute(tick.speedKmh, tick.inclinePercent, weight) / 60.0
        if (!valid) {
            val share = lastShare
            if (share == null) leading += Leading(minute, kcal) else assign(minute, kcal, share)
            return
        }
        validSeconds++
        recent.addLast(Reading(tick.k, tick.strapBpm))
        recentSum += tick.strapBpm
        while (recent.first().k <= tick.k - rules.smoothingSeconds) recentSum -= recent.removeFirst().bpm
        val modelBpm = recentSum.toDouble() / recent.size
        val share = SubstrateModel.fatShare(SubstrateModel.rer(modelBpm, anchors, movedMinutes, rules), rules)
        if (lastShare == null) {
            for (second in leading) assign(second.minute, second.kcal, share)
            leading.clear()
        }
        lastShare = share
        assign(minute, kcal, share)
        zones?.let { set ->
            val z = WorkoutAnalyzer.zoneIndex(set, modelBpm.roundToInt())
            zoneSeconds[z]++
            zoneFat[z] += kcal * share
            zoneCarb[z] += kcal * (1.0 - share)
        }
    }

    private fun assign(minute: Int, kcal: Double, share: Double) {
        val fat = kcal * share
        val carb = kcal - fat
        fatKcal += fat
        carbKcal += carb
        while (minuteFat.size <= minute) {
            minuteFat += 0.0
            minuteCarb += 0.0
        }
        minuteFat[minute] += fat
        minuteCarb[minute] += carb
    }

    private companion object {
        fun ceilSeconds(millis: Long): Int = Math.floorDiv(millis + 999L, 1000L).toInt()
    }
}
