package io.aaps.copilot.data.repository

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ClinicalRequestWireTemplate private constructor(
    val prefix: ByteArray,
    val suffix: ByteArray
) {
    companion object {
        fun capture(body: ByteArray, quotedMarker: ByteArray): ClinicalRequestWireTemplate {
            val index = body.indexOfSubsequence(quotedMarker)
            if (index < 0 || body.indexOfSubsequence(quotedMarker, index + quotedMarker.size) >= 0) {
                throw ClinicalOpenAiException.InvalidInput()
            }
            return ClinicalRequestWireTemplate(
                prefix = body.copyOfRange(0, index),
                suffix = body.copyOfRange(index + quotedMarker.size, body.size)
            )
        }
    }
}

internal class ClinicalSegmentedJsonInput private constructor(
    private val segments: List<String>
) {
    suspend fun measuredRequestBytes(
        template: ClinicalRequestWireTemplate,
        maxBytes: Int,
        probe: ClinicalDatasetSerializationProbe?
    ): Int {
        require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE)
        var bytes = boundedAdd(template.prefix.size, template.suffix.size, maxBytes)
        bytes = boundedAdd(bytes, 2, maxBytes)
        if (bytes > maxBytes) return maxBytes + 1
        forEachEscapedByteCount(probe) { count ->
            bytes = boundedAdd(bytes, count, maxBytes)
            bytes <= maxBytes
        }
        return bytes
    }

    suspend fun requestBodyBytes(
        template: ClinicalRequestWireTemplate,
        exactBytes: Int,
        probe: ClinicalDatasetSerializationProbe?
    ): ByteArray {
        if (exactBytes < template.prefix.size + template.suffix.size + 2) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        val output = ByteArray(exactBytes)
        var offset = 0
        template.prefix.copyInto(output, offset)
        offset += template.prefix.size
        output[offset++] = '"'.code.toByte()
        forEachEscapedByte(probe) { value ->
            if (offset >= output.size) throw ClinicalOpenAiException.InvalidInput()
            output[offset++] = value
        }
        if (offset >= output.size) throw ClinicalOpenAiException.InvalidInput()
        output[offset++] = '"'.code.toByte()
        template.suffix.copyInto(output, offset)
        offset += template.suffix.size
        if (offset != exactBytes) throw ClinicalOpenAiException.InvalidInput()
        currentCoroutineContext().ensureActive()
        probe?.onClinicalRequestBodyMaterialized()
        return output
    }

    fun materialize(probe: ClinicalDatasetSerializationProbe?): String {
        probe?.onClinicalInputMaterialized()
        val capacity = segments.fold(0) { total, segment ->
            try {
                Math.addExact(total, segment.length)
            } catch (_: ArithmeticException) {
                throw ClinicalOpenAiException.InvalidInput()
            }
        }
        return buildString(capacity) { segments.forEach(::append) }
    }

    private suspend fun forEachEscapedByteCount(
        probe: ClinicalDatasetSerializationProbe?,
        accept: (Int) -> Boolean
    ) {
        var processed = 0
        segments.forEach { segment ->
            var index = 0
            while (index < segment.length) {
                if (processed % CANCELLATION_CHECKPOINT_CHARS == 0) {
                    probe?.onTransportInputChunkProcessed()
                    currentCoroutineContext().ensureActive()
                }
                if (!accept(jsonStringCharacterByteCount(segment, index))) return
                index += jsonStringCharacterWidth(segment, index)
                processed++
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun forEachEscapedByte(
        probe: ClinicalDatasetSerializationProbe?,
        accept: (Byte) -> Unit
    ) {
        var processed = 0
        segments.forEach { segment ->
            var index = 0
            while (index < segment.length) {
                if (processed % CANCELLATION_CHECKPOINT_CHARS == 0) {
                    probe?.onTransportInputChunkProcessed()
                    currentCoroutineContext().ensureActive()
                }
                emitJsonStringCharacter(segment, index, accept)
                index += jsonStringCharacterWidth(segment, index)
                processed++
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun boundedAdd(left: Int, right: Int, maxBytes: Int): Int {
        val sum = left.toLong() + right.toLong()
        return if (sum > maxBytes) maxBytes + 1 else sum.toInt()
    }

    companion object {
        fun of(vararg segments: String): ClinicalSegmentedJsonInput =
            ClinicalSegmentedJsonInput(segments.toList())

        private const val CANCELLATION_CHECKPOINT_CHARS = 4_096
    }
}

private fun jsonStringCharacterWidth(value: String, index: Int): Int =
    if (Character.isHighSurrogate(value[index]) &&
        index + 1 < value.length && Character.isLowSurrogate(value[index + 1])
    ) {
        2
    } else {
        1
    }

private fun jsonStringCharacterByteCount(value: String, index: Int): Int {
    val character = value[index]
    jsonEscape(character)?.let { return it.length }
    if (character == '\u2028' || character == '\u2029' || character.code < 0x20) return 6
    val codePoint = when {
        Character.isHighSurrogate(character) &&
            index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) ->
            Character.toCodePoint(character, value[index + 1])
        Character.isSurrogate(character) -> '?'.code
        else -> character.code
    }
    return codePoint.utf8ByteCount()
}

private fun emitJsonStringCharacter(value: String, index: Int, accept: (Byte) -> Unit) {
    val character = value[index]
    jsonEscape(character)?.let { escape ->
        escape.forEach { accept(it.code.toByte()) }
        return
    }
    if (character == '\u2028' || character == '\u2029' || character.code < 0x20) {
        accept('\\'.code.toByte())
        accept('u'.code.toByte())
        for (shift in 12 downTo 0 step 4) {
            accept(HEX[(character.code shr shift) and 0xf].code.toByte())
        }
        return
    }
    val codePoint = when {
        Character.isHighSurrogate(character) &&
            index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) ->
            Character.toCodePoint(character, value[index + 1])
        Character.isSurrogate(character) -> '?'.code
        else -> character.code
    }
    codePoint.emitUtf8(accept)
}

private fun Int.utf8ByteCount(): Int = when {
    this <= 0x7f -> 1
    this <= 0x7ff -> 2
    this <= 0xffff -> 3
    else -> 4
}

private fun Int.emitUtf8(accept: (Byte) -> Unit) {
    when {
        this <= 0x7f -> accept(toByte())
        this <= 0x7ff -> {
            accept((0xc0 or (this shr 6)).toByte())
            accept((0x80 or (this and 0x3f)).toByte())
        }
        this <= 0xffff -> {
            accept((0xe0 or (this shr 12)).toByte())
            accept((0x80 or ((this shr 6) and 0x3f)).toByte())
            accept((0x80 or (this and 0x3f)).toByte())
        }
        else -> {
            accept((0xf0 or (this shr 18)).toByte())
            accept((0x80 or ((this shr 12) and 0x3f)).toByte())
            accept((0x80 or ((this shr 6) and 0x3f)).toByte())
            accept((0x80 or (this and 0x3f)).toByte())
        }
    }
}

internal suspend fun clinicalSha256Cancellable(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = ByteArray(4)
    var index = 0
    var processed = 0
    while (index < value.length) {
        if (processed % 4_096 == 0) currentCoroutineContext().ensureActive()
        val character = value[index]
        val codePoint = when {
            Character.isHighSurrogate(character) &&
                index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) ->
                Character.toCodePoint(character, value[index + 1])
            Character.isSurrogate(character) -> '?'.code
            else -> character.code
        }
        var count = 0
        codePoint.emitUtf8 { byte -> bytes[count++] = byte }
        digest.update(bytes, 0, count)
        index += if (codePoint > 0xffff) 2 else 1
        processed++
    }
    currentCoroutineContext().ensureActive()
    val result = digest.digest()
    return buildString(result.size * 2) {
        result.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0xf])
        }
    }
}

