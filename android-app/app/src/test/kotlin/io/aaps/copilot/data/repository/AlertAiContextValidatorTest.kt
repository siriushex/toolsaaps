package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.security.MessageDigest
import okio.ByteString.Companion.toByteString
import org.junit.Test

class AlertAiContextValidatorTest {
    @Test
    fun legitimateBuilderContextPassesIssuedAndStructuralValidation() {
        val context = alertContextFixture()

        assertThat(AlertAiContextValidator.isValid(context)).isTrue()
        assertThat(structurallyValid(context.canonicalJson, context.rowCount)).isTrue()
    }

    @Test
    fun rehashedReorderedOrDuplicateRowsFailStructuralValidation() {
        val base = alertContextFixture()
        val reordered = mutate(base) { root ->
            val rows = root.getAsJsonObject("detail24h").getAsJsonArray("therapy")
            val original = rows[0].asJsonArray.deepCopy()
            val later = rows[0].asJsonArray.deepCopy().apply {
                set(0, JsonPrimitive(ALERT_AI_CONTEXT_FIXTURE_NOW - 1_000L))
            }
            rows.remove(0)
            rows.add(later)
            rows.add(original)
            1
        }
        val duplicate = mutate(base) { root ->
            val rows = root.getAsJsonObject("detail24h").getAsJsonArray("therapy")
            rows.add(rows[0].deepCopy())
            1
        }

        assertThat(structurallyValid(reordered.first, reordered.second)).isFalse()
        assertThat(structurallyValid(duplicate.first, duplicate.second)).isFalse()
    }

    @Test
    fun alteredAggregateIsStructurallyValidButUnissuedContextFailsProductionValidation() {
        val base = alertContextFixture()
        val altered = mutate(base) { root ->
            root.getAsJsonArray("daily14d")[13].asJsonObject
                .addProperty("insulinUnits", 2.0)
            0
        }
        val forged = forgedContext(altered.first, altered.second)

        assertThat(structurallyValid(altered.first, altered.second)).isTrue()
        assertThat(AlertAiContextValidator.isValid(forged)).isFalse()
    }

    private fun mutate(
        base: AlertAiCanonicalContext,
        block: (JsonObject) -> Int
    ): Pair<String, Int> {
        val root = JsonParser.parseString(base.canonicalJson).asJsonObject
        val rowDelta = block(root)
        return root.toString() to (base.rowCount + rowDelta)
    }

    private fun structurallyValid(canonicalJson: String, rowCount: Int): Boolean {
        val context = forgedContext(canonicalJson, rowCount)
        return AlertAiContextValidator.isStructurallyValid(
            canonicalJson = context.canonicalJson,
            canonicalBytes = context.canonicalBytes,
            sha256 = context.sha256,
            rowCount = context.rowCount
        )
    }

    private fun forgedContext(
        canonicalJson: String,
        rowCount: Int
    ): AlertAiCanonicalContext {
        val bytes = canonicalJson.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return AlertAiCanonicalContext(
            canonicalJson = canonicalJson,
            canonicalBytes = bytes.toByteString(),
            sha256 = hash,
            rowCount = rowCount
        )
    }
}
