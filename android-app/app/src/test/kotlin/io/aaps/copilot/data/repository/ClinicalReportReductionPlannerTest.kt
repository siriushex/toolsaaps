package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.lang.reflect.Modifier
import java.security.MessageDigest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalReportReductionPlannerTest {

    @Test
    fun seventeenDigestsFormMaximalBoundedGroupsInInputOrder() {
        val digests = contiguousDigests(17)
        val budget = bodyBudgetFor(digests, 3)

        val level = planner(requestBudgetBytes = budget).nextLevel(digests)

        assertThat(level.groups).hasSize(6)
        assertThat(level.groups.map { it.sources.size })
            .containsExactly(3, 3, 3, 3, 3, 2)
            .inOrder()
        assertThat(level.groups.flatMap { it.sources }.map { it.id })
            .containsExactlyElementsIn(digests.map { it.id })
            .inOrder()
        assertThat(level.groups.map { it.index })
            .containsExactly(1, 2, 3, 4, 5, 6)
            .inOrder()
        assertThat(level.groups.map { it.count }.distinct()).containsExactly(6)
        assertThat(level.groups.all { it.requestBytes <= budget }).isTrue()
    }

    @Test
    fun exactFitProducesOneGroupWithExactPlannerInputAndTransportBytes() {
        val digests = contiguousDigests(3)
        val template = wireTemplate()
        val budget = bodyBudgetFor(digests, 3, template)

        val group = planner(budget, template).nextLevel(digests).groups.single()

        assertThat(group.index).isEqualTo(1)
        assertThat(group.count).isEqualTo(1)
        assertThat(group.requestBytes).isEqualTo(budget)
        assertThat(group.requestBytes).isEqualTo(group.requestBodyBytes.size)
        assertThat(JsonParser.parseString(group.requestInput).isJsonObject).isTrue()
        assertThat(
            JsonParser.parseString(group.requestBodyBytes.utf8())
                .asJsonObject["input"].asString
        ).isEqualTo(group.requestInput)
    }

    @Test
    fun oneDigestThatCannotFitThrowsDigestTooLarge() {
        val digest = contiguousDigests(1)
        val exactBytes = bodyBudgetFor(digest, 1)

        val failure = assertThrows(ClinicalReductionException.DigestTooLarge::class.java) {
            planner(requestBudgetBytes = exactBytes - 1).nextLevel(digest)
        }

        assertThat(failure).hasMessageThat()
            .isEqualTo("Clinical digest exceeds reduction request budget")
    }

    @Test
    fun allSingletonGroupsFailBecauseReductionCannotMakeProgress() {
        listOf(2, 40).forEach { count ->
            val digests = contiguousDigests(count)
            val singleBudget = bodyBudgetFor(digests, 1)
            assertThat(bodyBudgetFor(digests, 2)).isGreaterThan(singleBudget)

            val failure = assertThrows(
                ClinicalReductionException.CannotReduce::class.java
            ) {
                planner(singleBudget).nextLevel(digests)
            }

            assertThat(failure).hasMessageThat()
                .isEqualTo("Clinical reduction cannot make progress")
        }
    }

    @Test
    fun singletonTailIsAllowedAfterAtLeastOneOtherGroupMerged() {
        val digests = contiguousDigests(5)
        val pairBudget = bodyBudgetFor(digests, 2)

        val level = planner(pairBudget).nextLevel(digests)

        assertThat(level.groups.map { it.sources.size }).containsExactly(2, 2, 1)
        assertThat(level.groups.size).isLessThan(digests.size)
    }

    @Test
    fun eightyDigestsProduceFortyPairGroupsWithoutFixedGroupCap() {
        val digests = contiguousDigests(80)
        val pairBudget = bodyBudgetFor(digests, 2)
        assertThat(bodyBudgetFor(digests, 3)).isGreaterThan(pairBudget)

        val level = planner(pairBudget).nextLevel(digests)

        assertThat(level.groups).hasSize(40)
        assertThat(level.groups.all { it.sources.size == 2 }).isTrue()
        assertThat(level.groups.map { it.index })
            .containsExactlyElementsIn(1..40)
            .inOrder()
        assertThat(level.groups.map { it.count }.distinct()).containsExactly(40)
        assertThat(level.groups.size).isLessThan(digests.size)
    }

    @Test
    fun gapOverlapAndUnorderedInputsFailClosed() {
        val gap = listOf(
            digest("a", 0L, 10L),
            digest("b", 11L, 20L)
        )
        val overlap = listOf(
            digest("a", 0L, 11L),
            digest("b", 10L, 20L)
        )
        val unordered = listOf(
            digest("b", 10L, 20L),
            digest("a", 0L, 10L)
        )

        listOf(gap, overlap, unordered).forEach { invalid ->
            assertThrows(ClinicalReductionException.CoverageMismatch::class.java) {
                planner().nextLevel(invalid)
            }
        }
    }

    @Test
    fun duplicateDigestIdentityHashAndNestedSourceHashFailClosed() {
        val duplicateIdentityAndHash = digest("same", 0L, 10L).let {
            listOf(it, it)
        }
        val sharedSource = sha256("shared-source")
        val duplicateSourceHash = listOf(
            digest("a", 0L, 10L, sourceHashes = listOf(sharedSource)),
            digest("b", 10L, 20L, sourceHashes = listOf(sharedSource))
        )

        listOf(duplicateIdentityAndHash, duplicateSourceHash).forEach { invalid ->
            assertThrows(ClinicalReductionException.CoverageMismatch::class.java) {
                planner().nextLevel(invalid)
            }
        }
    }

    @Test
    fun plannerOwnsCompleteOrderedCanonicalReductionInput() {
        val digests = contiguousDigests(4)
        val level = planner().nextLevel(digests)
        val group = level.groups.single()
        val root = JsonParser.parseString(group.requestInput).asJsonObject

        assertThat(root["schema"].asString).isEqualTo("clinical-reduction-input")
        assertThat(root["version"].asInt).isEqualTo(1)
        assertThat(root["fromTs"].asLong).isEqualTo(digests.first().fromTs)
        assertThat(root["throughTsExclusive"].asLong)
            .isEqualTo(digests.last().throughTsExclusive)
        assertThat(root["digestCount"].asInt).isEqualTo(digests.size)

        val envelopes = root["digests"].asJsonArray
        assertThat(envelopes).hasSize(digests.size)
        digests.forEachIndexed { index, digest ->
            val envelope = envelopes[index].asJsonObject
            assertThat(envelope["canonicalJson"].asString)
                .isEqualTo(digest.canonicalJson)
            assertThat(envelope["hash"].asString).isEqualTo(digest.hash)
            assertThat(envelope.has("sourceHashes")).isFalse()
            assertThat(
                JsonParser.parseString(envelope["canonicalJson"].asString)
                    .asJsonObject["sourceHashes"]
                    .asJsonArray
                    .map { it.asString }
            )
                .containsExactlyElementsIn(digest.sourceHashes)
                .inOrder()
        }
        assertThat(envelopes.map { it.asJsonObject["hash"].asString })
            .containsExactlyElementsIn(digests.map { it.hash })
            .inOrder()
    }

    @Test
    fun denseFinalReductionFitsBudgetWithSingleCanonicalSourceHashCoverage() {
        val sourceHashes = (0 until 176).map { sha256("dense-source-$it") }
        val digests = listOf(
            digest(
                id = "dense-a",
                fromTs = 0L,
                throughTsExclusive = 100L,
                sourceHashes = sourceHashes.take(88)
            ),
            digest(
                id = "dense-b",
                fromTs = 100L,
                throughTsExclusive = 200L,
                sourceHashes = sourceHashes.drop(88)
            )
        )
        val budget = 23_552

        val first = planner(requestBudgetBytes = budget).nextLevel(digests).groups.single()
        val second = planner(requestBudgetBytes = budget).nextLevel(digests).groups.single()
        val envelopes = JsonParser.parseString(first.requestInput)
            .asJsonObject["digests"]
            .asJsonArray

        assertThat(first.requestBytes).isAtMost(budget)
        assertThat(first.sources.map { it.id }).containsExactly("dense-a", "dense-b").inOrder()
        assertThat(first.requestInput).isEqualTo(second.requestInput)
        assertThat(first.requestBodyBytes).isEqualTo(second.requestBodyBytes)
        assertThat(envelopes).hasSize(2)
        assertThat(envelopes.all { !it.asJsonObject.has("sourceHashes") }).isTrue()

        val coveredHashes = envelopes.flatMap { envelope ->
            JsonParser.parseString(envelope.asJsonObject["canonicalJson"].asString)
                .asJsonObject["sourceHashes"]
                .asJsonArray
                .map { it.asString }
        }
        assertThat(coveredHashes).containsExactlyElementsIn(sourceHashes).inOrder()
        assertThat(coveredHashes.distinct()).hasSize(sourceHashes.size)
    }

    @Test
    fun wireTemplateRejectsMissingInputBinding() {
        val invalidTemplates = listOf(
            Pair("{}".encodeUtf8(), ByteString.EMPTY),
            Pair("""{"unrelated":true}""".encodeUtf8(), ByteString.EMPTY)
        )

        invalidTemplates.forEach { (prefix, suffix) ->
            assertThrows(ClinicalReductionException.CoverageMismatch::class.java) {
                ClinicalReductionWireTemplate.create(prefix, suffix)
            }
        }
    }

    @Test
    fun wireTemplateRejectsNonStandardAndTrailingJson() {
        val invalidTemplates = listOf(
            Pair("{'input':".encodeUtf8(), "}".encodeUtf8()),
            Pair("""{"input":/*comment*/""".encodeUtf8(), "}".encodeUtf8()),
            Pair("{input:".encodeUtf8(), "}".encodeUtf8()),
            Pair("""{"input":""".encodeUtf8(), "} true".encodeUtf8()),
            Pair("""{"nonFinite":NaN,"input":""".encodeUtf8(), "}".encodeUtf8()),
            Pair(
                """{"nonFinite":Infinity,"input":""".encodeUtf8(),
                "}".encodeUtf8()
            )
        )

        invalidTemplates.forEach { (prefix, suffix) ->
            val failure = assertThrows(
                ClinicalReductionException.CoverageMismatch::class.java
            ) {
                ClinicalReductionWireTemplate.create(prefix, suffix)
            }
            assertThat(failure).hasMessageThat()
                .isEqualTo("Clinical reduction coverage mismatch")
        }
    }

    @Test
    fun groupFactoryRejectsNonStandardAndTrailingJsonBodies() {
        val source = digest("strict-body", 100L, 200L)
        val input = planner().nextLevel(listOf(source)).groups.single().requestInput
        val literal = JsonPrimitive(input).toString()
        val invalidBodies = listOf(
            "{'input':$literal}",
            """{"input":/*comment*/$literal}""",
            "{input:$literal}",
            """{"input":$literal} true""",
            """{"nonFinite":NaN,"input":$literal}""",
            """{"nonFinite":Infinity,"input":$literal}"""
        )

        invalidBodies.forEach { body ->
            val failure = assertThrows(
                ClinicalReductionException.CoverageMismatch::class.java
            ) {
                group(
                    sources = listOf(source),
                    requestInput = input,
                    requestBodyBytes = body.encodeUtf8()
                )
            }
            assertThat(failure).hasMessageThat()
                .isEqualTo("Clinical reduction coverage mismatch")
        }
    }

    @Test
    fun wireTemplateRejectsOldSentinelCollisionWithInsertionAsPropertyName() {
        val oldSentinel = "clinical-reduction-input-sentinel-v1"

        val failure = assertThrows(
            ClinicalReductionException.CoverageMismatch::class.java
        ) {
            ClinicalReductionWireTemplate.create(
                prefixBytes = "{".encodeUtf8(),
                suffixBytes = """:1,"static":"$oldSentinel"}""".encodeUtf8()
            )
        }

        assertThat(failure).hasMessageThat()
            .isEqualTo("Clinical reduction coverage mismatch")
    }

    @Test
    fun retainedBodyParsesAndContainsExactPlannerInputOnce() {
        val group = planner().nextLevel(contiguousDigests(4)).groups.single()
        val body = JsonParser.parseString(group.requestBodyBytes.utf8())

        assertThat(countStringValue(body, group.requestInput)).isEqualTo(1)
        assertThat(body.asJsonObject["input"].asString).isEqualTo(group.requestInput)
    }

    @Test
    fun nonAsciiPlannerInputHasExactTemplateBoundUtf8Bytes() {
        val digests = listOf(
            digest("дигест-é", 100L, 200L),
            digest("дигест-ß", 200L, 300L)
        )
        val template = wireTemplate()
        val level = planner(wireTemplate = template).nextLevel(digests)
        val group = level.groups.single()
        val encodedInput = JsonPrimitive(group.requestInput)
            .toString()
            .encodeUtf8()
        val expectedBody = Buffer()
            .write(template.prefixBytes)
            .write(encodedInput)
            .write(template.suffixBytes)
            .readByteString()

        assertThat(group.requestInput).contains("дигест-é")
        assertThat(group.requestBodyBytes).isEqualTo(expectedBody)
        assertThat(group.requestBytes).isEqualTo(group.requestBodyBytes.size)
        assertThat(group.requestBodyBytes.utf8()).contains("дигест-é")
    }

    @Test
    fun plannerHasNoArbitraryRequestFunctionConstructorParameter() {
        val constructorTypes = ClinicalReportReductionPlanner::class.java
            .declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertThat(constructorTypes.map { it.name })
            .doesNotContain("kotlin.jvm.functions.Function1")
    }

    @Test
    fun digestFactoryRejectsMalformedUtf16AndInvalidSourceHashes() {
        val validHash = sha256("valid")
        val invalidFactories = listOf<() -> ClinicalChunkDigest>(
            { digest(id = "\uD800", fromTs = 0L, throughTsExclusive = 1L) },
            { digest(id = " ", fromTs = 0L, throughTsExclusive = 1L) },
            { digest(id = "a", fromTs = 1L, throughTsExclusive = 1L) },
            { digest(id = "a", fromTs = 2L, throughTsExclusive = 1L) },
            {
                digest(
                    id = "a",
                    fromTs = 0L,
                    throughTsExclusive = 1L,
                    sourceHashes = emptyList()
                )
            },
            {
                digest(
                    id = "a",
                    fromTs = 0L,
                    throughTsExclusive = 1L,
                    sourceHashes = listOf("short")
                )
            },
            {
                digest(
                    id = "a",
                    fromTs = 0L,
                    throughTsExclusive = 1L,
                    sourceHashes = listOf(validHash.uppercase())
                )
            },
            {
                digest(
                    id = "a",
                    fromTs = 0L,
                    throughTsExclusive = 1L,
                    sourceHashes = listOf("g".repeat(64))
                )
            },
            {
                digest(
                    id = "a",
                    fromTs = 0L,
                    throughTsExclusive = 1L,
                    sourceHashes = listOf(validHash, validHash)
                )
            }
        )

        invalidFactories.forEach { createInvalid ->
            val failure = assertThrows(
                ClinicalReductionException.InvalidDigest::class.java
            ) {
                createInvalid()
            }
            assertThat(failure).hasMessageThat()
                .isEqualTo("Clinical reduction digest is invalid")
        }
    }

    @Test
    fun digestFactoryRejectsInvalidClinicalChunkSchemaValues() {
        val invalidClinicalInputs = listOf(
            Pair(
                listOf(
                    ClinicalDataQualityFlag.COMPLETE,
                    ClinicalDataQualityFlag.SENSOR_GAPS
                ),
                listOf(finding())
            ),
            Pair(
                listOf(
                    ClinicalDataQualityFlag.SENSOR_GAPS,
                    ClinicalDataQualityFlag.THERAPY_GAPS
                ),
                listOf(finding())
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(finding(), finding().copy(confidence = ClinicalFindingConfidence.LOW))
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(finding().copy(evidenceValue = 40.1))
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(
                    finding().copy(
                        evidenceMetric = ClinicalEvidenceMetric.TIME_IN_RANGE_PCT,
                        evidenceValue = 100.1
                    )
                )
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(
                    finding().copy(
                        evidenceMetric = ClinicalEvidenceMetric.SAMPLE_COUNT,
                        evidenceValue = 1.5
                    )
                )
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(
                    finding().copy(
                        evidenceMetric = ClinicalEvidenceMetric.MAX_GAP_MINUTES,
                        evidenceValue = 1.5
                    )
                )
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(finding().copy(evidenceValue = Double.NaN))
            ),
            Pair(
                listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
                listOf(finding().copy(evidenceValue = Double.POSITIVE_INFINITY))
            )
        )

        invalidClinicalInputs.forEachIndexed { index, (quality, findings) ->
            val failure = assertThrows(
                ClinicalReductionException.InvalidDigest::class.java
            ) {
                digest(
                    id = "invalid-clinical-$index",
                    fromTs = 100L,
                    throughTsExclusive = 200L,
                    dataQuality = quality,
                    findings = findings
                )
            }
            assertThat(failure).hasMessageThat()
                .isEqualTo("Clinical reduction digest is invalid")
        }
    }

    @Test
    fun digestCanonicalEnvelopeAndHashMatchIndependentGoldenVector() {
        val digest = ClinicalChunkDigest.create(
            id = "digest-golden",
            fromTs = 100L,
            throughTsExclusive = 200L,
            sourceHashes = listOf(
                "0f9f5ce47831e099e77e295ed8bb627f089efa8672ee6fbdc49eac6f0d7f5275",
                "3fed13457ee26a4f5b27c42544aa57045981a075a6103aab87e0b81c032e9d01"
            ),
            dataQuality = listOf(ClinicalDataQualityFlag.SENSOR_GAPS),
            findings = listOf(finding())
        )
        val expectedCanonical = """
            {"schema":"clinical-chunk-digest","version":1,"id":"digest-golden","fromTs":100,"throughTsExclusive":200,"sourceHashes":["0f9f5ce47831e099e77e295ed8bb627f089efa8672ee6fbdc49eac6f0d7f5275","3fed13457ee26a4f5b27c42544aa57045981a075a6103aab87e0b81c032e9d01"],"dataQuality":["SENSOR_GAPS"],"findings":[{"topic":"GLUCOSE_STABILITY","period":"LAST_24_HOURS","direction":"STABLE","confidence":"HIGH","timeBand":"ALL_DAY","evidenceMetric":"MEAN_GLUCOSE","evidenceValue":1.0}]}
        """.trimIndent()

        assertThat(digest.canonicalJson).isEqualTo(expectedCanonical)
        assertThat(digest.serializedBytes).isEqualTo(468)
        assertThat(digest.hash)
            .isEqualTo("f22f8cf71f34484f58ca2071652c74acf682d63eb54d1d8ef69b231f855c0aa3")
        assertThat(sha256(digest.canonicalJson)).isEqualTo(digest.hash)
    }

    @Test
    fun digestHashIsSensitiveToEveryEnvelopeAndFindingField() {
        val sourceA = sha256("source-a")
        val sourceB = sha256("source-b")
        val baseFinding = finding()
        val base = sensitiveDigest()
        val variants = listOf(
            sensitiveDigest(id = "changed"),
            sensitiveDigest(fromTs = 99L),
            sensitiveDigest(throughTsExclusive = 201L),
            sensitiveDigest(sourceHashes = listOf(sourceB, sourceA)),
            sensitiveDigest(sourceHashes = listOf(sourceA, sha256("source-c"))),
            sensitiveDigest(
                dataQuality = listOf(ClinicalDataQualityFlag.SENSOR_GAPS)
            ),
            sensitiveDigest(
                finding = baseFinding.copy(topic = ClinicalPatternTopic.DATA_COVERAGE)
            ),
            sensitiveDigest(
                finding = baseFinding.copy(period = ClinicalEvidencePeriod.LAST_7_DAYS)
            ),
            sensitiveDigest(
                finding = baseFinding.copy(
                    direction = ClinicalPatternDirection.INCREASING
                )
            ),
            sensitiveDigest(
                finding = baseFinding.copy(confidence = ClinicalFindingConfidence.LOW)
            ),
            sensitiveDigest(
                finding = baseFinding.copy(timeBand = ClinicalTimeBand.MORNING)
            ),
            sensitiveDigest(
                finding = baseFinding.copy(
                    evidenceMetric = ClinicalEvidenceMetric.MEDIAN_GLUCOSE
                )
            ),
            sensitiveDigest(finding = baseFinding.copy(evidenceValue = 1.1))
        )

        assertThat(variants.map { it.hash }.distinct()).hasSize(variants.size)
        variants.forEach { changed ->
            assertThat(changed.hash).isNotEqualTo(base.hash)
        }
    }

    @Test
    fun mutableInputsCannotAlterDigestGroupOrLevelAndViewsRejectMutation() {
        val sourceHashes = mutableListOf(sha256("source-a"), sha256("source-b"))
        val dataQuality = mutableListOf(ClinicalDataQualityFlag.COMPLETE)
        val originalFinding = finding()
        val findings = mutableListOf(originalFinding)
        val digest = digest(
            id = "immutable",
            fromTs = 100L,
            throughTsExclusive = 200L,
            sourceHashes = sourceHashes,
            dataQuality = dataQuality,
            findings = findings
        )
        val digestInputs = mutableListOf(digest)
        val level = planner().nextLevel(digestInputs)
        val group = level.groups.single()
        val originalBody = group.requestBodyBytes

        sourceHashes.clear()
        dataQuality.clear()
        findings.clear()
        digestInputs.clear()

        assertThat(digest.sourceHashes).hasSize(2)
        assertThat(digest.dataQuality).containsExactly(ClinicalDataQualityFlag.COMPLETE)
        assertThat(digest.findings).containsExactly(originalFinding)
        assertThat(digest.findings.single()).isNotSameInstanceAs(originalFinding)
        assertThat(group.sources).containsExactly(digest)
        assertThat(group.requestBodyBytes).isEqualTo(originalBody)
        assertThat(level.groups).containsExactly(group)

        assertThrows(UnsupportedOperationException::class.java) {
            (digest.sourceHashes as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (digest.dataQuality as MutableList<ClinicalDataQualityFlag>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (digest.findings as MutableList<ClinicalFinding>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (group.sources as MutableList<ClinicalChunkDigest>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (level.groups as MutableList<ClinicalReductionGroup>).clear()
        }
    }

    @Test
    fun oneThousandTwentyFourDigestsUseCorrectMaximalSingleGroup() {
        val digests = contiguousDigests(1_024)
        val level = planner(requestBudgetBytes = Int.MAX_VALUE)
            .nextLevel(digests)

        assertThat(level.groups).hasSize(1)
        assertThat(level.groups.single().sources).hasSize(1_024)
        assertThat(level.groups.single().requestBytes).isAtMost(Int.MAX_VALUE)
    }

    @Test
    fun repeatedRunsProduceIdenticalGroupsInputsIdsAndBytes() {
        val digests = contiguousDigests(17)
        val budget = bodyBudgetFor(digests, 3)
        val planner = planner(budget)

        val first = planner.nextLevel(digests)
        val second = planner.nextLevel(digests)

        assertThat(first.groups.map(::snapshot))
            .containsExactlyElementsIn(second.groups.map(::snapshot))
            .inOrder()
    }

    @Test
    fun constructorAndNextLevelRequirePositiveBudgetAndNonemptyInput() {
        assertThrows(IllegalArgumentException::class.java) {
            planner(requestBudgetBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planner(requestBudgetBytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planner().nextLevel(emptyList())
        }
    }

    @Test
    fun groupFactoryRejectsInvalidConstructionAndConstructorIsPrivate() {
        val source = digest("source", 100L, 200L)
        val valid = group(sources = listOf(source))
        val gapSources = listOf(
            digest("gap-a", 100L, 150L),
            digest("gap-b", 151L, 200L)
        )
        val sharedSourceHash = sha256("shared-group-source")
        val duplicateNestedSource = listOf(
            digest("duplicate-a", 100L, 150L, listOf(sharedSourceHash)),
            digest("duplicate-b", 150L, 200L, listOf(sharedSourceHash))
        )
        val invalidFactories = listOf<() -> ClinicalReductionGroup>(
            { group(index = 0, sources = listOf(source)) },
            { group(index = 2, count = 1, sources = listOf(source)) },
            { group(count = 0, sources = listOf(source)) },
            { group(fromTs = 200L, throughTsExclusive = 200L, sources = listOf(source)) },
            { group(fromTs = 99L, sources = listOf(source)) },
            { group(sources = emptyList(), requestInput = "{}") },
            {
                group(
                    fromTs = 100L,
                    throughTsExclusive = 200L,
                    sources = gapSources,
                    requestInput = "{}"
                )
            },
            {
                group(
                    sources = duplicateNestedSource,
                    requestInput = "{}"
                )
            },
            { group(requestInput = "{}", sources = listOf(source)) },
            { group(requestInput = " ", sources = listOf(source)) },
            { group(requestBodyBytes = ByteString.EMPTY, sources = listOf(source)) },
            {
                group(
                    requestBytes = valid.requestBytes + 1,
                    sources = listOf(source)
                )
            }
        )

        assertThat(valid.id)
            .isEqualTo(planner().nextLevel(listOf(source)).groups.single().id)
        invalidFactories.forEach { createInvalid ->
            assertThrows(ClinicalReductionException.CoverageMismatch::class.java) {
                createInvalid()
            }
        }
        assertThat(
            ClinicalReductionGroup::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) }
        ).isTrue()
    }

    @Test
    fun levelFactoryRejectsInvalidConstructionAndConstructorIsPrivate() {
        val firstSource = digest("first", 100L, 200L)
        val secondSource = digest("second", 200L, 300L)
        val thirdSource = digest("third", 300L, 400L)
        val first = group(
            index = 1,
            count = 2,
            fromTs = 100L,
            throughTsExclusive = 300L,
            sources = listOf(firstSource, secondSource)
        )
        val second = group(
            index = 2,
            count = 2,
            fromTs = 300L,
            throughTsExclusive = 400L,
            sources = listOf(thirdSource)
        )
        val singletonFirst = group(
            index = 1,
            count = 2,
            fromTs = 100L,
            throughTsExclusive = 200L,
            sources = listOf(firstSource)
        )
        val singletonSecond = group(
            index = 2,
            count = 2,
            fromTs = 200L,
            throughTsExclusive = 300L,
            sources = listOf(secondSource)
        )
        val sharedSourceHash = sha256("shared-level-source")
        val duplicateFirst = group(
            index = 1,
            count = 2,
            fromTs = 100L,
            throughTsExclusive = 200L,
            sources = listOf(
                digest("duplicate-first", 100L, 200L, listOf(sharedSourceHash))
            )
        )
        val duplicateSecond = group(
            index = 2,
            count = 2,
            fromTs = 200L,
            throughTsExclusive = 300L,
            sources = listOf(
                digest("duplicate-second", 200L, 300L, listOf(sharedSourceHash))
            )
        )

        val level = ClinicalReductionLevel.create(listOf(first, second))

        assertThat(level.groups).containsExactly(first, second).inOrder()
        assertThrows(ClinicalReductionException.CannotReduce::class.java) {
            ClinicalReductionLevel.create(listOf(singletonFirst, singletonSecond))
        }
        listOf(
            emptyList(),
            listOf(first),
            listOf(second, first),
            listOf(duplicateFirst, duplicateSecond),
            listOf(first, first)
        ).forEach { invalid ->
            assertThrows(ClinicalReductionException.CoverageMismatch::class.java) {
                ClinicalReductionLevel.create(invalid)
            }
        }
        assertThat(
            ClinicalReductionLevel::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) }
        ).isTrue()
    }

    private fun planner(
        requestBudgetBytes: Int = Int.MAX_VALUE,
        wireTemplate: ClinicalReductionWireTemplate = wireTemplate()
    ): ClinicalReportReductionPlanner = ClinicalReportReductionPlanner(
        requestBudgetBytes = requestBudgetBytes,
        wireTemplate = wireTemplate
    )

    private fun wireTemplate(
        prefixBytes: ByteString = """{"input":""".encodeUtf8(),
        suffixBytes: ByteString = "}".encodeUtf8()
    ): ClinicalReductionWireTemplate =
        ClinicalReductionWireTemplate.create(prefixBytes, suffixBytes)

    private fun bodyBudgetFor(
        digests: List<ClinicalChunkDigest>,
        groupSize: Int,
        wireTemplate: ClinicalReductionWireTemplate = wireTemplate()
    ): Int = planner(Int.MAX_VALUE, wireTemplate)
        .nextLevel(digests.take(groupSize))
        .groups
        .single()
        .requestBytes

    private fun contiguousDigests(count: Int): List<ClinicalChunkDigest> =
        (0 until count).map { index ->
            digest(
                id = "digest-${index.toString().padStart(4, '0')}",
                fromTs = 1_000_000L + index * 100L,
                throughTsExclusive = 1_000_000L + (index + 1L) * 100L,
                sourceHashes = listOf(sha256("source-$index"))
            )
        }

    private fun digest(
        id: String,
        fromTs: Long,
        throughTsExclusive: Long,
        sourceHashes: List<String> = listOf(sha256("source-$id")),
        dataQuality: List<ClinicalDataQualityFlag> =
            listOf(ClinicalDataQualityFlag.COMPLETE),
        findings: List<ClinicalFinding> = listOf(finding())
    ): ClinicalChunkDigest = ClinicalChunkDigest.create(
        id = id,
        fromTs = fromTs,
        throughTsExclusive = throughTsExclusive,
        sourceHashes = sourceHashes,
        dataQuality = dataQuality,
        findings = findings
    )

    private fun sensitiveDigest(
        id: String = "base",
        fromTs: Long = 100L,
        throughTsExclusive: Long = 200L,
        sourceHashes: List<String> =
            listOf(sha256("source-a"), sha256("source-b")),
        dataQuality: List<ClinicalDataQualityFlag> =
            listOf(ClinicalDataQualityFlag.COMPLETE),
        finding: ClinicalFinding = finding()
    ): ClinicalChunkDigest =
        digest(
            id = id,
            fromTs = fromTs,
            throughTsExclusive = throughTsExclusive,
            sourceHashes = sourceHashes,
            dataQuality = dataQuality,
            findings = listOf(finding)
        )

    private fun finding(): ClinicalFinding = ClinicalFinding(
        topic = ClinicalPatternTopic.GLUCOSE_STABILITY,
        period = ClinicalEvidencePeriod.LAST_24_HOURS,
        direction = ClinicalPatternDirection.STABLE,
        confidence = ClinicalFindingConfidence.HIGH,
        timeBand = ClinicalTimeBand.ALL_DAY,
        evidenceMetric = ClinicalEvidenceMetric.MEAN_GLUCOSE,
        evidenceValue = 1.0
    )

    private fun group(
        index: Int = 1,
        count: Int = 1,
        fromTs: Long = 100L,
        throughTsExclusive: Long = 200L,
        sources: List<ClinicalChunkDigest>,
        requestInput: String? = null,
        requestBodyBytes: ByteString? = null,
        requestBytes: Int? = null
    ): ClinicalReductionGroup {
        val exactInput = requestInput ?: planner()
            .nextLevel(sources)
            .groups
            .single()
            .requestInput
        val exactBody = requestBodyBytes ?: bindBody(wireTemplate(), exactInput)
        return ClinicalReductionGroup.create(
            index = index,
            count = count,
            fromTs = fromTs,
            throughTsExclusive = throughTsExclusive,
            sources = sources,
            requestInput = exactInput,
            requestBodyBytes = exactBody,
            requestBytes = requestBytes ?: exactBody.size
        )
    }

    private fun bindBody(
        template: ClinicalReductionWireTemplate,
        input: String
    ): ByteString = Buffer()
        .write(template.prefixBytes)
        .write(JsonPrimitive(input).toString().encodeUtf8())
        .write(template.suffixBytes)
        .readByteString()

    private fun countStringValue(element: JsonElement, target: String): Int =
        when {
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                if (primitive.isString && primitive.asString == target) 1 else 0
            }
            element.isJsonArray -> element.asJsonArray.sumOf {
                countStringValue(it, target)
            }
            element.isJsonObject -> element.asJsonObject.entrySet().sumOf {
                countStringValue(it.value, target)
            }
            else -> 0
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun snapshot(group: ClinicalReductionGroup): List<Any> = listOf(
        group.id,
        group.index,
        group.count,
        group.fromTs,
        group.throughTsExclusive,
        group.sources.map { it.id },
        group.requestInput,
        group.requestBodyBytes,
        group.requestBytes
    )
}
