package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class AapsFinalClientRole(val wire: String) {
    READ_ONLY("ReadOnly"), MEAL_ASSISTANT("MealAssistant"), CAREGIVER("Caregiver"), ADMIN("Admin");

    companion object {
        fun parse(wire: String) = entries.firstOrNull { it.wire == wire } ?: READ_ONLY
    }
}

internal data class AapsFinalRosterProof(val signedAt: Long, val member: Boolean,
                                       val role: AapsFinalClientRole, val controlEnabled: Boolean)

/** Wire validation only. Does not enroll a device or grant command authority. */
internal class AapsFinalMasterKey private constructor(private val key: PublicKey, val encodedPoint: String) {
    fun verifyRoster(doc: JsonObject, clientId: String, now: Long, minimumSignedAt: Long): AapsFinalRosterProof? = try {
        require(HistoryJson.long(doc.getValue("schemaVersion")) == 2L && "runningConfig" !in doc)
        val signed = HistoryJson.string(doc.getValue("signed"))
        val bytes = HistoryJson.encodeUtf8(signed)
        val signature = decode64(HistoryJson.string(doc.getValue("signature")), 64)
        require(bytes.size in 1..48_000)
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update("aaps-settings-v1|aaps|".toByteArray(Charsets.UTF_8))
            update(bytes)
            verify(toDer(signature))
        })
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
            val point = decode64(encodedPoint, 65)
            require(point[0] == 4.toByte())
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val x = BigInteger(1, point.copyOfRange(1, 33))
            val y = BigInteger(1, point.copyOfRange(33, 65))
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
            AapsFinalMasterKey(key, encodedPoint)
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
