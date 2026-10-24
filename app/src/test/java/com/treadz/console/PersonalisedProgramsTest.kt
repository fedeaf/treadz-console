package com.treadz.console

import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.CouchToFiveKDecision
import com.treadz.console.programs.personalised.GeneratorNoticeCode
import com.treadz.console.programs.personalised.ProgramGenerators
import com.treadz.console.training.GeneratorCardState
import com.treadz.console.training.GeneratorForm
import com.treadz.console.training.ProgramsViewModel
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The Personalised part of the programs list: the generators and the Couch to 5K card; the programs of the user. */
class PersonalisedProgramsTest {

    @get:Rule val main = MainDispatcherRule()

    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val zone = ZoneId.systemDefault()
    private var clock = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val data = InMemoryUserData(clock = Clock { clock }, zone = zone)
    private val user: UserProfile by lazy { data.createProfile("Ana").also { runBlocking { data.profiles.setActiveUser(it.id) } } }
    private val vm by lazy { user; ProgramsViewModel(data, { clock }, { today }) { "$it (copy)" } }
    private val state get() = vm.uiState.value
    private fun card(id: String): GeneratorCardState = state.generators.single { it.descriptor.id == id }

    /** Saves a generated session of [generatorId] "generated" at [at] (epoch ms). */
    private fun saveSession(generatorId: String, at: Long, form: GeneratorForm? = null): String {
        clock = at
        val generated = GeneratedFixtures.generate(data, user, generatorId, form, today, at)
        return (runBlocking { data.programs.save(user.id, generated.program) } as ProgramSaveResult.Saved).program.id
    }

    private val day = GeneratedFixtures.DAY_MS
    private val noon get() = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // ---- The generators ----

    @Test fun theGeneratorsAreListedInTheRegistrysOrderWithCouchToFiveKOnItsOwn() {
        assertEquals(listOf("zone2", "tempo", "norwegian-4x4", "intervals-30-30"), state.generators.map { it.descriptor.id })
        assertEquals(ProgramGenerators.all.map { it.descriptor.id }.toSet(), (state.generators.map { it.descriptor.id } + CouchToFiveK.ID).toSet())
        assertTrue(state.generators.filter { it.descriptor.heartRateGuided }.map { it.descriptor.id }.containsAll(listOf("zone2", "tempo", "norwegian-4x4")))
        assertFalse(card("intervals-30-30").descriptor.heartRateGuided)
    }

    // ---- Sessions saved by an older version ----

    @Test fun personalisedProgramsSavedBeforeAreListedNowhere() {
        saveSession("zone2", noon - day)
        saveSession("tempo", noon)
        // A program of a generator this version does not know (synced from a newer app) is not listed either: it has an origin.
        val future = Program(
            id = "", name = "Future", kind = ProgramKind.Personalised,
            steps = listOf(Block(BlockPhase.Work, BlockEnd.Duration(600), BlockTarget.Absolute(6.0, 1.0))),
            origin = ProgramOrigin("future-generator", 1, mapOf("mode" to "run")),
        )
        runBlocking { data.programs.save(user.id, future) }

        assertTrue(state.mine.isEmpty())
        assertEquals(listOf("zone2", "tempo", "norwegian-4x4", "intervals-30-30"), state.generators.map { it.descriptor.id })
    }

    private fun saveFree(name: String = "Mine"): String {
        val free = Program(
            id = "", name = name, kind = ProgramKind.Free,
            steps = listOf(Block(BlockPhase.Work, BlockEnd.Duration(600), BlockTarget.Absolute(6.0, 1.0))),
        )
        return (runBlocking { data.programs.save(user.id, free) } as ProgramSaveResult.Saved).program.id
    }

    @Test fun myProgramsAreTheFreeOnesWithoutOrigin() {
        saveSession("zone2", noon)
        val id = saveFree()
        assertEquals(listOf(id), state.mine.map { it.id })
        assertTrue(state.mine.single().editable)
    }

    @Test fun deletingAProgramAsksFirstAndRemovesIt() {
        val id = saveFree()
        vm.requestDelete(id)
        assertEquals(id, state.confirmingDeleteId)
        assertEquals("Mine", state.nameOf(id))
        vm.cancelDelete()
        assertEquals(1, state.mine.size)

        vm.requestDelete(id)
        vm.confirmDelete()
        assertTrue(state.mine.isEmpty())
        assertNull(runBlocking { data.programs.get(id) })
    }

