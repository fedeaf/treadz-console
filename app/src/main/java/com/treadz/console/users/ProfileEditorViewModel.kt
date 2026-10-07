package com.treadz.console.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.Decimals
import com.treadz.console.beltBusy
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.HeartRateEstimates
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.Validation
import com.treadz.console.userdata.ValidationCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period

enum class EditorMode {
    /** A new profile. */
    Create,

    /** An existing, named profile. */
    Edit,

    /** The generic profile: it gets a name and real data (and the real weight). */
    Personalize,
}

/** The text and choices of the form, as the user typed them. */
data class ProfileForm(
    val name: String = "",
    val sex: Sex = Sex.Male,
    val height: String = "",
    val birthDate: LocalDate? = null,
    val restingHeartRate: String = "",
    val maxHeartRate: String = "",
    val fitnessLevel: FitnessLevel? = null,
    val goal: Goal? = null,
    /** Only used when personalising: the real current weight. */
    val weight: String = "",
)

data class ProfileEditorUiState(
    val loading: Boolean = true,
    /** The profile being edited does not exist any more. */
    val notFound: Boolean = false,
    val mode: EditorMode = EditorMode.Create,
    val form: ProfileForm = ProfileForm(),
    /** One error per field, from the validation of `:userdata` or from reading the text. */
    val errors: Map<InputField, ValidationCode> = emptyMap(),
    /** Maximum heart rate estimated from the birth date, shown while the field is empty. */
    val estimatedMaxHeartRate: Int? = null,
    val saving: Boolean = false,
    /** Saving failed because of the storage (not because of the data). */
    val saveFailed: Boolean = false,
    val canDelete: Boolean = false,
    /** The profile is the active user and the belt is running: it cannot be deleted now. */
    val deleteLockedByBelt: Boolean = false,
    val confirmingDelete: Boolean = false,
)

/**
 * Create / edit / personalise a profile. [profileId] is `null` to create. Editing the generic profile always
 * personalises it (`ProfileRepository.personalize`), which requires a name and also takes the real weight.
 */
