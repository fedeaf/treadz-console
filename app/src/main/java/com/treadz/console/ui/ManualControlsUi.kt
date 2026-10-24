package com.treadz.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.controls.ControlAction
import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionKind
import com.treadz.console.controls.ControlFailure
import com.treadz.console.controls.ManualControls
import com.treadz.console.treadmill.CommandResult
import kotlin.math.abs
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ---------------------------------------------------------------------------------------------------------------
// Speed and incline

/**
 * Big −/+ button for the speed and incline. Holding it repeats ([onPress] starts the repetition, [onRelease] ends it,
 * also when the touch is cancelled or the button stops being enabled); [onStep] is the plain click that accessibility
 * services use.
 */
@Composable
fun StepButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onStep: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    symbolSize: androidx.compose.ui.unit.TextUnit = 48.sp,
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val container = when {
        !enabled -> colors.surfaceContainerHigh
        pressed -> colors.primary
        else -> colors.primaryContainer
    }
    val content = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.45f)
        pressed -> colors.onPrimary
        else -> colors.onPrimaryContainer
    }
    Box(
        modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(container)
            .border(2.dp, if (enabled) colors.primary else colors.outlineVariant, shape)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try {
                                press()
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                                release()
                            }
                        },
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick(label = description) {
                    if (enabled) step()
                    true
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = symbolSize, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
    }
}

/**
 * Speed or incline: the figure at its fixed size ([LiveSizes.figure]) with −/+ and a [footer] (the presets), in a panel
 * of fixed height ([LiveSizes.adjustPanelHeight]) so that nothing around it moves or resizes the figure. The requested
 * target shows next to the label while it is not reached, a thin line while the treadmill verifies a command, and a
 * failed command's [message] takes the place of the footer until the next command.
 */
