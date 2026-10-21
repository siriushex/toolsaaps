package io.aaps.copilot.historyexport

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ClinicalJsonCopyTest {
    @Test fun copyIsStreamedClosedAndByteExact() {
        val file = File.createTempFile("clinical-copy-", ".json")
        try {
            val data = ByteArray(150_000) { (it % 127).toByte() }; file.writeBytes(data)
            var closed = false; var largest = 0
            val output = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) { largest = maxOf(largest, len); super.write(b, off, len) }
                override fun close() { closed = true; super.close() }
            }
            assertEquals(data.size.toLong(), copyPreparedClinicalJson(file, { output }, { output.toByteArray().inputStream() }, {}))
            assertTrue(closed); assertTrue(largest <= 64 * 1024); assertArrayEquals(data, output.toByteArray())
        } finally { file.delete() }
    }
    @Test fun cancellationAndWriteFailureDoNotReturnSuccessAndCloseOutput() {
        val file = File.createTempFile("clinical-copy-", ".json")
        try {
            file.writeBytes(ByteArray(100_000))
            for (cancel in listOf(true, false)) {
                var closed = false; var checks = 0
                val output = object : OutputStream() {
                    override fun write(b: Int) { if (!cancel) throw IOException("write failed") }
                    override fun close() { closed = true }
                }
                try {
                    copyPreparedClinicalJson(file, { output }, { error("Must not verify failed write") }) {
                        if (cancel && ++checks == 2) throw CancellationException()
                    }
                    fail("Expected failure")
                } catch (_: IOException) { assertFalse(cancel) }
                catch (_: CancellationException) { assertTrue(cancel) }
                assertTrue(closed)
            }
        } finally { file.delete() }
    }
    @Test fun truncatedOrCorruptedSavedDocumentIsNeverSuccessful() {
        val file = File.createTempFile("clinical-copy-", ".json")
        try {
            file.writeText("{\"complete\":true}")
            for (saved in listOf("", "{\"complete\":fals}")) {
                assertThrows(IllegalStateException::class.java) {
                    copyPreparedClinicalJson(file, { ByteArrayOutputStream() }, { saved.byteInputStream() }, {})
                }
            }
        } finally { file.delete() }
    }
}
