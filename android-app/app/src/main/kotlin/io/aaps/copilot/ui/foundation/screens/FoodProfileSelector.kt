package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.profile.MealAbsorptionProfile

@Composable
fun FoodProfileSelector(
    selected: MealAbsorptionProfile,
    onSelected: (MealAbsorptionProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FoodProfileOption.entries.forEach { option ->
            val checked = selected == option.profile
            val description = stringResource(option.contentDescriptionRes)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
                        .selectable(
                            selected = checked,
                            onClick = { onSelected(option.profile) },
                            role = Role.RadioButton
                        )
                        .semantics {
                            contentDescription = description
                        },
                    color = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = BorderStroke(
                        if (checked) 2.dp else 1.dp,
                        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Image(
                        painter = painterResource(option.drawableRes),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(56.dp)
                    )
                }
                Text(
                    text = stringResource(option.labelRes),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(option.durationRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private enum class FoodProfileOption(
    val profile: MealAbsorptionProfile,
    val drawableRes: Int,
    val labelRes: Int,
    val durationRes: Int,
    val contentDescriptionRes: Int
) {
    FAST(
        MealAbsorptionProfile.FAST,
        R.drawable.food_profile_fast,
        R.string.food_profile_fast_label,
        R.string.food_profile_fast_duration,
        R.string.food_profile_fast_content_description
    ),
    MIXED(
        MealAbsorptionProfile.MIXED,
        R.drawable.food_profile_mixed,
        R.string.food_profile_mixed_label,
        R.string.food_profile_mixed_duration,
        R.string.food_profile_mixed_content_description
    ),
    FAT_PROTEIN(
        MealAbsorptionProfile.FAT_PROTEIN,
        R.drawable.food_profile_slow,
        R.string.food_profile_slow_label,
        R.string.food_profile_slow_duration,
        R.string.food_profile_slow_content_description
    )
}
