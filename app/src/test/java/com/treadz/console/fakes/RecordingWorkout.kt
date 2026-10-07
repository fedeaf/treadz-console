package com.treadz.console.fakes

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel

/**
 * Leaves a real workout in progress in the fake data layer: the actual history observer records a fake
 * treadmill that runs for [seconds] seconds and is still running when this returns.
 */
class RecordingWorkout(private val app: FakeApp, private val seconds: Int = 40) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = app.date.atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val clock = Clock { now }
    val observer: WorkoutHistoryObserver = app.userData.workoutObserver(
        app.controller,
        clock,
        WorkoutObserverConfig(flushIntervalMs = 20, minSampleSpacingMs = 0),
        onError = { throw it },
    )

    fun start(): RecordingWorkout = apply {
        observer.start(scope)
        app.controller.showConnected()
        runBlocking {
            for (i in 1..seconds) {
                now += 1_000
                app.controller.showState {
                    TreadmillState(
                        status = BeltStatus.Running,
                        speedKmh = if (i < 15) i * 0.5 else 7.5,
                        targetSpeedKmh = 7.5,
                        inclinePercent = if (i > 25) 2.0 else 0.0,
                        distanceMeters = i * 2,
                        elapsedSeconds = i,
                        calories = i / 5,
                        heartRateBpm = 100 + i,
                        updatedAtMillis = now,
                    )
                }
                delay(8)
            }
            withTimeout(10_000) {
                while (app.userData.workouts.inProgressWorkout().first()?.stats?.durationSeconds != seconds) delay(10)
            }
        }
    }

    /** Stops observing (no more work in the background). */
    fun close() {
        runBlocking { observer.stop() }
        scope.cancel()
    }
}
