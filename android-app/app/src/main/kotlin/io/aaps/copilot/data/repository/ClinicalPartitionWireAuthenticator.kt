package io.aaps.copilot.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ClinicalLeafWireExpectation(
    val currentJson: String,
    val detailFromMinute: Long,
    val detailThroughMinute: Long
) {
    companion object {
        fun from(dataset: ClinicalReportDataset): ClinicalLeafWireExpectation = try {
            ClinicalLeafWireExpectation(
                currentJson = ClinicalCurrentWireSchema.encode(dataset.currentSnapshot).toString(),
                detailFromMinute = Math.floorDiv(
                    Math.subtractExact(dataset.detail24h.fromTs, dataset.generatedAt),
                    60_000L
                ),
                detailThroughMinute = Math.floorDiv(
                    Math.subtractExact(dataset.detail24h.throughTs, dataset.generatedAt),
                    60_000L
                )
            )
        } catch (_: ArithmeticException) {
            mismatch()
        }
    }
}

internal data class ClinicalPartitionRootWireProjection(
    val currentJson: String,
    val summary7dJson: String,
    val summary30dJson: String
)

internal object ClinicalPartitionWireAuthenticator {
    suspend fun verify(
        rootCanonicalJson: String,
        leaves: List<ClinicalPartitionLeaf>,
        probe: ClinicalDatasetSerializationProbe?
    ): ClinicalPartitionRootWireProjection {
        try {
            val root = parseDataset(rootCanonicalJson, probe)
            val actualRows = ROW_PATHS.associateWith { linkedMapOf<String, Int>() }
            leaves.forEach { leaf ->
                currentCoroutineContext().ensureActive()
                val parsed = parseDataset(leaf.canonicalJson, probe)
                if (parsed.stableProjection != root.stableProjection ||
                    parsed.currentJson != leaf.wireExpectation.currentJson ||
                    parsed.detailFromMinute != leaf.wireExpectation.detailFromMinute ||
                    parsed.detailThroughMinute != leaf.wireExpectation.detailThroughMinute
                ) {
                    mismatch()
                }
                parsed.rows.forEach { (path, counts) ->
                    val aggregate = actualRows.getValue(path)
                    counts.forEach { (row, count) ->
                        aggregate[row] = try {
                            Math.addExact(aggregate[row] ?: 0, count)
                        } catch (_: ArithmeticException) {
                            mismatch()
                        }
                    }
                }
            }
            if (root.rows != actualRows) mismatch()
            return ClinicalPartitionRootWireProjection(
                currentJson = root.currentJson,
                summary7dJson = root.summary7dJson,
                summary30dJson = root.summary30dJson
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (mismatch: ClinicalPartitionException.CoverageMismatch) {
            throw mismatch
        } catch (_: Exception) {
            mismatch()
        }
    }

    private suspend fun parseDataset(
        canonicalJson: String,
        probe: ClinicalDatasetSerializationProbe?
    ): ParsedWireDataset {
        val fields = linkedSetOf<String>()
        val stable = linkedMapOf<String, String>()
        val rows = ROW_PATHS.associateWith { linkedMapOf<String, Int>() }
        var currentJson: String? = null
        var detailFromMinute: Long? = null
        var detailThroughMinute: Long? = null
        var summary7dJson: String? = null
        var summary30dJson: String? = null
        JsonReader(StringReader(canonicalJson)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                currentCoroutineContext().ensureActive()
                val name = reader.nextName()
                if (!fields.add(name)) mismatch()
                when (name) {
                    "v", "generatedAt", "zone", "s24", "ep" ->
                        stable[name] = readCanonicalElement(reader)
                    "c" -> {
                        val current = readCanonicalElementValue(reader)
                        if (!current.isJsonObject ||
                            !ClinicalCurrentWireSchema.isValidWireObject(current.asJsonObject)
                        ) {
                            mismatch()
                        }
                        currentJson = current.toString()
                    }
                    "d24" -> {
                        val detail = readDetail(reader, rows, probe)
                        detailFromMinute = detail.fromMinute
                        detailThroughMinute = detail.throughMinute
                        stable["d24/fq"] = detail.forecastQualityJson
                    }
                    "g7", "e7", "t7", "g30", "e30", "t30", "pa",
                    "ev24", "ev7", "ev30" -> readRows(reader, rows.getValue(name), probe)
                    "s7" -> {
                        val json = readCanonicalElement(reader)
                        summary7dJson = json
                        stable[name] = json
                    }
                    "s30" -> {
                        val json = readCanonicalElement(reader)
                        summary30dJson = json
                        stable[name] = json
                    }
                    else -> mismatch()
                }
            }
            reader.endObject()
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) mismatch()
        }
        if (!fields.containsAll(REQUIRED_ROOT_FIELDS) ||
            fields.any { it !in REQUIRED_ROOT_FIELDS && it !in OPTIONAL_ROOT_FIELDS } ||
            fields.contains("ep") != fields.contains("pa")
        ) {
            mismatch()
        }
        if (!fields.contains("pa")) rows.getValue("pa").clear()
        return ParsedWireDataset(
            rows = rows,
            stableProjection = stable,
            currentJson = currentJson ?: mismatch(),
            detailFromMinute = detailFromMinute ?: mismatch(),
            detailThroughMinute = detailThroughMinute ?: mismatch(),
            summary7dJson = summary7dJson ?: mismatch(),
            summary30dJson = summary30dJson ?: mismatch()
        )
    }

