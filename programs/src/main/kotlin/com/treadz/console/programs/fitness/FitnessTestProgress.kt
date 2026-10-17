package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.treadmill.CommandResult

/** Who is tested. [hrMaxBpm] is the profile's effective maximum heart rate (measured, or estimated). */
data class FitnessSubject(
    val ageYears: Int,
    val sex: Sex,
    /** Not used by the walking heart-rate test, which accepts 0 (unknown). */
    val weightKg: Double,
    val hrMaxBpm: Int,
)

/** Start parameters of the Ebbeling walking test. */
data class EbbelingParameters(
    val subject: FitnessSubject,
    /** Walking speed of stage 1 (the UI suggests the last test's speed). Clamped to 3.2-7.2 km/h and the limits. */
    val startSpeedKmh: Double = 4.8,
    /** Id of the result, when the caller needs it before the test starts (e.g. to link the workout); else a new one. */
    val resultId: String? = null,
)

/** Start parameters of the maximal heart-rate walking test (modified Balke). */
data class MaxHeartRateWalkParameters(
    val subject: FitnessSubject,
    /** Speed of the ramp (and base of the warm-up speeds). Clamped to 3-7.5 km/h and to the speed cap. */
    val startSpeedKmh: Double = 5.5,
    /** Incline of the first ramp stage. Clamped to 0-15 % and to the incline cap. */
    val startInclinePercent: Double = 5.0,
    val resultId: String? = null,
)

/** Start parameters of the maximal incremental test. */
data class MaxTestParameters(
    val subject: FitnessSubject,
    /** Speed of the first incremental stage. Clamped to 6-10 km/h and to the speed cap. */
    val startSpeedKmh: Double = 8.0,
    /** Id of the result, when the caller needs it before the test starts (e.g. to link the workout); else a new one. */
    val resultId: String? = null,
)

/** What the user should be doing now: a stable code the UI turns into a short instruction. */
enum class FitnessInstruction {
    /** The belt is starting: get ready. */
    BeltStarting,

    /**
     * Ebbeling stage 1: walk naturally; the speed may change to bring the heart rate into the band, and the user can
     * change it too (`FitnessTestRunner.adjustSpeed` or the console buttons) until [WalkSpeedFixed].
     */
    WalkSpeedMayChange,

    /** Ebbeling stage 1, last part: the speed is fixed; keep walking. */
    WalkSpeedFixed,

    /** Ebbeling stage 2: keep walking, the grade is 5 %; do not hold the handrails. */
    WalkUphill,

    /** Ebbeling stage 2 extended: the heart rate is not steady yet; keep walking. */
    WalkUphillExtended,

    /** Maximal test: brisk walk to warm up. */
    WarmUpWalk,

    /** Maximal test: easy jog to warm up. */
    WarmUpJog,

    /** Maximal test: keep running; the speed rises every minute. Press "exhausted" when you cannot keep up. */
    RunUntilExhausted,

    /** Maximal test: the speed cap was reached; hold it as long as you can and press "exhausted". */
    HoldTopSpeed,

    /**
     * Walking heart-rate test ramp: keep walking (do not hold the handrails); the incline (later the speed) rises every
     * minute. Press "exhausted" when you cannot keep up.
     */
    WalkUphillUntilExhausted,

    /** Easy walk to cool down; the belt stops by itself. */
    CoolDownWalk,

    /** The belt is stopping. */
    Stopping,

    /** The test is over: look at the result. */
    Finished,
}

/** Steady-state status of Ebbeling stage 2. */
enum class SteadyStateStatus {
    /** Not evaluated yet (the end of the stage has not come). */
    Pending,

    /** Evaluated and steady. */
    Steady,

    /** Evaluated, not steady: the stage was extended. */
    Extended,

    /** Not steady after every extension. */
    Failed,
}

