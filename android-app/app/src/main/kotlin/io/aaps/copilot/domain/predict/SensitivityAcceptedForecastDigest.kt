package io.aaps.copilot.domain.predict

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SensitivityAcceptedForecastRow(
    val horizonMinutes: Int,
    val targetTimestamp: Long,
    val valueMmol: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val modelVersion: String
)

data class SensitivityAcceptedForecastDecomposition(
    val trend60Mmol: Double?,
    val therapy60Mmol: Double?,
    val uam60Mmol: Double?,
    val residualRoc0Mmol5: Double?,
    val sigmaEMmol5: Double?,
    val kfSigmaGMmol: Double?,
    val modelVersion: String?
) {
    companion object {
        fun unavailable() = SensitivityAcceptedForecastDecomposition(
            trend60Mmol = null,
            therapy60Mmol = null,
            uam60Mmol = null,
            residualRoc0Mmol5 = null,
            sigmaEMmol5 = null,
            kfSigmaGMmol = null,
            modelVersion = null
        )
    }
}

object SensitivityAcceptedForecastDecompositionCodec {
    fun encode(value: SensitivityAcceptedForecastDecomposition): String {
        require(isValid(value)) { "accepted forecast decomposition is invalid" }
        return buildList {
            add(CODEC_VERSION)
            add(value.trend60Mmol.encodeDouble())
            add(value.therapy60Mmol.encodeDouble())
            add(value.uam60Mmol.encodeDouble())
            add(value.residualRoc0Mmol5.encodeDouble())
            add(value.sigmaEMmol5.encodeDouble())
            add(value.kfSigmaGMmol.encodeDouble())
            add(value.modelVersion.encodeText())
        }.joinToString(SEPARATOR)
    }

    fun decode(encoded: String?): SensitivityAcceptedForecastDecomposition? {
        val parts = encoded?.split(SEPARATOR) ?: return null
        if (parts.size != FIELD_COUNT || parts.first() != CODEC_VERSION) return null
        val numericFields = parts.subList(1, 7)
        if (numericFields.any { it != NULL_VALUE && it.decodeDouble() == null }) return null
        val modelVersion = if (parts[7] == NULL_VALUE) null else parts[7].decodeText() ?: return null
        return SensitivityAcceptedForecastDecomposition(
            trend60Mmol = parts[1].decodeDouble(),
            therapy60Mmol = parts[2].decodeDouble(),
            uam60Mmol = parts[3].decodeDouble(),
            residualRoc0Mmol5 = parts[4].decodeDouble(),
            sigmaEMmol5 = parts[5].decodeDouble(),
            kfSigmaGMmol = parts[6].decodeDouble(),
            modelVersion = modelVersion
        ).takeIf(::isValid)
    }

    private fun isValid(value: SensitivityAcceptedForecastDecomposition): Boolean =
        listOf(
            value.trend60Mmol,
            value.therapy60Mmol,
            value.uam60Mmol,
            value.residualRoc0Mmol5,
            value.sigmaEMmol5,
            value.kfSigmaGMmol
        ).filterNotNull().all { it.isFinite() && kotlin.math.abs(it) <= MAX_ABSOLUTE_VALUE } &&
            (value.modelVersion == null || value.modelVersion.strictUtf8Bytes(MAX_MODEL_BYTES) != null)

    private fun Double?.encodeDouble(): String = this?.let {
        java.lang.Long.toUnsignedString(it.canonicalBits(), 16).padStart(16, '0')
    } ?: NULL_VALUE

    private fun String.decodeDouble(): Double? = if (this == NULL_VALUE) {
        null
    } else if (length != 16) {
        null
    } else {
        runCatching { Double.fromBits(java.lang.Long.parseUnsignedLong(this, 16)) }.getOrNull()
    }

    private fun String?.encodeText(): String = when (this) {
        null -> NULL_VALUE
        else -> requireNotNull(strictUtf8Bytes(MAX_MODEL_BYTES)) {
            "accepted forecast decomposition modelVersion is invalid"
        }.toHex()
    }

    private fun String.decodeText(): String? {
        if (this == NULL_VALUE) return null
        if (length % 2 != 0) return null
        val bytes = runCatching {
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_MODEL_BYTES) return null
        return bytes.strictUtf8StringOrNull()
    }

    private const val CODEC_VERSION = "v1"
    private const val SEPARATOR = "|"
    private const val NULL_VALUE = "-"
    private const val FIELD_COUNT = 8
    private const val MAX_ABSOLUTE_VALUE = 10_000.0
    private const val MAX_MODEL_BYTES = 256
}

object SensitivityAcceptedForecastDigest {
    private val requiredHorizons = setOf(5, 30, 60)

