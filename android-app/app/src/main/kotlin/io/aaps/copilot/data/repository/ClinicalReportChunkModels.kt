package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

internal data class ClinicalSeriesRowCounts(
    val glucose: Int = 0,
    val insulin: Int = 0,
    val carbs: Int = 0,
    val targets: Int = 0
) {
    operator fun plus(other: ClinicalSeriesRowCounts): ClinicalSeriesRowCounts =
        ClinicalSeriesRowCounts(
            glucose = Math.addExact(glucose, other.glucose),
            insulin = Math.addExact(insulin, other.insulin),
            carbs = Math.addExact(carbs, other.carbs),
            targets = Math.addExact(targets, other.targets)
        )

    internal fun isNonNegative(): Boolean =
        glucose >= 0 && insulin >= 0 && carbs >= 0 && targets >= 0
}

internal data class ClinicalLeafDescriptor(
    val id: String,
    val fromTs: Long,
    val throughTsExclusive: Long,
    val depth: Int,
    val sourceRows: ClinicalSeriesRowCounts,
    val canonicalHash: String,
    val requestBytes: Int
)

internal class ClinicalChunkDigest private constructor(
    val id: String,
    val fromTs: Long,
    val throughTsExclusive: Long,
    val sourceHashes: List<String>,
    val dataQuality: List<ClinicalDataQualityFlag>,
    val findings: List<ClinicalFinding>,
    val canonicalJson: String,
    val serializedBytes: Int,
    val hash: String
) {
    companion object {
        fun create(
            id: String,
            fromTs: Long,
            throughTsExclusive: Long,
            sourceHashes: List<String>,
            dataQuality: List<ClinicalDataQualityFlag>,
            findings: List<ClinicalFinding>
        ): ClinicalChunkDigest {
            try {
                if (
                    id.isBlank() ||
                    fromTs >= throughTsExclusive ||
                    sourceHashes.isEmpty()
                ) {
                    invalidDigest()
                }

                id.strictDigestUtf8Bytes()
                val immutableSourceHashes = ArrayList<String>(sourceHashes.size)
                val uniqueSourceHashes = HashSet<String>(sourceHashes.size)
                sourceHashes.forEach { sourceHash ->
                    if (
                        !SOURCE_HASH_REGEX.matches(sourceHash) ||
                        !uniqueSourceHashes.add(sourceHash)
                    ) {
                        invalidDigest()
                    }
                    sourceHash.strictDigestUtf8Bytes()
                    immutableSourceHashes += sourceHash
                }

                val validatedReport = try {
                    ClinicalChunkReportSchemaParser.parse(
                        chunkReportJson(
                            dataQuality = dataQuality,
                            findings = findings
                        )
                    )
                } catch (_: ClinicalOpenAiException.InvalidResponse) {
                    invalidDigest()
                }
                val immutableDataQuality = Collections.unmodifiableList(
                    ArrayList(validatedReport.dataQuality)
                )
                val immutableFindings = Collections.unmodifiableList(
                    validatedReport.findings.mapTo(
                        ArrayList(validatedReport.findings.size)
                    ) { finding ->
                        finding.copy()
                    }
                )
                val canonicalJson = canonicalDigestJson(
                    id = id,
                    fromTs = fromTs,
                    throughTsExclusive = throughTsExclusive,
                    sourceHashes = immutableSourceHashes,
                    dataQuality = immutableDataQuality,
                    findings = immutableFindings
                )
                val canonicalBytes = canonicalJson.strictDigestUtf8Bytes()
                if (canonicalBytes.isEmpty()) invalidDigest()

                return ClinicalChunkDigest(
                    id = id,
                    fromTs = fromTs,
                    throughTsExclusive = throughTsExclusive,
                    sourceHashes = Collections.unmodifiableList(immutableSourceHashes),
                    dataQuality = immutableDataQuality,
                    findings = immutableFindings,
                    canonicalJson = canonicalJson,
                    serializedBytes = canonicalBytes.size,
                    hash = canonicalBytes.sha256Hex()
                )
            } catch (failure: ClinicalReductionException) {
                throw failure
            } catch (_: CharacterCodingException) {
                invalidDigest()
            }
        }

        private fun canonicalDigestJson(
            id: String,
            fromTs: Long,
            throughTsExclusive: Long,
            sourceHashes: List<String>,
            dataQuality: List<ClinicalDataQualityFlag>,
            findings: List<ClinicalFinding>
        ): String = JsonObject().apply {
            addProperty("schema", "clinical-chunk-digest")
            addProperty("version", 1)
            addProperty("id", id)
            addProperty("fromTs", fromTs)
            addProperty("throughTsExclusive", throughTsExclusive)
            add("sourceHashes", JsonArray().apply {
                sourceHashes.forEach { add(it) }
            })
            add("dataQuality", JsonArray().apply {
                dataQuality.forEach { add(it.name) }
            })
            add("findings", JsonArray().apply {
                findings.forEach { finding ->
                    add(JsonObject().apply {
                        addProperty("topic", finding.topic.name)
                        addProperty("period", finding.period.name)
                        addProperty("direction", finding.direction.name)
                        addProperty("confidence", finding.confidence.name)
                        addProperty("timeBand", finding.timeBand.name)
                        addProperty("evidenceMetric", finding.evidenceMetric.name)
                        addProperty("evidenceValue", finding.evidenceValue)
                    })
                }
            })
        }.toString()

        private fun chunkReportJson(
            dataQuality: List<ClinicalDataQualityFlag>,
            findings: List<ClinicalFinding>
        ): String = JsonObject().apply {
            add("dataQuality", JsonArray().apply {
                dataQuality.forEach { add(it.name) }
            })
            add("findings", JsonArray().apply {
                findings.forEach { finding ->
                    add(JsonObject().apply {
                        addProperty("topic", finding.topic.name)
                        addProperty("period", finding.period.name)
                        addProperty("direction", finding.direction.name)
                        addProperty("confidence", finding.confidence.name)
                        addProperty("timeBand", finding.timeBand.name)
                        addProperty("evidenceMetric", finding.evidenceMetric.name)
                        addProperty("evidenceValue", finding.evidenceValue)
                    })
                }
            })
        }.toString()

        private fun invalidDigest(): Nothing {
            throw ClinicalReductionException.InvalidDigest()
        }

        private fun String.strictDigestUtf8Bytes(): ByteArray {
            val encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(this))
            return ByteArray(encoded.remaining()).also { bytes ->
                encoded.get(bytes)
            }
        }

        private fun ByteArray.sha256Hex(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(this)
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(DIGEST_HEX[value ushr 4])
                    append(DIGEST_HEX[value and 0x0f])
                }
            }
        }

        private const val DIGEST_HEX = "0123456789abcdef"
        private val SOURCE_HASH_REGEX = Regex("[0-9a-f]{64}")
    }
}

