package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

internal fun finalSdkTokenAnswer(point: String, token: String?): JsonObject {
    val cp = System.getenv("AAPS_FINAL_INTEROP_CLASSPATH")?.let(::File)
    val java = System.getenv("AAPS_INTEROP_JAVA_HOME")
    val ready = cp?.isFile == true && !java.isNullOrBlank()
    if (System.getenv("CI") == "true") check(ready)
    assumeTrue(ready)
    val out = File.createTempFile("local-pair-interop-", ".json")
    val p = ProcessBuilder("$java/bin/java", "-cp", checkNotNull(cp).readText(), "integration.finalupload.FinalKeyExchangeInteropKt")
        .redirectErrorStream(true).redirectOutput(out).start()
    return try {
        p.outputStream.use { it.write((point + (token?.let { value -> "\n$value" } ?: "")).encodeToByteArray()) }
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && out.length() in 1..4096)
        HistoryJson.obj(HistoryJson.parse(out.readBytes(), 4096).getValue("answer"))
    } finally {
        if (p.isAlive) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        out.delete()
    }
}

class AapsFinalKeyExchangeInteropTest {
    @Test fun tokenVerifierOnlySeesAnAuthenticatedPlaintextAndCannotRetainTheBuffer() {
        AapsFinalKeyExchange.begin().use { client ->
            val token = "pair-test-local"
            val answer = finalSdkTokenAnswer(client.publicPoint, token)
            val id = "11111111-2222-4333-8444-555555555555"
            val at = 1_800_000_000_000L
            val initial = ByteArray(32) { it.toByte() }
            fun finish(doc: JsonObject, verify: (ByteArray) -> Boolean) =
                AapsFinalKeyExchange.finish(doc, id, client.publicPoint, client.privateDer, initial, at, at + 120_000, at + 1, verify)
            assertNull(finish(JsonObject(answer - "tokenIv")) { throw AssertionError("Incomplete answer reached issuer") })
            assertNull(finish(JsonObject(answer + ("signature" to JsonPrimitive("0".repeat(64))))) { throw AssertionError("Bad MAC reached issuer") })
            var calls = 0
            var observed: ByteArray? = null
            assertNull(finish(answer) { bytes -> calls++; observed = bytes; false })
            assertTrue(calls == 1 && checkNotNull(observed).all { it == 0.toByte() })
            checkNotNull(finish(answer) { bytes -> bytes.contentEquals(token.encodeToByteArray()) }).fill(0)
            initial.fill(0)
        }
    }

    @Test fun finalSdkAndCopilotDeriveTheSameRotatedSecret() {
        val classpath = System.getenv("AAPS_FINAL_INTEROP_CLASSPATH")?.let(::File)
        val javaHome = System.getenv("AAPS_INTEROP_JAVA_HOME")
        val ready = classpath?.isFile == true && !javaHome.isNullOrBlank()
        if (System.getenv("CI") == "true") check(ready) { "Missing required final SDK fixture" }
        assumeTrue("Run tools/aaps4_compat/prepare_final.sh", ready)
        AapsFinalKeyExchange.begin().use { client ->
            val output = File.createTempFile("pairing-interop-", ".json")
            val process = ProcessBuilder("$javaHome/bin/java", "-cp", checkNotNull(classpath).readText(),
                "integration.finalupload.FinalKeyExchangeInteropKt")
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                process.outputStream.use { it.write(client.publicPoint.encodeToByteArray()) }
                assertTrue("Final SDK key fixture timed out", process.waitFor(30, TimeUnit.SECONDS))
                assertTrue("Final SDK key fixture failed", process.exitValue() == 0)
                assertTrue(output.length() in 1..4096)
                val result = HistoryJson.parse(output.readBytes(), 4096)
                val answer = HistoryJson.obj(result.getValue("answer"))
                val expected = Base64.getDecoder().decode(HistoryJson.string(result.getValue("syntheticExpected")))
                val start = 1_800_000_000_000L
                val id = "11111111-2222-4333-8444-555555555555"
                val initial = ByteArray(32) { it.toByte() }
                val actual = checkNotNull(AapsFinalKeyExchange.finish(answer, id, client.publicPoint, client.privateDer,
                    initial, start, start + 120_000, start + 1))
                try {
                    assertTrue("Synthetic secret mismatch", actual.contentEquals(expected))
                    assertNull(AapsFinalKeyExchange.finish(answer, id, client.publicPoint, client.privateDer,
                        initial, start, start + 120_000, start + 120_000))
                    val wrongIdentity = JsonObject(answer + ("clientId" to JsonPrimitive("other")))
                    assertNull(AapsFinalKeyExchange.finish(wrongIdentity, id, client.publicPoint, client.privateDer,
                        initial, start, start + 120_000, start + 1))
                } finally { initial.fill(0); expected.fill(0); actual.fill(0) }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                output.delete()
            }
        }
    }
}
