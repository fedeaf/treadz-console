package com.treadz.console.fakes

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A heart-rate source the test feeds by hand (what the resting measurement and the runners listen to). */
class FakeHeartRateSource : HeartRateSource {
    private val flow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 512)
    private var current = HeartRateReading.NONE

    override fun latest(): HeartRateReading = current
    override val readings: Flow<HeartRateReading> = flow

    /** A reading at [atMillis] from [source]; `bpm == null` is "no heart rate". */
    fun emit(bpm: Int?, atMillis: Long, source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap, rr: List<Int> = emptyList()) {
        current = if (bpm == null) {
            HeartRateReading(null, HeartRateValidity.Invalid, atMillis, source)
        } else {
            HeartRateReading(bpm, HeartRateValidity.Valid, atMillis, source, rr)
        }
        flow.tryEmit(current)
    }
}
