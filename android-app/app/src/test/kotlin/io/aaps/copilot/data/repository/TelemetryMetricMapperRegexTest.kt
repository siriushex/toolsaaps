package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

class TelemetryMetricMapperRegexTest {

    @Test
    fun compilesRegexesOnlyDuringClassInitialization() {
        val mapperClass = TelemetryMetricMapper::class.java
        val classLoader = checkNotNull(mapperClass.classLoader)
        val mapperName = mapperClass.name.replace('.', '/')
        val pending = ArrayDeque(listOf(mapperName))
        val inspected = mutableSetOf<String>()
        val violations = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val className = pending.removeFirst()
            if (!inspected.add(className)) continue
            val node = ClassNode()
            checkNotNull(classLoader.getResourceAsStream("$className.class")).use {
                ClassReader(it).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            node.innerClasses.filter { it.name.startsWith("$mapperName\$") }
                .forEach { pending.addLast(it.name) }
            node.methods.filter { it.name != "<clinit>" }.forEach { method ->
                method.instructions.toArray().filterIsInstance<MethodInsnNode>().forEach { call ->
                    val compilesRegex = call.owner == "kotlin/text/Regex" && call.name == "<init>"
                    val compilesPattern = call.owner == "java/util/regex/Pattern" && call.name == "compile"
                    if (compilesRegex || compilesPattern) {
                        violations += "$className.${method.name} -> ${call.owner}.${call.name}"
                    }
                }
            }
        }
        assertWithMessage("Regex compilation must stay out of mapper calls").that(violations).isEmpty()
        val regexFields = mapperClass.declaredFields.filter { it.type == Regex::class.java }
        assertThat(regexFields).hasSize(6)
        assertThat(regexFields.all { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }).isTrue()
    }

    @Test
    fun preservesOrderedGoldenSamplesIncludingIdsAndRawCollisions() {
        assertThat(map(goldenPayload())).containsExactlyElementsIn(goldenSamples()).inOrder()
    }

    @Test
    fun preservesExactThenAliasThenPayloadOrderPrecedence() {
        val cases = listOf(
            linkedMapOf("device.cob" to "11", "carbsonboard" to "12") to 12.0,
            linkedMapOf("device.cob" to "11", "carbsonboard" to "12", "CoB" to "13") to 13.0,
            linkedMapOf("device.carbsOnBoard" to "21", "prefix.cob" to "22") to 22.0,
            linkedMapOf("COB" to "24", "cob" to "25") to 24.0,
            linkedMapOf("device..cob" to "26", "device.cob" to "27") to 26.0
        )
        cases.forEach { (payload, expected) ->
            assertWithMessage("Aliases: %s", payload.keys)
                .that(map(payload).single { it.key == "cob_grams" }.valueDouble).isEqualTo(expected)
        }
    }

    @Test
    fun preservesPunctuationCamelAndUnicodeAliasBehavior() {
        val accepted = listOf(
            "sensorStepsTotal", "sensor12Steps", "sensor--steps__total", "sensor.step_count",
            "caf\u00e9Steps", "\u0130.Steps", "daily\uFF11Steps", "footsteps", "\u017Fteps"
        )
        val rejected = listOf("counterstepsNoise", "stepping")
        (accepted + rejected).forEach { key ->
            val steps = map(mapOf(key to "777")).firstOrNull { it.key == "steps_count" }
            assertWithMessage("Key: %s", key).that(steps?.valueDouble)
                .isEqualTo(if (key in accepted) 777.0 else null)
        }
    }

