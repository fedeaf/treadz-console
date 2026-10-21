package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.programs.engine.RunnerConfig

/**
 * Every size of the live screens (free workout, program, fitness test), one set per form factor: the screens take
 * them from here and never override them, so the same figure has the same size everywhere.
 *
 * What is fixed: the speed and incline figure ([figure], no auto-size) and the panels around it
 * ([adjustPanelHeight], [figurePanelHeight]), STOP and Start ([stopHeight]), Pause and the row of block buttons. What
 * is flexible is the rest of each screen (the block panel and its chart, the secondary figures).
 */
data class LiveSizes(
    /** Two panes (tablet in landscape): the controls on the left, the side column on the right. */
    val wide: Boolean,
    val gap: Dp,
    /** The speed and incline figure, in every panel, test figure and the idle grid. */
    val figure: TextUnit,
    val figureLineHeight: TextUnit,
    /** The unit next to a figure of the idle grid. */
    val unit: TextUnit,
    /** The unit goes next to the figure (tablet) or on the label line (phone, where width is short). */
    val unitInline: Boolean,
    /** Biggest size of the other figures of the idle grid (they may shrink to fit, e.g. a long time). */
    val metricMax: TextUnit,
    /** Biggest size of the secondary figures while the belt runs (tablet). */
    val secondaryMax: TextUnit,
    /** Height of a speed or incline panel with its − / + (and presets). */
    val adjustPanelHeight: Dp,
    /** Height of a figure without buttons (fitness test) on the phone; on the tablet it is [adjustPanelHeight]. */
    val figurePanelHeight: Dp,
    /** Height (tablet) or width (phone) of the − / + buttons. */
    val stepButton: Dp,
    val stepSymbol: TextUnit,
    val presetHeight: Dp,
    val pauseHeight: Dp,
    /** Biggest size of the Pause / Resume text (a long word may shrink). */
    val pauseText: TextUnit,
    /** STOP and Start: the same slot, the same size. */
    val stopHeight: Dp,
    val stopText: TextUnit,
    val startText: TextUnit,
    /** Height of the row of block buttons (◀ previous, "+1", next ▶). */
    val skipHeight: Dp,
    /** Phone: width of ◀ and ▶ and of the "+1" button in the row of Pause. */
    val skipWidth: Dp,
    val extendWidth: Dp,
    /** The four totals of a program (two lines of two). */
    val totalValue: TextUnit,
)

/** The sizes of the live screens: the big ones of the tablet in landscape, the smaller ones of the phone. */
fun liveSizes(wide: Boolean): LiveSizes = if (wide) {
    LiveSizes(
        wide = true, gap = 16.dp, figure = 84.sp, figureLineHeight = 92.sp, unit = 30.sp, unitInline = true,
        metricMax = 84.sp, secondaryMax = 64.sp, adjustPanelHeight = 304.dp, figurePanelHeight = 304.dp,
        stepButton = 76.dp, stepSymbol = 64.sp, presetHeight = 48.dp, pauseHeight = 72.dp, pauseText = 36.sp,
        stopHeight = 220.dp, stopText = 96.sp, startText = 72.sp, skipHeight = 72.dp, skipWidth = 0.dp, extendWidth = 0.dp,
        totalValue = 40.sp,
    )
} else {
    LiveSizes(
        wide = false, gap = 12.dp, figure = 56.sp, figureLineHeight = 60.sp, unit = 22.sp, unitInline = false,
        metricMax = 56.sp, secondaryMax = 22.sp, adjustPanelHeight = 180.dp, figurePanelHeight = 148.dp,
        stepButton = 72.dp, stepSymbol = 48.sp, presetHeight = 48.dp, pauseHeight = 64.dp, pauseText = 26.sp,
        stopHeight = 92.dp, stopText = 52.sp, startText = 34.sp, skipHeight = 64.dp, skipWidth = 56.dp, extendWidth = 84.dp,
        totalValue = 28.sp,
    )
}

/** Share of the width of the main (left) part of a live screen on the tablet; the side column has 1. */
private const val MAIN_WEIGHT = 2.1f

/**
 * Tablet: [main] on the left; on the right the side column: [sideTop] (status, totals, hints, extra buttons; it scrolls
 * if it does not fit) and, pinned to the bottom, [sideBottom], which ends with STOP or Start ([LiveStop], [LiveStart]).
 */
@Composable
fun LiveWideLayout(
    sizes: LiveSizes,
    modifier: Modifier = Modifier,
    main: @Composable ColumnScope.() -> Unit,
    sideTop: @Composable ColumnScope.() -> Unit,
    sideBottom: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Column(Modifier.weight(MAIN_WEIGHT).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) { main() }
        Column(Modifier.weight(1f).fillMaxSize().testTag("live_side_column"), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sizes.gap),
            ) { sideTop() }
            sideBottom()
        }
    }
}

