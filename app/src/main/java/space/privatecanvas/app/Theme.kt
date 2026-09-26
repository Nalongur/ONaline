package space.privatecanvas.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF292B29)
val MutedInk = Color(0xFF70736E)
val Mist = Color(0xFFF5F3ED)
val Paper = Color(0xFFFCFBF8)
val Sage = Color(0xFF939B7E)
val MutedBlue = Color(0xFF4F8AD9)
val MutedCoral = Color(0xFFEA805A)
val Hairline = Color(0xFFD7D4CC)

val AcrylicShape = RoundedCornerShape(22.dp)
val LocalHighContrast = staticCompositionLocalOf { false }

private val CanvasTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 31.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.35).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = .3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = .7.sp,
    ),
)

@Composable
fun PrivateCanvasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = canvasColorScheme(),
        typography = CanvasTypography,
        content = content,
    )
}

private fun canvasColorScheme(): ColorScheme = lightColorScheme(
    primary = Sage,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8DC),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF88959A),
    onSecondary = Color.White,
    background = Mist,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFECE9E2),
    onSurfaceVariant = MutedInk,
    outline = Hairline,
    error = Color(0xFFA94F45),
)

fun CanvasTheme.backgroundColor(): Color = Color(background)
fun CanvasTheme.accentColor(): Color = Color(accent)
