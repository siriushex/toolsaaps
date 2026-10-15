package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewIobValue(pair: OverviewIobPresentation, unit: String, valueStyle: TextStyle,
    unitStyle: TextStyle, modifier: Modifier = Modifier, centered: Boolean = false) {
    val density = LocalDensity.current
    val lineHeight = with(density) {
        if (valueStyle.lineHeight.isSp) valueStyle.lineHeight.toDp() else valueStyle.fontSize.toDp() * 1.5f
    }
    FlowRow(modifier.heightIn(min = lineHeight * 2),
        horizontalArrangement = if (centered) Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
            else Arrangement.spacedBy(3.dp), maxItemsInEachRow = 3) {
        Text(pair.current, style = valueStyle, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        Text("/\u00a0${pair.signedNet}", style = valueStyle, maxLines = 1, softWrap = false,
            modifier = Modifier.alignByBaseline())
        if (unit.isNotBlank()) Text(unit, style = unitStyle, maxLines = 1, softWrap = false,
            modifier = Modifier.alignByBaseline())
    }
}
