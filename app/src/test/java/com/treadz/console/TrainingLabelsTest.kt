package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.platform.LocalContext
import com.treadz.console.programs.engine.InterruptionReason
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessInstruction
import com.treadz.console.programs.fitness.FitnessRefusal
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.SteadyStateStatus
import com.treadz.console.programs.fitness.Vo2MaxCategory
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.personalised.CouchToFiveKDecision
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.GeneratorNotice
import com.treadz.console.programs.personalised.GeneratorNoticeCode
import com.treadz.console.programs.personalised.ProgramGenerators
import com.treadz.console.programs.personalised.SessionOutcome
import com.treadz.console.training.GeneratorProblem
import com.treadz.console.ui.adjustedValueText
import com.treadz.console.ui.generatorDescriptionRes
import com.treadz.console.ui.generatorNameRes
import com.treadz.console.ui.generatorTagText
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.PlanWarning
import com.treadz.console.programs.plan.WarningCode
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.training.EditorField
import com.treadz.console.training.EditorProblem
import com.treadz.console.training.ParameterProblem
import com.treadz.console.training.StartBlocker
import com.treadz.console.training.StartOutcome
import com.treadz.console.training.TestBlocker
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.ui.label
import com.treadz.console.ui.programDisplayName
import com.treadz.console.ui.standardDescriptionRes
import com.treadz.console.ui.standardNameRes
import com.treadz.console.ui.summary
import com.treadz.console.ui.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every enum the contracts expose to the UI has its own text, in English and in Spanish; the catalogue's resource keys
 * match the ones the app defines; and both languages define the same strings.
 */
