package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.history.HistoryViewModel
import com.treadz.console.history.WorkoutDetailViewModel
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The history by origin: workouts of a generator carry its id, Couch to 5K ones their session, and the detail has the record. */
class HistoryOriginTest {

    @get:Rule val main = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val ana = runBlocking { DemoData.populate(data, app.date) }.first
    private val now = app.nowMillis()

    @Test fun workoutsOfAGeneratorCarryItsIdAndCouchSessionsAreMappedByWorkout() {
        val couch = GeneratedFixtures.addSession(data, ana.id, GeneratedFixtures.record(session = 12, startedAtMillis = now - 86_400_000L))
        val zone2 = GeneratedFixtures.addSession(data, ana.id, GeneratedFixtures.record(generatorId = "zone2", startedAtMillis = now - 2 * 86_400_000L))
        val state = HistoryViewModel(data).uiState.value

        assertEquals(CouchToFiveK.ID, state.workouts.single { it.id == couch }.generatorId)
        assertEquals("zone2", state.workouts.single { it.id == zone2 }.generatorId)
        assertEquals(mapOf(couch to 12), state.couchSessions)
        // An ordinary workout has none.
        assertNull(state.workouts.first { it.id != couch && it.id != zone2 }.generatorId)
    }

    @Test fun theDetailOfAGeneratedWorkoutHasItsRecord() {
        val id = GeneratedFixtures.addSession(data, ana.id, GeneratedFixtures.record(session = 3, startedAtMillis = now - 86_400_000L, settledValue = 9.0))
        val record = WorkoutDetailViewModel(id, data, app.exporter).uiState.value.detail!!.sessionRecord
        assertNotNull(record)
        assertEquals("3", record!!.origin.inputs["session"])
        assertEquals(9.0, record.blocks.single().heartRate!!.settledValue!!, 0.001)
    }
}
