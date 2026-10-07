package com.treadz.console.userdata.workout

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Records the treadmill's sessions into the workout history. It **only observes** [TreadmillController.state],
 * [TreadmillController.connection] and [TreadmillController.events]; it never sends commands.
 *
 * ## Session rules (the same as the debug recorder's)
 * - A session starts when the belt leaves [BeltStatus.Idle] (and the data is not stale).
 * - It ends when the belt is back to `Idle` **with speed 0** (after a stop the belt keeps decelerating for a few
 *   seconds in `Idle` with speed above 0; those samples still belong to the session), or when the connection is
 *   lost ([ConnectionState.Reconnecting] or [TreadmillEvent.ConnectionLost]) or closed
 *   ([ConnectionState.Disconnected]).
 * - After the session ends, stale states (the last known values replayed on a lost connection) never start a new one.
 * - A session in which the belt never moved (e.g. the countdown was interrupted) is discarded.
 *
 * ## Owner
 * The session belongs to the user who is active **when it starts**; later changes of the active user do not
 * affect it. With no active user the workout is saved unassigned (`userId == null`) so nothing is lost; the UI
 * can offer to assign it. If the owner (or the workout) is deleted during the session, recording stops silently
 * and nothing is stored for the rest of that session.
 *
 * ## Crash safety and performance
 * Samples are buffered in memory and written in one transaction at least every
 * [WorkoutObserverConfig.flushIntervalMs], together with the running totals, and again when the session ends. If the
 * process dies, what was flushed stays in the database; [start] closes such workouts as
 * [WorkoutEndReason.Interrupted]. The three observed flows only enqueue to an unbounded channel without suspending,
 * and all database work happens in a single coroutine of the scope given to [start] (use a background dispatcher),
 * so the treadmill controller is never blocked or slowed by storage.
 *
 * @param activeUserId resolves who is training at the time a session starts.
 * @param onError storage errors (the observer keeps buffering and retries on the next flush).
 */
