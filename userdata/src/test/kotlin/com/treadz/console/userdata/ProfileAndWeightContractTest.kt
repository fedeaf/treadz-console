package com.treadz.console.userdata

import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/** Behaviour every [UserData] implementation must have. Run against Room (Robolectric) and against the in-memory fake. */
@RunWith(RobolectricTestRunner::class)
abstract class ProfileAndWeightContractTest {

    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(seed: Boolean): UserData
    private val data by lazy { create(seed = false) }

    private fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    private fun DataResult<*>.issues(): List<ValidationIssue> = (this as DataResult.Invalid).issues

    // ---- Profiles ----

    @Test fun `create stores the profile and trims the name`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput("  Ana  ")).ok()
        assertEquals("Ana", p.name)
        assertEquals(Sex.Female, p.sex)
        assertEquals(168, p.heightCm)
        assertEquals(LocalDate.of(1986, 10, 8), p.birthDate)
        assertNull(p.maxHeartRateBpm)
        assertEquals(T0, p.createdAtMillis)
        assertEquals(listOf(p), data.profiles.profiles.first())
        assertEquals(p, data.profiles.profile(p.id).first())
    }

    @Test fun `optional fields round trip`() = runBlocking<Unit> {
        val input = validInput().copy(
            restingHeartRateBpm = 55, maxHeartRateBpm = 185,
            fitnessLevel = FitnessLevel.Advanced, goal = Goal.BuildEndurance,
        )
        val p = data.profiles.create(input).ok()
        assertEquals(55, p.restingHeartRateBpm)
        assertEquals(185, p.maxHeartRateBpm)
        assertEquals(FitnessLevel.Advanced, p.fitnessLevel)
        assertEquals(Goal.BuildEndurance, p.goal)
    }

    @Test fun `with no profile at all the first one created becomes active, later ones do not`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        val b = data.profiles.create(validInput("Beto")).ok()
        assertEquals(a, data.profiles.activeUser.first())
        assertEquals(a.id, data.profiles.activeUserId())
        data.profiles.setActiveUser(b.id).ok()
        assertEquals(b, data.profiles.activeUser.first())
        assertEquals(b.id, data.profiles.activeUserId())
    }

    @Test fun `profiles are ordered by name ignoring case`() = runBlocking<Unit> {
        data.profiles.create(validInput("beto")).ok()
        data.profiles.create(validInput("Carla")).ok()
        data.profiles.create(validInput("Ana")).ok()
        assertEquals(listOf("Ana", "beto", "Carla"), data.profiles.profiles.first().map { it.name })
    }

    @Test fun `edit changes fields and keeps id and creation time`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput("Ana")).ok()
        clock.nowMillis += 60_000
        val edited = data.profiles.update(p.id, validInput("Ana B").copy(heightCm = 170, goal = Goal.LoseWeight)).ok()
        assertEquals(p.id, edited.id)
        assertEquals("Ana B", edited.name)
        assertEquals(170, edited.heightCm)
        assertEquals(Goal.LoseWeight, edited.goal)
        assertEquals(p.createdAtMillis, edited.createdAtMillis)
        assertEquals(edited, data.profiles.profile(p.id).first())
    }

    @Test fun `edit keeping the own name is allowed`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput("Ana")).ok()
        data.profiles.update(p.id, validInput("ANA")).ok()
    }

    @Test fun `edit of an unknown profile is NotFound`() = runBlocking<Unit> {
        assertEquals(DataResult.NotFound, data.profiles.update(99, validInput()))
    }

    @Test fun `names are unique ignoring case and spaces`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        data.profiles.create(validInput("Beto")).ok()
        val taken = listOf(ValidationIssue(InputField.Name, ValidationCode.NameTaken))
        assertEquals(taken, data.profiles.create(validInput(" ana ")).issues())
        assertEquals(taken, data.profiles.update(a.id, validInput("BETO")).issues())
        assertEquals(2, data.profiles.profiles.first().size)
    }

    @Test fun `invalid input is rejected with stable codes and stores nothing`() = runBlocking<Unit> {
        val bad = validInput(" ").copy(heightCm = 20, birthDate = TODAY.plusDays(1), restingHeartRateBpm = 10, maxHeartRateBpm = 300)
        assertEquals(
            setOf(
                ValidationIssue(InputField.Name, ValidationCode.Blank),
                ValidationIssue(InputField.Height, ValidationCode.OutOfRange),
                ValidationIssue(InputField.BirthDate, ValidationCode.InFuture),
                ValidationIssue(InputField.RestingHeartRate, ValidationCode.OutOfRange),
                ValidationIssue(InputField.MaxHeartRate, ValidationCode.OutOfRange),
            ),
            data.profiles.create(bad).issues().toSet(),
        )
        assertTrue(data.profiles.profiles.first().isEmpty())
        assertNull(data.profiles.activeUserId())
    }

    @Test fun `delete removes the profile and clears the active user`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.profiles.delete(a.id).ok()
        assertEquals(listOf(b), data.profiles.profiles.first())
        assertNull(data.profiles.activeUser.first())
        assertNull(data.profiles.activeUserId())
        assertEquals(DataResult.NotFound, data.profiles.delete(a.id))
    }

    @Test fun `deleting a non active profile keeps the active user`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.profiles.delete(b.id).ok()
        assertEquals(a, data.profiles.activeUser.first())
    }

    @Test fun `selecting an unknown user is NotFound and can clear the selection`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        assertEquals(DataResult.NotFound, data.profiles.setActiveUser(99))
        assertEquals(a.id, data.profiles.activeUserId())
        data.profiles.setActiveUser(null).ok()
        assertNull(data.profiles.activeUser.first())
    }

    // ---- Weight ----

    @Test fun `entries come ordered by date then creation`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput()).ok()
        data.weights.add(p.id, LocalDate.of(2026, 10, 5), 70.0).ok()
        data.weights.add(p.id, LocalDate.of(2026, 9, 1), 72.5).ok()
        data.weights.add(p.id, LocalDate.of(2026, 10, 5), 69.8).ok()
        val entries = data.weights.entries(p.id).first()
        assertEquals(listOf(72.5, 70.0, 69.8), entries.map { it.weightKg })
        assertEquals(listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)), entries.map { it.date })
    }

    @Test fun `latest weight is the most recent date, not the last inserted`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput()).ok()
        assertNull(data.weights.latestWeight(p.id).first())
        data.weights.add(p.id, LocalDate.of(2026, 10, 5), 70.0).ok()
        data.weights.add(p.id, LocalDate.of(2026, 9, 1), 80.0).ok()
        assertEquals(70.0, data.weights.latestWeight(p.id).first()?.weightKg ?: 0.0, 0.0)
        data.weights.add(p.id, LocalDate.of(2026, 10, 5), 69.5).ok() // same date, created later
        assertEquals(69.5, data.weights.latestWeight(p.id).first()?.weightKg ?: 0.0, 0.0)
    }

    @Test fun `edit and delete entries`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput()).ok()
        val e = data.weights.add(p.id, LocalDate.of(2026, 10, 5), 70.0).ok()
        val edited = data.weights.update(e.id, LocalDate.of(2026, 10, 6), 71.2).ok()
        assertEquals(LocalDate.of(2026, 10, 6), edited.date)
        assertEquals(listOf(edited), data.weights.entries(p.id).first())
        data.weights.delete(e.id).ok()
        assertTrue(data.weights.entries(p.id).first().isEmpty())
        assertEquals(DataResult.NotFound, data.weights.delete(e.id))
        assertEquals(DataResult.NotFound, data.weights.update(e.id, TODAY, 70.0))
    }

    @Test fun `entries are per user`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.weights.add(a.id, TODAY, 60.0).ok()
        data.weights.add(b.id, TODAY, 90.0).ok()
        assertEquals(listOf(60.0), data.weights.entries(a.id).first().map { it.weightKg })
        assertEquals(90.0, data.weights.latestWeight(b.id).first()?.weightKg ?: 0.0, 0.0)
    }

    @Test fun `invalid weight entries are rejected`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput()).ok()
        assertEquals(
            listOf(ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)),
            data.weights.add(p.id, TODAY, 5.0).issues(),
        )
        assertEquals(
            listOf(ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)),
            data.weights.add(p.id, TODAY, Double.NaN).issues(),
        )
        assertEquals(
            listOf(ValidationIssue(InputField.WeightDate, ValidationCode.InFuture)),
            data.weights.add(p.id, TODAY.plusDays(1), 70.0).issues(),
        )
        assertEquals(DataResult.NotFound, data.weights.add(99, TODAY, 70.0))
        assertTrue(data.weights.entries(p.id).first().isEmpty())
    }

    @Test fun `target weight can be set, changed and cleared`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput()).ok()
        assertNull(data.weights.targetWeightKg(p.id).first())
        data.weights.setTargetWeight(p.id, 65.0).ok()
        assertEquals(65.0, data.weights.targetWeightKg(p.id).first() ?: 0.0, 0.0)
        data.weights.setTargetWeight(p.id, null).ok()
        assertNull(data.weights.targetWeightKg(p.id).first())
        assertEquals(
            listOf(ValidationIssue(InputField.TargetWeight, ValidationCode.OutOfRange)),
            data.weights.setTargetWeight(p.id, 1000.0).issues(),
        )
        assertEquals(DataResult.NotFound, data.weights.setTargetWeight(99, 60.0))
    }

    @Test fun `target weight survives a profile edit`() = runBlocking<Unit> {
        val p = data.profiles.create(validInput("Ana")).ok()
        data.weights.setTargetWeight(p.id, 65.0).ok()
        data.profiles.update(p.id, validInput("Ana B")).ok()
        assertEquals(65.0, data.weights.targetWeightKg(p.id).first() ?: 0.0, 0.0)
    }

    @Test fun `deleting a profile deletes its weights and target but not the others`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.weights.add(a.id, TODAY, 60.0).ok()
        data.weights.setTargetWeight(a.id, 55.0).ok()
        data.weights.add(b.id, TODAY, 90.0).ok()
        data.profiles.delete(a.id).ok()
        assertTrue(data.weights.entries(a.id).first().isEmpty())
        assertNull(data.weights.latestWeight(a.id).first())
        assertNull(data.weights.targetWeightKg(a.id).first())
        assertEquals(1, data.weights.entries(b.id).first().size)
    }

    @Test fun `active user weight follows the active user`() = runBlocking<Unit> {
        val a = data.profiles.create(validInput("Ana")).ok()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.weights.add(a.id, TODAY, 60.0).ok()
        data.weights.add(b.id, TODAY, 90.0).ok()
        assertEquals(60.0, data.activeUserWeightKg.first())
        data.profiles.setActiveUser(b.id).ok()
        assertEquals(90.0, data.activeUserWeightKg.first())
        data.profiles.setActiveUser(null).ok()
        assertNull(data.activeUserWeightKg.first())
    }
}

class RoomProfileAndWeightTest : ProfileAndWeightContractTest() {
    override fun create(seed: Boolean): UserData = newRoomUserData(clock, seed = seed)
}

@RunWith(JUnit4::class)
class InMemoryProfileAndWeightTest : ProfileAndWeightContractTest() {
    override fun create(seed: Boolean): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seed)
}
