package integration.finalenrollment

import app.aaps.core.nssdk.localmodel.clientcontrol.PairingOffer
import app.aaps.core.nssdk.localmodel.clientcontrol.PairingPayload
import app.aaps.core.nssdk.localmodel.clientcontrol.KeyExchangeEnvelope
import app.aaps.core.nssdk.localmodel.clientcontrol.AckEnvelope
import app.aaps.core.nssdk.utils.MasterDocSigning
import app.aaps.core.nssdk.utils.LocalFirstPong
import app.aaps.core.nssdk.networking.NsLoopbackUploadIdentity
import app.aaps.core.nssdk.utils.ClientControlCrypto
import app.aaps.core.nssdk.utils.ClientControlPinOffer
import app.aaps.plugins.sync.nsclientV3.localpin.LocalPinHelloPeer
import app.aaps.plugins.sync.nsclientV3.localpin.LocalPinKeyResponder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/** Synthetic Binder calls go through private pipes. No tokens or documents go to test logs. */
fun main() {
    val ok = runCatching {
        val id = UUID.randomUUID().toString()
        val secret = ClientControlCrypto.newSecretBytes()
        val pending = try {
            PairingPayload(masterInstallId = UUID.randomUUID().toString(), clientId = id,
                secretHex = ClientControlCrypto.bytesToHex(secret), expiresAt = System.currentTimeMillis() + 120_000,
                role = "Caregiver")
        } finally { secret.fill(0) }
        val offer = ClientControlPinOffer.wrap(pending, "23456789")
        println(Json { encodeDefaults = true }.encodeToString(PairingOffer.serializer(), offer))
        System.out.flush()
        val bearer = checkNotNull(readlnOrNull())
        val identity = NsLoopbackUploadIdentity(File(System.getenv("SETTINGS_TEST_CA")).readBytes(),
            File(System.getenv("SETTINGS_TEST_LEAF")).readBytes())
        runBlocking {
            var saved: ByteArray? = null
            try {
                check(LocalPinHelloPeer.exchange(System.getenv("SETTINGS_TEST_PORT").toInt(), identity, bearer, pending, accept = { hello ->
                    val answer = LocalPinKeyResponder.answer(pending, hello, issue = { point ->
                        println(point)
                        System.out.flush()
                        readln().encodeToByteArray()
                    }, storePending = { _, key -> saved = key.copyOf(); true }) ?: return@exchange false
                    println(Json.encodeToString(KeyExchangeEnvelope.serializer(), answer))
                    System.out.flush()
                    readln() == "KEY_ACCEPTED"
                }, confirmProbe = { ping ->
                    val signer = MasterDocSigning.newKeyPair()
                    val ack = try { LocalFirstPong.sign(checkNotNull(saved), id, ping, pending.expiresAt - 120_000,
                        pending.expiresAt, System.currentTimeMillis(), signer.publicKey) } finally { signer.privateKey.fill(0) }
                    println(Json.encodeToString(AckEnvelope.serializer(), checkNotNull(ack)))
                    System.out.flush()
                    readln() == "PONG_ACCEPTED"
                }))
            } finally { saved?.fill(0) }
        }
    }.isSuccess
    println(if (ok) "LOCAL_PONG_VERIFIED" else "LOCAL_PONG_FAILED")
    exitProcess(if (ok) 0 else 1)
}
