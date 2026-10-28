package integration.entries

import app.aaps.core.nssdk.localmodel.clientcontrol.ComboHistoryReadReply
import app.aaps.core.nssdk.localmodel.clientcontrol.ComboHistoryReadRequest
import app.aaps.core.nssdk.localmodel.clientcontrol.SignedEnvelope
import app.aaps.core.nssdk.utils.ClientControlCrypto
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Exact candidate native reply source, synthetic catalog only; no device, credentials or network. */
fun main() {
    try {
        val bytes = System.`in`.readNBytes(8193)
        check(bytes.size in 1..8192)
        val input = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        check(input.keys == setOf("envelope", "count", "readBytes", "unscoped", "writerFailed"))
        val envelope = Json.decodeFromJsonElement(SignedEnvelope.serializer(), input.getValue("envelope"))
        val secret = ByteArray(32) { it.toByte() }
        check(ClientControlCrypto.verifyEnvelope(secret, envelope))
        val request = ComboHistoryReadRequest.decode(envelope, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", 1_800_000_000_000)
        val count = input.getValue("count").jsonPrimitive.int
        val size = input.getValue("readBytes").jsonPrimitive.int
        check(count in 0..4096 && size in 1..2 * 1024 * 1024)
        val raw = ByteArray(size) { (it % 251).toByte() }
        try {
            val hash = ClientControlCrypto.bytesToHex(MessageDigest.getInstance("SHA-256").digest(raw))
            val rows = (1..count).map { n -> ComboHistoryReadReply.Read(
                "00000000-0000-4000-8000-%012d".format(n), size, hash, n != 1, n == 1, true, n == 2) }
            val reply = ComboHistoryReadReply.create(envelope.clientId, request.masterInstallId,
                "00000000-0000-4000-8000-000000009999", "a".repeat(64), rows,
                input.getValue("unscoped").jsonPrimitive.int, input.getValue("writerFailed").jsonPrimitive.boolean)
            val query = request.query
            val chunk = if (query is ComboHistoryReadRequest.Chunk) raw.copyOfRange(query.offset, query.offset + query.length) else null
            try { System.out.write(reply.seal(request, secret, 1_800_000_000_000, chunk)) }
            finally { chunk?.fill(0) }
        } finally { raw.fill(0); secret.fill(0) }
    } catch (_: Exception) {
        System.err.print("native_reply_check_failed")
        exitProcess(1)
    }
}
