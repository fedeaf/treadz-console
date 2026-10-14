package com.treadz.console.programs.heartrate

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How far a heart-rate reading can be trusted. */
enum class HeartRateValidity {
    /** The source confirms the sensor is in contact and the value is current (e.g. a strap read directly over BLE). */
    Valid,

    /**
     * There is a value but the source cannot confirm the sensor contact. This is the case of the heart rate relayed by
     * the treadmill console (no contact indicator; it keeps reporting plausible values for 1-2 minutes after the strap
     * is taken off) and of a strap that does not report its contact. The console's heart rate comes from a chest strap
     * and is **as reliable as a strap connected to the app** (decision of 2026-10-14): heart-rate control and the
     * tests treat [Valid] and [Unconfirmed] alike; the impossible-reading rules
     * ([HeartRatePlausibilityFilter]) catch a strap that was taken off.
     */
    Unconfirmed,

    /** No usable reading: no sensor, a zero, or data that is not current. */
    Invalid,
}

/** One heart-rate reading. [bpm] is `null` when [validity] is [HeartRateValidity.Invalid]. */
data class HeartRateReading(
    val bpm: Int?,
    val validity: HeartRateValidity,
    /** When the reading was taken, in epoch milliseconds (0 when unknown). */
    val atMillis: Long = 0,
    /**
     * Where it comes from: [HeartRateSourceKind.DirectStrap] (the strap connected to the app),
     * [HeartRateSourceKind.TreadmillConsole], or `null` when there is no heart-rate source at all.
     */
    val source: HeartRateSourceKind? = null,
    /**
     * RR intervals (ms between beats) that arrived with this reading, oldest first; only a strap connected to the app
     * provides them (the console does not). A reading is emitted for every strap notification, so collecting
     * [HeartRateSource.readings] gives every interval once.
     */
    val rrIntervalsMs: List<Int> = emptyList(),
) {
    companion object {
        val NONE = HeartRateReading(null, HeartRateValidity.Invalid)
    }
}

/**
 * Where the program engine gets the heart rate from. The engine does not care whether it comes from the treadmill
 * console or from a strap connected directly to the app: [AutomaticHeartRateSource] picks the best one available.
 */
interface HeartRateSource {
    /** The most recent reading. */
    fun latest(): HeartRateReading

    /** Readings as they change. */
    val readings: Flow<HeartRateReading>
}

/**
 * Heart rate relayed by the treadmill (FTMS Treadmill Data). 0 means no heart-rate monitor; any other value is
 * [HeartRateValidity.Unconfirmed] (the console gives no contact indicator) with source
 * [HeartRateSourceKind.TreadmillConsole]. Data marked stale (connection lost or frozen) is [HeartRateValidity.Invalid]
 * without a source.
 */
class ControllerHeartRateSource(private val controller: TreadmillController) : HeartRateSource {

    override fun latest(): HeartRateReading = map(controller.state.value)

    override val readings: Flow<HeartRateReading> = controller.state.map { map(it) }.distinctUntilChanged()

    companion object {
        fun map(state: TreadmillState): HeartRateReading {
            val bpm = state.heartRateBpm
            return if (bpm == null || bpm <= 0 || state.isStale) {
                HeartRateReading(null, HeartRateValidity.Invalid, state.updatedAtMillis)
            } else {
                HeartRateReading(bpm, HeartRateValidity.Unconfirmed, state.updatedAtMillis, HeartRateSourceKind.TreadmillConsole)
            }
        }
    }
}
