package com.treadz.console.nav

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

    data object Weight : Screen

    data object History : Screen

    data class WorkoutDetail(val workoutId: Long) : Screen
}
