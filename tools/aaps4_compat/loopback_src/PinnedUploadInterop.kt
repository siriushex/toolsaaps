package integration.finalupload

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.networking.NsLoopbackUploadIdentity
import app.aaps.core.nssdk.networking.NsLocalObservationUpload
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ProtocolException
import java.net.URI
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

fun main() {
    var stage = "setup"
    try {
        val identity = NsLoopbackUploadIdentity(
            File(System.getenv("ENTRY_TEST_ROOT_DER")).readBytes(),
            File(System.getenv("ENTRY_TEST_LEAF_DER")).readBytes()
        )
        val ticket = System.getenv("ENTRY_TEST_UPLOAD_BEARER")
        if (ticket != null) {
            val port = URI(System.getenv("ENTRY_TEST_URL")).port
            val client = NsLocalObservationUpload(port, identity, ticket)
            try {
                runBlocking {
                    stage = "ticket-status"
                    check(client.ready())
                    stage = "ticket-glucose"
                    check(client.store(true, Json.parseToJsonElement(
                        """{"app":"AAPS-local","type":"sgv","device":"fixture-sensor","date":1800000000000,"sgv":120,"units":"mg/dl"}"""
                    ).jsonObject))
                    stage = "ticket-profile"
                    check(client.store(false, Json.parseToJsonElement(
                        """{"app":"AAPS-local","eventType":"Note","date":1800000000000,"utcOffset":0,"originalProfileName":"Fixture",
                           "originalCustomizedName":"Fixture","originalTimeshift":0,"originalPercentage":100,
                           "originalDuration":0,"originalEnd":0,"icfg":{"insulinLabel":"Fixture","insulinEndTime":18000000,
                           "insulinPeakTime":4500000,"concentration":1.0},"profileJson":"{\"units\":\"mg/dl\",\"timezone\":\"UTC\",\"carbratio\":[{\"time\":\"00:00\",\"timeAsSeconds\":0,\"value\":10}],\"sens\":[{\"time\":\"00:00\",\"timeAsSeconds\":0,\"value\":45}],\"basal\":[{\"time\":\"00:00\",\"timeAsSeconds\":0,\"value\":0.8}],\"target_low\":[{\"time\":\"00:00\",\"timeAsSeconds\":0,\"value\":100}],\"target_high\":[{\"time\":\"00:00\",\"timeAsSeconds\":0,\"value\":105}]}"}"""
                    ).jsonObject))
                    stage = "ticket-insulin"
                    check(client.store(false, Json.parseToJsonElement(
                        """{"app":"AAPS-local","eventType":"Correction Bolus","date":1800000000000,"utcOffset":0,"insulin":1.2,
                           "type":"NORMAL","pumpId":123,"pumpType":"ACCU_CHEK_COMBO","pumpSerial":"synthetic-pump",
                           "icfg":{"insulinLabel":"Fixture","insulinEndTime":18000000,"insulinPeakTime":4500000,"concentration":1.0}}"""
                    ).jsonObject))
                }
            } finally {
                client.close()
            }
            println("PINNED_LOCAL_WIRE_OK")
            return
        }
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
