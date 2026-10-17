package com.treadz.console.analysis.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NonExerciseVo2maxTest {

    @Test
    fun `Jackson 1990 BMI equation with the published coefficients`() {
        // 56.363 + 1.921*4 - 0.381*40 - 0.754*24.6914 + 10.987 = 41.177
        assertEquals(41.177, NonExerciseVo2max.jacksonVo2max(4, 40, 80.0 / (1.8 * 1.8), Sex.Male), 0.001)
        // 56.363 + 1.921*1 - 0.381*30 - 0.754*22.0386 = 30.237
        assertEquals(30.237, NonExerciseVo2max.jacksonVo2max(1, 30, 60.0 / (1.65 * 1.65), Sex.Female), 0.001)
        assertEquals(10.987, NonExerciseVo2max.jacksonVo2max(3, 35, 25.0, Sex.Male) - NonExerciseVo2max.jacksonVo2max(3, 35, 25.0, Sex.Female), 1e-9)
    }

    @Test
    fun `self-rated level maps to the activity rating, unknown is conservative`() {
        assertEquals(1, NonExerciseVo2max.physicalActivityRating(SelfRatedFitness.Beginner))
        assertEquals(4, NonExerciseVo2max.physicalActivityRating(SelfRatedFitness.Intermediate))
        assertEquals(6, NonExerciseVo2max.physicalActivityRating(SelfRatedFitness.Advanced))
        assertEquals(1, NonExerciseVo2max.physicalActivityRating(null))
    }
}
