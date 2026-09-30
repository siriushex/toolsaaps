package io.aaps.copilot.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.components.clinicalChartColors
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class MidnightContrastRegressionTest {
    private val root = Path.of("src/main/kotlin/io/aaps/copilot/ui/foundation")

    @Test
    fun midnightBackgroundUsesTheSamePaletteAsItsText() {
        val source = readSource("theme/CopilotTheme.kt")
        val background = source.substringAfter("fun CopilotStyledBackground(")
            .substringAfter("UiStyle.MIDNIGHT_GLASS ->")
        assertThat(background).contains("MaterialTheme.colorScheme.background")
        assertThat(background).doesNotContain("0xFFF4F8FC")
    }

    @Test
    fun transparentScaffoldAndCustomSettingsCardProvideContentColor() {
        val scaffold = readSource("CopilotFoundationRoot.kt")
            .substringAfter("Scaffold(").substringBefore("topBar =")
        assertThat(scaffold).contains("contentColor = MaterialTheme.colorScheme.onBackground")
        val settings = readSource("screens/SettingsScreen.kt")
            .substringAfter("private fun SettingsSectionCard(").substringBefore("private fun SettingsSectionLabel(")
        assertThat(settings).contains("contentColor = MaterialTheme.colorScheme.onSurface")
    }

    @Test
    fun warningsDoNotRelyOnTheBackgroundBehindThem() {
        val overview = readSource("screens/OverviewScreen.kt")
        val activity = overview.substringAfter("private fun EnergyActivityStatusRow(")
            .substringBefore("@Composable")
        val warning = overview.substringAfter("private fun OverviewWarningBanner(")
            .substringBefore("internal fun resolveOverviewBannerKind")
        for (source in listOf(activity, warning)) {
            assertThat(source).contains(".compositeOver(MaterialTheme.colorScheme.surface)")
            assertThat(source).contains("contentColor = MaterialTheme.colorScheme.onSurface")
        }
        val safety = readSource("components/FoundationComponents.kt")
            .substringAfter("fun SafetyBanner(").substringBefore("@Composable")
        assertThat(safety).contains(".compositeOver(MaterialTheme.colorScheme.surface)")
        assertThat(safety).contains("contentColor = MaterialTheme.colorScheme.onSurface")
    }

    @Test
    fun chartBandAdaptsToItsSurfaceInsteadOfHidingWhiteHistory() {
        val source = readSource("components/ClinicalForecastChart.kt")
        assertThat(source).doesNotContain("Color(0xFFE8F5E9).copy(alpha = 0.82f)")
        assertThat(source).contains("clinicalChartColors(MaterialTheme.colorScheme.surface)")
    }

    @Test
    fun chartLinesRemainReadableInsideBandAndConfidenceOverlayInBothThemes() {
        for ((surface, history, overlay) in listOf(
            Triple(Color(0xFF0C1730), Color(0xFFF8FAFC), Color(0xFF5CA9FF)),
            Triple(Color(0xFFFFFFFF), Color(0xFF101828), Color(0xFF2563EB))
        )) {
            val colors = clinicalChartColors(surface)
            for (background in listOf(surface, colors.displayBand,
                overlay.copy(alpha = 0.14f).compositeOver(colors.displayBand))) {
                assertThat(contrast(history, background)).isAtLeast(3.0)
                assertThat(contrast(colors.future, background)).isAtLeast(3.0)
            }
        }
    }

    @Test
    fun midnightSemanticTextPairsMeetNormalTextContrast() {
        val field = Class.forName("io.aaps.copilot.ui.foundation.theme.CopilotThemeKt")
            .getDeclaredField("MidnightGlassDarkColors")
        field.isAccessible = true
        val colors = field.get(null) as ColorScheme
        val pairs = listOf(
            colors.onBackground to colors.background,
            colors.onSurface to colors.surface,
            colors.onSurfaceVariant to colors.surfaceVariant,
            colors.onPrimary to colors.primary,
            colors.onError to colors.error
        )
        for ((text, surface) in pairs) {
            assertThat(contrast(text, surface)).isAtLeast(4.5)
        }
    }

    private fun readSource(path: String) = String(Files.readAllBytes(root.resolve(path)))

    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
