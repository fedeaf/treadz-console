package com.treadz.console.training

import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.LoadQuantity
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventQuantity
import com.treadz.console.userdata.WorkoutEventType

/**
 * The event the data layer stores for an event of the engine. The codes of the two are the same one to one; an event whose
 * code the data layer does not know (it cannot happen while both sides are built together) is left out.
 */
fun LoadEvent.toWorkoutEvent(): WorkoutEvent? {
    val kind = WorkoutEventType.ofCode(type.code) ?: return null
    val what = when (quantity) {
        LoadQuantity.Speed -> WorkoutEventQuantity.Speed
        LoadQuantity.Incline -> WorkoutEventQuantity.Incline
        null -> null
    }
    return WorkoutEvent(seconds, kind, what, value.takeIf { what != null }, block)
}

/** The events of a workout as they are stored. */
fun List<LoadEvent>.toWorkoutEvents(): List<WorkoutEvent> = mapNotNull { it.toWorkoutEvent() }

/** The list of events of a workout in progress is stored when it changes, at most this often (milliseconds). */
const val EVENTS_STORE_MILLIS = 3_000L