internal suspend fun clinicalUtf8SizeAtMost(value: String, maxBytes: Int): Int {
    require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE)
    var bytes = 0
    var index = 0
    var processed = 0
    while (index < value.length) {
        if (processed % 4_096 == 0) currentCoroutineContext().ensureActive()
        val character = value[index]
        val codePoint = when {
            Character.isHighSurrogate(character) &&
                index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) ->
                Character.toCodePoint(character, value[index + 1])
            Character.isSurrogate(character) -> '?'.code
            else -> character.code
        }
        val width = codePoint.utf8ByteCount()
        if (width > maxBytes - bytes) return maxBytes + 1
        bytes += width
        index += if (codePoint > 0xffff) 2 else 1
        processed++
    }
    currentCoroutineContext().ensureActive()
    return bytes
}

private fun ByteArray.indexOfSubsequence(target: ByteArray, fromIndex: Int = 0): Int {
    if (target.isEmpty()) return fromIndex.coerceIn(0, size)
    for (index in fromIndex.coerceAtLeast(0)..size - target.size) {
        var matches = true
        for (targetIndex in target.indices) {
            if (this[index + targetIndex] != target[targetIndex]) {
                matches = false
                break
            }
        }
        if (matches) return index
    }
    return -1
}

private fun jsonEscape(character: Char): String? = when (character) {
    '"' -> "\\\""
    '\\' -> "\\\\"
    '\t' -> "\\t"
    '\b' -> "\\b"
    '\n' -> "\\n"
    '\r' -> "\\r"
    '\u000c' -> "\\f"
    else -> null
}

private const val HEX = "0123456789abcdef"
