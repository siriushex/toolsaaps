package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class InsightsRepositoryOpenAiFailureTest {

    @Test
    fun cancellationAndFatal_areRethrownUnchanged() = runTest {
        listOf<Throwable>(
            CancellationException("cancel sk-secret"),
            AssertionError("fatal sk-secret")
        ).forEach { failure ->
            val thrown = try {
                InsightsRepository.runSanitizedOpenAiOperation<String> {
                    throw failure
                }
                null
            } catch (thrown: Throwable) {
                thrown
            }

            assertSame(failure, thrown)
        }
    }

    @Test
    fun ordinaryFailure_isSanitized() = runTest {
        val result = InsightsRepository.runSanitizedOpenAiOperation<String> {
            error("request failed with sk-secret and private response")
        }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("OpenAI request failed")
        assertThat(result.exceptionOrNull().toString()).doesNotContain("sk-secret")
        assertThat(result.exceptionOrNull().toString()).doesNotContain("private response")
    }
}
