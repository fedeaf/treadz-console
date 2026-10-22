package com.treadz.console.sync

import com.treadz.console.sync.format.RecordCodec
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.WorkoutLink
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
 * The `updated_at` of every file the app writes to the repository (and of every row of the csv files) changes whenever the
 * content changes, to the time of the device that changed it, and stays when nothing changed. Seen from the repository:
 * each step moves the clock of the device, changes one thing, synchronises and looks at the files.
 */
@RunWith(RobolectricTestRunner::class)
class UpdatedAtFilesTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))
    private val a = Device("tablet", github, 1, seedGenericProfile = false)
    private val hour = 3_600_000L

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value
    private fun iso(millis: Long) = RecordCodec.time(millis)

    private fun folder() = repo.files().keys.first { it.startsWith("users/ana-") && it.endsWith("/profile.json") }.removeSuffix("profile.json")

    /** The `updated_at` values of a file: the field of a json file, the column of every row of a csv file. */
    private fun stamps(path: String): List<String> {
        val text = repo.text(path) ?: return emptyList()
        return if (path.endsWith(".csv")) {
            val rows = text.lines().filter { it.isNotBlank() }
            val column = rows.first().split(',').indexOf("updated_at")
            rows.drop(1).map { it.split(',')[column] }
        } else {
            Regex("\"updated_at\": \"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList().takeLast(1)
        }
    }

    private fun step(at: Long, change: () -> Unit) {
        a.at(at)
        change()
        a.sync()
    }

    @Test fun `every kind of file gets the time of its change`() {
        val start = T0
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 70.0)
        a.sync()
        val f = folder()
        assertEquals(listOf(iso(start)), stamps("${f}profile.json"))

        // profile.json: when the user edits the profile
        step(start + hour) { ok(runBlocking { a.data.profiles.update(ana.id, com.treadz.console.userdata.ProfileInput(ana.name, ana.sex, 171, ana.birthDate)) }) }
        assertEquals(listOf(iso(start + hour)), stamps("${f}profile.json"))

        // a fitness test, and the profile derived from it
        step(start + 2 * hour) { ok(runBlocking { a.data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, start - DAY_MS, vo2max = 41.0)) }) }
        assertEquals(listOf(iso(start + 2 * hour)), stamps("${f}fitness-tests/$TEST_1.json"))
        assertEquals(listOf(iso(start + 2 * hour)), stamps("${f}fitness.json"))
        assertEquals("the profile file did not change", listOf(iso(start + hour)), stamps("${f}profile.json"))

        // a recalculated result: other content, so a new time for the result and for the derived profile
        step(start + 3 * hour) { ok(runBlocking { a.data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, start - DAY_MS, vo2max = 42.0)) }) }
        assertEquals(listOf(iso(start + 3 * hour)), stamps("${f}fitness-tests/$TEST_1.json"))
        assertEquals(listOf(iso(start + 3 * hour)), stamps("${f}fitness.json"))

        // resting heart rate: the row of the measurement, nothing else
        step(start + 4 * hour) { ok(runBlocking { a.data.restingHeartRates.add(ana.id, RestingHeartRateInput(start - hour, 56, RestingHeartRateSource.Manual)) }) }
        assertEquals(listOf(iso(start + 4 * hour)), stamps("${f}resting-heart-rate.csv"))
        step(start + 5 * hour) {
            val m = runBlocking { a.data.restingHeartRates.measurements(ana.id).first() }.single()
            ok(runBlocking { a.data.restingHeartRates.update(m.id, RestingHeartRateInput(m.measuredAtMillis, 58, m.source)) })
        }
        assertEquals(listOf(iso(start + 5 * hour)), stamps("${f}resting-heart-rate.csv"))
        assertEquals(listOf(iso(start + hour)), stamps("${f}profile.json"))

        // weights: only the row that changes gets the new time
        step(start + 6 * hour) { a.addWeight(ana, LocalDate.of(2026, 10, 2), 69.5) }
        assertEquals(listOf(iso(start), iso(start + 6 * hour)), stamps("${f}weight.csv"))
        step(start + 7 * hour) {
            val entry = runBlocking { a.data.weights.entries(ana.id).first() }.first { it.weightKg == 70.0 }
            ok(runBlocking { a.data.weights.update(entry.id, entry.date, 70.5) })
        }
        assertEquals(listOf(iso(start + 7 * hour), iso(start + 6 * hour)).sorted(), stamps("${f}weight.csv").sorted())

        // a workout: its summary at the end, each later change of the record, never its csv
        val workoutId = a.at(start + 8 * hour).finishWorkout(ana, start + 8 * hour, seconds = 3)
        a.sync()
        val workoutJson = repo.files().keys.first { it.contains("/workouts/") && it.endsWith(".json") }
        val firstStamp = stamps(workoutJson).single()
        assertTrue(firstStamp, firstStamp >= iso(start + 8 * hour))
        step(start + 9 * hour) { ok(runBlocking { a.data.workouts.link(workoutId, WorkoutLink(plan = hillPlan())) }) }
        assertEquals(listOf(iso(start + 9 * hour)), stamps(workoutJson))
        step(start + 10 * hour) { ok(runBlocking { a.data.workouts.setEvents(workoutId, listOf(com.treadz.console.userdata.WorkoutEvent(5, com.treadz.console.userdata.WorkoutEventType.ManualChange, com.treadz.console.userdata.WorkoutEventQuantity.Speed, 7.0))) }) }
        assertEquals(listOf(iso(start + 10 * hour)), stamps(workoutJson))
        val sameAgain = repo.log().size
        step(start + 11 * hour) { ok(runBlocking { a.data.workouts.setEvents(workoutId, listOf(com.treadz.console.userdata.WorkoutEvent(5, com.treadz.console.userdata.WorkoutEventType.ManualChange, com.treadz.console.userdata.WorkoutEventQuantity.Speed, 7.0))) }) }
        assertEquals("the same events are no change", sameAgain, repo.log().size)
        assertEquals(listOf(iso(start + 10 * hour)), stamps(workoutJson))

        // programs
        val program = norwegian()
        step(start + 12 * hour) { assertTrue(runBlocking { a.data.programs.save(ana.id, program) } is ProgramSaveResult.Saved) }
        assertEquals(listOf(iso(start + 12 * hour)), stamps("${f}programs/$PROGRAM_1.json"))
        step(start + 13 * hour) { assertTrue(runBlocking { a.data.programs.save(ana.id, norwegian(name = "Norwegian 4x4 hard")) } is ProgramSaveResult.Saved) }
        assertEquals(listOf(iso(start + 13 * hour)), stamps("${f}programs/$PROGRAM_1.json"))

        // deletions are changes too
        step(start + 14 * hour) { ok(runBlocking { a.data.fitnessTests.delete(TEST_1) }) }
        assertEquals(listOf(iso(start + 14 * hour)), stamps("${f}fitness-tests/$TEST_1.json"))
        assertNull("nothing usable is left", repo.text("${f}fitness.json"))
        step(start + 15 * hour) { ok(runBlocking { a.data.workouts.delete(workoutId) }) }
        assertEquals(listOf(iso(start + 15 * hour)), stamps(workoutJson))
        step(start + 16 * hour) { ok(runBlocking { a.data.programs.delete(PROGRAM_1) }) }
        assertEquals(listOf(iso(start + 16 * hour)), stamps("${f}programs/$PROGRAM_1.json"))
        assertNotNull(repo.text("${f}profile.json"))
    }
}
