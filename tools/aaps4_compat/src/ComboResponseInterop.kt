package integration.entries

import app.aaps.core.nssdk.localmodel.clientcontrol.AckEnvelope
import app.aaps.core.nssdk.localmodel.clientcontrol.AckPhase
import app.aaps.core.nssdk.localmodel.clientcontrol.AckStatus
import app.aaps.core.nssdk.utils.ClientControlCrypto
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Offline synthetic response signed by the pinned, unchanged native SDK. */
fun main() {
    try {
        val bytes = System.`in`.readNBytes(60 * 1024 + 1)
        check(bytes.size in 1..60 * 1024)
        val input = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        check(input.keys == setOf("status", "payload"))
        val status = when (input.getValue("status").jsonPrimitive.content) {
            "Ok" -> AckStatus.Ok
            "Failed" -> AckStatus.Failed
            "Expired" -> AckStatus.Expired
            else -> error("invalid_status")
        }
        val payload = input.getValue("payload").takeUnless { it == JsonNull }?.jsonPrimitive?.content
        check((status == AckStatus.Ok) == (payload != null))
        val result = ClientControlCrypto.signAck(ByteArray(32) { it.toByte() }, AckEnvelope(
            "11111111-2222-4333-8444-555555555555", 3, AckPhase.Done, status,
            reason = if (status == AckStatus.Ok) null else "unavailable", payload = payload,
            timestamp = 1_800_000_000_001, signature = ""))
        print(Json { encodeDefaults = true }.encodeToJsonElement(AckEnvelope.serializer(), result))
    } catch (_: Exception) {
        System.err.print("response_protocol_check_failed")
        exitProcess(1)
    }
}