class ProfileEditorViewModel(
    private val profileId: Long?,
    private val profiles: ProfileRepository,
    controller: TreadmillController,
    private val today: () -> LocalDate,
) : ViewModel() {

    private val state = MutableStateFlow(
        ProfileEditorUiState(loading = profileId != null, mode = EditorMode.Create),
    )
    val uiState: StateFlow<ProfileEditorUiState> = state.asStateFlow()

    private var profile: UserProfile? = null
    private var isActive = false
    private var beltBusy = false

    init {
        viewModelScope.launch { controller.beltBusy.collect { beltBusy = it; refreshDeleteLock() } }
        if (profileId != null) {
            viewModelScope.launch {
                val loaded = profiles.profile(profileId).first()
                if (loaded == null) {
                    state.update { it.copy(loading = false, notFound = true) }
                    return@launch
                }
                profile = loaded
                isActive = profiles.activeUserId() == loaded.id
                val totalProfiles = profiles.profiles.first().size
                // The made-up data of the generic profile is not prefilled: the user has to give the real ones.
                val form = if (loaded.isGeneric) {
                    ProfileForm(sex = loaded.sex)
                } else {
                    ProfileForm(
                        name = loaded.name,
                        sex = loaded.sex,
                        height = loaded.heightCm.toString(),
                        birthDate = loaded.birthDate,
                        restingHeartRate = loaded.restingHeartRateBpm?.toString().orEmpty(),
                        maxHeartRate = loaded.maxHeartRateBpm?.toString().orEmpty(),
                        fitnessLevel = loaded.fitnessLevel,
                        goal = loaded.goal,
                    )
                }
                state.update {
                    it.copy(
                        loading = false,
                        mode = if (loaded.isGeneric) EditorMode.Personalize else EditorMode.Edit,
                        form = form,
                        estimatedMaxHeartRate = estimate(form.birthDate),
                        // Deleting the only (generic) profile is pointless: it would be recreated.
                        canDelete = !(loaded.isGeneric && totalProfiles <= 1),
                    )
                }
                refreshDeleteLock()
            }
        }
    }

    private fun refreshDeleteLock() {
        state.update { it.copy(deleteLockedByBelt = isActive && beltBusy) }
    }

    private fun estimate(birthDate: LocalDate?): Int? =
        birthDate?.takeIf { !it.isAfter(today()) }?.let { HeartRateEstimates.maxFromAge(Period.between(it, today()).years) }

    private fun edit(field: InputField?, change: (ProfileForm) -> ProfileForm) = state.update {
        val form = change(it.form)
        it.copy(
            form = form,
            errors = if (field == null) it.errors else it.errors - field,
            estimatedMaxHeartRate = estimate(form.birthDate),
            saveFailed = false,
        )
    }

    fun setName(value: String) = edit(InputField.Name) { it.copy(name = value) }
    fun setSex(value: Sex) = edit(null) { it.copy(sex = value) }
    fun setHeight(value: String) = edit(InputField.Height) { it.copy(height = value) }
    fun setBirthDate(value: LocalDate) = edit(InputField.BirthDate) { it.copy(birthDate = value) }
    fun setRestingHeartRate(value: String) = edit(InputField.RestingHeartRate) { it.copy(restingHeartRate = value) }
    fun setMaxHeartRate(value: String) = edit(InputField.MaxHeartRate) { it.copy(maxHeartRate = value) }
    fun setFitnessLevel(value: FitnessLevel?) = edit(null) { it.copy(fitnessLevel = value) }
    fun setGoal(value: Goal?) = edit(null) { it.copy(goal = value) }
    fun setWeight(value: String) = edit(InputField.Weight) { it.copy(weight = value) }

    /** Validates and saves; calls [onSaved] when it worked. Problems are left in [ProfileEditorUiState.errors]. */
    fun save(onSaved: () -> Unit) {
        val current = state.value
        if (current.loading || current.saving || current.notFound) return
        val parsed = parse(current.form)
        if (parsed.localErrors.isNotEmpty()) {
            // Something could not even be read: show it together with whatever else the rules reject, without saving.
            val others = Validation.profile(parsed.input, today()).filter { it.field !in parsed.localErrors }
            state.update { it.copy(errors = others.associate { i -> i.field to i.code } + parsed.localErrors) }
            return
        }
        state.update { it.copy(saving = true, errors = emptyMap(), saveFailed = false) }
        viewModelScope.launch {
            val result = when (current.mode) {
                EditorMode.Create -> profiles.create(parsed.input)
                EditorMode.Edit -> profiles.update(profileId!!, parsed.input)
                EditorMode.Personalize -> profiles.personalize(profileId!!, parsed.input, parsed.weightKg)
            }
            when (result) {
                is DataResult.Success -> {
                    state.update { it.copy(saving = false) }
                    onSaved()
                }
                is DataResult.Invalid ->
                    state.update { it.copy(saving = false, errors = result.issues.associate { i -> i.field to i.code }) }
                DataResult.NotFound -> state.update { it.copy(saving = false, notFound = true) }
                is DataResult.StorageError -> state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    fun requestDelete() {
        if (state.value.canDelete && !state.value.deleteLockedByBelt) state.update { it.copy(confirmingDelete = true) }
    }

    fun cancelDelete() = state.update { it.copy(confirmingDelete = false) }

    /** Deletes the profile with its weights and workouts; calls [onDeleted] when done. */
    fun confirmDelete(onDeleted: () -> Unit) {
        val id = profileId ?: return
        val current = state.value
        if (!current.canDelete || current.deleteLockedByBelt || current.saving) return
        state.update { it.copy(confirmingDelete = false, saving = true) }
        viewModelScope.launch {
            when (profiles.delete(id)) {
                is DataResult.Success, DataResult.NotFound -> {
                    state.update { it.copy(saving = false) }
                    onDeleted()
                }
                else -> state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    private class Parsed(val input: ProfileInput, val weightKg: Double?, val localErrors: Map<InputField, ValidationCode>)

    /** Reads the form. What cannot be read becomes an error and a harmless stand-in so the rest can still be checked. */
    private fun parse(form: ProfileForm): Parsed {
        val errors = mutableMapOf<InputField, ValidationCode>()
        fun requiredInt(text: String, field: InputField, fallback: Int): Int =
            text.trim().toIntOrNull() ?: fallback.also { errors[field] = if (text.isBlank()) ValidationCode.Blank else ValidationCode.OutOfRange }

        fun optionalInt(text: String, field: InputField): Int? =
            if (text.isBlank()) null else text.trim().toIntOrNull().also { if (it == null) errors[field] = ValidationCode.OutOfRange }

        val height = requiredInt(form.height, InputField.Height, 170)
        val birth = form.birthDate ?: today().minusYears(30).also { errors[InputField.BirthDate] = ValidationCode.Blank }
        val resting = optionalInt(form.restingHeartRate, InputField.RestingHeartRate)
        val max = optionalInt(form.maxHeartRate, InputField.MaxHeartRate)
        var weight: Double? = null
        if (state.value.mode == EditorMode.Personalize && form.weight.isNotBlank()) {
            weight = Decimals.parse(form.weight)
            if (weight == null) errors[InputField.Weight] = ValidationCode.OutOfRange
        }
        return Parsed(
            ProfileInput(form.name, form.sex, height, birth, resting, max, form.fitnessLevel, form.goal),
            weight,
            errors,
        )
    }
}
