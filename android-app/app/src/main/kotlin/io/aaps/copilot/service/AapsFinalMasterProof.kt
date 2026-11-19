package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.math.BigInteger
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class AapsFinalClientRole(val wire: String) {
    READ_ONLY("ReadOnly"), MEAL_ASSISTANT("MealAssistant"), CAREGIVER("Caregiver"), ADMIN("Admin");

    companion object {
        fun parse(wire: String) = entries.firstOrNull { it.wire == wire } ?: READ_ONLY
    }
}

internal data class AapsFinalRosterProof(val signedAt: Long, val member: Boolean,
                                       val role: AapsFinalClientRole, val controlEnabled: Boolean)
internal data class AapsLocalRoleReceipt(val role: AapsFinalClientRole, val signedAt: Long)

/** Wire validation only. Does not enroll a device or grant command authority. */
internal class AapsFinalMasterKey private constructor(private val key: PublicKey, val encodedPoint: String) {
    private fun signatureMatches(identifier: String, bytes: ByteArray, signature: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update("aaps-settings-v1|$identifier|".toByteArray(Charsets.UTF_8))
            update(bytes)
            verify(toDer(signature))
        }

    fun verifyLocalRole(doc: JsonObject, clientId: String, masterId: String, handle: String,
                        issued: Long, deadline: Long, now: Long): AapsLocalRoleReceipt? = try {
        require(CLIENT_ID.matches(clientId) && CLIENT_ID.matches(handle) && now in issued until deadline)
        HistoryJson.fields(doc, setOf("signed", "signature"))
        val bytes = HistoryJson.encodeUtf8(HistoryJson.string(doc.getValue("signed")))
        require(bytes.size in 1..1024 && signatureMatches("local_enrollment_role_v1_" + clientId, bytes,
            decode64(HistoryJson.string(doc.getValue("signature")), 64)))
        val body = HistoryJson.parse(bytes, 1024)
        HistoryJson.fields(body, setOf("clientId","masterInstallId","handle","role","counter","signedAt","expiresAt","controlEnabled"))
        require(body["clientId"] == JsonPrimitive(clientId) && body["masterInstallId"] == JsonPrimitive(masterId) &&
            body["handle"] == JsonPrimitive(handle) && body["controlEnabled"] == JsonPrimitive(true) &&
            body["counter"] == JsonPrimitive(2L) && body["expiresAt"] == JsonPrimitive(deadline))
        val at = HistoryJson.long(body.getValue("signedAt"))
        require(at in issued..now)
        val role = AapsFinalClientRole.entries.single { body["role"] == JsonPrimitive(it.wire) }
        AapsLocalRoleReceipt(role, at)
    } catch (_: Exception) { null }

    fun verifyLocalWitness(doc: JsonObject, client: String, master: String, nonce: String, issued: Long, now: Long): AapsLocalRoleReceipt? = try {
        require(CLIENT_ID.matches(client) && CLIENT_ID.matches(nonce) && issued in 1..(HistoryJson.maxTime - 8_000L) &&
            now in issued until issued + 8_000L)
        HistoryJson.fields(doc, setOf("signed", "signature"))
        val bytes = HistoryJson.encodeUtf8(HistoryJson.string(doc.getValue("signed")))
        require(bytes.size in 1..1024 && signatureMatches("local_session_witness_v1_" + client, bytes,
            decode64(HistoryJson.string(doc.getValue("signature")), 64)))
        val body = HistoryJson.parse(bytes, 1024)
        HistoryJson.fields(body, setOf("clientId","masterInstallId","nonce","role","issued","expiresAt","signedAt"))
        require(body["clientId"] == JsonPrimitive(client) && body["masterInstallId"] == JsonPrimitive(master) &&
            body["nonce"] == JsonPrimitive(nonce) && body["issued"] == JsonPrimitive(issued) &&
            body["expiresAt"] == JsonPrimitive(issued + 8_000L))
        val at = HistoryJson.long(body.getValue("signedAt"))
        require(at in issued..now)
        AapsLocalRoleReceipt(AapsFinalClientRole.entries.single { body["role"] == JsonPrimitive(it.wire) }, at)
    } catch (_: Exception) { null }

    fun verifyRoster(doc: JsonObject, clientId: String, now: Long, minimumSignedAt: Long): AapsFinalRosterProof? = try {
        require(HistoryJson.long(doc.getValue("schemaVersion")) == 2L && "runningConfig" !in doc)
        val signed = HistoryJson.string(doc.getValue("signed"))
        val bytes = HistoryJson.encodeUtf8(signed)
        val signature = decode64(HistoryJson.string(doc.getValue("signature")), 64)
        require(bytes.size in 1..48_000)
        require(signatureMatches("aaps", bytes, signature))
        val envelope = HistoryJson.parse(bytes, 48_000)
        HistoryJson.fields(envelope, setOf("signedAt", "runningConfig"))
        val signedAt = HistoryJson.long(envelope.getValue("signedAt"))
        require(now in 1..HistoryJson.maxTime && signedAt in maxOf(1L, minimumSignedAt)..(now + 5_000L))
        val config = HistoryJson.obj(envelope.getValue("runningConfig"))
        val roster = HistoryJson.obj(config.getValue("authorizedClients"))
        HistoryJson.fields(roster, setOf("clientIds", "roles"))
        val ids = HistoryJson.array(roster.getValue("clientIds")).map(HistoryJson::string)
        require(ids.size <= 1024 && ids.all { CLIENT_ID.matches(it) } && ids.distinct().size == ids.size)
        val roles = HistoryJson.obj(roster.getValue("roles"))
        require(roles.keys == ids.toSet())
        val role = roles[clientId]?.let { AapsFinalClientRole.parse(HistoryJson.string(it)) } ?: AapsFinalClientRole.READ_ONLY
        val enabled = HistoryJson.obj(config.getValue("syncedPrefs"))["ns_allow_client_control"] == JsonPrimitive("true")
        AapsFinalRosterProof(signedAt, clientId in ids, role, enabled && clientId in ids && role != AapsFinalClientRole.READ_ONLY)
    } catch (_: Exception) {
        null
    }

    companion object {
        private val CLIENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun fromPong(payload: JsonElement?): AapsFinalMasterKey? = try {
            val body = HistoryJson.parse(HistoryJson.encodeUtf8(HistoryJson.string(checkNotNull(payload))), 256)
            HistoryJson.fields(body, setOf("signingKey"))
            fromEncodedPoint(HistoryJson.string(body.getValue("signingKey")))
        } catch (_: Exception) {
            null
        }

        fun fromEncodedPoint(encodedPoint: String): AapsFinalMasterKey? = try {
            AapsFinalMasterKey(AapsFinalKeyExchange.decodePoint(encodedPoint), encodedPoint)
        } catch (_: Exception) {
            null
        }

        private fun decode64(text: String, size: Int): ByteArray {
            require(text.length == ((size + 2) / 3) * 4)
            return Base64.getDecoder().decode(text).also {
                require(it.size == size && Base64.getEncoder().encodeToString(it) == text)
            }
        }

        // AAPS sends fixed-width r||s; Android's SHA256withECDSA expects ASN.1 DER.
        private fun toDer(raw: ByteArray): ByteArray {
            val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
            val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
            return byteArrayOf(0x30, (r.size + s.size + 4).toByte(), 0x02, r.size.toByte()) +
                r + byteArrayOf(0x02, s.size.toByte()) + s
        }
    }
}
