package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.training.ProgramsViewModel
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProgramsViewModelTest {

    @get:Rule val main = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val vm by lazy { ProgramsViewModel(data) { "$it (copy)" } }
    private val state get() = vm.uiState.value

    @Test fun listsTheEightStandardProgramsInTheCataloguesOrderWithTheirShape() {
        assertEquals(StandardPrograms.all.map { it.id }, state.standard.map { it.id })
        assertEquals(8, state.standard.size)
        state.standard.forEach { card ->
            assertEquals(ProgramKind.Standard, card.kind)
            assertTrue("${card.id} has a shape", card.segments.isNotEmpty())
            assertEquals(1_800, card.segments.last().endSeconds)
            assertTrue(card.minDurationSeconds != null && card.maxDurationSeconds != null)
            assertFalse(card.editable)
        }
        assertTrue(state.standard.first().standardKey == "hill")
    }

    @Test fun theUsersOwnProgramsAreListedForTheActiveUserOnly() {
        val (ana, beto) = runBlocking { DemoData.populate(data, app.date) }
        assertEquals(listOf(DemoPrograms.NORWEGIAN_ID), state.mine.map { it.id })
        val card = state.mine.single()
        assertEquals("Norwegian 4x4", card.name)
        assertEquals(43 * 60, card.durationSeconds)
        assertTrue(card.editable)
        assertTrue(card.segments.isNotEmpty())

        runBlocking { data.profiles.setActiveUser(beto.id) }
        assertTrue(state.mine.isEmpty())
        assertEquals(beto.id, state.user?.id)
        assertEquals(ana.id, runBlocking { data.programs.get(DemoPrograms.NORWEGIAN_ID)!!.userId })
    }

    @Test fun withoutAnActiveUserThereAreOnlyTheStandardPrograms() {
        runBlocking { data.profiles.setActiveUser(null) }
        assertNull(state.user)
        assertTrue(state.mine.isEmpty())
        assertEquals(8, state.standard.size)
    }

    @Test fun deletingAsksFirstAndRemovesTheProgram() {
        runBlocking { DemoData.populate(data, app.date) }
        vm.requestDelete(DemoPrograms.NORWEGIAN_ID)
        assertEquals(DemoPrograms.NORWEGIAN_ID, state.confirmingDeleteId)
        vm.cancelDelete()
        assertNull(state.confirmingDeleteId)
        assertEquals(1, state.mine.size)

        vm.requestDelete(DemoPrograms.NORWEGIAN_ID)
        vm.confirmDelete()
        assertTrue(state.mine.isEmpty())
        assertNull(runBlocking { data.programs.get(DemoPrograms.NORWEGIAN_ID) })
    }

    @Test fun duplicatingSavesACopyUnderANewIdAndName() {
        runBlocking { DemoData.populate(data, app.date) }
        var copyId: String? = null
        vm.duplicate(DemoPrograms.NORWEGIAN_ID) { copyId = it }

        val copy = checkNotNull(runBlocking { data.programs.get(checkNotNull(copyId)) }).program
        assertNotEquals(DemoPrograms.NORWEGIAN_ID, copy.id)
        assertEquals("Norwegian 4x4 (copy)", copy.name)
        assertEquals(DemoPrograms.norwegian4x4().steps, copy.steps)
        assertEquals(2, state.mine.size)
    }

    @Test fun aProgramThatCannotBeEditedIsMarkedSo() {
        val user = data.createProfile("Eva")
        runBlocking { data.profiles.setActiveUser(user.id) }
        val relative = com.treadz.console.programs.model.Program(
            id = "", name = "Relative", kind = ProgramKind.Free,
            steps = listOf(
                com.treadz.console.programs.model.Block(
                    com.treadz.console.programs.model.BlockPhase.Work, com.treadz.console.programs.model.BlockEnd.Duration(600),
                    com.treadz.console.programs.model.BlockTarget.Relative(50.0, 0.0),
                ),
            ),
        )
        runBlocking { data.programs.save(user.id, relative) }
        val card = state.mine.single()
        assertFalse(card.editable)
        assertTrue(card.segments.isNotEmpty()) // it can still be drawn and run
    }

    @Test fun theListIsLoadedForTheFirstFrame() {
        assertEquals(8, ProgramsViewModel(data).uiState.value.standard.size)
        assertFalse(runBlocking { data.programs.programs(1).first() }.isNotEmpty())
    }
}
