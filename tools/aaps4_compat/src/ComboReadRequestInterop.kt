package integration.entries

import app.aaps.core.nssdk.localmodel.clientcontrol.ClientControlMessage
import app.aaps.core.nssdk.localmodel.clientcontrol.SignedEnvelope
import app.aaps.core.nssdk.utils.ClientControlCrypto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Offline synthetic check: native MAC compatibility never implies support for the extension. */
fun main() {
    try {
        val bytes = System.`in`.readNBytes(4097)
        check(bytes.size in 1..4096)
        val envelope = Json.decodeFromString<SignedEnvelope>(bytes.decodeToString())
        check(envelope.clientId == "11111111-2222-4333-8444-555555555555")
        check(envelope.counter == 3L && envelope.timestamp == 1_800_000_000_000L)
        check(envelope.validUntil == envelope.timestamp + 30_000 && envelope.wantsAck)
        check(envelope.type == "combo_history_read_v1")
        check(ClientControlCrypto.verifyEnvelope(ByteArray(32) { it.toByte() }, envelope))
        val body = Json.parseToJsonElement(envelope.payload).jsonObject
        check(body["version"] == JsonPrimitive(1))
        check(body["masterInstallId"] == JsonPrimitive("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        check(body.getValue("query").jsonObject.getValue("kind").jsonPrimitive.content in setOf("inventory", "chunk"))
        var unsupported = false
        try { Json.decodeFromString<ClientControlMessage>(envelope.payload) }
        catch (_: SerializationException) { unsupported = true }
        check(unsupported)
        print("native_signature_ok_extension_unsupported")
    } catch (_: Exception) {
        System.err.print("read_protocol_check_failed")
        exitProcess(1)
    }
}
