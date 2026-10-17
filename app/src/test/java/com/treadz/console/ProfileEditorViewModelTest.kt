package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.ValidationCode
import com.treadz.console.users.EditorMode
import com.treadz.console.users.ProfileEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileEditorViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val app = FakeApp()
    private val profiles get() = app.userData.profiles

    private fun editor(profileId: Long?) = ProfileEditorViewModel(profileId, profiles, app.controller, app::today)

    private fun ProfileEditorViewModel.fillValid(name: String = "Carla") {
        setName(name)
        setHeight("168")
        setBirthDate(app.date.minusYears(30))
    }

    private fun genericId() = runBlocking { profiles.profiles.first().first { it.isGeneric }.id }

    @Test fun createRejectsEmptyFormWithAnErrorPerField() {
        val vm = editor(null)
        var saved = false
        vm.save { saved = true }
        assertFalse(saved)
        val errors = vm.uiState.value.errors
        assertEquals(ValidationCode.Blank, errors[InputField.Name])
        assertEquals(ValidationCode.Blank, errors[InputField.Height])
        assertEquals(ValidationCode.Blank, errors[InputField.BirthDate])
        assertEquals(1, runBlocking { profiles.profiles.first().size }) // nothing was created
    }

    @Test fun validationErrorsFromTheDataLayerLandOnTheirField() {
        val vm = editor(null)
        vm.fillValid()
        vm.setHeight("50")
        vm.setRestingHeartRate("150")
        vm.setMaxHeartRate("140")
        vm.save { }
        val errors = vm.uiState.value.errors
        assertEquals(ValidationCode.OutOfRange, errors[InputField.Height])
        assertEquals(ValidationCode.OutOfRange, errors[InputField.RestingHeartRate])
        assertNull(errors[InputField.Name])
    }

    @Test fun restingHeartRateMustBeBelowTheMaximum() {
        val vm = editor(null)
        vm.fillValid()
        vm.setRestingHeartRate("110")
        vm.setMaxHeartRate("105")
        vm.save { }
        assertEquals(ValidationCode.RestingNotBelowMax, vm.uiState.value.errors[InputField.RestingHeartRate])
    }

    @Test fun textThatIsNotANumberIsAnErrorTogetherWithTheOtherProblems() {
        val vm = editor(null)
        vm.setHeight("tall")
        vm.setMaxHeartRate("abc")
        vm.save { }
        val errors = vm.uiState.value.errors
        assertEquals(ValidationCode.OutOfRange, errors[InputField.Height])
        assertEquals(ValidationCode.OutOfRange, errors[InputField.MaxHeartRate])
        assertEquals(ValidationCode.Blank, errors[InputField.Name])
        assertEquals(ValidationCode.Blank, errors[InputField.BirthDate])
    }

    @Test fun duplicateNameIsReportedOnTheNameField() {
        app.userData.createProfile("Ana")
        val vm = editor(null)
        vm.fillValid(" ana ")
        vm.save { }
        assertEquals(ValidationCode.NameTaken, vm.uiState.value.errors[InputField.Name])
    }

    @Test fun editingAFieldClearsItsError() {
        val vm = editor(null)
        vm.save { }
        assertTrue(vm.uiState.value.errors.containsKey(InputField.Name))
        vm.setName("D")
        assertFalse(vm.uiState.value.errors.containsKey(InputField.Name))
        assertTrue(vm.uiState.value.errors.containsKey(InputField.Height))
    }

    @Test fun createSavesTheProfileAndKeepsTheActiveUser() = runBlocking {
        val activeBefore = profiles.activeUserId()
        val vm = editor(null)
        vm.fillValid()
        vm.setSex(Sex.Female)
        vm.setFitnessLevel(FitnessLevel.Advanced)
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
        val carla = profiles.profiles.first().first { it.name == "Carla" }
        assertEquals(Sex.Female, carla.sex)
        assertEquals(168, carla.heightCm)
        assertEquals(FitnessLevel.Advanced, carla.fitnessLevel)
        assertEquals(activeBefore, profiles.activeUserId())
    }

    @Test fun editLoadsTheProfileAndUpdatesIt() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        val vm = editor(ana.id)
        val state = vm.uiState.value
        assertEquals(EditorMode.Edit, state.mode)
        assertEquals("Ana", state.form.name)
        assertEquals("170", state.form.height)
        assertEquals("60", state.form.restingHeartRate)
        vm.setName("Zoë")
        vm.setMaxHeartRate("185")
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
        val updated = profiles.profile(ana.id).first()!!
        assertEquals("Zoë", updated.name)
        assertEquals(185, updated.maxHeartRateBpm)
    }

    @Test fun estimatedMaxHeartRateFollowsTheBirthDateWhileTheFieldIsEmpty() {
        val vm = editor(null)
        assertNull(vm.uiState.value.estimatedMaxHeartRate)
        vm.setBirthDate(app.date.minusYears(40))
        assertEquals(180, vm.uiState.value.estimatedMaxHeartRate) // 208 - 0.7 * 40
        vm.setBirthDate(app.date.minusYears(20))
        assertEquals(194, vm.uiState.value.estimatedMaxHeartRate)
    }

    @Test fun editingTheGenericProfilePersonalizesItWithTheRealWeight() = runBlocking {
        val id = genericId()
        val vm = editor(id)
        assertEquals(EditorMode.Personalize, vm.uiState.value.mode)
        // The made-up data is not prefilled: the real values must be entered.
        assertEquals("", vm.uiState.value.form.height)
        assertNull(vm.uiState.value.form.birthDate)
        vm.save { }
        assertEquals(ValidationCode.Blank, vm.uiState.value.errors[InputField.Name])

        vm.fillValid("Fede")
        vm.setWeight("72,5")
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
        val profile = profiles.profile(id).first()!!
        assertEquals("Fede", profile.name)
        assertFalse(profile.isGeneric)
        val weights = app.userData.weights.entries(id).first()
        assertEquals(listOf(72.5), weights.map { it.weightKg })
        assertFalse(weights.single().isPlaceholder)
    }

    @Test fun personalizingWithoutWeightKeepsThePlaceholder() = runBlocking {
        val id = genericId()
        val vm = editor(id)
        vm.fillValid("Fede")
        vm.save { }
        val weights = app.userData.weights.entries(id).first()
        assertTrue(weights.single().isPlaceholder)
    }

    @Test fun badWeightWhilePersonalizingIsAnErrorOnTheWeightField() {
        val vm = editor(genericId())
        vm.fillValid("Fede")
        vm.setWeight("5")
        vm.save { }
        assertEquals(ValidationCode.OutOfRange, vm.uiState.value.errors[InputField.Weight])
        vm.setWeight("lots")
        vm.save { }
        assertEquals(ValidationCode.OutOfRange, vm.uiState.value.errors[InputField.Weight])
        assertEquals(1, runBlocking { profiles.profiles.first().size })
        assertTrue(runBlocking { profiles.profiles.first().single().isGeneric })
    }

    @Test fun deleteNeedsConfirmationAndRemovesTheProfile() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        val vm = editor(ana.id)
        assertTrue(vm.uiState.value.canDelete)
        vm.requestDelete()
        assertTrue(vm.uiState.value.confirmingDelete)
        vm.cancelDelete()
        assertEquals(2, profiles.profiles.first().size)
        vm.requestDelete()
        var deleted = false
        vm.confirmDelete { deleted = true }
        assertTrue(deleted)
        assertEquals(listOf(""), profiles.profiles.first().map { it.name })
    }

    @Test fun theOnlyGenericProfileCannotBeDeleted() {
        assertFalse(editor(genericId()).uiState.value.canDelete)
    }

    @Test fun theGenericProfileCanBeDeletedWhenThereAreOthers() {
        app.userData.createProfile("Ana")
        assertTrue(editor(genericId()).uiState.value.canDelete)
    }

    @Test fun theActiveUserCannotBeDeletedWhileTheBeltRuns() = runBlocking {
        val ana = app.userData.createProfile("Ana")
        profiles.setActiveUser(ana.id)
        val vm = editor(ana.id)
        assertFalse(vm.uiState.value.deleteLockedByBelt)
        app.controller.showState { it.copy(status = BeltStatus.Running) }
        assertTrue(vm.uiState.value.deleteLockedByBelt)
        vm.requestDelete()
        assertFalse(vm.uiState.value.confirmingDelete)
        // Another user can still be deleted while the belt runs.
        val beto = app.userData.createProfile("Beto")
        val other = editor(beto.id)
        assertFalse(other.uiState.value.deleteLockedByBelt)
    }

    @Test fun missingProfileIsReported() {
        val vm = editor(9_999)
        assertTrue(vm.uiState.value.notFound)
    }

    @Test fun deleteWarningMentionsEveryDeviceOnlyWhileSyncIsLinked() {
        val linked = kotlinx.coroutines.flow.MutableStateFlow(false)
        val id = app.userData.createProfile("Carla").id
        val vm = ProfileEditorViewModel(id, profiles, app.controller, app::today, syncLinked = linked)
        assertFalse(vm.uiState.value.syncLinked)
        linked.value = true
        assertTrue(vm.uiState.value.syncLinked)
        linked.value = false
        assertFalse(vm.uiState.value.syncLinked)
    }

    // ---- Fitness block (milestone 5c) ----

    private fun editorWithFitness(profileId: Long?) =
        ProfileEditorViewModel(profileId, profiles, app.controller, app::today, fitnessTests = app.userData.fitnessTests)

    private fun betoWithANewMaximalTest(): Long {
        val (_, beto) = runBlocking { com.treadz.console.userdata.memory.DemoData.populate(app.userData, app.date) }
        val started = app.date.atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking {
            app.userData.fitnessTests.save(
                beto.id,
                com.treadz.console.userdata.memory.DemoPrograms.maxTest(
                    id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b41", startedAtMillis = started, peakBpm = 193, ageYears = 42,
                    sex = com.treadz.console.analysis.fitness.Sex.Male, weightKg = 90.0,
                ),
            )
        }
        return beto.id
    }

    @Test fun anExistingProfileShowsItsFitnessFigures() {
        val (ana, _) = runBlocking { com.treadz.console.userdata.memory.DemoData.populate(app.userData, app.date) }
        val fitness = checkNotNull(editorWithFitness(ana.id).uiState.value.fitness)
        assertEquals(183, fitness.heartRateMax!!.bpm)
        assertEquals(com.treadz.console.programs.fitness.HeartRateMaxOrigin.Measured, fitness.heartRateMax!!.origin)
        assertTrue(fitness.profile!!.vo2max != null)
    }

    @Test fun theFitnessBlockIsNotLoadedWithoutTheRepositoryOrForANewProfile() {
        assertNull(editor(null).uiState.value.fitness)
        val id = app.userData.createProfile("Carla").id
        assertNull(editor(id).uiState.value.fitness)
    }

    @Test fun usingTheMeasuredMaximumHeartRateClearsTheFieldToo() {
        val betoId = betoWithANewMaximalTest()
        val vm = editorWithFitness(betoId)
        assertEquals("184", vm.uiState.value.form.maxHeartRate)
        assertTrue(vm.uiState.value.fitness!!.heartRateMax!!.askUser)

        vm.useMeasuredHeartRateMax()
        assertEquals("", vm.uiState.value.form.maxHeartRate)
        assertEquals(193, vm.uiState.value.fitness!!.heartRateMax!!.bpm)
        assertFalse(vm.uiState.value.fitness!!.heartRateMax!!.askUser)
        // Saving does not bring the old value back.
        vm.save { }
        assertNull(runBlocking { profiles.profile(betoId).first() }!!.maxHeartRateBpm)
    }

    @Test fun keepingTheManualMaximumHeartRateLeavesTheFieldAlone() {
        val betoId = betoWithANewMaximalTest()
        val vm = editorWithFitness(betoId)
        vm.keepManualHeartRateMax()
        assertEquals("184", vm.uiState.value.form.maxHeartRate)
        assertFalse(vm.uiState.value.fitness!!.heartRateMax!!.askUser)
        assertEquals(184, vm.uiState.value.fitness!!.heartRateMax!!.bpm)
    }
}
