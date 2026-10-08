package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.settings.AppLanguage
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.settings.SettingsViewModel
import com.treadz.console.sync.FirstLinkSummary
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.UserUpload
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.FakeSyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.sync.service.SyncStatus
import com.treadz.console.treadmill.BeltStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The settings screen's logic: GitHub setup flow, language, theme, forgetting the treadmill, and the gear's availability. */
class SettingsViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val repo = RepositoryRef("demo", "treadz-data", "main")
    private val other = RepositoryRef("demo", "treadz-backup", "main")
    private val upload = FirstLinkSummary(
        users = listOf(UserUpload("Ana", profile = true, weightEntries = 12, workouts = 8, programs = 0)),
        remoteUsers = 1,
    )

    private fun appWith(remembered: RememberedTreadmill? = null, sync: FakeSyncService = FakeSyncService()) =
        FakeApp(settings = InMemorySettingsStore(remembered), sync = sync).also { it.viewModel }

    private fun settingsOf(app: FakeApp) = SettingsViewModel(app.sync, app.language, app.viewModel.uiState)

    private fun linked(lastSync: Long? = 1_000L, pending: Int = 0, error: SyncError? = null) =
        SyncStatus(setup = SyncSetup.Linked, repository = repo, lastSyncMillis = lastSync, pendingCount = pending, error = error)

    // ---- Token ----

    @Test fun startsNotConfiguredWithAnEmptyTokenField() {
        val vm = settingsOf(appWith())
        val state = vm.uiState.value
        assertEquals(SyncSetup.NotConfigured, state.sync.setup)
        assertTrue(state.showsTokenField)
        assertFalse(state.canSaveToken)
        assertFalse(state.tokenVisible)
    }

    @Test fun tokenIsOnlyKeptWhileTypedAndNeverShownBackAfterSaving() {
        val app = appWith()
        val vm = settingsOf(app)
        vm.setToken("  github_pat_secret  ")
        assertTrue(vm.uiState.value.canSaveToken)
        vm.saveToken()
        val state = vm.uiState.value
        assertEquals(listOf("configure"), app.sync.calls)
        assertEquals(SyncSetup.Linked, state.sync.setup)
        assertEquals("", state.tokenInput)
        assertFalse(state.toString().contains("github_pat_secret"))
        assertFalse(state.showsTokenField)
        assertNull(state.error)
    }

    @Test fun showAndHideTheToken() {
        val vm = settingsOf(appWith())
        vm.setTokenVisible(true)
        assertTrue(vm.uiState.value.tokenVisible)
        vm.setTokenVisible(false)
        assertFalse(vm.uiState.value.tokenVisible)
    }

    @Test fun blankTokenCannotBeSaved() {
        val app = appWith()
        val vm = settingsOf(app)
        vm.setToken("   ")
        assertFalse(vm.uiState.value.canSaveToken)
        vm.saveToken()
        assertTrue(app.sync.calls.isEmpty())
    }

    @Test fun rejectedTokenShowsTheErrorAndKeepsTheField() {
        val app = appWith()
        val vm = settingsOf(app)
        vm.setToken("invalid")
        vm.saveToken()
        val state = vm.uiState.value
        assertEquals(SyncErrorCode.Unauthorized, state.error?.code)
        assertEquals(SyncSetup.NotConfigured, state.sync.setup)
        assertEquals("invalid", state.tokenInput)
        assertFalse(state.busy)
        // Typing again clears the old message.
        vm.setToken("invalid2")
        assertNull(vm.uiState.value.error)
    }

    @Test fun tokenWithoutPrivateRepositoryShowsThatError() {
        val app = appWith(sync = FakeSyncService(privateRepositories = emptyList()))
        val vm = settingsOf(app)
        vm.setToken("token")
        vm.saveToken()
        assertEquals(SyncErrorCode.NoPrivateRepository, vm.uiState.value.error?.code)
        assertEquals(SyncSetup.NotConfigured, vm.uiState.value.sync.setup)
    }

    @Test fun leavingTheScreenDropsATypedToken() {
        val vm = settingsOf(appWith())
        vm.setToken("typed")
        vm.setTokenVisible(true)
        vm.clearToken()
        assertEquals("", vm.uiState.value.tokenInput)
        assertFalse(vm.uiState.value.tokenVisible)
    }

    // ---- Repository choice and first link ----

    @Test fun severalPrivateRepositoriesAreChosenFromAList() {
        val app = appWith(sync = FakeSyncService(privateRepositories = listOf(repo, other)))
        val vm = settingsOf(app)
        vm.setToken("token")
        vm.saveToken()
        assertEquals(SyncSetup.ChoosingRepository, vm.uiState.value.sync.setup)
        assertEquals(listOf(repo, other), vm.uiState.value.sync.repositories)
        vm.selectRepository(other)
        assertEquals(SyncSetup.Linked, vm.uiState.value.sync.setup)
        assertEquals(other, vm.uiState.value.sync.repository)
        assertEquals(listOf("configure", "selectRepository"), app.sync.calls)
    }

    @Test fun cancellingTheRepositoryChoiceForgetsTheToken() {
        val app = appWith(sync = FakeSyncService(privateRepositories = listOf(repo, other)))
        val vm = settingsOf(app)
        vm.setToken("token")
        vm.saveToken()
        vm.cancelChoice()
        assertEquals(SyncSetup.NotConfigured, vm.uiState.value.sync.setup)
        assertEquals("", vm.uiState.value.tokenInput)
        assertEquals("clear", app.sync.calls.last())
    }

    @Test fun dataToUploadAsksForConfirmationFirst() {
        val app = appWith(sync = FakeSyncService(localUploads = upload))
        val vm = settingsOf(app)
        vm.setToken("token")
        vm.saveToken()
        val state = vm.uiState.value
        assertEquals(SyncSetup.AwaitingFirstLinkConfirmation, state.sync.setup)
        assertEquals(upload, state.sync.firstLink)
        // Nothing was synced yet.
        assertFalse(app.sync.calls.contains("syncNow"))
    }

    @Test fun confirmingTheFirstLinkLinksAndSyncs() {
        val app = appWith(sync = FakeSyncService(localUploads = upload))
        val vm = settingsOf(app)
        vm.setToken("token")
        vm.saveToken()
        vm.confirmFirstLink()
        assertEquals(SyncSetup.Linked, vm.uiState.value.sync.setup)
        assertTrue(app.sync.calls.contains("confirmFirstLink"))
        assertNotNull(vm.uiState.value.sync.lastSyncMillis)
    }

    @Test fun cancellingTheFirstLinkForgetsEverythingAndUploadsNothing() {
        val app = appWith(sync = FakeSyncService(localUploads = upload))
        val vm = settingsOf(app)
        vm.setToken("token")
        vm.saveToken()
        vm.cancelFirstLink()
        assertEquals(SyncSetup.NotConfigured, vm.uiState.value.sync.setup)
        assertNull(vm.uiState.value.sync.firstLink)
        assertFalse(app.sync.calls.contains("confirmFirstLink"))
        assertFalse(app.sync.calls.contains("syncNow"))
    }

    // ---- Linked ----

    @Test fun syncNowAsksTheServiceAndStampsTheTime() {
        val app = appWith()
        app.sync.emit(linked(lastSync = null, pending = 3))
        val vm = settingsOf(app)
        assertNull(vm.uiState.value.sync.lastSyncMillis)
        vm.syncNow()
        assertEquals(listOf("syncNow"), app.sync.calls)
        val state = vm.uiState.value
        assertNotNull(state.sync.lastSyncMillis)
        assertEquals(0, state.sync.pendingCount)
        assertFalse(state.syncingNow)
    }

    @Test fun syncNowIsIgnoredWhenNotLinked() {
        val app = appWith()
        settingsOf(app).syncNow()
        assertTrue(app.sync.calls.isEmpty())
    }

    @Test fun everySyncErrorCodeReachesTheStateAsItsCode() {
        for (code in SyncErrorCode.entries) {
            val app = appWith()
            app.sync.emit(linked())
            app.sync.nextSyncError = SyncError(code, retryAfterMillis = 90_000L.takeIf { code == SyncErrorCode.RateLimited })
            val vm = settingsOf(app)
            vm.syncNow()
            assertEquals("code $code", code, vm.uiState.value.error?.code)
        }
    }

    @Test fun aSuccessfulSyncClearsTheError() {
        val app = appWith()
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoNetwork)))
        val vm = settingsOf(app)
        assertEquals(SyncErrorCode.NoNetwork, vm.uiState.value.error?.code)
        vm.syncNow()
        assertNull(vm.uiState.value.error)
    }

    @Test fun aNewTokenCanBePastedOnlyWhenTheCurrentOneIsTheProblem() {
        val app = appWith()
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoNetwork)))
        val vm = settingsOf(app)
        assertFalse(vm.uiState.value.canReplaceToken)
        app.sync.emit(linked(error = SyncError(SyncErrorCode.Unauthorized)))
        assertTrue(vm.uiState.value.canReplaceToken)
        assertTrue(vm.uiState.value.showsTokenField)
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoWriteAccess)))
        assertTrue(vm.uiState.value.canReplaceToken)
    }

    @Test fun unlinkNeedsConfirmationAndKeepsNothingOfTheLink() {
        val app = appWith()
        app.sync.emit(linked())
        val vm = settingsOf(app)
        vm.requestUnlink()
        assertTrue(vm.uiState.value.confirmingUnlink)
        assertFalse(app.sync.calls.contains("clear"))
        vm.confirmUnlink()
        assertEquals(listOf("clear"), app.sync.calls)
        assertEquals(SyncSetup.NotConfigured, vm.uiState.value.sync.setup)
        assertFalse(vm.uiState.value.confirmingUnlink)
    }

    @Test fun cancellingTheUnlinkLeavesTheLinkAlone() {
        val app = appWith()
        app.sync.emit(linked())
        val vm = settingsOf(app)
        vm.requestUnlink()
        vm.cancelUnlink()
        assertFalse(vm.uiState.value.confirmingUnlink)
        assertTrue(app.sync.calls.isEmpty())
        assertTrue(vm.uiState.value.linked)
    }

    // ---- Gear ----

    @Test fun settingsOpenOnlyWithTheBeltAtRest() {
        val app = appWith()
        val ui = { app.viewModel.uiState.value }
        assertTrue(ui().canOpenSettings)
        app.controller.showConnected()
        assertTrue(ui().canOpenSettings)
        app.controller.showRunning()
        assertFalse(ui().canOpenSettings)
        app.controller.showState { it.copy(status = BeltStatus.Paused) }
        assertFalse(ui().canOpenSettings)
        app.controller.showState { it.copy(status = BeltStatus.Starting) }
        assertFalse(ui().canOpenSettings)
        app.controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.0) }
        assertTrue(ui().canOpenSettings)
    }

    @Test fun theGearShowsAMarkOnlyWhileLinkedWithAnError() {
        val app = appWith()
        val ui = { app.viewModel.uiState.value }
        assertNull(ui().syncError)
        app.sync.emit(linked(error = SyncError(SyncErrorCode.ServerError)))
        assertEquals(SyncErrorCode.ServerError, ui().syncError?.code)
        app.sync.emit(linked())
        assertNull(ui().syncError)
        // An error from a setup attempt is not a "sync problem": the device is not linked.
        app.sync.emit(SyncStatus(error = SyncError(SyncErrorCode.Unauthorized)))
        assertNull(ui().syncError)
    }

    // ---- Treadmill ----

    @Test fun forgetTreadmillClearsTheRememberedOne() {
        val app = appWith(RememberedTreadmill("AA:BB", "REEBOK 0517"))
        val vm = settingsOf(app)
        assertTrue(vm.uiState.value.treadmillRemembered)
        assertEquals("REEBOK 0517", vm.uiState.value.treadmillName)
        assertTrue(vm.uiState.value.canForgetTreadmill)
        app.viewModel.forgetTreadmill()
        assertNull(app.settings.lastTreadmill)
        assertFalse(vm.uiState.value.treadmillRemembered)
        assertNull(vm.uiState.value.treadmillName)
        assertNull(app.viewModel.uiState.value.rememberedAddress)
    }

    @Test fun forgetTreadmillIsRefusedWhileTheBeltIsNotAtRest() {
        val app = appWith(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517"))
        app.controller.showConnected()
        app.controller.showRunning()
        val vm = settingsOf(app)
        assertFalse(vm.uiState.value.canForgetTreadmill)
        app.viewModel.forgetTreadmill()
        assertNotNull(app.settings.lastTreadmill)
    }

    @Test fun forgetTreadmillWorksWhileConnectedAndIdle() {
        val app = appWith(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517"))
        app.controller.showConnected()
        app.viewModel.forgetTreadmill()
        assertNull(app.settings.lastTreadmill)
    }

    @Test fun nothingToForgetWithoutARememberedTreadmill() {
        val app = appWith()
        val vm = settingsOf(app)
        assertFalse(vm.uiState.value.treadmillRemembered)
        assertFalse(vm.uiState.value.canForgetTreadmill)
    }

    // ---- Appearance and language ----

    @Test fun theThemeLivesInTheMainViewModelAndTheSettingsFollowIt() {
        val app = appWith()
        val vm = settingsOf(app)
        assertTrue(vm.uiState.value.darkTheme)
        app.viewModel.setDarkTheme(false)
        assertFalse(vm.uiState.value.darkTheme)
        assertFalse(app.settings.darkTheme)
        assertFalse(app.viewModel.uiState.value.darkTheme)
    }

    @Test fun languageStartsWithWhatAndroidReports() {
        val app = appWith()
        app.language.language = AppLanguage.Spanish
        assertEquals(AppLanguage.Spanish, settingsOf(app).uiState.value.language)
    }

    @Test fun choosingALanguageCallsThePlatformWithThatLanguage() {
        val app = appWith()
        val vm = settingsOf(app)
        vm.setLanguage(AppLanguage.Spanish)
        vm.setLanguage(AppLanguage.English)
        vm.setLanguage(AppLanguage.Automatic)
        assertEquals(listOf(AppLanguage.Spanish, AppLanguage.English, AppLanguage.Automatic), app.language.calls)
        assertEquals(AppLanguage.Automatic, vm.uiState.value.language)
    }

    @Test fun languageCannotChangeWhileTheBeltIsRunning() {
        val app = appWith()
        app.controller.showConnected()
        app.controller.showRunning()
        val vm = settingsOf(app)
        vm.setLanguage(AppLanguage.English)
        assertTrue(app.language.calls.isEmpty())
    }

    @Test fun languageIsReadAgainInCaseAndroidSettingsChangedIt() {
        val app = appWith()
        val vm = settingsOf(app)
        app.language.language = AppLanguage.English
        vm.refreshLanguage()
        assertEquals(AppLanguage.English, vm.uiState.value.language)
    }
}
