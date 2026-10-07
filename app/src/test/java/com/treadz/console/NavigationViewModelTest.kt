package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.nav.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Startup flow and back stack. */
class NavigationViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val app = FakeApp()

    @Test fun freshInstallGoesStraightToTheMainScreen() {
        assertEquals(listOf(Screen.Main), app.newNavigation().stack.value)
    }

    @Test fun oneNamedProfileGoesStraightToTheMainScreen() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        app.userData.profiles.setActiveUser(ana.id)
        app.userData.profiles.delete(app.userData.profiles.profiles.first().first { it.isGeneric }.id)
        assertEquals(listOf(Screen.Main), app.newNavigation().stack.value)
    }

    @Test fun severalProfilesAskWhoIsTrainingEveryTimeTheAppOpens() {
        app.userData.createProfile("Ana")
        assertEquals(listOf(Screen.UserSelection), app.newNavigation().stack.value)
        // A second start of the app (a new navigation state) asks again, even if somebody is already active.
        assertEquals(listOf(Screen.UserSelection), app.newNavigation().stack.value)
    }

    @Test fun nobodyActiveForcesTheSelectionEvenWithOneProfile() = runBlocking {
        app.userData.profiles.setActiveUser(null)
        assertEquals(listOf(Screen.UserSelection), app.newNavigation().stack.value)
    }

    @Test fun deletingTheActiveUserLaterForcesTheSelection() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        val nav = app.newNavigation()
        nav.replaceAll(Screen.Main)
        app.userData.profiles.setActiveUser(ana.id)
        app.userData.profiles.delete(ana.id)
        assertEquals(listOf(Screen.UserSelection), nav.stack.value)
    }

    @Test fun forcedSelectionAlsoReplacesScreensOpenedOnTopOfIt() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        val nav = app.newNavigation()
        app.userData.profiles.setActiveUser(ana.id)
        nav.push(Screen.Main)
        nav.push(Screen.Weight)
        app.userData.profiles.delete(ana.id)
        assertEquals(listOf(Screen.UserSelection), nav.stack.value)
    }

    @Test fun theEditorStaysOpenWhileNobodyIsActive() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        app.userData.profiles.setActiveUser(ana.id)
        val nav = app.newNavigation()
        nav.openEditor(ana.id)
        app.userData.profiles.delete(ana.id)
        assertTrue(nav.stack.value!!.last() is Screen.ProfileEditor)
        nav.onProfileDeleted()
        assertEquals(listOf(Screen.UserSelection), nav.stack.value)
    }

    @Test fun pushAndPopKeepTheBottomScreen() {
        val nav = app.newNavigation()
        nav.push(Screen.Weight)
        nav.push(Screen.History)
        assertEquals(listOf(Screen.Main, Screen.Weight, Screen.History), nav.stack.value)
        nav.pop()
        nav.pop()
        nav.pop()
        assertEquals(listOf(Screen.Main), nav.stack.value)
    }

    @Test fun everyEditorVisitIsANewScreen() {
        val nav = app.newNavigation()
        nav.openEditor(null)
        val first = nav.stack.value!!.last()
        nav.pop()
        nav.openEditor(null)
        assertTrue(first != nav.stack.value!!.last())
    }

    @Test fun deletingTheLastChoiceReturnsToMain() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        val nav = app.newNavigation()
        assertEquals(listOf(Screen.UserSelection), nav.stack.value)
        nav.openEditor(ana.id)
        app.userData.profiles.delete(ana.id)
        nav.onProfileDeleted()
        assertEquals(listOf(Screen.Main), nav.stack.value)
    }
}
