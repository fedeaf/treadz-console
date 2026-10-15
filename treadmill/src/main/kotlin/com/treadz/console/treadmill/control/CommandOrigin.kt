package com.treadz.console.treadmill.control

import kotlin.coroutines.CoroutineContext

/**
 * Who issued a command to the treadmill. It travels in the caller's coroutine context and is written to the session
 * recording with the command (`"origin"` of a `command` line), so a recording tells the user's changes from those of a
 * program or of the heart-rate control:
 *
 * ```
 * withContext(CommandOrigin.App) { controller.setSpeed(6.0) }
 * ```
 *
 * A command issued without one is recorded without an origin. It never changes how the command is executed.
 */
enum class CommandOrigin(val traceName: String) : CoroutineContext.Element {
    /** The user, from the app's controls (also a program's or test's own STOP, pause and adjustments). */
    App("app"),

    /** The STOP of the notification. */
    Notification("notification"),

    /** A program applying its blocks (or ending). */
    Program("program"),

    /** The heart-rate control of a program block. */
    HeartRateControl("hr_control"),

    /** A fitness test applying its protocol. */
    FitnessTest("test"),
    ;

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<CommandOrigin>
}
