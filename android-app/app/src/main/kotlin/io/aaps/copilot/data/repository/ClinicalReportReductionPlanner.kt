package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

internal sealed class ClinicalReductionException(message: String) : Exception(message) {
    class InvalidDigest :
        ClinicalReductionException("Clinical reduction digest is invalid")

    class DigestTooLarge :
        ClinicalReductionException("Clinical digest exceeds reduction request budget")

    class CoverageMismatch :
        ClinicalReductionException("Clinical reduction coverage mismatch")

    class CannotReduce :
        ClinicalReductionException("Clinical reduction cannot make progress")
}

internal class ClinicalReductionWireTemplate private constructor(
    val prefixBytes: ByteString,
    val suffixBytes: ByteString
) {
    companion object {
        private const val TEMPLATE_VALIDATION_PROBES = 2

        fun create(
            prefixBytes: ByteString,
            suffixBytes: ByteString
        ): ClinicalReductionWireTemplate {
            (1..TEMPLATE_VALIDATION_PROBES).forEach { probeIndex ->
                val sentinel = collisionFreeTemplateSentinel(
                    prefixBytes = prefixBytes,
                    suffixBytes = suffixBytes,
                    probeIndex = probeIndex
                )
                val root = parseStrictJson(
                    bindReductionBody(
                        prefixBytes = prefixBytes,
                        requestInput = sentinel,
                        suffixBytes = suffixBytes
                    )
                )
                validateUniqueStringValue(root, sentinel)
            }
            return ClinicalReductionWireTemplate(
                prefixBytes = prefixBytes,
                suffixBytes = suffixBytes
            )
        }
    }

    internal fun bind(requestInput: String): ByteString =
        bindReductionBody(prefixBytes, requestInput, suffixBytes)
}

internal class ClinicalReductionGroup private constructor(
    val id: String,
    val index: Int,
    val count: Int,
    val fromTs: Long,
    val throughTsExclusive: Long,
    val sources: List<ClinicalChunkDigest>,
    val requestInput: String,
    val requestBodyBytes: ByteString,
    val requestBytes: Int
) {
    companion object {
        fun create(
            index: Int,
            count: Int,
            fromTs: Long,
            throughTsExclusive: Long,
            sources: List<ClinicalChunkDigest>,
            requestInput: String,
            requestBodyBytes: ByteString,
            requestBytes: Int
        ): ClinicalReductionGroup {
            try {
                if (
                    index < 1 ||
                    count < 1 ||
                    index > count ||
                    fromTs >= throughTsExclusive ||
                    sources.isEmpty() ||
                    requestInput.isBlank() ||
                    requestBodyBytes.size == 0 ||
                    requestBytes <= 0 ||
                    requestBytes != requestBodyBytes.size
                ) {
                    coverageMismatch()
                }
                requestInput.strictReductionUtf8Bytes()

                val immutableSources = ArrayList(sources)
                validateReductionSources(immutableSources)
                if (
                    immutableSources.first().fromTs != fromTs ||
                    immutableSources.last().throughTsExclusive !=
                    throughTsExclusive
                ) {
                    coverageMismatch()
                }
                if (requestInput != clinicalReductionInput(immutableSources)) {
                    coverageMismatch()
                }
                val parsedBody = parseStrictJson(requestBodyBytes)
                validateUniqueStringValue(parsedBody, requestInput)

                return ClinicalReductionGroup(
                    id = reductionGroupId(
                        fromTs = fromTs,
                        throughTsExclusive = throughTsExclusive,
                        sources = immutableSources
                    ),
                    index = index,
                    count = count,
                    fromTs = fromTs,
                    throughTsExclusive = throughTsExclusive,
                    sources = Collections.unmodifiableList(immutableSources),
                    requestInput = requestInput,
                    requestBodyBytes = requestBodyBytes,
                    requestBytes = requestBytes
                )
            } catch (failure: ClinicalReductionException) {
                throw failure
            } catch (_: CharacterCodingException) {
                coverageMismatch()
            }
        }
    }
}

