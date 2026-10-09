package com.treadz.console.training

import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.controls.TreadmillCommands
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.ProgramRunner
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.fitness.EbbelingParameters
import com.treadz.console.programs.fitness.FitnessActionResult
import com.treadz.console.programs.fitness.FitnessRefusal
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestRunner
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.MaxTestParameters
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.WorkoutLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How a start (of a program or of a fitness test) ended. */
sealed interface StartOutcome {
    /** The belt is running and the program / test clock has started. */
    data object Started : StartOutcome

    /** Something else is going on: another program or test, or (for a test) a belt that is not at rest. Nothing was sent. */
    data object Busy : StartOutcome

    data class ProgramRefused(val refusal: Refusal, val message: String) : StartOutcome

    data class TestRefused(val refusal: FitnessRefusal, val message: String) : StartOutcome

    /** The runner sent a command to the treadmill and it was not carried out. */
    data class CommandFailed(val result: CommandResult) : StartOutcome
}

/** Tells the workout history which plan / test the next treadmill session is for (see `WorkoutHistoryObserver`). */
interface SessionLinks {
    fun link(link: WorkoutLink)
    fun clear()
}

/**
 * The id of the next fitness test result, known **before** the test starts so that the workout can be linked to it
 * while the belt is still stopped. The test runner asks for its ids through [next].
 */
class FitnessTestIds(private val generate: () -> String = { java.util.UUID.randomUUID().toString() }) {
    private var reserved: String? = null

    /** Picks the id the next test will use. */
    @Synchronized
    fun reserve(): String = generate().also { reserved = it }

    /** The test did not start: forget the picked id. */
    @Synchronized
    fun release() {
        reserved = null
    }

    /** The runner's `idFactory`: the reserved id if there is one, else a new one. */
    @Synchronized
    fun next(): String = reserved?.also { reserved = null } ?: generate()
}

/**
 * Owns what ties the treadmill to the training screens: it decides where the manual controls' commands go, starts
 * programs and fitness tests, and keeps the fitness test result stored. It lives as long as the process (the runners
 * do), so a program or test goes on, and a test result is saved, whatever screen is open or even if the app is in the
 * background.
 *
 * **Routing.** While a program runs (from its start until it ends) every in-app command goes through [ProgramRunner]
 * (STOP, pause, resume, speed and incline adjustments); while a test runs, through [FitnessTestRunner] (STOP; the speed
 * of Ebbeling stage 1; nothing else is allowed). Outside of both, commands go straight to the controller. The notification's
 * STOP calls the controller directly: the runners treat that as the belt changing externally.
 */
