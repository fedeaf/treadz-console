package com.treadz.console.training

import com.treadz.console.programs.fitness.EbbelingMeasurements
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.MaxHeartRateWalkMeasurements
import com.treadz.console.programs.fitness.Validity

/** How a result compares with the previous valid test of the same type. */
data class FitnessComparison(
    val previousResultId: String,
    val previousStartedAtMillis: Long,
    val previousVo2max: Double,
    /** This VO2max minus the previous one. */
    val vo2maxDelta: Double,
    val previousMasKmh: Double?,
    val masDelta: Double?,
    /** The change is smaller than the measurement error of these tests (about 5 ml/kg/min). */
    val withinMeasurementError: Boolean,
)

object FitnessResults {

    /** Two VO2max values closer than this are within the error of the submaximal and field tests. */
    const val MEASUREMENT_ERROR_VO2MAX = 5.0

    /** The latest valid test of the same type that was done before [result], or `null`. */
    fun previousOf(result: FitnessTestResult, all: List<FitnessTestResult>): FitnessTestResult? =
        all.filter {
            it.id != result.id && it.testType == result.testType && it.validity == Validity.Valid && it.outputs != null &&
                it.startedAtMillis < result.startedAtMillis
        }.maxByOrNull { it.startedAtMillis }

    /** `null` when [result] has no outputs (it is invalid) or there is no previous test to compare with. */
    fun compare(result: FitnessTestResult, all: List<FitnessTestResult>): FitnessComparison? {
        val outputs = result.outputs ?: return null
        val previous = previousOf(result, all) ?: return null
        val previousOutputs = previous.outputs ?: return null
        val delta = outputs.vo2maxMlKgMin - previousOutputs.vo2maxMlKgMin
        val mas = outputs.masKmh
        val previousMas = previousOutputs.masKmh
        return FitnessComparison(
            previousResultId = previous.id,
            previousStartedAtMillis = previous.startedAtMillis,
            previousVo2max = previousOutputs.vo2maxMlKgMin,
            vo2maxDelta = delta,
            previousMasKmh = previousMas,
            masDelta = if (mas != null && previousMas != null) mas - previousMas else null,
            withinMeasurementError = kotlin.math.abs(delta) < MEASUREMENT_ERROR_VO2MAX,
        )
    }

    /** Speed to suggest for the next Ebbeling test: the speed of the last one (valid or not, as far as it got), else [default]. */
    fun suggestedEbbelingSpeed(all: List<FitnessTestResult>, default: Double): Double {
        val last = all.filter { it.testType == FitnessTestType.EbbelingWalk }.maxByOrNull { it.startedAtMillis } ?: return default
        return (last.measurements as? EbbelingMeasurements)?.testSpeedKmh ?: last.inputs.startSpeedKmh
    }

    /** Start speed of the last maximal test, else [default]. */
    fun suggestedMaxStartSpeed(all: List<FitnessTestResult>, default: Double): Double =
        all.filter { it.testType == FitnessTestType.MaxIncrementalRun }.maxByOrNull { it.startedAtMillis }?.inputs?.startSpeedKmh ?: default

    /** Ramp start (speed, incline) of the last walking heart-rate test, as it ended up after the warm-up, else the defaults. */
    fun suggestedWalkStart(all: List<FitnessTestResult>, defaultSpeed: Double, defaultIncline: Double): Pair<Double, Double> {
        val last = all.filter { it.testType == FitnessTestType.MaxHeartRateWalk }.maxByOrNull { it.startedAtMillis }
        val m = last?.measurements as? MaxHeartRateWalkMeasurements ?: return defaultSpeed to defaultIncline
        return m.startSpeedKmh to m.startInclinePercent
    }
}
