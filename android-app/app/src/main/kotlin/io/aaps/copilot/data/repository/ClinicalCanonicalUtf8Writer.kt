package io.aaps.copilot.data.repository

import java.io.Writer

/** Bounded character sink whose byte accounting matches String UTF-8 encoding. */
internal class ClinicalCanonicalUtf8Writer(
    private val maxBytes: Int,
    private val expected: String? = null
) : Writer() {
    private val output: StringBuilder? = if (expected == null) {
        StringBuilder(minOf(maxBytes.coerceAtLeast(0), INITIAL_CAPACITY))
    } else {
        null
    }
    private var byteCount = 0
    private var expectedIndex = 0
    private var pendingHighSurrogate: Char? = null
    private var closed = false

    init {
        require(maxBytes >= 0)
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        requireBounds(cbuf.size, off, len)
        ensureOpen()
        for (index in off until off + len) appendCodeUnit(cbuf[index])
    }

    override fun write(str: String, off: Int, len: Int) {
        requireBounds(str.length, off, len)
        ensureOpen()
        for (index in off until off + len) appendCodeUnit(str[index])
    }

    override fun write(c: Int) {
        ensureOpen()
        appendCodeUnit(c.toChar())
    }

    override fun flush() {
        ensureOpen()
    }

    override fun close() {
        if (closed) return
        flushPendingHighSurrogate()
        closed = true
        if (expected != null && expectedIndex != expected.length) invalidInput()
    }

    fun finish(): String {
        check(expected == null) { "Comparison writer does not retain output" }
        close()
        return requireNotNull(output).toString()
    }

    private fun appendCodeUnit(value: Char) {
        pendingHighSurrogate?.let { high ->
            if (value.isLowSurrogate()) {
                appendChecked(4, high, value)
                pendingHighSurrogate = null
                return
            }
            appendChecked(MALFORMED_SURROGATE_UTF8_BYTES, high)
            pendingHighSurrogate = null
        }
        when {
            value.isHighSurrogate() -> pendingHighSurrogate = value
            value.isLowSurrogate() -> appendChecked(MALFORMED_SURROGATE_UTF8_BYTES, value)
            value.code <= 0x7f -> appendChecked(1, value)
            value.code <= 0x7ff -> appendChecked(2, value)
            else -> appendChecked(3, value)
        }
    }

    private fun flushPendingHighSurrogate() {
        pendingHighSurrogate?.let { appendChecked(MALFORMED_SURROGATE_UTF8_BYTES, it) }
        pendingHighSurrogate = null
    }

    private fun appendChecked(bytes: Int, first: Char, second: Char? = null) {
        if (bytes > maxBytes - byteCount) throw ClinicalOpenAiException.DatasetTooLarge()
        appendCharacter(first)
        second?.let(::appendCharacter)
        byteCount += bytes
    }

    private fun appendCharacter(value: Char) {
        val comparison = expected
        if (comparison == null) {
            requireNotNull(output).append(value)
            return
        }
        if (expectedIndex >= comparison.length || comparison[expectedIndex] != value) {
            invalidInput()
        }
        expectedIndex++
    }

    private fun ensureOpen() = check(!closed) { "Writer is closed" }

    private fun requireBounds(size: Int, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= size - length)
    }

    private fun invalidInput(): Nothing = throw ClinicalOpenAiException.InvalidInput()

    companion object {
        fun comparing(maxBytes: Int, expected: String): ClinicalCanonicalUtf8Writer =
            ClinicalCanonicalUtf8Writer(maxBytes, expected)

        private const val INITIAL_CAPACITY = 16 * 1_024
        private const val MALFORMED_SURROGATE_UTF8_BYTES = 1
    }
}
