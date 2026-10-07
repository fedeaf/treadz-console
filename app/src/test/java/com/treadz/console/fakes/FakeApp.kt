package com.treadz.console.fakes

import android.content.Intent
import com.treadz.console.AppDependencies
import com.treadz.console.MainViewModel
import com.treadz.console.nav.NavigationViewModel
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.memory.InMemoryUserData
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the UI needs, built on fakes, with direct access to them to set up scenarios. The data layer starts
 * like a fresh install (one generic profile, active); the date is fixed so screenshots and tests are repeatable.
 */
class FakeApp(
    override val controller: FakeTreadmillController = FakeTreadmillController(),
    override val recorder: FakeWorkoutRecorder = FakeWorkoutRecorder(),
    override val settings: InMemorySettingsStore = InMemorySettingsStore(),
    val date: LocalDate = TODAY,
    zone: ZoneId = ZoneId.systemDefault(),
    override val userData: InMemoryUserData = InMemoryUserData(
        clock = Clock { date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() },
        zone = zone,
    ),
) : AppDependencies {

    override fun shareIntent(recording: RecordingInfo): Intent = Intent(Intent.ACTION_SEND)

    override fun today(): LocalDate = date

    val viewModel: MainViewModel by lazy { MainViewModel(controller, recorder, settings, userData) }

    /** A new navigation state, as when the app opens (create it after setting up the profiles). */
    fun newNavigation(): NavigationViewModel = NavigationViewModel(userData.profiles)

    val navigation: NavigationViewModel by lazy { newNavigation() }

    companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 7)
    }
}
