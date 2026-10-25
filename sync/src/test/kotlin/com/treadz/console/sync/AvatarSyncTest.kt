package com.treadz.console.sync

import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The avatar of a user through a fake GitHub: it is part of the profile file and the other device shows the same one. */
@RunWith(RobolectricTestRunner::class)
class AvatarSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long) = Device(name, github, seed, seedGenericProfile = false)

    private fun profileFile() =
        repo.files().keys.first { it.startsWith("users/ana-") && it.endsWith("/profile.json") }.let { repo.text(it)!! }

    private fun Device.profile(name: String = "Ana") = profiles().first { it.name == name }

    private fun Device.setAvatar(user: UserProfile, avatarId: String?) {
        val input = ProfileInput(
            user.name, user.sex, user.heightCm, user.birthDate, user.restingHeartRateBpm, user.maxHeartRateBpm,
            user.fitnessLevel, user.goal, avatarId,
        )
        assertTrue(runBlocking { data.profiles.update(user.id, input) } is DataResult.Success)
    }

    @Test fun `the avatar of a user is in the profile file and the other device shows the same one`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        assertFalse(profileFile().contains("avatar"))

        a.at(T0 + 1_000)
        a.setAvatar(ana, "woman")
        a.sync()
        assertTrue(profileFile(), profileFile().contains("\"avatar\": \"woman\""))
        assertEquals("Ana · profile", repo.log().last())

        val b = device("phone", 2)
        b.sync()
        assertEquals("woman", b.profile().avatarId)

        // the phone picks another one; the tablet follows
        b.at(T0 + 2_000)
        b.setAvatar(b.profile(), "man")
        b.sync()
        a.sync()
        assertEquals("man", a.profile().avatarId)

        // the tablet removes it; the phone follows and the file has no avatar again
        a.at(T0 + 3_000)
        a.setAvatar(a.profile(), null)
        a.sync()
        assertFalse(profileFile().contains("avatar"))
        b.sync()
        assertNull(b.profile().avatarId)
    }

    @Test fun `an avatar id that this version does not know travels through unchanged`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        a.at(T0 + 1_000)
        a.setAvatar(ana, "avatar_from_a_later_version")
        a.sync()
        val b = device("phone", 2)
        b.sync()
        assertEquals("avatar_from_a_later_version", b.profile().avatarId)
    }
}
