package com.treadz.console.userdata.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Workouts and fitness test results whose upload waits for the user's decision at the end of the session: keep it
 * (the default) or discard it. While held they are left out of the pending changes, so a discarded session never
 * reaches the repository and can be deleted without a tombstone.
 *
 * It lives in memory only: after a restart nothing is held and everything is uploaded as usual (keeping is the
 * default when the user never answered).
 */
class UploadHold {
    private val _workoutIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _testIds = MutableStateFlow<Set<String>>(emptySet())

    val workoutIds: StateFlow<Set<Long>> = _workoutIds.asStateFlow()
    val testIds: StateFlow<Set<String>> = _testIds.asStateFlow()

    fun holdWorkout(workoutId: Long) = _workoutIds.update { it + workoutId }

    fun releaseWorkout(workoutId: Long) = _workoutIds.update { it - workoutId }

    fun holdTest(testId: String) = _testIds.update { it + testId }

    fun releaseTest(testId: String) = _testIds.update { it - testId }

    fun isWorkoutHeld(workoutId: Long): Boolean = workoutId in _workoutIds.value

    fun isTestHeld(testId: String): Boolean = testId in _testIds.value
}
