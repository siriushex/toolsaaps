package io.aaps.copilot.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PreparedMealPhoto(
    bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int
) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.isNotEmpty())
        require(mimeType == "image/jpeg")
        require(width > 0 && height > 0)
        require(maxOf(width, height) <= MealPhotoPreparation.MAX_LONGEST_EDGE)
        require(value.size <= MealPhotoPreparation.MAX_OUTPUT_BYTES)
    }

    val bytes: ByteArray
        get() = value.copyOf()
}

sealed interface MealPhotoPreparationResult {
    data class Success(val image: PreparedMealPhoto) : MealPhotoPreparationResult

    data class Failure(val reason: Reason) : MealPhotoPreparationResult {
        enum class Reason {
            EMPTY,
            INVALID_IMAGE,
            DIMENSIONS_TOO_LARGE,
            DECODE_FAILED,
            OUTPUT_TOO_LARGE
        }
    }
}

/** Decode, resize and re-encode photo input without carrying EXIF metadata. */
object MealPhotoPreparation {
    const val MAX_LONGEST_EDGE = 1_280
    const val MAX_OUTPUT_BYTES = 1_048_576
    private const val MIN_LONGEST_EDGE = 320
    private const val MAX_INPUT_DIMENSION = 100_000
    private const val JPEG_QUALITY = 88

    fun prepare(input: ByteArray): MealPhotoPreparationResult {
        if (input.isEmpty()) return MealPhotoPreparationResult.Failure(
            MealPhotoPreparationResult.Failure.Reason.EMPTY
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return MealPhotoPreparationResult.Failure(
                MealPhotoPreparationResult.Failure.Reason.INVALID_IMAGE
            )
        }
        if (bounds.outWidth > MAX_INPUT_DIMENSION || bounds.outHeight > MAX_INPUT_DIMENSION) {
            return MealPhotoPreparationResult.Failure(
                MealPhotoPreparationResult.Failure.Reason.DIMENSIONS_TOO_LARGE
            )
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(input, 0, input.size, options)
            ?: return MealPhotoPreparationResult.Failure(
                MealPhotoPreparationResult.Failure.Reason.DECODE_FAILED
            )
        return try {
            encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_LONGEST_EDGE * 2) {
            if (sample > Int.MAX_VALUE / 2) return Int.MAX_VALUE
            sample *= 2
        }
        return sample
    }

    private fun encode(source: Bitmap): MealPhotoPreparationResult {
        var current = fitToEdge(source, MAX_LONGEST_EDGE)
        var ownsCurrent = current !== source
        try {
            repeat(8) { attempt ->
                val quality = (JPEG_QUALITY - attempt * 7).coerceAtLeast(40)
                val output = ByteArrayOutputStream()
                if (!current.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    return MealPhotoPreparationResult.Failure(
                        MealPhotoPreparationResult.Failure.Reason.DECODE_FAILED
                    )
                }
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_OUTPUT_BYTES) {
                    return MealPhotoPreparationResult.Success(
                        PreparedMealPhoto(bytes, "image/jpeg", current.width, current.height)
                    )
                }
                if (attempt < 7) {
                    val next = fitToEdge(current, maxOf(MIN_LONGEST_EDGE, current.width.coerceAtMost(current.height) * 9 / 10))
                    if (next !== current) {
                        if (ownsCurrent) current.recycle()
                        current = next
                        ownsCurrent = true
                    }
                }
            }
            return MealPhotoPreparationResult.Failure(
                MealPhotoPreparationResult.Failure.Reason.OUTPUT_TOO_LARGE
            )
        } finally {
            if (ownsCurrent && !current.isRecycled) current.recycle()
        }
    }

    private fun fitToEdge(source: Bitmap, longestEdge: Int): Bitmap {
        val currentLongest = maxOf(source.width, source.height)
        if (currentLongest <= longestEdge) return source
        val scale = longestEdge.toDouble() / currentLongest.toDouble()
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