internal class ClinicalReductionLevel private constructor(
    val groups: List<ClinicalReductionGroup>
) {
    companion object {
        fun create(groups: List<ClinicalReductionGroup>): ClinicalReductionLevel {
            if (groups.isEmpty()) coverageMismatch()
            val immutableGroups = ArrayList(groups)
            val count = immutableGroups.size
            val groupIds = HashSet<String>(count)
            immutableGroups.forEachIndexed { index, group ->
                if (
                    group.index != index + 1 ||
                    group.count != count ||
                    !groupIds.add(group.id)
                ) {
                    coverageMismatch()
                }
                if (
                    index > 0 &&
                    immutableGroups[index - 1].throughTsExclusive != group.fromTs
                ) {
                    coverageMismatch()
                }
            }
            val flattenedSources = immutableGroups.flatMap { it.sources }
            validateReductionSources(flattenedSources)
            if (
                flattenedSources.size > 1 &&
                immutableGroups.size >= flattenedSources.size
            ) {
                throw ClinicalReductionException.CannotReduce()
            }
            return ClinicalReductionLevel(
                Collections.unmodifiableList(immutableGroups)
            )
        }
    }
}

internal class ClinicalReportReductionPlanner(
    private val requestBudgetBytes: Int,
    private val wireTemplate: ClinicalReductionWireTemplate
) {
    init {
        require(requestBudgetBytes > 0)
    }

    fun nextLevel(digests: List<ClinicalChunkDigest>): ClinicalReductionLevel {
        require(digests.isNotEmpty())
        validateCoverage(digests)

        val pendingGroups = ArrayList<PendingReductionGroup>()
        var startIndex = 0
        while (startIndex < digests.size) {
            val pending = maximalFittingGroup(digests, startIndex)
            pendingGroups += pending
            startIndex = pending.endExclusive
        }

        if (digests.size > 1 && pendingGroups.size >= digests.size) {
            throw ClinicalReductionException.CannotReduce()
        }

        val groupCount = pendingGroups.size
        val groups = pendingGroups.mapIndexedTo(ArrayList(groupCount)) { index, pending ->
            val sources = digests.subList(
                pending.startIndex,
                pending.endExclusive
            )
            val first = sources.first()
            val last = sources.last()
            ClinicalReductionGroup.create(
                index = index + 1,
                count = groupCount,
                fromTs = first.fromTs,
                throughTsExclusive = last.throughTsExclusive,
                sources = sources,
                requestInput = pending.requestInput,
                requestBodyBytes = pending.requestBodyBytes,
                requestBytes = pending.requestBodyBytes.size
            )
        }
        verifyFlattenedCoverage(digests, groups)
        return ClinicalReductionLevel.create(groups)
    }

    private fun maximalFittingGroup(
        digests: List<ClinicalChunkDigest>,
        startIndex: Int
    ): PendingReductionGroup {
        val remaining = digests.size - startIndex
        val probes = HashMap<Int, PendingReductionGroup>()

        fun probe(size: Int): PendingReductionGroup =
            probes.getOrPut(size) {
                val endExclusive = startIndex + size
                val requestInput = clinicalReductionInput(
                    digests.subList(startIndex, endExclusive)
                )
                val requestBodyBytes = wireTemplate.bind(requestInput)
                if (requestBodyBytes.size == 0) coverageMismatch()
                PendingReductionGroup(
                    startIndex = startIndex,
                    endExclusive = endExclusive,
                    requestInput = requestInput,
                    requestBodyBytes = requestBodyBytes
                )
            }

        var best = probe(1)
        if (best.requestBodyBytes.size > requestBudgetBytes) {
            throw ClinicalReductionException.DigestTooLarge()
        }
        if (remaining == 1) return best

        var bestSize = 1
        var probeSize = 2
        var firstFailingSize: Int
        while (true) {
            val boundedProbeSize = minOf(probeSize, remaining)
            val candidate = probe(boundedProbeSize)
            if (candidate.requestBodyBytes.size <= requestBudgetBytes) {
                best = candidate
                bestSize = boundedProbeSize
                if (bestSize == remaining) return best
                probeSize = if (probeSize > remaining / 2) {
                    remaining
                } else {
                    probeSize * 2
                }
            } else {
                firstFailingSize = boundedProbeSize
                break
            }
        }

        var low = bestSize + 1
        var high = firstFailingSize - 1
        while (low <= high) {
            val middle = low + (high - low) / 2
            val candidate = probe(middle)
            if (candidate.requestBodyBytes.size <= requestBudgetBytes) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun validateCoverage(digests: List<ClinicalChunkDigest>) {
        val ids = HashSet<String>(digests.size)
        val hashes = HashSet<String>(digests.size)
        val sourceHashes = HashSet<String>()

        digests.forEachIndexed { index, digest ->
            if (!ids.add(digest.id) || !hashes.add(digest.hash)) {
                coverageMismatch()
            }
            digest.sourceHashes.forEach { sourceHash ->
                if (!sourceHashes.add(sourceHash)) coverageMismatch()
            }
            if (
                index > 0 &&
                digests[index - 1].throughTsExclusive != digest.fromTs
            ) {
                coverageMismatch()
            }
        }
    }

    private fun verifyFlattenedCoverage(
        expected: List<ClinicalChunkDigest>,
        groups: List<ClinicalReductionGroup>
    ) {
        val flattened = groups.flatMap { it.sources }
        if (
            flattened.size != expected.size ||
            flattened.indices.any { flattened[it] !== expected[it] }
        ) {
            coverageMismatch()
        }
    }

    private class PendingReductionGroup(
        val startIndex: Int,
        val endExclusive: Int,
        val requestInput: String,
        val requestBodyBytes: ByteString
    )
}

private fun String.strictReductionUtf8Bytes(): ByteArray {
    val encoded = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(this))
    return ByteArray(encoded.remaining()).also { bytes ->
        encoded.get(bytes)
    }
}

private fun coverageMismatch(): Nothing {
    throw ClinicalReductionException.CoverageMismatch()
}

private fun bindReductionBody(
    prefixBytes: ByteString,
    requestInput: String,
    suffixBytes: ByteString
): ByteString {
    requestInput.strictReductionUtf8Bytes()
    return Buffer()
        .write(prefixBytes)
        .write(JsonPrimitive(requestInput).toString().encodeUtf8())
        .write(suffixBytes)
        .readByteString()
}

private fun parseStrictJson(bytes: ByteString): JsonElement {
    val json = try {
        bytes.toByteArray().strictReductionUtf8()
    } catch (_: CharacterCodingException) {
        coverageMismatch()
    }
    return try {
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) coverageMismatch()
            parsed
        }
    } catch (failure: ClinicalReductionException) {
        throw failure
    } catch (_: JsonParseException) {
        coverageMismatch()
    } catch (_: IOException) {
        coverageMismatch()
    } catch (_: IllegalStateException) {
        coverageMismatch()
    } catch (_: NumberFormatException) {
        coverageMismatch()
    }
}

