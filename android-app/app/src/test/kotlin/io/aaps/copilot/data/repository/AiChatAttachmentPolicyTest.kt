package io.aaps.copilot.data.repository

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AiChatAttachmentPolicyTest {
    @Test fun boundedCopyRejectsOversizeAndRemovesOnlyItsPartialFile() {
        val dir = Files.createTempDirectory("chat-files-").toFile()
        try {
            val target = File(dir, "sample.txt")
            assertFailure { AiChatAttachmentPolicy.copyBounded(ByteArrayInputStream(ByteArray(33)), target, 32) }
            assertFalse(target.exists())
            target.writeText("original")
            assertFailure { AiChatAttachmentPolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1)), target, 32) }
            assertEquals("original", target.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun textIsStrictUtf8CompleteAndNeverSilentlyTruncated() {
        val value = "Measured history\n1,2\n"
        assertEquals(value, AiChatAttachmentPolicy.text(value.toByteArray()))
        listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0), byteArrayOf(1), byteArrayOf(),
            ByteArray(AiChatAttachmentPolicy.MAX_TEXT_BYTES + 1) { 65 }).forEach {
            assertFailure { AiChatAttachmentPolicy.text(it) }
        }
    }

    @Test fun serverTextFilesCarryActualContentWithNoPersonalRoute() {
        val dir = Files.createTempDirectory("chat-files-").toFile()
        try {
            val file = File(dir, "sample.csv").apply { writeText("minute,value\n0,1") }
            val request = request(file)
            val question = AiChatAttachmentPolicy.serverQuestion("Review this file", listOf(request))
            assertTrue(question.contains("minute,value"))
            assertTrue(question.contains("Review this file"))
            assertTrue(question.contains("untrusted"))
            assertFailure { AiChatAttachmentPolicy.serverQuestion("q", List(5) { request }) }
            assertFailure { AiChatAttachmentPolicy.serverQuestion("q", listOf(request.copy(kind = AiChatAttachmentRequest.Kind.IMAGE))) }
            assertFailure { AiChatAttachmentPolicy.serverQuestion("q", listOf(request.copy(name = "sample.pdf", mimeType = "application/pdf"))) }
            assertFailure { AiChatAttachmentPolicy.serverQuestion("q".repeat(4096), listOf(request)) }
            file.writeText("x".repeat(AiChatAttachmentPolicy.MAX_SERVER_TEXT_BYTES + 1))
            assertFailure { AiChatAttachmentPolicy.serverQuestion("q", listOf(request)) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingEmptyAndBinaryFilesNeverBecomeMetadataOnlyAnswers() {
        val dir = Files.createTempDirectory("chat-files-").toFile()
        try {
            val file = File(dir, "sample.txt")
            assertFailure { AiChatAttachmentPolicy.load(listOf(request(file))) }
            file.writeBytes(byteArrayOf())
            assertFailure { AiChatAttachmentPolicy.load(listOf(request(file))) }
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertFailure { AiChatAttachmentPolicy.load(listOf(request(file))) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun fileAndTotalLimitsAndTypedMagicAreEnforced() {
        assertTrue(AiChatAttachmentPolicy.isText("table.csv", "text/csv"))
        assertFalse(AiChatAttachmentPolicy.isText("photo.png", "text/plain"))
        assertEquals("image/png", AiChatAttachmentPolicy.imageMime(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)))
        assertFailure { AiChatAttachmentPolicy.imageMime("not an image".toByteArray()) }
        assertFailure { AiChatAttachmentPolicy.checkBudget(listOf(1L, 2L, 3L, 4L, 5L)) }
        assertFailure { AiChatAttachmentPolicy.checkBudget(listOf(4_194_305L)) }
        assertFailure { AiChatAttachmentPolicy.checkBudget(listOf(4_194_304L, 4_194_304L, 1L)) }
    }

    private fun request(file: File) = AiChatAttachmentRequest(file.name, "text/plain", file.path, AiChatAttachmentRequest.Kind.FILE)
    private fun assertFailure(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
}
