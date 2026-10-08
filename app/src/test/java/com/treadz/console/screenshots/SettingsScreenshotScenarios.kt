package com.treadz.console.screenshots

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
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
import com.treadz.console.ui.BluetoothPermission
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Milestone 4 screens: the settings (sync with GitHub in each of its states, treadmill, language, permissions, about),
 * the top bar without the theme switch and the history's "not uploaded yet" mark. Subclasses set the language and the
 * screen size with `@Config(qualifiers = ...)`. Images are only written with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SettingsScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val repo = RepositoryRef("fedeaf", "treadz-data", "main")
    private val other = RepositoryRef("fedeaf", "treadz-backup", "main")
    private val upload = FirstLinkSummary(
        users = listOf(
            UserUpload("Ana", profile = true, weightEntries = 14, workouts = 23, programs = 0),
            UserUpload("Beto", profile = true, weightEntries = 3, workouts = 1, programs = 2),
        ),
        remoteUsers = 1,
    )

    private var app = FakeApp()
    private val data get() = app.userData

    private fun linked(error: SyncError? = null, pending: Int = 0, syncing: Boolean = false) = SyncStatus(
        setup = SyncSetup.Linked,
        repository = repo,
        lastSyncMillis = app.date.atTime(9, 41).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
        pendingCount = pending,
        isSyncing = syncing,
        error = error,
    )

    private fun capture(
        name: String,
        startAt: List<Screen> = listOf(Screen.Settings),
        permissions: PermissionsUi = PermissionsUi(),
        interact: () -> Unit = {},
    ) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = permissions, actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun captureDialog(name: String) {
        rule.waitForIdle()
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
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

    private fun scrollTo(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.waitForIdle()
    }

    private fun withDemo() = runBlocking { DemoData.populate(data, app.date) }

    private fun rememberedTreadmill() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))
    }

    // ---- Sync with GitHub ----

    @Test fun s01_not_configured() {
        rememberedTreadmill()
        capture("s01_not_configured")
    }

    @Test fun s02_token_entered_masked() {
        capture("s02_token_masked") { type("token_field", "github_pat_11ABCDEFG0123456789") }
    }

    @Test fun s03_token_entered_visible() {
        capture("s03_token_visible") {
            type("token_field", "github_pat_11ABCDEFG0123456789")
            click("token_visibility")
        }
    }

    @Test fun s04_token_rejected() {
        capture("s04_token_rejected") {
            type("token_field", "invalid")
            click("token_save")
        }
    }

    @Test fun s05_no_private_repository() {
        app = FakeApp(sync = FakeSyncService(privateRepositories = emptyList()))
        capture("s05_no_private_repository") {
            type("token_field", "github_pat_11ABCDEFG0123456789")
            click("token_save")
        }
    }

    @Test fun s06_choosing_repository() {
        app = FakeApp(sync = FakeSyncService(privateRepositories = listOf(repo, other, RepositoryRef("fedeaf", "notes-private", "main"))))
        capture("s06_choosing_repository") {
            type("token_field", "github_pat_11ABCDEFG0123456789")
            click("token_save")
        }
    }

    @Test fun s07_first_link_dialog() {
        withDemo()
        app = FakeApp(sync = FakeSyncService(localUploads = upload))
        capture("s07_first_link_behind") {
            type("token_field", "github_pat_11ABCDEFG0123456789")
            click("token_save")
        }
        captureDialog("s07_first_link_dialog")
    }

    @Test fun s08_linked_idle() {
        rememberedTreadmill()
        app.sync.emit(linked())
        capture("s08_linked_idle")
    }

    @Test fun s09_linked_syncing() {
        app.sync.emit(linked(pending = 4, syncing = true))
        capture("s09_linked_syncing")
    }

    @Test fun s10_linked_pending() {
        app.sync.emit(linked(pending = 4))
        capture("s10_linked_pending")
    }

    @Test fun s11_linked_error_no_network() {
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoNetwork), pending = 2))
        capture("s11_linked_no_network")
    }

    @Test fun s12_linked_invalid_token() {
        app.sync.emit(linked(error = SyncError(SyncErrorCode.Unauthorized)))
        capture("s12_linked_invalid_token")
    }

    @Test fun s12b_linked_replacement_token_other_repository() {
        app = FakeApp(sync = FakeSyncService(privateRepositories = listOf(other)))
        app.sync.emit(linked(error = SyncError(SyncErrorCode.Unauthorized)))
        capture("s12b_linked_replacement_other_repository") {
            type("token_field", "github_pat_11ABCDEFG0123456789")
            click("token_visibility")
            click("token_save")
            scrollTo("sync_error")
        }
    }

    @Test fun s13_linked_rate_limited() {
        app.sync.emit(linked(error = SyncError(SyncErrorCode.RateLimited, retryAfterMillis = 540_000L)))
        capture("s13_linked_rate_limited")
    }

    @Test fun s14_unlink_confirmation() {
        app.sync.emit(linked())
        capture("s14_unlink_behind") { click("sync_unlink") }
        captureDialog("s14_unlink_dialog")
    }

    // ---- The rest of the settings ----

    @Test fun g01_settings_light_theme() {
        app.settings.darkTheme = false
        app.sync.emit(linked())
        capture("g01_settings_light")
    }

    @Test fun g02_settings_permissions_missing() {
        capture(
            "g02_permissions_missing",
            permissions = PermissionsUi(BluetoothPermission.DeniedPermanently, notificationsDenied = true, notificationsGranted = false),
        ) { scrollTo("settings_permissions") }
    }

    @Test fun g03_settings_while_running() {
        rememberedTreadmill()
        app.controller.showConnected()
        app.controller.showRunning()
        capture("g03_settings_while_running") { scrollTo("settings_treadmill") }
    }

    @Test fun g04_settings_language_spanish_chosen() {
        app.language.language = AppLanguage.Spanish
        capture("g04_language_chosen") { scrollTo("settings_language") }
    }

    @Test fun g05_settings_about_with_recording() {
        app.recorder.showSaved()
        capture("g05_about_recording") { scrollTo("settings_about") }
    }

    // ---- Top bar ----

    @Test fun t01_top_bar_idle() {
        app.controller.showConnected()
        capture("t01_top_bar_idle", startAt = listOf(Screen.Main))
    }

    @Test fun t02_top_bar_sync_error() {
        app.controller.showConnected()
        app.sync.emit(linked(error = SyncError(SyncErrorCode.NoNetwork), pending = 3))
        capture("t02_top_bar_sync_error", startAt = listOf(Screen.Main))
    }

    @Test fun t03_top_bar_running_gear_disabled() {
        app.controller.showConnected()
        app.controller.showRunning()
        app.sync.emit(linked(error = SyncError(SyncErrorCode.ServerError)))
        capture("t03_top_bar_running", startAt = listOf(Screen.Main))
    }

    // ---- History and users ----

    @Test fun h01_history_pending_upload() {
        val (ana, _) = withDemo()
        val workouts = runBlocking { data.workouts.workouts(ana.id).first() }
        app.sync.setPendingWorkouts(workouts.take(2).map { it.id }.toSet())
        app.sync.emit(linked(pending = 2))
        capture("h01_history_pending", startAt = listOf(Screen.History))
    }

    @Test fun h02_history_not_linked_has_no_mark() {
        val (ana, _) = withDemo()
        val workouts = runBlocking { data.workouts.workouts(ana.id).first() }
        app.sync.setPendingWorkouts(workouts.take(2).map { it.id }.toSet())
        capture("h02_history_not_linked", startAt = listOf(Screen.History))
    }

    @Test fun u01_delete_user_warning_when_linked() {
        val (ana, _) = withDemo()
        app.sync.emit(linked())
        capture("u01_delete_user_behind", startAt = listOf(Screen.ProfileEditor(ana.id, 1))) { click("delete_button") }
        captureDialog("u01_delete_user_linked_dialog")
    }
}
