package com.treadz.console.userdata.workout

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutLink
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
 * - It ends when the belt is back to `Idle` **with speed 0** (after a stop the belt keeps decelerating, at about
 *   0.5 km/h per second, in `Idle` with speed above 0; those samples still belong to the session), or when the
 *   connection is lost ([ConnectionState.Reconnecting] or [TreadmillEvent.ConnectionLost]) or closed
 *   ([ConnectionState.Disconnected]).
 * - The packet that reports speed 0 is recorded as the **last sample** of the session, so a workout that ended
 *   normally always finishes with the belt really stopped. The treadmill resets its distance, time and calories to 0
 *   in that very packet; the last sample keeps the values the session had reached instead. If the speed in `Idle`
 *   stops changing for [WorkoutObserverConfig.idleStallMs] (or the deceleration lasts longer than
 *   [WorkoutObserverConfig.idleMaxMs]) without ever reaching 0, the session ends anyway with the last sample as it is.
 * - After the session ends, stale states (the last known values replayed on a lost connection) never start a new one.
 * - A session in which the belt never moved (e.g. the countdown was interrupted) is discarded.
 *
 * ## Owner
 * The session belongs to the user who is active **when it starts**; later changes of the active user do not
 * affect it. With no active user the workout is saved unassigned (`userId == null`) so nothing is lost; the UI
 * can offer to assign it. If the owner (or the workout) is deleted during the session, recording stops silently
 * and nothing is stored for the rest of that session.
 *
 * ## What the workout was for (plan and fitness test)
 * The observer does not know the program runner or the fitness test runner. The app tells it with [linkNextSession]:
 * the [WorkoutLink] (the executed plan snapshot and/or the fitness test id) is attached to the session that is in
 * progress or, if there is none yet, to the **next one that starts** within [WorkoutObserverConfig.linkValidityMs];
 * [clearLink] withdraws it (the program was cancelled before the belt moved). Calls are cheap, never block and can
 * be made from any thread. A workout can also be linked later by id, see `WorkoutHistoryRepository.link`
 * ([activeWorkoutId] tells which workout is being recorded). Call [linkNextSession] when the belt is stopped: a
 * session that is still decelerating from a previous workout would take the link.
 *
 * ## Heart rate, its source and RR intervals
 * Without a [HeartRateSource] the heart rate of a sample is the console's (`TreadmillState.heartRateBpm`) and its source
 * [HeartRateSourceKind.TreadmillConsole]. With one (the app passes its `AutomaticHeartRateSource`), the heart rate and the
 * source of every sample are those of the source's latest usable reading (a missing or invalid reading gives a sample
 * without heart rate), and the sample carries the RR intervals of the source's readings **received since the previous
 * sample**: they are collected from [HeartRateSource.readings] while a session runs and handed to the next sample
 * written, so concatenating the intervals of all samples gives each interval once, in order (those received before the
 * session started, or after its last sample, are not part of it). The same holds for the samples of fitness test
 * workouts, which are ordinary workouts linked to a test.
 *
 * The workout's heart-rate peak ([WorkoutStats.peakHeartRate]) is computed along the way ([HeartRatePeakTracker]) with
 * the user's maximum heart rate resolved by [heartRateMax] when the session starts.
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
 * @param heartRate where the heart rate and the RR intervals come from; `null` uses the console's heart rate.
 * @param heartRateMax resolves the maximum heart rate of the session's owner when the session starts, for the
 *   plausibility rules of the peak (`null`: only the absolute ceiling applies).
 */
