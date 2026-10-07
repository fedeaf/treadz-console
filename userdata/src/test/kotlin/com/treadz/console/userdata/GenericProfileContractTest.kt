package com.treadz.console.userdata

import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/** The generic default profile: seeding, validation, personalisation and the "always one profile" rule. */
@RunWith(RobolectricTestRunner::class)
abstract class GenericProfileContractTest {

    private val clock = MutableClock()
    protected abstract fun create(seed: Boolean): UserData
    private val data by lazy { create(seed = true) }

    @After fun closeDatabases() = closeRoomDatabases()

    private fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")
    private fun DataResult<*>.issues(): List<ValidationIssue> = (this as DataResult.Invalid).issues

    private suspend fun generic(): UserProfile = data.profiles.profiles.first().single { it.isGeneric }
    private suspend fun names() = data.profiles.profiles.first().map { it.name }

    // ---- Seeding ----

    @Test fun `a fresh database has exactly the generic profile, active`() = runBlocking<Unit> {
        val all = data.profiles.profiles.first()
        assertEquals(1, all.size)
        val g = all.single()
        assertTrue(g.isGeneric)
        assertEquals("", g.name)
        assertEquals(Sex.Male, g.sex)
        assertEquals(180, g.heightCm)
        assertEquals(TODAY.minusYears(40), g.birthDate)
        assertEquals(40, g.ageOn(TODAY))
        assertNull(g.restingHeartRateBpm)
        assertNull(g.maxHeartRateBpm)
        assertNull(g.fitnessLevel)
        assertNull(g.goal)
        assertTrue(g.isMaxHeartRateEstimated)
        assertEquals(180, g.effectiveMaxHeartRateBpm(TODAY)) // 208 - 0.7 * 40
        assertEquals(T0, g.createdAtMillis)
        assertEquals(g, data.profiles.activeUser.first())
        assertEquals(g.id, data.profiles.activeUserId())
        assertEquals(GenericProfile.input(TODAY), ProfileInput("", Sex.Male, 180, TODAY.minusYears(40)))
    }

    @Test fun `the generic profile has the 80 kg placeholder as current weight and no target`() = runBlocking<Unit> {
        val g = generic()
        val entries = data.weights.entries(g.id).first()
        assertEquals(1, entries.size)
        assertEquals(80.0, entries.single().weightKg, 0.0)
        assertEquals(TODAY, entries.single().date)
        assertTrue(entries.single().isPlaceholder)
        assertEquals(entries.single(), data.weights.latestWeight(g.id).first())
        assertEquals(80.0, data.activeUserWeightKg.first())
        assertNull(data.weights.targetWeightKg(g.id).first())
    }

    @Test fun `reading and writing never seeds a second generic profile`() = runBlocking<Unit> {
        repeat(3) { data.profiles.profiles.first(); data.profiles.activeUser.first(); data.profiles.activeUserId() }
        data.profiles.create(validInput("Ana")).ok()
        data.profiles.setActiveUser(null).ok()
        assertEquals(listOf("", "Ana"), names())
        assertEquals(1, data.profiles.profiles.first().count { it.isGeneric })
    }

    // ---- Validation rules ----

    @Test fun `a blank name is never valid when creating a profile`() = runBlocking<Unit> {
        assertEquals(listOf(ValidationIssue(InputField.Name, ValidationCode.Blank)), data.profiles.create(validInput("  ")).issues())
        assertEquals(1, data.profiles.profiles.first().size)
    }

    @Test fun `the generic profile can be edited keeping the blank name and stays generic`() = runBlocking<Unit> {
        val g = generic()
        val edited = data.profiles.update(g.id, GenericProfile.input(TODAY).copy(heightCm = 175, restingHeartRateBpm = 60)).ok()
        assertTrue(edited.isGeneric)
        assertEquals("", edited.name)
        assertEquals(175, edited.heightCm)
        assertEquals(60, edited.restingHeartRateBpm)
        assertEquals(edited, data.profiles.activeUser.first())
        // the other rules still apply
        assertEquals(
            listOf(ValidationIssue(InputField.Height, ValidationCode.OutOfRange)),
            data.profiles.update(g.id, GenericProfile.input(TODAY).copy(heightCm = 50)).issues(),
        )
    }

