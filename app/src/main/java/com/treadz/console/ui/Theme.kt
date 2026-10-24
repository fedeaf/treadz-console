package com.treadz.console.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colours Material 3 does not provide (connection state, warnings, critical alert). */
@Immutable
data class ExtraColors(
    val ok: Color,
    val onOk: Color,
    val warning: Color,
    val onWarning: Color,
    val alert: Color,
    val onAlert: Color,
    /** Fat and carbohydrate in the estimated split of the energy (distinct from speed, incline and heart-rate colours). */
    val fuelFat: Color,
    val fuelCarbs: Color,
)

private val DarkExtra = ExtraColors(
    ok = Color(0xFF4ADE80),
    onOk = Color(0xFF00210C),
    warning = Color(0xFFFFC53D),
    onWarning = Color(0xFF2B1D00),
    alert = Color(0xFFC62828),
    onAlert = Color(0xFFFFFFFF),
    fuelFat = Color(0xFFFF9F43),
    fuelCarbs = Color(0xFFB69CFF),
)

private val LightExtra = ExtraColors(
    ok = Color(0xFF0B7A34),
    onOk = Color(0xFFFFFFFF),
    warning = Color(0xFF8A5A00),
    onWarning = Color(0xFFFFFFFF),
    alert = Color(0xFFB71C1C),
    onAlert = Color(0xFFFFFFFF),
    fuelFat = Color(0xFFB45309),
    fuelCarbs = Color(0xFF5B3FC4),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF00210C),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF001E2E),
    background = Color(0xFF05070A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF05070A),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF151B23),
    onSurfaceVariant = Color(0xFFB6C2CF),
    surfaceContainer = Color(0xFF151B23),
    surfaceContainerHigh = Color(0xFF1D252F),
    outline = Color(0xFF5B6876),
    outlineVariant = Color(0xFF2A333E),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0000),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B7A34),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8F2D4),
    onPrimaryContainer = Color(0xFF00210C),
    secondary = Color(0xFF00629A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF05070A),
    surface = Color(0xFFF4F6F8),
    onSurface = Color(0xFF05070A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF3D4753),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6EAEE),
    outline = Color(0xFF6B7683),
    outlineVariant = Color(0xFFCBD2D9),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

val LocalExtraColors = staticCompositionLocalOf { DarkExtra }

val MaterialTheme.extra: ExtraColors
    @Composable get() = LocalExtraColors.current

@Composable
fun TreadZTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extra = if (darkTheme) DarkExtra else LightExtra
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
