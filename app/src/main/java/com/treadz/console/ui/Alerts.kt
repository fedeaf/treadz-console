package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.R
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillEvent

/**
 * Full-screen safety alert, on top of everything and not letting touches through. [onStop], when given, adds a STOP
 * button: with the belt possibly moving, the alert must not be what stands between the user and STOP.
 */
@Composable
fun AlertOverlay(alert: TreadmillAlert, onDismiss: () -> Unit, modifier: Modifier = Modifier, onStop: (() -> Unit)? = null) {
    val (titleRes, body) = when (alert) {
        TreadmillEvent.SafetyKeyRemoved -> R.string.alert_key_title to stringResource(R.string.alert_key_body)
        TreadmillEvent.ConnectionLost -> R.string.alert_connection_lost_title to stringResource(R.string.alert_connection_lost_body)
        TreadmillEvent.DataStalled -> R.string.alert_data_stalled_title to stringResource(R.string.alert_data_stalled_body)
        is TreadmillEvent.CommandNotExecuted ->
            R.string.alert_command_title to stringResource(R.string.alert_command_body, alert.command)
    }
    val bg = MaterialTheme.extra.alert
    val fg = MaterialTheme.extra.onAlert
    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .testTag("alert_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            WarningSign(fg, bg)
            Text(
                stringResource(titleRes),
                color = fg,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                color = fg,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (onStop != null && (alert is TreadmillEvent.CommandNotExecuted || alert == TreadmillEvent.DataStalled)) {
                StopButton(
                    onStop = onStop,
                    textSize = 44.sp,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).height(96.dp),
                    testTag = "alert_stop_button",
                    onAlertBackground = true,
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).heightIn(min = 80.dp).testTag("alert_dismiss"),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = bg),
            ) {
                Text(
                    stringResource(R.string.alert_dismiss),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

/** Hand-drawn warning triangle with an exclamation mark (no dependency on fonts or icons). */
@Composable
private fun WarningSign(fg: Color, bg: Color) {
    Canvas(Modifier.size(104.dp)) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(w / 2, h * 0.06f)
            lineTo(w * 0.97f, h * 0.92f)
            lineTo(w * 0.03f, h * 0.92f)
            close()
        }
        drawPath(triangle, fg)
        // Exclamation mark
        val stroke = w * 0.09f
        drawLine(bg, Offset(w / 2, h * 0.34f), Offset(w / 2, h * 0.64f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(bg, radius = stroke * 0.62f, center = Offset(w / 2, h * 0.79f))
    }
}
