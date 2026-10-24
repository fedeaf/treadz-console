package com.treadz.console.treadmill.events

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.CommandOrigin
import com.treadz.console.treadmill.control.SafetyClamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The load events of a **manual workout** (without a program or a fitness test): every change of the speed or the
 * incline is a [LoadEventType.ManualChange], whoever made it. The time of an event is the seconds since the start of the
 * workout ([start]).
 *
 * Two sources, both told apart from nothing else:
 *  - **the console's buttons**: the controller reports them as [TreadmillEvent.ManualChange], which this component
 *    collects between [start] and [stop];
 *  - **the app's own buttons**: the commands the user gives from the app. The app sends them through [setSpeed] and
 *    [setIncline] (they run the controller's verified command under [CommandOrigin.App] and record the change only when
 *    it succeeded), or reports one it sent itself with [onAppChange] after a successful result. The notification only has a
 *    STOP, which changes no load, so nothing else needs reporting.
 *
 * The app attaches it to a manual workout only: while a program or a fitness test runs, that runner keeps its own list
 * (the changes would be counted twice). Usage: [start] when the workout starts, [stop] when it ends (it returns the
 * final list); [events] is the list so far, to store during the workout. A value is the one the treadmill was asked
 * for (clamped like any command), the console's is the settled one the controller reports.
 *
 * Nothing here writes to the treadmill except the two pass-through commands, which are the controller's own.
 */
class ManualWorkoutEvents(
    private val controller: TreadmillController,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val log = LoadEventLog()

    /** The events so far (kept after [stop] until the next [start]). */
    val events: StateFlow<List<LoadEvent>> = log.events

    private val lock = Any()
    private var startedAt = 0L
    private var active = false
    private var job: Job? = null

    val isActive: Boolean get() = synchronized(lock) { active }

    /** A manual workout starts now (or at [startedAtMillis]): the list is emptied and the console's changes are collected. */
    fun start(startedAtMillis: Long = clock.nowMillis()) {
        synchronized(lock) {
            job?.cancel()
            log.clear()
            startedAt = startedAtMillis
            active = true
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.events.collect { e -> if (e is TreadmillEvent.ManualChange) record(e.speedKmh, e.inclinePercent) }
            }
        }
    }

    /** The workout ended: nothing is recorded any more. Returns the final list (also in [events]). */
    fun stop(): List<LoadEvent> {
        synchronized(lock) {
            active = false
            job?.cancel()
            job = null
        }
        return log.events.value
    }

    /** Sets the speed from the app (origin [CommandOrigin.App]) and records a manual change if the controller verified it. */
    suspend fun setSpeed(kmh: Double, origin: CommandOrigin = CommandOrigin.App): CommandResult {
        val result = withContext(origin) { controller.setSpeed(kmh) }
        if (result == CommandResult.Success) onAppChange(speedKmh = kmh, inclinePercent = null)
        return result
    }

    /** Sets the incline from the app (origin [CommandOrigin.App]) and records a manual change if the controller verified it. */
    suspend fun setIncline(percent: Double, origin: CommandOrigin = CommandOrigin.App): CommandResult {
        val result = withContext(origin) { controller.setIncline(percent) }
        if (result == CommandResult.Success) onAppChange(speedKmh = null, inclinePercent = percent)
        return result
    }

    /**
     * The app sent a speed and/or incline command itself and the controller verified it: records it (clamped like the
     * command was). Ignored while no workout is attached.
     */
    fun onAppChange(speedKmh: Double?, inclinePercent: Double?) {
        val caps = controller.capabilities.value
        val limits = controller.limits.value
        record(
            speedKmh?.let { SafetyClamp.speed(it, caps?.speed ?: SafetyClamp.DEFAULT_SPEED_RANGE, limits) },
            inclinePercent?.let { SafetyClamp.incline(it, caps?.incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE, limits) },
        )
    }

    private fun record(speedKmh: Double?, inclinePercent: Double?) {
        val seconds: Int
        synchronized(lock) {
            if (!active) return
            seconds = ((clock.nowMillis() - startedAt) / 1000L).toInt()
        }
        speedKmh?.let { log.add(seconds, LoadEventType.ManualChange, LoadQuantity.Speed, it) }
        inclinePercent?.let { log.add(seconds, LoadEventType.ManualChange, LoadQuantity.Incline, it) }
    }
}