/** What the UI shows during a test. */
data class FitnessTestProgress(
    val testType: FitnessTestType? = null,
    val phase: FitnessTestPhase = FitnessTestPhase.Idle,
    val instruction: FitnessInstruction? = null,
    /** Test time, from the moment the belt was running for the test. */
    val elapsedSeconds: Int = 0,
    val phaseElapsedSeconds: Int = 0,
    /** Time left in the phase; `null` for the open-ended incremental phase. */
    val phaseRemainingSeconds: Int? = null,
    /** Ebbeling: 1 or 2. Maximal test: incremental stage (1-based), `null` outside it. */
    val stageNumber: Int? = null,
    /** Maximal test: seconds to the next speed increase; `null` at the speed cap or outside the incremental phase. */
    val stageRemainingSeconds: Int? = null,
    val targetSpeedKmh: Double? = null,
    val targetInclinePercent: Double? = null,
    /** Maximal tests: speed of the next stage (`null` at the cap). Walking test warm-up: the ramp start speed. */
    val nextStageSpeedKmh: Double? = null,
    /** Walking heart-rate test: incline of the next stage (`null` at the cap); during the warm-up the ramp start incline. */
    val nextStageInclinePercent: Double? = null,
    val actualSpeedKmh: Double = 0.0,
    val actualInclinePercent: Double = 0.0,
    val heartRateBpm: Int? = null,
    val heartRateValidity: HeartRateValidity = HeartRateValidity.Invalid,
    /** Ebbeling: the 50-70 % band (bpm). */
    val heartRateBandLowBpm: Int? = null,
    val heartRateBandHighBpm: Int? = null,
    /** Ebbeling: the safety stop limit (85 % of the user's maximum). */
    val heartRateLimitBpm: Int? = null,
    /**
     * Speed and incline may be changed with the app's controls without spoiling the test: the cool-down. They go through
     * [FitnessTestRunner.adjustByHand]. (The console buttons are also accepted in the walking test's warm-up, where the
     * app offers the ramp start instead.)
     */
    val manualChangesAllowed: Boolean = false,
    /** Ebbeling: the speed will not change any more (nor can the user change it: from here a change invalidates the test). */
    val speedLocked: Boolean = false,
    /** Ebbeling stage 2. */
    val steadyState: SteadyStateStatus? = null,
    /** Ebbeling stage 2: current variation over the last 2 min (`null` until 2 min of stage 2). */
    val steadyStateVariationBpm: Double? = null,
    val stage2Extensions: Int = 0,
    /** Maximal test: the "exhausted" button applies now. */
    val canReportExhaustion: Boolean = false,
    /** Maximal test: the speed cap was reached. */
    val capReached: Boolean = false,
    /** Maximal test: the MAS that would be recorded if the user reported exhaustion now. */
    val masIfExhaustedNowKmh: Double? = null,
    /** "End test" (early end with cool-down) applies now. */
    val canEndTest: Boolean = false,
    /** Set when the measuring part has ended (during the cool-down already). */
    val endReason: FitnessEndReason? = null,
    /** The result: preliminary during the cool-down (it does not change except for the cool-down data), final when [phase] is [FitnessTestPhase.Finished]. */
    val result: FitnessTestResult? = null,
)

/** Events of a test, in order, for the recorder and debugging. [atMillis] is epoch time of the injected clock. */
sealed interface FitnessTestEvent {
    val atMillis: Long

    data class Started(override val atMillis: Long, val testType: FitnessTestType, val inputs: FitnessTestInputs) : FitnessTestEvent

    data class PhaseStarted(override val atMillis: Long, val testSecond: Int, val phase: FitnessTestPhase) : FitnessTestEvent

    /** The runner sent new targets (only the values that changed). */
    data class TargetsApplied(override val atMillis: Long, val testSecond: Int, val speedKmh: Double?, val inclinePercent: Double?) : FitnessTestEvent

    /** Ebbeling stage 1: speed change towards the heart-rate band, or by the user (see [SpeedAdjustment.origin]). */
    data class SpeedAdjusted(override val atMillis: Long, val adjustment: SpeedAdjustment) : FitnessTestEvent

    /** Maximal test: a new incremental stage. */
    data class StageStarted(override val atMillis: Long, val testSecond: Int, val number: Int, val speedKmh: Double) : FitnessTestEvent

    /** Ebbeling stage 2 extended by one minute. */
    data class SteadyStateExtended(override val atMillis: Long, val extension: Int, val variationBpm: Double?) : FitnessTestEvent

    /** Speed or incline changed by hand; [brokeTest] when it ended the test. */
    data class ManualChange(override val atMillis: Long, val speedKmh: Double?, val inclinePercent: Double?, val brokeTest: Boolean) : FitnessTestEvent

    /** The measuring part ended: [result] is already known (the cool-down follows, unless the belt stopped). */
    data class EffortEnded(override val atMillis: Long, val reason: FitnessEndReason, val result: FitnessTestResult) : FitnessTestEvent

    data class Finished(override val atMillis: Long, val result: FitnessTestResult) : FitnessTestEvent
}

/** Why a user action of the test runner was refused. */
enum class FitnessRefusal {
    AlreadyActive,
    NotActive,

    /** The action does not apply in this phase or to this test. */
    WrongState,
    NotConnected,
    SafetyKeyRemoved,

    /** The belt is starting or stopping. */
    BeltBusy,

    /** The treadmill data is not current. */
    DataNotCurrent,

    /** Ebbeling needs a current, non-zero heart rate before starting (chest strap on and paired). */
    NoHeartRate,

    /** Age, weight or maximum heart rate out of range. */
    InvalidSubject,

    /** The user's safety limits do not allow the test (e.g. a maximum incline below 5 % for Ebbeling). */
    LimitsTooLow,
}

sealed interface FitnessActionResult {
    data object Success : FitnessActionResult

    data class NotAllowed(val refusal: FitnessRefusal, val message: String) : FitnessActionResult

    /** A command the action needed failed (e.g. the belt did not start). */
    data class CommandFailed(val result: CommandResult) : FitnessActionResult
}
