package io.aaps.copilot.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MealPhotoPreparationTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allExifOrientationsPreservePixelsAndExpectedDimensions() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(),
            0xff00ffff.toInt(), 0xffff00ff.toInt(), 0xffffff00.toInt())
        val expected = listOf(
            listOf(0, 1, 2, 3, 4, 5), listOf(1, 0, 3, 2, 5, 4),
            listOf(5, 4, 3, 2, 1, 0), listOf(4, 5, 2, 3, 0, 1),
            listOf(0, 2, 4, 1, 3, 5), listOf(4, 2, 0, 5, 3, 1),
            listOf(5, 3, 1, 4, 2, 0), listOf(1, 3, 5, 0, 2, 4))
        for (orientation in 1..8) {
            val source = Bitmap.createBitmap(pixels, 2, 3, Bitmap.Config.ARGB_8888)
            val output = MealPhotoPreparation.orient(source, orientation)
            try {
                assertThat(output.width).isEqualTo(if (orientation >= 5) 3 else 2)
                assertThat(output.height).isEqualTo(if (orientation >= 5) 2 else 3)
                val actual = IntArray(6)
                output.getPixels(actual, 0, output.width, 0, 0, output.width, output.height)
                assertThat(actual.toList()).containsExactlyElementsIn(expected[orientation - 1].map { pixels[it] }).inOrder()
            } finally {
                if (output !== source) output.recycle()
                source.recycle()
            }
        }
    }

    @Test
    fun rejectsEmptyImage() {
        assertThat((MealPhotoPreparation.prepare(ByteArray(0)) as MealPhotoPreparationResult.Failure).reason)
            .isEqualTo(MealPhotoPreparationResult.Failure.Reason.EMPTY)
    }

    @Test
    fun resizesAndReencodesWithoutPreservingInputFormatMetadata() {
        val bitmap = Bitmap.createBitmap(1_600, 800, Bitmap.Config.ARGB_8888)
        val input = ByteArrayOutputStream().also {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            bitmap.recycle()
        }.toByteArray()

        val result = MealPhotoPreparation.prepare(input)
        assertThat(result).isInstanceOf(MealPhotoPreparationResult.Success::class.java)
        val image = (result as MealPhotoPreparationResult.Success).image
        assertThat(image.mimeType).isEqualTo("image/jpeg")
        assertThat(image.width).isEqualTo(1_280)
        assertThat(image.height).isEqualTo(640)
        assertThat(image.bytes.size).isAtMost(MealPhotoPreparation.MAX_OUTPUT_BYTES)
        val copy = image.bytes
        copy[0] = (copy[0].toInt() xor 0x01).toByte()
        assertThat(image.bytes[0]).isNotEqualTo(copy[0])
    }
}
