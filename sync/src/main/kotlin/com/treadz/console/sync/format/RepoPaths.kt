package com.treadz.console.sync.format

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** File layout of the repository, see [RepositoryReadme] for the description. */
internal object RepoPaths {
    const val USERS = "users"
    const val README = "README.md"
    const val PROFILE_FILE = "profile.json"
    const val WEIGHT_FILE = "weight.csv"
    const val RESTING_HEART_RATE_FILE = "resting-heart-rate.csv"

    private const val UUID_LENGTH = 36
    private const val MAX_SLUG_LENGTH = 30

    private val DIACRITICS = Regex("\\p{M}+")
    private val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    private val YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT)
    private val MONTH = DateTimeFormatter.ofPattern("MM", Locale.ROOT)

    /** Lower-case ASCII, accents removed, anything else folded into single hyphens; `user` if nothing is left. */
    fun slug(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT)
        val slug = plain.replace(NOT_ALPHANUMERIC, "-").trim('-').take(MAX_SLUG_LENGTH).trim('-')
        return slug.ifEmpty { "user" }
    }

    /** `<slug>-<uuid>`: the name makes the folder readable, the uuid identifies the user. */
    fun userFolder(name: String, uuid: String): String = "${slug(name)}-$uuid"

    /** The user's uuid at the end of a folder name made by [userFolder]; `null` for a folder that is not one. */
    fun uuidOfFolder(folder: String): String? {
        if (folder.length <= UUID_LENGTH + 1 || folder[folder.length - UUID_LENGTH - 1] != '-') return null
        return folder.substring(folder.length - UUID_LENGTH)
    }

    fun userPrefix(folder: String) = "$USERS/$folder/"

    fun profile(folder: String) = "$USERS/$folder/$PROFILE_FILE"

    fun weights(folder: String) = "$USERS/$folder/$WEIGHT_FILE"

    fun restingHeartRates(folder: String) = "$USERS/$folder/$RESTING_HEART_RATE_FILE"

    fun program(folder: String, programUuid: String) = "$USERS/$folder/programs/$programUuid.json"

    fun fitnessTest(folder: String, testId: String) = "$USERS/$folder/fitness-tests/$testId.json"

    /** `users/<folder>/workouts/<yyyy>/<MM>/<yyyyMMdd_HHmmss>` without extension, from the start time in [zone]. */
    fun workoutBase(folder: String, startedAtMillis: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
        return "$USERS/$folder/workouts/${YEAR.format(time)}/${MONTH.format(time)}/${FILE_TIME.format(time)}"
    }

    fun csvOf(jsonPath: String) = jsonPath.removeSuffix(".json") + ".csv"

    /** What a path of the repository is. */
    sealed interface Kind {
        data class Profile(val folder: String) : Kind
        data class Weights(val folder: String) : Kind
        data class RestingHeartRates(val folder: String) : Kind
        data class WorkoutSummary(val folder: String) : Kind
        data class Program(val folder: String) : Kind
        data class FitnessTest(val folder: String) : Kind
        data object Other : Kind
    }

    private val WORKOUT_JSON = Regex("^users/([^/]+)/workouts/\\d{4}/\\d{2}/[^/]+\\.json$")
    private val PROGRAM_JSON = Regex("^users/([^/]+)/programs/[^/]+\\.json$")
    private val FITNESS_TEST_JSON = Regex("^users/([^/]+)/fitness-tests/[^/]+\\.json$")

    fun classify(path: String): Kind {
        val parts = path.split('/')
        if (parts.size < 3 || parts[0] != USERS) return Kind.Other
        val folder = parts[1]
        return when {
            parts.size == 3 && parts[2] == PROFILE_FILE -> Kind.Profile(folder)
            parts.size == 3 && parts[2] == WEIGHT_FILE -> Kind.Weights(folder)
            parts.size == 3 && parts[2] == RESTING_HEART_RATE_FILE -> Kind.RestingHeartRates(folder)
            WORKOUT_JSON.matches(path) -> Kind.WorkoutSummary(folder)
            PROGRAM_JSON.matches(path) -> Kind.Program(folder)
            FITNESS_TEST_JSON.matches(path) -> Kind.FitnessTest(folder)
            else -> Kind.Other
        }
    }
}