private fun ByteArray.strictReductionUtf8(): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(this))
        .toString()

private fun validateUniqueStringValue(element: JsonElement, target: String) {
    if (
        countStringValues(element, target) != 1 ||
        countJsonStringOccurrences(element, target) != 1
    ) {
        coverageMismatch()
    }
}

private fun countStringValues(element: JsonElement, target: String): Int =
    when {
        element.isJsonPrimitive -> {
            val primitive = element.asJsonPrimitive
            if (primitive.isString && primitive.asString == target) 1 else 0
        }
        element.isJsonArray -> element.asJsonArray.sumOf {
            countStringValues(it, target)
        }
        element.isJsonObject -> element.asJsonObject.entrySet().sumOf {
            countStringValues(it.value, target)
        }
        else -> 0
    }

private fun countJsonStringOccurrences(
    element: JsonElement,
    target: String
): Int = when {
    element.isJsonPrimitive -> {
        val primitive = element.asJsonPrimitive
        if (primitive.isString && primitive.asString == target) 1 else 0
    }
    element.isJsonArray -> element.asJsonArray.sumOf {
        countJsonStringOccurrences(it, target)
    }
    element.isJsonObject -> element.asJsonObject.entrySet().sumOf { entry ->
        (if (entry.key == target) 1 else 0) +
            countJsonStringOccurrences(entry.value, target)
    }
    else -> 0
}

