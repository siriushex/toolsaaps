package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class AiAnalysisClinicalReportContrastTest {

    @Test
    fun periodHeadingUsesExplicitSurfaceContrast() {
        val source = String(
            Files.readAllBytes(
                Path.of(
                "src/main/kotlin/io/aaps/copilot/ui/foundation/screens/" +
                    "AiAnalysisScreen.kt"
                )
            )
        )
        val heading = Regex(
            """Text\(\s*text = periodLabel,\s*""" +
                """style = MaterialTheme\.typography\.titleSmall,\s*""" +
                """color = MaterialTheme\.colorScheme\.onSurface\s*\)"""
        )

        assertThat(heading.containsMatchIn(source)).isTrue()
    }
}
