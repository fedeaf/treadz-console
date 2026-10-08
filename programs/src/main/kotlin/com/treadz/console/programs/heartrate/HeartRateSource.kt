package com.treadz.console.programs.heartrate

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
     * There is a value but the source cannot confirm it is real. This is the case of the heart rate relayed by the
     * treadmill console: it has no contact indicator and keeps reporting plausible values for 1-2 minutes after the
     * strap is taken off. Control code must treat it with care (plausibility checks, limited authority).
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
) {
    companion object {
        val NONE = HeartRateReading(null, HeartRateValidity.Invalid)
    }
}

/**
 * Where the program engine gets the heart rate from. The engine does not care whether it comes from the treadmill
 * console or from a strap connected directly to the app: a better source only has to implement this interface.
 */
interface HeartRateSource {
    /** The most recent reading. */
    fun latest(): HeartRateReading

    /** Readings as they change. */
    val readings: Flow<HeartRateReading>
}

/**
 * Heart rate relayed by the treadmill (FTMS Treadmill Data). 0 means no heart-rate monitor; any other value is only
 * [HeartRateValidity.Unconfirmed], because the console gives no contact indicator and keeps repeating plausible values
 * after the strap is removed. Data marked stale (connection lost or frozen) is [HeartRateValidity.Invalid].
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
                HeartRateReading(bpm, HeartRateValidity.Unconfirmed, state.updatedAtMillis)
            }
        }
    }
}
