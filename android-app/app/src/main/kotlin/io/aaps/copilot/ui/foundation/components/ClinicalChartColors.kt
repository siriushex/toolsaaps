package io.aaps.copilot.ui.foundation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class ClinicalChartColors(val displayBand: Color, val future: Color)

internal fun clinicalChartColors(surface: Color): ClinicalChartColors =
    if (surface.luminance() < 0.5f) {
        ClinicalChartColors(displayBand = Color(0xFF16382F), future = Color(0xFFB2C8EC))
    } else {
        ClinicalChartColors(displayBand = Color(0xFFE8F5E9), future = Color(0xFF3A5278))
    }
