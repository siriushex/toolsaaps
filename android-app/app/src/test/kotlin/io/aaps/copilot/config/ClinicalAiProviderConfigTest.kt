package io.aaps.copilot.config

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalAiProviderConfigTest {

    @Test
    fun catalogsExposeCurrentDefaultsAndTiers() {
        assertThat(ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.OPENAI).id)
            .isEqualTo("gpt-5.6-terra")
        assertThat(ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.ANTHROPIC).id)
            .isEqualTo("claude-sonnet-5")
        assertThat(ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.GEMINI).id)
            .isEqualTo("gemini-3.6-flash")
        assertThat(ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.OPENAI_COMPATIBLE))
            .isEmpty()
        assertThat(ClinicalAiModelCatalog.entriesFor(ClinicalAiProviderId.OPENAI_COMPATIBLE))
            .containsExactly(ClinicalAiModelCatalogEntry.CustomModel)

        assertThat(ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.OPENAI).map { it.id })
            .containsExactly("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
            .inOrder()
        assertThat(ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.ANTHROPIC).map { it.id })
            .containsExactly("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5")
            .inOrder()
        assertThat(ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.GEMINI).map { it.id })
            .containsExactly(
                "gemini-3.1-pro-preview",
                "gemini-3.6-flash",
                "gemini-3.5-flash-lite"
            )
            .inOrder()
        assertThat(ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.GEMINI).first().preview)
            .isTrue()
        assertThat(ClinicalAiModelCatalog.entriesFor(ClinicalAiProviderId.OPENAI).last())
            .isEqualTo(ClinicalAiModelCatalogEntry.CustomModel)
    }

    @Test
    fun catalogCollectionsRejectJavaAndKotlinMutationWithoutChangingDefaults() {
        val expectedOpenAiDefault = ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.OPENAI)
        val replacement = expectedOpenAiDefault.copy(id = "replacement-model")

        @Suppress("UNCHECKED_CAST")
        val mutableCatalog = ClinicalAiModelCatalog.openAi as MutableList<ClinicalAiModelPreset>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableCatalog[0] = replacement
        }
        assertThrows(UnsupportedOperationException::class.java) {
            mutableCatalog.add(replacement)
        }

        @Suppress("UNCHECKED_CAST")
        val mutableProviderResult =
            ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.ANTHROPIC)
                as MutableList<ClinicalAiModelPreset>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableProviderResult.clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            val compatible =
                ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.OPENAI_COMPATIBLE)
                    as MutableList<ClinicalAiModelPreset>
            compatible.add(replacement)
        }

        @Suppress("UNCHECKED_CAST")
        val mutableEntries =
            ClinicalAiModelCatalog.entriesFor(ClinicalAiProviderId.OPENAI)
                as MutableList<ClinicalAiModelCatalogEntry>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableEntries.removeAt(0)
        }

        assertThat(ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.OPENAI))
            .isEqualTo(expectedOpenAiDefault)
        assertThat(ClinicalAiModelCatalog.forProvider(ClinicalAiProviderId.OPENAI).map { it.id })
            .containsExactly("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
            .inOrder()
    }

    @Test
    fun modelIdsAcceptPresetsCustomIdsAndOpaqueVisibleUnicodeExactly() {
        val decomposedModelId = "e\u0301-model"
        listOf(
            "gpt-5.6-terra",
            "publisher/model_name:latest",
            "org.model-v2",
            "local_model",
            "модель/клиника:v1",
            "临床/模型:v2",
            decomposedModelId
        ).forEach { modelId ->
            assertThat(ClinicalAiModelIdPolicy.requireValid(modelId)).isEqualTo(modelId)
        }
        assertThat(ClinicalAiModelIdPolicy.requireValid(decomposedModelId))
            .isNotEqualTo("\u00e9-model")
    }

    @Test
    fun modelIdsRejectInvisibleUnicodeAndOversize() {
        listOf(
            "",
            " ",
            "model id",
            "model\nid",
            "model\u00a0id",
            "model\u2028id",
            "model\u2029id",
            "model\u200bid",
            "model\u200eid",
            "model\u202eid",
            "model\u034fid",
            "model\ufe0fid",
            "model\u180bid",
            "model\ue000id",
            "model\u0378id",
            "model\ufdd0id",
            "model\ufffeid",
            "model\uD800id",
            "a".repeat(129)
        ).forEach { modelId ->
            assertThrows(IllegalArgumentException::class.java) {
                ClinicalAiModelIdPolicy.requireValid(modelId)
            }
        }
    }

    @Test
    fun openAiCompatibleRejectsCredentialsQueryFragmentRemoteHttpAndUnsafePorts() {
        listOf(
            "https://key@example.com/v1",
            "https://key:secret@example.com/v1",
            "https://example.com/v1?key=x",
            "https://example.com/v1#fragment",
            "http://example.com/v1",
            "http://192.168.1.2:11434/v1",
            "https://example.com:22/v1",
            "https://example.com:0/v1",
            "ftp://example.com/v1",
            "https:///v1",
            "https://example.com/%",
            "https://example.com/v1/../admin",
            "https://example.com/v1/%2e%2e/admin",
            "https://example.com/v1%2fadmin",
            "https://example.com/v1\\admin"
        ).forEach { endpoint ->
            assertThat(ClinicalAiEndpointPolicy.validate(endpoint))
                .isInstanceOf(ClinicalAiEndpointValidation.Invalid::class.java)
        }
    }

    @Test
    fun openAiCompatibleAcceptsHttpsAndExactLoopbackHttpAndNormalizes() {
        assertThat(ClinicalAiEndpointPolicy.requireValid("https://models.example/v1").host)
            .isEqualTo("models.example")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("http://127.0.0.1:11434/v1").normalized
        ).isEqualTo("http://127.0.0.1:11434/v1")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("http://localhost:11434/v1/").normalized
        ).isEqualTo("http://localhost:11434/v1")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("http://[::1]:11434/v1").host
        ).isEqualTo("::1")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("HTTPS://Models.Example:443/v1/").normalized
        ).isEqualTo("https://models.example/v1")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("https://models.example/%76%31").normalized
        ).isEqualTo("https://models.example/v1")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("https://models.example/%7euser/%3a").normalized
        ).isEqualTo("https://models.example/~user/%3A")
        assertThat(
            ClinicalAiEndpointPolicy.requireValid("https://models.example/%7Euser/%3A").normalized
        ).isEqualTo("https://models.example/~user/%3A")
    }

    @Test
    fun endpointRejectsSingleAndDoubleEncodedPathTricksAndUnicodeLookalikes() {
        listOf(
            "https://example.com/v1/%25",
            "https://example.com/v1/%252e%252e/admin",
            "https://example.com/v1/%252E%252E/admin",
            "https://example.com/v1/%252fadmin",
            "https://example.com/v1/%255cadmin",
            "https://example.com/v1/%2e/admin",
            "https://example.com/v1/%2E%2E/admin",
            "https://example.com/v1/%2fadmin",
            "https://example.com/v1/%5cadmin",
            "https://example.com/v1//admin",
            "https://example.com/v1/./admin",
            "https://example.com/v1/../admin",
            "https://example.com/v1\u2215admin",
            "https://example.com/v1\u2044admin",
            "https://example.com/v1\uff0fadmin",
            "https://example.com/v1\u29f5admin",
            "https://example.com/v1\uff3cadmin",
            "https://example.com/v1\u2024admin",
            "https://example.com/v1\uff0eadmin",
            "https://example.com/v1\u3002admin",
            "https://example.com/v1\u00a0admin",
            "https://example.com/v1\u2028admin",
            "https://example.com/v1\u2029admin",
            "https://example.com/v1\u034fadmin",
            "https://example.com/v1\ufe0fadmin",
            "https://example.com/v1\u180badmin",
            "https://example.com/v1/%CD%8Fadmin",
            "https://example.com/v1/%EF%B8%8Fadmin",
            "https://example.com/v1/%E1%A0%8Badmin"
        ).forEach { endpoint ->
            assertThat(ClinicalAiEndpointPolicy.validate(endpoint))
                .isInstanceOf(ClinicalAiEndpointValidation.Invalid::class.java)
        }
    }

    @Test
    fun nativeConfigRejectsEndpointAndProtocol() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI,
                modelId = "gpt-5.6-terra",
                endpoint = "https://example.com/v1"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.ANTHROPIC,
                modelId = "custom-model",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            )
        }
    }

    @Test
    fun compatibleConfigRequiresNormalizedEndpointAndProtocol() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local/model"
            )
        }

        val config = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "local/model",
            endpoint = "HTTP://LOCALHOST:11434/v1/",
            compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
        )

        assertThat(config).isEqualTo(
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local/model",
                endpoint = "http://localhost:11434/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
            )
        )
    }

    @Test
    fun executionIdentityIsStableValidatedSha256ForTheSameNormalizedConfig() {
        val first = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "local/model",
            endpoint = "HTTPS://Models.Example:443/v1/",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        )
        val equivalent = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "local/model",
            endpoint = "https://models.example/v1",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        )

        val identity = first.executionIdentity()

        assertThat(identity).isEqualTo(equivalent.executionIdentity())
        assertThat(identity).matches("[0-9a-f]{64}")
        assertThat(ClinicalAiConfigIdentityPolicy.requireValid(identity)).isEqualTo(identity)
    }

    @Test
    fun executionIdentityCoversEveryNormalizedExecutionField() {
        val base = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "local/model",
            endpoint = "https://models.example/v1",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        )
        val variants = listOf(
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local/model",
                endpoint = "https://models.example/v2",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            ),
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local/model",
                endpoint = "https://models.example:8443/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            ),
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local/model",
                endpoint = "http://localhost:11434/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            ),
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local/model",
                endpoint = "https://models.example/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
            ),
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "changed-model",
                endpoint = "https://models.example/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            ),
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI,
                modelId = "local/model"
            ),
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.ANTHROPIC,
                modelId = "local/model"
            )
        )

        assertThat(variants.map(ClinicalAiProviderConfig::executionIdentity))
            .doesNotContain(base.executionIdentity())
        assertThat(variants.map(ClinicalAiProviderConfig::executionIdentity).toSet())
            .hasSize(variants.size)
    }

    @Test
    fun executionIdentityValidationRejectsNonCanonicalValues() {
        listOf(
            "",
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
            "a".repeat(63) + "\n"
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ClinicalAiConfigIdentityPolicy.requireValid(invalid)
            }
        }
    }
}
