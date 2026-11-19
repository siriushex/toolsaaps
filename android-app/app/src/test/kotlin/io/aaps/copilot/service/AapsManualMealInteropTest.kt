package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AapsManualMealInteropTest {
    @Test fun finalSdkDecodesCopilotRequestAndCopilotAcceptsNativePreview() {
        val cp = System.getenv("AAPS_LOOPBACK_INTEROP_CLASSPATH")?.let(::File)
        val home = System.getenv("AAPS_INTEROP_JAVA_HOME")
        val ready = cp?.isFile == true && !home.isNullOrBlank()
        if (System.getenv("CI") == "true") check(ready)
        assumeTrue(ready)
        val at = 1_800_000_000_000L
        AapsV3TargetProtocol(ByteArray(32) { it.toByte() }).use { protocol ->
            val req = protocol.prepareMeal("11111111-2222-4333-8444-555555555555", 7, at, "manual:meal:sample", 12.0)
            val output = File.createTempFile("meal-preview-", ".json")
            try {
                val p = ProcessBuilder("$home/bin/java", "-cp", checkNotNull(cp).readText(), "integration.mealpreview.MealPreviewInteropKt")
                    .redirectOutput(output).start()
                try {
                    p.outputStream.bufferedWriter().use { it.write(req.envelope.json.toString()); it.newLine() }
                    check(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && output.length() in 1..4096)
                    assertTrue(protocol.verifiedMealPreview(req, HistoryJson.parse(output.readBytes(), 4096), at + 2))
                } finally {
                    if (p.isAlive) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                }
            } finally { output.delete() }
        }
    }
}
