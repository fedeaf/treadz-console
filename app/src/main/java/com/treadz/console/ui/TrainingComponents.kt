package com.treadz.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.plan.PlanWarning

/** Square − / + button of the steppers. */
@Composable
fun StepSymbolButton(symbol: String, description: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(56.dp).semantics { contentDescription = description }.testTag(tag),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A number the user can type, with − / + buttons around it. [unit] is shown after the number. */
@Composable
fun StepperField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    onStep: (Int) -> Unit,
    unit: String?,
    tag: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    lessDescription: String = stringResource(R.string.stepper_less, label),
    moreDescription: String = stringResource(R.string.stepper_more, label),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StepSymbolButton("−", lessDescription, { onStep(-1) }, "${tag}_minus")
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                isError = error != null,
                suffix = if (unit != null) ({ Text(unit) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(tag),
            )
            StepSymbolButton("+", moreDescription, { onStep(+1) }, "${tag}_plus")
        }
        val text = error ?: supporting
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (error != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A value changed only with − / + (the duration, the intensity adjustment). */
@Composable
fun ValueStepper(
    label: String,
    value: String,
    onStep: (Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StepSymbolButton("−", stringResource(R.string.stepper_less, label), { onStep(-1) }, "${tag}_minus", minusEnabled)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(value, Modifier.testTag("${tag}_value"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            StepSymbolButton("+", stringResource(R.string.stepper_more, label), { onStep(+1) }, "${tag}_plus", plusEnabled)
        }
        if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Colour of a phase: the colour only helps, the name is always written. */
@Composable
fun BlockPhase.color(): Color = when (this) {
    BlockPhase.WarmUp -> MaterialTheme.extra.warning
    BlockPhase.Work -> MaterialTheme.colorScheme.primary
    BlockPhase.Recovery -> MaterialTheme.colorScheme.secondary
    BlockPhase.CoolDown -> MaterialTheme.colorScheme.onSurfaceVariant
    BlockPhase.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun PhaseTag(phase: BlockPhase, modifier: Modifier = Modifier) = Tag(phase.label(), phase.color(), modifier)

/** The warnings of a plan, each one translated by its code. At most [limit] are listed. */
@Composable
fun PlanWarnings(warnings: List<PlanWarning>, modifier: Modifier = Modifier, limit: Int = 4) {
    if (warnings.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("plan_warnings"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        warnings.take(limit).forEach { warning ->
            Text("• " + warning.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
        }
        if (warnings.size > limit) {
            Text(stringResource(R.string.warnings_more, warnings.size - limit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
        }
    }
}

/** "Your test measured X bpm; you set Y bpm. Use the measured value?" Shown only when the decision asks. */
@Composable
fun HeartRateMaxQuestion(
    decision: HeartRateMaxDecision?,
    onUseMeasured: () -> Unit,
    onKeepMine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measured = decision?.measured
    if (decision == null || !decision.askUser || measured == null) return
    Panel(modifier.fillMaxWidth().testTag("hrmax_question"), borderColor = MaterialTheme.extra.warning) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.hrmax_question_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
            Text(stringResource(R.string.hrmax_question_body, measured.measuredBpm, decision.bpm), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RowButton(stringResource(R.string.hrmax_use_measured, measured.measuredBpm), onUseMeasured, Modifier.testTag("hrmax_use_measured"))
                RowButton(stringResource(R.string.hrmax_keep_mine, decision.bpm), onKeepMine, Modifier.testTag("hrmax_keep_mine"))
            }
        }
    }
}

/** "185 bpm (measured by a test)". */
@Composable
fun hrMaxValue(decision: HeartRateMaxDecision): String =
    stringResource(R.string.hrmax_value, decision.bpm, decision.origin.label())

/** A label with its value, for the lists of figures. */
@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier, tag: String? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            // On a phone the label keeps room next to a long value (it wraps in two lines instead).
            Modifier.widthIn(max = if (LocalLayout.current.wide) 260.dp else 200.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = valueColor, textAlign = TextAlign.End,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