class WorkoutHistoryObserver(
    private val controller: TreadmillController,
    private val store: WorkoutSessionStore,
    private val activeUserId: suspend () -> Long?,
    private val clock: Clock = Clock.System,
    private val config: WorkoutObserverConfig = WorkoutObserverConfig(),
    private val onError: (Throwable) -> Unit = {},
) {

    private val _activeWorkoutId = MutableStateFlow<Long?>(null)

    /** Id of the workout being recorded, `null` when there is no session. */
    val activeWorkoutId: StateFlow<Long?> = _activeWorkoutId.asStateFlow()

    private sealed interface Input {
        val at: Long

        class State(val state: TreadmillState, override val at: Long) : Input
        class Connection(val state: ConnectionState, override val at: Long) : Input
        class ConnectionLost(override val at: Long) : Input
        class Tick(override val at: Long) : Input
        class Stop(override val at: Long, val done: CompletableDeferred<Unit>) : Input
    }

    private val lock = Any()
    private var running: Job? = null
    private var inputs: Channel<Input>? = null

    // State of the processing coroutine. Only touched from it.
    private var session: Session? = null
    private var blockedUntilIdle = false
    private var ticker: Job? = null
    private var processScope: CoroutineScope? = null

    /**
     * Starts observing in [scope], which should use a background dispatcher. Closes workouts left open by a dead
     * process first. Calling it again while running does nothing. Returns the observer's job.
     */
    fun start(scope: CoroutineScope): Job = synchronized(lock) {
        running?.let { if (it.isActive) return it }
        val channel = Channel<Input>(Channel.UNLIMITED)
        inputs = channel
        val job = scope.launch {
            launch { controller.state.collect { channel.trySend(Input.State(it, clock.nowMillis())) } }
            launch { controller.connection.collect { channel.trySend(Input.Connection(it, clock.nowMillis())) } }
            launch {
                controller.events.collect { if (it is TreadmillEvent.ConnectionLost) channel.trySend(Input.ConnectionLost(clock.nowMillis())) }
            }
            processScope = this
            process(channel)
        }
        running = job
        job
    }

    /**
     * Ends the session in progress with [WorkoutEndReason.Closed] (flushing everything) and stops observing. Does
     * nothing if not started. If the scope is cancelled instead, the session stays open and is recovered later.
     */
    suspend fun stop() {
        val (job, channel) = synchronized(lock) { running to inputs }
        if (job == null || channel == null || !job.isActive) return
        val done = CompletableDeferred<Unit>()
        channel.trySend(Input.Stop(clock.nowMillis(), done))
        try {
            done.await()
        } finally {
            job.cancel()
        }
    }

    private suspend fun process(channel: Channel<Input>) {
        try {
            store.closeInterrupted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
        try {
            for (input in channel) {
                when (input) {
                    is Input.State -> onState(input.state, input.at)
                    is Input.Connection -> when (input.state) {
                        is ConnectionState.Reconnecting -> endSession(WorkoutEndReason.ConnectionLost, input.at)
                        is ConnectionState.Disconnected -> endSession(WorkoutEndReason.Disconnected, input.at)
                        else -> Unit
                    }
                    is Input.ConnectionLost -> endSession(WorkoutEndReason.ConnectionLost, input.at)
                    is Input.Tick -> flush()
                    is Input.Stop -> {
                        endSession(WorkoutEndReason.Closed, input.at)
                        input.done.complete(Unit)
                        return
                    }
                }
            }
        } finally {
            ticker?.cancel()
        }
    }

    private suspend fun onState(state: TreadmillState, at: Long) {
        if (state.status == BeltStatus.Idle) blockedUntilIdle = false
        val s = session
        // Real "Idle": no session on the treadmill and the belt already stopped.
        val idle = state.status == BeltStatus.Idle && state.speedKmh == 0.0
        if (s == null) {
            if (state.status != BeltStatus.Idle && !state.isStale && !blockedUntilIdle) {
                val started = startSession(at) ?: return
                started.add(state, at, config)
            }
            return
        }
        if (idle) {
            val reason = if (s.lastNonIdleStatus == BeltStatus.SafetyKeyRemoved) {
                WorkoutEndReason.SafetyKeyRemoved
            } else {
                WorkoutEndReason.Stopped
            }
            endSession(reason, at)
        } else {
            s.add(state, at, config)
        }
    }

    private suspend fun startSession(at: Long): Session? {
        val scope = processScope ?: return null
        val channel = inputs ?: return null
        val userId = try {
            activeUserId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            null // unassigned: recoverable
        }
        val id = try {
            store.begin(userId, at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            blockedUntilIdle = true
            return null
        }
        val s = Session(id, at)
        session = s
        _activeWorkoutId.value = id
        // Periodic flush trigger: it only enqueues ticks, so all the processing stays sequential.
        ticker = scope.launch {
            while (true) {
                delay(config.flushIntervalMs)
                channel.trySend(Input.Tick(clock.nowMillis()))
            }
        }
        return s
    }

    private suspend fun flush() {
        val s = session ?: return
        if (s.buffer.isEmpty() && !s.statsDirty) return
        val batch = s.buffer.toList()
        val exists = try {
            store.append(s.workoutId, batch, s.stats())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            while (s.buffer.size > config.maxBufferedSamples) s.buffer.removeFirst()
            return
        }
        if (!exists) {
            abandon()
            return
        }
        s.buffer.subList(0, batch.size).clear()
        s.statsDirty = false
    }

    private suspend fun endSession(reason: WorkoutEndReason, endedAt: Long) {
        val s = session ?: return
        ticker?.cancel()
        ticker = null
        session = null
        _activeWorkoutId.value = null
        try {
            if (!s.hasMoved) {
                store.discard(s.workoutId)
            } else {
                store.finish(s.workoutId, s.buffer.toList(), s.stats(), maxOf(endedAt, s.startedAt), reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What was flushed before stays in the database and is recovered as Interrupted at the next start.
            onError(e)
        }
    }

    /** The workout was deleted under us: stop recording this session and do not start another until Idle. */
    private fun abandon() {
        ticker?.cancel()
        ticker = null
        session = null
        _activeWorkoutId.value = null
        blockedUntilIdle = true
    }

    private class Session(val workoutId: Long, val startedAt: Long) {
        val buffer = ArrayDeque<WorkoutSample>()
        var statsDirty = false
        var lastNonIdleStatus: BeltStatus = BeltStatus.Starting
        private var seq = 0
        private var lastSampleAt = Long.MIN_VALUE
        private var lastStatus: BeltStatus? = null

        private var duration = 0
        private var distance = 0
        private var calories = 0
        private var maxSpeed = 0.0
        private var speedSum = 0.0
        private var speedCount = 0
        private var hrSum = 0L
        private var hrCount = 0
        private var maxHr = 0
        private var maxIncline = 0.0

        val hasMoved: Boolean get() = maxSpeed > 0.0

        fun add(state: TreadmillState, at: Long, config: WorkoutObserverConfig) {
            if (state.status != BeltStatus.Idle) lastNonIdleStatus = state.status
            val spaced = at - lastSampleAt >= config.minSampleSpacingMs
            if (!spaced && state.status == lastStatus) return
            lastSampleAt = at
            lastStatus = state.status
            buffer.addLast(
                WorkoutSample(
                    seq = seq++,
                    timestampMillis = at,
                    elapsedSeconds = state.elapsedSeconds,
                    speedKmh = state.speedKmh,
                    targetSpeedKmh = state.targetSpeedKmh,
                    inclinePercent = state.inclinePercent,
                    distanceMeters = state.distanceMeters,
                    calories = state.calories,
                    heartRateBpm = state.heartRateBpm,
                    beltStatus = state.status,
                    isStale = state.isStale,
                ),
            )
            duration = maxOf(duration, state.elapsedSeconds)
            distance = maxOf(distance, state.distanceMeters)
            calories = maxOf(calories, state.calories)
            if (!state.isStale) {
                maxSpeed = maxOf(maxSpeed, state.speedKmh)
                if (state.speedKmh > 0.0) {
                    speedSum += state.speedKmh
                    speedCount++
                }
                maxIncline = maxOf(maxIncline, state.inclinePercent)
                val hr = state.heartRateBpm
                if (hr != null && hr > 0) {
                    hrSum += hr
                    hrCount++
                    maxHr = maxOf(maxHr, hr)
                }
            }
            statsDirty = true
        }

        fun stats() = WorkoutStats(
            durationSeconds = duration,
            distanceMeters = distance,
            calories = calories,
            averageSpeedKmh = if (speedCount > 0) speedSum / speedCount else 0.0,
            maxSpeedKmh = maxSpeed,
            averageHeartRateBpm = if (hrCount > 0) (hrSum.toDouble() / hrCount).roundToInt() else null,
            maxHeartRateBpm = if (hrCount > 0) maxHr else null,
            maxInclinePercent = maxIncline,
        )
    }
}
