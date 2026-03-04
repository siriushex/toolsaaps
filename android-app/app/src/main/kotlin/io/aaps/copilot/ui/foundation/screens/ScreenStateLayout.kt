package io.aaps.copilot.ui.foundation.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(id = R.string.state_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = Spacing.sm)
                )
                repeat(5) {
                    SkeletonCardPlaceholder()
                }
                SkeletonChartPlaceholder()
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
                        text = stringResource(id = R.string.status_stale_data)
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

@Composable
private fun SkeletonCardPlaceholder() {
    val alpha = rememberShimmerAlpha()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            SkeletonLine(widthFraction = 0.35f, alpha = alpha)
            SkeletonLine(widthFraction = 0.5f, alpha = alpha)
            SkeletonLine(widthFraction = 0.25f, alpha = alpha)
        }
    }
}

@Composable
private fun SkeletonChartPlaceholder() {
    val alpha = rememberShimmerAlpha()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
    }
}

@Composable
private fun SkeletonLine(widthFraction: Float, alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(16.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                shape = MaterialTheme.shapes.small
            )
    )
}

@Composable
private fun rememberShimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    ).value
}
