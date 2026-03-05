package io.aaps.copilot.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudEndpointModesTest {

    @Test
    fun detectsOpenAiEndpoints() {
        assertThat(isOpenAiApiEndpoint("https://api.openai.com/v1")).isTrue()
        assertThat(isOpenAiApiEndpoint("https://api.openai.com")).isTrue()
        assertThat(isOpenAiApiEndpoint("https://foo.openai.com/v1")).isTrue()
    }

    @Test
    fun rejectsNonOpenAiEndpoints() {
        assertThat(isOpenAiApiEndpoint("https://copilot.example.com")).isFalse()
        assertThat(isOpenAiApiEndpoint("https://localhost:8080")).isFalse()
        assertThat(isOpenAiApiEndpoint("")).isFalse()
        assertThat(isOpenAiApiEndpoint("not-a-url")).isFalse()
    }

    @Test
    fun copilotBackendEndpointIsInverseOfOpenAiMode() {
        assertThat(isCopilotCloudBackendEndpoint("https://api.openai.com/v1")).isFalse()
        assertThat(isCopilotCloudBackendEndpoint("https://copilot.example.com")).isTrue()
        assertThat(isCopilotCloudBackendEndpoint("")).isFalse()
    }
}
