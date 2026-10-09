package com.treadz.console.fakes

import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.control.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import com.treadz.console.userdata.memory.InMemoryUserData

/**
 * A [FakeApp] whose program and fitness test runners run in virtual time: tests and screenshots move the clock with
 * [advance] and get exactly the progress a run would have at that moment. The fake treadmill is connected and running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingRig(userData: InMemoryUserData? = null) {
    val scheduler = TestCoroutineScheduler()
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    val app: FakeApp = if (userData == null) {
        FakeApp(trainingScope = scope, trainingClock = Clock { scheduler.currentTime })
    } else {
        FakeApp(userData = userData, trainingScope = scope, trainingClock = Clock { scheduler.currentTime })
    }

    init {
        app.controller.showConnected()
        app.controller.showRunning()
        // The coordinator's collectors start.
        app.training
        advance(1)
    }

    /** Moves the clock and lets everything that is due run (the runners tick every 250 ms). */
    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    fun standardPlan(id: String, parameters: StartParameters? = null): ResolvedPlan {
        val std = checkNotNull(StandardPrograms.byId(id))
        return ProgramResolver.resolve(std.program, parameters ?: std.defaultParameters)
    }

    /** Starts [plan] for the active user through the coordinator, like the start screen does. */
    fun startProgram(plan: ResolvedPlan, userId: Long): StartOutcome {
        var outcome: StartOutcome? = null
        scope.launch { outcome = app.training.startProgram(userId, plan, plan.parameters) }
        advance(10)
        return checkNotNull(outcome)
    }

    /** Runs a suspend call of the runners/coordinator to its end (the call is made inside the virtual-time scope). */
    fun <T> call(block: suspend () -> T): T {
        var result: Result<T>? = null
        scope.launch { result = runCatching { block() } }
        advance(10)
        return checkNotNull(result).getOrThrow()
    }

    fun adjustSpeed(kmh: Double): RunnerResult = call { app.programRunner.adjustSpeed(kmh) }
}

/** Collects [flow] in this scope (undispatched, so nothing is missed). */
fun <T> CoroutineScope.launchCollect(flow: kotlinx.coroutines.flow.Flow<T>, onEach: (T) -> Unit): kotlinx.coroutines.Job =
    launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { flow.collect { onEach(it) } }
