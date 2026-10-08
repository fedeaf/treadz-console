package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gear button of the top bar. It is disabled while the belt is not at rest. With [syncProblem] a small mark tells that
 * the last synchronisation failed: the details are in the settings, nothing interrupts the workout.
 */
@Composable
fun SettingsButton(
    enabled: Boolean,
    syncProblem: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.settings_open)
    val problem = stringResource(R.string.settings_sync_problem_marker)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (syncProblem) "$label, $problem" else label }
            .testTag("settings_button"),
        contentAlignment = Alignment.Center,
    ) {
        GearIcon(MaterialTheme.colorScheme.onSurface)
        if (syncProblem) {
            Box(Modifier.align(Alignment.TopEnd).size(22.dp), contentAlignment = Alignment.Center) {
                StatusDot(MaterialTheme.extra.warning, size = 12.dp, modifier = Modifier.testTag("settings_sync_marker"))
            }
        }
    }
}

@Composable
private fun GearIcon(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val radius = size.minDimension / 2
        drawCircle(color, radius = radius * 0.52f, style = Stroke(width = radius * 0.3f))
        for (i in 0 until TEETH) {
            val angle = i * 2 * PI / TEETH
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(
                color,
                start = center + direction * (radius * 0.68f),
                end = center + direction * (radius * 0.96f),
                strokeWidth = radius * 0.34f,
                cap = StrokeCap.Butt,
            )
        }
    }
}

private const val TEETH = 8
