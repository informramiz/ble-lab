package com.codebyramiz.blelab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Indigo = Color(0xFF3D2BB8)
private val IndigoLight = Color(0xFFC6BFFF)
private val Signal = Color(0xFF00B8D9)
private val SignalLight = Color(0xFF5CE1FF)
private val Night = Color(0xFF0E1033)
private val NightSurface = Color(0xFF15183F)
private val NightSurfaceHigh = Color(0xFF1E2252)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF16006B),
    secondary = Color(0xFF00687A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBFF),
    onSecondaryContainer = Color(0xFF001F26),
    tertiary = Color(0xFFB2338E),
    background = Color(0xFFF8F7FF),
    surface = Color(0xFFF8F7FF),
    surfaceContainerLow = Color(0xFFF1F0FB),
    surfaceContainer = Color(0xFFEBEAF6),
    surfaceContainerHigh = Color(0xFFE5E4F1),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF2A0F9E),
    primaryContainer = Color(0xFF3D2BB8),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = SignalLight,
    onSecondary = Color(0xFF003641),
    secondaryContainer = Color(0xFF004E5C),
    onSecondaryContainer = Color(0xFFB2EBFF),
    tertiary = Color(0xFFFFADE0),
    background = Night,
    surface = Night,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    error = Color(0xFFFFB4AB),
)

/** Colours that carry meaning (link health) and so stay fixed under dynamic colour. */
@Immutable
data class SignalColors(val good: Color, val warn: Color, val bad: Color, val chart: Color, val glow: Color)

// Theme-scoped tokens, the same pattern MaterialTheme uses for its colour scheme.
@Suppress("ComposeCompositionLocalUsage")
val LocalSignalColors = staticCompositionLocalOf {
    SignalColors(Color(0xFF1E9E63), Color(0xFFE09B12), Color(0xFFD9363E), Signal, Color(0xFFFFD54F))
}

private val Mono = FontFamily.Monospace

private val BleTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** Monospace style for addresses, UUIDs and hex. */
val MonoStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp)

@Composable
fun BleLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors

        else -> LightColors
    }
    val signal = if (darkTheme) {
        SignalColors(Color(0xFF4ADE9A), Color(0xFFFFC857), Color(0xFFFF6B6B), SignalLight, Color(0xFFFFE082))
    } else {
        LocalSignalColors.current
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSignalColors provides signal) {
        MaterialTheme(colorScheme = colors, typography = BleTypography, content = content)
    }
}
