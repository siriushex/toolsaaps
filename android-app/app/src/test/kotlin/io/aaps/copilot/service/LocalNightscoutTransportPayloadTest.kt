package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNightscoutTransportPayloadTest {
    @Test fun retainsCanonicalLookupForLegacyKeySpellingsAndRejectsConflicts() {
        val payload = readLocalNightscoutTransportPayload("""{"is_valid":"0","target_top":100}""")
        assertThat(payload["isValid"]).isEqualTo("0")
        assertThat(payload["targetTop"]).isEqualTo("100")
        assertThat(runCatching {
            readLocalNightscoutTransportPayload("""{"isValid":false,"is_valid":true}""")
        }.isFailure).isTrue()
    }

    @Test fun preservesLongProseAndExplicitCancellationAndInvalidation() {
        val payload = readLocalNightscoutTransportPayload(
            """{"reason":"${"x".repeat(829)}","duration":0,"isValid":false,"notes":"copilot:test","extra":{"values":[1,true,null]}}"""
        )
        assertThat(payload["reason"]).hasLength(829)
        assertThat(payload["duration"]).isEqualTo("0")
        assertThat(payload["isValid"]).isEqualTo("false")
        assertThat(payload["notes"]).isEqualTo("copilot:test")
    }

    @Test fun rejectsMalformedAmbiguousAndOverBudgetPayloads() {
        listOf(
            "{broken", "{} {}", "[]", """{"isValid":false,"isValid":true}""",
            """{"isValid":{"value":false}}""", """{"isValid":null}""",
            """{"notes":"${"x".repeat(16 * 1024)}"}""",
            """{"notes":"${"\u0430".repeat(9_000)}"}""",
            "{" + (0..160).joinToString(",") { "\"key$it\":1" } + "}",
            """{"extra":[[[[[[1]]]]]]}"""
        ).forEach { raw ->
            assertThat(runCatching { readLocalNightscoutTransportPayload(raw) }.isFailure).isTrue()
        }
    }
}
