package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.isSyntheticUamCarbEvent
import java.util.Locale
import kotlin.random.Random
import org.junit.Test

class InsulinOnsetMealExclusionTest {
    @Test
    fun repeatedScansReadEachVisitedPayloadOnlyOnce() {
        val payloads = List(37) { CountingPayload(linkedMapOf("units" to "1", "noise" to "x")) }
        val rows = payloads.mapIndexed { index, payload -> row(index * MINUTE, payload) }
        val scan = InsulinOnsetMealExclusion(rows, HOUR)

        repeat(29) { assertThat(scan.hasNearbyMeal(it * MINUTE)).isFalse() }

        assertThat(payloads.map { it.numberReads }).containsExactlyElementsIn(List(37) { 1 })
    }

    @Test
    fun lazyScanDoesNotReadUnvisitedRowsAndCanVisitThemLater() {
        val first = CountingPayload(linkedMapOf("grams" to "5"))
        val last = CountingPayload(linkedMapOf("grams" to "0"))
        val scan = InsulinOnsetMealExclusion(listOf(row(0, first), row(4 * HOUR, last)), HOUR)
        assertThat(first.entriesReads + last.entriesReads).isEqualTo(0)

        assertThat(scan.hasNearbyMeal(0)).isTrue()
        assertThat(first.numberReads).isEqualTo(1)
        assertThat(last.entriesReads).isEqualTo(0)
        val firstWork = first.entriesReads
        assertThat(scan.hasNearbyMeal(4 * HOUR)).isFalse()
        assertThat(first.entriesReads).isEqualTo(firstWork)
        assertThat(last.numberReads).isEqualTo(1)
    }

    @Test
    fun syntheticClassificationIsAlsoDoneOnlyOnce() {
        val payload = CountingPayload(linkedMapOf("grams" to "20", "synthetic" to "true"))
        val scan = InsulinOnsetMealExclusion(listOf(row(0, payload)), HOUR)
        assertThat(scan.hasNearbyMeal(0)).isFalse()
        val work = payload.entriesReads
        assertThat(work).isGreaterThan(0)
        repeat(5) { assertThat(scan.hasNearbyMeal(it * MINUTE)).isFalse() }
        assertThat(payload.entriesReads).isEqualTo(work)
        assertThat(payload.numberReads).isEqualTo(0)
    }

    @Test
    fun duplicateIdsAndSameInstanceAtDifferentIndicesAreNotDeduplicated() {
        val payload = CountingPayload(linkedMapOf("_id" to "same", "grams" to "0"))
        val nonMeal = row(0, payload).copy(sourceRowId = "same")
        val meal = row(0, linkedMapOf("_id" to "same", "grams" to "5")).copy(sourceRowId = "same")
        val scan = InsulinOnsetMealExclusion(listOf(nonMeal, nonMeal, meal), HOUR)
        repeat(3) { assertThat(scan.hasNearbyMeal(0)).isTrue() }
        assertThat(payload.numberReads).isEqualTo(2)
    }

    @Test
    fun nextInvocationRereadsSameRowsAndReorderedCorrections() {
        val payload = CountingPayload(linkedMapOf("grams" to "0"))
        val original = row(0, payload).copy(sourceRowId = "duplicate")
        val corrected = original.copy(payload = linkedMapOf("grams" to "5"))
        assertThat(InsulinOnsetMealExclusion(listOf(original), HOUR).hasNearbyMeal(0)).isFalse()
        assertThat(InsulinOnsetMealExclusion(listOf(corrected, original), HOUR).hasNearbyMeal(0)).isTrue()
        assertThat(InsulinOnsetMealExclusion(listOf(original, corrected), HOUR).hasNearbyMeal(0)).isTrue()
        assertThat(InsulinOnsetMealExclusion(listOf(original), HOUR).hasNearbyMeal(0)).isFalse()
        assertThat(payload.numberReads).isEqualTo(3)
    }

