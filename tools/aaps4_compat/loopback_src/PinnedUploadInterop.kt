package integration.finalupload

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.networking.NsLoopbackUploadIdentity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ProtocolException
import kotlin.system.exitProcess

fun main() {
    var stage = "setup"
    try {
        val identity = NsLoopbackUploadIdentity(
            File(System.getenv("ENTRY_TEST_ROOT_DER")).readBytes(),
            File(System.getenv("ENTRY_TEST_LEAF_DER")).readBytes()
        )
        val client = NSAndroidClientImpl(
            System.getenv("ENTRY_TEST_URL"), System.getenv("ENTRY_TEST_ACCESS"), true,
            { error("Unexpected local request log") }, loopbackUpload = identity
        )
        try {
            runBlocking {
                stage = "status"
                val rights = client.getStatus().apiPermissions
                check(rights.entries.create && rights.treatments.create && !rights.isFull())
                transferSamples(client, 1_800_000_000_000L, verifyReads = false) { stage = it }
                stage = "blocked-read"
                check(runCatching { client.getSgvs() }.exceptionOrNull() is ProtocolException)
            }
        } finally {
            client.close()
        }
        println("PINNED_LOCAL_WIRE_OK")
    } catch (failure: Exception) {
        println("PINNED_LOCAL_WIRE_FAILED:$stage:${failure.javaClass.simpleName}")
        exitProcess(1)
    }
}