class WorkoutHistoryObserver(
    private val controller: TreadmillController,
    private val store: WorkoutSessionStore,
    private val activeUserId: suspend () -> Long?,
    private val clock: Clock = Clock.System,
    private val config: WorkoutObserverConfig = WorkoutObserverConfig(),
    private val onError: (Throwable) -> Unit = {},
    private val heartRate: HeartRateSource? = null,
    private val heartRateMax: suspend (userId: Long?) -> Int? = { null },
) {

    private val _activeWorkoutId = MutableStateFlow<Long?>(null)

    /** Id of the workout being recorded, `null` when there is no session. */
    val activeWorkoutId: StateFlow<Long?> = _activeWorkoutId.asStateFlow()

    private val _lastWorkoutId = MutableStateFlow<Long?>(null)

    /**
     * Id of the most recent workout this observer recorded: the one in progress or, once it ends, the one that has just
     * ended (it stays until the next session starts). The app uses it to attach data to a workout that ended a moment
     * ago, e.g. `WorkoutHistoryRepository.attachSessionRecord`. A session in which the belt never moved is discarded and
     * its id remains here, but the workout no longer exists.
     */
    val lastWorkoutId: StateFlow<Long?> = _lastWorkoutId.asStateFlow()

    private sealed interface Input {
        val at: Long

        class State(val state: TreadmillState, override val at: Long) : Input
        class Connection(val state: ConnectionState, override val at: Long) : Input
        class ConnectionLost(override val at: Long) : Input
        class Tick(override val at: Long) : Input
        class RrIntervals(val intervalsMs: List<Int>, override val at: Long) : Input
        class Link(val link: WorkoutLink, override val at: Long) : Input
        class ClearLink(override val at: Long) : Input
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
    private var pendingLink: WorkoutLink? = null
    private var pendingLinkAt = 0L

    // A link announced before the observer started: delivered as soon as it does.
    private var earlyLink: Input.Link? = null

    /**
     * Attaches [link] to the session in progress or, if none is, to the next session that starts (see the class
     * description). Links announced one after the other are merged (a part given later replaces the earlier one).
     */
    fun linkNextSession(link: WorkoutLink) {
        if (link.isEmpty) return
        val input = Input.Link(link, clock.nowMillis())
        synchronized(lock) { inputs?.trySend(input) ?: run { earlyLink = earlyLink?.let { Input.Link(it.link.merge(link), input.at) } ?: input } }
    }

    /** Withdraws a link announced for the next session (nothing happens to a session already linked). */
    fun clearLink() {
        synchronized(lock) { inputs?.trySend(Input.ClearLink(clock.nowMillis())) ?: run { earlyLink = null } }
    }

    /**
     * Starts observing in [scope], which should use a background dispatcher. Closes workouts left open by a dead
     * process first. Calling it again while running does nothing. Returns the observer's job.
     */
    fun start(scope: CoroutineScope): Job = synchronized(lock) {
        running?.let { if (it.isActive) return it }
        val channel = Channel<Input>(Channel.UNLIMITED)
        inputs = channel
        earlyLink?.let { channel.trySend(it) }
        earlyLink = null
        val job = scope.launch {
            launch { controller.state.collect { channel.trySend(Input.State(it, clock.nowMillis())) } }
            launch { controller.connection.collect { channel.trySend(Input.Connection(it, clock.nowMillis())) } }
            launch {
                controller.events.collect { if (it is TreadmillEvent.ConnectionLost) channel.trySend(Input.ConnectionLost(clock.nowMillis())) }
            }
            heartRate?.let { source ->
                // Only the intervals are taken from the readings: the heart rate itself is read when a sample is written.
                launch {
                    source.readings.collect { r ->
                        if (r.rrIntervalsMs.isNotEmpty()) channel.trySend(Input.RrIntervals(r.rrIntervalsMs, clock.nowMillis()))
                    }
                }
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
                    is Input.RrIntervals -> session?.addRrIntervals(input.intervalsMs)
                    is Input.Tick -> onTick(input.at)
                    is Input.Link -> onLink(input.link, input.at)
                    is Input.ClearLink -> pendingLink = null
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
                started.add(state, at, config, heartReading(state))
            }
            return
        }
        if (idle) {
            s.add(state, at, config, heartReading(state), terminal = true)
            endSession(normalEndReason(s), at)
        } else {
            s.add(state, at, config, heartReading(state))
            if (s.decelerationTimedOut(at, config)) endSession(normalEndReason(s), at)
        }
    }

    /** The heart rate and its source for the sample of [state] (see the class description). */
    private fun heartReading(state: TreadmillState): HeartReading {
        val source = heartRate
        if (source == null) {
            val bpm = state.heartRateBpm
            return HeartReading(bpm, if (bpm != null && bpm > 0) HeartRateSourceKind.TreadmillConsole else null)
        }
        val reading = source.latest()
        val bpm = reading.bpm?.takeIf { it > 0 && reading.validity != HeartRateValidity.Invalid }
        return HeartReading(bpm, if (bpm != null) reading.source else null)
    }

    private class HeartReading(val bpm: Int?, val source: HeartRateSourceKind?)

    private suspend fun onLink(link: WorkoutLink, at: Long) {
        val s = session
        if (s != null) {
            s.unwrittenLink = s.unwrittenLink?.merge(link) ?: link
            writeLink(s)
        } else {
            val waiting = pendingLink?.takeIf { at - pendingLinkAt <= config.linkValidityMs }
            pendingLink = waiting?.merge(link) ?: link
            pendingLinkAt = at
        }
    }

    /** Writes the link of the session if it is not stored yet; a failure is retried on the next flush. */
    private suspend fun writeLink(s: Session) {
        val link = s.unwrittenLink ?: return
        val exists = try {
            store.attachLink(s.workoutId, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            return
        }
        s.unwrittenLink = null
        if (!exists) abandon()
    }

    private suspend fun onTick(at: Long) {
        // States keep arriving while connected, but a silent controller must not keep a decelerating session open.
        val s = session
        if (s != null && s.decelerationTimedOut(at, config)) endSession(normalEndReason(s), at) else flush()
    }

    private fun normalEndReason(s: Session) =
        if (s.lastNonIdleStatus == BeltStatus.SafetyKeyRemoved) WorkoutEndReason.SafetyKeyRemoved else WorkoutEndReason.Stopped

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
        val link = pendingLink?.takeIf { at - pendingLinkAt <= config.linkValidityMs }
        val id = try {
            store.begin(userId, at, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            blockedUntilIdle = true
            return null
        }
        pendingLink = null
        val maxHeartRate = try {
            heartRateMax(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            null
        }
        val s = Session(id, at, maxHeartRate)
        session = s
        _activeWorkoutId.value = id
        _lastWorkoutId.value = id
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
        writeLink(s)
        if (session !== s) return
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
                writeLink(s)
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

    private class Session(val workoutId: Long, val startedAt: Long, maxHeartRateBpm: Int?) {
        val buffer = ArrayDeque<WorkoutSample>()
        private val peakTracker = HeartRatePeakTracker(maxHeartRateBpm)

        /** RR intervals received since the last sample was written. */
        private val pendingRr = ArrayList<Int>()
        var statsDirty = false

        /** A link announced during the session that has not been stored yet. */
        var unwrittenLink: WorkoutLink? = null
        var lastNonIdleStatus: BeltStatus = BeltStatus.Starting

        // The belt reported Idle but is still moving: when that began, when its speed last changed, and that speed.
        private var decelerationSince: Long? = null
        private var decelerationProgressAt = 0L
        private var decelerationSpeed = 0.0
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
        private var lastHeartRate: Int? = null
        private var lastHeartSource: HeartRateSourceKind? = null
        private var maxIncline = 0.0

        val hasMoved: Boolean get() = maxSpeed > 0.0

        /** True when the belt has been in `Idle` while still moving for too long (see [WorkoutObserverConfig]). */
        fun decelerationTimedOut(at: Long, config: WorkoutObserverConfig): Boolean {
            val since = decelerationSince ?: return false
            return at - decelerationProgressAt >= config.idleStallMs || at - since >= config.idleMaxMs
        }

        /**
         * @param terminal the packet that reports the belt stopped: always recorded (it is the end of the workout,
         *   whatever the spacing), keeping the distance, time and calories reached, which the treadmill has just reset,
         *   and the last heart rate before it (the console reports a sudden drop of ~20 bpm in that packet).
         */
        fun addRrIntervals(intervalsMs: List<Int>) {
            pendingRr.addAll(intervalsMs)
            while (pendingRr.size > MAX_PENDING_RR) pendingRr.removeAt(0)
        }

        fun add(state: TreadmillState, at: Long, config: WorkoutObserverConfig, heart: HeartReading, terminal: Boolean = false) {
            if (state.status != BeltStatus.Idle) {
                lastNonIdleStatus = state.status
                decelerationSince = null
            } else if (state.speedKmh > 0.0) {
                if (decelerationSince == null) {
                    decelerationSince = at
                    decelerationProgressAt = at
                    decelerationSpeed = state.speedKmh
                } else if (state.speedKmh != decelerationSpeed) {
                    decelerationSpeed = state.speedKmh
                    decelerationProgressAt = at
                }
            }
            val spaced = at - lastSampleAt >= config.minSampleSpacingMs
            if (!terminal && !spaced && state.status == lastStatus) return
            lastSampleAt = at
            lastStatus = state.status
            val repeatLast = terminal && lastHeartRate != null
            val bpm = if (repeatLast) lastHeartRate else heart.bpm
            val source = if (repeatLast) lastHeartSource else heart.source
            val rr = if (pendingRr.isEmpty()) emptyList() else pendingRr.toList().also { pendingRr.clear() }
            val sample = WorkoutSample(
                seq = seq++,
                timestampMillis = at,
                elapsedSeconds = if (terminal) maxOf(state.elapsedSeconds, duration) else state.elapsedSeconds,
                speedKmh = state.speedKmh,
                targetSpeedKmh = state.targetSpeedKmh,
                inclinePercent = state.inclinePercent,
                distanceMeters = if (terminal) maxOf(state.distanceMeters, distance) else state.distanceMeters,
                calories = if (terminal) maxOf(state.calories, calories) else state.calories,
                heartRateBpm = bpm,
                beltStatus = state.status,
                isStale = state.isStale,
                heartRateSource = source,
                rrIntervalsMs = rr,
            )
            buffer.addLast(sample)
            duration = maxOf(duration, sample.elapsedSeconds)
            distance = maxOf(distance, sample.distanceMeters)
            calories = maxOf(calories, sample.calories)
            if (!terminal) {
                lastHeartRate = heart.bpm
                lastHeartSource = heart.source
            }
            if (!state.isStale && !terminal) {
                maxSpeed = maxOf(maxSpeed, state.speedKmh)
                if (state.speedKmh > 0.0) {
                    speedSum += state.speedKmh
                    speedCount++
                }
                maxIncline = maxOf(maxIncline, state.inclinePercent)
                peakTracker.add(at, heart.bpm, heart.source)
                val hr = heart.bpm
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
            peakHeartRate = peakTracker.peak,
        )

        private companion object {
            const val MAX_PENDING_RR = 10_000
        }
    }
}