    @Test fun duplicatingMakesAnEditableCopy() {
        val id = saveFree()
        var copyId: String? = null
        vm.duplicate(id) { copyId = it }
        val copy = checkNotNull(runBlocking { data.programs.get(checkNotNull(copyId)) }).program

        assertEquals(ProgramKind.Free, copy.kind)
        assertNull(copy.origin)
        assertEquals("Mine (copy)", copy.name)
        assertEquals(2, state.mine.size)
        assertTrue(state.mine.single { it.id == copyId }.editable)
    }

    // ---- The Couch to 5K card ----

    @Test fun withoutHistoryTheCardOffersTheFirstSession() {
        val couch = checkNotNull(state.couch)
        assertEquals(1, couch.selection.session)
        assertEquals(1, couch.selection.week)
        assertEquals(1, couch.selection.day)
        assertEquals(CouchToFiveKDecision.FirstSession, couch.selection.decision)
        assertNull(couch.lastSessionAtMillis)
    }

    @Test fun aCompletedSessionMovesOnAndTheCardShowsWhenItWas() {
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 7, startedAtMillis = noon - 4 * day))
        val couch = checkNotNull(state.couch)
        assertEquals(8, couch.selection.session)
        assertEquals(3, couch.selection.week)
        assertEquals(2, couch.selection.day)
        assertEquals(CouchToFiveKDecision.Advance, couch.selection.decision)
        assertEquals(noon - 4 * day + 1_800_000L, couch.lastSessionAtMillis)
        assertTrue(couch.notices.none { it.code == GeneratorNoticeCode.RecentSession })
    }

    @Test fun aHardEffortOrAManualReductionRepeatsTheSession() {
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 7, startedAtMillis = noon - 4 * day, endHeartRate = 165.0))
        assertEquals(7, state.couch!!.selection.session)
        assertEquals(CouchToFiveKDecision.RepeatHardEffort, state.couch!!.selection.decision)
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 7, startedAtMillis = noon - 2 * day, manualReductions = 1))
        assertEquals(CouchToFiveKDecision.RepeatManualReduction, state.couch!!.selection.decision)
    }

    @Test fun anIncompleteSessionStepsBackAndTheUnder48HourWarningNeverBlocks() {
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 7, startedAtMillis = noon - 10 * 3_600_000L, completed = false))
        val couch = checkNotNull(state.couch)
        assertEquals(6, couch.selection.session)
        assertEquals(CouchToFiveKDecision.StepBack, couch.selection.decision)
        assertTrue(couch.selection.recentSessionWarning)
        assertTrue(couch.notices.any { it.code == GeneratorNoticeCode.RecentSession })
    }

    @Test fun aLongBreakMovesThePlanBack() {
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 10, startedAtMillis = noon - 15 * day))
        val couch = checkNotNull(state.couch)
        assertEquals(1, couch.selection.weeksBack)
        assertEquals(8, couch.selection.session) // advance to 11, three sessions back
        assertTrue(couch.notices.any { it.code == GeneratorNoticeCode.LongBreak })
    }

    @Test fun thePlanCompletedIsOfferedAgain() {
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 27, startedAtMillis = noon - 3 * day))
        val couch = checkNotNull(state.couch)
        assertEquals(27, couch.selection.session)
        assertEquals(CouchToFiveKDecision.PlanCompleted, couch.selection.decision)
    }

    @Test fun eachUserHasTheirOwnPlan() {
        GeneratedFixtures.addSession(data, user.id, GeneratedFixtures.record(session = 7, startedAtMillis = noon - 4 * day))
        assertEquals(8, state.couch!!.selection.session)
        val beto = data.createProfile("Beto")
        runBlocking { data.profiles.setActiveUser(beto.id) }
        assertEquals(1, state.couch!!.selection.session)
        assertEquals(beto.id, state.user!!.id)
    }

    @Test fun withoutAUserThereIsNoCardAndNothingIsSaved() {
        user
        runBlocking { data.profiles.setActiveUser(null) }
        assertNull(state.couch)
        assertTrue(runBlocking { data.programs.programs(user.id).first() }.isEmpty())
    }
}
