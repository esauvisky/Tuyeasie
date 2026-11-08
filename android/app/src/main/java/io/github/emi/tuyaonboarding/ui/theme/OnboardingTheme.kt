package io.github.emi.tuyaonboarding.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Home Assistant's frontend palette. The bar uses light-blue 800 rather than HA's #03A9F4 so white text passes 4.5:1.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F5FE),
    onPrimaryContainer = Color(0xFF01579B),
    secondary = Color(0xFF616161),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF212121),
    tertiary = Color(0xFF2E7D32),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8F5E9),
    onTertiaryContainer = Color(0xFF1B5E20),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF616161),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFEEEEEE),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF0D3B52),
    onPrimaryContainer = Color(0xFFB3E5FC),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF212121),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFE1E1E1),
    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF0B2E0E),
    tertiaryContainer = Color(0xFF1B3A1D),
    onTertiaryContainer = Color(0xFFC8E6C9),
    background = Color(0xFF111111),
    onBackground = Color(0xFFE1E1E1),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color(0xFF111111),
    surfaceContainerLow = Color(0xFF1C1C1C),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF2C2C2C),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF2C2C2C),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF3B0909),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFFCDD2)
)

@Immutable
data class HaBarColors(val container: Color, val content: Color, val subtitle: Color)

val LocalHaBar = staticCompositionLocalOf { HaBarColors(Color.Unspecified, Color.Unspecified, Color.Unspecified) }

// The amber "working" tint is used only for icons, never text, so it needs no text-contrast pairing.
val WorkingTint = Color(0xFFF57C00)

private val HaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun OnboardingTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val bar = if (dark) {
        HaBarColors(Color(0xFF1C1C1C), Color(0xFFE1E1E1), Color(0xFFA8A8A8))
    } else {
        HaBarColors(Color(0xFF0277BD), Color.White, Color(0xFFE1F5FE))
    }
    CompositionLocalProvider(LocalHaBar provides bar) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = HaShapes,
            content = content
        )
    }
}
