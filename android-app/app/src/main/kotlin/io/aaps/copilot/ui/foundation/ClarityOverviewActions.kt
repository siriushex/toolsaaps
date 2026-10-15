package io.aaps.copilot.ui.foundation

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.screens.ClarityToolButton

@Composable
internal fun ClarityOverviewActions(canRunCycle: Boolean, onRunCycle: () -> Unit, onBloodCheck: () -> Unit) {
    Row {
        ClarityToolButton(Icons.Default.Refresh, stringResource(R.string.balance_recalculate),
            "overviewTopRefresh", canRunCycle, onRunCycle)
        ClarityToolButton(Icons.Default.Bloodtype, stringResource(R.string.overview_calibration_action),
            "overviewTopBloodCheck", onBloodCheck)
    }
}
