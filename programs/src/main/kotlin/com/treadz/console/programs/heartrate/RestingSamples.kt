package com.treadz.console.programs.heartrate

import com.treadz.console.hrm.SensorContact
import com.treadz.console.hrm.resting.RestingHeartRateSession
import com.treadz.console.hrm.resting.RestingSample
import com.treadz.console.programs.fitness.HeartRateSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The readings of this source as samples for a resting heart-rate measurement: a usable reading gives its bpm; an
 * invalid reading of the strap connected to the app (contact lost) counts as a contact-lost sample, any other invalid
 * reading as a missing one.
 */
fun HeartRateSource.restingSamples(): Flow<RestingSample> = readings.map { r ->
    val bpm = r.bpm?.takeIf { r.validity != HeartRateValidity.Invalid }
    val contact = when {
        r.source != HeartRateSourceKind.DirectStrap -> SensorContact.NotSupported
        bpm == null -> SensorContact.NotDetected
        r.validity == HeartRateValidity.Valid -> SensorContact.Detected
        else -> SensorContact.NotSupported
    }
    RestingSample(r.atMillis, bpm, contact)
}

/**
 * A "measure resting heart rate" session (3 min seated or lying, lowest 30-s mean of the last 2 min) fed by [source]
 * (normally the `AutomaticHeartRateSource`, so it works with the strap or the console). Call `start()` on it.
 */
fun restingHeartRateSession(source: HeartRateSource, scope: CoroutineScope, now: () -> Long = { System.currentTimeMillis() }): RestingHeartRateSession =
    RestingHeartRateSession(scope, source.restingSamples(), now)
