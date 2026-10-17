package com.treadz.console.training

import com.treadz.console.Decimals
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.analysis.fitness.Sex as FitnessSex
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.GeneratedProgram
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.programs.personalised.GeneratorDescriptor
import com.treadz.console.programs.personalised.GeneratorInput
import com.treadz.console.programs.personalised.GeneratorProfile
import com.treadz.console.programs.personalised.GeneratorRequest
import com.treadz.console.programs.personalised.IntensityTranslator
import com.treadz.console.programs.personalised.ProgramGenerators
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WeightEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** What the generators need to know about the active user, as the data layer has it now. */
data class GeneratorContext(
    val user: UserProfile,
    /** The latest weight entry; a placeholder entry (the generic profile's 80 kg) counts as unknown. */
    val weight: WeightEntry?,
    val fitnessProfile: FitnessProfile?,
    val heartRateMax: HeartRateMaxDecision?,
    /** The resting heart-rate reference. */
    val restingBpm: Int?,
    /** The records of the user's sessions of the generator, newest first. */
    val history: List<GeneratedSessionRecord>,
)

/** The inputs of a generator as the form holds them. */
data class GeneratorForm(
    val mode: ExerciseMode = ExerciseMode.Run,
    val durationMinutes: Int? = null,
    val series: Int? = null,
    /** Typed; empty = automatic. */
    val walkingSpeed: String = "",
    /** Couch to 5K: the week to start at; `null` = continue the plan. */
    val startWeek: Int? = null,
    /** Couch to 5K: the ± adjustment of the jog speed, percent. */
    val jogAdjustmentPercent: Int = 0,
) {
    /** The walking speed typed, `null` when empty or not a number. */
    val walkingSpeedKmh: Double? get() = Decimals.parse(walkingSpeed)

    /** The walking speed is empty (automatic) or a number the generators accept. */
    val walkingSpeedValid: Boolean
        get() = walkingSpeed.isBlank() || walkingSpeedKmh?.let { it in WALKING_SPEED_RANGE } == true

    /** What is remembered for next time: the start week is not (the plan continues by default). */
    fun toStored(descriptor: GeneratorDescriptor): Map<String, String> = buildMap {
        if (GeneratorInput.Mode in descriptor.inputs) put("mode", mode.name)
        if (GeneratorInput.DurationMinutes in descriptor.inputs) durationMinutes?.let { put("duration", it.toString()) }
        if (GeneratorInput.Series in descriptor.inputs) series?.let { put("series", it.toString()) }
        if (GeneratorInput.WalkingSpeed in descriptor.inputs) put("walk", walkingSpeed.trim())
        if (GeneratorInput.JogSpeedAdjustment in descriptor.inputs) put("jog", jogAdjustmentPercent.toString())
    }

    companion object {
        val WALKING_SPEED_RANGE: ClosedFloatingPointRange<Double> =
            IntensityTranslator.MIN_WALKING_SPEED_KMH..IntensityTranslator.MAX_WALKING_SPEED_KMH

        /** The values a generator starts with when nothing is remembered. */
        fun defaults(descriptor: GeneratorDescriptor) = GeneratorForm(
            mode = if (ExerciseMode.Run in descriptor.modes) ExerciseMode.Run else ExerciseMode.Walk,
            durationMinutes = descriptor.defaultDurationMinutes,
            series = descriptor.defaultSeries,
        )

        /** The form of [descriptor] with what was [stored] (values outside what the generator allows are brought inside). */
        fun fromStored(descriptor: GeneratorDescriptor, stored: Map<String, String>): GeneratorForm {
            val base = defaults(descriptor)
            val mode = stored["mode"]?.let { name -> ExerciseMode.entries.firstOrNull { it.name == name } }?.takeIf { it in descriptor.modes }
            val duration = stored["duration"]?.toIntOrNull()
            val series = stored["series"]?.toIntOrNull()
            val walk = stored["walk"]?.takeIf { Decimals.parse(it)?.let { v -> v in WALKING_SPEED_RANGE } == true }
            val jog = stored["jog"]?.toIntOrNull()
            return base.copy(
                mode = mode ?: base.mode,
                durationMinutes = descriptor.durationMinutes?.let { range -> duration?.coerceIn(range.first, range.last) } ?: base.durationMinutes,
                series = descriptor.series?.let { range -> series?.coerceIn(range.first, range.last) } ?: base.series,
                walkingSpeed = walk ?: "",
                jogAdjustmentPercent = (jog ?: 0).coerceIn(-JOG_LIMIT, JOG_LIMIT) / JOG_STEP * JOG_STEP,
            )
        }

        const val JOG_LIMIT = 20
        const val JOG_STEP = 5
    }
}