    fun compute(
        cycleId: String,
        settingsRevision: Long,
        forecasts: List<SensitivityAcceptedForecastRow>,
        decomposition: SensitivityAcceptedForecastDecomposition =
            SensitivityAcceptedForecastDecomposition.unavailable()
    ): String? {
        if (!isValidCycleId(cycleId) || settingsRevision < 0L) return null
        val cycleIdBytes = requireNotNull(cycleId.strictUtf8Bytes(MAX_CYCLE_ID_BYTES))
        val grouped = forecasts.groupBy(SensitivityAcceptedForecastRow::horizonMinutes)
        if (grouped.keys != requiredHorizons) return null
        if (requiredHorizons.any { grouped[it]?.size != 1 }) return null
        val rows = requiredHorizons.sorted().map { grouped.getValue(it).single() }
        if (rows.any { row ->
                row.targetTimestamp <= 0L ||
                    !row.valueMmol.isFinite() || !row.ciLow.isFinite() || !row.ciHigh.isFinite() ||
                    row.modelVersion.strictUtf8Bytes(MAX_MODEL_VERSION_BYTES) == null
            }
        ) return null
        val generationTimestamp = rows.mapNotNull { row ->
            runCatching {
                Math.subtractExact(
                    row.targetTimestamp,
                    Math.multiplyExact(row.horizonMinutes.toLong(), 60_000L)
                )
            }.getOrNull()
        }.takeIf { it.size == requiredHorizons.size }
            ?.distinct()
            ?.singleOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        val canonicalDecomposition = runCatching {
            SensitivityAcceptedForecastDecompositionCodec.encode(decomposition)
        }.getOrNull() ?: return null
        val decompositionBytes = canonicalDecomposition.strictUtf8Bytes(MAX_DECOMPOSITION_BYTES)
            ?: return null
        val modelVersionBytes = rows.associateWith { row ->
            row.modelVersion.strictUtf8Bytes(MAX_MODEL_VERSION_BYTES) ?: return null
        }

        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(CANONICAL_VERSION)
                output.writeLengthPrefixed(cycleIdBytes)
                output.writeLong(settingsRevision)
                output.writeLong(generationTimestamp)
                rows.forEach { row ->
                    output.writeInt(row.horizonMinutes)
                    output.writeLong(row.targetTimestamp)
                    output.writeLong(row.valueMmol.canonicalBits())
                    output.writeLong(row.ciLow.canonicalBits())
                    output.writeLong(row.ciHigh.canonicalBits())
                    output.writeLengthPrefixed(modelVersionBytes.getValue(row))
                }
                output.writeLengthPrefixed(decompositionBytes)
            }
            buffer.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun matches(
        cycleId: String,
        settingsRevision: Long,
        forecasts: List<SensitivityAcceptedForecastRow>,
        expectedDigest: String?
    ): Boolean = matches(
        cycleId = cycleId,
        settingsRevision = settingsRevision,
        forecasts = forecasts,
        decomposition = SensitivityAcceptedForecastDecomposition.unavailable(),
        expectedDigest = expectedDigest
    )

    fun matches(
        cycleId: String,
        settingsRevision: Long,
        forecasts: List<SensitivityAcceptedForecastRow>,
        decomposition: SensitivityAcceptedForecastDecomposition,
        expectedDigest: String?
    ): Boolean {
        val expected = expectedDigest?.trim()?.lowercase()?.takeIf { it.length == SHA_256_HEX_LENGTH }
            ?: return false
        return compute(cycleId, settingsRevision, forecasts, decomposition) == expected
    }

    internal fun isValidCycleId(cycleId: String): Boolean =
        cycleId.isNotBlank() && cycleId.strictUtf8Bytes(MAX_CYCLE_ID_BYTES) != null

    private fun DataOutputStream.writeLengthPrefixed(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private const val CANONICAL_VERSION = 2
    private const val SHA_256_HEX_LENGTH = 64
    private const val MAX_CYCLE_ID_BYTES = 512
    private const val MAX_MODEL_VERSION_BYTES = 256
    private const val MAX_DECOMPOSITION_BYTES = 2_048
}

private fun Double.canonicalBits(): Long = if (this == 0.0) 0.0.toRawBits() else toRawBits()

private fun String.strictUtf8Bytes(maxBytes: Int): ByteArray? {
    val encoded = runCatching {
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(this))
    }.getOrNull() ?: return null
    if (encoded.remaining() > maxBytes) return null
    return encoded.toByteArray()
}

private fun ByteArray.strictUtf8StringOrNull(): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
}.getOrNull()

private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also(::get)

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