private fun collisionFreeTemplateSentinel(
    prefixBytes: ByteString,
    suffixBytes: ByteString,
    probeIndex: Int
): String {
    var collisionIndex = 0
    while (true) {
        val seed = Buffer()
            .writeUtf8("clinical-reduction-template-probe-v1")
            .writeInt(probeIndex)
            .writeInt(collisionIndex)
            .writeInt(prefixBytes.size)
            .write(prefixBytes)
            .writeInt(suffixBytes.size)
            .write(suffixBytes)
            .readByteArray()
        val sentinel = buildString {
            append("clinical-reduction-template-check-")
            append(probeIndex)
            append('-')
            append(
                MessageDigest.getInstance("SHA-256")
                    .digest(seed)
                    .toLowercaseHex()
            )
        }
        val rawBytes = sentinel.encodeUtf8()
        val escapedBytes = JsonPrimitive(sentinel).toString().encodeUtf8()
        val collision = prefixBytes.containsEither(rawBytes, escapedBytes) ||
            suffixBytes.containsEither(rawBytes, escapedBytes)
        if (!collision) return sentinel
        collisionIndex = Math.addExact(collisionIndex, 1)
    }
}

private fun ByteString.containsEither(
    first: ByteString,
    second: ByteString
): Boolean = indexOf(first) >= 0 || indexOf(second) >= 0

private fun validateReductionSources(sources: List<ClinicalChunkDigest>) {
    if (sources.isEmpty()) coverageMismatch()
    val ids = HashSet<String>(sources.size)
    val hashes = HashSet<String>(sources.size)
    val sourceHashes = HashSet<String>()
    sources.forEachIndexed { index, digest ->
        if (!ids.add(digest.id) || !hashes.add(digest.hash)) {
            coverageMismatch()
        }
        digest.sourceHashes.forEach { sourceHash ->
            if (!sourceHashes.add(sourceHash)) coverageMismatch()
        }
        if (
            index > 0 &&
            sources[index - 1].throughTsExclusive != digest.fromTs
        ) {
            coverageMismatch()
        }
    }
}

private fun clinicalReductionInput(
    sources: List<ClinicalChunkDigest>
): String = JsonObject().apply {
    addProperty("schema", "clinical-reduction-input")
    addProperty("version", 1)
    addProperty("fromTs", sources.first().fromTs)
    addProperty(
        "throughTsExclusive",
        sources.last().throughTsExclusive
    )
    addProperty("digestCount", sources.size)
    add("digests", JsonArray().apply {
        sources.forEach { digest ->
            add(JsonObject().apply {
                addProperty("canonicalJson", digest.canonicalJson)
                addProperty("hash", digest.hash)
            })
        }
    })
}.toString().also { it.strictReductionUtf8Bytes() }

private fun reductionGroupId(
    fromTs: Long,
    throughTsExclusive: Long,
    sources: List<ClinicalChunkDigest>
): String {
    val canonical = buildString {
        appendReductionField("format", "clinical-reduction-group-v1")
        appendReductionField("fromTs", fromTs.toString())
        appendReductionField(
            "throughTsExclusive",
            throughTsExclusive.toString()
        )
        sources.forEachIndexed { digestIndex, digest ->
            appendReductionField(
                "digest[$digestIndex].hash",
                digest.hash
            )
            digest.sourceHashes.forEachIndexed { sourceIndex, sourceHash ->
                appendReductionField(
                    "digest[$digestIndex].sourceHash[$sourceIndex]",
                    sourceHash
                )
            }
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.strictReductionUtf8Bytes())
        .toLowercaseHex()
}

private fun StringBuilder.appendReductionField(name: String, value: String) {
    append(name)
    append('=')
    append(value.strictReductionUtf8Bytes().size)
    append(':')
    append(value)
    append('\n')
}

private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(REDUCTION_HEX[value ushr 4])
        append(REDUCTION_HEX[value and 0x0f])
    }
}

private const val REDUCTION_HEX = "0123456789abcdef"
