package integration.finalenrollment

import app.aaps.core.nssdk.localmodel.clientcontrol.PairingOffer
import app.aaps.core.nssdk.localmodel.clientcontrol.PairingPayload
import app.aaps.core.nssdk.networking.NsLoopbackUploadIdentity
import app.aaps.core.nssdk.utils.ClientControlCrypto
import app.aaps.core.nssdk.utils.ClientControlPinOffer
import app.aaps.plugins.sync.nsclientV3.localpin.LocalPinHelloPeer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/** Child of an isolated Copilot test. The pipe carries the encrypted offer, then a fixed result. */
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
            check(LocalPinHelloPeer.exchange(System.getenv("SETTINGS_TEST_PORT").toInt(), identity, bearer, pending) { true })
        }
    }.isSuccess
    println(if (ok) "LOCAL_HELLO_VERIFIED" else "LOCAL_HELLO_FAILED")
    exitProcess(if (ok) 0 else 1)
}
