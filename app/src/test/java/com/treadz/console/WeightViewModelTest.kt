package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.ValidationCode
import com.treadz.console.weight.WeightEditorKind
import com.treadz.console.weight.WeightViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WeightViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val app = FakeApp()
    private val vm by lazy { WeightViewModel(app.userData, app::today) }
    private val state get() = vm.uiState.value

    @Test fun freshInstallShowsOnlyThePlaceholder() {
        assertTrue(state.onlyPlaceholder)
        assertEquals(80.0, state.current!!.weightKg, 0.0)
        assertTrue(state.current!!.isPlaceholder)
    }

    @Test fun addingARealWeightReplacesThePlaceholder() {
        vm.openAdd()
        // The made-up 80 kg is not suggested as the value to enter.
        assertEquals("", state.editor!!.weightText)
        assertEquals(app.date, state.editor!!.date)
        vm.setWeightText("71,4")
        vm.saveEditor()
        assertNull(state.editor)
        assertFalse(state.onlyPlaceholder)
        assertEquals(listOf(71.4), state.entries.map { it.weightKg })
    }

    @Test fun addSuggestsTheCurrentRealWeight() {
        vm.openAdd(); vm.setWeightText("70"); vm.saveEditor()
        vm.openAdd()
        assertEquals("70.0", state.editor!!.weightText.replace(',', '.'))
    }

    @Test fun invalidWeightIsAnErrorOnTheField() {
        vm.openAdd()
        vm.setWeightText("")
        vm.saveEditor()
        assertEquals(ValidationCode.Blank, state.editor!!.errors[InputField.Weight])
        vm.setWeightText("12")
        vm.saveEditor()
        assertEquals(ValidationCode.OutOfRange, state.editor!!.errors[InputField.Weight])
        vm.setWeightText("abc")
        vm.saveEditor()
        assertEquals(ValidationCode.OutOfRange, state.editor!!.errors[InputField.Weight])
        assertTrue(state.onlyPlaceholder) // nothing was saved
        vm.setWeightText("70")
        assertTrue(state.editor!!.errors.isEmpty())
    }

    @Test fun futureDateIsAnErrorOnTheDateField() {
        vm.openAdd()
        vm.setWeightText("70")
        vm.setDate(app.date.plusDays(1))
        vm.saveEditor()
        assertEquals(ValidationCode.InFuture, state.editor!!.errors[InputField.WeightDate])
        vm.setDate(app.date)
        assertTrue(state.editor!!.errors.isEmpty())
    }

    @Test fun editingThePlaceholderTurnsItIntoARealEntry() {
        val placeholder = state.entries.single()
        vm.openEdit(placeholder)
        assertEquals("", state.editor!!.weightText)
        vm.setWeightText("76")
        vm.saveEditor()
        assertFalse(state.entries.single().isPlaceholder)
        assertEquals(76.0, state.current!!.weightKg, 0.0)
    }

    @Test fun editChangesAnEntry() {
        vm.openAdd(); vm.setWeightText("70"); vm.saveEditor()
        val entry = state.entries.single()
        vm.openEdit(entry)
        assertEquals(WeightEditorKind.Edit(entry.id), state.editor!!.kind)
        vm.setWeightText("69.5")
        vm.setDate(app.date.minusDays(3))
        vm.saveEditor()
        assertEquals(69.5, state.entries.single().weightKg, 0.0)
        assertEquals(app.date.minusDays(3), state.entries.single().date)
    }

    @Test fun deleteAsksForConfirmation() {
        vm.openAdd(); vm.setWeightText("70"); vm.saveEditor()
        vm.openAdd(); vm.setWeightText("69"); vm.saveEditor()
        val first = state.entries.first()
        vm.requestDelete(first.id)
        assertEquals(first.id, state.confirmingDeleteId)
        vm.cancelDelete()
        assertEquals(2, state.entries.size)
        vm.requestDelete(first.id)
        vm.confirmDelete()
        assertEquals(1, state.entries.size)
        assertNull(state.confirmingDeleteId)
    }

    @Test fun targetWeightCanBeSetChangedAndCleared() {
        assertNull(state.targetKg)
        vm.openTarget()
        assertFalse(state.editor!!.canClearTarget)
        vm.setWeightText("65")
        vm.saveEditor()
        assertEquals(65.0, state.targetKg!!, 0.0)
        vm.openTarget()
        assertTrue(state.editor!!.canClearTarget)
        assertEquals("65.0", state.editor!!.weightText.replace(',', '.'))
        vm.setWeightText("500")
        vm.saveEditor()
        assertEquals(ValidationCode.OutOfRange, state.editor!!.errors[InputField.TargetWeight])
        vm.clearTarget()
        assertNull(state.targetKg)
        assertNull(state.editor)
    }

    @Test fun followsTheActiveUser() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        app.userData.weights.add(ana.id, app.date, 61.0)
        assertEquals(80.0, state.current!!.weightKg, 0.0) // the generic user is active
        app.userData.profiles.setActiveUser(ana.id)
        assertEquals(ana.id, state.user!!.id)
        assertEquals(61.0, state.current!!.weightKg, 0.0)
        assertFalse(state.onlyPlaceholder)
        app.userData.profiles.setActiveUser(null)
        assertNull(state.user)
        assertTrue(state.entries.isEmpty())
        assertNotNull(app.userData.profiles.profiles.first())
    }
}