    @Test
    fun preservesReasonCaseBoundariesFirstMatchAndRanges() {
        val unicodeReason = "\u0131sf: 2,5; cr: 8"
        // Unicode word boundaries differ by JVM; compare with the original expression on this runtime.
        val unicodeIsf = Regex("""\bISF:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(unicodeReason)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
        val cases = listOf(
            "XISF: 2; XCR: 8" to emptyList(),
            "ISF : 2; CR : 8" to emptyList(),
            "ISF: -2.5; CR: -8" to emptyList(),
            "ISF: 0.1; ISF: 2.5; CR: 61; CR: 8" to emptyList(),
            "iSf:\t2,5; cR:\n8,25; ISF: 4; CR: 10" to listOf(2.5, 8.25),
            unicodeReason to listOfNotNull(unicodeIsf, 8.0),
            "ISF: 90; CR: 8" to listOf(90.0 / 18.0182, 8.0),
            "ISF: 2.5x; CR: 8x" to listOf(2.5, 8.0)
        )
        cases.forEach { (reason, expected) ->
            val parsed = map(mapOf("reason" to reason)).filter { it.key == "isf_value" || it.key == "cr_value" }
            assertWithMessage("Reason: %s", reason).that(parsed.map { it.valueDouble })
                .containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test
    fun preservesProfilePercentFormatBoundsAndExactOverride() {
        val cases = listOf(
            mapOf("profile" to "day (099,5%)") to 99.5,
            mapOf("profile" to "day (120.25%)") to 120.25,
            mapOf("profile" to "day (5%) (110%)") to 110.0,
            mapOf("profile" to "day (09%) (120%)") to null,
            mapOf("profile" to "day (301%) (120%)") to null,
            mapOf("profile" to "day (120 %)") to null,
            mapOf("profile" to "day (120%)", "percentage" to "80") to 80.0,
            mapOf("profile" to "day (120%)", "percentage" to "9") to 120.0
        )
        cases.forEach { (payload, expected) ->
            assertWithMessage("Profile: %s", payload).that(map(payload).firstOrNull { it.key == "profile_percent" }?.valueDouble)
                .isEqualTo(expected)
        }
    }

    @Test
    fun normalizationAndRawFingerprintsRemainIndependentOfDefaultLocale() {
        val original = Locale.getDefault()
        val originalDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        val originalFormat = Locale.getDefault(Locale.Category.FORMAT)
        try {
            listOf(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.GERMANY).forEach { locale ->
                Locale.setDefault(locale)
                assertThat(map(goldenPayload())).containsExactlyElementsIn(goldenSamples()).inOrder()
                assertThat(map(mapOf("INDIGO.\u0130" to "1,5"))).containsExactly(
                    expected("raw_indigo_i", value = 1.5, fingerprint = "1.5000")
                )
            }
        } finally {
            Locale.setDefault(original)
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay)
            Locale.setDefault(Locale.Category.FORMAT, originalFormat)
        }
    }

    @Test
    fun preservesRawRedactionNormalizationAndLengthBounds() {
        val longKey = "diagnostic_" + "x".repeat(90)
        val payload = linkedMapOf(
            "timestamp" to TIMESTAMP.toString(),
            "id" to "fixture-id",
            "x" to "1",
            "\u00e9\uFF11" to "1",
            "blank" to "  ",
            "object" to "{${"x".repeat(400)}}",
            "array" to "[${"x".repeat(400)}]",
            "diagnostic.text" to "  ${"t".repeat(110)}  ",
            longKey to "  bounded  "
        )
        listOf("secret", "TOKEN", "password", "apiKey", "api--key", "authorization", "bearer", "jwt")
            .forEach { payload[it] = "synthetic-redaction-fixture" }

        assertThat(map(payload)).containsExactly(
            expected("raw_diagnostic_text", text = "t".repeat(96), fingerprint = "t".repeat(96)),
            expected("raw_${longKey.take(84)}", text = "bounded", fingerprint = "bounded")
        ).inOrder()
    }

    @Test
    fun preservesRawLimitAfterFilteringAndStableLengthOrdering() {
        val payload = linkedMapOf("token" to "synthetic-redaction-fixture", "id" to "fixture-id", "blank" to " ")
        repeat(170) { payload["field_${it.toString().padStart(3, '0')}"] = it.toString() }

        val samples = map(payload)
        assertThat(samples).hasSize(160)
        assertThat(samples.map { it.key }).containsExactlyElementsIn(
            (0 until 160).map { "raw_field_${it.toString().padStart(3, '0')}" }
        ).inOrder()
        assertThat(samples.map { it.valueDouble }).containsExactlyElementsIn((0 until 160).map { it.toDouble() }).inOrder()
    }

    @Test
    fun repeatedCallsDoNotRetainOtherPayloads() {
        repeat(5) {
            callCases().forEach { (payload, expected) ->
                assertThat(map(payload)).containsExactlyElementsIn(expected).inOrder()
            }
        }
    }

    @Test
    fun concurrentCallsDoNotShareMatcherOrResultState() {
        val cases = callCases()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val calls = (0 until 4).map { worker ->
                executor.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(8) { iteration ->
                        val (payload, expected) = cases[(worker + iteration) % cases.size]
                        assertThat(map(payload)).containsExactlyElementsIn(expected).inOrder()
                    }
                })
            }
            start.countDown()
            calls.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun sameTimestampCorrectionsReplaceCanonicalValuesWithoutChangingEarlierResults() {
        val payload = linkedMapOf("profile" to "day (120%)", "reason" to "ISF: 2.5; CR: 8")
        val before = map(payload)
        payload["profile"] = "day (130%)"
        payload["reason"] = "ISF: 3.5; CR: 9"
        val after = map(payload)
        val canonicalBefore = before.filterNot { it.key.startsWith("raw_") }
        val canonicalAfter = after.filterNot { it.key.startsWith("raw_") }

        assertThat(canonicalBefore).containsExactly(
            expected("profile_percent", value = 120.0, unit = "%"),
            expected("isf_value", value = 2.5, unit = "mmol/L/U"),
            expected("cr_value", value = 8.0, unit = "g/U")
        ).inOrder()
        assertThat(canonicalAfter).containsExactly(
            expected("profile_percent", value = 130.0, unit = "%"),
            expected("isf_value", value = 3.5, unit = "mmol/L/U"),
            expected("cr_value", value = 9.0, unit = "g/U")
        ).inOrder()
        assertThat(canonicalAfter.map { it.id }).containsExactlyElementsIn(canonicalBefore.map { it.id }).inOrder()
        assertThat(after.filter { it.key.startsWith("raw_") }.map { it.id })
            .containsNoneIn(before.filter { it.key.startsWith("raw_") }.map { it.id })
    }

    private fun map(payload: Map<String, String>): List<TelemetrySampleEntity> =
        TelemetryMetricMapper.fromKeyValueMap(TIMESTAMP, SOURCE, payload)

    private fun goldenPayload(): Map<String, String> = linkedMapOf(
        "status.cob" to "17",
        "CARBSONBOARD" to "23",
        "CoB" to "11,5",
        "statusStepsTotal" to "1200",
        "status.futureCarbs" to "9,5",
        "prefix--heartRate" to "75",
        "zz..metric" to "5",
        "zz__metric" to "6",
        "API_TOKEN" to "synthetic-redaction-fixture",
        "timestamp" to TIMESTAMP.toString(),
        "reason" to GOLDEN_REASON,
        "profile" to "day (125,5%)",
        "isf" to "7",
        "cr" to "12"
    )

    private fun goldenSamples(): List<TelemetrySampleEntity> = listOf(
        expected("cob_grams", value = 11.5, unit = "g"),
        expected("future_carbs_grams", value = 9.5, unit = "g"),
        expected("steps_count", value = 1200.0, unit = "steps"),
        expected("heart_rate_bpm", value = 75.0, unit = "bpm"),
        expected("profile_percent", value = 125.5, unit = "%"),
        expected("isf_value", value = 2.5, unit = "mmol/L/U"),
        expected("cr_value", value = 8.25, unit = "g/U"),
        expected("raw_cr", value = 12.0, fingerprint = "12.0000"),
        expected("raw_cob", value = 11.5, fingerprint = "11.5000"),
        expected("raw_isf", value = 7.0, fingerprint = "7.0000"),
        expected("raw_reason", text = GOLDEN_REASON, fingerprint = GOLDEN_REASON),
        expected("raw_profile", text = "day (125,5%)", fingerprint = "day (125,5%)"),
        expected("raw_status_cob", value = 17.0, fingerprint = "17.0000"),
        expected("raw_zz_metric", value = 5.0, fingerprint = "5.0000"),
        expected("raw_zz_metric", value = 6.0, fingerprint = "6.0000"),
        expected("raw_carbsonboard", value = 23.0, fingerprint = "23.0000"),
        expected("raw_statusstepstotal", value = 1200.0, fingerprint = "1200.0000"),
        expected("raw_prefix_heartrate", value = 75.0, fingerprint = "75.0000"),
        expected("raw_status_futurecarbs", value = 9.5, fingerprint = "9.5000")
    )

    private fun callCases(): List<Pair<Map<String, String>, List<TelemetrySampleEntity>>> = listOf(
        goldenPayload() to goldenSamples(),
        mapOf("profile" to "late (80%)", "reason" to "ISF: 4.0; CR: 11") to listOf(
            expected("profile_percent", value = 80.0, unit = "%"),
            expected("isf_value", value = 4.0, unit = "mmol/L/U"),
            expected("cr_value", value = 11.0, unit = "g/U"),
            expected("raw_reason", text = "ISF: 4.0; CR: 11", fingerprint = "ISF: 4.0; CR: 11"),
            expected("raw_profile", text = "late (80%)", fingerprint = "late (80%)")
        ),
        emptyMap<String, String>() to emptyList()
    )

    private fun expected(
        key: String,
        value: Double? = null,
        text: String? = null,
        unit: String? = null,
        fingerprint: String? = null
    ): TelemetrySampleEntity = TelemetrySampleEntity(
        id = "tm-$SOURCE-$key-$TIMESTAMP" + (fingerprint?.let { "-${it.hashCode()}" } ?: ""),
        timestamp = TIMESTAMP,
        source = SOURCE,
        key = key,
        valueDouble = value,
        valueText = text,
        unit = unit,
        quality = "OK"
    )

    private companion object {
        const val TIMESTAMP = 1_700_001_000_000L
        const val SOURCE = "mapper_fixture"
        const val GOLDEN_REASON = "XISF: 9; iSf:\t2,5; CR: 8.25; ISF: 3; CR: 10"
    }
}
