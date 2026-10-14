package com.treadz.console.training

import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.workout.WorkoutHistoryObserver

/** [SessionLinks] on the process-wide workout history observer. */
class ObserverSessionLinks(private val observer: WorkoutHistoryObserver) : SessionLinks {
    override fun link(link: WorkoutLink) = observer.linkNextSession(link)
    override fun clear() = observer.clearLink()
    override fun currentWorkoutId(): Long? = observer.activeWorkoutId.value ?: observer.lastWorkoutId.value
}