@Composable
fun AdjustPanel(
    label: String,
    unit: String,
    value: String,
    target: String?,
    busy: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    lessDescription: String,
    moreDescription: String,
    onLessPress: () -> Unit,
    onMorePress: () -> Unit,
    onRelease: () -> Unit,
    onLessStep: () -> Unit,
    onMoreStep: () -> Unit,
    sizes: LiveSizes,
    tag: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    footer: @Composable () -> Unit = {},
) {
    @Composable
    fun button(symbol: String, description: String, press: () -> Unit, step: () -> Unit, suffix: String, mod: Modifier) = StepButton(
        symbol = symbol,
        description = description,
        enabled = enabled,
        onPress = press,
        onRelease = onRelease,
        onStep = step,
        testTag = "${tag}_$suffix",
        modifier = mod,
        symbolSize = sizes.stepSymbol,
    )

    @Composable
    fun labelText() = Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }

    @Composable
    fun targetText(style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) = Text(
        // Always there (a blank line without a target) so that nothing moves when one appears.
        target ?: " ",
        modifier = modifier.testTag("${tag}_target"),
        style = style,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.extra.warning,
        maxLines = 1,
    )

    Panel(modifier.height(sizes.adjustPanelHeight).testTag(tag)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sizes.wide) {
                    // Tablet: label and target on one line, the figure, the two big buttons side by side under it.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { labelText() }
                        targetText(MaterialTheme.typography.headlineSmall)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LiveFigureText(value, sizes, "${tag}_value", dimmed = dimmed)
                    }
                    Row(Modifier.fillMaxWidth().height(sizes.stepButton), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        button("−", lessDescription, onLessPress, onLessStep, "minus", Modifier.weight(1f).fillMaxHeight())
                        button("+", moreDescription, onMorePress, onMoreStep, "plus", Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    // Phone: the buttons on both sides of the figure.
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        button("−", lessDescription, onLessPress, onLessStep, "minus", Modifier.width(sizes.stepButton).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            labelText()
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                LiveFigureText(value, sizes, "${tag}_value", dimmed = dimmed)
                            }
                            targetText(MaterialTheme.typography.titleMedium)
                        }
                        button("+", moreDescription, onMorePress, onMoreStep, "plus", Modifier.width(sizes.stepButton).fillMaxHeight())
                    }
                }
                Box(Modifier.fillMaxWidth().height(sizes.presetHeight), contentAlignment = Alignment.CenterStart) {
                    if (message != null) {
                        Text(
                            message,
                            modifier = Modifier.fillMaxWidth().testTag("${tag}_failure").semantics { liveRegion = LiveRegionMode.Assertive },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        footer()
                    }
                }
            }
            // The verification line runs along the top edge: it takes no room from the figure.
            if (busy) {
                LinearProgressIndicator(
                    Modifier.align(Alignment.TopCenter).padding(horizontal = 28.dp, vertical = 4.dp).fillMaxWidth().height(3.dp).testTag("${tag}_busy"),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The speed and incline panels of the free workout and of a program: incline on the left and speed on the right on the
 * tablet (in a row of fixed height), incline above speed on the phone (the same reading order).
 */
@Composable
fun SpeedInclinePanels(ui: MainUiState, controls: ControlActions, sizes: LiveSizes) {
    if (sizes.wide) {
        Row(Modifier.fillMaxWidth().height(sizes.adjustPanelHeight), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            InclinePanel(ui, controls, sizes, Modifier.weight(1f))
            SpeedPanel(ui, controls, sizes, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            InclinePanel(ui, controls, sizes, Modifier.fillMaxWidth())
            SpeedPanel(ui, controls, sizes, Modifier.fillMaxWidth())
        }
    }
}

/** The speed panel: figure, target, −/+ and the presets. */
@Composable
fun SpeedPanel(ui: MainUiState, actions: ControlActions, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val t = ui.treadmill
    val target = ui.targetSpeed?.takeIf { abs(it - t.speedKmh) >= TARGET_VISIBLE_DIFFERENCE }
    val failure = ui.controls.failure?.takeIf { it.action == ControlAction.Speed }
    val range = ui.controls.speedRange
    val enabled = ui.canAdjustSpeed
    AdjustPanel(
        label = stringResource(R.string.metric_speed),
        unit = stringResource(R.string.unit_speed),
        value = Format.speed(t.speedKmh),
        target = target?.let { stringResource(R.string.control_target, Format.speed(it)) },
        busy = ui.controls.speedInFlight,
        enabled = enabled,
        dimmed = t.isStale,
        lessDescription = stringResource(R.string.control_speed_less),
        moreDescription = stringResource(R.string.control_speed_more),
        onLessPress = { actions.onSpeedPress(-1) },
        onMorePress = { actions.onSpeedPress(+1) },
        onRelease = actions.onSpeedRelease,
        onLessStep = { actions.onSpeedStep(-1) },
        onMoreStep = { actions.onSpeedStep(+1) },
        sizes = sizes,
        tag = "speed",
        modifier = modifier,
        message = failure?.let { failureMessage(it) },
    ) {
        val shown = ui.targetSpeed ?: t.speedKmh
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in ManualControls.SPEED_PRESETS) {
                val clamped = preset.coerceIn(range.min, range.max)
                PresetButton(
                    text = if (clamped % 1.0 == 0.0) clamped.toInt().toString() else Format.speed(clamped),
                    description = stringResource(R.string.control_speed_preset, Format.speed(clamped)),
                    selected = enabled && abs(shown - clamped) < TARGET_VISIBLE_DIFFERENCE,
                    enabled = enabled,
                    height = sizes.presetHeight,
                    tag = "speed_preset_${preset.toInt()}",
                    onClick = { actions.onSetSpeed(clamped) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The incline panel: figure, target, −/+ and the presets. */
@Composable
fun InclinePanel(ui: MainUiState, actions: ControlActions, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val t = ui.treadmill
    val target = ui.targetIncline?.takeIf { abs(it - t.inclinePercent) >= TARGET_VISIBLE_DIFFERENCE }
    val failure = ui.controls.failure?.takeIf { it.action == ControlAction.Incline }
    val enabled = ui.canAdjustIncline
    val range = ui.controls.inclineRange
    AdjustPanel(
        label = stringResource(R.string.metric_incline),
        unit = stringResource(R.string.unit_incline),
        value = Format.incline(t.inclinePercent),
        target = target?.let { stringResource(R.string.control_target, Format.incline(it)) },
        busy = ui.controls.inclineInFlight,
        enabled = enabled,
        dimmed = t.isStale,
        lessDescription = stringResource(R.string.control_incline_less),
        moreDescription = stringResource(R.string.control_incline_more),
        onLessPress = { actions.onInclinePress(-1) },
        onMorePress = { actions.onInclinePress(+1) },
        onRelease = actions.onInclineRelease,
        onLessStep = { actions.onInclineStep(-1) },
        onMoreStep = { actions.onInclineStep(+1) },
        sizes = sizes,
        tag = "incline",
        modifier = modifier,
        message = failure?.let { failureMessage(it) },
    ) {
        val shown = ui.targetIncline ?: t.inclinePercent
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in ManualControls.INCLINE_PRESETS) {
                val clamped = preset.coerceIn(range.min, range.max)
                PresetButton(
                    text = Format.incline(clamped),
                    description = stringResource(R.string.control_incline_preset, Format.incline(clamped)),
                    selected = enabled && abs(shown - clamped) < TARGET_VISIBLE_DIFFERENCE,
                    enabled = enabled,
                    height = sizes.presetHeight,
                    tag = "incline_preset_${preset.toInt()}",
                    onClick = { actions.onSetIncline(clamped) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PresetButton(
    text: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    height: androidx.compose.ui.unit.Dp,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(height)
            .semantics { contentDescription = description }
            .testTag(tag),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.5.dp,
            when {
                !enabled -> colors.outlineVariant
                selected -> colors.primary
                else -> colors.outline
            },
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Differences below this (km/h or %) are not worth showing as a target: the figure already says it. */
private const val TARGET_VISIBLE_DIFFERENCE = 0.05

// ---------------------------------------------------------------------------------------------------------------
// Pause, resume and STOP

/** One toggle: Pause with the belt running, Resume when paused (and the belt has stopped). */
@Composable
fun PauseResumeButton(ui: MainUiState, actions: ControlActions, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val paused = ui.isPaused
    val enabled = if (paused) ui.canResume else ui.canPause
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = if (paused) actions.onResume else actions.onPause,
        enabled = enabled,
        modifier = modifier.height(sizes.pauseHeight).testTag("pause_resume_button"),
        shape = RoundedCornerShape(24.dp),
        // A narrow slot (the phone's row of block buttons) keeps the word big.
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (paused) colors.primary else MaterialTheme.extra.warning,
            contentColor = if (paused) colors.onPrimary else MaterialTheme.extra.onWarning,
            disabledContainerColor = colors.surfaceContainerHigh,
            disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            stringResource(if (paused) R.string.control_resume else R.string.control_pause),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = sizes.pauseText, stepSize = 1.sp),
        )
    }
}

/**
 * STOP: red, one touch, no confirmation. It acts when the finger goes down (not when it is lifted): it is the control
 * for the worst moment.
 */
@Composable
fun StopButton(
    onStop: () -> Unit,
    textSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    testTag: String = "stop_button",
    /** On the red alert screen the button is dark with a white border: a red one would not be seen. */
    onAlertBackground: Boolean = false,
) {
    val stop by rememberUpdatedState(onStop)
    var pressed by remember { mutableStateOf(false) }
    val extra = MaterialTheme.extra
    val shape = RoundedCornerShape(28.dp)
    val description = stringResource(R.string.control_stop)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    onAlertBackground -> if (pressed) androidx.compose.ui.graphics.Color(0xFF3A0000) else androidx.compose.ui.graphics.Color.Black
                    pressed -> extra.alert.copy(alpha = 0.78f)
                    else -> extra.alert
                },
            )
            .border(3.dp, extra.onAlert.copy(alpha = 0.85f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        stop()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                onClick(label = description) {
                    stop()
                    true
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(description, fontSize = textSize, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = extra.onAlert, maxLines = 1)
    }
}

/** Where STOP is when the connection is down: a clear instruction instead of a button that cannot work. */
@Composable
fun StopUnreachable(modifier: Modifier = Modifier) {
    val extra = MaterialTheme.extra
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(extra.alert.copy(alpha = 0.16f))
            .border(3.dp, extra.alert, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .testTag("stop_unreachable"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.stop_unreachable_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.stop_unreachable_body),
                style = if (LocalLayout.current.compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** STOP, or the message that replaces it without a connection. */
@Composable
fun StopSection(ui: MainUiState, actions: ControlActions, sizes: LiveSizes, modifier: Modifier = Modifier) {
    if (ui.stopUnreachable) {
        StopUnreachable(modifier)
    } else {
        StopButton(actions.onStop, sizes.stopText, modifier)
    }
}

/**
 * One line for what is worth knowing about pause and resume: the last failure, no active user, or why Resume waits.
 * `null` when there is nothing to say.
 */
@Composable
fun controlHint(ui: MainUiState): Pair<String, Boolean>? {
    val failure = ui.controls.failure?.takeIf { it.action == ControlAction.Pause || it.action == ControlAction.Resume }
    return when {
        failure != null -> failureMessage(failure) to true
        ui.activeUser == null && ui.isConnected -> stringResource(R.string.control_hint_no_user) to false
        ui.session.note == ControlsNote.ProgramInterrupted -> stringResource(R.string.control_hint_program_interrupted) to false
        ui.session.note == ControlsNote.TestSpeedOnly -> stringResource(R.string.control_hint_test_speed_only) to false
        ui.session.note == ControlsNote.TestLocked -> stringResource(R.string.control_hint_test_locked) to false
        ui.resumeWaitsForBelt -> stringResource(R.string.control_resume_waiting) to false
        else -> null
    }
}

@Composable
fun ControlHintText(hint: Pair<String, Boolean>?, modifier: Modifier = Modifier, reserveLines: Boolean = true) {
    Text(
        hint?.first ?: " ",
        modifier = modifier.fillMaxWidth().testTag("control_hint").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (hint?.second == true) FontWeight.Bold else FontWeight.Normal,
        color = if (hint?.second == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        minLines = if (reserveLines) 2 else 1,
        maxLines = 2,
    )
}

/** Translated message of a failed command, e.g. "Could not change the speed: the treadmill did not respond." */
@Composable
fun failureMessage(failure: ControlFailure): String {
    val reason = when (val result = failure.result) {
        null -> stringResource(R.string.failure_not_executed)
        CommandResult.Success -> ""
        CommandResult.NotConnected -> stringResource(R.string.control_failed_not_connected)
        is CommandResult.Failure -> failureText(result.code)
    }
    if (failure.result == CommandResult.NotConnected) return reason
    return stringResource(
        when (failure.action) {
            ControlAction.Speed -> R.string.control_failed_speed
            ControlAction.Incline -> R.string.control_failed_incline
            ControlAction.Pause -> R.string.control_failed_pause
            ControlAction.Resume -> R.string.control_failed_resume
        },
        reason,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Secondary figures (phone)

/**
 * The four secondary figures of the phone in one line, so the speed and the incline keep the room. [footer] is one more
 * line inside the same panel (the estimated fat/carbohydrate split).
 */
@Composable
fun MetricStrip(items: List<StripItem>, dimmed: Boolean, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null) {
    Panel(modifier) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((label, value, unit, badge) in items) {
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val valueColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                append(value)
                                if (unit != null) {
                                    pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant))
                                    append(" $unit")
                                    pop()
                                }
                            },
                            modifier = Modifier.testTag("metric_value_$label"),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = valueColor,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (badge != null) HeartRateSourceIcon(badge, size = 14.dp)
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }
}

/** One figure of the [MetricStrip]; [badge] is the heart-rate source, shown as a small icon next to the label. */
data class StripItem(val label: String, val value: String, val unit: String?, val badge: com.treadz.console.programs.fitness.HeartRateSourceKind? = null)

// ---------------------------------------------------------------------------------------------------------------
// STOP bar for the other screens

/**
 * Bar shown on every screen but the main one while the belt is not at rest: current speed, a way back to the live
 * screen, and STOP (or, with no connection, the instruction to use the console's STOP or the key).
 */
@Composable
fun SessionBar(ui: MainUiState, actions: ControlActions, modifier: Modifier = Modifier) {
    val compact = LocalLayout.current.compact
    val extra = MaterialTheme.extra
    Panel(modifier.fillMaxWidth().testTag("session_bar"), borderColor = extra.alert) {
        Column {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (compact) {
                    Column(Modifier.weight(1f)) {
                        SessionSpeed(ui)
                        BackToMainButton(ui.session.kind, actions.onBackToMain)
                    }
                    SessionStop(ui, actions, if (ui.stopUnreachable) Modifier.weight(1.3f) else Modifier.width(132.dp).height(80.dp))
                } else {
                    SessionSpeed(ui)
                    BackToMainButton(ui.session.kind, actions.onBackToMain)
                    Box(Modifier.weight(1f))
                    SessionStop(ui, actions, if (ui.stopUnreachable) Modifier.weight(2f) else Modifier.width(220.dp).height(80.dp))
                }
            }
            // A command that failed (a program's or a test's too) is told here when the live screen is not in view.
            ui.controls.failure?.let { failure ->
                Text(
                    failureMessage(failure),
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp).testTag("session_failure"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extra.warning,
                )
            }
        }
    }
}

@Composable
private fun SessionSpeed(ui: MainUiState) {
    Text(
        stringResource(R.string.session_bar_speed, Format.speed(ui.treadmill.speedKmh)),
        modifier = Modifier.testTag("session_speed"),
        style = if (LocalLayout.current.compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

@Composable
private fun BackToMainButton(kind: SessionKind, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("session_back_button")) {
        Text(
            "← " + stringResource(
                when (kind) {
                    SessionKind.None -> R.string.session_bar_main
                    SessionKind.Program -> R.string.session_bar_program
                    SessionKind.FitnessTest -> R.string.session_bar_test
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun SessionStop(ui: MainUiState, actions: ControlActions, modifier: Modifier) {
    if (ui.stopUnreachable) {
        Text(
            stringResource(R.string.session_bar_unreachable),
            modifier = modifier.testTag("session_unreachable").semantics { liveRegion = LiveRegionMode.Assertive },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        StopButton(actions.onStop, textSize = 34.sp, modifier = modifier, testTag = "session_stop_button")
    }
}
