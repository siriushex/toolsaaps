package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.components.*
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealPhotoFlowRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun resetCameraProviderRoots() {
        // Each Robolectric application has a fresh cache path; AndroidX caches roots process-wide.
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache")
            .remove("${RuntimeEnvironment.getApplication().packageName}.meal-photo")
    }

    @Test fun gallerySuccessNeverRevokesTheSelectedExternalUri() {
        val registry = PhotoRegistry()
        val context = CameraContext()
        val image = syntheticPhoto()
        val controller = showPhoto(registry, contextOverride = context,
            analyze = { MealPhotoAnalysisResult.Success(estimate()) })
        try {
            selectPhoto(registry, image)
            assertTrue(context.revoked.isEmpty())
            assertTrue(image.exists())
        } finally { controller.pause().stop().destroy(); image.delete() }
        assertTrue(context.revoked.isEmpty())
    }

    @Test fun cameraSuccessReleasesOwnedImageBeforeWaitingForAnalysis() {
        val registry = PhotoRegistry()
        val context = CameraContext()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val controller = showPhoto(registry, contextOverride = context, analyze = { bytes ->
            calls++
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(16, bitmap.width)
            bitmap.recycle()
            finish.await()
            MealPhotoAnalysisResult.Success(estimate())
        })
        try {
            compose.onNodeWithTag("mealPhotoCamera").performClick()
            val uri = registry.lastInput as Uri
            val capture = writeCapture(uri)
            assertTrue(capture.exists())
            compose.runOnIdle { registry.deliver(true) }
            compose.waitUntil(10_000) { calls == 1 }
            assertFalse("Camera image must not remain while waiting for AI", capture.exists())
            assertEquals(listOf(uri to CAMERA_FLAGS), context.revoked)
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
            compose.runOnIdle { finish.complete(Unit) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mealPhotoMass_rice").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().assertIsDisplayed()
            assertEquals(1, calls)
        } finally { finish.complete(Unit); controller.pause().stop().destroy() }
        assertEquals(1, context.revoked.size)
    }

    @Test fun cameraCancellationReleasesGrantBeforeAnotherCapture() {
        val registry = PhotoRegistry()
        val context = CameraContext()
        val controller = showPhoto(registry, contextOverride = context)
        try {
            val captures = mutableListOf<Uri>()
            repeat(2) {
                compose.onNodeWithTag("mealPhotoCamera").performClick()
                val uri = registry.lastInput as Uri
                captures.add(uri)
                val file = writeCapture(uri)
                compose.runOnIdle { registry.deliver(false) }
                compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
                assertFalse(file.exists())
                assertEquals(captures.map { it to CAMERA_FLAGS }, context.revoked)
            }
            assertNotEquals(captures[0], captures[1])
        } finally { controller.pause().stop().destroy() }
        assertEquals(2, context.revoked.size)
    }

    @Test fun failedCameraLaunchReleasesOnlyItsOwnedUri() {
        val registry = PhotoRegistry().apply { failLaunch = true }
        val context = CameraContext()
        val controller = showPhoto(registry, contextOverride = context)
        try {
            compose.onNodeWithTag("mealPhotoCamera").performClick()
            val uri = registry.lastInput as Uri
            compose.onNodeWithTag("mealPhotoError").assertIsDisplayed()
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
            assertEquals(listOf(uri to CAMERA_FLAGS), context.revoked)
        } finally { controller.pause().stop().destroy() }
        assertEquals(1, context.revoked.size)
    }

    @Test fun malformedCameraImageIsReleasedWithoutAnalysis() {
        val registry = PhotoRegistry()
        val context = CameraContext()
        val controller = showPhoto(registry, contextOverride = context)
        try {
            compose.onNodeWithTag("mealPhotoCamera").performClick()
            val uri = registry.lastInput as Uri
            val capture = writeCapture(uri, malformed = true)
            compose.runOnIdle { registry.deliver(true) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mealPhotoError").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mealPhotoError").assertTextEquals(
                context.getString(R.string.meal_photo_invalid_image))
            assertFalse(capture.exists())
            assertEquals(listOf(uri to CAMERA_FLAGS), context.revoked)
        } finally { controller.pause().stop().destroy() }
        assertEquals(1, context.revoked.size)
    }

    @Test fun closingDuringCameraSelectionReleasesOwnedCaptureOnce() {
        val registry = PhotoRegistry()
        val context = CameraContext()
        val controller = showPhoto(registry, contextOverride = context)
        var uri: Uri? = null
        var capture: java.io.File? = null
        try {
            compose.onNodeWithTag("mealPhotoCamera").performClick()
            uri = registry.lastInput as Uri
            capture = writeCapture(uri)
        } finally { controller.pause().stop().destroy() }
        assertFalse(capture!!.exists())
        assertEquals(listOf(uri to CAMERA_FLAGS), context.revoked)
    }

    @Test fun cancellingRecognizedPhotoPreservesExistingFoodAndEatingSoonChoice() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val meals = mutableListOf<MealEntryConfirmation>()
        var calls = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AapsCopilotTheme(dynamicColor = false) {
                        MealEntryDialog(MealPortionSettings(showCalories = true), 80.0, {}, { meals.add(it) },
                            { MealPhotoAvailability.READY }, { calls++; MealPhotoAnalysisResult.Success(estimate()) })
                    }
                }
            }
            compose.onNodeWithTag("mealManualCarbs").performClick()
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().performTextReplacement("42")
            compose.onNodeWithTag("overviewEatingSoon").performScrollTo().performClick()
            compose.onNodeWithTag("mealProfile_FAT_PROTEIN").performScrollTo().performClick()
            compose.onNodeWithTag("overviewManualMealEnergyKcal").performScrollTo().performTextReplacement("300")
            compose.onNodeWithTag("mealPhotoOpen").performClick()
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("55")
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.action_cancel)).performClick()
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().assertTextContains("42")
            compose.onNodeWithTag("overviewEatingSoon").assertIsOff()
            compose.onNodeWithTag("overviewManualMealEnergyKcal").performScrollTo().assertTextContains("300")
            assertTrue(meals.isEmpty())
            compose.onNodeWithTag("overviewPrepareMeal").performClick()
            assertEquals(1, meals.size)
            assertEquals(42.0, meals.single().grams, 0.0)
            assertEquals(300.0, meals.single().energyKcal!!, 0.0)
            assertEquals(MealAbsorptionProfile.FAT_PROTEIN, meals.single().profile)
            assertFalse(meals.single().eatingSoon)
            assertEquals(1, calls)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    private class CameraContext : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
        val revoked = mutableListOf<Pair<Uri, Int>>()
        override fun revokeUriPermission(uri: Uri, modeFlags: Int) {
            revoked.add(uri to modeFlags)
            super.revokeUriPermission(uri, modeFlags)
        }
    }

    private fun writeCapture(uri: Uri, malformed: Boolean = false): java.io.File {
        val app = RuntimeEnvironment.getApplication()
        checkNotNull(app.contentResolver.openOutputStream(uri, "w")).use { output ->
            if (malformed) output.write("synthetic-invalid-image".toByteArray()) else {
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
                finally { bitmap.recycle() }
            }
        }
        return java.io.File(app.cacheDir, "meal-images/${uri.lastPathSegment}")
    }

    @Test fun successfulPhotoTransfersOnlyExplicitCarbsAndRequiresFinalFoodConfirmation() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val meals = mutableListOf<MealEntryConfirmation>()
        var calls = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AapsCopilotTheme(dynamicColor = false) {
                        MealEntryDialog(MealPortionSettings(), 80.0, {}, { meals.add(it) },
                            { MealPhotoAvailability.READY }, {
                                calls++
                                assertTrue(it.isNotEmpty())
                                MealPhotoAnalysisResult.Success(estimate().copy(
                                    suggestedProfile = MealAbsorptionProfile.FAST, suggestedDurationMinutes = 45))
                            })
                    }
                }
            }
            compose.onNodeWithTag("overviewEatingSoon").performScrollTo().performClick()
            compose.onNodeWithTag("mealPhotoOpen").performClick()
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
            assertEquals("", compose.onNodeWithTag("mealPhotoMass_rice").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_eaten))
                .performScrollTo().performTextReplacement("50")
            compose.onNodeWithTag("mealPhotoTotals").performScrollTo().assertTextContains("25,0 - 30,0", substring = true)
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo()
                .performTextReplacement("27,5")
            val use = compose.onNodeWithTag("mealPhotoUse").assertIsEnabled().fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            compose.runOnIdle { use(); use() }
            assertTrue(meals.isEmpty())
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().assertTextContains("27.5")
            compose.onNodeWithTag("overviewEatingSoon").assertIsOff()
            val confirm = compose.onNodeWithTag("overviewPrepareMeal").assertIsEnabled().fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            compose.runOnIdle { confirm(); confirm() }
            assertEquals(1, meals.size)
            assertEquals(27.5, meals.single().grams, 0.0)
            assertEquals(MealAbsorptionProfile.FAST, meals.single().profile)
            assertFalse(meals.single().eatingSoon)
            assertEquals(1, calls)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun photoCarbsBelowFoodMinimumCannotLeaveReview() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val controller = showPhoto(registry, analyze = { MealPhotoAnalysisResult.Success(estimate()) })
        try {
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("0,5")
            compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").assert(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun invalidEatenPercentageIsExplainedAndCorrectable() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val controller = showPhoto(registry, analyze = { MealPhotoAnalysisResult.Success(estimate()) })
        try {
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("30")
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_eaten))
                .performScrollTo().performTextReplacement("101")
            compose.onNodeWithTag("mealPhotoEaten_rice").assert(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
            compose.onNodeWithTag("mealPhotoEatenError_rice", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
            compose.onNodeWithTag("mealPhotoTotals").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoEaten_rice").performScrollTo().performTextReplacement("50")
            compose.onNodeWithTag("mealPhotoEatenError_rice", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoUse").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoTotals").performScrollTo().assertTextContains("25,0 - 30,0", substring = true)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun massOutsideModelRangeIsExplainedWithoutRelaxingTheRange() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val controller = showPhoto(registry, analyze = { MealPhotoAnalysisResult.Success(estimate()) })
        try {
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("230")
            compose.onNodeWithTag("mealPhotoMassError_rice", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mealPhotoTotals").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
            compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().performTextReplacement("220")
            compose.onNodeWithTag("mealPhotoMassError_rice", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoTotals").performScrollTo().assertIsDisplayed()
            saveEvidence("meal-photo-confirmation", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun explicitCarbsBoundsAndInvalidNumbersRecoverWithoutAnotherAnalysis() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        var calls = 0
        val controller = showPhoto(registry, analyze = { calls++; MealPhotoAnalysisResult.Success(estimate()) })
        try {
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            listOf("0", "-1", "80,1", "NaN", "Infinity", "abc").forEach { value ->
                compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement(value)
                compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
                compose.onNodeWithTag("mealPhotoConfirmedCarbs").assert(
                    SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
            }
            listOf("1", "80", "27,5").forEach { value ->
                compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement(value)
                compose.onNodeWithTag("mealPhotoUse").assertIsEnabled()
                compose.onNodeWithTag("mealPhotoConfirmedCarbs").assert(
                    SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
            }
            assertEquals(1, calls)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun enlargedReviewErrorsAndCorrectionsRemainReachable() {
        val app = RuntimeEnvironment.getApplication()
        val before = android.content.res.Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(android.content.res.Configuration(before).apply { fontScale = 1.8f },
            app.resources.displayMetrics)
        val image = syntheticPhoto()
        var controller: ActivityController<ComponentActivity>? = null
        try {
            val registry = PhotoRegistry()
            controller = showPhoto(registry, analyze = { MealPhotoAnalysisResult.Success(estimate()) })
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("230")
            compose.onNodeWithTag("mealPhotoMassError_rice", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            saveEvidence("meal-photo-mass-error-large", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoEaten_rice").performScrollTo().performTextReplacement("-1")
            compose.onNodeWithTag("mealPhotoEatenError_rice", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            saveEvidence("meal-photo-eaten-error-large", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithTag("mealPhotoEaten_rice").performScrollTo().performTextReplacement("50")
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("81")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(app.getString(R.string.meal_photo_confirm_carbs), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            compose.onNodeWithTag("mealPhotoCarbsHint", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            saveEvidence("meal-photo-carbs-error-large", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("27,5")
            compose.onNodeWithTag("mealPhotoUse").assertIsEnabled()
        } finally {
            controller?.pause()?.stop()?.destroy(); image.delete()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(before, app.resources.displayMetrics)
        }
    }

    private fun selectPhoto(registry: PhotoRegistry, image: java.io.File) {
        compose.onNodeWithTag("mealPhotoGallery").performClick()
        compose.runOnIdle { registry.deliver(Uri.fromFile(image)) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("mealPhotoMass_rice").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().assertIsDisplayed()
    }

    @Test fun galleryLaunchFailureIsRecoverableAndDoesNotAnalyze() {
        val registry = PhotoRegistry().apply { failLaunch = true }
        val controller = showPhoto(registry)
        try {
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.onNodeWithTag("mealPhotoError").assertIsDisplayed()
            assertFalse(compose.onNodeWithTag("mealPhotoError").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Text].any {
                it.text.contains("synthetic launch detail")
            })
            compose.onNodeWithTag("mealPhotoCamera").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
            assertEquals(1, registry.launches)
            saveEvidence("meal-photo-open-failure", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cameraLaunchFailureIsNotAnInvalidImageAndKeepsGalleryAvailable() {
        val registry = PhotoRegistry().apply { failLaunch = true }
        val controller = showPhoto(registry)
        try {
            compose.onNodeWithTag("mealPhotoCamera").performClick()
            val invalidImage = RuntimeEnvironment.getApplication().getString(R.string.meal_photo_invalid_image)
            compose.onNodeWithTag("mealPhotoError").assertIsDisplayed()
            compose.onNodeWithText(invalidImage).assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun oneSelectionDisablesBothSourcesUntilGalleryCancellation() {
        val registry = PhotoRegistry()
        val controller = showPhoto(registry)
        try {
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.onNodeWithTag("mealPhotoCamera").assertIsNotEnabled()
            compose.onNodeWithTag("mealPhotoGallery").assertIsNotEnabled()
            assertEquals(1, registry.launches)
            compose.runOnIdle { registry.deliver<Uri?>(null) }
            compose.onNodeWithTag("mealPhotoCamera").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoError").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cameraCancellationReleasesSelectionWithoutAnalysis() {
        val registry = PhotoRegistry()
        val controller = showPhoto(registry)
        try {
            compose.onNodeWithTag("mealPhotoCamera").performClick()
            compose.onNodeWithTag("mealPhotoGallery").assertIsNotEnabled()
            compose.runOnIdle { registry.deliver(false) }
            compose.onNodeWithTag("mealPhotoCamera").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoError").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rapidSourceActionsCannotLaunchAgainBeforeRecomposition() {
        val registry = PhotoRegistry()
        val controller = showPhoto(registry)
        try {
            val gallery = compose.onNodeWithTag("mealPhotoGallery").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            val camera = compose.onNodeWithTag("mealPhotoCamera").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            compose.runOnIdle { gallery(); camera(); gallery() }
            assertEquals(1, registry.launches)
            compose.runOnIdle { registry.deliver<Uri?>(null) }
            compose.onNodeWithTag("mealPhotoCamera").assertIsEnabled()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun enlargedLaunchErrorRemainsReachableWithoutAnalysis() {
        val app = RuntimeEnvironment.getApplication()
        val before = android.content.res.Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(android.content.res.Configuration(before).apply { fontScale = 1.8f },
            app.resources.displayMetrics)
        var controller: ActivityController<ComponentActivity>? = null
        try {
            controller = showPhoto(PhotoRegistry().apply { failLaunch = true })
            compose.onNodeWithTag("mealPhotoGallery").performScrollTo().performClick()
            compose.onNodeWithTag("mealPhotoError").performScrollTo().assertIsDisplayed()
                .assertTextEquals(app.getString(R.string.meal_photo_open_failed))
            saveEvidence("meal-photo-open-failure-large", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithTag("mealPhotoCamera").performScrollTo().assertIsEnabled()
        } finally {
            controller?.pause()?.stop()?.destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(before, app.resources.displayMetrics)
        }
    }

    @Test fun authorizationLossAfterSelectionRequiresRecheckWithoutReupload() {
        assertReadinessRecovery(MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED,
            R.string.meal_photo_needs_connection)
    }

    @Test fun serverRefusalAfterSelectionRequiresRecheckWithoutReupload() {
        assertReadinessRecovery(MealPhotoAnalysisResult.Failure.Reason.UNAVAILABLE,
            R.string.meal_photo_unavailable)
    }

    @Test fun networkFailureIsNotReportedAsUnsupportedPhotoAnalysis() {
        assertAnalysisError(MealPhotoAnalysisResult.Failure.Reason.NETWORK, R.string.meal_photo_exchange_failed)
    }

    @Test fun busyAnalysisDoesNotLookLikeMissingPhotoSupport() {
        assertAnalysisError(MealPhotoAnalysisResult.Failure.Reason.BUSY, R.string.meal_photo_busy)
    }

    @Test fun rejectedImageDoesNotLookLikeMissingPhotoSupport() {
        assertAnalysisError(MealPhotoAnalysisResult.Failure.Reason.INVALID_REQUEST, R.string.meal_photo_invalid_request)
    }

    @Test fun failedJobKeepsNewPhotoSelectionAvailable() {
        assertTerminalRecovery(ServerAiJobState.FAILED,
            "Не удалось завершить анализ фото. Выберите снимок ещё раз или введите еду вручную.")
    }

    @Test fun cancelledJobKeepsNewPhotoSelectionAvailable() {
        assertTerminalRecovery(ServerAiJobState.CANCELLED, "Анализ отменён. Можно выбрать новое фото.")
    }

    @Test fun expiredJobShowsTimeoutAndKeepsNewPhotoSelectionAvailable() {
        assertTerminalRecovery(ServerAiJobState.EXPIRED,
            RuntimeEnvironment.getApplication().getString(R.string.meal_photo_timeout))
    }

    @Test fun unavailableResultDoesNotImplyUnavailableServer() {
        assertTerminalRecovery(ServerAiJobState.SUCCEEDED,
            "Результат больше недоступен. Выберите снимок ещё раз или введите еду вручную.")
    }

    private fun assertTerminalRecovery(state: ServerAiJobState, message: String) {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val now = 1_800_000_000_000L
        var sends = 0
        val jobs = object : MealPhotoJobs {
            override suspend fun ready() = true
            override fun prepare(request: MealPhotoGatewayRequest, deadlineMs: Long) =
                ServerMealPhotoProtocol.prepare(request, now, deadlineMs)
            override suspend fun submit(submission: MealPhotoSubmission): MealPhotoJobSnapshot {
                sends++
                return MealPhotoJobSnapshot(java.util.UUID.randomUUID().toString(), submission.requestId,
                    state, now, submission.deadlineMs, now + 1, now + 2, false, null, null)
            }
            override suspend fun status(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot =
                error("A terminal job must not be polled")
            override suspend fun cancel(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot =
                error("A terminal job must not be cancelled again")
        }
        val gateway = ServerMealPhotoGateway(jobs, { true }, { now }, {})
        val repository = MealPhotoAnalysisRepository(gateway)
        val controller = showPhoto(registry, availability = gateway::availability,
            analyze = { repository.analyze(it) })
        try {
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.runOnIdle { registry.deliver(Uri.fromFile(image)) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mealPhotoError").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(RuntimeEnvironment.getApplication()
                        .getString(R.string.meal_photo_unavailable)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mealPhotoError").assertIsDisplayed().assertTextEquals(message)
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoCamera").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_recheck))
                .assertDoesNotExist()
            compose.waitForIdle()
            assertEquals(1, sends)
            if (state == ServerAiJobState.FAILED)
                saveEvidence("meal-photo-analysis-failed", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.runOnIdle { registry.deliver(Uri.fromFile(image)) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mealPhotoError").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, sends)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun unusableNutritionShowsAnErrorAndRequiresExplicitNewSelection() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        var calls = 0
        val repository = MealPhotoAnalysisRepository(MealPhotoGateway {
            calls++
            """{
                "schemaVersion": 1, "estimateId": "review-estimate", "mealName": "Rice",
                "ingredients": [{
                    "id": "rice", "name": "Rice", "massGrams": {"min": 180, "max": 220},
                    "preparationState": "COOKED", "carbohydrateBasis": "TOTAL",
                    "carbohydrates": {"min": 25, "max": ${if (calls == 1) 134 else 30}},
                    "protein": null, "fat": null, "fiber": null, "sugar": null,
                    "polyols": null, "energy": null
                }], "suggestedProfile": null, "suggestedDurationMinutes": null
            }""".trimIndent()
        })
        val controller = showPhoto(registry, analyze = { bytes ->
            repository.analyze(java.util.UUID.randomUUID().toString(), bytes)
        })
        try {
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.runOnIdle { registry.deliver(Uri.fromFile(image)) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mealPhotoError").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("mealPhotoMass_rice").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mealPhotoError").assertIsDisplayed().assertTextEquals(
                RuntimeEnvironment.getApplication().getString(R.string.meal_photo_invalid_result))
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
            compose.waitForIdle()
            assertEquals(1, calls)

            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoError").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("50")
            compose.onNodeWithTag("mealPhotoUse").assertIsEnabled()
            assertEquals(2, calls)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    @Test fun massBelowCalculatorMinimumHasACorrectableFieldError() {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        val wideEstimate = estimate().copy(ingredients = estimate().ingredients.map {
            it.copy(massGrams = NutritionRange(0.0, 220.0))
        })
        val controller = showPhoto(registry, analyze = { MealPhotoAnalysisResult.Success(wideEstimate) })
        try {
            selectPhoto(registry, image)
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("0.05")
            compose.onNodeWithTag("mealPhotoMassError_rice", useUnmergedTree = true)
                .assertExists().assertTextContains("0,1", substring = true)
            compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo().performTextReplacement("1")
            compose.onNodeWithTag("mealPhotoUse").assertIsNotEnabled()
            compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().performTextReplacement("0.1")
            compose.onNodeWithTag("mealPhotoMassError_rice", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoUse").assertIsEnabled()
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    private fun assertAnalysisError(reason: MealPhotoAnalysisResult.Failure.Reason, message: Int) {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        var calls = 0
        val controller = showPhoto(registry, analyze = {
            calls++
            MealPhotoAnalysisResult.Failure(reason)
        })
        try {
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.runOnIdle { registry.deliver(Uri.fromFile(image)) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mealPhotoError").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mealPhotoError").assertIsDisplayed()
                .assertTextEquals(RuntimeEnvironment.getApplication().getString(message))
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_unavailable))
                .assertDoesNotExist()
            assertEquals(1, calls)
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
            if (reason == MealPhotoAnalysisResult.Failure.Reason.NETWORK)
                saveEvidence("meal-photo-exchange-failure", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    private fun assertReadinessRecovery(reason: MealPhotoAnalysisResult.Failure.Reason, message: Int) {
        val registry = PhotoRegistry()
        val image = syntheticPhoto()
        var analyses = 0
        var readiness = 0
        val controller = showPhoto(registry, availability = { readiness++; MealPhotoAvailability.READY },
            analyze = { analyses++; MealPhotoAnalysisResult.Failure(reason) })
        try {
            compose.onNodeWithTag("mealPhotoGallery").performClick()
            compose.runOnIdle { registry.deliver(Uri.fromFile(image)) }
            val text = RuntimeEnvironment.getApplication().getString(message)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(text).assertCountEquals(1)
            compose.onNodeWithTag("mealPhotoCamera").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoGallery").assertDoesNotExist()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_recheck))
                .performClick()
            compose.onNodeWithTag("mealPhotoGallery").assertIsEnabled()
            compose.onNodeWithTag("mealPhotoCamera").assertIsEnabled()
            assertEquals(2, readiness)
            assertEquals(1, analyses)
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
        } finally { controller.pause().stop().destroy(); image.delete() }
    }

    private fun showPhoto(
        registry: PhotoRegistry,
        availability: suspend () -> MealPhotoAvailability = { MealPhotoAvailability.READY },
        contextOverride: android.content.Context? = null,
        analyze: suspend (ByteArray) -> MealPhotoAnalysisResult = { error("No image may be analyzed") }
    ): ActivityController<ComponentActivity> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        controller.get().setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,
                androidx.compose.ui.platform.LocalContext provides (contextOverride ?: androidx.compose.ui.platform.LocalContext.current)) {
                AapsCopilotTheme(dynamicColor = false) {
                    MealPhotoFlowDialog(80.0, availability, analyze, {}, { error("No meal may be applied") })
                }
            }
        }
        compose.onNodeWithTag("mealPhotoGallery").performScrollTo().assertIsDisplayed()
        return controller
    }

    private class PhotoRegistry : ActivityResultRegistry() {
        var failLaunch = false
        var launches = 0
        var lastInput: Any? = null
            private set
        private var pendingCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            launches++
            lastInput = input
            if (failLaunch) throw ActivityNotFoundException("synthetic launch detail")
            pendingCode = requestCode
        }
        fun <O> deliver(value: O) {
            dispatchResult(checkNotNull(pendingCode), value)
            pendingCode = null
        }
    }

    private fun syntheticPhoto(): java.io.File {
        val image = java.io.File.createTempFile("selection-fixture-", ".jpg", RuntimeEnvironment.getApplication().cacheDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        return image
    }

    @Test fun enlargedPhotoChoicesRemainReachableWithoutUploading() {
        val app = RuntimeEnvironment.getApplication()
        val before = android.content.res.Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(android.content.res.Configuration(before).apply { fontScale = 1.8f },
            app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    MealPhotoFlowDialog(80.0, { MealPhotoAvailability.READY },
                        { error("Visual test must not upload") }, {}, { error("No meal may be applied") })
                }
            }
            val camera = compose.onNodeWithTag("mealPhotoCamera").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val gallery = compose.onNodeWithTag("mealPhotoGallery").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(gallery.top >= camera.bottom)
            saveEvidence("meal-photo-large", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(before, app.resources.displayMetrics)
        }
    }

    @Test fun unconfiguredServerDoesNotOfferImageOrRunAnalysis() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    MealPhotoFlowDialog(80.0, { MealPhotoAvailability.NEEDS_CONNECTION },
                        { error("Unconfigured route must never analyze") }, {}, { error("No meal may be applied") })
                }
            }
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_needs_connection))
                .assertIsDisplayed()
            compose.onNodeWithTag("mealPhotoCamera").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoGallery").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
            saveEvidence("meal-photo-unconfigured", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun openingAndCancellingPhotoKeepsManualFoodAndNeverSubmitsIt() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val meals = mutableListOf<MealEntryConfirmation>()
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    MealEntryDialog(MealPortionSettings(), 80.0, {}, { meals.add(it) },
                        { MealPhotoAvailability.UNSUPPORTED }, { error("Text-only route must not analyze") })
                }
            }
            compose.onNodeWithTag("mealManualCarbs").performClick()
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().performTextReplacement("42")
            compose.onNodeWithTag("mealPhotoOpen").performClick()
            compose.onNodeWithTag("mealPhotoCamera").assertDoesNotExist()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.action_cancel)).performClick()
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().assertTextContains("42")
            assertTrue(meals.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun reviewRequiresMassAndScalesPerHundredGramFactsAndEatenFraction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var latest = MealPhotoDraftEditor.initial(estimate())
        try {
            controller.get().setContent {
                var state by remember { mutableStateOf(latest) }
                AapsCopilotTheme(dynamicColor = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MealPhotoReview(state, { state = it; latest = it }, "", {}, 80.0)
                    }
                }
            }
            compose.onNodeWithTag("mealPhotoTotals").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoTotals").performScrollTo().assertIsDisplayed()
            fun carbs() = ((MealNutritionCalculator.calculate(
                (MealPhotoDraftEditor.build(latest) as MealPhotoDraftBuildResult.Ready).draft)
                as MealNutritionCalculationResult.Calculated).value.carbohydrates.completeTotalGrams)!!
            assertEquals(50.0, carbs().minimum, 0.0)
            assertEquals(60.0, carbs().maximum, 0.0)
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_eaten))
                .performScrollTo().performTextReplacement("50")
            assertEquals(25.0, carbs().minimum, 0.0)
            saveEvidence("meal-photo-review", controller.get().window.decorView)
            assertEquals("", compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo()
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().performTextReplacement("999")
            compose.onNodeWithTag("mealPhotoTotals").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    private fun saveEvidence(name: String, view: android.view.View) {
        val directory = System.getenv("MEAL_UI_EVIDENCE_DIR") ?: return
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun estimate() = MealPhotoNutritionEstimate(1, "synthetic-meal", "Rice",
        listOf(MealPhotoIngredientEstimate("rice", "Rice", NutritionRange(180.0, 220.0),
            PreparationState.COOKED, CarbohydrateBasis.TOTAL, NutritionRange(25.0, 30.0),
            NutritionRange(2.0, 3.0), NutritionRange(1.0, 2.0), NutritionRange(0.0, 1.0),
            NutritionRange(1.0, 2.0), null, PhotoEnergyEstimate(NutritionRange(100.0, 120.0), EnergyUnit.KCAL))),
        MealAbsorptionProfile.MIXED, 120)

    private companion object {
        const val CAMERA_FLAGS = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