@RunWith(RobolectricTestRunner::class)
class TrainingLabelsTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Every text of every enum, grouped by enum. */
    private fun collect(): Map<String, List<String>> {
        var texts: Map<String, List<String>> = emptyMap()
        rule.setContent { texts = allTexts() }
        rule.waitForIdle()
        return texts
    }

    @Composable
    private fun allTexts(): Map<String, List<String>> = mapOf(
        "BlockPhase" to BlockPhase.entries.map { it.label() },
        "ProgramKind" to ProgramKind.entries.map { it.label() },
        "WarningCode" to WarningCode.entries.map { PlanWarning(it, 2, 30.0, 20.0, "log").text() },
        "Refusal" to Refusal.entries.map { it.text() },
        "InterruptionReason" to InterruptionReason.entries.map { it.text() },
        "RunOutcome" to RunOutcome.entries.map { it.text() },
        "RunState" to RunState.entries.map { it.label() },
        "FitnessTestType" to FitnessTestType.entries.map { it.label() },
        "FitnessTestTypeSummary" to FitnessTestType.entries.map { it.summary() },
        "FitnessTestPhase" to FitnessTestPhase.entries.map { it.label() },
        "FitnessInstruction" to FitnessInstruction.entries.map { it.text() },
        "SteadyStateStatus" to SteadyStateStatus.entries.map { it.label() },
        "FitnessRefusal" to FitnessRefusal.entries.map { it.text() },
        "FitnessTestFlag" to FitnessTestFlag.entries.map { it.text() },
        "FitnessEndReason" to FitnessEndReason.entries.map { it.text() },
        "Vo2MaxCategory" to Vo2MaxCategory.entries.map { it.label() },
        "EffortAssessment" to EffortAssessment.entries.map { it.label() },
        "HeartRateMaxOrigin" to HeartRateMaxOrigin.entries.map { it.label() },
        "StartBlocker" to StartBlocker.entries.map { it.text() },
        "TestBlocker" to TestBlocker.entries.map { it.text() },
        "ParameterProblem" to ParameterProblem.entries.map { it.text(IntensityParameter.EasySpeed, 0.5, 20.0, 18.0) },
        "ParameterProblemIncline" to ParameterProblem.entries.map { it.text(IntensityParameter.MaxIncline, 0.5, 20.0, 18.0) },
        "EditorProblem" to EditorProblem.entries.flatMap { p -> EditorField.entries.map { p.text(it) } },
        "StartOutcome" to listOf(
            StartOutcome.Busy.text(),
            StartOutcome.ProgramRefused(Refusal.BeltBusy, "x").text(),
            StartOutcome.TestRefused(FitnessRefusal.NoHeartRate, "x").text(),
            StartOutcome.CommandFailed(CommandResult.NotConnected).text(),
            StartOutcome.CommandFailed(CommandResult.Rejected(FailureCode.NotStarted, "x")).text(),
        ),
        "ExerciseMode" to ExerciseMode.entries.map { it.label() },
        "HeartRateAdjust" to HeartRateAdjust.entries.map { it.label() },
        "GeneratorProblem" to GeneratorProblem.entries.map { it.text() },
        "CouchToFiveKDecision" to CouchToFiveKDecision.entries.map { it.text() },
        "SessionOutcome" to SessionOutcome.entries.map { it.label() },
        "GeneratorNotice" to GeneratorNoticeCode.entries.flatMap { code ->
            listOf(
                GeneratorNotice(code, null, 12.0).text(null, null),
                GeneratorNotice(code, "1.0", 7.5).text(null, null),
            )
        },
        "GeneratorNames" to ProgramGenerators.all.flatMap {
            listOf(
                androidx.compose.ui.res.stringResource(generatorNameRes(it.descriptor.id)),
                androidx.compose.ui.res.stringResource(generatorDescriptionRes(it.descriptor.id)),
            )
        },
        "GeneratorTags" to listOf(generatorTagText("couch-to-5k", 7), generatorTagText("zone2", null), adjustedValueText(HeartRateAdjust.Speed, 8.5), adjustedValueText(HeartRateAdjust.Incline, 6.0)),
        "StandardPrograms" to StandardPrograms.all.flatMap { listOf(programDisplayName(it.id, it.program.name), androidx.compose.ui.res.stringResource(standardDescriptionRes(it.key))) },
    )

    private fun check(texts: Map<String, List<String>>) {
        assertTrue(texts.isNotEmpty())
        for ((name, list) in texts) {
            assertTrue("$name has texts", list.isNotEmpty())
            list.forEachIndexed { i, text ->
                assertTrue("$name #$i is empty", text.isNotBlank())
                assertFalse("$name #$i has an unresolved placeholder: $text", text.contains("%1\$") || text.contains("%s") || text.contains("%d"))
            }
        }
        // Each value of an enum reads differently (a missing branch would repeat a text).
        for (name in listOf("BlockPhase", "ProgramKind", "RunState", "FitnessTestType", "FitnessTestPhase", "FitnessInstruction", "FitnessEndReason", "FitnessTestFlag", "Vo2MaxCategory", "InterruptionReason", "Refusal", "FitnessRefusal", "SteadyStateStatus", "EffortAssessment", "HeartRateMaxOrigin", "ExerciseMode", "HeartRateAdjust", "GeneratorProblem", "CouchToFiveKDecision", "SessionOutcome")) {
            val list = texts.getValue(name)
            assertEquals("$name has repeated texts", list.size, list.toSet().size)
        }
    }

    @Test @Config(qualifiers = "en") fun everyEnumHasAnEnglishText() = check(collect())

    @Test @Config(qualifiers = "es") fun everyEnumHasASpanishText() {
        var es: Map<String, List<String>> = emptyMap()
        var english: Map<String, List<String>> = emptyMap()
        rule.setContent {
            es = allTexts()
            // The same texts in English, to see that nothing is left untranslated.
            val context = LocalContext.current
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
            androidx.compose.runtime.CompositionLocalProvider(LocalContext provides context.createConfigurationContext(config)) { english = allTexts() }
        }
        rule.waitForIdle()
        check(es)
        for ((name, list) in es) {
            val same = list.indices.filter { list[it] == english.getValue(name)[it] && list[it].length > 12 }
            assertTrue("$name has English texts in the Spanish version: ${same.map { list[it] }}", same.isEmpty())
        }
    }

    @Test fun theCataloguesResourceKeysAreTheOnesTheAppDefines() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for (std in StandardPrograms.all) {
            assertEquals(std.nameResourceKey, context.resources.getResourceEntryName(standardNameRes(std.key)))
            assertEquals(std.descriptionResourceKey, context.resources.getResourceEntryName(standardDescriptionRes(std.key)))
        }
        assertEquals(8, StandardPrograms.all.size)
    }

    @Test fun theGeneratorsResourceKeysAreTheOnesTheAppDefines() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for (generator in ProgramGenerators.all) {
            val d = generator.descriptor
            assertEquals(d.nameResourceKey, context.resources.getResourceEntryName(generatorNameRes(d.id)))
            assertEquals(d.descriptionResourceKey, context.resources.getResourceEntryName(generatorDescriptionRes(d.id)))
        }
        assertEquals(5, ProgramGenerators.all.size)
    }

    @Test fun bothLanguagesDefineTheSameStrings() {
        fun names(path: String) = Regex("""<(?:string|plurals)\s+name="([^"]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toList()
        val en = names("src/main/res/values/strings.xml")
        val es = names("src/main/res/values-es/strings.xml")
        assertEquals("duplicates in English", en.size, en.toSet().size)
        assertEquals("duplicates in Spanish", es.size, es.toSet().size)
        assertEquals("only in English: ${en.toSet() - es.toSet()}", emptySet<String>(), en.toSet() - es.toSet())
        assertEquals("only in Spanish: ${es.toSet() - en.toSet()}", emptySet<String>(), es.toSet() - en.toSet())
    }
}