/**
 * Phone: [content] scrolls if the screen is short; [bottom] (Pause and the block buttons, then STOP or Start as a
 * full-width bar) stays at the bottom, always in view.
 */
@Composable
fun LiveCompactLayout(
    sizes: LiveSizes,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(sizes.gap),
        ) { content() }
        bottom()
    }
}

/**
 * The slot of STOP: the whole width of its column (the side column on the tablet, the screen on the phone) and a fixed
 * height. Without a connection it holds the instruction to use the console instead, which may need more lines.
 */
@Composable
fun LiveStop(ui: MainUiState, controls: ControlActions, sizes: LiveSizes) {
    StopSection(
        ui, controls, sizes,
        Modifier.fillMaxWidth().then(if (ui.stopUnreachable) Modifier.heightIn(min = sizes.stopHeight) else Modifier.height(sizes.stopHeight)),
    )
}

/** Start, in the slot of STOP and with its size: the belt at rest shows it where STOP will be once it runs. */
@Composable
fun LiveStart(ui: MainUiState, onStart: () -> Unit, sizes: LiveSizes) {
    Button(
        onClick = onStart,
        enabled = ui.canStart,
        modifier = Modifier.fillMaxWidth().height(sizes.stopHeight).testTag("start_button"),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (ui.startInProgress) {
            CircularProgressIndicator(Modifier.size(sizes.startText.value.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 4.dp)
        } else {
            Text(stringResource(R.string.start_button), fontSize = sizes.startText, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, maxLines = 1)
        }
    }
}

/** The speed or incline figure: always [LiveSizes.figure], never resized by what is around it. */
@Composable
fun LiveFigureText(value: String, sizes: LiveSizes, tag: String, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    Text(
        value,
        modifier = modifier.testTag(tag),
        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        fontSize = sizes.figure,
        lineHeight = sizes.figureLineHeight,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
    )
}

/**
 * A live figure without buttons (the fitness tests): label and unit, the figure at its fixed size and a [note] (the
 * target, the heart-rate band). Its height is fixed: [LiveSizes.adjustPanelHeight] on the tablet, so it lines up with a
 * panel that has buttons, [LiveSizes.figurePanelHeight] on the phone.
 */
@Composable
fun LiveFigurePanel(
    label: String,
    unit: String?,
    value: String,
    sizes: LiveSizes,
    tag: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    dimmed: Boolean = false,
    labelTrailing: (@Composable () -> Unit)? = null,
) {
    Panel(modifier.height(if (sizes.wide) sizes.adjustPanelHeight else sizes.figurePanelHeight).testTag(tag)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (unit != null) Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                labelTrailing?.invoke()
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LiveFigureText(value, sizes, "${tag}_value", dimmed = dimmed)
            }
            Text(
                note ?: " ",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/**
 * "+1": a minute more in a block that ends by time, 100 m more in one that ends by distance ([byDistance]). The fitness
 * tests show it in the same place, enabled in their warm-up and cool-down.
 */
@Composable
fun ExtendBlockButton(byDistance: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val config = RunnerConfig()
    val label = if (byDistance) stringResource(R.string.block_extend_meters, config.extendBlockMeters)
    else stringResource(R.string.block_extend_minutes, config.extendBlockSeconds / 60)
    val description = if (byDistance) stringResource(R.string.block_extend_meters_description, config.extendBlockMeters)
    else stringResource(R.string.block_extend_minutes_description, config.extendBlockSeconds / 60)
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description }.testTag("extend_block_button"),
        shape = RoundedCornerShape(24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) colors.primary else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary, disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.6f)),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/**
 * ◀ previous or next ▶: on the tablet the symbol over the [label], so three buttons fit in the side column; on the
 * phone the symbol alone ([label] `null`), with [description] for accessibility.
 */
@Composable
fun SkipButton(symbol: String, label: String?, description: String, enabled: Boolean, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    val content = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description }.testTag(tag),
        shape = RoundedCornerShape(24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, color = content)
            if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, color = content)
        }
    }
}

/**
 * The row of block buttons of the tablet: ◀ previous, "+1" and next ▶. A fitness test shows only "+1" in its
 * place ([previous] and [next] `null`).
 */
@Composable
fun BlockButtonsRow(
    sizes: LiveSizes,
    extend: @Composable (Modifier) -> Unit,
    previous: (@Composable (Modifier) -> Unit)? = null,
    next: (@Composable (Modifier) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(sizes.skipHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (previous != null) previous(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
        extend(Modifier.weight(1f).fillMaxHeight())
        if (next != null) next(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
    }
}
