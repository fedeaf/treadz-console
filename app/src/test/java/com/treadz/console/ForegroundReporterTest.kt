package com.treadz.console

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.treadz.console.fakes.FakeAppLifecycle
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.lifecycle.ForegroundReporter
import org.junit.Assert.assertEquals
import org.junit.Test

/** The process lifecycle (what `ProcessLifecycleOwner` reports) reaches the backend as visibility. */
class ForegroundReporterTest {

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private val appLifecycle = FakeAppLifecycle(FakeTreadmillController())

    @Test fun startAndStopAreReportedAsForegroundAndBackground() {
        val owner = Owner()
        ForegroundReporter(appLifecycle).attachTo(owner)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // First report: the process starts without a visible screen.
        assertEquals(listOf(false, true, false, true), appLifecycle.reports)
    }

    @Test fun attachingWhileAlreadyVisibleReportsForeground() {
        val owner = Owner()
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        ForegroundReporter(appLifecycle).attachTo(owner)
        assertEquals(true, appLifecycle.reports.last())
        assertEquals(true, appLifecycle.appInForeground.value)
    }
}
