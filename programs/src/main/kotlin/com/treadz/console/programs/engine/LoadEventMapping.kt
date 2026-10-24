package com.treadz.console.programs.engine

import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.LoadEventType
import com.treadz.console.treadmill.events.LoadQuantity

/**
 * The load events a program event stands for (usually none), with the program clock ([ProgramEvent.programSeconds]) as
 * time. One event per quantity.
 *
 *  - [ProgramEvent.ManualChange]: a manual change for the speed and/or one for the incline (app or console, not told apart).
 *  - [ProgramEvent.HeartRateAdjusted]: by its reason, an increase or decrease of the regulation (also between repetitions),
 *    the α1 reduction, the safety reduction above the ceiling, the reduction at the maximum heart rate, or the fall back to
 *    the block's start value without heart rate.
 *  - [ProgramEvent.HeartRateMaxReached] **without** a reduction: the maximum was reached and nothing was lowered (already at
 *    the minimum, or the control is off). With a reduction the alert is reported as the adjustment above, one event not two.
 *  - [ProgramEvent.HeartRateCeilingExceeded] gives nothing: only a reduction counts, and that is an adjustment.
 *
 * The resumption of the control is not a program event; the runner adds it directly.
 */
internal fun ProgramEvent.toLoadEvents(): List<LoadEvent> = when (this) {
    is ProgramEvent.ManualChange -> listOfNotNull(
        speedKmh?.let { LoadEvent(programSeconds, LoadEventType.ManualChange, LoadQuantity.Speed, it) },
        inclinePercent?.let { LoadEvent(programSeconds, LoadEventType.ManualChange, LoadQuantity.Incline, it) },
    )
    is ProgramEvent.HeartRateAdjusted -> {
        val type = when (reason) {
            HeartRateAdjustReason.AboveZone -> LoadEventType.HeartRateDecrease
            HeartRateAdjustReason.BelowZone -> LoadEventType.HeartRateIncrease
            HeartRateAdjustReason.CeilingExceeded -> LoadEventType.CeilingReduction
            HeartRateAdjustReason.MaxHeartRateReached -> LoadEventType.MaxHeartRateReached
            HeartRateAdjustReason.NoHeartRateFallback -> LoadEventType.NoHeartRateFallback
            HeartRateAdjustReason.NextRepetition ->
                if (toValue >= fromValue) LoadEventType.HeartRateIncrease else LoadEventType.HeartRateDecrease
            HeartRateAdjustReason.Alpha1Brake -> LoadEventType.Alpha1Reduction
        }
        val quantity = if (adjust == HeartRateAdjust.Speed) LoadQuantity.Speed else LoadQuantity.Incline
        listOf(LoadEvent(programSeconds, type, quantity, toValue))
    }
    is ProgramEvent.HeartRateMaxReached ->
        if (reduced) emptyList() else listOf(LoadEvent(programSeconds, LoadEventType.MaxHeartRateReached))
    else -> emptyList()
}
