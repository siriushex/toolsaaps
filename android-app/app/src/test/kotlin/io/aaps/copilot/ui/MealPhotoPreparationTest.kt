package io.aaps.copilot.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MealPhotoPreparationTest {
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
