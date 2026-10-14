package com.treadz.console.programs.heartrate

import com.treadz.console.hrm.HeartRateMonitor
import com.treadz.console.hrm.HrmTraceFields
import com.treadz.console.hrm.SensorContact
import com.treadz.console.hrm.StrapConnectionState
import com.treadz.console.hrm.StrapReading
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerObserver
import com.treadz.console.treadmill.control.TraceRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The heart-rate source the app should use: it picks, automatically and without asking anything, **the strap
 * connected to the app** when there is one, else **the treadmill console** (a strap paired with the console), else
 * none. Every reading carries its [HeartRateReading.source] and, from the strap, its RR intervals.
 *
 * Rules:
 *  - While the strap is connected to the app it is the only source, even when its reading is not usable (contact lost:
 *    the reading is [HeartRateValidity.Invalid]). Falling back to the console then could pick up somebody else's strap
 *    paired with the console (two users), and the user's own strap cannot be on the console while the app holds it.
 *  - A strap reading with contact detected is [HeartRateValidity.Valid]; without contact support it is
 *    [HeartRateValidity.Unconfirmed]; 0 bpm or contact not detected is invalid.
 *  - The console reading is used as it is ([ControllerHeartRateSource]): 0 or stale data means no heart rate.
 *
 * Both sources are equally trusted (decision of 2026-10-14): consumers must not behave differently by source.
 *
 * [readings] emits every new reading (one per strap notification, so RR intervals are not lost or repeated; one per
 * console update). Changes of source are written to [trace] (the treadmill session recording) as `hr_source` lines.
 */
class AutomaticHeartRateSource(
    private val strap: HeartRateMonitor?,
    private val console: HeartRateSource,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val trace: ControllerObserver? = null,
) : HeartRateSource {

    private val lock = Any()
    private var lastEmitted: HeartRateReading? = null

    private val _readings = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 256)
    override val readings: Flow<HeartRateReading> = _readings.asSharedFlow()

    private val _source = MutableStateFlow(select().source)

    /** The source in use now (`null` = no heart rate), for the small icon next to the heart rate. */
    val source: StateFlow<HeartRateSourceKind?> = _source.asStateFlow()

    init {
        val triggers = listOfNotNull<Flow<Any?>>(strap?.latest, strap?.connection, console.readings)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { merge(*triggers.toTypedArray()).collect { update(null) } }
        // Every notification of the strap, in order (the state flows above may conflate two close notifications).
        if (strap != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { strap.readings.collect { update(it) } }
    }

    override fun latest(): HeartRateReading = select()

    private fun update(notification: StrapReading?) {
        synchronized(lock) {
            val strapConnected = strap?.connection?.value is StrapConnectionState.Connected
            val r = when {
                notification != null -> if (strapConnected) fromStrap(notification) else return
                else -> select()
            }
            if (r.source != _source.value) {
                val previous = _source.value
                _source.value = r.source
                trace?.onTrace(
                    TraceRecord.External(
                        clock.nowMillis(), "hr_source",
                        linkedMapOf(
                            "source" to r.source?.name,
                            "previous" to previous?.name,
                            "hr_bpm" to r.bpm,
                            "strap" to strap?.connection?.value?.let { HrmTraceFields.connectionFields(it) },
                        ),
                    ),
                )
            }
            val last = lastEmitted
            val emit = when {
                // A usable strap value is emitted once, from its notification (never re-emitted by other triggers).
                strapConnected && notification == null && r.bpm != null -> false
                // "No heart rate" is emitted once per source, not on every trigger.
                r.bpm == null -> last == null || last.bpm != null || last.source != r.source
                else -> r != last
            }
            if (emit) {
                lastEmitted = r
                _readings.tryEmit(r)
            }
        }
    }

    private fun select(): HeartRateReading {
        val s = strap
        if (s != null && s.connection.value is StrapConnectionState.Connected) {
            val r = s.latest.value ?: return HeartRateReading(null, HeartRateValidity.Invalid, clock.nowMillis(), HeartRateSourceKind.DirectStrap)
            return fromStrap(r)
        }
        val c = console.latest()
        return if (c.bpm == null || c.validity == HeartRateValidity.Invalid) {
            c.copy(bpm = null, validity = HeartRateValidity.Invalid, source = null)
        } else {
            c.copy(source = c.source ?: HeartRateSourceKind.TreadmillConsole)
        }
    }

    companion object {
        /** A strap reading as a [HeartRateReading]. */
        fun fromStrap(r: StrapReading): HeartRateReading = if (!r.isUsable) {
            HeartRateReading(null, HeartRateValidity.Invalid, r.atMillis, HeartRateSourceKind.DirectStrap)
        } else {
            HeartRateReading(
                bpm = r.bpm,
                validity = if (r.sensorContact == SensorContact.Detected) HeartRateValidity.Valid else HeartRateValidity.Unconfirmed,
                atMillis = r.atMillis,
                source = HeartRateSourceKind.DirectStrap,
                rrIntervalsMs = r.rrIntervalsMs,
            )
        }
    }
}
