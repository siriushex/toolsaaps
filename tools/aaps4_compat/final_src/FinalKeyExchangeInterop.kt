package integration.finalupload

import app.aaps.core.nssdk.localmodel.clientcontrol.KeyExchangeEnvelope
import app.aaps.core.nssdk.utils.ClientControlCrypto
import app.aaps.core.nssdk.utils.ClientControlKeyExchangeCrypto
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val input = System.`in`.readNBytes(346).decodeToString().split('\n')
    check(input.size in 1..2)
    val request = input.first()
    val token = input.getOrNull(1)?.also { check(it.length in 1..256) }
    check(request.length == 88)
    val client = "11111111-2222-4333-8444-555555555555"
    val initial = ByteArray(32) { it.toByte() }
    val master = ClientControlKeyExchangeCrypto.newKeyPair()
    try {
        val keys = ClientControlKeyExchangeCrypto.deriveKeys(
            master.privateKey, request, initial, client, request, master.publicKey
        )
        try {
            val encrypted = token?.let { ClientControlKeyExchangeCrypto.encryptToken(keys.tokenKey, it) }
            val answer = ClientControlCrypto.signKeyExchange(initial, KeyExchangeEnvelope(
                client, request, master.publicKey, tokenIv = encrypted?.first, token = encrypted?.second,
                timestamp = 1_800_000_000_001, signature = ""
            ))
            println(buildJsonObject {
                put("answer", Json.encodeToJsonElement(KeyExchangeEnvelope.serializer(), answer))
                put("syntheticExpected", Base64.getEncoder().encodeToString(keys.secret))
            })
        } finally { keys.secret.fill(0); keys.tokenKey.fill(0) }
    } finally { initial.fill(0); master.privateKey.fill(0) }
}
