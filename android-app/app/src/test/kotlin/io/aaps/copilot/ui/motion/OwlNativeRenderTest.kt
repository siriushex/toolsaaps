package io.aaps.copilot.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, qualifiers="w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwlNativeRenderTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun modelCompletionMovesTheOwlAndBurgerThenReturnsToRest() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        try {
            controller.get().setContent {
                ProvideCopilotMotion(enabled = true) {
                    Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
                        OwlMealCompletion(OwlColorBand.GREEN, "meal-1")
                    }
                }
            }
            compose.waitForIdle()
            val initial = draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(240)
            compose.waitForIdle()
            val moving = draw(controller.get().window.decorView)
            assertFalse("finite eating gesture must change raster pixels", initial.sameAs(moving))
            compose.mainClock.advanceTimeBy(3_000)
            compose.waitForIdle()
            val resting = draw(controller.get().window.decorView)
            assertTrue("completed gesture must return to rest", initial.sameAs(resting))
            System.getenv("CLARITY_UI_EVIDENCE_DIR")?.let { dir ->
                File(dir, "onset-owl-moving.png").outputStream().use { moving.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, "onset-owl-rest.png").outputStream().use { resting.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeFamilyIsNonblankInBothThemesAndAllOpticalSizes() {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var dark by mutableStateOf(false)
        try {
            controller.get().setContent {
                ProvideCopilotMotion(enabled=false) {
                    Column(Modifier.fillMaxSize().background(if(dark)Color(0xFF1B2426) else Color(0xFFF6F8F8)).padding(8.dp),
                        verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(24,32,64,128).forEach { px -> CopilotOwl(size=px.dp,modifier=Modifier.testTag("owl-$px")) }
                        }
                        Row { OwlColorBand.entries.forEach { band -> CopilotOwl(size=40.dp,band=band) } }
                        ClinicalGlyphData.byId.keys.chunked(8).forEach { row ->
                            Row { row.forEach { id -> ClinicalGlyph(id,size=36.dp) } }
                        }
                    }
                }
            }
            listOf(false,true).forEach { theme ->
                compose.runOnIdle { dark=theme }
                listOf(24,32,64,128).forEach { px -> compose.onNodeWithTag("owl-$px").assertIsDisplayed() }
                val image=draw(controller.get().window.decorView)
                val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                val colorful=pixels.count { pixel -> val r=(pixel shr 16)and 255;val g=(pixel shr 8)and 255;val b=pixel and 255
                    maxOf(r,g,b)-minOf(r,g,b)>40 }
                assertTrue("native vectors must contain colored raster pixels",colorful>500)
                System.getenv("FEATHER_UI_EVIDENCE_DIR")?.let { dir ->
                    val file=File(dir,"native-family-${if(theme)"dark" else "light"}.png");checkNotNull(file.parentFile).mkdirs()
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun finiteClockStopsAndDisablingMotionResetsWithoutReplay() {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance=false
        var enabled by mutableStateOf(true)
        var observed=0L
        var observedGate=true
        try {
            controller.get().setContent {
                ProvideCopilotMotion(enabled) {
                    observedGate=LocalCopilotMotionEnabled.current
                    val clock=rememberFiniteMotionClock(560,"finite-test",true)
                    observed=clock.value
                    Text(observed.toString(),Modifier.testTag("clock"))
                }
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertEquals(560L,observed)
            compose.mainClock.advanceTimeBy(5_000);compose.waitForIdle();assertEquals(560L,observed)
            compose.runOnIdle { enabled=false }
            // Flush snapshot invalidation before advancing a manually controlled frame clock.
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
            assertFalse("the disabled provider must have been committed",observedGate)
            assertEquals(0L,observed)
            compose.runOnIdle { enabled=true };compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000);compose.waitForIdle()
            assertTrue("the enabled provider must have been committed",observedGate)
            assertEquals("changing preference must not replay a consumed gesture",0L,observed)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lifecycleStopCancelsFramesAndResumeDoesNotReplay() {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance=false
        var observed=0L
        try {
            controller.get().setContent {
                val clock=rememberFiniteMotionClock(8_000,"lifecycle-test",true)
                observed=clock.value
                Text(observed.toString())
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400);compose.waitForIdle();assertTrue(observed>0)
            controller.pause().stop()
            compose.mainClock.advanceTimeBy(10_000)
            controller.start().resume().visible()
            compose.mainClock.advanceTimeBy(1_000);compose.waitForIdle()
            assertEquals(0L,observed)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun waitingStaysAccessibleWhenNativeAnimationFreezesOrIsDisabled() {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance=false
        var enabled by mutableStateOf(true)
        val progress=SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo.Indeterminate)
        try {
            controller.get().setContent {
                ProvideCopilotMotion(enabled) {
                    Column(Modifier.fillMaxSize().background(Color.White)) {
                        CopilotOwl(size=128.dp,gesture=OwlGesture.WAITING,
                            modifier=Modifier.testTag("waiting-owl"))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("waiting-owl").assert(progress)
            compose.mainClock.advanceTimeBy(160);compose.waitForIdle()
            val first=draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(240);compose.waitForIdle()
            val moving=draw(controller.get().window.decorView)
            assertFalse("native animation must change rendered pixels",first.sameAs(moving))
            compose.mainClock.advanceTimeBy(10_000);compose.waitForIdle()
            val frozen=draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(2_000);compose.waitForIdle()
            assertTrue("waiting artwork must freeze after its finite cap",frozen.sameAs(draw(controller.get().window.decorView)))
            compose.onNodeWithTag("waiting-owl").assert(progress)
            compose.runOnIdle { enabled=false };compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
            compose.onNodeWithTag("waiting-owl").assert(progress)
            System.getenv("FEATHER_UI_EVIDENCE_DIR")?.let { dir ->
                mapOf("early" to first,"moving" to moving,"frozen" to frozen).forEach { (name,image) ->
                    val file=File(dir,"native-waiting-$name.png");checkNotNull(file.parentFile).mkdirs()
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun draw(view: View): Bitmap {
        fun composeView(v: View): View? {
            if(v.javaClass.simpleName=="AndroidComposeView")return v
            if(v is ViewGroup)for(i in 0 until v.childCount)composeView(v.getChildAt(i))?.let { return it }
            return null
        }
        val target=checkNotNull(composeView(view));assertTrue(target.width>0&&target.height>0)
        return Bitmap.createBitmap(target.width,target.height,Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }
    }
}