    @Test
    fun rawCollisionOrderAliasPriorityAndMalformedFirstMatchArePreserved() {
        val cases = listOf(
            linkedMapOf("carbs" to "20", "grams" to "bad") to null,
            linkedMapOf("grams!" to "bad", "grams" to "20") to null,
            linkedMapOf("grams" to "20", "grams!" to "bad") to 20.0,
            linkedMapOf("mealCarbs" to "20", "enteredCarbs" to "4,9") to 4.9,
            linkedMapOf("enteredCarbs" to "bad", "mealCarbs" to "20") to null,
            linkedMapOf("enteredCarbs" to "5,0") to 5.0,
            linkedMapOf("entered_carbs" to "5", "enteredCarbs" to "0") to 5.0,
            linkedMapOf("x.grams" to "20", "GRAMS" to "5") to 5.0,
            linkedMapOf("gramS" to "20", "mealCarbs" to "4") to 4.0
        )
        cases.forEach { (payload, expected) ->
            assertThat(TherapyPayloadLookup.number(payload, CARB_KEYS)).isEqualTo(expected)
            assertThat(InsulinOnsetMealExclusion(listOf(row(0, payload)), HOUR).hasNearbyMeal(0))
                .isEqualTo((expected ?: 0.0) >= 5.0)
        }
    }

    @Test
    fun commaNonfiniteHexAndThresholdBehaviorIsNotSanitized() {
        listOf("4.999999", "5", "5,0", "1,2,3", "NaN", "Infinity", "-Infinity", "0x1.4p2", " 5 ", "-0.0", "5e0")
            .forEach { value ->
                val payload = linkedMapOf("grams" to value)
                val expected = legacyNumber(payload, *CARB_KEYS)
                assertThat(TherapyPayloadLookup.number(payload, CARB_KEYS)?.toBits()).isEqualTo(expected?.toBits())
                assertThat(InsulinOnsetMealExclusion(listOf(row(0, payload)), HOUR).hasNearbyMeal(0))
                    .isEqualTo((expected ?: 0.0) >= 5.0)
            }
    }

    @Test
    fun syntheticAndCanonicalRealUamUseTheExistingGuard() {
        val cases = listOf(
            linkedMapOf("grams" to "20") to true,
            linkedMapOf("grams" to "20", "synthetic" to "TRUE") to false,
            linkedMapOf("grams" to "20", "source" to "uam_engine") to false,
            linkedMapOf("grams" to "20", "syntheticType" to "UAM") to false,
            linkedMapOf("grams" to "20", "note" to "UAM_ENGINE|synthetic-fixture|") to false,
            linkedMapOf("grams" to "20", "note" to "ordinary UAM mention") to true,
            canonicalCarbs("AAPS_REAL", "false") to true,
            canonicalCarbs("UAM_SYNTHETIC", "true") to false
        )
        cases.forEach { (payload, expected) ->
            val event = row(0, payload)
            assertThat(!isSyntheticUamCarbEvent(event)).isEqualTo(expected)
            assertThat(InsulinOnsetMealExclusion(listOf(event), HOUR).hasNearbyMeal(0)).isEqualTo(expected)
        }
    }

    @Test
    fun unsortedRowsInclusiveTimesAndOverflowFollowOriginalArithmetic() {
        val times = listOf(-HOUR - 1, -HOUR, HOUR, HOUR + 1, Long.MIN_VALUE, Long.MAX_VALUE)
        times.forEach { timestamp ->
            val rows = listOf(row(timestamp, mapOf("grams" to "5")), row(-2 * HOUR, emptyMap()))
            val expected = kotlin.math.abs(timestamp - 0L) <= HOUR
            assertThat(InsulinOnsetMealExclusion(rows, HOUR).hasNearbyMeal(0)).isEqualTo(expected)
        }
        val rows = times.reversed().map { row(it, mapOf("grams" to "5")) }
        val scan = InsulinOnsetMealExclusion(rows, HOUR)
        times.forEach { assertThat(scan.hasNearbyMeal(it)).isEqualTo(legacyNearby(rows, it)) }
        // abs(Long.MIN_VALUE) remains negative in the original predicate.
        assertThat(InsulinOnsetMealExclusion(listOf(row(Long.MIN_VALUE, mapOf("grams" to "5"))), HOUR)
            .hasNearbyMeal(0)).isTrue()
    }

