package io.aaps.copilot.service

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class AapsV3ComboReadProtocolTest {
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val id = "00000000-0000-4000-8000-000000000001"
    private val snapshotId = "00000000-0000-4000-8000-000000000002"
    private val at = 1_800_000_000_000L
    private val chunk = AapsV3ComboReadSpec.Chunk(snapshotId, id, "a".repeat(64), "b".repeat(64), 32_771, 32_768)

    @Test fun typedQueriesExposeNoTherapyParametersAndUseBoundedChunkGeometry() {
        AapsV3ComboReadProtocol(ByteArray(32)).use { p ->
            val request = p.request(client, master, id, 3, at, chunk).json
            val payload = Json.parseToJsonElement(request.getValue("payload").jsonPrimitive.content).jsonObject
            val query = payload.getValue("query").jsonObject
            assertEquals(3, query.getValue("length").jsonPrimitive.int)
            assertEquals("combo_history_read_v1", request.getValue("type").jsonPrimitive.content)
            assertEquals(setOf("type", "version", "masterInstallId", "requestId", "query"), payload.keys)
            assertEquals(setOf("kind", "snapshotId", "readId", "pumpBinding", "sha256", "totalBytes", "offset", "length"), query.keys)
            assertTrue(request.toString().toByteArray().size < 4096)
            assertFalse(p.toString().contains(client))
        }
    }

    @Test fun malformedQueriesClosedProtocolAndInvalidIdentityAreRefused() {
        val p = AapsV3ComboReadProtocol(ByteArray(32))
        val bad = listOf(AapsV3ComboReadSpec.Inventory(limit = 0), AapsV3ComboReadSpec.Inventory(limit = 33),
            AapsV3ComboReadSpec.Inventory(snapshotId = snapshotId),
            AapsV3ComboReadSpec.Inventory(snapshotId = snapshotId, afterReadId = "../read"),
            chunk.copy(offset = 1), chunk.copy(totalBytes = Int.MAX_VALUE), chunk.copy(pumpBinding = "invalid"))
        bad.forEach { assertThrows(Exception::class.java) { p.request(client, master, id, 3, at, it) } }
        assertThrows(Exception::class.java) { p.request(client, client, id, 3, at, chunk) }
        assertThrows(Exception::class.java) { p.request(client, master, id, 1, at, chunk) }
        assertThrows(Exception::class.java) { p.request(client, master, id, 3, Long.MAX_VALUE, chunk) }
        p.close(); p.close()
        assertThrows(IllegalStateException::class.java) { p.request(client, master, id, 3, at, chunk) }
    }

    @Test fun nativeRc1AuthenticatesReadEnvelopeButRefusesUnknownMessageType() {
        val classpath = System.getenv("AAPS_INTEROP_CLASSPATH")?.let(::File)
        val javaHome = System.getenv("AAPS_INTEROP_JAVA_HOME")
        if (System.getenv("CI") == "true") check(classpath?.isFile == true && !javaHome.isNullOrBlank())
        Assume.assumeTrue("Prepare native RC1 test drivers", classpath?.isFile == true && !javaHome.isNullOrBlank())
        val output = File.createTempFile("read-protocol-", ".out")
        val errors = File.createTempFile("read-protocol-", ".err")
        try {
            AapsV3ComboReadProtocol(ByteArray(32) { it.toByte() }).use { protocol ->
                for (spec in listOf(AapsV3ComboReadSpec.Inventory(),
                    AapsV3ComboReadSpec.Inventory(snapshotId, id), chunk)) {
                    val process = ProcessBuilder("$javaHome/bin/java", "-cp", classpath!!.readText(),
                        "integration.entries.ComboReadRequestInteropKt").redirectOutput(output).redirectError(errors).start()
                    try {
                        process.outputStream.use { it.write(protocol.request(client, master, id, 3, at, spec).json.toString().toByteArray()) }
                        assertTrue("Native request check timed out", process.waitFor(30, TimeUnit.SECONDS))
                        assertEquals("Native request check failed", 0, process.exitValue())
                        assertEquals("native_signature_ok_extension_unsupported", output.readText())
                    } finally {
                        if (process.isAlive) { process.destroyForcibly(); check(process.waitFor(10, TimeUnit.SECONDS)) }
                    }
                }
            }
        } finally { output.delete(); errors.delete() }
    }
}
