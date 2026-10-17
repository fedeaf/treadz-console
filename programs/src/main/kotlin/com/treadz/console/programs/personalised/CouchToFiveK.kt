package com.treadz.console.programs.personalised

import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.personalised.Generation.Companion.fmt
import com.treadz.console.programs.personalised.Generation.Companion.wire
import kotlin.math.max
import kotlin.math.min

/** Why Couch to 5K offers a session. */
enum class CouchToFiveKDecision {
    /** No usable history: session 1. */
    FirstSession,

    /** The user chose the week to start (or restart) at. */
    ChosenStartWeek,

    /** The previous session was completed without manual speed reductions and with the heart rate below 85 % HRmax. */
    Advance,

    /**
     * Completed, but the heart rate at the end of a run reached 85 % HRmax (or a safety reduction happened): repeat. The
     * end of a continuous heart-rate-guided run inside its zone is never too hard.
     */
    RepeatHardEffort,

    /** Completed, but the user lowered the speed by hand during a run: repeat. */
    RepeatManualReduction,

    /** The previous session was not completed (stopped or abandoned before the end of its runs): one session back. */
    StepBack,

    /** Session 27 was completed: the plan is over; it is offered again. */
    PlanCompleted,
}

/**
 * The Couch to 5K session chosen for today.
 *
 * @property session 1-27; [week] 1-9 and [day] 1-3.
 * @property previousSession The session of the last record used, `null` without one.
 * @property daysSinceLast Days since the end of that session.
 * @property weeksBack 1 after more than 10 days without training, 2 after more than 21 (already applied to [session]).
 * @property recentSessionWarning Less than 48 h since the previous session: a warning, nothing is blocked.
 * @property suggestedStartWeek The week the app suggests to start at, from the VO2max (only a suggestion: by default
 *   the plan starts at session 1).
 */
data class CouchToFiveKSelection(
    val session: Int,
    val week: Int,
    val day: Int,
    val decision: CouchToFiveKDecision,
    val previousSession: Int?,
    val daysSinceLast: Double?,
    val weeksBack: Int,
    val recentSessionWarning: Boolean,
    val suggestedStartWeek: Int,
)

/**
 * Couch to 5K: the 27 classic sessions (9 weeks × 3), from 8 × (1 min run + 1.5 min walk) to 30 min of
 * continuous running, each with a 5 min walking warm-up and a 5 min walking cool-down. Everything at 1 %.
 *
 * **Next session** (from the records of this generator; a record whose runs never started is ignored):
 *  - with [GeneratorRequest.startWeek]: the first session of that week;
 *  - without history: session 1;
 *  - previous session completed (every run ran to its end; a stop during the cool-down still counts) without manual
 *    speed reductions in the runs and with the heart rate at the end of every run below 85 % of the maximum (and no
 *    heart-rate safety reduction): the next one. A continuous guided run that ends inside its zone is fine even above
 *    85 % (zone 3 in % of the reserve can reach it). Runs without a heart-rate reading do not block advancing;
 *  - completed with a manual speed reduction, or with a harder effort than that: the same session again;
 *  - not completed: one session back;
 *  - more than 10 days since the previous session: one more week back (3 sessions); more than 21: two weeks back;
 *  - less than 48 h since the previous session: only a warning.
 *
 * **Speeds**: jog at 62.5 % of the MAS (the middle of 60-65 %; × 0.9 when a reference is estimated), at least 6 km/h
 * and at least the walking speed + 0.5, then the user's ± adjustment. Walking at the speed asked, else the final speed
 * of the last walking block of the previous session, else 5 km/h.
 *
 * **Heart rate**: runs of 6 min or more are guided in zone 3 (70-80 % of the heart-rate reserve, see
 * [ProgramGenerators.reserveZone]; continuous), starting at the jog speed or where the heart rate settled in the last
 * such run, between a slow jog (walking speed + [MIN_JOG_ABOVE_WALK_KMH]; they stay runs) and the jog speed + 1 km/h
 * (or 75 % of the MAS). Shorter runs keep the jog speed (never faster) with the heart-rate ceiling: their zone is 60-85 % HRmax, so
 * the speed only comes down when the heart rate goes above 85 % (or above the ceiling, 85 % + 8 bpm), at most to the
 * walking speed. Both give the heart rate at the end of every run in the session record.
 */
object CouchToFiveK : ProgramGenerator {

    const val ID = "couch-to-5k"
    const val SESSIONS = 27

    /** Heart rate at the end of a run at or above this fraction of the maximum means "too hard": repeat. */
    const val HARD_END_HEART_RATE_FRACTION = 0.85

