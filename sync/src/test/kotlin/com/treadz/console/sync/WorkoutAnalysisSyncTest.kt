package com.treadz.console.sync

import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The per-workout analyses are a local cache (never synchronised): after a pull that brings workouts, weights, resting
 * measurements, profiles or fitness test results the engine has the device bring them up to date; a pull that brings
 * nothing of that, or nothing at all, does not. Synthetic data, a fake GitHub and the real Room data layer.
 */
@RunWith(RobolectricTestRunner::class)
class WorkoutAnalysisSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long, analyses: Boolean = false) =
        Device(name, github, seed, seedGenericProfile = false, recalculatesAnalyses = analyses)

    private fun Device.ana(): UserProfile = profiles().first { it.name == "Ana" }

    private fun Device.workoutId(): Long = runBlocking { data.workouts.workouts(ana().id).first().single().id }

    private fun Device.analysis(): WorkoutAnalysis? = runBlocking { data.workoutAnalysis.analysis(workoutId()).first() }?.analysis

    private fun Device.kcal(): Double? = analysis()?.energy?.value?.grossKcal

    private fun paths(): List<String> = repo.files().keys.toList()

    @Test fun `a pull that brings a workout and its context gives the device its analysis, once`() {
        val phone = device("phone", 1)
        val ana = phone.createUser("Ana")
        phone.addWeight(ana, LocalDate.of(2026, 9, 1), 70.0)
        phone.finishWorkout(ana, T0 - 3 * DAY_MS, seconds = 120)
        phone.sync()

        val tablet = device("tablet", 2, analyses = true)
        val old = device("old", 3, analyses = false)
        tablet.at(T0 + 1_000).sync()
        old.at(T0 + 1_000).sync()
        assertEquals(1, tablet.analysisRuns)
        // the device that computes it has the analysis, with the pulled weight in its context ...
        val a = tablet.analysis()!!
        assertEquals(120, a.signal.sampleCount)
        assertNotNull(a.energy.value!!.grossKcal)
        // ... the one that does not (not updated yet) has none, and no analysis travelled through the repository
        assertNull(old.analysis())
        assertTrue(paths().toString(), paths().none { it.contains("analysis") })
        val commits = repo.log().size

        // nothing new anywhere: no pull, no recalculation, no commit
        tablet.at(T0 + 2_000).sync()
        assertEquals(1, tablet.analysisRuns)
        assertEquals(0, runBlocking { tablet.data.workoutAnalysisRecalculator.recalculate() })
        assertEquals(commits, repo.log().size)
    }

    @Test fun `a pull that brings a weight, a resting measurement, a test or a profile recomputes the workouts it touches`() {
        val phone = device("phone", 1)
        val ana = phone.createUser("Ana")
        phone.addWeight(ana, LocalDate.of(2026, 9, 1), 70.0)
        phone.finishWorkout(ana, T0 - 3 * DAY_MS, seconds = 120)
        phone.sync()
        val tablet = device("tablet", 2, analyses = true)
        tablet.at(T0 + 1_000).sync()
        val before = tablet.kcal()!!
        val max = tablet.analysis()!!.zoneTime.value!!.zones.zones.last().highBpm

        // a later weight of the phone changes the weight of the workout
        phone.at(T0 + 2_000).addWeight(ana, LocalDate.of(2026, 9, 20), 63.0)
        phone.sync()
        tablet.at(T0 + 3_000).sync()
        assertEquals(2, tablet.analysisRuns)
        assertEquals(before * 63.0 / 70.0, tablet.kcal()!!, 1e-6)

        // a fitness test taken before the workout changes the maximum heart rate of its zones
        phone.at(T0 + 4_000)
        runBlocking { phone.data.fitnessTests.save(ana.id, maxTestResult(TEST_2, T0 - 10 * DAY_MS, peak = 185)) }
        phone.sync()
        tablet.at(T0 + 5_000).sync()
        assertEquals(3, tablet.analysisRuns)
        assertEquals(185, tablet.analysis()!!.zoneTime.value!!.zones.zones.last().highBpm)
        assertTrue("it was not 185 before", max != 185)

        // a resting heart rate measurement
        phone.at(T0 + 6_000)
        runBlocking { phone.data.restingHeartRates.add(ana.id, RestingHeartRateInput(T0 - 4 * DAY_MS, 52, RestingHeartRateSource.Manual)) }
        phone.sync()
        tablet.at(T0 + 7_000).sync()
        assertEquals(4, tablet.analysisRuns)
        assertEquals(52, tablet.analysis()!!.trimp.value!!.restingHeartRateBpm)
        assertEquals("up to date", 0, runBlocking { tablet.data.workoutAnalysisRecalculator.recalculate() })

        // a profile change
        val me = phone.ana()
        phone.at(T0 + 8_000)
        runBlocking {
            phone.data.profiles.update(
                me.id,
                com.treadz.console.userdata.ProfileInput(me.name, me.sex, me.heightCm, me.birthDate, null, 190, me.fitnessLevel, me.goal),
            )
        }
        phone.sync()
        tablet.at(T0 + 9_000).sync()
        assertEquals(5, tablet.analysisRuns)
        assertEquals(190, tablet.analysis()!!.zoneTime.value!!.zones.zones.last().highBpm)
    }

    @Test fun `a pull that only brings a program does not run the analyses`() {
        val phone = device("phone", 1)
        val ana = phone.createUser("Ana")
        phone.sync()
        val tablet = device("tablet", 2, analyses = true)
        tablet.at(T0 + 1_000).sync()
        val runs = tablet.analysisRuns
        phone.at(T0 + 2_000)
        assertTrue(runBlocking { phone.data.programs.save(ana.id, norwegian()) } is ProgramSaveResult.Saved)
        phone.sync()
        tablet.at(T0 + 3_000).sync()
        assertEquals(runs, tablet.analysisRuns)
    }

    @Test fun `a failure of the analyses never fails the synchronisation`() {
        val phone = device("phone", 1)
        val ana = phone.createUser("Ana")
        phone.finishWorkout(ana, T0 - DAY_MS, seconds = 30)
        phone.sync()
        val tablet = device("tablet", 2, analyses = true)
        tablet.analysisFails = true
        val report = tablet.at(T0 + 1_000).sync()
        assertTrue(report.downloaded > 0)
        assertEquals(1, tablet.analysisRuns)
        assertNull(tablet.analysis())
        // the data came in; the next run (a change, the next start) computes the analysis
        tablet.analysisFails = false
        assertEquals(1, runBlocking { tablet.data.workoutAnalysisRecalculator.recalculate() })
        assertNotNull(tablet.analysis())
    }
}
