package com.treadz.console

import android.content.Context
import android.content.Intent
import com.treadz.console.settings.SettingsStore
import com.treadz.console.settings.SharedPreferencesSettingsStore
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.android.TreadZTreadmill
import com.treadz.console.userdata.TreadZUserData
import com.treadz.console.userdata.UserData
import java.time.LocalDate

/**
 * Everything the UI needs from outside. The app uses [RealDependencies]; tests and screenshots
 * build the ViewModels directly with fake implementations, without touching production code.
 */
interface AppDependencies {
    val controller: TreadmillController
    val recorder: WorkoutRecorder
    val settings: SettingsStore
    val userData: UserData
    fun shareIntent(recording: RecordingInfo): Intent

    /** Today's date in the device time zone. Injectable so tests and screenshots are deterministic. */
    fun today(): LocalDate
}

class RealDependencies(private val context: Context) : AppDependencies {
    override val controller: TreadmillController by lazy { TreadZTreadmill.controller(context) }
    override val recorder: WorkoutRecorder by lazy { TreadZTreadmill.recorder(context) }
    override val settings: SettingsStore by lazy { SharedPreferencesSettingsStore(context) }
    override val userData: UserData by lazy { TreadZUserData.get(context) }
    override fun shareIntent(recording: RecordingInfo): Intent = TreadZTreadmill.shareIntent(context, recording)
    override fun today(): LocalDate = LocalDate.now()
}
