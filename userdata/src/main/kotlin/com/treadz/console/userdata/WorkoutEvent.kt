package com.treadz.console.userdata

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Kind of a [WorkoutEvent]. Stable [code]s: they are stored and written to the data repository. */
enum class WorkoutEventType(val code: String) {
    /** The user changed the speed or the incline (with the app or the console: not told apart). */
    ManualChange("manual_change"),

    /** The heart rate regulation, continuous or between repetitions, raised the load. */
    HeartRateIncrease("heart_rate_increase"),

    /** The heart rate regulation, continuous or between repetitions, lowered the load. */
    HeartRateDecrease("heart_rate_decrease"),

    /** The real-time DFA-alpha1 brake lowered the load. */
    Alpha1Reduction("alpha1_reduction"),

    /** Safety reduction because the heart rate stayed above the ceiling of the zone. */
    CeilingReduction("ceiling_reduction"),

    /** The heart rate reached the maximum; [WorkoutEvent.quantity] and [WorkoutEvent.value] are the reduction made, if any. */
    MaxHeartRateReached("max_heart_rate_reached"),

    /** No heart rate for a while: back to the start value of the block. */
    NoHeartRateFallback("no_heart_rate_fallback"),

    /** The heart rate control is active again: the user resumed it after a manual change, or it resumed by itself when the heart rate came back. */
    HeartRateControlResumed("heart_rate_control_resumed"),

    /**
     * A block of the program started ([WorkoutEvent.block] is its index in the plan): at its natural end, or because the
     * user skipped forward or back or restarted it. The first block starts at 0 without an event. Programs only; it places
     * the plan on the real clock of the workout.
     */
    BlockStarted("block_started"),
    ;

    companion object {
        fun ofCode(code: String?): WorkoutEventType? = entries.firstOrNull { it.code == code }
    }
}

/** What a [WorkoutEvent] changed. Stable [code]s. */
enum class WorkoutEventQuantity(val code: String) {
    /** Belt speed, in km/h. */
    Speed("speed"),

    /** Incline, in percent. */
    Incline("incline"),
    ;

    companion object {
        fun ofCode(code: String?): WorkoutEventQuantity? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Something that changed the load during a workout, without reasoning or intermediate data (no heart rate, no DFA-alpha1
 * values). Every kind of workout can have them: free, standard and personalised programs, the fitness tests and manual
 * workouts without a program (there every event is a [WorkoutEventType.ManualChange]). A change of speed and incline at
 * once is two events.
 *
 * Stored with the workout ([WorkoutHistoryRepository.setEvents], [WorkoutDetail.events]) and synchronised in its
 * `.json` file (`events`).
 */
data class WorkoutEvent(
    /**
     * When it happened, in seconds: the program clock for a workout done with a program (`ProgramEvent.programSeconds`),
     * the test clock for a fitness test, and the time since the start of the workout for a workout without a program.
     */
    val seconds: Int,
    val type: WorkoutEventType,
    /** What changed; `null` when the event changes nothing (for instance the heart rate control resumed). */
    val quantity: WorkoutEventQuantity? = null,
    /** The new value of [quantity] (km/h or %); `null` when [quantity] is. */
    val value: Double? = null,
    /** The index of the block in the plan for a [WorkoutEventType.BlockStarted], `null` otherwise. */
    val block: Int? = null,
)

/**
 * The events as they are stored and read back: the quantity and the value go together (an event that changes nothing has
 * neither), a value that is not a number counts as none.
 */
fun List<WorkoutEvent>.normalised(): List<WorkoutEvent> = map { e ->
    val value = e.value?.takeIf { it.isFinite() }
    if (e.quantity != null && value != null) e.copy(value = value) else e.copy(quantity = null, value = null)
}

/**
 * The JSON array a workout's events are stored as in the database and written as in the workout file
 * (`[{"seconds":12,"type":"manual_change","quantity":"speed","value":6.5}]`, `quantity` and `value` left out when the
 * event changes nothing): the order is kept; an event of an unknown type (written by a newer app) is left out when reading.
 */
object WorkoutEventsJson {

    /** `null` for no events (nothing is stored then). */
    fun encode(events: List<WorkoutEvent>): String? {
        if (events.isEmpty()) return null
        val array = JSONArray()
        for (e in events.normalised()) {
            val o = JSONObject().put("seconds", e.seconds).put("type", e.type.code)
            val quantity = e.quantity
            val value = e.value
            if (quantity != null && value != null) o.put("quantity", quantity.code).put("value", value)
            e.block?.let { o.put("block", it) }
            array.put(o)
        }
        return array.toString()
    }

    fun decode(text: String?): List<WorkoutEvent> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val type = WorkoutEventType.ofCode(o.optString("type")) ?: return@mapNotNull null
                WorkoutEvent(
                    seconds = o.optInt("seconds"),
                    type = type,
                    quantity = if (o.isNull("quantity")) null else WorkoutEventQuantity.ofCode(o.optString("quantity")),
                    value = if (o.isNull("value")) null else o.getDouble("value"),
                    block = if (o.isNull("block")) null else o.optInt("block"),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
