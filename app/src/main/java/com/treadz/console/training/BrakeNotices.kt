package com.treadz.console.training

import com.treadz.console.programs.engine.HeartRateAdjustReason
import com.treadz.console.programs.engine.ProgramEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/** A reduction of the load made by the real-time α1 brake, to tell the user. [id] is unique: two in a row are two notices. */
data class BrakeNotice(val id: Long)

/**
 * The short notice of the α1 brake: each time the program's real-time DFA-α1 brake lowers the load
 * ([HeartRateAdjustReason.Alpha1Brake]) the notice is on for [visibleMillis], the way the failures of the manual controls
 * are. A new reduction restarts the time. The notice only informs: it asks nothing and blocks nothing.
 */
class BrakeNotices(
    scope: CoroutineScope,
    events: Flow<ProgramEvent>,
    private val visibleMillis: Long = VISIBLE_MILLIS,
) {
    private val _notice = MutableStateFlow<BrakeNotice?>(null)
    val notice: StateFlow<BrakeNotice?> = _notice.asStateFlow()

    private var ids = 0L

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.filterIsInstance<ProgramEvent.HeartRateAdjusted>()
                .filter { it.reason == HeartRateAdjustReason.Alpha1Brake }
                .collectLatest {
                    _notice.value = BrakeNotice(++ids)
                    delay(visibleMillis)
                    _notice.value = null
                }
        }
    }

    companion object {
        /** The same time as the short notices of the manual controls. */
        const val VISIBLE_MILLIS = 6_000L
    }
}
