package com.treadz.console.userdata

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The analyses keep themselves up to date (`WorkoutAnalysisRecalculator.keepUpToDate`, which the real data layer starts
 * by itself): when the observer finishes a session, and after a local change of the weights, with the observer driven by a
 * fake treadmill on virtual time. Against Room and against the in-memory implementation.
 */
@RunWith(RobolectricTestRunner::class)
abstract class WorkoutAnalysisTriggerTest {
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData

    @Test fun `a session that ends gets its analysis by itself, and a later change of the context recomputes it`() = runTest {
        val clock = Clock { T0 + testScheduler.currentTime }
        val data = create(clock, testScheduler)
        val controller = FakeTreadmill()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val user = (data.profiles.create(validInput()) as DataResult.Success).value
            val observer = data.workoutObserver(controller, clock, WorkoutObserverConfig())
            observer.start(scope)
            scope.launch { data.workoutAnalysisRecalculator.keepUpToDate(debounceMillis = 2_000) }
            runCurrent()

            // 60 s of running at 8 km/h with a heart rate: nothing is analysed while the session is in progress
            for (i in 1..60) {
                controller.state.value = state(BeltStatus.Running, 8.0, 1.0, distance = i * 2, elapsed = i, calories = i / 10, hr = 130 + i / 10)
                advanceTimeBy(1_000)
                runCurrent()
            }
            val id = checkNotNull(observer.activeWorkoutId.value)
            advanceTimeBy(5_000)
            runCurrent()
            assertNull("a workout in progress has no analysis", data.workoutAnalysis.analysis(id).first())

            // the belt stops: the observer finishes the session ...
            controller.state.value = state(BeltStatus.Idle, speed = 2.0)
            advanceTimeBy(1_000)
            runCurrent()
            controller.state.value = state(BeltStatus.Idle, speed = 0.0)
            runCurrent()
            assertTrue(data.workouts.workouts(user.id).first().single().endReason != null)

            // ... and the analysis appears once the changes settle, with nobody calling the recalculator
            advanceTimeBy(5_000)
            runCurrent()
            val first = data.workoutAnalysis.analysis(id).first()
            assertNotNull("the analysis of the session that has just ended", first)
            val detail = data.workouts.detail(id)!!
            assertEquals(detail.samples.size, first!!.analysis.signal.sampleCount)
            assertNotNull(first.analysis.energy.value)
            assertNull("no weight yet: no kcal", first.analysis.energy.value!!.grossKcal)
            assertEquals(listOf(id), data.workoutAnalysis.analyses(user.id).first().map { it.workoutId })

            // a weight of that day changes the context of the workout: it is recomputed, again by itself
            val computedAt = first.computedAtMillis
            assertTrue(data.weights.add(user.id, LocalDate.ofEpochDay(T0 / DAY_MS).minusDays(1), 75.0) is DataResult.Success)
            advanceTimeBy(5_000)
            runCurrent()
            val second = data.workoutAnalysis.analysis(id).first()!!
            assertNotNull("the weight gives the energy in kcal", second.analysis.energy.value!!.grossKcal)
            assertTrue(second.computedAtMillis > computedAt)
            assertEquals("the observer sent nothing to the treadmill", emptyList<String>(), controller.commands)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a burst of changes is one recalculation after the debounce`() = runTest {
        val clock = Clock { T0 + testScheduler.currentTime }
        val data = create(clock, testScheduler)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val user = (data.profiles.create(validInput()) as DataResult.Success).value
            val ids = (1..3).map { data.addFinishedWorkout(user.id, T0 - it * DAY_MS, steadySamples(T0 - it * DAY_MS, 120), WorkoutStats(durationSeconds = 120)) }
            scope.launch { data.workoutAnalysisRecalculator.keepUpToDate(debounceMillis = 2_000) }
            runCurrent()
            assertNull("not before the debounce", data.workoutAnalysis.analysis(ids[0]).first())
            advanceTimeBy(5_000)
            runCurrent()
            ids.forEach { assertNotNull(data.workoutAnalysis.analysis(it).first()) }
            assertEquals("everything is up to date", 0, data.workoutAnalysisRecalculator.recalculate())
        } finally {
            scope.cancel()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class RoomWorkoutAnalysisTriggerTest : WorkoutAnalysisTriggerTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData = newRoomUserData(clock, StandardTestDispatcher(scheduler))
}

@RunWith(JUnit4::class)
class InMemoryWorkoutAnalysisTriggerTest : WorkoutAnalysisTriggerTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
