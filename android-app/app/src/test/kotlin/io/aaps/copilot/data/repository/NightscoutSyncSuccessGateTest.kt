package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

class NightscoutSyncSuccessGateTest {
    @Test
    fun `successful parsed SGV response enables reconciliation even when empty`() {
        assertThat(isAuthoritativeNightscoutFetchSuccessful(Result.success(emptyList<Any>())))
            .isTrue()
    }

    @Test
    fun `http auth parse and timeout outcomes never enable reconciliation`() {
        val failures = listOf(
            Result.failure<List<Any>>(IOException("http 500")),
            Result.failure<List<Any>>(SecurityException("unauthorized")),
            Result.failure<List<Any>>(IllegalStateException("parse failed")),
            Result.success<List<Any>?>(null)
        )

        failures.forEach { result ->
            assertThat(isAuthoritativeNightscoutFetchSuccessful(result)).isFalse()
        }
    }
}