/** The active user's data, as a flow that follows the active user and every change of what the generators read. */
@OptIn(ExperimentalCoroutinesApi::class)
fun generatorContext(userData: UserData, generatorId: String): Flow<GeneratorContext?> =
    userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(null)
        } else {
            combine(
                userData.weights.latestWeight(user.id),
                userData.fitnessTests.liveFitnessProfile(user.id),
                userData.fitnessTests.heartRateMax(user.id),
                userData.restingHeartRates.reference(user.id),
                userData.workouts.sessionRecords(user.id, generatorId),
            ) { weight, fitness, hrMax, resting, records ->
                GeneratorContext(user, weight, fitness, hrMax, resting?.bpm, records.map { it.record })
            }
        }
    }

object GeneratorRequests {

    /** The user as the generators see them. The generic profile's placeholder weight is not a weight. */
    fun profileOf(context: GeneratorContext, today: LocalDate): GeneratorProfile {
        val user = context.user
        val decision = context.heartRateMax
        return GeneratorProfile(
            sex = if (user.sex == Sex.Male) FitnessSex.Male else FitnessSex.Female,
            ageYears = user.ageOn(today),
            heightCm = user.heightCm.toDouble(),
            weightKg = context.weight?.takeUnless { it.isPlaceholder }?.weightKg,
            selfRatedFitness = UserZones.selfRatedOf(user.fitnessLevel),
            fitnessProfile = context.fitnessProfile,
            heartRate = HeartRateProfile(decision?.bpm ?: user.estimatedMaxHeartRateBpm(today), context.restingBpm),
            maxHeartRateOrigin = decision?.origin ?: HeartRateMaxOrigin.Estimated,
        )
    }

    /** The request for one generation: only the inputs the generator asks for are passed on. */
    fun requestOf(
        descriptor: GeneratorDescriptor,
        context: GeneratorContext,
        form: GeneratorForm,
        today: LocalDate,
        nowMillis: Long,
        name: String?,
        programId: String,
    ): GeneratorRequest {
        val inputs = descriptor.inputs
        return GeneratorRequest(
            profile = profileOf(context, today),
            nowMillis = nowMillis,
            mode = if (GeneratorInput.Mode in inputs) form.mode else ExerciseMode.Run,
            durationMinutes = form.durationMinutes.takeIf { GeneratorInput.DurationMinutes in inputs },
            series = form.series.takeIf { GeneratorInput.Series in inputs },
            startWeek = form.startWeek.takeIf { GeneratorInput.StartWeek in inputs },
            walkingSpeedKmh = form.walkingSpeedKmh.takeIf { GeneratorInput.WalkingSpeed in inputs },
            jogSpeedAdjustmentPercent = if (GeneratorInput.JogSpeedAdjustment in inputs) form.jogAdjustmentPercent else 0,
            history = context.history,
            programId = programId,
            name = name,
        )
    }

    /** Why a generation is not possible with these values, or `null`. */
    fun problemOf(descriptor: GeneratorDescriptor, context: GeneratorContext, form: GeneratorForm, today: LocalDate): GeneratorProblem? {
        val age = context.user.ageOn(today)
        return when {
            age !in MIN_AGE..MAX_AGE -> GeneratorProblem.AgeOutOfRange
            GeneratorInput.WalkingSpeed in descriptor.inputs && !form.walkingSpeedValid -> GeneratorProblem.WalkingSpeed
            else -> null
        }
    }

    /** Generates; `null` with the generator's own complaint when the request is outside what it allows. */
    fun generate(descriptor: GeneratorDescriptor, request: GeneratorRequest): GeneratedProgram? =
        try {
            ProgramGenerators.generate(descriptor.id, request)
        } catch (_: IllegalArgumentException) {
            null
        }

    /** The next Couch to 5K session for the user, from their history. */
    fun couchSession(context: GeneratorContext, today: LocalDate, nowMillis: Long): GeneratedProgram? {
        val descriptor = CouchToFiveK.descriptor
        val form = GeneratorForm.defaults(descriptor)
        if (problemOf(descriptor, context, form, today) != null) return null
        return generate(descriptor, requestOf(descriptor, context, form, today, nowMillis, name = null, programId = PREVIEW_ID))
    }

    const val MIN_AGE = 10
    const val MAX_AGE = 100
    private const val PREVIEW_ID = "00000000-0000-4000-8000-000000000000"

    /** The sessions the user has done, newest first, from the stored records. */
    fun lastSessionAtMillis(history: List<GeneratedSessionRecord>): Long? = history.maxOfOrNull { it.endedAtMillis }
}

/** Why the generator cannot produce a session with the current values. */
enum class GeneratorProblem {
    /** The profile's age is outside what the generators handle (10-100). */
    AgeOutOfRange,

    /** The walking speed typed is not a number or outside the allowed range. */
    WalkingSpeed,

    /** The generator refused the values. */
    Rejected,
}
