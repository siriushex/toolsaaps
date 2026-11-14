package integration.finalupload

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.nssdk.localmodel.clientcontrol.AckEnvelope
import app.aaps.core.nssdk.localmodel.clientcontrol.AckPhase
import app.aaps.core.nssdk.localmodel.clientcontrol.AckStatus
import app.aaps.core.nssdk.localmodel.clientcontrol.PairingPayload
import app.aaps.core.nssdk.localmodel.clientcontrol.PongPayload
import app.aaps.core.nssdk.utils.ClientControlCrypto
import app.aaps.core.nssdk.utils.ClientControlPairingCrypto
import app.aaps.core.nssdk.utils.MasterDocSigning
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import app.aaps.core.nssdk.localmodel.treatment.EventType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

fun main() {
    var stage = "setup"
    try {
        val url = URI(System.getenv("ENTRY_TEST_URL"))
        check(url.scheme == "https" && url.host == "127.0.0.1" && url.port > 0)
        val client = NSAndroidClientImpl(url.toString(), System.getenv("ENTRY_TEST_ACCESS"), false, {})
        try {
            runBlocking {
                stage = "status"
                val status = client.getStatus()
                check(status.apiVersion == "3.0.0")
                check(status.apiPermissions.settings.create && status.apiPermissions.settings.update)
                check(!status.apiPermissions.isFull() && !status.apiPermissions.isClient())
                val at = 1_800_000_000_000L
                stage = "glucose"
                val sample = NSSgvV3(at, "sdk-test-sensor", null, utcOffset = 0, isValid = true,
                    sgv = 120.0, units = NsUnits.MG_DL, direction = Direction.FLAT, noise = null,
                    filtered = null, unfiltered = null)
                check(client.createSgv(sample).response == 201)
                check(client.getSgvs().values!!.single().date == at)
                stage = "effective-profile"
                val effective = NSEffectiveProfileSwitch(date = at, utcOffset = 0, isValid = true,
                    eventType = EventType.NOTE, notes = null, pumpId = null, endId = null, pumpType = null, pumpSerial = null,
                    originalProfileName = "Synthetic", originalCustomizedName = "Synthetic",
                    originalTimeshift = 0L, originalPercentage = 100, originalDuration = 0L, originalEnd = 0L,
                    profileJson = """{"units":"mg/dl","timezone":"UTC",
                        "carbratio":[{"time":"00:00","timeAsSeconds":0,"value":10}],
                        "sens":[{"time":"00:00","timeAsSeconds":0,"value":45}],
                        "basal":[{"time":"00:00","timeAsSeconds":0,"value":0.8}],
                        "target_low":[{"time":"00:00","timeAsSeconds":0,"value":100}],
                        "target_high":[{"time":"00:00","timeAsSeconds":0,"value":105}]}""",
                    iCfg = NSICfg("synthetic insulin", 18_000_000, 4_500_000, 1.0))
                check(client.createTreatment(effective).response == 201)
                stage = "insulin"
                val bolus = NSBolus(date = at, identifier = null, utcOffset = 0, isValid = true,
                    eventType = EventType.MEAL_BOLUS, notes = null, pumpId = 123L, endId = null,
                    pumpType = "ACCU_CHEK_COMBO", pumpSerial = "synthetic-pump", insulin = 1.2,
                    type = NSBolus.BolusType.NORMAL, isBasalInsulin = false,
                    iCfg = NSICfg("synthetic insulin", 18_000_000, 4_500_000, 1.0))
                check(client.createTreatment(bolus).response == 201)
                val treatments = client.getTreatmentsNewerThan("1970-01-01T00:00:00Z", 10).values!!
                check(treatments.filterIsInstance<NSEffectiveProfileSwitch>().single().iCfg == effective.iCfg)
                val read = treatments.filterIsInstance<NSBolus>().single()
                check(read.pumpId == bolus.pumpId && read.iCfg == bolus.iCfg)
                stage = "device-status"
                val created = client.createDeviceStatus(NSDeviceStatus(date = at, device = "sdk-test-pump"))
                check(created.response == 201)
                check(client.getDeviceStatusModifiedSince(0, 1).single().date == at)
                check(client.getDeviceStatusModifiedSince(created.lastModified!!, 1).isEmpty())
                stage = "signed-fixtures"
                publishSignedFixtures(client, at)
            }
        } finally {
            client.close()
        }
        println("STOCK_FINAL_WIRE_OK")
    } catch (failure: Exception) {
        println("STOCK_FINAL_WIRE_FAILED:$stage:${failure.javaClass.simpleName}")
        exitProcess(1)
    }
}

private suspend fun publishSignedFixtures(client: NSAndroidClientImpl, at: Long) {
    val clientId = "11111111-2222-4333-8444-555555555555"
    val master = "synthetic-master"
    val shared = ByteArray(32) { it.toByte() }
    val codec = Json { encodeDefaults = true }
    val pair = MasterDocSigning.newKeyPair()
    try {
        val expires = at + 120_000L
        val salt = ClientControlPairingCrypto.newSalt()
        val iv = ClientControlPairingCrypto.newIv()
        val fields = PairingPayload(1, master, clientId,
            shared.joinToString("") { "%02x".format(it.toInt() and 255) }, expires, "Caregiver", "Synthetic")
        val plain = codec.encodeToString(PairingPayload.serializer(), fields).encodeToByteArray()
        val encrypted = try { ClientControlPairingCrypto.wrap(plain, "23456789", salt, iv) } finally { plain.fill(0) }
        fun bytes(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        val offer = buildJsonObject {
            put("schemaVersion", 1); put("clientId", clientId); put("expiresAt", expires)
            put("kdfSaltB64", bytes(salt)); put("ivB64", bytes(iv)); put("wrappedB64", bytes(encrypted))
        }
        val pongPayload = codec.encodeToString(PongPayload.serializer(), PongPayload(pair.publicKey))
        val pong = ClientControlCrypto.signAck(shared, AckEnvelope(clientId, 2L,
            AckPhase.Done, AckStatus.Ok, payload = pongPayload, timestamp = at + 1, signature = ""))
        val signed = buildJsonObject {
            put("signedAt", at + 1)
            putJsonObject("runningConfig") {
                putJsonObject("syncedPrefs") { put("ns_allow_client_control", "true") }
                putJsonObject("authorizedClients") {
                    putJsonArray("clientIds") { add(clientId) }
                    putJsonObject("roles") { put(clientId, "Caregiver") }
                }
            }
        }.toString()
        fun doc(version: Int, content: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("date", 946684800001L); put("utcOffset", 0); put("app", "AAPS")
            put("schemaVersion", version)
            content()
        }
        check(client.updateSettings("aaps_clientcontrol_offer_$clientId", doc(1) { put("offer", offer) }).response == 201)
        check(client.updateSettings("aaps_clientcontrol_ack_$clientId", doc(1) {
            put("ack", codec.encodeToJsonElement(AckEnvelope.serializer(), pong))
        }).response == 201)
        check(client.updateSettings("aaps", doc(2) {
            put("signed", signed); put("signature", MasterDocSigning.sign(pair.privateKey, "aaps", signed))
        }).response == 201)
    } finally {
        pair.privateKey.fill(0)
        shared.fill(0)
    }
}
