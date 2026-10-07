package com.treadz.console

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * `true` while the belt is not stopped (starting, running, paused or key removed). The active user cannot change, and
 * cannot be deleted, during that time: the workout belongs to the person who started it.
 */
val TreadmillController.beltBusy: Flow<Boolean>
    get() = state.map { it.status != BeltStatus.Idle }.distinctUntilChanged()
