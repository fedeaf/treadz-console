package com.treadz.console.analysis.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Vo2MaxNormsTest {

    @Test
    fun `classification boundaries belong to the higher category`() {
        // Men 40-49: Superior 52.5, Excellent 46.4, Good 42.4, Fair 38.5.
        assertEquals(Vo2MaxCategory.Superior, Vo2MaxNorms.classify(52.5, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Excellent, Vo2MaxNorms.classify(52.4, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Excellent, Vo2MaxNorms.classify(46.4, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Good, Vo2MaxNorms.classify(46.3, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Good, Vo2MaxNorms.classify(42.4, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Good, Vo2MaxNorms.classify(42.39, 45, Sex.Male).category, "compared on one decimal: 42.39 is 42.4")
        assertEquals(Vo2MaxCategory.Fair, Vo2MaxNorms.classify(42.3, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Fair, Vo2MaxNorms.classify(38.5, 45, Sex.Male).category)
        assertEquals(Vo2MaxCategory.Poor, Vo2MaxNorms.classify(38.4, 45, Sex.Male).category)
        // Women 20-29: Fair from 36.1.
        assertEquals(Vo2MaxCategory.Fair, Vo2MaxNorms.classify(36.1, 25, Sex.Female).category)
        assertEquals(Vo2MaxCategory.Poor, Vo2MaxNorms.classify(36.0, 25, Sex.Female).category)
        // Women 70-79: Superior from 36.7.
        assertEquals(Vo2MaxCategory.Superior, Vo2MaxNorms.classify(36.7, 75, Sex.Female).category)
    }

    @Test
    fun `age groups and ages outside the norms`() {
        assertEquals(Vo2MaxClassification(Vo2MaxCategory.Good, "20-29", false), Vo2MaxNorms.classify(46.0, 29, Sex.Male))
        assertEquals(Vo2MaxClassification(Vo2MaxCategory.Good, "30-39", false), Vo2MaxNorms.classify(46.0, 30, Sex.Male))
        assertEquals(Vo2MaxClassification(Vo2MaxCategory.Good, "20-29", true), Vo2MaxNorms.classify(46.0, 16, Sex.Male))
        assertEquals(Vo2MaxClassification(Vo2MaxCategory.Superior, "70-79", true), Vo2MaxNorms.classify(46.0, 85, Sex.Male))
        assertEquals(mapOf(Vo2MaxCategory.Superior to 47.4, Vo2MaxCategory.Excellent to 42.4, Vo2MaxCategory.Good to 37.8, Vo2MaxCategory.Fair to 34.4), Vo2MaxNorms.bounds(35, Sex.Female))
    }
}
