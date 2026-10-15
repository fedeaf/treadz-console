package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.fakes.createProfile
import com.treadz.console.nav.Screen
import com.treadz.console.settings.AppLanguage
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.sync.FirstLinkSummary
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.UserUpload
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.FakeSyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.sync.service.SyncStatus
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.BluetoothPermission
import com.treadz.console.ui.TreadZRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The settings screen and its entry point through the real UI (Compose + ViewModels) on the fakes. Tablet in landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class SettingsUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = RepositoryRef("demo", "treadz-data", "main")
    private val upload = FirstLinkSummary(
        users = listOf(
            UserUpload("Ana", profile = true, weightEntries = 12, workouts = 8, programs = 0),
            UserUpload("Beto", profile = true, weightEntries = 1, workouts = 1, programs = 2),
        ),
        remoteUsers = 1,
    )
    private var app = FakeApp()
    private var shared: String? = null
    private var openedAppSettings = 0
    private var openedDownloads = 0

    private fun show(vararg then: Screen, permissions: PermissionsUi = PermissionsUi()) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(
                app, app.viewModel, permissions,
                MainActions(
                    onShare = { shared = it.fileName },
                    onOpenSettings = { openedAppSettings++ },
                    onOpenDownloads = { openedDownloads++ },
                ),
                navigation,
            )
        }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun type(tag: String, text: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performTextInput(text)
        rule.waitForIdle()
    }

    private fun linked(error: SyncError? = null, pending: Int = 0) =
        SyncStatus(setup = SyncSetup.Linked, repository = repo, lastSyncMillis = 1_000L, pendingCount = pending, error = error)

    // ---- Entry point ----

    @Test fun theTopBarHasAGearAndNoThemeSwitch() {
        app.controller.showConnected()
        show()
        rule.onNodeWithTag("settings_button").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("theme_switch").assertDoesNotExist()
    }

    @Test fun theGearIsDisabledWhileTheBeltIsNotAtRest() {
        app.controller.showConnected()
        app.controller.showRunning()
        show()
        rule.onNodeWithTag("settings_button").assertIsNotEnabled()
        click("settings_button")
        rule.onNodeWithTag("settings_sync").assertDoesNotExist()
    }

    @Test fun theGearOpensTheSettingsAndBackReturns() {
        app.controller.showConnected()
        show()
        click("settings_button")
        rule.onNodeWithTag("screen_title").assertTextEquals("Settings")
        rule.onNodeWithTag("settings_sync").assertIsDisplayed()
        click("back_button")
        rule.onNodeWithTag("start_button").assertIsDisplayed()
    }

    @Test fun aSyncErrorMarksTheGearOnlyWhileLinked() {
        app.controller.showConnected()
        show()
        rule.onNodeWithContentDescription("Settings, Sync problem").assertDoesNotExist()
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoNetwork)))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Settings, Sync problem").assertIsDisplayed()
        app.sync.emit(linked())
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Settings, Sync problem").assertDoesNotExist()
    }

    // ---- Sync: token ----

    @Test fun notConfiguredShowsTheExplanationTheMaskedTokenFieldAndHelp() {
        show(Screen.Settings)
        rule.onNodeWithTag("token_field").assertIsDisplayed()
        rule.onNodeWithTag("token_help").assertIsDisplayed()
        rule.onNodeWithTag("token_save").assertIsNotEnabled()
        type("token_field", "ghp_secret")
        rule.onNodeWithTag("token_save").assertIsEnabled()
        // Masked until "Show" is tapped.
        fun shown() = rule.onNodeWithTag("token_field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertFalse(shown().contains("ghp_secret"))
        click("token_visibility")
        assertTrue(shown().contains("ghp_secret"))
    }

    @Test fun savingATokenLinksAndNeverShowsItAgain() {
        show(Screen.Settings)
        type("token_field", "ghp_secret")
        click("token_save")
        rule.onNodeWithTag("sync_repository").assertTextContains("demo/treadz-data", substring = true)
        rule.onNodeWithTag("token_saved_note").assertIsDisplayed()
        rule.onNodeWithTag("token_field").assertDoesNotExist()
        rule.onNodeWithText("ghp_secret", substring = true).assertDoesNotExist()
        assertEquals(listOf("configure"), app.sync.calls)
    }

    @Test fun aRejectedTokenShowsTheTranslatedError() {
        show(Screen.Settings)
        type("token_field", "invalid")
        click("token_save")
        rule.onNodeWithTag("sync_error").assertTextContains("rejected the token", substring = true)
        rule.onNodeWithTag("token_field").assertIsDisplayed()
    }

    @Test fun everySyncErrorHasATranslatedTextAndNeverShowsTheRawCode() {
        show(Screen.Settings)
        for (code in SyncErrorCode.entries) {
            app.sync.emit(linked(error = SyncError(code, retryAfterMillis = 120_000L.takeIf { code == SyncErrorCode.RateLimited })))
            rule.waitForIdle()
            val text = rule.onNodeWithTag("sync_error").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            assertTrue("no text for $code", text.length > 20)
            assertFalse("raw code shown for $code: $text", text.contains(code.name, ignoreCase = true))
        }
    }

    @Test fun aRateLimitSaysHowLongToWait() {
        show(Screen.Settings)
        app.sync.emit(linked(error = SyncError(SyncErrorCode.RateLimited, retryAfterMillis = 125_000L)))
        rule.waitForIdle()
        rule.onNodeWithTag("sync_error").assertTextContains("about 3 min", substring = true)
    }

    @Test fun anInvalidTokenWhileLinkedOffersAFieldForANewOne() {
        app.sync.emit(linked(error = SyncError(SyncErrorCode.Unauthorized)))
        show(Screen.Settings)
        rule.onNodeWithTag("token_field").assertIsDisplayed()
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoNetwork)))
        rule.waitForIdle()
        rule.onNodeWithTag("token_field").assertDoesNotExist()
    }

    @Test fun theTokenButtonSaysLinkFirstAndUseThisTokenWhenReplacingOne() {
        show(Screen.Settings)
        rule.onNodeWithTag("token_save").assertTextContains("Link")

        app.sync.emit(linked(error = SyncError(SyncErrorCode.Unauthorized)))
        rule.waitForIdle()
        rule.onNodeWithTag("token_save").assertTextContains("Use this token")
    }

    @Test fun aReplacementTokenForAnotherRepositoryExplainsToUnlinkFirstAndStaysLinked() {
        app = FakeApp(sync = FakeSyncService(privateRepositories = listOf(RepositoryRef("demo", "treadz-backup", "main"))))
        app.sync.emit(linked(error = SyncError(SyncErrorCode.Unauthorized)))
        show(Screen.Settings)
        type("token_field", "github_pat_other_repo")
        click("token_save")
        rule.onNodeWithTag("sync_error").assertTextContains("cannot access the linked repository", substring = true)
        rule.onNodeWithTag("sync_error").assertTextContains("unlink this device first", substring = true)
        rule.onNodeWithTag("sync_repository").assertTextContains("demo/treadz-data", substring = true)
        rule.onNodeWithTag("sync_now").assertIsEnabled()
        rule.onNodeWithTag("sync_unlink").assertIsDisplayed()
        rule.onNodeWithTag("token_field").assertIsDisplayed()
    }

    @Test fun aLeftoverTokenErrorCanBeDismissedWhileNotConfigured() {
        show(Screen.Settings)
        type("token_field", "invalid")
        click("token_save")
        rule.onNodeWithTag("sync_error").assertIsDisplayed()
        click("sync_error_dismiss")
        rule.onNodeWithTag("sync_error").assertDoesNotExist()
        rule.onNodeWithTag("sync_error_dismiss").assertDoesNotExist()
    }

    // ---- Sync: repository choice and first link ----

    @Test fun severalPrivateRepositoriesAreListedAndOneIsPicked() {
        app = FakeApp(sync = FakeSyncService(privateRepositories = listOf(repo, RepositoryRef("demo", "treadz-backup", "main"))))
        show(Screen.Settings)
        type("token_field", "token")
        click("token_save")
        rule.onNodeWithTag("repository_choice").assertIsDisplayed()
        rule.onNodeWithTag("repository_demo/treadz-backup").assertIsDisplayed()
        click("repository_demo/treadz-backup")
        rule.onNodeWithTag("sync_repository").assertTextContains("demo/treadz-backup", substring = true)
    }

    @Test fun theFirstLinkDialogListsWhatWouldBeUploadedAndCanBeCancelled() {
        app = FakeApp(sync = FakeSyncService(localUploads = upload))
        show(Screen.Settings)
        type("token_field", "token")
        click("token_save")
        rule.onNodeWithTag("first_link_dialog").assertIsDisplayed()
        rule.onNodeWithText("Ana").assertIsDisplayed()
        rule.onNodeWithText("Profile · 12 weight entries · 8 workouts", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Profile · 1 weight entry · 1 workout · 2 programs").assertIsDisplayed()
        rule.onNodeWithTag("first_link_remote").assertTextContains("1 user is already", substring = true)
        rule.onNodeWithText("Nothing has been uploaded yet", substring = true).assertIsDisplayed()
        assertFalse(app.sync.calls.contains("confirmFirstLink"))
        click("first_link_cancel")
        rule.onNodeWithTag("first_link_dialog").assertDoesNotExist()
        rule.onNodeWithTag("token_field").assertIsDisplayed()
        assertEquals(SyncSetup.NotConfigured, app.sync.status.value.setup)
    }

    @Test fun confirmingTheFirstLinkLinks() {
        app = FakeApp(sync = FakeSyncService(localUploads = upload))
        show(Screen.Settings)
        type("token_field", "token")
        click("token_save")
        click("first_link_confirm")
        rule.onNodeWithTag("first_link_dialog").assertDoesNotExist()
        rule.onNodeWithTag("sync_now").assertIsDisplayed()
        assertTrue(app.sync.calls.contains("confirmFirstLink"))
    }

    // ---- Sync: linked ----

    @Test fun linkedShowsRepositoryLastSyncPendingAndTheActions() {
        app.sync.emit(linked(pending = 3))
        show(Screen.Settings)
        rule.onNodeWithTag("sync_repository").assertTextContains("demo/treadz-data", substring = true)
        rule.onNodeWithTag("sync_pending").assertTextContains("3 changes", substring = true)
        rule.onNodeWithTag("sync_now").assertIsEnabled()
        rule.onNodeWithTag("sync_unlink").assertIsDisplayed()
        rule.onNodeWithTag("sync_progress").assertDoesNotExist()
    }

    @Test fun syncingShowsAnIndicatorAndDisablesSyncNow() {
        app.sync.emit(linked().copy(isSyncing = true))
        show(Screen.Settings)
        rule.onNodeWithTag("sync_progress").assertIsDisplayed()
        rule.onNodeWithTag("sync_now").assertIsNotEnabled()
    }

    @Test fun syncNowCallsTheService() {
        app.sync.emit(linked(pending = 2))
        show(Screen.Settings)
        click("sync_now")
        assertEquals(listOf("syncNow"), app.sync.calls)
        rule.onNodeWithTag("sync_pending").assertTextContains("Nothing", substring = true)
    }

    @Test fun unlinkAsksForConfirmationAndExplainsNothingIsDeleted() {
        app.sync.emit(linked())
        show(Screen.Settings)
        click("sync_unlink")
        rule.onNodeWithText("nothing is deleted from the repository", substring = true).assertIsDisplayed()
        assertTrue(app.sync.calls.isEmpty())
        click("cancel_button")
        assertTrue(app.sync.calls.isEmpty())
        click("sync_unlink")
        click("confirm_button")
        assertEquals(listOf("clear"), app.sync.calls)
        rule.onNodeWithTag("token_field").assertIsDisplayed()
    }

    // ---- Users ----

    @Test fun manageUsersOpensTheUserSelection() {
        data.createProfile("Ana")
        show(Screen.Settings)
        click("settings_manage_users")
        rule.onNodeWithTag("add_user_button").assertIsDisplayed()
        click("back_button")
        rule.onNodeWithTag("settings_users").assertIsDisplayed()
    }

    @Test fun deleteWarningMentionsEveryLinkedDeviceOnlyWhenLinked() {
        val ana = data.createProfile("Ana")
        runBlocking { data.profiles.setActiveUser(ana.id) }
        show(Screen.Main, Screen.ProfileEditor(ana.id, 1))
        click("delete_button")
        rule.onNodeWithText("every device linked through GitHub", substring = true).assertDoesNotExist()
        click("cancel_button")
        app.sync.emit(linked())
        rule.waitForIdle()
        click("delete_button")
        rule.onNodeWithText("every device linked through GitHub", substring = true).assertIsDisplayed()
    }

    private val data get() = app.userData

    // ---- Treadmill ----

    @Test fun forgetTreadmillClearsTheRememberedOne() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill("AA:BB", "REEBOK 0517")))
        show(Screen.Settings)
        rule.onNodeWithTag("settings_treadmill_name").assertTextEquals("REEBOK 0517")
        click("forget_treadmill")
        assertNull(app.settings.lastTreadmill)
        rule.onNodeWithTag("settings_treadmill_none").assertIsDisplayed()
    }

    @Test fun forgetTreadmillIsDisabledWhileTheBeltRuns() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))
        app.controller.showConnected()
        show(Screen.Settings)
        app.controller.showRunning()
        rule.waitForIdle()
        rule.onNodeWithTag("forget_treadmill").assertIsNotEnabled()
        rule.onNodeWithTag("settings_belt_locked").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings_belt_locked_language").performScrollTo().assertIsDisplayed()
    }

    // ---- Appearance and language ----

    @Test fun theThemeSwitchIsInTheSettingsAndPersists() {
        show(Screen.Settings)
        click("theme_switch")
        assertFalse(app.settings.darkTheme)
        assertFalse(app.viewModel.uiState.value.darkTheme)
        click("theme_switch")
        assertTrue(app.settings.darkTheme)
    }

    @Test fun languageChipsCallThePlatformWithTheRightLanguage() {
        show(Screen.Settings)
        click("language_es")
        click("language_en")
        click("language_auto")
        assertEquals(listOf(AppLanguage.Spanish, AppLanguage.English, AppLanguage.Automatic), app.language.calls)
    }

    // ---- Permissions and about ----

    @Test fun permissionsShowTheirStatusAndOfferTheAppSettingsOnlyWhenSomethingIsMissing() {
        show(Screen.Settings)
        rule.onNodeWithTag("open_app_settings").assertDoesNotExist()
    }

    @Test fun missingPermissionsOfferTheAppSettings() {
        show(Screen.Settings, permissions = PermissionsUi(BluetoothPermission.DeniedPermanently, notificationsDenied = true, notificationsGranted = false))
        rule.onNodeWithTag("perm_bluetooth").assertTextContains("Not allowed", substring = true)
        rule.onNodeWithTag("perm_notifications").assertTextContains("Not allowed", substring = true)
        click("open_app_settings")
        assertEquals(1, openedAppSettings)
    }

    @Test fun aboutShowsTheVersionAndOpensDownloads() {
        show(Screen.Settings)
        rule.onNodeWithTag("about_version").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        click("open_downloads")
        assertEquals(1, openedDownloads)
        // No recording yet: nothing to share.
        rule.onNodeWithTag("share_last_recording").assertDoesNotExist()
    }

    @Test fun theLastRecordingCanBeShared() {
        val info = RecordingInfo("treadz_20261007_183000.jsonl", 1_000L)
        app.recorder.status.value = RecordingStatus.Saved(info, "Download/TreadZ/treadz_20261007_183000.jsonl")
        show(Screen.Settings)
        click("share_last_recording")
        assertEquals("treadz_20261007_183000.jsonl", shared)
    }
}
