package integration.finalupload

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.clientcontrol.*
import app.aaps.core.nssdk.utils.ClientControlCrypto
import app.aaps.core.nssdk.utils.ClientControlKeyExchangeCrypto
import app.aaps.core.nssdk.utils.ClientControlPairingCrypto
import app.aaps.core.nssdk.utils.MasterDocSigning
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.transactions.InsertAndCancelCurrentTemporaryTargetTransaction
import integration.storage.StoragePollingPeer
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Synthetic master state machine using stock wire code and a separate stock AAPS database. */
fun main() {
    var stage = "setup"
    try {
        runBlocking {
            val url = URI(System.getenv("ENTRY_TEST_URL"))
            check(url.scheme == "https" && url.host == "127.0.0.1" && url.port > 0)
            val access = checkNotNull(System.getenv("ENTRY_TEST_ACCESS"))
            val client = NSAndroidClientImpl(url.toString(), access, false, {})
            val socket = StoragePollingPeer(url.toString(), access)
            val scratch = Files.createTempDirectory("target-storage-stand")
            val initial = ByteArray(32) { it.toByte() }
            val keys = ClientControlKeyExchangeCrypto.newKeyPair()
            val signing = MasterDocSigning.newKeyPair()
            var rotated: ByteArray? = null
            val codec = Json { encodeDefaults = true }
            val id = "11111111-2222-4333-8444-555555555555"
            val at = 1_800_000_000_000L
            val offerId = "aaps_clientcontrol_offer_$id"
            val keyId = "aaps_clientcontrol_key_$id"
            fun doc(field: String, value: JsonElement, schema: Int = 1) = buildJsonObject {
                put("date", 946_684_800_001L); put("utcOffset", 0); put("app", "AAPS")
                put("schemaVersion", schema); put(field, value)
            }
            val useToken = System.getenv("ENTRY_TEST_PAIR_TOKEN") == "1"
            suspend fun incoming(type: String, counter: Long, key: ByteArray): SignedEnvelope {
                val name = if (type == "hello") "aaps_clientcontrol_hello_$id" else "aaps_clientcontrol_cmd_${type}_$id"
                val pushed = socket.awaitDocument(name)
                if (useToken) {
                    val expected = if (type == "hello") "local-v3-target" else "aaps-Stand"
                    val fetched = checkNotNull(client.getSettings(name).values)
                    for (wire in listOf(pushed, fetched)) {
                        check(wire["subject"] == JsonPrimitive(expected) && wire["modifiedBy"] == JsonPrimitive(expected))
                        check("_localPairSubjectV1" !in wire)
                    }
                }
                val cmd = codec.decodeFromJsonElement(SignedEnvelope.serializer(), pushed.getValue("envelope"))
                check(cmd.clientId == id && cmd.counter == counter && cmd.type == type && cmd.timestamp <= cmd.validUntil)
                check(ClientControlCrypto.verifyEnvelope(key, cmd))
                return cmd
            }
            suspend fun done(command: SignedEnvelope, payload: String? = null) {
                check(command.wantsAck && command.validUntil - command.timestamp == 8_000L)
                val ack = ClientControlCrypto.signAck(checkNotNull(rotated), AckEnvelope(
                    id, command.counter, AckPhase.Done, AckStatus.Ok, payload = payload,
                    timestamp = command.timestamp + 1, signature = ""
                ))
                check(client.updateSettings("aaps_clientcontrol_ack_$id",
                    doc("ack", codec.encodeToJsonElement(AckEnvelope.serializer(), ack))).response in 200..299)
            }
            try {
                stage = "subscribe"
                socket.subscribe()
                stage = "offer"
                val salt = ClientControlPairingCrypto.newSalt()
                val iv = ClientControlPairingCrypto.newIv()
                val expiry = at + 120_000
                val offer = PairingPayload(masterInstallId = "stand-master", clientId = id, expiresAt = expiry,
                    secretHex = initial.joinToString("") { "%02x".format(it.toInt() and 255) }, role = "Caregiver", name = "Stand")
                val plaintext = codec.encodeToString(PairingPayload.serializer(), offer).encodeToByteArray()
                val wrapped = try { ClientControlPairingCrypto.wrap(plaintext, "23456789", salt, iv) }
                    finally { plaintext.fill(0) }
                val b64 = Base64.getEncoder()
                val wire = PairingOffer(clientId = id, expiresAt = expiry, kdfSaltB64 = b64.encodeToString(salt),
                    ivB64 = b64.encodeToString(iv), wrappedB64 = b64.encodeToString(wrapped))
                check(client.updateSettings(offerId, doc("offer", codec.encodeToJsonElement(PairingOffer.serializer(), wire))).response == 201)
                println("FINAL_STORAGE_OFFER_READY")
                stage = "hello-key"
                val hello = incoming("hello", 1, initial)
                check(!hello.wantsAck && hello.validUntil == expiry)
                val message = codec.decodeFromString(ClientControlMessage.serializer(), hello.payload) as ClientControlMessage.Hello
                val clientPoint = checkNotNull(message.publicKey)
                val shared = ClientControlKeyExchangeCrypto.deriveKeys(keys.privateKey, clientPoint, initial, id, clientPoint, keys.publicKey)
                rotated = shared.secret
                val encrypted = if (useToken) {
                    val token = System.`in`.readNBytes(44)
                    try {
                        check(token.size == 43)
                        ClientControlKeyExchangeCrypto.encryptToken(shared.tokenKey, token.decodeToString())
                    } finally { token.fill(0) }
                } else null
                shared.tokenKey.fill(0)
                val answer = ClientControlCrypto.signKeyExchange(initial, KeyExchangeEnvelope(
                    id, clientPoint, keys.publicKey, tokenIv = encrypted?.first, token = encrypted?.second,
                    timestamp = hello.timestamp + 1, signature = ""
                ))
                check(client.deleteSettingsPermanent(offerId).response == 200)
                check(client.updateSettings(keyId, doc("key", codec.encodeToJsonElement(KeyExchangeEnvelope.serializer(), answer))).response == 201)
                stage = "roster-pong"
                val roster = buildJsonObject {
                    put("signedAt", hello.timestamp + 1)
                    putJsonObject("runningConfig") {
                        putJsonObject("authorizedClients") {
                            putJsonArray("clientIds") { add(id) }
                            putJsonObject("roles") { put(id, "Caregiver") }
                        }
                        putJsonObject("syncedPrefs") { put("ns_allow_client_control", "true") }
                    }
                }.toString()
                val rosterDoc = JsonObject(doc("signed", JsonPrimitive(roster), 2) +
                    ("signature" to JsonPrimitive(MasterDocSigning.sign(signing.privateKey, "aaps", roster))))
                check(client.updateSettings("aaps", rosterDoc).response == 201)
                val ping = incoming("ping", 2, shared.secret)
                check(codec.decodeFromString(ClientControlMessage.serializer(), ping.payload) == ClientControlMessage.Ping)
                check(client.deleteSettingsPermanent(keyId).response == 200)
                done(ping, codec.encodeToString(PongPayload.serializer(), PongPayload(signing.publicKey)))

                JvmAppDatabaseBuilder().provideAppRepository(scratch.resolve("stand.db").toString()).use { repository ->
                    var previousId = 0L
                    val reasons = listOf(TemporaryTarget.Reason.AUTOMATION, TemporaryTarget.Reason.EATING_SOON)
                    for ((index, reason) in reasons.withIndex()) {
                        stage = "prepare"
                        val counter = 3L + 2L * index
                        val prepare = incoming("batch_prepare", counter, shared.secret)
                        val batch = codec.decodeFromString(ClientControlMessage.serializer(), prepare.payload) as ClientControlMessage.BatchPrepare
                        val action = batch.actions.single()
                        val target = if (index == 0) 108.0 else 74.0
                        val duration = if (index == 0) 45 else 30
                        val label = if (index == 0) "Automation" else "Eating Soon"
                        val marker = if (index == 0) "fixture-target" else "manual:meal:fixture:eating-soon"
                        check(action == BatchActionDto(type = "temp_target", reason = label,
                            lowMgdl = target, highMgdl = target, durationMinutes = duration,
                            startOffsetMinutes = 0, notes = "copilot:$marker"))
                        check((repository.getTemporaryTargetActiveAt(prepare.timestamp)?.id ?: 0L) == previousId)
                        val parkedId = 321L + index
                        done(prepare, codec.encodeToString(BolusPreview.serializer(),
                            BolusPreview(parkedId, listOf(ConfirmationLineDto("Normal", "stand target")))))
                        stage = "commit"
                        val commit = incoming("bolus_commit", counter + 1, shared.secret)
                        check(codec.decodeFromString(ClientControlMessage.serializer(), commit.payload) ==
                            ClientControlMessage.BolusCommit(parkedId, asAdvisor = false, correctionU = 0.0))
                        val stamp = commit.timestamp
                        val millis = duration * 60_000L
                        val inserted = repository.runTransactionForResultSuspend(InsertAndCancelCurrentTemporaryTargetTransaction(
                            TemporaryTarget(timestamp = stamp, reason = reason, duration = millis,
                                lowTarget = action.lowMgdl, highTarget = action.highMgdl)
                        )).inserted.single()
                        stage = "readback"
                        val active = checkNotNull(repository.getTemporaryTargetActiveAt(stamp))
                        check(active.id == inserted.id && active.id > previousId && active.timestamp == stamp &&
                            active.reason == reason && active.lowTarget == target && active.highTarget == target && active.duration == millis)
                        check(repository.getTemporaryTargetActiveAt(stamp + millis - 1)?.id == active.id)
                        check(repository.getTemporaryTargetActiveAt(stamp + millis) == null)
                        previousId = active.id
                        // Return success only after the same repository query the AAPS Overview uses.
                        done(commit)
                    }
                }
                println("FINAL_STORAGE_READBACK_OK")
            } finally {
                rotated?.fill(0); initial.fill(0); keys.privateKey.fill(0); signing.privateKey.fill(0)
                socket.close(); client.close()
                scratch.toFile().deleteRecursively()
            }
        }
    } catch (failure: Exception) {
        println("FINAL_STORAGE_FAILED:$stage:${failure.javaClass.simpleName}")
        exitProcess(1)
    }
}
