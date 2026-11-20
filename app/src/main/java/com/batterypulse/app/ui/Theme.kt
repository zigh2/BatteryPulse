package com.batterypulse.app.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object Brand {
    val Charge = Color(0xFF22C55E)
    val Discharge = Color(0xFFF59E0B)
    val Hot = Color(0xFFEF4444)
    val Screen = Color(0xFF8B5CF6)
    val Health = Color(0xFF14B8A6)
    val Blue = Color(0xFF3B82F6)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF04130D),
    primaryContainer = Color(0xFF0F3D2E),
    onPrimaryContainer = Color(0xFFC9F7E3),
    background = Color(0xFF0A1311),
    onBackground = Color(0xFFE4EEEA),
    surface = Color(0xFF101C19),
    onSurface = Color(0xFFE4EEEA),
    surfaceVariant = Color(0xFF182824),
    onSurfaceVariant = Color(0xFF9DB3AC),
    surfaceContainer = Color(0xFF0D1816),
    surfaceContainerHigh = Color(0xFF152421),
    outline = Color(0xFF284038),
    outlineVariant = Color(0xFF1F332D),
    error = Brand.Hot,
    errorContainer = Color(0xFF4A1D22),
    onErrorContainer = Color(0xFFFFD9DC),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF047857),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF053A2A),
    background = Color(0xFFF3F8F6),
    onBackground = Color(0xFF0A1311),
    surface = Color.White,
    onSurface = Color(0xFF0A1311),
    surfaceVariant = Color(0xFFE3EEEA),
    onSurfaceVariant = Color(0xFF4F655E),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAF2EF),
    outline = Color(0xFFCBDAD4),
    outlineVariant = Color(0xFFDDE8E4),
    error = Color(0xFFB91C1C),
    errorContainer = Color(0xFFFFE1E3),
    onErrorContainer = Color(0xFF5A0F16),
)

@Composable
fun BatteryPulseTheme(dark: Boolean, dynamic: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Colour that tells the battery state at a glance. */
fun levelColor(level: Int, charging: Boolean): Color = when {
    charging -> Brand.Charge
    level in 0..15 -> Brand.Hot
    level in 0..30 -> Brand.Discharge
    else -> Brand.Blue
}
