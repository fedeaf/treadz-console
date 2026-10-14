package com.treadz.console.nav

import com.treadz.console.programs.fitness.FitnessTestType

/** The screens of the app. Navigation is a simple back stack (see [NavigationViewModel]). */
sealed interface Screen {
    /** Live data, connection and Start. */
    data object Main : Screen

    /** Who is training. */
    data object UserSelection : Screen

    /**
     * Creates ([profileId] `null`), edits or, for the generic profile, personalises a profile. [token] makes every visit
     * a new screen instance, so a form never reuses what a previous visit left behind.
     */
    data class ProfileEditor(val profileId: Long?, val token: Int) : Screen

    /** General settings (synchronisation, users, treadmill, appearance, language, permissions, about). */
    data object Settings : Screen

    data object Weight : Screen

    data object History : Screen

    data class WorkoutDetail(val workoutId: Long) : Screen

    /** The standard programs and the active user's own programs. */
    data object Programs : Screen

    /** Choose duration and reference values of a program and start it. */
    data class ProgramStart(val programId: String) : Screen

    /** Creates ([programId] `null`) or edits a free program. [token] makes every visit a new screen instance. */
    data class ProgramEditor(val programId: String?, val token: Int) : Screen

    /** The two fitness tests, the user's fitness figures and their past tests. */
    data object FitnessTests : Screen

    /**
     * The inputs of a personalised program and the session generated from them. [fromProgramId] is a saved personalised
     * program whose inputs fill the form ("Regenerate with today's data"), or `null`.
     */
    data class Generator(val generatorId: String, val fromProgramId: String? = null) : Screen

    /** Warning, subject and start speed of a fitness test. */
    data class FitnessStart(val type: FitnessTestType) : Screen

    /** A stored fitness test result. */
    data class FitnessResult(val testId: String) : Screen

    /** The treadmill and the active user's heart-rate strap: state, connect, search, pair, forget. */
    data object Devices : Screen

    /** Measure the resting heart rate (no treadmill needed), its reference and history. */
    data object RestingHeartRate : Screen

    /** The maximum heart rate of a profile: the value in use, where it comes from and every measurement. */
    data class HeartRateMax(val profileId: Long) : Screen
}
