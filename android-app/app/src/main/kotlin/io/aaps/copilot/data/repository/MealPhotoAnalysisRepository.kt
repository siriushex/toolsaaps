package io.aaps.copilot.data.repository

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MealPhotoGatewayRequest(
    val requestId: String,
    bytes: ByteArray,
    val mimeType: String
) {
    private val value = bytes.copyOf()

    init {
        require(requestId.isCanonicalMealPhotoUuid())
        require(mimeType == "image/jpeg")
        require(value.isNotEmpty() && value.size <= 1_048_576)
    }

    val bytes: ByteArray
        get() = value.copyOf()
}

/** Network boundary for a future server-default or explicitly personal route. */
fun interface MealPhotoGateway {
    suspend fun analyze(request: MealPhotoGatewayRequest): String
}

sealed interface MealPhotoAnalysisResult {
    data class Success(val estimate: MealPhotoNutritionEstimate) : MealPhotoAnalysisResult

    data class Failure(val reason: Reason) : MealPhotoAnalysisResult {
        enum class Reason {
            INVALID_REQUEST,
            BUSY,
            NETWORK,
            INVALID_RESPONSE
        }
    }
}

/**
 * Foreground-only orchestration. It keeps a successful result by request ID so
 * recovery does not reinfer the same image, but never persists the image or
 * sends anything to AAPS.
 */
class MealPhotoAnalysisRepository(
    private val gateway: MealPhotoGateway,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutex = Mutex()
    private var activeRequestId: String? = null
    private val completed = LinkedHashMap<String, MealPhotoNutritionEstimate>()

    suspend fun analyze(bytes: ByteArray, mimeType: String = "image/jpeg"): MealPhotoAnalysisResult {
        val requestId = requestIdFactory()
        return analyze(requestId, bytes, mimeType)
    }

    suspend fun analyze(
        requestId: String,
        bytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): MealPhotoAnalysisResult {
        if (!requestId.isCanonicalMealPhotoUuid() || mimeType != "image/jpeg" ||
            bytes.isEmpty() || bytes.size > 1_048_576
        ) {
            return MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.INVALID_REQUEST)
        }
        val request = try {
            MealPhotoGatewayRequest(requestId, bytes, mimeType)
        } catch (_: IllegalArgumentException) {
            return MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.INVALID_REQUEST)
        }
        mutex.withLock {
            completed[requestId]?.let { return MealPhotoAnalysisResult.Success(it) }
            if (activeRequestId != null && activeRequestId != requestId) {
                return MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.BUSY)
            }
            activeRequestId = requestId
        }
        return try {
            val parsed = when (val result = MealPhotoEstimateParser.parse(gateway.analyze(request))) {
                is MealPhotoParseResult.Valid -> result.value
                is MealPhotoParseResult.Invalid -> {
                    return MealPhotoAnalysisResult.Failure(
                        MealPhotoAnalysisResult.Failure.Reason.INVALID_RESPONSE
                    )
                }
            }
            mutex.withLock {
                completed[requestId] = parsed
                while (completed.size > MAX_COMPLETED_RESULTS) {
                    completed.remove(completed.entries.first().key)
                }
            }
            MealPhotoAnalysisResult.Success(parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.NETWORK)
        } finally {
            mutex.withLock {
                if (activeRequestId == requestId) activeRequestId = null
            }
        }
    }

    private companion object {
        const val MAX_COMPLETED_RESULTS = 4
    }
}

private fun String.isCanonicalMealPhotoUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
