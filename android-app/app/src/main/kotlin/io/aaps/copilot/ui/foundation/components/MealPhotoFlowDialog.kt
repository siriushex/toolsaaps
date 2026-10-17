package io.aaps.copilot.ui.foundation.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.MealPhotoPreparation
import io.aaps.copilot.ui.MealPhotoPreparationResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PhotoMealSelection(val grams: Double, val profile: MealAbsorptionProfile?)

/** Foreground photo review. Only explicit user input can populate the existing meal form. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MealPhotoFlowDialog(
    maximumGrams: Double,
    availability: suspend () -> MealPhotoAvailability,
    analyze: suspend (ByteArray) -> MealPhotoAnalysisResult,
    onDismiss: () -> Unit,
    onUse: (PhotoMealSelection) -> Unit
) {
    val context = LocalContext.current
    val enlargedText = LocalDensity.current.fontScale > 1.3f
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf<MealPhotoAvailability?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var editor by remember { mutableStateOf<MealPhotoDraftEditorState?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var grams by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf(false) }
    suspend fun checkReady() {
        ready = null
        ready = try { availability() } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { MealPhotoAvailability.UNAVAILABLE }
    }
    LaunchedEffect(Unit) { checkReady() }
    DisposableEffect(Unit) {
        onDispose {
            cameraFile?.delete()
            cameraUri?.let { context.revokeUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
    fun process(uri: Uri) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= MealPhotoPreparation.MAX_INPUT_BYTES) {
                            val count = stream.read(buffer, 0, minOf(buffer.size,
                                MealPhotoPreparation.MAX_INPUT_BYTES + 1 - output.size()))
                            if (count < 0) break
                            if (count == 0) throw java.io.IOException("image_read_failed")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: return@withContext null
                    if (input.size > MealPhotoPreparation.MAX_INPUT_BYTES) null
                    else (MealPhotoPreparation.prepare(input) as? MealPhotoPreparationResult.Success)?.image
                }
                if (prepared == null) error = R.string.meal_photo_invalid_image
                else when (val result = analyze(prepared.bytes)) {
                    is MealPhotoAnalysisResult.Success -> editor = MealPhotoDraftEditor.initial(result.estimate)
                    is MealPhotoAnalysisResult.Failure -> error = when (result.reason) {
                        MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED -> R.string.meal_photo_needs_connection
                        MealPhotoAnalysisResult.Failure.Reason.UNKNOWN_OUTCOME -> R.string.meal_photo_unknown
                        MealPhotoAnalysisResult.Failure.Reason.TIMED_OUT -> R.string.meal_photo_timeout
                        MealPhotoAnalysisResult.Failure.Reason.INVALID_RESPONSE -> R.string.meal_photo_invalid_result
                        else -> R.string.meal_photo_unavailable
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = R.string.meal_photo_invalid_image
            } finally {
                busy = false
                cameraFile?.delete()
                cameraFile = null
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::process) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(::process)
        else { cameraFile?.delete(); cameraFile = null }
    }
    val dismiss = { onDismiss() }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.meal_photo_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val draft = editor
                when {
                    busy || ready == null -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(if (busy) R.string.meal_photo_analyzing else R.string.meal_photo_connecting))
                    }
                    draft != null -> {
                        MealPhotoReview(draft, { editor = it }, grams, { grams = it }, maximumGrams)
                    }
                    ready == MealPhotoAvailability.READY -> {
                        Text(stringResource(R.string.meal_photo_consent))
                        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (enlargedText) 1 else 2,
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = {
                                try {
                                    val directory = File(context.cacheDir, "meal-images").apply { mkdirs() }
                                    directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }
                                        ?.forEach { it.delete() }
                                    val file = File(directory, "meal-${UUID.randomUUID()}.jpg")
                                    cameraFile = file
                                    cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.meal-photo", file)
                                    camera.launch(checkNotNull(cameraUri))
                                } catch (_: Exception) { error = R.string.meal_photo_invalid_image }
                            }, Modifier.weight(1f).testTag("mealPhotoCamera")) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.meal_photo_camera))
                            }
                            OutlinedButton(onClick = { picker.launch("image/*") },
                                Modifier.weight(1f).testTag("mealPhotoGallery")) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.meal_photo_gallery))
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(if (ready == MealPhotoAvailability.NEEDS_CONNECTION)
                            R.string.meal_photo_needs_connection else R.string.meal_photo_unavailable))
                        TextButton(onClick = { scope.launch { checkReady() } }) {
                            Text(stringResource(R.string.meal_photo_recheck))
                        }
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("mealPhotoError")) }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            val selected = grams.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= maximumGrams }
            val draft = editor
            if (draft != null) Button(enabled = !applied && selected != null && photoCalculation(draft) != null,
                modifier = Modifier.testTag("mealPhotoUse"), onClick = {
                    if (!applied && selected != null) {
                        applied = true
                        onUse(PhotoMealSelection(selected, draft.profile))
                    }
                }) { Text(stringResource(R.string.meal_photo_use)) }
        })
}

private fun photoCalculation(state: MealPhotoDraftEditorState): MealNutritionCalculation? =
    (MealPhotoDraftEditor.build(state) as? MealPhotoDraftBuildResult.Ready)?.let {
        (MealNutritionCalculator.calculate(it.draft) as? MealNutritionCalculationResult.Calculated)?.value
    }

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MealPhotoReview(state: MealPhotoDraftEditorState, onChange: (MealPhotoDraftEditorState) -> Unit,
    grams: String, onGrams: (String) -> Unit, maximumGrams: Double) {
    Text(state.estimate.mealName, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.meal_photo_estimate_note), style = MaterialTheme.typography.bodySmall)
    state.profile?.let { profile ->
        io.aaps.copilot.ui.foundation.screens.FoodProfileSelector(profile, { selected ->
            val duration = when (selected) {
                MealAbsorptionProfile.FAST -> 45
                MealAbsorptionProfile.MIXED -> 120
                MealAbsorptionProfile.FAT_PROTEIN -> 240
            }
            (MealPhotoDraftEditor.setProfile(state, selected, duration) as? MealPhotoDraftEditResult.Updated)
                ?.let { onChange(it.state) }
        }, compact = true)
    }
    state.estimate.ingredients.forEach { ingredient ->
        var raw by remember(ingredient.id) { mutableStateOf(state.confirmedMassesGrams[ingredient.id]?.toString().orEmpty()) }
        var fraction by remember(ingredient.id) { mutableStateOf("100") }
        Text(ingredient.name, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.meal_photo_mass_range, photoRange(ingredient.massGrams)),
            style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(raw, { value ->
                raw = value
                val number = value.replace(',', '.').toDoubleOrNull()
                val result = number?.let { MealPhotoDraftEditor.setMass(state, ingredient.id, it) }
                onChange((result as? MealPhotoDraftEditResult.Updated)?.state ?: state.copy(
                    confirmedMassesGrams = state.confirmedMassesGrams + (ingredient.id to null)))
            }, label = { Text(stringResource(R.string.meal_photo_mass)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.confirmedMassesGrams[ingredient.id] == null,
                modifier = Modifier.weight(1f).testTag("mealPhotoMass_${ingredient.id}"))
            OutlinedTextField(fraction, { value ->
                fraction = value
                val number = value.replace(',', '.').toDoubleOrNull()
                val updated = number?.let { MealPhotoDraftEditor.setEatenFraction(state, ingredient.id, it / 100) }
                onChange((updated as? MealPhotoDraftEditResult.Updated)?.state ?: state.copy(
                    eatenFractions = state.eatenFractions + (ingredient.id to Double.NaN)))
            }, label = { Text(stringResource(R.string.meal_photo_eaten)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
        }
        if (ingredient.preparationState == PreparationState.UNKNOWN) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PreparationState.RAW, PreparationState.COOKED).forEach { candidate ->
                FilterChip(state.preparationStates[ingredient.id] == candidate, onClick = {
                    (MealPhotoDraftEditor.setPreparationState(state, ingredient.id, candidate) as? MealPhotoDraftEditResult.Updated)
                        ?.let { onChange(it.state) }
                }, label = { Text(stringResource(if (candidate == PreparationState.RAW) R.string.meal_photo_raw else R.string.meal_photo_cooked)) })
            }
        }
        HorizontalDivider()
    }
    val nutrition = photoCalculation(state)
    val missing = stringResource(R.string.meal_photo_unknown_value)
    fun range(value: NutritionRange?) = value?.let(::photoRange) ?: missing
    if (nutrition != null) {
        Text(stringResource(R.string.meal_photo_totals,
            range(nutrition.declaredEnergy.completeTotalKilocalories), range(nutrition.protein.completeTotalGrams),
            range(nutrition.fat.completeTotalGrams), range(nutrition.carbohydrates.completeTotalGrams)),
            modifier = Modifier.testTag("mealPhotoTotals"))
        if (nutrition.carbohydrates.completeTotalGrams == null) Text(stringResource(R.string.meal_photo_incomplete),
            color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(grams, onGrams, label = { Text(stringResource(R.string.meal_photo_confirm_carbs)) },
        supportingText = { Text(stringResource(R.string.meal_photo_carb_limit, maximumGrams.toInt())) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("mealPhotoConfirmedCarbs"))
}

private fun photoRange(range: NutritionRange): String {
    fun number(value: Double) = String.format(java.util.Locale.getDefault(), "%.1f", value)
    return if (range.minimum == range.maximum) number(range.minimum) else "${number(range.minimum)} - ${number(range.maximum)}"
}
