package com.treadz.console.fakes

import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** Helpers to set up profiles in tests (the data layer starts with the generic profile, active). */
fun profileInput(
    name: String,
    today: LocalDate = FakeApp.TODAY,
    age: Int = 35,
    sex: Sex = Sex.Female,
) = ProfileInput(
    name = name,
    sex = sex,
    heightCm = 170,
    birthDate = today.minusYears(age.toLong()).minusDays(10),
    restingHeartRateBpm = 60,
    fitnessLevel = FitnessLevel.Intermediate,
    goal = Goal.MaintainWeight,
)

fun InMemoryUserData.createProfile(name: String, age: Int = 35): UserProfile =
    runBlocking { (profiles.create(profileInput(name, age = age)) as DataResult.Success).value }

/** Gives [user] the avatar [avatarId] (`null` for none), keeping the rest of the profile as it is. */
fun InMemoryUserData.setAvatar(user: UserProfile, avatarId: String?) {
    runBlocking {
        profiles.update(
            user.id,
            ProfileInput(
                user.name, user.sex, user.heightCm, user.birthDate, user.restingHeartRateBpm, user.maxHeartRateBpm,
                user.fitnessLevel, user.goal, avatarId,
            ),
        )
    }
}