internal class ClinicalCoverageLedger private constructor(
    val expectedFromTs: Long,
    val expectedThroughTsExclusive: Long,
    val sourceRows: ClinicalSeriesRowCounts,
    val leafCount: Int,
    val leafHashes: List<String>,
    val ledgerHash: String
) {
    companion object {
        fun create(
            expectedFromTs: Long,
            expectedThroughTsExclusive: Long,
            expectedRows: ClinicalSeriesRowCounts,
            leaves: List<ClinicalLeafDescriptor>
        ): ClinicalCoverageLedger {
            try {
                return createValidated(
                    expectedFromTs = expectedFromTs,
                    expectedThroughTsExclusive = expectedThroughTsExclusive,
                    expectedRows = expectedRows,
                    leaves = leaves
                )
            } catch (failure: ClinicalPartitionException) {
                throw failure
            } catch (_: ArithmeticException) {
                throw ClinicalPartitionException.CoverageMismatch()
            } catch (_: CharacterCodingException) {
                throw ClinicalPartitionException.CoverageMismatch()
            }
        }

        private fun createValidated(
            expectedFromTs: Long,
            expectedThroughTsExclusive: Long,
            expectedRows: ClinicalSeriesRowCounts,
            leaves: List<ClinicalLeafDescriptor>
        ): ClinicalCoverageLedger {
            if (expectedFromTs >= expectedThroughTsExclusive || leaves.isEmpty()) {
                coverageMismatch()
            }

            val orderedLeaves = leaves.sortedWith(
                compareBy<ClinicalLeafDescriptor>(
                    { it.fromTs },
                    { it.throughTsExclusive },
                    { it.id }
                )
            )
            val ids = HashSet<String>(orderedLeaves.size)
            val hashes = HashSet<String>(orderedLeaves.size)
            var aggregateRows = ClinicalSeriesRowCounts()

            orderedLeaves.forEach { leaf ->
                if (
                    leaf.id.isBlank() ||
                    leaf.canonicalHash.isBlank() ||
                    leaf.fromTs >= leaf.throughTsExclusive ||
                    leaf.depth < 0 ||
                    leaf.requestBytes <= 0 ||
                    !leaf.sourceRows.isNonNegative() ||
                    !ids.add(leaf.id) ||
                    !hashes.add(leaf.canonicalHash)
                ) {
                    coverageMismatch()
                }
                aggregateRows += leaf.sourceRows
            }

            if (
                orderedLeaves.first().fromTs != expectedFromTs ||
                orderedLeaves.last().throughTsExclusive != expectedThroughTsExclusive ||
                aggregateRows != expectedRows
            ) {
                coverageMismatch()
            }
            orderedLeaves.zipWithNext().forEach { (current, next) ->
                if (current.throughTsExclusive != next.fromTs) {
                    coverageMismatch()
                }
            }

            val leafHashes = Collections.unmodifiableList(
                orderedLeaves.mapTo(ArrayList(orderedLeaves.size)) {
                    it.canonicalHash
                }
            )
            return ClinicalCoverageLedger(
                expectedFromTs = expectedFromTs,
                expectedThroughTsExclusive = expectedThroughTsExclusive,
                sourceRows = expectedRows,
                leafCount = orderedLeaves.size,
                leafHashes = leafHashes,
                ledgerHash = ledgerHash(
                    expectedFromTs = expectedFromTs,
                    expectedThroughTsExclusive = expectedThroughTsExclusive,
                    expectedRows = expectedRows,
                    leaves = orderedLeaves
                )
            )
        }

        private fun ledgerHash(
            expectedFromTs: Long,
            expectedThroughTsExclusive: Long,
            expectedRows: ClinicalSeriesRowCounts,
            leaves: List<ClinicalLeafDescriptor>
        ): String {
            val canonical = buildString {
                appendField("format", "clinical-coverage-ledger-v1")
                appendField("expectedFromTs", expectedFromTs.toString())
                appendField("expectedThroughTsExclusive", expectedThroughTsExclusive.toString())
                appendRowCounts("expectedRows", expectedRows)
                appendField("leafCount", leaves.size.toString())
                leaves.forEachIndexed { index, leaf ->
                    val prefix = "leaf[$index]"
                    appendField("$prefix.id", leaf.id)
                    appendField("$prefix.fromTs", leaf.fromTs.toString())
                    appendField("$prefix.throughTsExclusive", leaf.throughTsExclusive.toString())
                    appendField("$prefix.depth", leaf.depth.toString())
                    appendRowCounts("$prefix.sourceRows", leaf.sourceRows)
                    appendField("$prefix.canonicalHash", leaf.canonicalHash)
                    appendField("$prefix.requestBytes", leaf.requestBytes.toString())
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.strictUtf8Bytes())
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }

        private fun StringBuilder.appendRowCounts(
            prefix: String,
            rows: ClinicalSeriesRowCounts
        ) {
            appendField("$prefix.glucose", rows.glucose.toString())
            appendField("$prefix.insulin", rows.insulin.toString())
            appendField("$prefix.carbs", rows.carbs.toString())
            appendField("$prefix.targets", rows.targets.toString())
        }

        private fun StringBuilder.appendField(name: String, value: String) {
            append(name)
            append('=')
            append(value.strictUtf8Bytes().size)
            append(':')
            append(value)
            append('\n')
        }

        private fun String.strictUtf8Bytes(): ByteArray {
            val encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(this))
            return ByteArray(encoded.remaining()).also { bytes ->
                encoded.get(bytes)
            }
        }

        private fun coverageMismatch(): Nothing {
            throw ClinicalPartitionException.CoverageMismatch()
        }

        private const val HEX = "0123456789abcdef"
    }
}

internal sealed class ClinicalPartitionException(message: String) : Exception(message) {
    class CoverageMismatch :
        ClinicalPartitionException("Clinical report coverage mismatch")

    class MinimumIntervalTooLarge :
        ClinicalPartitionException("Clinical report minimum interval exceeds request budget")
}
