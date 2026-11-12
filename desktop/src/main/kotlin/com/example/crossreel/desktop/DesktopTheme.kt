package com.example.crossreel.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AppBackground = Color(0xFF0F0E0D)
val SurfaceBase = Color(0xFF141312)
val SurfaceDim = Color(0xFF141312)
val SurfaceContainerLowest = Color(0xFF181716)
val SurfaceContainerLow = Color(0xFF1C1B19)
val SurfaceContainer = Color(0xFF22201E)
val SurfaceContainerHigh = Color(0xFF2C2926)
val SurfaceContainerHighest = Color(0xFF373430)

val FlameOrange = Color(0xFFFC6000)
val PrimaryContainerColor = Color(0xFF3D1600)
val OnPrimaryContainerColor = Color(0xFFFFDBCC)

val WarmAmber = Color(0xFFFFB74D)
val SecondaryContainerColor = Color(0xFF422B00)
val OnSecondaryContainerColor = Color(0xFFFFDF9E)

val TertiaryCoral = Color(0xFFFF8A65)
val OutlineColor = Color(0xFF8A827A)
val OutlineVariantColor = Color(0xFF3A3633)

val TextPrimary = Color(0xFFEBE0D9)
val TextSecondary = Color(0xFFC7BFB7)
val TextMuted = Color(0xFF8A827A)

private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = FlameOrange,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerColor,
    onPrimaryContainer = OnPrimaryContainerColor,
    secondary = WarmAmber,
    onSecondary = Color(0xFF452B00),
    secondaryContainer = SecondaryContainerColor,
    onSecondaryContainer = OnSecondaryContainerColor,
    tertiary = TertiaryCoral,
    onTertiary = Color.White,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceDim = SurfaceDim,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = OutlineColor,
    outlineVariant = OutlineVariantColor
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val DesktopTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.4.sp)
)

val TabularNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum"
)

@Composable
fun OrangeBeatDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ExpressiveDarkColorScheme,
        typography = DesktopTypography,
        shapes = ExpressiveShapes,
        content = content
    )
}
