package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class AiChatRepositoryOptimizerTest {

    @Test
    fun resolvePreferredOptimizerModel_prefersProWhenAvailable() {
        val model = AiChatRepository.resolvePreferredOptimizerModel(
            listOf("gpt-4.1-mini", "gpt-5.2-pro", "gpt-5.2")
        )

        assertThat(model).isEqualTo("gpt-5.2-pro")
    }

    @Test
    fun parseOptimizerOutputStatic_readsAndClampsScales() {
        val parsed = AiChatRepository.parseOptimizerOutputStatic(
            """
            {
              "status":"APPLY",
              "confidence":0.88,
              "reason":"underprediction at 60m when COB is high",
              "focus_horizon_min":60,
              "notes":["increase upward correction carefully"],
              "calibration":{
                "gain_scale_5m":1.05,
                "gain_scale_30m":1.35,
                "gain_scale_60m":1.62,
                "max_up_scale_5m":1.1,
                "max_up_scale_30m":1.4,
                "max_up_scale_60m":2.3,
                "max_down_scale_5m":0.9,
                "max_down_scale_30m":0.85,
                "max_down_scale_60m":0.4
              }
            }
            """.trimIndent()
        )

        assertThat(parsed.status).isEqualTo("APPLY")
        assertThat(parsed.confidence).isWithin(1e-9).of(0.88)
        assertThat(parsed.focusHorizonMinutes).isEqualTo(60)
        assertThat(parsed.gainScale30m).isWithin(1e-9).of(1.35)
        assertThat(parsed.gainScale60m).isWithin(1e-9).of(1.50) // clamped
        assertThat(parsed.maxUpScale60m).isWithin(1e-9).of(1.80) // clamped
        assertThat(parsed.maxDownScale60m).isWithin(1e-9).of(0.80) // clamped
    }

    @Test
    fun parseResponsesOutputTextStatic_supportsOutputTextAndContentArray() {
        val direct = """{"output_text":"{\"status\":\"NO_CHANGE\"}"}"""
        assertThat(AiChatRepository.parseResponsesOutputTextStatic(direct))
            .isEqualTo("{\"status\":\"NO_CHANGE\"}")

        val viaContent = """
            {
              "output": [
                {
                  "type": "message",
                  "content": [
                    { "type": "output_text", "text": "{\"status\":\"APPLY\"}" }
                  ]
                }
              ]
            }
        """.trimIndent()
        assertThat(AiChatRepository.parseResponsesOutputTextStatic(viaContent))
            .contains("\"status\":\"APPLY\"")
    }

    @Test
    fun parseResponsesOutputTextStatic_supportsParsedStructuredObject() {
        val response = """
            {
              "output": [
                {
                  "type": "message",
                  "content": [
                    {
                      "type": "output_text",
                      "parsed": {
                        "status": "APPLY",
                        "confidence": 0.74,
                        "reason": "keep upward calibration slightly higher at 60m",
                        "focus_horizon_min": 60,
                        "notes": ["parsed branch"],
                        "calibration": {
                          "gain_scale_5m": 1.0,
                          "gain_scale_30m": 1.1,
                          "gain_scale_60m": 1.2,
                          "max_up_scale_5m": 1.0,
                          "max_up_scale_30m": 1.1,
                          "max_up_scale_60m": 1.2,
                          "max_down_scale_5m": 1.0,
                          "max_down_scale_30m": 1.0,
                          "max_down_scale_60m": 1.0
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = AiChatRepository.parseResponsesOutputTextStatic(response)
        assertThat(parsed).contains("\"status\":\"APPLY\"")
        assertThat(parsed).contains("\"calibration\"")
    }

    @Test
    fun parseResponsesOutputTextStatic_supportsDirectOutputTextObjectValue() {
        val response = """
            {
              "output": [
                {
                  "type": "output_text",
                  "text": {
                    "value": "{\"status\":\"NO_CHANGE\",\"confidence\":0.2,\"reason\":\"stable\",\"focus_horizon_min\":0,\"notes\":[],\"calibration\":{\"gain_scale_5m\":1.0,\"gain_scale_30m\":1.0,\"gain_scale_60m\":1.0,\"max_up_scale_5m\":1.0,\"max_up_scale_30m\":1.0,\"max_up_scale_60m\":1.0,\"max_down_scale_5m\":1.0,\"max_down_scale_30m\":1.0,\"max_down_scale_60m\":1.0}}"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = AiChatRepository.parseResponsesOutputTextStatic(response)
        assertThat(parsed).contains("\"status\":\"NO_CHANGE\"")
        assertThat(parsed).contains("\"confidence\":0.2")
    }

    @Test
    fun parseTranscriptionTextStatic_readsTextField() {
        val parsed = AiChatRepository.parseTranscriptionTextStatic(
            """
            {
              "text": "проверка голосового ввода"
            }
            """.trimIndent()
        )

        assertThat(parsed).isEqualTo("проверка голосового ввода")
    }

    @Test
    fun extractTextAttachmentPreviewStatic_readsSupportedTextFile() {
        val file = File.createTempFile("ai-chat-", ".txt")
        file.writeText("line one\nline two")
        try {
            val preview = AiChatRepository.extractTextAttachmentPreviewStatic(file, "text/plain")
            assertThat(preview).isEqualTo("line one\nline two")
        } finally {
            file.delete()
        }
    }

    @Test
    fun extractTextAttachmentPreviewStatic_ignoresBinaryFile() {
        val file = File.createTempFile("ai-chat-", ".bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val preview = AiChatRepository.extractTextAttachmentPreviewStatic(file, "application/octet-stream")
            assertThat(preview).isNull()
        } finally {
            file.delete()
        }
    }
}
