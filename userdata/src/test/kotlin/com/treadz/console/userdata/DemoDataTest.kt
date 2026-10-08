package com.treadz.console.userdata

import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DemoDataTest {
    @Test fun `demo content is consistent`() = runBlocking<Unit> {
        val data = InMemoryUserData(MutableClock(), ZoneOffset.UTC)
        val (ana, beto) = DemoData.populate(data, TODAY, ZoneOffset.UTC)
        assertEquals(ana, data.profiles.activeUser.first())
        assertEquals(listOf("Ana", "Beto"), data.profiles.profiles.first().map { it.name }) // the generic profile is gone
        assertTrue(data.profiles.profiles.first().none { it.isGeneric })
        assertEquals(12, data.weights.entries(ana.id).first().size)
        assertEquals(3, data.workouts.workouts(ana.id).first().size)
        assertEquals(2, data.workouts.workouts(beto.id).first().size)
        assertEquals(1, data.workouts.unassignedWorkouts().first().size)
        val newest = data.workouts.workouts(ana.id).first().first()
        assertEquals(newest.stats.durationSeconds, data.workouts.detail(newest.id)!!.samples.size)
        assertTrue(newest.stats.averageHeartRateBpm != null)
        // programs and fitness tests of the demo
        assertEquals(listOf("Norwegian 4x4"), data.programs.programs(ana.id).first().map { it.program.name })
        assertEquals(emptyList<UserProgram>(), data.programs.programs(beto.id).first())
        assertEquals(2, data.fitnessTests.results(ana.id).first().size)
        assertEquals(2400, data.startParameters.lastUsed(ana.id, "standard-hill").first()!!.totalDurationSeconds)
        val hrMax = data.fitnessTests.heartRateMax(ana.id).first()!!
        assertEquals(com.treadz.console.programs.fitness.HeartRateMaxOrigin.Measured, hrMax.origin)
        assertEquals(183, hrMax.bpm)
        assertEquals(12.0, data.fitnessTests.liveFitnessProfile(ana.id).first()!!.mas!!.valueKmh, 0.0)
    }
}
