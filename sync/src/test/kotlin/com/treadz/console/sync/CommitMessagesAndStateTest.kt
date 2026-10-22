package com.treadz.console.sync

import com.treadz.console.sync.engine.CommitMessages
import com.treadz.console.sync.engine.FileSyncStateStore
import com.treadz.console.sync.engine.FitnessBlob
import com.treadz.console.sync.engine.SyncState
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncWeightEntry
import com.treadz.console.userdata.sync.SyncWorkout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CommitMessagesAndStateTest {

    @get:Rule val folder = TemporaryFolder()

    private val v = RecordVersion(T0, "d")
    private val names = mapOf("u1" to "Fede", "u2" to "Ana", "u3" to "")
    private fun profile(uuid: String, deleted: Boolean = false) = SyncProfile(uuid, v, deleted, names.getValue(uuid), Sex.Male, 180, LocalDate.of(1980, 1, 1))
    private fun workout(uuid: String, user: String, minutes: Int, meters: Int, deleted: Boolean = false) = SyncWorkout(
        uuid, user, names.getValue(user), v, deleted, T0, T0 + minutes * 60_000L, WorkoutEndReason.Stopped,
        WorkoutStats(durationSeconds = minutes * 60, distanceMeters = meters),
    )
    private fun weight(user: String, kg: Double, deleted: Boolean = false) = SyncWeightEntry("w", user, v, deleted, LocalDate.of(2026, 10, 8), kg)
    private fun message(changes: PendingChanges, programs: List<ProgramDocument> = emptyList(), fitnessTests: List<com.treadz.console.userdata.sync.FitnessTestDocument> = emptyList()) = CommitMessages.build(changes, programs, fitnessTests, names, ZoneOffset.UTC)

    @Test fun `a single record gets a descriptive message`() {
        val e = emptyList<Nothing>()
        assertEquals("Fede · workout 2026-10-08 00:40 · 45 min · 5.2 km", message(PendingChanges(e, e, listOf(workout("k", "u1", 45, 5200)))))
        assertEquals("Fede · workout 2026-10-08 00:40 · 30 s · 0.1 km", message(PendingChanges(e, e, listOf(workout("k", "u1", 0, 90).let { it.copy(stats = it.stats.copy(durationSeconds = 30)) }))))
        assertEquals("Ana · workout deleted 2026-10-08 00:40", message(PendingChanges(e, e, listOf(workout("k", "u2", 45, 5200, deleted = true)))))
        assertEquals("Ana · weight 2026-10-08 · 61.5 kg", message(PendingChanges(e, listOf(weight("u2", 61.5)), e)))
        assertEquals("Ana · weight entry deleted", message(PendingChanges(e, listOf(weight("u2", 61.5, deleted = true)), e)))
        assertEquals("Fede · profile", message(PendingChanges(listOf(profile("u1")), e, e)))
        assertEquals("Fede · profile deleted", message(PendingChanges(listOf(profile("u1", deleted = true)), e, e)))
        assertEquals("User · profile", message(PendingChanges(listOf(profile("u3")), e, e)))
    }

    @Test fun `several records are summarised with the users`() {
        val changes = PendingChanges(
            listOf(profile("u1")),
            listOf(weight("u2", 61.5), weight("u2", 61.0)),
            listOf(workout("k1", "u1", 10, 1000), workout("k2", "u2", 20, 2000)),
        )
        assertEquals("Sync · 2 workouts, 2 weight entries, 1 profile · Ana, Fede", message(changes))
        val program = ProgramDocument("p", "u1", v, false, "{}")
        assertEquals("Fede · program", message(PendingChanges(emptyList(), emptyList(), emptyList()), listOf(program)))
    }

    @Test fun `programs and fitness tests have descriptive messages`() {
        val none = PendingChanges(emptyList(), emptyList(), emptyList())
        val program = ProgramDocument(PROGRAM_1, "u1", v, false, com.treadz.console.programs.json.ProgramJson.encode(norwegian()))
        assertEquals("Fede · program \"Norwegian 4x4\"", message(none, listOf(program)))
        assertEquals("Fede · program deleted", message(none, listOf(program.copy(deleted = true, json = ""))))
        assertEquals("Fede · program", message(none, listOf(program.copy(json = "{ not a program"))))

        fun test(result: com.treadz.console.programs.fitness.FitnessTestResult, owner: String = "u2", deleted: Boolean = false) =
            com.treadz.console.userdata.sync.FitnessTestDocument(
                result.id, owner, v, deleted, if (deleted) "" else com.treadz.console.programs.fitness.FitnessTestJson.encode(result),
            )
        val ebbeling = ebbelingResult(TEST_1, T0, vo2max = 41.04)
        assertEquals("Ana · fitness test 2026-10-08 00:40 · Ebbeling walk · VO2max 41.0", message(none, fitnessTests = listOf(test(ebbeling))))
        assertEquals(
            "Ana · fitness test 2026-10-08 00:40 · max incremental run · VO2max 47.3 · MAS 13.5 km/h",
            message(none, fitnessTests = listOf(test(maxTestResult(TEST_2, T0)))),
        )
        assertEquals(
            "Ana · fitness test 2026-10-08 00:40 · Ebbeling walk · invalid",
            message(none, fitnessTests = listOf(test(ebbelingResult(TEST_1, T0, validity = com.treadz.console.programs.fitness.Validity.Invalid)))),
        )
        assertEquals("Ana · fitness test deleted", message(none, fitnessTests = listOf(test(ebbeling, deleted = true))))
        assertEquals(
            "Sync · 1 profile, 1 program, 2 fitness tests · Ana, Fede",
            message(
                PendingChanges(listOf(profile("u1")), emptyList(), emptyList()), listOf(program),
                listOf(test(ebbeling), test(maxTestResult(TEST_2, T0))),
            ),
        )
    }

    @Test fun `a commit that only changes the derived fitness profile says so`() {
        val none = PendingChanges(emptyList(), emptyList(), emptyList())
        fun derived(vararg users: String) = CommitMessages.build(none, emptyList(), emptyList(), names, ZoneOffset.UTC, users.toList())
        assertEquals("Ana · fitness profile", derived("u2"))
        assertEquals("Sync · fitness profile · Ana, Fede", derived("u2", "u1"))
        // next to a record, the record names the commit
        assertEquals("Fede · profile", CommitMessages.build(PendingChanges(listOf(profile("u1")), emptyList(), emptyList()), emptyList(), emptyList(), names, ZoneOffset.UTC, listOf("u2")))
    }

    @Test fun `the state file round trips and a damaged file means a fresh start`() = runBlocking<Unit> {
        val file = folder.root.resolve("state.json")
        val store = FileSyncStateStore(file)
        assertEquals(SyncState(), store.load())
        val state = SyncState(
            RepositoryRef("fede", "treadz-data", "main"), true, T0, "abc",
            mapOf("README.md" to "111", "users/a/profile.json" to "222"), mapOf("w-1" to "users/a/workouts/2026/10/x.json"),
            mapOf("333" to FitnessBlob("abcdef", 1)),
        )
        store.save(state)
        assertEquals(state, FileSyncStateStore(file).load())
        assertTrue(!file.resolveSibling("state.json.tmp").exists())
        file.writeText("{ damaged")
        assertEquals(SyncState(), FileSyncStateStore(file).load())
    }
}
