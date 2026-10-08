package com.treadz.console.userdata

import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

private fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

private fun ProgramSaveResult.saved(): UserProgram = (this as? ProgramSaveResult.Saved)?.program ?: error("expected Saved but was $this")

/** Behaviour of [ProgramRepository] that every [UserData] implementation must have. */
@RunWith(RobolectricTestRunner::class)
abstract class ProgramsContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    private fun user(name: String = "Ana") = runBlocking { data.profiles.create(validInput(name)).ok() }

    @Test fun `a saved program is listed by name for its owner only`() = runBlocking<Unit> {
        val ana = user("Ana")
        val beto = user("Beto")
        data.programs.save(ana.id, norwegian(PROGRAM_1, "Zeta")).saved()
        data.programs.save(ana.id, norwegian(PROGRAM_2, "alpha")).saved()
        data.programs.save(beto.id, norwegian("5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c03", "Mine")).saved()

        assertEquals(listOf("alpha", "Zeta"), data.programs.programs(ana.id).first().map { it.program.name })
        assertEquals(listOf("Mine"), data.programs.programs(beto.id).first().map { it.program.name })
        assertEquals(emptyList<UserProgram>(), data.programs.programs(999).first())
        val stored = data.programs.get(PROGRAM_1)!!
        assertEquals(ana.id, stored.userId)
        assertEquals(PROGRAM_1, stored.id)
        assertEquals(norwegian(PROGRAM_1, "Zeta"), stored.program)
        assertEquals(stored, data.programs.program(PROGRAM_1).first())
        assertEquals(T0, stored.createdAtMillis)
        assertNull(data.programs.get("5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2cff"))
    }

    @Test fun `personalised programs keep their origin`() = runBlocking<Unit> {
        val ana = user()
        val origin = ProgramOrigin("norwegian-4x4", 2, linkedMapOf("maxHeartRateBpm" to "185"))
        val saved = data.programs.save(ana.id, norwegian(kind = ProgramKind.Personalised).copy(origin = origin)).saved()
        assertEquals(origin, saved.program.origin)
        assertEquals(ProgramKind.Personalised, data.programs.get(PROGRAM_1)!!.program.kind)
    }

    @Test fun `a program without an id gets one`() = runBlocking<Unit> {
        val ana = user()
        val saved = data.programs.save(ana.id, norwegian(id = "")).saved()
        assertTrue(saved.id, Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(saved.id))
        assertEquals(saved, data.programs.get(saved.id))
    }

    @Test fun `saving again replaces the program, and saving the same thing changes nothing`() = runBlocking<Unit> {
        val ana = user()
        val first = data.programs.save(ana.id, norwegian()).saved()
        clock.nowMillis = T0 + 5_000
        assertEquals(first, data.programs.save(ana.id, norwegian()).saved())
        val renamed = data.programs.save(ana.id, norwegian(name = "Renamed")).saved()
        assertEquals("Renamed", renamed.program.name)
        assertEquals(first.createdAtMillis, renamed.createdAtMillis)
        assertEquals(T0 + 5_000, renamed.updatedAtMillis)
        assertEquals(1, data.programs.programs(ana.id).first().size)
    }

    @Test fun `invalid programs are rejected with the paths of the problems and nothing is stored`() = runBlocking<Unit> {
        val ana = user()
        val broken = norwegian().copy(name = " ", steps = listOf(Block(BlockPhase.Work, BlockEnd.Duration(0), BlockTarget.Absolute(99.0, 0.0))))
        val result = data.programs.save(ana.id, broken)
        val paths = (result as ProgramSaveResult.Invalid).issues.map { it.path }
        assertTrue(paths.toString(), "name" in paths && "steps[0].durationSeconds" in paths && "steps[0].target.speedKmh" in paths)
        assertEquals(emptyList<UserProgram>(), data.programs.programs(ana.id).first())

        val empty = data.programs.save(ana.id, norwegian().copy(steps = emptyList()))
        assertEquals(listOf("steps"), (empty as ProgramSaveResult.Invalid).issues.map { it.path })
    }

    @Test fun `standard programs are not stored`() = runBlocking<Unit> {
        val ana = user()
        val result = data.programs.save(ana.id, norwegian(id = "standard-hill", kind = ProgramKind.Standard))
        assertTrue("kind" in (result as ProgramSaveResult.Invalid).issues.map { it.path })
        assertEquals(emptyList<UserProgram>(), data.programs.programs(ana.id).first())
    }

    @Test fun `an unknown user is not found, and an id of another user cannot be taken`() = runBlocking<Unit> {
        val ana = user("Ana")
        val beto = user("Beto")
        assertEquals(ProgramSaveResult.NotFound, data.programs.save(999, norwegian()))
        data.programs.save(ana.id, norwegian()).saved()
        val taken = data.programs.save(beto.id, norwegian(name = "Copy"))
        assertEquals(listOf("id"), (taken as ProgramSaveResult.Invalid).issues.map { it.path })
        assertEquals(ana.id, data.programs.get(PROGRAM_1)!!.userId)
    }

    @Test fun `deleting removes the program everywhere, and saving it again brings it back`() = runBlocking<Unit> {
        val ana = user()
        data.programs.save(ana.id, norwegian()).saved()
        assertEquals(DataResult.Success(Unit), data.programs.delete(PROGRAM_1))
        assertNull(data.programs.get(PROGRAM_1))
        assertNull(data.programs.program(PROGRAM_1).first())
        assertEquals(emptyList<UserProgram>(), data.programs.programs(ana.id).first())
        assertEquals(DataResult.NotFound, data.programs.delete(PROGRAM_1))
        assertEquals(DataResult.NotFound, data.programs.delete("5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2cff"))

        clock.nowMillis = T0 + 1_000
        val back = data.programs.save(ana.id, norwegian()).saved()
        assertEquals(listOf(back), data.programs.programs(ana.id).first())
    }

    @Test fun `deleting a profile deletes its programs and their last used parameters`() = runBlocking<Unit> {
        val ana = user("Ana")
        val beto = user("Beto")
        data.programs.save(ana.id, norwegian()).saved()
        data.programs.save(beto.id, norwegian(PROGRAM_2)).saved()
        data.startParameters.save(ana.id, PROGRAM_1, StartParameters(totalDurationSeconds = 1800)).ok()
        data.profiles.delete(ana.id).ok()
        assertNull(data.programs.get(PROGRAM_1))
        assertEquals(listOf(PROGRAM_2), data.programs.programs(beto.id).first().map { it.id })
        assertNull(data.startParameters.lastUsed(ana.id, PROGRAM_1).first())
    }

    @Test fun `deleting a program deletes the last used parameters of every user for it`() = runBlocking<Unit> {
        val ana = user("Ana")
        val beto = user("Beto")
        data.programs.save(ana.id, norwegian()).saved()
        val p = StartParameters(totalDurationSeconds = 2400, intensityAdjustmentPercent = 5)
        data.startParameters.save(ana.id, PROGRAM_1, p).ok()
        data.startParameters.save(beto.id, PROGRAM_1, p).ok()
        data.startParameters.save(ana.id, "standard-hill", p).ok()
        data.programs.delete(PROGRAM_1).ok()
        assertNull(data.startParameters.lastUsed(ana.id, PROGRAM_1).first())
        assertNull(data.startParameters.lastUsed(beto.id, PROGRAM_1).first())
        assertEquals(p, data.startParameters.lastUsed(ana.id, "standard-hill").first())
    }
}

