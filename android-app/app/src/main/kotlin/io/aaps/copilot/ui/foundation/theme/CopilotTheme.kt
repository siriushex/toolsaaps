package io.aaps.copilot.ui.foundation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF0B57D0),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF006B5B),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = androidx.compose.ui.graphics.Color(0xFF5B5F97),
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color(0xFFF8FAFD),
    onBackground = androidx.compose.ui.graphics.Color(0xFF111318),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF111318),
    error = androidx.compose.ui.graphics.Color(0xFFB3261E),
    onError = androidx.compose.ui.graphics.Color.White
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFA8C7FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003061),
    secondary = androidx.compose.ui.graphics.Color(0xFF7DD7C3),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF00382F),
    tertiary = androidx.compose.ui.graphics.Color(0xFFC2C4FF),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF2D3165),
    background = androidx.compose.ui.graphics.Color(0xFF111318),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE2E2E9),
    surface = androidx.compose.ui.graphics.Color(0xFF1A1C21),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE2E2E9),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    onError = androidx.compose.ui.graphics.Color(0xFF690005)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    )
)

private val AppShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
)

@Immutable
data class NumericTypography(
    val valueLarge: TextStyle,
    val valueMedium: TextStyle,
    val valueSmall: TextStyle
)

val LocalNumericTypography = staticCompositionLocalOf {
    NumericTypography(
        valueLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold),
        valueMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        valueSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    )
}

@Composable
fun AapsCopilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val numericTypography = NumericTypography(
        valueLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold),
        valueMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        valueSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    )

    androidx.compose.runtime.CompositionLocalProvider(LocalNumericTypography provides numericTypography) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