    @Test fun `giving the generic profile a name makes it a normal profile with the usual rules`() = runBlocking<Unit> {
        data.profiles.create(validInput("Ana")).ok()
        val g = generic()
        // the name must be unique
        assertEquals(
            listOf(ValidationIssue(InputField.Name, ValidationCode.NameTaken)),
            data.profiles.update(g.id, validInput(" ana ")).issues(),
        )
        assertTrue(generic().isGeneric)
        val named = data.profiles.update(g.id, validInput("Beto")).ok()
        assertFalse(named.isGeneric)
        assertEquals("Beto", named.name)
        // from now on a blank name is rejected like for any profile
        assertEquals(
            listOf(ValidationIssue(InputField.Name, ValidationCode.Blank)),
            data.profiles.update(g.id, validInput("")).issues(),
        )
        assertEquals("Beto", data.profiles.profile(g.id).first()!!.name)
    }

    @Test fun `the generic profile comes first in the list`() = runBlocking<Unit> {
        data.profiles.create(validInput("Ana")).ok()
        data.profiles.create(validInput("Beto")).ok()
        assertEquals(listOf("", "Ana", "Beto"), names())
    }

    // ---- Personalisation ----

    @Test fun `personalize sets the profile and replaces the placeholder with the real weight in one step`() = runBlocking<Unit> {
        val g = generic()
        data.weights.setTargetWeight(g.id, 70.0).ok()
        val p = data.profiles.personalize(g.id, validInput("Ana").copy(goal = Goal.LoseWeight), 72.5).ok()
        assertEquals(g.id, p.id)
        assertFalse(p.isGeneric)
        assertEquals("Ana", p.name)
        assertEquals(Sex.Female, p.sex)
        assertEquals(Goal.LoseWeight, p.goal)
        val entries = data.weights.entries(g.id).first()
        assertEquals(1, entries.size) // the 80 kg is gone
        assertEquals(72.5, entries.single().weightKg, 0.0)
        assertEquals(TODAY, entries.single().date)
        assertFalse(entries.single().isPlaceholder)
        assertEquals(72.5, data.activeUserWeightKg.first())
        assertEquals(70.0, data.weights.targetWeightKg(g.id).first())
        assertEquals(g.id, data.profiles.activeUserId()) // still the active user
        assertEquals(listOf(p), data.profiles.profiles.first())
    }

    @Test fun `personalize without a weight keeps the placeholder, still flagged`() = runBlocking<Unit> {
        val g = generic()
        data.profiles.personalize(g.id, validInput("Ana"), null).ok()
        val entry = data.weights.latestWeight(g.id).first()!!
        assertEquals(80.0, entry.weightKg, 0.0)
        assertTrue(entry.isPlaceholder)
    }

