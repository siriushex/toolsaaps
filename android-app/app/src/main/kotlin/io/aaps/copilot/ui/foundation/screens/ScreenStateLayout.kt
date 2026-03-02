package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.SafetyBanner
import io.aaps.copilot.ui.foundation.components.SafetyBannerType
import io.aaps.copilot.ui.foundation.design.Spacing

@Composable
fun ScreenStateLayout(
    loadState: ScreenLoadState,
    isStale: Boolean,
    errorText: String?,
    emptyText: String,
    content: @Composable () -> Unit
) {
    when (loadState) {
        ScreenLoadState.LOADING -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    CircularProgressIndicator()
                    Text(text = stringResource(id = R.string.state_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ScreenLoadState.EMPTY -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = emptyText, style = MaterialTheme.typography.bodyLarge)
            }
        }
        ScreenLoadState.ERROR -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SafetyBanner(
                    type = SafetyBannerType.ERROR,
                    text = errorText ?: stringResource(id = R.string.state_error_generic)
                )
            }
        }
        ScreenLoadState.READY -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (isStale) {
                    SafetyBanner(
                        type = SafetyBannerType.WARNING,
                        text = stringResource(id = R.string.state_stale)
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    content()
                }
            }
        }
    }
}
