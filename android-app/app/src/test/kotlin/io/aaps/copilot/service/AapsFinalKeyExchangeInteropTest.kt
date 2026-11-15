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

class AapsFinalKeyExchangeInteropTest {
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