    @Test fun `personalize is atomic - a rejected weight, name or duplicate changes nothing`() = runBlocking<Unit> {
        data.profiles.create(validInput("Beto")).ok()
        val g = generic()
        assertEquals(
            listOf(ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)),
            data.profiles.personalize(g.id, validInput("Ana"), 10.0).issues(),
        )
        assertEquals(
            listOf(ValidationIssue(InputField.Name, ValidationCode.Blank)),
            data.profiles.personalize(g.id, validInput(""), 70.0).issues(),
        )
        assertEquals(
            listOf(ValidationIssue(InputField.Name, ValidationCode.NameTaken)),
            data.profiles.personalize(g.id, validInput("beto"), 70.0).issues(),
        )
        assertEquals(g, generic())
        val entry = data.weights.entries(g.id).first().single()
        assertEquals(80.0, entry.weightKg, 0.0)
        assertTrue(entry.isPlaceholder)
        assertEquals(DataResult.NotFound, data.profiles.personalize(9999, validInput("Ana"), 70.0))
    }

    @Test fun `a real weight entry removes the placeholder`() = runBlocking<Unit> {
        val g = generic()
        val real = data.weights.add(g.id, TODAY.minusDays(3), 74.0).ok()
        assertEquals(listOf(real), data.weights.entries(g.id).first())
        assertEquals(74.0, data.activeUserWeightKg.first())
        // another user's placeholder is not touched
        val ana = data.profiles.create(validInput("Ana")).ok()
        data.weights.add(ana.id, TODAY, 60.0).ok()
        assertEquals(1, data.weights.entries(ana.id).first().size)
    }

    @Test fun `editing the placeholder turns it into a normal entry, deleting it is allowed`() = runBlocking<Unit> {
        val g = generic()
        val placeholder = data.weights.entries(g.id).first().single()
        val edited = data.weights.update(placeholder.id, TODAY, 76.0).ok()
        assertFalse(edited.isPlaceholder)
        data.weights.add(g.id, TODAY.minusDays(1), 77.0).ok()
        assertEquals(listOf(77.0, 76.0), data.weights.entries(g.id).first().map { it.weightKg }) // the edited one stays

        val ana = data.profiles.create(validInput("Ana")).ok()
        data.profiles.personalize(ana.id, validInput("Ana B"), null).ok() // not generic: no placeholder to keep
        assertTrue(data.weights.entries(ana.id).first().isEmpty())
    }

    @Test fun `deleting the placeholder leaves the user without a weight`() = runBlocking<Unit> {
        val g = generic()
        data.weights.delete(data.weights.entries(g.id).first().single().id).ok()
        assertNull(data.weights.latestWeight(g.id).first())
        assertNull(data.activeUserWeightKg.first())
    }

    // ---- Always at least one profile ----

    @Test fun `deleting the last profile recreates a fresh generic profile, active, with none of the old data`() = runBlocking<Unit> {
        val g = generic()
        data.profiles.personalize(g.id, validInput("Ana").copy(maxHeartRateBpm = 190), 72.5).ok()
        data.weights.setTargetWeight(g.id, 65.0).ok()
        data.weights.add(g.id, TODAY.minusDays(7), 73.5).ok()

        data.profiles.delete(g.id).ok()

        val all = data.profiles.profiles.first()
        assertEquals(1, all.size)
        val fresh = all.single()
        assertTrue(fresh.isGeneric)
        assertNotEquals(g.id, fresh.id)
        assertEquals("", fresh.name)
        assertNull(fresh.maxHeartRateBpm)
        assertEquals(fresh, data.profiles.activeUser.first())
        assertEquals(fresh.id, data.profiles.activeUserId())
        assertNull(data.profiles.profile(g.id).first())
        assertTrue(data.weights.entries(g.id).first().isEmpty())
        val weights = data.weights.entries(fresh.id).first()
        assertEquals(listOf(80.0), weights.map { it.weightKg })
        assertTrue(weights.single().isPlaceholder)
        assertNull(data.weights.targetWeightKg(fresh.id).first())
        assertEquals(DataResult.NotFound, data.profiles.delete(g.id))
        assertEquals(1, data.profiles.profiles.first().size)
    }

    @Test fun `deleting the generic profile itself when it is the last one recreates it`() = runBlocking<Unit> {
        val g = generic()
        data.profiles.delete(g.id).ok()
        val fresh = generic()
        assertNotEquals(g.id, fresh.id)
        assertEquals(fresh.id, data.profiles.activeUserId())
    }

    @Test fun `deleting the active user while others remain leaves nobody active and creates no generic profile`() = runBlocking<Unit> {
        val g = generic()
        data.profiles.personalize(g.id, validInput("Ana"), 70.0).ok()
        val beto = data.profiles.create(validInput("Beto")).ok()
        data.profiles.delete(g.id).ok()
        assertEquals(listOf(beto), data.profiles.profiles.first())
        assertNull(data.profiles.activeUser.first())
        assertNull(data.profiles.activeUserId())
        assertNull(data.activeUserWeightKg.first())
    }

    @Test fun `deleting a user who is not active keeps the active one`() = runBlocking<Unit> {
        val g = generic()
        val ana = data.profiles.create(validInput("Ana")).ok()
        data.profiles.delete(ana.id).ok()
        assertEquals(g.id, data.profiles.activeUserId())
        assertEquals(1, data.profiles.profiles.first().size)
    }

    @Test fun `with several profiles the active user can be chosen and cleared`() = runBlocking<Unit> {
        val g = generic()
        val ana = data.profiles.create(validInput("Ana")).ok()
        assertEquals(g.id, data.profiles.activeUserId()) // creating a profile does not change who is active
        data.profiles.setActiveUser(ana.id).ok()
        assertEquals(ana, data.profiles.activeUser.first())
        data.profiles.setActiveUser(null).ok()
        assertNull(data.profiles.activeUser.first())
        assertEquals(DataResult.NotFound, data.profiles.setActiveUser(9999))
    }
}

class RoomGenericProfileTest : GenericProfileContractTest() {
    override fun create(seed: Boolean): UserData = newRoomUserData(MutableClock(), seed = seed)
}

@RunWith(JUnit4::class)
class InMemoryGenericProfileTest : GenericProfileContractTest() {
    override fun create(seed: Boolean): UserData = InMemoryUserData(MutableClock(), ZoneOffset.UTC, seed)
}
