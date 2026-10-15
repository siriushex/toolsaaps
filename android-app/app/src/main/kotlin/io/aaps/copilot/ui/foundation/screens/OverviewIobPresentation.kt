package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.ui.foundation.format.UiFormatters

internal data class OverviewIobPresentation(val current: String, val signedNet: String) {
    // Keep the separator with the signed total when the pair wraps.
    val value: String get() = "$current /\u00a0$signedNet"
}

internal fun overviewIobPresentation(current: Double?, signedNet: Double?) =
    OverviewIobPresentation(
        UiFormatters.formatUnits(current?.takeIf(Double::isFinite), 1),
        UiFormatters.formatUnits(signedNet?.takeIf(Double::isFinite), 1)
    )
