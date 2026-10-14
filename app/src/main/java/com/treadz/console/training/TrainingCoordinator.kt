package com.treadz.console.training

import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.controls.TreadmillCommands
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.ProgramRunner
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunOptions
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.fitness.EbbelingParameters
import com.treadz.console.programs.fitness.FitnessActionResult
import com.treadz.console.programs.fitness.FitnessRefusal
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestRunner
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.MaxHeartRateWalkParameters
import com.treadz.console.programs.fitness.MaxTestParameters
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.GeneratedSessionRecords
import com.treadz.console.programs.personalised.TimedHeartRate
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.WorkoutSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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

/** A workout that has just ended and waits for the user to keep it (the default) or discard it. */
data class FinishedWorkout(
    val workoutId: Long,
    val durationSeconds: Int,
    val distanceMeters: Int,
    /** The fitness test result run in it, discarded with it. */
    val fitnessTestId: String? = null,
)

/** Tells the workout history which plan / test the next treadmill session is for (see `WorkoutHistoryObserver`). */
interface SessionLinks {
    fun link(link: WorkoutLink)
    fun clear()

    /**
     * The workout being recorded now or, once the belt has stopped, the one that has just ended: where the record of a
     * generated session is attached. `null` when unknown.
     */
    fun currentWorkoutId(): Long? = null
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
    private val links: SessionLinks,
    private val userData: UserData,
    private val scope: CoroutineScope,
    /** Ids of fitness test results, known before the test starts so the workout can be linked to the result. */
    private val newTestId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Something went wrong in the background (storing the record of a generated session): logged, never shown. */
    private val onError: (Throwable) -> Unit = {},
) {

    /** Owner of the test that is running or ran last: its result is stored for this user. */
    @Volatile
    private var testOwnerId: Long? = null

    private val programResultSeen = MutableStateFlow(false)
    private val testResultSeen = MutableStateFlow(false)
    private val _saveFailed = MutableStateFlow(false)

    /** Storing the fitness test result failed (the result is still shown). */
    val saveFailed: StateFlow<Boolean> = _saveFailed

    private val _finishedWorkout = MutableStateFlow<FinishedWorkout?>(null)

    /**
     * The workout that has just ended, until the user keeps it ([keepFinishedWorkout], also "Done" on a result) or
     * discards it ([discardFinishedWorkout]). Every session's upload is held from its start until that decision, so a
     * discarded workout never leaves the device. A new session, or a restart, keeps it.
     */
    val finishedWorkout: StateFlow<FinishedWorkout?> = _finishedWorkout

    /** The workout being recorded now. */
    @Volatile
    private var inProgressId: Long? = null

    /** The workout in progress was kept with "Done" before it ended (the belt was still slowing down). */
    @Volatile
    private var keepWhenEnded: Long? = null

    /** Test results the user discarded: a late save must not bring them back. */
    private val discardedTests: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        scope.launch { followSessions() }
        // The test result is stored as soon as it exists (during the cool-down) and again if it changes until the end.
        scope.launch {
            fitnessRunner.progress.map { it.result }.filterNotNull().distinctUntilChanged().collect { result ->
                val owner = testOwnerId ?: return@collect
                if (result.id in discardedTests) return@collect
                _saveFailed.value = userData.fitnessTests.save(owner, result) !is DataResult.Success
            }
        }
    }

    /**
     * Closes the result of the program / test that has finished, so the main screen shows the live data again, and
     * keeps its workout. While the belt still runs (an abandoned program) the session goes on by hand and its workout
     * is offered for discarding when it ends.
     */
    fun dismissResult() {
        programResultSeen.value = true
        testResultSeen.value = true
        val finished = _finishedWorkout.value
        if (finished != null) {
            keep(finished)
        } else if (controller.state.value.status != BeltStatus.Running) {
            keepWhenEnded = inProgressId
        }
    }

    // ------------------------------------------------------------------ keep or discard the finished workout

    fun keepFinishedWorkout() {
        _finishedWorkout.value?.let { keep(it) }
    }

    /** Deletes the finished workout and its fitness test result; while its upload is held it leaves no trace. */
    fun discardFinishedWorkout() {
        val workout = _finishedWorkout.value ?: return
        if (!_finishedWorkout.compareAndSet(workout, null)) return
        workout.fitnessTestId?.let { discardedTests += it }
        programResultSeen.value = true
        testResultSeen.value = true
        scope.launch {
            userData.workouts.discardFinished(workout.workoutId)
            workout.fitnessTestId?.let { userData.fitnessTests.releaseUpload(it) }
        }
    }

    private fun keep(workout: FinishedWorkout) {
        _finishedWorkout.compareAndSet(workout, null)
        release(workout.workoutId, workout.fitnessTestId)
    }

    private fun release(workoutId: Long, testId: String?) {
        userData.workouts.releaseUpload(workoutId)
        testId?.let { userData.fitnessTests.releaseUpload(it) }
    }

    /** Follows the recorded sessions: holds the upload of each one and offers it when it ends. */
    private suspend fun followSessions() {
        var current: WorkoutSummary? = null
        userData.workouts.inProgressWorkout().collect { workout ->
            val previous = current
            current = workout
            if (workout != null) {
                if (workout.id != previous?.id) {
                    // A new session keeps the one still waiting for an answer.
                    _finishedWorkout.value?.let { keep(it) }
                    userData.workouts.holdUpload(workout.id)
                }
                workout.fitnessTestId?.let { userData.fitnessTests.holdUpload(it) }
            }
            // Until ended() has decided, "Done" still refers to the workout that is closing.
            if (previous != null && previous.id != workout?.id) ended(previous)
            inProgressId = workout?.id
        }
    }

    private suspend fun ended(workout: WorkoutSummary) {
        val summary = userData.workouts.detail(workout.id)?.summary
        val testId = summary?.fitnessTestId ?: workout.fitnessTestId
        val keptAlready = keepWhenEnded == workout.id
        if (keptAlready) keepWhenEnded = null
        // Dropped by the history (the belt never moved), or kept with "Done" before it ended.
        if (summary == null || summary.isInProgress || keptAlready) {
            release(workout.id, testId)
            return
        }
        _finishedWorkout.value = FinishedWorkout(summary.id, summary.stats.durationSeconds, summary.stats.distanceMeters, testId)
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
        val origin = plan.source.origin
        // Listening starts before the program does: the runner's events are not replayed.
        val recording = origin?.let(::recordGeneratedSession)
        return when (val result = programRunner.start(plan, RunOptions(heartRateProfile = heartRateProfile(userId)))) {
            RunnerResult.Success -> {
                // A generated program has no start parameters to remember: its values come from the generator.
                if (origin == null) userData.startParameters.save(userId, plan.source.programId, parameters)
                StartOutcome.Started
            }
            is RunnerResult.NotAllowed -> {
                recording?.cancel()
                links.clear()
                StartOutcome.ProgramRefused(result.refusal, result.message)
            }
            is RunnerResult.CommandFailed -> {
                recording?.cancel()
                links.clear()
                StartOutcome.CommandFailed(result.result)
            }
        }
    }

    /**
     * Collects the events of the run that starts now and, when it finishes, stores the record of the generated session
     * ([GeneratedSessionRecords]) with its workout. This is what makes the generators start where the heart rate settled
     * last time and what makes Couch to 5K advance. Errors are reported to [onError]; the run never notices.
     */
    private fun recordGeneratedSession(origin: ProgramOrigin): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val events = mutableListOf<ProgramEvent>()
        val finished = programRunner.events.first { event ->
            if (event is ProgramEvent.Started || events.isNotEmpty()) events += event
            event is ProgramEvent.Finished && events.isNotEmpty()
        } as ProgramEvent.Finished
        try {
            val workoutId = links.currentWorkoutId() ?: throw IllegalStateException("No workout to attach the session record to")
            val samples = userData.workouts.detail(workoutId)?.samples.orEmpty()
                .mapNotNull { s -> s.heartRateBpm?.let { TimedHeartRate(s.timestampMillis, it) } }
            val record = GeneratedSessionRecords.build(origin, finished, events, samples)
            when (val result = userData.workouts.attachSessionRecord(workoutId, record)) {
                is DataResult.Success -> Unit
                else -> throw IllegalStateException("The session record was not stored: $result")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    /**
     * The user's heart-rate references for heart-rate-guided blocks: the effective maximum (`HeartRateMaxPolicy`: manual,
     * else measured, else estimated) and the resting reference of the history. `null` when the maximum is unknown.
     */
    private suspend fun heartRateProfile(userId: Long): HeartRateProfile? {
        val max = userData.fitnessTests.heartRateMax(userId).first()?.bpm ?: return null
        val resting = userData.restingHeartRates.reference(userId).first()?.bpm
        return HeartRateProfile(max, resting)
    }

    /** "Resume heart-rate control" after a manual change switched it off: the runner decides whether it applies. */
    fun resumeHeartRateControl(): RunnerResult = programRunner.resumeHeartRateControl()

    suspend fun startEbbeling(userId: Long, parameters: EbbelingParameters): StartOutcome =
        startTest(userId) { id -> fitnessRunner.startEbbeling(parameters.copy(resultId = id)) }

    suspend fun startMaxTest(userId: Long, parameters: MaxTestParameters): StartOutcome =
        startTest(userId) { id -> fitnessRunner.startMaxTest(parameters.copy(resultId = id)) }

    /** Starts the maximal heart-rate walk test (modified Balke), like the other two tests. */
    suspend fun startMaxHeartRateWalk(userId: Long, parameters: MaxHeartRateWalkParameters): StartOutcome =
        startTest(userId) { id -> fitnessRunner.startMaxHeartRateWalk(parameters.copy(resultId = id)) }

    /**
     * During the warm-up of the walking heart-rate test: changes the speed and/or the incline the ramp starts at (`null`
     * keeps a value). The runner clamps them and refuses it in any other phase or test.
     */
    fun adjustRampStart(speedKmh: Double?, inclinePercent: Double?): FitnessActionResult =
        fitnessRunner.adjustRampStart(speedKmh, inclinePercent)

    /**
     * A test needs the belt at rest: the workout is linked to the result id before the belt moves, and a session already
     * in progress would take the link.
     */
    private suspend fun startTest(userId: Long, start: suspend (String) -> FitnessActionResult): StartOutcome =
        scope.async { startTestBody(userId, start) }.await()

    private suspend fun startTestBody(userId: Long, start: suspend (String) -> FitnessActionResult): StartOutcome {
        val belt = controller.state.value
        val atRest = belt.status == BeltStatus.Idle && belt.speedKmh <= ManualControls.STOPPED_SPEED_KMH
        if (!atRest || programRunner.progress.value.state.isActive() || fitnessRunner.progress.value.phase.isActive()) return StartOutcome.Busy
        val id = newTestId()
        testOwnerId = userId
        // Its upload waits, with the workout's, for the user to keep or discard it (see finishedWorkout).
        userData.fitnessTests.holdUpload(id)
        links.link(WorkoutLink(fitnessTestId = id))
        return when (val result = start(id)) {
            FitnessActionResult.Success -> StartOutcome.Started
            is FitnessActionResult.NotAllowed -> {
                links.clear()
                userData.fitnessTests.releaseUpload(id)
                StartOutcome.TestRefused(result.refusal, result.message)
            }
            is FitnessActionResult.CommandFailed -> {
                links.clear()
                userData.fitnessTests.releaseUpload(id)
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
