package com.treadz.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R

/** Window-dependent layout: a phone in portrait is [compact]; a wide landscape window is [wide] (two columns). */
data class LayoutInfo(val compact: Boolean, val wide: Boolean)

val LocalLayout = staticCompositionLocalOf { LayoutInfo(compact = false, wide = true) }

/** Header of the secondary screens: back button, title and optional trailing content. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp).testTag("back_button")) {
                BackArrow(MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.nav_back),
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (LocalLayout.current.compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.testTag("screen_title"),
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        trailing()
    }
}

@Composable
private fun BackArrow(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(size.width, size.height / 2), Offset(2.dp.toPx(), size.height / 2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(2.dp.toPx(), size.height / 2), Offset(size.width * 0.5f, size.height * 0.1f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(2.dp.toPx(), size.height / 2), Offset(size.width * 0.5f, size.height * 0.9f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

/** Big primary action (touch-friendly, used while exercising). */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(24.dp),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Big secondary action. */
@Composable
fun BigOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, if (enabled) (if (destructive) color else MaterialTheme.colorScheme.outline) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Medium-size action for list rows (at least 48 dp tall). */
@Composable
fun RowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = contentPadding,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** Rounded surface used for cards and groups. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = color,
        // The card colours are not matched by contentColorFor (surfaceContainer equals surfaceVariant in the palette).
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
        content = content,
    )
}

/** Small label next to a title ("Active", "In progress"). The text always carries the meaning, the colour only helps. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Confirmation of a destructive action. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("confirm_dialog"),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_button")) {
                Text(confirmText, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_button")) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/** A labelled figure (weight, duration…) on a card. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    note: String? = null,
    valueTag: String? = null,
) {
    Panel(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    value,
                    modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (unit != null) {
                    Text(unit, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
