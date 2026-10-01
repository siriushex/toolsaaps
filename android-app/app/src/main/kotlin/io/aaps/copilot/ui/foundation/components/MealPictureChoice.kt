package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MealPictureChoice(
    selected: Boolean, label: String, image: Int, tag: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, description: String = label,
    horizontal: Boolean = false
) {
    Surface(
        modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag).semantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        if (horizontal) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealChoiceImage(image, selected, Modifier.size(64.dp))
                Text(label, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, letterSpacing = 0.sp)
            }
        } else {
            Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MealChoiceImage(image, selected, Modifier.fillMaxWidth().height(64.dp))
                Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 0.sp, minLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MealChoiceImage(image: Int, selected: Boolean, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(image), contentDescription = null,
            modifier = Modifier.size(64.dp), contentScale = ContentScale.Fit)
        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).size(16.dp))
    }
}