    const val JOG_MAS_FRACTION = 0.625
    const val CONTINUOUS_RUN_SECONDS = 360

    /** The slowest jog: the walking speed plus this. Also the lowest speed of a continuous guided run. */
    const val MIN_JOG_ABOVE_WALK_KMH = 0.5

    override val descriptor = GeneratorDescriptor(
        id = ID,
        version = 1,
        inputs = setOf(GeneratorInput.StartWeek, GeneratorInput.WalkingSpeed, GeneratorInput.JogSpeedAdjustment),
        modes = setOf(ExerciseMode.Run),
        startWeeks = 1..9,
        heartRateGuided = true,
        usesHistory = true,
    )

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
    private val SHORT_RUN_ZONE = HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 85)

    // ------------------------------------------------------------------ sessions

    /** A run (`true`) or walk of [seconds]. */
    private data class Seg(val run: Boolean, val seconds: Int)

    private sealed interface Part
    private data class Single(val seg: Seg) : Part
    private data class Rep(val count: Int, val segs: List<Seg>) : Part

    private fun r(seconds: Int) = Seg(true, seconds)
    private fun w(seconds: Int) = Seg(false, seconds)
    private fun seq(vararg segs: Seg): List<Part> = segs.map { Single(it) }

    private val WEEKS: List<List<List<Part>>> = run {
        fun same(parts: List<Part>) = listOf(parts, parts, parts)
        listOf(
            same(listOf(Rep(8, listOf(r(60), w(90))))),
            same(listOf(Rep(6, listOf(r(90), w(120))))),
            same(listOf(Rep(2, listOf(r(90), w(90), r(180), w(180))))),
            same(seq(r(180), w(90), r(300), w(150), r(180), w(90), r(300))),
            listOf(
                listOf(Rep(2, listOf(r(300), w(180))), Single(r(300))),
                seq(r(480), w(300), r(480)),
                seq(r(1200)),
            ),
            listOf(
                seq(r(300), w(180), r(480), w(180), r(300)),
                seq(r(600), w(180), r(600)),
                seq(r(1320)),
            ),
            same(seq(r(1500))),
            same(seq(r(1680))),
            same(seq(r(1800))),
        )
    }

    private fun parts(session: Int): List<Part> = WEEKS[(session - 1) / 3][(session - 1) % 3]

    /** Seconds of running and of walking of [session] (1-27), warm-up and cool-down excluded. */
    fun mainSeconds(session: Int): Pair<Int, Int> {
        require(session in 1..SESSIONS) { "session must be between 1 and $SESSIONS (was $session)" }
        var run = 0
        var walk = 0
        for (p in parts(session)) {
            val (segs, count) = when (p) {
                is Single -> listOf(p.seg) to 1
                is Rep -> p.segs to p.count
            }
            for (s in segs) if (s.run) run += s.seconds * count else walk += s.seconds * count
        }
        return run to walk
    }

    fun weekOf(session: Int): Int = (session - 1) / 3 + 1

    fun dayOf(session: Int): Int = (session - 1) % 3 + 1

    fun firstSessionOfWeek(week: Int): Int = (week - 1) * 3 + 1

    // ------------------------------------------------------------------ progression

    /** The session number a record was generated for, `null` if it has none. */
    fun sessionOf(record: GeneratedSessionRecord): Int? = record.origin.inputs["session"]?.toIntOrNull()?.takeIf { it in 1..SESSIONS }

    /**
     * The next session from [history] (records of other generators are ignored). [maxHeartRateBpm] is used for the 85 %
     * rule when a record has no maximum of its own.
     */
    fun nextSession(
        history: List<GeneratedSessionRecord>,
        nowMillis: Long,
        startWeek: Int?,
        maxHeartRateBpm: Int,
        suggestedStartWeek: Int = 1,
    ): CouchToFiveKSelection {
        startWeek?.let { require(it in 1..9) { "startWeek must be between 1 and 9 (was $it)" } }
        val usable = history
            .filter { it.generatorId == ID && sessionOf(it) != null }
            .filter { r -> r.blocks.any { it.phase == BlockPhase.Work } }
        val last = usable.maxByOrNull { it.startedAtMillis }
        val sinceMs = last?.let { max(0L, nowMillis - it.endedAtMillis) }
        val recent = sinceMs != null && sinceMs < 48 * HOUR_MS
        val days = sinceMs?.let { it.toDouble() / DAY_MS }

        fun selection(session: Int, decision: CouchToFiveKDecision, weeksBack: Int) = CouchToFiveKSelection(
            session = session,
            week = weekOf(session),
            day = dayOf(session),
            decision = decision,
            previousSession = last?.let(::sessionOf),
            daysSinceLast = days,
            weeksBack = weeksBack,
            recentSessionWarning = recent,
            suggestedStartWeek = suggestedStartWeek,
        )

        if (startWeek != null) return selection(firstSessionOfWeek(startWeek), CouchToFiveKDecision.ChosenStartWeek, 0)
        if (last == null) return selection(1, CouchToFiveKDecision.FirstSession, 0)

        val previous = checkNotNull(sessionOf(last))
        val runs = last.blocks.filter { it.phase == BlockPhase.Work }
        val (next, decision) = when {
            !last.workCompleted -> max(1, previous - 1) to CouchToFiveKDecision.StepBack
            runs.any { it.manualSpeedReductions > 0 } -> previous to CouchToFiveKDecision.RepeatManualReduction
            isHard(runs, last.maxHeartRateBpm ?: maxHeartRateBpm) -> previous to CouchToFiveKDecision.RepeatHardEffort
            previous >= SESSIONS -> SESSIONS to CouchToFiveKDecision.PlanCompleted
            else -> previous + 1 to CouchToFiveKDecision.Advance
        }
        val weeksBack = when {
            checkNotNull(days) > 21.0 -> 2
            days > 10.0 -> 1
            else -> 0
        }
        return selection(max(1, next - 3 * weeksBack), decision, weeksBack)
    }

    private fun isHard(runs: List<SessionBlockRecord>, maxBpm: Int): Boolean = runs.any { b ->
        val end = b.endHeartRateBpm
        val hr = b.heartRate
        val inContinuousZone = end != null && hr != null && hr.mode == HeartRateControlMode.Continuous && end <= hr.zoneHighBpm
        (end != null && end >= HARD_END_HEART_RATE_FRACTION * maxBpm && !inContinuousZone) || (hr?.safetyReductions ?: 0) > 0
    }

    /** The week the app suggests from the VO2max category for the age and sex: Poor/Fair 1, Good 2, Excellent 3, Superior 4. */
    fun suggestedStartWeek(references: IntensityReferences, profile: GeneratorProfile): Int =
        when (Vo2MaxNorms.classify(references.vo2maxMlKgMin, profile.ageYears, profile.sex).category) {
            Vo2MaxCategory.Poor, Vo2MaxCategory.Fair -> 1
            Vo2MaxCategory.Good -> 2
            Vo2MaxCategory.Excellent -> 3
            Vo2MaxCategory.Superior -> 4
        }

    /** Final speed of the last walking recovery (else warm-up) block of the latest record that has one. */
    private fun lastWalkingSpeed(history: List<GeneratedSessionRecord>): Double? {
        for (r in history.filter { it.generatorId == ID }.sortedByDescending { it.startedAtMillis }) {
            val b = r.blocks.lastOrNull { it.phase == BlockPhase.Recovery } ?: r.blocks.lastOrNull { it.phase == BlockPhase.WarmUp }
            if (b != null) return b.finalSpeedKmh
        }
        return null
    }

    /** Where the heart rate settled in the latest continuous run (≤ 90 days old). */
    private fun settledRunSpeed(history: List<GeneratedSessionRecord>, nowMillis: Long): Double? {
        val cutoff = nowMillis - ProgramGenerators.HISTORY_MAX_AGE_DAYS * DAY_MS
        for (r in history.filter { it.generatorId == ID && it.startedAtMillis >= cutoff }.sortedByDescending { it.startedAtMillis }) {
            val v = r.blocks.lastOrNull { b ->
                val hr = b.heartRate
                b.phase == BlockPhase.Work && hr != null && hr.mode == HeartRateControlMode.Continuous &&
                    hr.adjust == HeartRateAdjust.Speed && hr.settledValue != null
            }?.heartRate?.settledValue
            if (v != null) return v
        }
        return null
    }

    // ------------------------------------------------------------------ generation

    override fun generate(request: GeneratorRequest): GeneratedProgram {
        require(request.jogSpeedAdjustmentPercent in -20..20) { "jogSpeedAdjustmentPercent must be between -20 and 20 (was ${request.jogSpeedAdjustmentPercent})" }
        val g = Generation(
            request, descriptor, usesVo2max = false, usesMas = true, usesMaxHeartRate = true,
            walkingSpeedFallback = { lastWalkingSpeed(request.history) },
        )
        val suggested = suggestedStartWeek(g.refs, request.profile)
        val sel = nextSession(request.history, request.nowMillis, request.startWeek, g.refs.maxHeartRateBpm, suggested)
        val walk = g.walkingSpeed
        val base = max(IntensityTranslator.MIN_RUNNING_SPEED_KMH, JOG_MAS_FRACTION * g.factor * g.refs.masKmh)
        val jog = Generation.clampSpeed(max(base * (1 + request.jogSpeedAdjustmentPercent / 100.0), walk + MIN_JOG_ABOVE_WALK_KMH))
        val zone3 by lazy { g.reserveZone(70, 80) }
        val settled = settledRunSpeed(request.history, request.nowMillis)?.let { Generation.clampSpeed(it) }

        if (sel.recentSessionWarning) {
            g.notices += GeneratorNotice(GeneratorNoticeCode.RecentSession, value = sel.daysSinceLast?.let { Math.round(it * 24 * 10) / 10.0 })
        }
        if (sel.weeksBack > 0) g.notices += GeneratorNotice(GeneratorNoticeCode.LongBreak, value = sel.daysSinceLast?.let { Math.round(it * 10) / 10.0 })
        if (sel.decision == CouchToFiveKDecision.PlanCompleted) g.notices += GeneratorNotice(GeneratorNoticeCode.PlanCompleted)

        g.inputs["session"] = sel.session.toString()
        g.inputs["week"] = sel.week.toString()
        g.inputs["day"] = sel.day.toString()
        g.inputs["decision"] = sel.decision.wire()
        sel.previousSession?.let { g.inputs["previousSession"] = it.toString() }
        sel.daysSinceLast?.let { g.inputs["daysSinceLast"] = fmt(it) }
        g.inputs["weeksBack"] = sel.weeksBack.toString()
        request.startWeek?.let { g.inputs["startWeek"] = it.toString() }
        g.inputs["suggestedStartWeek"] = suggested.toString()
        g.inputs["jogSpeedKmh"] = fmt(jog)
        g.inputs["jogSpeedAdjustmentPercent"] = request.jogSpeedAdjustmentPercent.toString()

        val inc = IntensityTranslator.RUNNING_INCLINE_PERCENT
        val walkTarget = BlockTarget.Absolute(Generation.clampSpeed(walk), inc)
        var settledUsed = false
        fun block(seg: Seg): Block = when {
            !seg.run -> Block(BlockPhase.Recovery, BlockEnd.Duration(seg.seconds), walkTarget)
            seg.seconds >= CONTINUOUS_RUN_SECONDS -> {
                val start = settled ?: jog
                if (settled != null) settledUsed = true
                Block(
                    BlockPhase.Work, BlockEnd.Duration(seg.seconds),
                    BlockTarget.HeartRate(
                        zone3, HeartRateAdjust.Speed,
                        minimum = Generation.clampSpeed(min(walk + MIN_JOG_ABOVE_WALK_KMH, start)),
                        maximum = Generation.clampSpeed(max(start, max(jog + 1.0, 0.75 * g.refs.masKmh))),
                        start = BlockTarget.Absolute(start, inc),
                    ),
                )
            }
            else -> Block(
                BlockPhase.Work, BlockEnd.Duration(seg.seconds),
                BlockTarget.HeartRate(SHORT_RUN_ZONE, HeartRateAdjust.Speed, minimum = min(walk, jog), maximum = jog, start = BlockTarget.Absolute(jog, inc)),
            )
        }

        val main: List<ProgramStep> = parts(sel.session).map { p ->
            when (p) {
                is Single -> block(p.seg)
                is Rep -> Repeat(p.count, p.segs.map { block(it) })
            }
        }
        if (settledUsed && settled != null) {
            g.notices += GeneratorNotice(GeneratorNoticeCode.StartedFromHistory, value = settled)
            g.inputs["settledRunSpeedKmh"] = fmt(settled)
        }
        val steps = listOf(Block(BlockPhase.WarmUp, BlockEnd.Duration(300), walkTarget)) + main +
            Block(BlockPhase.CoolDown, BlockEnd.Duration(300), walkTarget)
        val (runSeconds, walkSeconds) = mainSeconds(sel.session)
        return g.result(
            steps,
            defaultName = "Couch to 5K: week ${sel.week}, day ${sel.day}",
            description = "Session ${sel.session} of $SESSIONS: 5 min walking warm-up, ${minutesText(runSeconds)} running and " +
                "${minutesText(walkSeconds)} walking, 5 min walking cool-down.",
            couchToFiveK = sel,
        )
    }

    private fun minutesText(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} min" else "${fmt(seconds / 60.0)} min"
}