class TrainingCoordinator(
    private val controller: TreadmillController,
    val programRunner: ProgramRunner,
    val fitnessRunner: FitnessTestRunner,
    private val testIds: FitnessTestIds,
    private val links: SessionLinks,
    private val userData: UserData,
    private val scope: CoroutineScope,
) {

    /** Owner of the test that is running or ran last: its result is stored for this user. */
    @Volatile
    private var testOwnerId: Long? = null

    private val programResultSeen = MutableStateFlow(false)
    private val testResultSeen = MutableStateFlow(false)
    private val _saveFailed = MutableStateFlow(false)

    /** Storing the fitness test result failed (the result is still shown). */
    val saveFailed: StateFlow<Boolean> = _saveFailed

    /** Where the manual controls send their commands. */
    val commands: TreadmillCommands = RoutedCommands()

    /** What the manual controls may do now. */
    val controls: StateFlow<SessionControls> = combine(programRunner.progress, fitnessRunner.progress) { p, t -> controlsOf(p, t) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, SessionControls())

    /**
     * Which session the main screen shows: the one in progress, or the one that has just finished and whose result the
     * user has not closed yet ([dismissResult]).
     */
    val visible: StateFlow<SessionKind> = combine(
        programRunner.progress.map { it.state },
        fitnessRunner.progress.map { it.phase },
        programResultSeen,
        testResultSeen,
    ) { programState, testPhase, programSeen, testSeen ->
        when {
            testPhase.isActive() -> SessionKind.FitnessTest
            programState.isActive() -> SessionKind.Program
            testPhase == FitnessTestPhase.Finished && !testSeen -> SessionKind.FitnessTest
            programState == RunState.Finished && !programSeen -> SessionKind.Program
            else -> SessionKind.None
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, SessionKind.None)

    init {
        // A new run shows its own screen again, even if the previous result was closed.
        scope.launch { programRunner.progress.map { it.state.isActive() }.distinctUntilChanged().collect { if (it) programResultSeen.value = false } }
        scope.launch { fitnessRunner.progress.map { it.phase.isActive() }.distinctUntilChanged().collect { if (it) testResultSeen.value = false } }
        // The test result is stored as soon as it exists (during the cool-down) and again if it changes until the end.
        scope.launch {
            fitnessRunner.progress.map { it.result }.filterNotNull().distinctUntilChanged().collect { result ->
                val owner = testOwnerId ?: return@collect
                _saveFailed.value = userData.fitnessTests.save(owner, result) !is DataResult.Success
            }
        }
    }

    /** Closes the result of the program / test that has finished, so the main screen shows the live data again. */
    fun dismissResult() {
        programResultSeen.value = true
        testResultSeen.value = true
    }

    // ------------------------------------------------------------------ starting

    /**
     * Starts [plan] for [userId]: links the workout to the plan, starts the program, and on success remembers
     * [parameters] as the last used ones. A refusal withdraws the link.
     */
    suspend fun startProgram(userId: Long, plan: ResolvedPlan, parameters: StartParameters): StartOutcome =
        scope.async { startProgramBody(userId, plan, parameters) }.await()

    private suspend fun startProgramBody(userId: Long, plan: ResolvedPlan, parameters: StartParameters): StartOutcome {
        if (programRunner.progress.value.state.isActive() || fitnessRunner.progress.value.phase.isActive()) return StartOutcome.Busy
        links.link(WorkoutLink(plan = plan))
        return when (val result = programRunner.start(plan)) {
            RunnerResult.Success -> {
                userData.startParameters.save(userId, plan.source.programId, parameters)
                StartOutcome.Started
            }
            is RunnerResult.NotAllowed -> {
                links.clear()
                StartOutcome.ProgramRefused(result.refusal, result.message)
            }
            is RunnerResult.CommandFailed -> {
                links.clear()
                StartOutcome.CommandFailed(result.result)
            }
        }
    }

    suspend fun startEbbeling(userId: Long, parameters: EbbelingParameters): StartOutcome =
        startTest(userId) { fitnessRunner.startEbbeling(parameters) }

    suspend fun startMaxTest(userId: Long, parameters: MaxTestParameters): StartOutcome =
        startTest(userId) { fitnessRunner.startMaxTest(parameters) }

    /**
     * A test needs the belt at rest: the workout is linked to the result id before the belt moves, and a session already
     * in progress would take the link.
     */
    private suspend fun startTest(userId: Long, start: suspend () -> FitnessActionResult): StartOutcome =
        scope.async { startTestBody(userId, start) }.await()

    private suspend fun startTestBody(userId: Long, start: suspend () -> FitnessActionResult): StartOutcome {
        val belt = controller.state.value
        val atRest = belt.status == BeltStatus.Idle && belt.speedKmh <= ManualControls.STOPPED_SPEED_KMH
        if (!atRest || programRunner.progress.value.state.isActive() || fitnessRunner.progress.value.phase.isActive()) return StartOutcome.Busy
        val id = testIds.reserve()
        testOwnerId = userId
        links.link(WorkoutLink(fitnessTestId = id))
        return when (val result = start()) {
            FitnessActionResult.Success -> StartOutcome.Started
            is FitnessActionResult.NotAllowed -> {
                links.clear()
                testIds.release()
                StartOutcome.TestRefused(result.refusal, result.message)
            }
            is FitnessActionResult.CommandFailed -> {
                links.clear()
                testIds.release()
                StartOutcome.CommandFailed(result.result)
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private fun programActive() = programRunner.progress.value.state.isActive()

    private fun testActive() = fitnessRunner.progress.value.phase.isActive()

    private inner class RoutedCommands : TreadmillCommands {
        override suspend fun setSpeed(kmh: Double): CommandResult = when {
            programActive() -> programRunner.adjustSpeed(kmh).toCommandResult()
            testActive() -> fitnessRunner.adjustSpeed(kmh).toCommandResult()
            else -> controller.setSpeed(kmh)
        }

        override suspend fun setIncline(percent: Double): CommandResult = when {
            programActive() -> programRunner.adjustIncline(percent).toCommandResult()
            // A test never changes the incline by hand.
            testActive() -> CommandResult.NotAllowed(FailureCode.BeltNotRunning, "The incline is locked during a fitness test")
            else -> controller.setIncline(percent)
        }

        override suspend fun pause(): CommandResult = when {
            programActive() -> programRunner.pause().toCommandResult()
            testActive() -> CommandResult.NotAllowed(FailureCode.BeltNotRunning, "A fitness test cannot be paused")
            else -> controller.pause()
        }

        override suspend fun resume(): CommandResult = when {
            programActive() -> programRunner.resume().toCommandResult()
            testActive() -> CommandResult.NotAllowed(FailureCode.NothingToResume, "A fitness test cannot be paused")
            else -> controller.resume()
        }

        /** STOP always reaches the treadmill: if the runner says nothing is active (it just ended), the controller stops the belt. */
        override suspend fun stop(): CommandResult {
            if (programActive()) {
                val result = programRunner.stop()
                if (!(result is RunnerResult.NotAllowed && result.refusal == Refusal.NotActive)) return result.toCommandResult()
            } else if (testActive()) {
                val result = fitnessRunner.stop()
                if (!(result is FitnessActionResult.NotAllowed && result.refusal == FitnessRefusal.NotActive)) return result.toCommandResult()
            }
            return controller.stop()
        }
    }

    private companion object {
        fun controlsOf(program: ProgramProgress, test: FitnessTestProgress): SessionControls = when {
            test.phase.isActive() -> {
                val speed = test.testType == FitnessTestType.EbbelingWalk && test.phase == FitnessTestPhase.Stage1 && !test.speedLocked
                SessionControls(
                    kind = SessionKind.FitnessTest, speed = speed, incline = false, pause = false,
                    note = if (speed) ControlsNote.TestSpeedOnly else ControlsNote.TestLocked,
                )
            }
            program.state == RunState.Interrupted ->
                SessionControls(SessionKind.Program, speed = false, incline = false, pause = false, note = ControlsNote.ProgramInterrupted)
            program.state.isActive() -> SessionControls(SessionKind.Program)
            else -> SessionControls()
        }
    }
}

/** The program is on: it has started and has not ended (an interrupted program is still on). */
fun RunState.isActive() = this != RunState.Idle && this != RunState.Finished

/** The test is on: it has started and has not ended. */
fun FitnessTestPhase.isActive() = this != FitnessTestPhase.Idle && this != FitnessTestPhase.Finished

private fun RunnerResult.toCommandResult(): CommandResult = when (this) {
    RunnerResult.Success -> CommandResult.Success
    is RunnerResult.CommandFailed -> result
    is RunnerResult.NotAllowed -> when (refusal) {
        Refusal.NotConnected -> CommandResult.NotConnected
        Refusal.SafetyKeyRemoved -> CommandResult.NotAllowed(FailureCode.SafetyKeyRemoved, message)
        Refusal.BeltBusy -> CommandResult.NotAllowed(FailureCode.BeltStillStopping, message)
        Refusal.InvalidValue -> CommandResult.NotAllowed(FailureCode.InvalidValue, message)
        Refusal.DataNotCurrent -> CommandResult.NotAllowed(FailureCode.DataLost, message)
        else -> CommandResult.NotAllowed(FailureCode.BeltNotRunning, message)
    }
}

private fun FitnessActionResult.toCommandResult(): CommandResult = when (this) {
    FitnessActionResult.Success -> CommandResult.Success
    is FitnessActionResult.CommandFailed -> result
    is FitnessActionResult.NotAllowed -> when (refusal) {
        FitnessRefusal.NotConnected -> CommandResult.NotConnected
        FitnessRefusal.SafetyKeyRemoved -> CommandResult.NotAllowed(FailureCode.SafetyKeyRemoved, message)
        FitnessRefusal.BeltBusy -> CommandResult.NotAllowed(FailureCode.BeltStillStopping, message)
        FitnessRefusal.DataNotCurrent -> CommandResult.NotAllowed(FailureCode.DataLost, message)
        else -> CommandResult.NotAllowed(FailureCode.BeltNotRunning, message)
    }
}
