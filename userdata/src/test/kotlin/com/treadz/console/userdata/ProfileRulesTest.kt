package com.treadz.console.userdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileRulesTest {

    private val profile = UserProfile(
        id = 1, name = "Ana", sex = Sex.Female, heightCm = 168, birthDate = LocalDate.of(1986, 10, 8),
    )

    @Test fun `age counts whole years and changes on the birthday`() {
        assertEquals(39, profile.ageOn(LocalDate.of(2026, 10, 7)))
        assertEquals(40, profile.ageOn(LocalDate.of(2026, 10, 8)))
        assertEquals(0, profile.ageOn(LocalDate.of(1980, 1, 1))) // never negative
    }

    @Test fun `age of a leap day birthday`() {
        val leap = profile.copy(birthDate = LocalDate.of(2000, 2, 29))
        assertEquals(25, leap.ageOn(LocalDate.of(2026, 2, 28)))
        assertEquals(26, leap.ageOn(LocalDate.of(2026, 3, 1)))
    }

    @Test fun `estimated max heart rate follows Tanaka 208 minus 0,7 times age`() {
        assertEquals(180, HeartRateEstimates.maxFromAge(40))
        assertEquals(194, HeartRateEstimates.maxFromAge(20))
        assertEquals(173, HeartRateEstimates.maxFromAge(50)) // 173.0
        assertEquals(181, HeartRateEstimates.maxFromAge(39)) // 180.7 rounds up
        assertEquals(180, profile.estimatedMaxHeartRateBpm(LocalDate.of(2026, 10, 8)))
    }

    @Test fun `effective max heart rate prefers the value set by the user`() {
        val day = LocalDate.of(2026, 10, 7)
        assertTrue(profile.isMaxHeartRateEstimated)
        assertEquals(181, profile.effectiveMaxHeartRateBpm(day))
        val set = profile.copy(maxHeartRateBpm = 190)
        assertFalse(set.isMaxHeartRateEstimated)
        assertEquals(190, set.effectiveMaxHeartRateBpm(day))
    }

    private fun input(
        name: String = "Ana",
        height: Int = 168,
        birth: LocalDate = LocalDate.of(1986, 10, 8),
        resting: Int? = null,
        max: Int? = null,
    ) = ProfileInput(name, Sex.Female, height, birth, resting, max)

    private fun issues(i: ProfileInput) = Validation.profile(i, TODAY).toSet()

    @Test fun `valid input has no issues, boundaries included`() {
        assertTrue(issues(input()).isEmpty())
        assertTrue(issues(input(height = 100, resting = 25, max = 100 + 1)).isEmpty())
        assertTrue(issues(input(height = 230, resting = 120, max = 230)).isEmpty())
        assertTrue(issues(input(name = "x".repeat(UserDataLimits.NAME_MAX_LENGTH))).isEmpty())
    }

    @Test fun `name rules`() {
        assertEquals(setOf(ValidationIssue(InputField.Name, ValidationCode.Blank)), issues(input(name = "")))
        assertEquals(setOf(ValidationIssue(InputField.Name, ValidationCode.Blank)), issues(input(name = "   ")))
        assertEquals(
            setOf(ValidationIssue(InputField.Name, ValidationCode.TooLong)),
            issues(input(name = "x".repeat(UserDataLimits.NAME_MAX_LENGTH + 1))),
        )
    }

    @Test fun `height range`() {
        assertEquals(setOf(ValidationIssue(InputField.Height, ValidationCode.OutOfRange)), issues(input(height = 99)))
        assertEquals(setOf(ValidationIssue(InputField.Height, ValidationCode.OutOfRange)), issues(input(height = 231)))
    }

    @Test fun `birth date rules`() {
        assertEquals(
            setOf(ValidationIssue(InputField.BirthDate, ValidationCode.InFuture)),
            issues(input(birth = TODAY.plusDays(1))),
        )
        // 9 years old: too young. 10th birthday today: accepted.
        assertEquals(
            setOf(ValidationIssue(InputField.BirthDate, ValidationCode.OutOfRange)),
            issues(input(birth = TODAY.minusYears(10).plusDays(1))),
        )
        assertTrue(issues(input(birth = TODAY.minusYears(10))).isEmpty())
        assertTrue(issues(input(birth = TODAY.minusYears(100))).isEmpty())
        assertEquals(
            setOf(ValidationIssue(InputField.BirthDate, ValidationCode.OutOfRange)),
            issues(input(birth = TODAY.minusYears(101))),
        )
    }

    @Test fun `heart rate rules`() {
        assertEquals(setOf(ValidationIssue(InputField.RestingHeartRate, ValidationCode.OutOfRange)), issues(input(resting = 24)))
        assertEquals(setOf(ValidationIssue(InputField.RestingHeartRate, ValidationCode.OutOfRange)), issues(input(resting = 121)))
        assertEquals(setOf(ValidationIssue(InputField.MaxHeartRate, ValidationCode.OutOfRange)), issues(input(max = 99)))
        assertEquals(setOf(ValidationIssue(InputField.MaxHeartRate, ValidationCode.OutOfRange)), issues(input(max = 231)))
        assertEquals(
            setOf(ValidationIssue(InputField.RestingHeartRate, ValidationCode.RestingNotBelowMax)),
            issues(input(resting = 120, max = 120)),
        )
    }

    @Test fun `weight rules`() {
        assertTrue(Validation.weightEntry(TODAY, 70.5, TODAY).isEmpty())
        assertTrue(Validation.weightEntry(TODAY, 25.0, TODAY).isEmpty())
        assertTrue(Validation.weightEntry(TODAY, 280.0, TODAY).isEmpty())
        assertEquals(listOf(ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)), Validation.weightEntry(TODAY, 24.9, TODAY))
        assertEquals(listOf(ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)), Validation.weightEntry(TODAY, 280.1, TODAY))
        assertEquals(
            listOf(ValidationIssue(InputField.Weight, ValidationCode.OutOfRange)),
            Validation.weightEntry(TODAY, Double.POSITIVE_INFINITY, TODAY),
        )
        assertEquals(
            listOf(ValidationIssue(InputField.WeightDate, ValidationCode.OutOfRange)),
            Validation.weightEntry(LocalDate.of(1899, 12, 31), 70.0, TODAY),
        )
        assertEquals(
            listOf(ValidationIssue(InputField.TargetWeight, ValidationCode.OutOfRange)),
            Validation.targetWeight(0.0),
        )
    }
}
