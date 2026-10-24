package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.res.ResourcesCompat
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.fakes.profileInput
import com.treadz.console.nav.Screen
import com.treadz.console.ui.Avatars
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.AvatarRules
import com.treadz.console.users.ProfileEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The avatar of a user: the catalogue, the editor's choice and what is saved. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class AvatarsTest {

    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private val data get() = app.userData
    private val profiles get() = data.profiles

    private fun editor(profileId: Long?) = ProfileEditorViewModel(profileId, profiles, app.controller, app::today)

    private fun saved(id: Long) = runBlocking { profiles.profile(id).first()!! }

    // ---- Catalogue ----

    @Test fun everyAvatarHasAValidUniqueIdAndItsDrawableAndDescriptions() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(Avatars.all.isNotEmpty())
        assertEquals("ids are unique", Avatars.all.size, Avatars.all.map { it.id }.toSet().size)
        for (avatar in Avatars.all) {
            assertEquals("${avatar.id} is a valid id", avatar.id, AvatarRules.normalize(avatar.id))
            assertNotNull("${avatar.id} has its drawable", ResourcesCompat.getDrawable(context.resources, avatar.drawable, null))
            assertTrue("${avatar.id} has a description", context.getString(avatar.description).isNotBlank())
        }
    }

    @Test fun anIdTheCatalogueDoesNotKnowFindsNothing() {
        assertNull(Avatars.find(null))
        assertNull(Avatars.find("avatar_from_a_future_version"))
        assertEquals("man", Avatars.find("man")!!.id)
    }

    // ---- The editor ----

    @Test fun theEditorShowsTheAvatarOfTheProfileAndSavingKeepsOrChangesIt() {
        val ana = data.createProfile("Ana")
        runBlocking { profiles.update(ana.id, profileInput("Ana").copy(avatarId = "woman")) }
        val vm = editor(ana.id)
        assertEquals("woman", vm.uiState.value.form.avatarId)

        vm.save { }
        assertEquals("woman", saved(ana.id).avatarId)

        vm.setAvatar("man")
        vm.save { }
        assertEquals("man", saved(ana.id).avatarId)

        vm.setAvatar(null)
        vm.save { }
        assertNull(saved(ana.id).avatarId)
    }

    @Test fun anAvatarPickedWhileCreatingIsSavedWithTheNewProfile() {
        val vm = editor(null)
        vm.setName("Carla")
        vm.setHeight("168")
        vm.setBirthDate(app.date.minusYears(30))
        vm.setAvatar("woman")
        vm.save { }
        assertEquals("woman", runBlocking { profiles.profiles.first().first { it.name == "Carla" } }.avatarId)
    }

    @Test fun anIdFromAFutureVersionSurvivesSavingTheFormUntouched() {
        val ana = data.createProfile("Ana")
        runBlocking { profiles.update(ana.id, profileInput("Ana").copy(avatarId = "avatar_from_a_future_version")) }
        val vm = editor(ana.id)
        vm.save { }
        assertEquals("avatar_from_a_future_version", saved(ana.id).avatarId)
    }

    // ---- The screens ----

    private fun show(vararg then: Screen) {
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        // Scrolls only when the node is inside a scrollable container (the save bar is not).
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Test fun theChoiceStartsWithNoAvatarAndPickingOneSelectsItAndSavingStoresIt() {
        val ana = data.createProfile("Ana")
        show(Screen.ProfileEditor(ana.id, 1))
        rule.onNodeWithTag("avatar_choice").assertIsDisplayed()
        rule.onNodeWithTag("avatar_none").assertIsSelected()
        rule.onNodeWithTag("avatar_man").assertIsNotSelected()

        click("avatar_woman")
        rule.onNodeWithTag("avatar_woman").assertIsSelected()
        rule.onNodeWithTag("avatar_none").assertIsNotSelected()
        click("save_button")
        assertEquals("woman", saved(ana.id).avatarId)
    }

    @Test fun pickingNoAvatarRemovesIt() {
        val ana = data.createProfile("Ana")
        runBlocking { profiles.update(ana.id, profileInput("Ana").copy(avatarId = "man")) }
        show(Screen.ProfileEditor(ana.id, 1))
        rule.onNodeWithTag("avatar_man").assertIsSelected()
        click("avatar_none")
        click("save_button")
        assertNull(saved(ana.id).avatarId)
    }

    @Test fun personalisingTheGenericProfileCanPickAnAvatar() {
        show()
        click("personalize_button")
        rule.onNodeWithTag("avatar_choice").assertIsDisplayed()
        click("avatar_man")
        rule.onNodeWithTag("field_name").performTextInput("Beto")
        rule.onNodeWithTag("field_height").performTextInput("180")
        rule.onNodeWithTag("avatar_man").assertIsSelected()
    }

    @Test fun theUserCardOfAPickedAvatarStillShowsTheUser() {
        val ana = data.createProfile("Ana")
        runBlocking { profiles.update(ana.id, profileInput("Ana").copy(avatarId = "woman")) }
        show(Screen.UserSelection)
        rule.onNodeWithTag("user_card_${ana.id}").assertIsDisplayed()
    }
}
