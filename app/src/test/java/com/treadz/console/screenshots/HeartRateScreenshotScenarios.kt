package com.treadz.console.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.devices.LiveHeartRate
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.hrm.resting.RestingHeartRateProgress
import com.treadz.console.hrm.resting.RestingHeartRateResult
import com.treadz.console.hrm.resting.RestingInvalidReason
import com.treadz.console.nav.Screen
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.resting.ManualRestingEditor
import com.treadz.console.resting.RestingPhase
import com.treadz.console.resting.RestingUiState
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.RestingActions
import com.treadz.console.ui.RestingHeartRateScreen
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateMeasurement
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Milestone 5 phase C1 screens: the home and the device indicators, Devices, live data with the heart-rate source, the
 * resting heart rate screen, the maximum heart rate and the observed-peak offer, the origin of the values in the
 * profile, and a heart-rate guided block. Subclasses set the language and screen size with `@Config(qualifiers = ...)`.
 * Images are only written with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class HeartRateScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp(strapPairingScanMillis = 20)
    private val data get() = app.userData
    private val day = 86_400_000L

    /** Opens the app (as after a start), goes to the screens of [startAt] and runs [interact] before the picture. */
    private fun capture(name: String, theApp: FakeApp = app, startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        val navigation = theApp.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = theApp, viewModel = theApp.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
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

    /** Draws [content] inside the app frame, like the root does, without the navigation. */
    private fun host(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun demo(): UserProfile = runBlocking { DemoData.populate(data, app.date).first }

    private fun restingHistory(userId: Long, theApp: FakeApp = app) = runBlocking {
        val values = listOf(60, 59, 61, 58, 58, 60, 57, 59, 58, 57, 58, 57)
        values.forEachIndexed { i, bpm ->
            theApp.userData.restingHeartRates.add(
                userId,
                RestingHeartRateInput(
                    theApp.nowMillis() - (values.size - i) * 2 * day + 7 * 3_600_000L, bpm,
                    if (i % 3 == 0) RestingHeartRateSource.Manual else RestingHeartRateSource.Strap,
                    rmssdMs = if (i % 3 == 0) null else 38.0 + i,
                ),
            )
        }
    }

    private fun pairStrap(userId: Long) {
        runBlocking { data.profiles.setStrap(userId, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
    }

    private fun remembered() {
        app.settings.lastTreadmill = RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, FakeTreadmillController.TREADMILL_NAME)
    }

    private val notFound = CommandResult.Rejected(FailureCode.ConnectionFailed, "raw", ConnectionProblem.DeviceNotFound)

    // ---- Home ----

    @Test fun a01_home_without_treadmill_and_without_strap() {
        val ana = demo()
        restingHistory(ana.id)
        capture("a01_home_no_strap")
    }

    @Test fun a02_home_with_strap_connected() {
        val ana = demo()
        restingHistory(ana.id)
        pairStrap(ana.id)
        app.strapMonitor.showConnected(bpm = 64)
        capture("a02_home_strap")
    }

    @Test fun a03_home_new_user_nothing_yet() {
        remembered()
        capture("a03_home_new_user")
    }

    @Test fun a04_home_treadmill_searching() {
        remembered()
        val ana = demo()
        pairStrap(ana.id)
        app.controller.connectResult = notFound
        app.viewModel.onPermissionsGranted()
        capture("a04_home_searching")
    }

    @Test fun a05_home_treadmill_not_found() {
        remembered()
        demo()
        app.controller.connectResult = notFound
        app.viewModel.onPermissionsGranted()
        app.viewModel.reconnectToRemembered()
        capture("a05_home_not_found")
    }

    @Test fun a06_home_pairing_card() {
        demo()
        app.strapMonitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
        app.viewModel.onPermissionsGranted()
        capture("a06_home_pairing_card") {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && app.viewModel.uiState.value.strap.pairingOffer == null) {
                Thread.sleep(50)
                rule.waitForIdle()
            }
        }
    }

    @Test fun a07_home_peak_offer() {
        val ana = demo()
        val at = app.nowMillis() - day
        data.addWorkout(
            ana.id,
            WorkoutStats(durationSeconds = 2400, distanceMeters = 6200, peakHeartRate = HeartRatePeak(196, HeartRateSourceKind.DirectStrap, at)),
            emptyList(), at - 2_400_000,
        )
        capture("a07_home_peak_offer")
    }

    // ---- Live data with the source ----

    @Test fun b01_live_idle_strap_source() {
        val ana = demo()
        pairStrap(ana.id)
        app.controller.showConnected()
        app.controller.state.value = TreadmillState(heartRateBpm = 91, updatedAtMillis = 1_000)
        app.strapMonitor.showConnected(bpm = 66)
        capture("b01_live_idle_strap")
    }

    @Test fun b02_live_running_strap_source() {
        val ana = demo()
        pairStrap(ana.id)
        app.controller.showConnected()
        app.controller.showRunning(heartRate = 150)
        app.strapMonitor.showConnected(bpm = 143)
        capture("b02_live_running_strap")
    }

    @Test fun b03_live_running_console_source() {
        demo()
        app.controller.showConnected()
        app.controller.showRunning(heartRate = 142)
        capture("b03_live_running_console")
    }

    @Test fun b04_live_running_without_heart_rate() {
        demo()
        app.controller.showConnected()
        app.controller.showRunning(heartRate = null)
        capture("b04_live_running_no_hr")
    }

    // ---- Devices ----

    @Test fun c01_devices_treadmill_searching_with_scan_list() {
        demo()
        app.controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        app.viewModel.startScan()
        capture("c01_devices_scan_treadmills", startAt = listOf(Screen.Devices))
    }

    @Test fun c02_devices_treadmill_not_found() {
        remembered()
        demo()
        app.controller.connectResult = notFound
        app.viewModel.reconnectToRemembered()
        capture("c02_devices_not_found", startAt = listOf(Screen.Devices))
    }

    @Test fun c03_devices_treadmill_connected_and_strap_connected() {
        remembered()
        val ana = demo()
        pairStrap(ana.id)
        app.controller.showConnected()
        app.controller.state.value = TreadmillState(heartRateBpm = 90, updatedAtMillis = 1_000)
        app.strapMonitor.showConnected(bpm = 67, battery = 64)
        capture("c03_devices_connected", startAt = listOf(Screen.Devices))
    }

    @Test fun c04_devices_strap_none_scan_list() {
        demo()
        app.strapMonitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
        capture("c04_devices_strap_scan", startAt = listOf(Screen.Devices)) {
            click("strap_scan_button")
        }
    }

    @Test fun c05_devices_strap_searching_console_holds_it() {
        remembered()
        val ana = demo()
        pairStrap(ana.id)
        app.strapMonitor.showSearching()
        app.controller.showConnected()
        app.controller.state.value = TreadmillState(heartRateBpm = 88, updatedAtMillis = 1_000)
        capture("c05_devices_strap_searching", startAt = listOf(Screen.Devices))
    }

    @Test fun c06_devices_strap_none_treadmill_searching() {
        remembered()
        demo()
        capture("c06_devices_strap_none", startAt = listOf(Screen.Devices))
    }

    @Test fun c08_devices_treadmill_searching_on_its_own() {
        remembered()
        demo()
        app.controller.connectResult = notFound
        app.viewModel.onPermissionsGranted()
        capture("c08_devices_auto_searching", startAt = listOf(Screen.Devices))
    }

    @Test fun c07_devices_bluetooth_off() {
        remembered()
        demo()
        app.controller.showBluetoothOff()
        capture("c07_devices_bluetooth_off", startAt = listOf(Screen.Devices))
    }

    // ---- Resting heart rate ----

    @Test fun d01_resting_instructions_with_strap() {
        val ana = demo()
        pairStrap(ana.id)
        app.strapMonitor.showConnected(bpm = 63)
        capture("d01_resting_instructions", startAt = listOf(Screen.RestingHeartRate))
    }

    @Test fun d02_resting_needs_a_strap() {
        demo()
        capture("d02_resting_no_strap", startAt = listOf(Screen.RestingHeartRate))
    }

    @Test fun d03_resting_history_chart_and_list() {
        val ana = demo()
        restingHistory(ana.id)
        capture("d03_resting_history", startAt = listOf(Screen.RestingHeartRate))
    }

    @Test fun d04_resting_manual_dialog() {
        demo()
        capture("d04_resting_manual", startAt = listOf(Screen.RestingHeartRate)) {
            // On the phone the button is at the end of the list, which composes only what is in view.
            runCatching { rule.onNodeWithTag("resting_list").performScrollToNode(hasTestTag("resting_add_manual")) }
            click("resting_add_manual")
        }
        captureDialog("d04_resting_manual_dialog")
    }

    private val ana get() = runBlocking { data.profiles.activeUser.first()!! }

    private fun restingState(
        phase: RestingPhase,
        result: RestingHeartRateResult? = null,
        saved: RestingHeartRateMeasurement? = null,
        elevated: com.treadz.console.userdata.RestingHeartRateAssessment? = null,
        progress: RestingHeartRateProgress = RestingHeartRateProgress(),
    ): RestingUiState {
        val user = ana
        val measurements = runBlocking { data.restingHeartRates.measurements(user.id).first() }
        val reference = runBlocking { data.restingHeartRates.reference(user.id).first() }
        return RestingUiState(
            loaded = true, user = user, phase = phase, live = LiveHeartRate(63, HeartRateSourceKind.DirectStrap), progress = progress,
            result = result, saved = saved, elevated = elevated, reference = reference, measurements = measurements,
        )
    }

    private fun result(valid: Boolean, bpm: Int?, reasons: List<RestingInvalidReason> = emptyList(), rmssd: Double? = null) = RestingHeartRateResult(
        startedAtMillis = 0, endedAtMillis = 180_000, valid = valid, bpm = bpm, lowestMeanBpm = bpm?.toDouble(), invalidReasons = reasons,
        coverage = if (valid) 0.99 else 0.4, longestGapSeconds = if (valid) 1 else 60, badSamples = 0, samples = 180, rmssdMs = rmssd,
    )

    @Test fun d05_resting_measuring() {
        val a = demo()
        restingHistory(a.id)
        host("d05_resting_measuring") {
            RestingHeartRateScreen(
                restingState(RestingPhase.Measuring, progress = RestingHeartRateProgress(running = true, elapsedSeconds = 74, remainingSeconds = 106, currentBpm = 62, lowestMeanBpm = 61.4)),
                RestingActions(),
            )
        }
    }

    @Test fun d06_resting_result_valid() {
        val a = demo()
        restingHistory(a.id)
        val saved = RestingHeartRateMeasurement(99, a.id, app.nowMillis(), 58, RestingHeartRateSource.Strap, 58.2, 0.99, 41.5)
        host("d06_resting_valid") { RestingHeartRateScreen(restingState(RestingPhase.Result, result(true, 58, rmssd = 41.5), saved), RestingActions()) }
    }

    @Test fun d07_resting_result_elevated_warning() {
        val a = demo()
        restingHistory(a.id)
        val saved = RestingHeartRateMeasurement(99, a.id, app.nowMillis(), 67, RestingHeartRateSource.Strap, 66.8, 0.98, 29.0)
        val assessment = com.treadz.console.userdata.RestingHeartRateAssessment(67, 58, com.treadz.console.userdata.ReferenceStatus.Established, 9, true)
        host("d07_resting_elevated") { RestingHeartRateScreen(restingState(RestingPhase.Result, result(true, 67, rmssd = 29.0), saved, assessment), RestingActions()) }
    }

    @Test fun d08_resting_result_invalid() {
        val a = demo()
        restingHistory(a.id)
        host("d08_resting_invalid") {
            RestingHeartRateScreen(
                restingState(RestingPhase.Result, result(false, null, listOf(RestingInvalidReason.MissingReadings, RestingInvalidReason.UnreliableReadings))),
                RestingActions(),
            )
        }
    }

    @Test fun d09_resting_reference_provisional_and_stale() {
        val a = demo()
        // One measurement 45 days ago: the reference is old.
        runBlocking { data.restingHeartRates.add(a.id, RestingHeartRateInput(app.nowMillis() - 45 * day, 60, RestingHeartRateSource.Strap)) }
        capture("d09_resting_stale", startAt = listOf(Screen.RestingHeartRate))
    }

    // ---- Maximum heart rate and the profile ----

    private fun withTestAndPeak(): UserProfile {
        val a = demo()
        val at = app.nowMillis() - 2 * day
        data.addWorkout(
            a.id,
            WorkoutStats(durationSeconds = 2400, distanceMeters = 6200, peakHeartRate = HeartRatePeak(197, HeartRateSourceKind.DirectStrap, at)),
            emptyList(), at - 2_400_000,
        )
        return a
    }

    @Test fun e01_max_heart_rate_detail_with_offer() {
        val a = withTestAndPeak()
        capture("e01_hrmax_detail", startAt = listOf(Screen.UserSelection, Screen.HeartRateMax(a.id)))
    }

    @Test fun e02_max_heart_rate_detail_manual() {
        val a = demo()
        capture("e02_hrmax_manual", startAt = listOf(Screen.UserSelection, Screen.HeartRateMax(runBlocking { data.profiles.profiles.first().first { it.name == "Beto" }.id })))
        check(a.id > 0)
    }

    @Test fun e03_profile_with_origin_labels() {
        val a = demo()
        restingHistory(a.id)
        capture("e03_profile_origins", startAt = listOf(Screen.UserSelection, Screen.ProfileEditor(a.id, 1)))
    }

    @Test fun e04_profile_with_offer_card() {
        val a = withTestAndPeak()
        capture("e04_profile_offer", startAt = listOf(Screen.UserSelection, Screen.ProfileEditor(a.id, 1))) {
            runCatching { rule.onNodeWithTag("peak_offer").performScrollTo() }
        }
    }

    // ---- Heart-rate guided block ----

    private val rig by lazy { TrainingRig() }

    /** The treadmill reports every second, like the real one (the runner treats a silent source as "no heart rate"). */
    private fun report(seconds: Int, heartRate: Int?) {
        repeat(seconds) {
            rig.app.controller.state.value = rig.app.controller.state.value.copy(heartRateBpm = heartRate, updatedAtMillis = rig.scheduler.currentTime)
            rig.advance(1_000)
        }
    }

    private fun startGuided(heartRate: Int, adjustAfterwards: Boolean = false) {
        val a = runBlocking { DemoData.populate(rig.app.userData, rig.app.date).first }
        rig.app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
        val outcome = rig.startProgram(ProgramResolver.resolve(TrainingFixtures.heartRateProgram()), a.id)
        check(outcome == com.treadz.console.training.StartOutcome.Started) { "$outcome" }
        rig.app.controller.state.value = TreadmillState(
            status = BeltStatus.Running, speedKmh = 6.0, targetSpeedKmh = 6.0, inclinePercent = 1.0, heartRateBpm = 100,
            distanceMeters = 300, elapsedSeconds = 180, updatedAtMillis = rig.scheduler.currentTime,
        )
        // The warm-up (2 minutes), then the heart rate climbs like a real one into the guided block.
        report(125, 100)
        var current = 100
        while (current != heartRate) {
            current += (heartRate - current).coerceIn(-4, 4)
            report(1, current)
        }
        report(12, heartRate)
        if (adjustAfterwards) {
            rig.adjustSpeed(7.0)
            rig.advance(400)
        }
    }

    @Test fun f01_guided_block_in_zone() {
        startGuided(heartRate = 118)
        capture("f01_guided_in_zone", theApp = rig.app)
    }

    @Test fun f02_guided_block_above_the_zone() {
        startGuided(heartRate = 133)
        capture("f02_guided_above", theApp = rig.app)
    }

    @Test fun f03_guided_block_off_after_a_manual_change() {
        startGuided(heartRate = 118, adjustAfterwards = true)
        capture("f03_guided_off", theApp = rig.app)
    }

    @Test fun f04_guided_block_no_heart_rate() {
        startGuided(heartRate = 118)
        report(6, null)
        capture("f04_guided_no_hr", theApp = rig.app)
    }

    @Test fun f05_guided_block_light_theme() {
        startGuided(heartRate = 118)
        rig.app.viewModel.setDarkTheme(false)
        capture("f05_guided_light", theApp = rig.app)
    }
}
