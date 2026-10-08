package com.treadz.console.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.nav.Screen
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.sync.service.SyncStatus
import com.treadz.console.ui.BluetoothPermission
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The whole settings page in one picture: the window is made tall enough to show every section without scrolling
 * (see the subclasses' `@Config`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SettingsFullPageScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private fun capture(name: String, app: FakeApp, permissions: PermissionsUi = PermissionsUi()) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = permissions, actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        navigation.push(Screen.Settings)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun appWithTreadmill() =
        FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))

    @Test fun f01_full_page_not_configured() {
        capture("f01_full_not_configured", appWithTreadmill())
    }

    @Test fun f02_full_page_linked_with_error() {
        val app = appWithTreadmill()
        app.sync.emit(
            SyncStatus(
                setup = SyncSetup.Linked,
                repository = RepositoryRef("fedeaf", "treadz-data", "main"),
                lastSyncMillis = app.date.atTime(9, 41).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                pendingCount = 3,
                error = SyncError(SyncErrorCode.NoNetwork),
            ),
        )
        app.language.language = com.treadz.console.settings.AppLanguage.Spanish
        capture(
            "f02_full_linked_error", app,
            PermissionsUi(BluetoothPermission.Granted, notificationsDenied = true, notificationsGranted = false),
        )
    }
}
