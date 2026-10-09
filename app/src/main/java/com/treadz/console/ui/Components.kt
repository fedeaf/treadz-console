package com.treadz.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.contentDescription

/** Big live figure, with label, unit and an optional note. Meant to be read from a distance. */
@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    note: String? = null,
    maxValueSize: TextUnit = 96.sp,
    unitSize: TextUnit = 24.sp,
    dimmed: Boolean = false,
    unitInline: Boolean = true,
) {
    val valueColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (!unitInline && unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        modifier = Modifier.weight(1f, fill = false).testTag("metric_value_$label"),
                        color = valueColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 24.sp,
                            maxFontSize = maxValueSize,
                            stepSize = 2.sp,
                        ),
                    )
                    if (unitInline && unit != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = unit,
                            modifier = Modifier.padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = unitSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Coloured dot for states (decorative: the meaning is always in the text). */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(color, CircleShape)
            .clearAndSetSemantics { },
    )
}