    private suspend fun readDetail(
        reader: JsonReader,
        rows: Map<String, MutableMap<String, Int>>,
        probe: ClinicalDatasetSerializationProbe?
    ): ParsedDetail {
        val fields = linkedSetOf<String>()
        var fromMinute: Long? = null
        var throughMinute: Long? = null
        var forecastQuality: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            val name = reader.nextName()
            if (!fields.add(name)) mismatch()
            when (name) {
                "from" -> fromMinute = reader.nextLong()
                "through" -> throughMinute = reader.nextLong()
                "g", "gc", "e", "t", "f", "m" ->
                    readRows(reader, rows.getValue("d24/$name"), probe)
                "fq" -> forecastQuality = readCanonicalElement(reader)
                else -> mismatch()
            }
        }
        reader.endObject()
        if (fields != DETAIL_FIELDS) mismatch()
        return ParsedDetail(
            fromMinute = fromMinute ?: mismatch(),
            throughMinute = throughMinute ?: mismatch(),
            forecastQualityJson = forecastQuality ?: mismatch()
        )
    }

    private suspend fun readRows(
        reader: JsonReader,
        counts: MutableMap<String, Int>,
        probe: ClinicalDatasetSerializationProbe?
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            probe?.onWireCoverageRowRead()
            currentCoroutineContext().ensureActive()
            val row = readCanonicalElementValue(reader)
            if (!row.isJsonArray && !row.isJsonObject) mismatch()
            val canonical = row.toString()
            counts[canonical] = try {
                Math.addExact(counts[canonical] ?: 0, 1)
            } catch (_: ArithmeticException) {
                mismatch()
            }
        }
        reader.endArray()
    }

    private fun readCanonicalElement(reader: JsonReader): String =
        readCanonicalElementValue(reader).toString()

    private fun readCanonicalElementValue(reader: JsonReader): JsonElement = try {
        JsonParser.parseReader(reader)
    } catch (_: Exception) {
        mismatch()
    }

    private data class ParsedDetail(
        val fromMinute: Long,
        val throughMinute: Long,
        val forecastQualityJson: String
    )

    private data class ParsedWireDataset(
        val rows: Map<String, MutableMap<String, Int>>,
        val stableProjection: Map<String, String>,
        val currentJson: String,
        val detailFromMinute: Long,
        val detailThroughMinute: Long,
        val summary7dJson: String,
        val summary30dJson: String
    )

    private val REQUIRED_ROOT_FIELDS = setOf(
        "v", "generatedAt", "zone", "c", "d24",
        "g7", "e7", "t7", "g30", "e30", "t30", "s7", "s30",
        "ev24", "ev7", "ev30"
    )
    private val OPTIONAL_ROOT_FIELDS = setOf("s24", "ep", "pa")
    private val DETAIL_FIELDS = setOf("from", "through", "g", "gc", "e", "t", "f", "fq", "m")
    private val ROW_PATHS = listOf(
        "d24/g", "d24/gc", "d24/e", "d24/t", "d24/f", "d24/m",
        "g7", "e7", "t7", "g30", "e30", "t30", "pa", "ev24", "ev7", "ev30"
    )
}

private fun mismatch(): Nothing = throw ClinicalPartitionException.CoverageMismatch()
