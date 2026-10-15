package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MealPhotoAnalysisRepositoryTest {
    @Test
    fun parsesSuccessAndCachesSuccessfulRequestWithoutSecondGatewayCall() = runTest {
        val calls = AtomicInteger(0)
        val repository = MealPhotoAnalysisRepository(MealPhotoGateway {
            calls.incrementAndGet()
            validJson()
        })
        val id = UUID.randomUUID().toString()

        val first = repository.analyze(id, byteArrayOf(1, 2, 3))
        val second = repository.analyze(id, byteArrayOf(9, 9, 9))

        assertThat(first).isInstanceOf(MealPhotoAnalysisResult.Success::class.java)
        assertThat(second).isInstanceOf(MealPhotoAnalysisResult.Success::class.java)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun doesNotAllowTwoDifferentForegroundRequests() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = MealPhotoAnalysisRepository(MealPhotoGateway {
            started.complete(Unit)
            release.await()
            validJson()
        })
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        val first = async { repository.analyze(firstId, byteArrayOf(1)) }
        started.await()

        val second = repository.analyze(secondId, byteArrayOf(2))
        release.complete(Unit)

        assertThat(second).isEqualTo(
            MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.BUSY)
        )
        assertThat(first.await()).isInstanceOf(MealPhotoAnalysisResult.Success::class.java)
    }

    @Test
    fun rejectsInvalidResponseAndInvalidRequestWithoutCallingGateway() = runTest {
        val calls = AtomicInteger(0)
        val repository = MealPhotoAnalysisRepository(MealPhotoGateway {
            calls.incrementAndGet()
            "{\"target\":4.1}"
        })

        val invalidRequest = repository.analyze("not-a-uuid", byteArrayOf(1))
        val invalidResponse = repository.analyze(UUID.randomUUID().toString(), byteArrayOf(1))

        assertThat(invalidRequest).isEqualTo(
            MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.INVALID_REQUEST)
        )
        assertThat(invalidResponse).isEqualTo(
            MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.INVALID_RESPONSE)
        )
        assertThat(calls.get()).isEqualTo(1)
    }

    private fun validJson(): String = """
        {
          "schemaVersion": 1,
          "estimateId": "estimate-1",
          "mealName": "Rice bowl",
          "ingredients": [{
            "id": "rice",
            "name": "Rice",
            "massGrams": {"min": 80, "max": 120},
            "preparationState": "COOKED",
            "carbohydrateBasis": "TOTAL",
            "carbohydrates": {"min": 28, "max": 34},
            "protein": {"min": 2, "max": 4},
            "fat": {"min": 0, "max": 2},
            "fiber": null,
            "sugar": {"min": 0, "max": 1},
            "polyols": null,
            "energy": {"min": 540, "max": 650, "unit": "KJ"}
          }],
          "suggestedProfile": "MIXED",
          "suggestedDurationMinutes": 120
        }
    """.trimIndent()
}
