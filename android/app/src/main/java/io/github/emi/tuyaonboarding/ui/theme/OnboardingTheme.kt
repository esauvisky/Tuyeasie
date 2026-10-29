package io.github.emi.tuyaonboarding.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFFB53D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2D4),
    onPrimaryContainer = Color(0xFF3A1000),
    secondary = Color(0xFF6B5E55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E4DC),
    onSecondaryContainer = Color(0xFF2B1B13),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1C1B19),
    surface = Color.White,
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF625B55),
    surfaceContainer = Color(0xFFF2F1ED),
    outline = Color(0xFFD5CEC7),
    outlineVariant = Color(0xFFE8E2DC),
    error = Color(0xFFBA1A1A)
)

private val OnboardingShapes = Shapes(
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val OnboardingTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

@Composable
fun OnboardingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = OnboardingTypography,
        shapes = OnboardingShapes,
        content = content
    )
}
