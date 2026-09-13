package io.aaps.copilot.receiver

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class LocalDataBroadcastReceiverLifecycleSourceTest {

    @Test
    fun pendingResultIsFinishedExactlyOnceFromFinally() {
        val source = receiverSource()
        val finishCall = "pendingResult.finish()"
        val finallyBlock = source.indexOf("} finally {")
        val finishIndex = source.indexOf(finishCall)

        assertThat(source.windowed(finishCall.length).count { it == finishCall }).isEqualTo(1)
        assertThat(finallyBlock).isAtLeast(0)
        assertThat(finishIndex).isGreaterThan(finallyBlock)
    }

    @Test
    fun auditFailureCannotBypassPendingResultCompletion() {
        val source = receiverSource()
        val failureAudit = source.indexOf("\"broadcast_ingest_failed\"")
        val auditFailureCatch = source.indexOf("catch (auditError: Throwable)", startIndex = failureAudit)
        val auditFailureFilter = source.indexOf(
            "auditError.rethrowIfCancellationOrFatal()",
            startIndex = auditFailureCatch
        )
        val finallyBlock = source.indexOf("} finally {", startIndex = auditFailureFilter)
        val finishCall = source.indexOf("pendingResult.finish()", startIndex = finallyBlock)

        assertThat(failureAudit).isAtLeast(0)
        assertThat(auditFailureCatch).isGreaterThan(failureAudit)
        assertThat(auditFailureFilter).isGreaterThan(auditFailureCatch)
        assertThat(finallyBlock).isGreaterThan(auditFailureFilter)
        assertThat(finishCall).isGreaterThan(finallyBlock)
    }

    @Test
    fun cancellationAndFatalJvmErrorsAreRethrownInsteadOfAuditedOrSwallowed() {
        val source = receiverSource()

        assertThat(source).contains("error.rethrowIfCancellationOrFatal()")
        assertThat(source).contains("is CancellationException")
        assertThat(source).contains("is VirtualMachineError")
        assertThat(source).contains("is LinkageError")
        assertThat(source).contains("is ThreadDeath")
    }

    private fun receiverSource(): String = sourceFile(
        "src/main/kotlin/io/aaps/copilot/receiver/LocalDataBroadcastReceiver.kt"
    ).readText()

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }
}
