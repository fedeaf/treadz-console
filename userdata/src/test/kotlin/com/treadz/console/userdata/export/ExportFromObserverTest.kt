@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.treadz.console.userdata.export

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FakeTreadmill
import com.treadz.console.userdata.T0
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.state
import com.treadz.console.userdata.validInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/**
 * Export of what the observer really stores: the treadmill's state changes on every data packet (~2 per second,
 * repeated values), so the history holds ~2 samples per second.
 */
class ExportFromObserverTest {

    @Test fun `a 2 Hz stream is stored twice per second and exported once per second`() = runTest {
        val clock = Clock { T0 + testScheduler.currentTime }
        val data = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
        val user = (data.profiles.create(validInput("Ana")) as DataResult.Success).value
        data.profiles.setActiveUser(user.id)
        val controller = FakeTreadmill()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val observer = data.workoutObserver(controller, clock)
        observer.start(scope)
        runCurrent()

        var stamp = 0L
        suspend fun packet(speed: Double, elapsed: Int, distance: Int, hr: Int?) {
            // updatedAtMillis changes with every packet, so each one is a different state, as in the real controller
            controller.state.value = state(BeltStatus.Running, speed, incline = 1.0, distance = distance, elapsed = elapsed, hr = hr)
                .copy(updatedAtMillis = ++stamp)
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
        }
        for (second in 1..20) {
            val distance = second * 2
            packet(7.2, second, distance, 100 + second)
            packet(7.2, second, distance, 100 + second) // the repeat
        }
        controller.state.value = state(BeltStatus.Idle, 0.0)
        runCurrent()

        val detail = data.workouts.detail(data.workouts.workouts(user.id).first().single().id)!!
        assertEquals("stored as they arrive", 40, detail.samples.size)

        val exporter = WorkoutExporter("0.1.0", InMemoryExportStorage(), ZoneOffset.UTC)
        val csv = String((exporter.render(detail, ExportFormat.Csv) as ExportResult.Success).value.content)
        val rows = csv.trimEnd('\n').split('\n').drop(1)
        assertEquals("one row per second", 20, rows.size)
        assertEquals((1..20).toList(), rows.map { it.split(',')[2].toInt() })

        val tcx = TcxChecks.parse((exporter.render(detail, ExportFormat.Tcx) as ExportResult.Success).value.content)
        TcxChecks.assertSchemaOrder(tcx)
        assertEquals(20, TcxChecks.trackpoints(tcx).size)
        assertEquals(19.0, TcxChecks.lap(tcx).totalTimeSeconds, 0.0)
        scope.cancel()
    }
}
