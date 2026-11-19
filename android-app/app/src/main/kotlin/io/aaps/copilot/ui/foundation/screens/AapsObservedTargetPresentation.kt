package io.aaps.copilot.ui.foundation.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.aaps.copilot.R
import io.aaps.copilot.service.AapsObservedTarget
import io.aaps.copilot.ui.foundation.format.UiFormatters

internal fun observedAapsTargetRange(target: AapsObservedTarget?): String {
    val low = UiFormatters.formatMmol(target?.lowMmol, 1)
    if (target == null) return low
    val high = UiFormatters.formatMmol(target.highMmol, 1)
    return if (low == high) low else "$low - $high"
}

@Composable
internal fun observedAapsTargetSource(target: AapsObservedTarget?, showExpiry: Boolean = true): String = when (target?.kind) {
    AapsObservedTarget.Kind.PROFILE -> stringResource(R.string.clarity_aaps_target_profile)
    AapsObservedTarget.Kind.APS_ADJUSTED -> stringResource(R.string.clarity_aaps_target_adjusted)
    AapsObservedTarget.Kind.TEMPORARY -> target.expiresAt?.takeIf { showExpiry }?.let {
        stringResource(R.string.clarity_aaps_target_temporary_until, UiFormatters.formatTimestamp(it))
    } ?: stringResource(R.string.clarity_aaps_target_temporary)
    null -> stringResource(R.string.clarity_aaps_target_unavailable)
}
