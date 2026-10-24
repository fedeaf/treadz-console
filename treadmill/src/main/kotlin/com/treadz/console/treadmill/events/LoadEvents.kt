package com.treadz.console.treadmill.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

/**
 * Kind of a [LoadEvent]: what changed the load of a workout. The meaning is the one of the data layer's
 * `WorkoutEventType` (which the app maps these to one to one); [code] is the stable name used by it.
 */
enum class LoadEventType(val code: String) {
    /** The user changed the speed or the incline (with the app or with the console: not told apart). */
    ManualChange("manual_change"),

    /** The heart-rate regulation, continuous or between repetitions, raised the load. */
    HeartRateIncrease("heart_rate_increase"),

    /** The heart-rate regulation, continuous or between repetitions, lowered the load. */
    HeartRateDecrease("heart_rate_decrease"),

    /** The real-time DFA-α1 brake lowered the load. */
    Alpha1Reduction("alpha1_reduction"),

    /** Safety reduction because the heart rate stayed above the ceiling of the zone (only when something was reduced). */
    CeilingReduction("ceiling_reduction"),

    /** The heart rate reached the maximum; [LoadEvent.quantity] and [LoadEvent.value] are the reduction made, if any. */
    MaxHeartRateReached("max_heart_rate_reached"),

    /** No heart rate for a while: back to the start value of the block. */
    NoHeartRateFallback("no_heart_rate_fallback"),

    /** The heart-rate control is active again: the user resumed it after a manual change, or the heart rate came back. */
    HeartRateControlResumed("heart_rate_control_resumed"),
}

/** What a [LoadEvent] changed. */
enum class LoadQuantity(val code: String) {
    /** Belt speed, in km/h. */
    Speed("speed"),

    /** Incline, in percent. */
    Incline("incline"),
}

/**
 * Something that changed the load of a workout, without reasoning and without intermediate data (no heart rate, no α1
 * value). A change of speed and incline at once is two events.
 *
 * @property seconds When it happened: the program clock for a program, the test clock for a fitness test, the seconds
 *   since the start of the workout for a manual workout ([com.treadz.console.treadmill.events.ManualWorkoutEvents]).
 * @property quantity What changed; `null` when the event changes nothing (the heart-rate control resumed, or the maximum
 *   heart rate was reached without any reduction).
 * @property value The new value of [quantity] (km/h or %); `null` when [quantity] is.
 */
data class LoadEvent(
    val seconds: Int,
    val type: LoadEventType,
    val quantity: LoadQuantity? = null,
    val value: Double? = null,
)

/**
 * The list of [LoadEvent]s of one workout, growing in order of arrival. Thread-safe. The runners and
 * [ManualWorkoutEvents] each own one and publish it as a [StateFlow], so the app can store the list during the workout
 * and at its end.
 */
class LoadEventLog {
    private val lock = Any()
    private val _events = MutableStateFlow<List<LoadEvent>>(emptyList())

    /** The events so far, oldest first; a new list at every change. */
    val events: StateFlow<List<LoadEvent>> = _events.asStateFlow()

    /** Forgets everything (a new workout starts). */
    fun clear() {
        synchronized(lock) { _events.value = emptyList() }
    }

    /**
     * Adds an event. [value] is rounded to 0.01 and a quantity without a (finite) value is dropped together with it. An
     * event identical to the previous one (same second, type, quantity and value) is a repetition of the same report and
     * is not added.
     */
    fun add(seconds: Int, type: LoadEventType, quantity: LoadQuantity? = null, value: Double? = null) = add(LoadEvent(seconds, type, quantity, value))

    /** Same as the other [add], for an event already built. */
    fun add(event: LoadEvent) {
        val (seconds, type, quantity, value) = event
        val v = value?.takeIf { it.isFinite() }?.let { (it * 100.0).roundToLong() / 100.0 }
        val event = if (quantity != null && v != null) LoadEvent(seconds.coerceAtLeast(0), type, quantity, v) else LoadEvent(seconds.coerceAtLeast(0), type)
        synchronized(lock) {
            val current = _events.value
            if (current.lastOrNull() == event) return
            _events.value = current + event
        }
    }
}
