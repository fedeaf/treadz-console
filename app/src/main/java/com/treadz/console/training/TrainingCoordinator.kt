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
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.CommandOrigin
import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.ManualWorkoutEvents
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
 * of Ebbeling stage 1; nothing else is allowed). Outside of both, speed and incline go through the [ManualWorkoutEvents] of
 * the manual workout (it runs the controller's own verified command and records the change), the rest straight to the
 * controller. The notification's STOP takes the same route ([stopFromNotification]), so it ends a program or test as the
 * app's STOP does.
 *
 * **Events.** Every workout stores the list of what changed its load ([com.treadz.console.userdata.WorkoutEvent]): the list
 * of the program or test that runs in it, or, for a workout with neither, the one of the [ManualWorkoutEvents] attached to
 * it (the changes of the app's buttons and of the console's). The list is stored while the workout lasts (when it changes,
 * at most every [EVENTS_STORE_MILLIS]) and once more, final, when it ends; a discarded workout stores nothing. Nothing in
 * the app shows them yet. A workout that mixes manual changes and a program (a program started on a moving belt, or the
 * belt kept moving after it was abandoned) stores one list on the clock of the workout (see [EventsTarget]).
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
    /** Today's date, for the user's zones (age, recent tests). */
    private val today: () -> LocalDate = LocalDate::now,
    /** The clock of the manual workouts' events (the seconds since the start of the workout). */
    private val clock: Clock = Clock.System,
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

    /** The fitness test result of the workout in progress, if any. */
    @Volatile
    private var inProgressTestId: String? = null

    /** The workout in progress was discarded before it ended (the belt was still slowing down after a STOP). */
    @Volatile
    private var discardWhenEnded: Long? = null

    /** Test results the user discarded: a late save must not bring them back. */
    private val discardedTests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The events of a workout without a program or a test: the changes of the app's buttons and of the console's. */
    private val manualEvents = ManualWorkoutEvents(controller, scope, clock)

    /** The short notice of the α1 brake of the program in progress (see [BrakeNotices]). */
    val brakeNotice: StateFlow<BrakeNotice?> = BrakeNotices(scope, programRunner.events).notice

    /** Where the manual controls send their commands. */
    val commands: TreadmillCommands = RoutedCommands()

    private val _programSeries = MutableStateFlow(ProgramSeries())

    /**
     * What the treadmill really did during the current program (or the last one, until another starts): the live
     * chart and the chart at the end draw it over the plan. It lives here, not in a screen, so leaving and reopening
     * the program screen keeps it.
     */
    val programSeries: StateFlow<ProgramSeries> = _programSeries

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
        scope.launch { followProgramSeries() }
        // An abandoned program leaves the belt moving: the workout goes on by hand, and so do its events.
        scope.launch {
            programRunner.events.filterIsInstance<ProgramEvent.Finished>().collect {
                val status = controller.state.value.status
                if (status == BeltStatus.Running || status == BeltStatus.Paused) switchEvents(EventsFrom.Manual, null)
            }
        }
        // "+1" changes the plan: the workout keeps the plan as it was run, so the history compares like with like.
        scope.launch { programRunner.events.filterIsInstance<ProgramEvent.BlockExtended>().collect { links.link(WorkoutLink(plan = it.plan)) } }
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

    /**
     * Fills [programSeries]: emptied when a program starts, a sample per program second while the program clock runs
     * (never while starting, paused or interrupted), and none while the treadmill data are not current.
     */
    private suspend fun followProgramSeries() {
        var wasActive = false
        programRunner.progress.collect { p ->
            val active = p.state.isActive()
            if (active && !wasActive) _programSeries.value = ProgramSeries()
            wasActive = active
            if (p.state == RunState.Running && !controller.state.value.isStale) {
                val sample = ActualSample(p.totalElapsedSeconds, p.actualSpeedKmh, p.actualInclinePercent)
                _programSeries.update { it.appended(sample) }
            }
        }
    }

    // ------------------------------------------------------------------ keep or discard the finished workout

    fun keepFinishedWorkout() {
        _finishedWorkout.value?.let { keep(it) }
    }

    /**
     * Deletes the finished workout and its fitness test result; while its upload is held it leaves no trace. While the
     * belt is still slowing down after a STOP (the workout has not ended yet) the result is closed now and the workout
     * is deleted as soon as it ends.
     */
    fun discardFinishedWorkout() {
        val workout = _finishedWorkout.value
        if (workout == null) {
            discardWhenEnded(inProgressId ?: return)
            return
        }
        if (!_finishedWorkout.compareAndSet(workout, null)) return
        workout.fitnessTestId?.let { discardedTests += it }
        programResultSeen.value = true
        testResultSeen.value = true
        scope.launch {
            userData.workouts.discardFinished(workout.workoutId)
            workout.fitnessTestId?.let { userData.fitnessTests.releaseUpload(it) }
        }
    }

    private fun discardWhenEnded(workoutId: Long) {
        inProgressTestId?.let { discardedTests += it }
        discardWhenEnded = workoutId
        if (keepWhenEnded == workoutId) keepWhenEnded = null
        programResultSeen.value = true
        testResultSeen.value = true
        // ended() may have offered it in the meantime (it sets the offer, then looks at the flag): discard it now.
        if (_finishedWorkout.value?.workoutId == workoutId) discardOffered(workoutId)
    }

    private fun discardOffered(workoutId: Long) {
        if (discardWhenEnded == workoutId) discardWhenEnded = null
        if (_finishedWorkout.value?.workoutId == workoutId) discardFinishedWorkout()
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
                    attachEvents(workout)
                }
                workout.fitnessTestId?.let { userData.fitnessTests.holdUpload(it) }
            }
            inProgressTestId = workout?.fitnessTestId
            // Until ended() has decided, "Done" still refers to the workout that is closing.
            if (previous != null && previous.id != workout?.id) ended(previous)
            inProgressId = workout?.id
        }
    }

    private suspend fun ended(workout: WorkoutSummary) {
        val summary = userData.workouts.detail(workout.id)?.summary
        val testId = summary?.fitnessTestId ?: workout.fitnessTestId
        if (discardWhenEnded == workout.id) {
            discardWhenEnded = null
            dropEvents(workout.id)
            testId?.let { discardedTests += it }
            if (summary == null || summary.isInProgress) {
                release(workout.id, testId)
            } else {
                userData.workouts.discardFinished(workout.id)
                testId?.let { userData.fitnessTests.releaseUpload(it) }
            }
            return
        }
        // Dropped by the history (the belt never moved): there is nothing to attach the events to.
        if (summary == null) dropEvents(workout.id) else finishEvents(workout.id)
        val keptAlready = keepWhenEnded == workout.id
        if (keptAlready) keepWhenEnded = null
        // Dropped by the history (the belt never moved), or kept with "Done" before it ended.
        if (summary == null || summary.isInProgress || keptAlready) {
            release(workout.id, testId)
            return
        }
        _finishedWorkout.value = FinishedWorkout(summary.id, summary.stats.durationSeconds, summary.stats.distanceMeters, testId)
        // Discarded while this was running (see discardWhenEnded).
        if (discardWhenEnded == workout.id) discardOffered(workout.id)
    }

    // ------------------------------------------------------------------ the events of the workout

    private enum class EventsFrom { Manual, Program, Test }

    /**
     * A closed part of the events of a workout: what a program, a test or the manual controls recorded, on its own clock;
     * [offsetSeconds] takes it to the clock of the workout (0 for the manual part, which already counts from the start of
     * the workout).
     */
    private class EventsPart(val offsetSeconds: Int, val events: List<LoadEvent>)

    /**
     * Where the events of one workout come from and what has been stored of them. A workout is usually one part (a
     * program, a test, or the manual controls) and its list keeps the clock of that part. A workout can also mix parts: a
     * program started on a belt that was already moving by hand, or the belt kept moving by hand after a program was
     * abandoned. When more than one part has events, the list is one list on the **clock of the workout** (the program's
     * events shifted by the time the program started after the workout; approximate, the program clock may stop while the
     * workout's does not).
     *
     * [latest] is the longest list seen of the current part: the runners empty their list when the next program or test
     * starts, which may happen before this workout has closed.
     */
    private inner class EventsTarget(val workoutId: Long, private val workoutStartedAtMillis: Long) {
        var from: EventsFrom = EventsFrom.Manual
            private set
        private var offsetSeconds = 0
        private var source: StateFlow<List<LoadEvent>> = manualEvents.events
        private val closed = ArrayList<EventsPart>()

        @Volatile
        private var latest: List<LoadEvent> = emptyList()

        /** The list last written (what [store] skips). */
        var stored: List<LoadEvent> = emptyList()

        private var job: Job? = null
        private var flush: Job? = null

        /** Starts following [from]; a program's part is shifted by the time it started after the workout. */
        fun follow(from: EventsFrom, startedAtMillis: Long?) {
            this.from = from
            offsetSeconds = startedAtMillis?.let { ((it - workoutStartedAtMillis) / 1000L).toInt().coerceAtLeast(0) } ?: 0
            latest = emptyList()
            source = when (from) {
                EventsFrom.Program -> programRunner.loadEvents
                EventsFrom.Test -> fitnessRunner.loadEvents
                EventsFrom.Manual -> {
                    manualEvents.start(workoutStartedAtMillis)
                    manualEvents.events
                }
            }
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect { list ->
                    remember(list)
                    if (flush?.isActive != true) {
                        flush = launch {
                            delay(EVENTS_STORE_MILLIS)
                            store(this@EventsTarget, composed())
                        }
                    }
                }
            }
        }

        /** The current part ends and [from] follows (the same workout goes on). */
        fun switchTo(from: EventsFrom, startedAtMillis: Long?) {
            closed += EventsPart(offsetSeconds, closeCurrent())
            follow(from, startedAtMillis)
        }

        private fun remember(list: List<LoadEvent>) {
            if (list.size >= latest.size) latest = list
        }

        /** Stops following the current part and returns its final list. */
        fun closeCurrent(): List<LoadEvent> {
            job?.cancel()
            if (from == EventsFrom.Manual) remember(manualEvents.stop()) else remember(source.value)
            return latest
        }

        /** The list of the workout: one part on its own clock, or several parts on the clock of the workout. */
        fun composed(current: List<LoadEvent> = latest): List<LoadEvent> {
            val parts = (closed + EventsPart(offsetSeconds, current)).filter { it.events.isNotEmpty() }
            if (parts.size <= 1) return parts.firstOrNull()?.events.orEmpty()
            return parts.flatMap { part -> part.events.map { it.copy(seconds = it.seconds + part.offsetSeconds) } }
        }
    }

    private val eventTargets = ConcurrentHashMap<Long, EventsTarget>()

    /** Stores happen one at a time, so a late write of an old list never lands after the final one. */
    private val eventsLock = Mutex()

    /** When the program in progress started (for the clock of a workout that mixes it with manual changes). */
    @Volatile
    private var programStartedAtMillis: Long? = null

    /** A workout has started: its events come from the program or the test that is running, else from a manual workout. */
    private fun attachEvents(workout: WorkoutSummary) {
        val from = when {
            testActive() -> EventsFrom.Test
            programActive() -> EventsFrom.Program
            else -> EventsFrom.Manual
        }
        eventTargets.remove(workout.id)?.closeCurrent()
        val target = EventsTarget(workout.id, workout.startedAtMillis)
        target.follow(from, programStartedAtMillis.takeIf { from == EventsFrom.Program })
        eventTargets[workout.id] = target
    }

    /** The workout in progress goes on with another part: a program started on the moving belt, or the manual controls after it. */
    private fun switchEvents(from: EventsFrom, startedAtMillis: Long?) {
        val target = inProgressId?.let { eventTargets[it] } ?: return
        if (target.from == from) return
        target.switchTo(from, startedAtMillis)
    }

    /** The workout ends and is kept for now: its final list is stored. */
    private suspend fun finishEvents(workoutId: Long) {
        val target = eventTargets.remove(workoutId) ?: return
        val events = target.composed(target.closeCurrent())
        store(target, events, final = true)
    }

    /** The workout is discarded, or never existed: its events are forgotten. */
    private fun dropEvents(workoutId: Long) {
        eventTargets.remove(workoutId)?.closeCurrent()
    }

    /**
     * Writes [events]. A partial write ([final] false) is dropped when the workout has meanwhile ended or been discarded
     * (both take the target out of [eventTargets]), so it never follows the final one.
     */
    private suspend fun store(target: EventsTarget, events: List<LoadEvent>, final: Boolean = false) {
        if (events.isEmpty()) return
        eventsLock.withLock {
            if (target.stored == events || (!final && eventTargets[target.workoutId] !== target)) return
            try {
                when (val result = userData.workouts.setEvents(target.workoutId, events.toWorkoutEvents())) {
                    is DataResult.Success -> target.stored = events
                    DataResult.NotFound -> Unit
                    else -> onError(IllegalStateException("The events of the workout were not stored: $result"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
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
        // A program started on a belt that is already moving takes over the events of the workout in progress.
        val beltMoving = controller.state.value.status.let { it == BeltStatus.Running || it == BeltStatus.Paused }
        links.link(WorkoutLink(plan = plan))
        val origin = plan.source.origin
        // Listening starts before the program does: the runner's events are not replayed.
        val recording = origin?.let(::recordGeneratedSession)
        return when (val result = programRunner.start(plan, RunOptions(heartRateProfile = heartRateProfile(userId)))) {
            RunnerResult.Success -> {
                programStartedAtMillis = clock.nowMillis()
                // On a belt already moving by hand the workout goes on: the program is its next part.
                if (beltMoving) switchEvents(EventsFrom.Program, programStartedAtMillis)
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
     * else measured, else estimated), the resting reference of the history and the VT1 of the user's zones (measured,
     * else the typical one of their level: the top of zone 2, the same the generators use), which tells the real-time
     * α1 brake which blocks are easy enough, and the zones themselves, for targets that name a zone of the profile.
     * `null` when the maximum is unknown.
     */
    private suspend fun heartRateProfile(userId: Long): HeartRateProfile? {
        val maxDecision = userData.fitnessTests.heartRateMax(userId).first() ?: return null
        val resting = userData.restingHeartRates.reference(userId).first()?.bpm
        val user = userData.profiles.profile(userId).first() ?: return HeartRateProfile(maxDecision.bpm, resting)
        return UserZones.heartRateProfile(user, userData.fitnessTests.liveFitnessProfile(userId).first(), maxDecision, resting, today())
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

    private fun testManualAllowed() = fitnessRunner.progress.value.let { it.phase.isActive() && it.manualChangesAllowed }

    private inner class RoutedCommands : TreadmillCommands {
        override suspend fun setSpeed(kmh: Double): CommandResult = withContext(CommandOrigin.App) {
            when {
                programActive() -> programRunner.adjustSpeed(kmh).toCommandResult()
                testManualAllowed() -> fitnessRunner.adjustByHand(kmh, null).toCommandResult()
                testActive() -> fitnessRunner.adjustSpeed(kmh).toCommandResult()
                else -> manualEvents.setSpeed(kmh)
            }
        }

        override suspend fun setIncline(percent: Double): CommandResult = withContext(CommandOrigin.App) {
            when {
                programActive() -> programRunner.adjustIncline(percent).toCommandResult()
                testManualAllowed() -> fitnessRunner.adjustByHand(null, percent).toCommandResult()
                // Otherwise a test never changes the incline by hand.
                testActive() -> CommandResult.NotAllowed(FailureCode.BeltNotRunning, "The incline is locked during a fitness test")
                else -> manualEvents.setIncline(percent)
            }
        }

        override suspend fun pause(): CommandResult = withContext(CommandOrigin.App) {
            when {
                programActive() -> programRunner.pause().toCommandResult()
                testActive() -> CommandResult.NotAllowed(FailureCode.BeltNotRunning, "A fitness test cannot be paused")
                else -> controller.pause()
            }
        }

        override suspend fun resume(): CommandResult = withContext(CommandOrigin.App) {
            when {
                programActive() -> programRunner.resume().toCommandResult()
                testActive() -> CommandResult.NotAllowed(FailureCode.NothingToResume, "A fitness test cannot be paused")
                else -> controller.resume()
            }
        }

        override suspend fun stop(): CommandResult = routedStop(CommandOrigin.App)
    }

    /**
     * The notification's STOP: through the program or test in progress, like the app's STOP, so a program ends as
     * stopped by the user (not interrupted) and a test as the STOP rules of the test say; otherwise straight to the
     * controller. Recorded with [CommandOrigin.Notification].
     */
    suspend fun stopFromNotification(): CommandResult = routedStop(CommandOrigin.Notification)

    /** STOP always reaches the treadmill: if the runner says nothing is active (it just ended), the controller stops the belt. */
    private suspend fun routedStop(origin: CommandOrigin): CommandResult {
        if (programActive()) {
            val result = programRunner.stop(origin)
            if (!(result is RunnerResult.NotAllowed && result.refusal == Refusal.NotActive)) return result.toCommandResult()
        } else if (testActive()) {
            val result = fitnessRunner.stop(origin)
            if (!(result is FitnessActionResult.NotAllowed && result.refusal == FitnessRefusal.NotActive)) return result.toCommandResult()
        }
        return withContext(origin) { controller.stop() }
    }

    private companion object {
        fun controlsOf(program: ProgramProgress, test: FitnessTestProgress): SessionControls = when {
            test.phase.isActive() && test.manualChangesAllowed -> SessionControls(SessionKind.FitnessTest, pause = false)
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
