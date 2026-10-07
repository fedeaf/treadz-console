package com.treadz.console

import android.content.Context
import android.content.Intent
import com.treadz.console.settings.SettingsStore
import com.treadz.console.settings.SharedPreferencesSettingsStore
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.android.TreadZTreadmill

/**
 * Everything the UI needs from outside. The app uses [RealDependencies]; tests and screenshots
 * build the [MainViewModel] directly with fake implementations, without touching production code.
 */
interface AppDependencies {
    val controller: TreadmillController
    val recorder: WorkoutRecorder
    val settings: SettingsStore
    fun shareIntent(recording: RecordingInfo): Intent
}

class RealDependencies(private val context: Context) : AppDependencies {
    override val controller: TreadmillController by lazy { TreadZTreadmill.controller(context) }
    override val recorder: WorkoutRecorder by lazy { TreadZTreadmill.recorder(context) }
    override val settings: SettingsStore by lazy { SharedPreferencesSettingsStore(context) }
    override fun shareIntent(recording: RecordingInfo): Intent = TreadZTreadmill.shareIntent(context, recording)
}
