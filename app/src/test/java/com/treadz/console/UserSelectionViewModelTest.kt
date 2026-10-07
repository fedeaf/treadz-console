package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.users.UserSelectionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UserSelectionViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val app = FakeApp()
    private val vm by lazy { UserSelectionViewModel(app.userData.profiles, app.controller, app::today) }

    @Test fun listsProfilesWithAgeAndActiveMark() {
        val ana = app.userData.createProfile("Ana", age = 39)
        val cards = vm.uiState.value.users
        assertEquals(2, cards.size)
        assertTrue(cards.first().profile.isGeneric) // the generic profile comes first
        assertEquals(40, cards.first().ageYears)
        assertTrue(cards.first().isActive)
        assertEquals(39, cards.first { it.profile.id == ana.id }.ageYears)
        assertFalse(cards.first { it.profile.id == ana.id }.isActive)
    }

    @Test fun selectingSetsTheActiveUser() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        var selected = false
        vm.select(ana.id) { selected = true }
        assertTrue(selected)
        assertEquals(ana.id, app.userData.profiles.activeUserId())
        assertTrue(vm.uiState.value.users.first { it.profile.id == ana.id }.isActive)
    }

    @Test fun switchingIsBlockedWhileTheBeltIsNotStopped() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        val generic = app.userData.profiles.activeUserId()
        app.controller.showState { it.copy(status = BeltStatus.Running) }
        assertTrue(vm.uiState.value.switchLocked)
        var selected = false
        vm.select(ana.id) { selected = true }
        assertFalse(selected)
        assertEquals(generic, app.userData.profiles.activeUserId())
        // Carrying on with the current user is still possible.
        vm.select(generic!!) { selected = true }
        assertTrue(selected)
    }

    @Test fun switchingIsBlockedInEveryNonIdleState() = runBlocking {
        app.userData.createProfile("Ana")
        for (status in listOf(BeltStatus.Starting, BeltStatus.Paused, BeltStatus.SafetyKeyRemoved)) {
            app.controller.showState { it.copy(status = status) }
            assertTrue("$status", vm.uiState.value.switchLocked)
        }
        app.controller.showState { it.copy(status = BeltStatus.Idle) }
        assertFalse(vm.uiState.value.switchLocked)
    }
}