class RoomProgramsTest : ProgramsContractTest() {
    override fun create(): UserData = newRoomUserData(clock)
}

@RunWith(JUnit4::class)
class InMemoryProgramsTest : ProgramsContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

/** [StartParametersRepository]: the last parameters used per user and program. */
@RunWith(RobolectricTestRunner::class)
abstract class StartParametersContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    private val data by lazy { create() }

    private val full = StartParameters(
        totalDurationSeconds = 2400, easySpeedKmh = 5.5, maxSpeedKmh = 11.0, maxInclinePercent = 8.0, intensityAdjustmentPercent = -10,
    )

    @Test fun `nothing is remembered until a program is started`() = runBlocking<Unit> {
        val ana = data.profiles.create(validInput()).ok()
        assertNull(data.startParameters.lastUsed(ana.id, "standard-hill").first())
    }

    @Test fun `the last parameters are remembered per user and program, standard or not`() = runBlocking<Unit> {
        val ana = data.profiles.create(validInput("Ana")).ok()
        val beto = data.profiles.create(validInput("Beto")).ok()
        data.startParameters.save(ana.id, "standard-hill", full).ok()
        data.startParameters.save(ana.id, PROGRAM_1, StartParameters()).ok()
        data.startParameters.save(beto.id, "standard-hill", full.copy(totalDurationSeconds = 1200)).ok()

        assertEquals(full, data.startParameters.lastUsed(ana.id, "standard-hill").first())
        assertEquals(StartParameters(), data.startParameters.lastUsed(ana.id, PROGRAM_1).first())
        assertEquals(1200, data.startParameters.lastUsed(beto.id, "standard-hill").first()!!.totalDurationSeconds)
        assertNull(data.startParameters.lastUsed(beto.id, PROGRAM_1).first())
        assertNull(data.startParameters.lastUsed(ana.id, "standard-pyramid").first())
    }

    @Test fun `saving again replaces the previous parameters`() = runBlocking<Unit> {
        val ana = data.profiles.create(validInput()).ok()
        data.startParameters.save(ana.id, "standard-hill", full).ok()
        data.startParameters.save(ana.id, "standard-hill", full.copy(totalDurationSeconds = null, intensityAdjustmentPercent = 15)).ok()
        val last = data.startParameters.lastUsed(ana.id, "standard-hill").first()!!
        assertNull(last.totalDurationSeconds)
        assertEquals(15, last.intensityAdjustmentPercent)
        assertEquals(5.5, last.easySpeedKmh!!, 0.0)
    }

    @Test fun `clear forgets them, and unknown users are not found`() = runBlocking<Unit> {
        val ana = data.profiles.create(validInput()).ok()
        data.startParameters.save(ana.id, "standard-hill", full).ok()
        data.startParameters.clear(ana.id, "standard-hill").ok()
        assertNull(data.startParameters.lastUsed(ana.id, "standard-hill").first())
        assertEquals(DataResult.NotFound, data.startParameters.save(999, "standard-hill", full))
    }

    @Test fun `the flow follows the changes`() = runBlocking<Unit> {
        val ana = data.profiles.create(validInput()).ok()
        val flow = data.startParameters.lastUsed(ana.id, "standard-hill")
        assertNull(flow.first())
        data.startParameters.save(ana.id, "standard-hill", full).ok()
        assertEquals(full, flow.first())
    }
}

class RoomStartParametersTest : StartParametersContractTest() {
    override fun create(): UserData = newRoomUserData(clock)
}

@RunWith(JUnit4::class)
class InMemoryStartParametersTest : StartParametersContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
