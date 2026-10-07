package com.treadz.console.fakes

import com.treadz.console.MainViewModel

/** A complete ViewModel built on the fakes, with direct access to them to set up scenarios. */
class FakeApp(
    val controller: FakeTreadmillController = FakeTreadmillController(),
    val recorder: FakeWorkoutRecorder = FakeWorkoutRecorder(),
    val settings: InMemorySettingsStore = InMemorySettingsStore(),
) {
    val viewModel: MainViewModel by lazy { MainViewModel(controller, recorder, settings) }
}
