package io.aaps.copilot.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w360dp-h640dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwlMotionStudioTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun nativeChoreographyIsVisibleUnclippedAndFiniteAtBothSizes() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val scene = mutableStateOf(OwlGesture.LAUNCH)
        val frame = mutableLongStateOf(0)
        val enabled = mutableStateOf(true)
        try {
            controller.get().setContent {
                AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                    CompositionLocalProvider(LocalCopilotMotionEnabled provides enabled.value) {
                        Surface(Modifier.fillMaxSize(), color = Color.White) {
                            Column(Modifier.fillMaxSize().padding(24.dp)) {
                                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CopilotOwl(size = 38.dp, band = OwlColorBand.GREEN, modifier = Modifier.testTag("studio-header"),
                                        poseOverride = { OwlMotionPolicy.sample(scene.value, frame.longValue) })
                                    Text("Copilot", style = MaterialTheme.typography.titleMedium)
                                }
                                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    CopilotOwl(size = 144.dp, band = OwlColorBand.GREEN, modifier = Modifier.testTag("studio-hero"),
                                        poseOverride = { OwlMotionPolicy.sample(scene.value, frame.longValue) })
                                    Spacer(Modifier.height(24.dp))
                                    Text(caption(scene.value), style = MaterialTheme.typography.titleLarge)
                                }
                                Box(Modifier.height(88.dp), contentAlignment = Alignment.Center) {
                                    if (scene.value == OwlGesture.WAITING) AiChatWaitingIndicator(
                                        "preview-attention", frameClock = frame, showOwl = false)
                                }
                            }
                        }
                    }
                }
            }
            fun pixels(time: Long): Bitmap {
                compose.runOnIdle { frame.longValue = time }
                return draw(controller.get().window.decorView)
            }
            OwlGesture.entries.filter { it != OwlGesture.STILL }.forEach { gesture ->
                compose.runOnIdle { scene.value = gesture }
                val first = pixels(0)
                val moving = pixels(220)
                listOf("studio-header", "studio-hero").forEach { tag ->
                    val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    fun crop(image: Bitmap) = Bitmap.createBitmap(image, bounds.left.toInt(), bounds.top.toInt(),
                        bounds.width.toInt(), bounds.height.toInt())
                    val opening = crop(first)
                    val action = crop(moving)
                    assertFalse("$gesture must move at $tag", opening.sameAs(action))
                    for (time in 0..gesture.durationMs step 33) {
                        val bitmap = pixels(time)
                        val owl = crop(bitmap)
                        assertClearEdges(owl, "$gesture/$tag/$time")
                        owl.recycle(); bitmap.recycle()
                    }
                    opening.recycle(); action.recycle()
                }
                val end = pixels(gesture.durationMs)
                // Busy dots settle to a different static alpha; compare artwork only.
                val bounds = compose.onNodeWithTag("studio-hero").fetchSemanticsNode().boundsInRoot
                val opening = Bitmap.createBitmap(first, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                val resting = Bitmap.createBitmap(end, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                assertTrue("$gesture must return to the same visible rest", opening.sameAs(resting))
                opening.recycle(); resting.recycle()
                first.recycle(); moving.recycle(); end.recycle()
            }
            compose.runOnIdle { enabled.value = false; scene.value = OwlGesture.GREET }
            val reduced = pixels(220)
            val reducedLater = pixels(440)
            assertTrue("reduced motion must ignore injected animation poses", reduced.sameAs(reducedLater))
            reduced.recycle(); reducedLater.recycle()
            System.getenv("OWL_MOTION_RENDER_DIR")?.let { directory ->
                val output = File(directory).also { it.mkdirs() }
                compose.runOnIdle { enabled.value = true }
                for (index in 0..432) {
                    val time = index * 100L / 3
                    val gesture = when {
                        time < 1_200 -> OwlGesture.LAUNCH
                        time < 2_400 -> OwlGesture.GREET
                        time < 3_600 -> OwlGesture.GAZE
                        time < 4_800 -> OwlGesture.NOD
                        time < 6_000 -> OwlGesture.LISTEN
                        else -> OwlGesture.WAITING
                    }
                    val local = if (time < 6_000) time % 1_200 else time - 6_000
                    compose.runOnIdle { scene.value = gesture }
                    val bitmap = pixels(local)
                    File(output, "frame-%04d.png".format(index)).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    bitmap.recycle()
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun caption(gesture: OwlGesture) = when (gesture) {
        OwlGesture.LAUNCH -> "Приветствие"
        OwlGesture.GREET -> "С возвращением"
        OwlGesture.GAZE -> "Прогноз"
        OwlGesture.NOD -> "Аналитика"
        OwlGesture.LISTEN -> "AI-анализ"
        OwlGesture.WAITING -> "Внимательное ожидание"
        OwlGesture.MENU -> "Меню"
        OwlGesture.STILL -> "Покой"
    }

    private fun assertClearEdges(image: Bitmap, label: String) {
        fun edge(x: Int, y: Int) {
            val pixel = image.getPixel(x, y)
            // Permit only the <=1.2% raster antialias fringe, never a visible contour.
            val ink = 255 - minOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
            if (ink > 3) System.getenv("OWL_MOTION_RENDER_DIR")?.let { directory ->
                val output = File(directory).also { it.mkdirs() }
                File(output, "edge-${label.replace('/', '-')}.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue("$label at $x,$y has edge ink $ink/255", ink <= 3)
        }
        for (x in 0 until image.width) {
            edge(x, 0)
            edge(x, image.height - 1)
        }
        for (y in 0 until image.height) {
            edge(0, y)
            edge(image.width - 1, y)
        }
    }

    private fun draw(view: View): Bitmap {
        fun content(v: View): View? {
            if (v.javaClass.simpleName == "AndroidComposeView") return v
            if (v is ViewGroup) for (i in 0 until v.childCount) content(v.getChildAt(i))?.let { return it }
            return null
        }
        val target = checkNotNull(content(view))
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }
    }
}