    @Test
    fun fixedAndSeededHistoriesMatchLegacyPredicateExactly() {
        val random = Random(0x51A5)
        repeat(80) {
            val rows = List(random.nextInt(0, 55)) { index ->
                val payload = linkedMapOf<String, String>()
                listOf("grams", "grams!", "enteredCarbs", "mealCarbs", "carbs", "noise")
                    .shuffled(random).take(random.nextInt(0, 7)).forEach { key ->
                        payload[key] = listOf("0", "4,9", "5", "NaN", "Infinity", "bad", "20").random(random)
                    }
                if (index % 7 == 0) payload["synthetic"] = "true"
                row(random.nextLong(-5 * HOUR, 5 * HOUR), payload).copy(sourceRowId = "duplicate")
            }
            val scan = InsulinOnsetMealExclusion(rows, HOUR)
            repeat(12) {
                val timestamp = random.nextLong(-5 * HOUR, 5 * HOUR)
                assertThat(scan.hasNearbyMeal(timestamp)).isEqualTo(legacyNearby(rows, timestamp))
            }
        }
    }

    @Test
    fun keyGrammarMatchesLegacyIncludingNonAsciiAndExplicitUsLocale() {
        val fixed = listOf("", "mealCarbs", "IOBUnits", "x1Y", "__grams__", "Grams!", "iob.net", "\u0130OB", "\u0131ob", "\u00c9grams", "\ud83d\ude00Carbs")
        val random = Random(935)
        val alphabet = "aAzZ09_ -.!\u0130\u0131\u00c9\u03a3\n\t"
        val keys = fixed + List(300) { buildString { repeat(random.nextInt(35)) { append(alphabet.random(random)) } } }
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            keys.forEach { assertThat(TherapyPayloadLookup.normalizeKey(it)).isEqualTo(legacyNormalize(it)) }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun emptyPayloadAndEmptyAliasesKeepNullBehavior() {
        assertThat(TherapyPayloadLookup.number(emptyMap(), CARB_KEYS)).isNull()
        assertThat(TherapyPayloadLookup.number(mapOf("grams" to "5"), emptyArray())).isNull()
        assertThat(InsulinOnsetMealExclusion(emptyList(), HOUR).hasNearbyMeal(0)).isFalse()
    }

    private fun canonicalCarbs(classification: String, synthetic: String) = linkedMapOf(
        "grams" to "20", "synthetic" to "true", "aapsCarbAmount" to "20",
        "aapsCarbIsValid" to "true", "aapsCarbClassification" to classification,
        "aapsCarbSynthetic" to synthetic
    )

    private fun row(ts: Long, payload: Map<String, String>) = TherapyEvent(ts, "carbs", payload)

    private fun legacyNearby(rows: List<TherapyEvent>, timestamp: Long): Boolean = rows.any { row ->
        val grams = if (isSyntheticUamCarbEvent(row)) 0.0 else legacyNumber(row.payload, *CARB_KEYS) ?: 0.0
        grams >= 5.0 && kotlin.math.abs(row.ts - timestamp) <= HOUR
    }

    private fun legacyNumber(payload: Map<String, String>, vararg keys: String): Double? {
        if (payload.isEmpty()) return null
        val normalized = keys.map(::legacyNormalize)
        for (candidate in normalized) {
            for ((key, value) in payload) {
                if (legacyNormalize(key) == candidate) return value.replace(",", ".").toDoubleOrNull()
            }
        }
        return null
    }

    private fun legacyNormalize(key: String): String = key
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private class CountingPayload(private val backing: Map<String, String>) : Map<String, String> by backing {
        var numberReads = 0
        var entriesReads = 0
        override fun isEmpty(): Boolean {
            numberReads++
            return backing.isEmpty()
        }
        override val entries: Set<Map.Entry<String, String>>
            get() {
                entriesReads++
                return backing.entries
            }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        val CARB_KEYS = arrayOf("grams", "carbs", "enteredCarbs", "mealCarbs")
    }
}
