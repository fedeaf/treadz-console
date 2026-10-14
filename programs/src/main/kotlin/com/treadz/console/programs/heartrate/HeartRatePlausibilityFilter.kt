package com.treadz.console.programs.heartrate

import kotlin.math.abs

/** Thresholds of [HeartRatePlausibilityFilter] (decided 2026-10-14). */
data class PlausibilityConfig(
    /** Below this it is not a heart rate. */
    val minBpm: Int = 40,
    /** Above the user's maximum by more than this it is not a heart rate (`null` disables the rule). */
    val maxAboveHrMaxBpm: Int? = 15,
    /** Absolute ceiling, whatever the user's maximum. */
    val absoluteMaxBpm: Int = 240,
    /** A change of more than [maxJumpBpm] within [jumpWindowMs] is impossible. */
    val maxJumpBpm: Int = 30,
    val jumpWindowMs: Long = 1_500,
    /** A fall of more than [maxDropBpm] below the highest accepted value of the last [dropWindowMs] is impossible. */
    val maxDropBpm: Int = 25,
    val dropWindowMs: Long = 5_000,
    /** After an impossible drop, the reference level is the mean of the accepted values of this period before it. */
    val referenceWindowMs: Long = 10_000,
)

/**
 * Rejects impossible heart-rate readings, which are then treated as "no heart rate" (decision of 2026-10-14):
 *  - below 40 bpm, above the user's maximum + 15 (or 240);
 *  - a jump of more than 30 bpm within ~1 s of the previous accepted reading (a single spike is dropped; a lasting
 *    change is accepted once the previous accepted reading is older than the window);
 *  - **a drop of more than 25 bpm within 5 s**: what the console reports when the strap is taken off (it falls to
 *    73-80 and keeps showing plausible values for 1-2 min). After such a drop the filter stays **suspicious**: every
 *    reading is rejected until (a) one comes back within 25 bpm of the level before the drop (it was a glitch), or
 *    (b) the reading rises by more than 25 bpm within 5 s from the lowest suspicious value (the strap was put back on),
 *    or (c) the source reports no heart rate at all (`null`: the strap went off, the series starts again).
 *
 * Time-based (packets arrive at ~2 Hz with each value repeated). Not thread-safe.
 */
class HeartRatePlausibilityFilter(
    private val maxHeartRateBpm: Int?,
    private val config: PlausibilityConfig = PlausibilityConfig(),
) {
    private val accepted = ArrayDeque<Pair<Long, Int>>()
    private val suspicious = ArrayDeque<Pair<Long, Int>>()
    private var lastAccepted: Pair<Long, Int>? = null
    private var reference: Double? = null

    /** True after an impossible drop, until the readings are trusted again. */
    val isSuspicious: Boolean get() = reference != null

    /** Why the last reading was rejected (for logs and tests), `null` when it was accepted. */
    var lastRejection: String? = null
        private set

    /** Returns [bpm] when plausible, else `null`. [bpm] `null` (no reading) resets the series. */
    fun accept(t: Long, bpm: Int?): Int? {
        if (bpm == null || bpm <= 0) {
            reset()
            lastRejection = "no reading"
            return null
        }
        val maxAllowed = minOf(config.absoluteMaxBpm, maxHeartRateBpm?.let { m -> config.maxAboveHrMaxBpm?.let { m + it } } ?: Int.MAX_VALUE)
        if (bpm < config.minBpm) return reject("below ${config.minBpm}")
        if (bpm > maxAllowed) return reject("above $maxAllowed")

        val ref = reference
        if (ref != null) {
            suspicious.addLast(t to bpm)
            while (suspicious.isNotEmpty() && suspicious.first().first < t - config.dropWindowMs) suspicious.removeFirst()
            val lowest = suspicious.minOf { it.second }
            val backToLevel = bpm >= ref - config.maxDropBpm
            val strapBack = bpm - lowest > config.maxDropBpm
            if (!backToLevel && !strapBack) return reject("suspicious since an impossible drop (level ${ref.toInt()})")
            reference = null
            suspicious.clear()
            accepted.clear()
            return acceptNow(t, bpm)
        }

        val last = lastAccepted
        if (last != null && t - last.first <= config.jumpWindowMs && abs(bpm - last.second) > config.maxJumpBpm) {
            return reject("jump of ${bpm - last.second} bpm in ${t - last.first} ms")
        }
        while (accepted.isNotEmpty() && accepted.first().first < t - config.referenceWindowMs) accepted.removeFirst()
        val recentMax = accepted.filter { it.first >= t - config.dropWindowMs }.maxOfOrNull { it.second }
        if (recentMax != null && recentMax - bpm > config.maxDropBpm) {
            reference = accepted.map { it.second }.average()
            suspicious.clear()
            suspicious.addLast(t to bpm)
            return reject("drop of ${recentMax - bpm} bpm within ${config.dropWindowMs} ms")
        }
        return acceptNow(t, bpm)
    }

    private fun acceptNow(t: Long, bpm: Int): Int {
        accepted.addLast(t to bpm)
        lastAccepted = t to bpm
        lastRejection = null
        return bpm
    }

    private fun reject(why: String): Int? {
        lastRejection = why
        return null
    }

    fun reset() {
        accepted.clear()
        suspicious.clear()
        lastAccepted = null
        reference = null
    }
}
