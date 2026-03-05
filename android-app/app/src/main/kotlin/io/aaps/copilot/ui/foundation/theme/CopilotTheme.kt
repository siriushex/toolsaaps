package io.aaps.copilot.ui.foundation.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.config.UiStyle
import kotlin.math.max

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF005CB8),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E4FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001C3B),
    secondary = androidx.compose.ui.graphics.Color(0xFF00696B),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF9BF0F3),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF002021),
    tertiary = androidx.compose.ui.graphics.Color(0xFF875500),
    onTertiary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDDB8),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF2B1700),
    background = androidx.compose.ui.graphics.Color(0xFFF8FAFD),
    onBackground = androidx.compose.ui.graphics.Color(0xFF101828),
    surface = androidx.compose.ui.graphics.Color(0xFFFDFBFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF101828),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEDEEF2),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF3F4752),
    outline = androidx.compose.ui.graphics.Color(0xFF6E7886),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFD0D7E2),
    error = androidx.compose.ui.graphics.Color(0xFFB3261E),
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFA8C8FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003062),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF00468B),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E4FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF77DADB),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF003738),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF004F51),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF9BF0F3),
    tertiary = androidx.compose.ui.graphics.Color(0xFFFFB95C),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF462A00),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF633F00),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDDB8),
    background = androidx.compose.ui.graphics.Color(0xFF121417),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE2E6EC),
    surface = androidx.compose.ui.graphics.Color(0xFF151A21),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE2E6EC),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2B313D),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC1C8D4),
    outline = androidx.compose.ui.graphics.Color(0xFF8B94A3),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF404958),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    onError = androidx.compose.ui.graphics.Color(0xFF690005),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6)
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

@Composable
fun CopilotStyledBackground(
    uiStyle: UiStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    when (uiStyle) {
        UiStyle.CLASSIC -> {
            val backgroundColor = MaterialTheme.colorScheme.background
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(color = backgroundColor)
                    }
            ) {
                content()
            }
        }

        UiStyle.DYNAMIC_GRADIENT -> {
            val transition = rememberInfiniteTransition(label = "copilot-gradient")
            val phase = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 13_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "phase"
            )
            val primary = if (darkTheme) {
                listOf(
                    Color(0xFF0B132B),
                    Color(0xFF1C2541),
                    Color(0xFF1B4965),
                    Color(0xFF102A43)
                )
            } else {
                listOf(
                    Color(0xFFEAF2FF),
                    Color(0xFFDDF7FF),
                    Color(0xFFF1F7FF),
                    Color(0xFFEAF4F4)
                )
            }
            val overlay = if (darkTheme) {
                listOf(
                    Color(0x553B82F6),
                    Color(0x4438BDF8),
                    Color(0x3322D3EE),
                    Color.Transparent
                )
            } else {
                listOf(
                    Color(0x6693C5FD),
                    Color(0x55A5F3FC),
                    Color(0x4499F6E4),
                    Color.Transparent
                )
            }

            Box(
                modifier = modifier
                    .fillMaxSize()
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val shift = phase.value
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = primary,
                                start = Offset(x = -w + w * shift, y = 0f),
                                end = Offset(x = w + w * shift, y = h)
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = overlay,
                                center = Offset(
                                    x = w * (0.2f + 0.6f * shift),
                                    y = h * (0.2f + 0.3f * (1f - shift))
                                ),
                                radius = max(w, h) * 0.95f
                            )
                        )
                    }
            ) {
                content()
            }
        }
    }
}
