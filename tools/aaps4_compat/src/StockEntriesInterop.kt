package integration.entries

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NSMbgV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.nssdk.localmodel.clientcontrol.*
import app.aaps.core.nssdk.localmodel.treatment.*
import app.aaps.core.nssdk.utils.ClientControlCrypto
import app.aaps.core.nssdk.utils.ClientControlPairingCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/** Uses the unchanged RC1 SDK, including its real auth interceptor and HTTP engine. */
fun main() {
    var stage = "setup"
    try {
        runBlocking {
            val url = System.getenv("ENTRY_TEST_URL")
            val uri = URI(url)
            check(uri.scheme == "https" && uri.host == "127.0.0.1" && uri.port > 0)
            val client = NSAndroidClientImpl(url, System.getenv("ENTRY_TEST_ACCESS"), false, {})
            val socket = StoragePollingPeer(url, System.getenv("ENTRY_TEST_ACCESS"))
            try {
                stage = "status"
                check(client.getStatus().apiVersion == "3.0.0")
                check(client.getLastModified().collections.entries == 0L)
                val at = 1_800_000_000_000L
                fun sample(time: Long = at) = NSSgvV3(time, "sdk-test-sensor", null,
                    utcOffset = 0, isValid = true, sgv = 120.0, units = NsUnits.MG_DL,
                    direction = Direction.FLAT, noise = null, filtered = null, unfiltered = null)
                stage = "create"
                val created = client.createSgv(sample())
                check(created.response == 201 && !created.identifier.isNullOrEmpty())
                val retry = client.createSgv(sample())
                check(retry.response == 200 && retry.isDeduplication == true && retry.identifier == created.identifier)
                check(retry.lastModified == created.lastModified)
                check(client.createSgv(sample(at + 60_000)).response == 201)
                stage = "chronological"
                val first = client.getSgvsNewerThan(0, 1)
                check(first.values!!.single().date == at)
                check(client.getSgvsNewerThan(at, 1).values!!.single().date == at + 60_000)
                check(client.getSgvs().values!!.map { it.date } == listOf(at + 60_000, at))
                stage = "history"
                val page1 = client.getSgvsModifiedSince(0, 1)
                check(page1.lastServerModified == created.lastModified)
                val page2 = client.getSgvsModifiedSince(page1.lastServerModified!!, 1)
                check(page2.values!!.single().date == at + 60_000)
                val beforeEdit = page2.lastServerModified!!
                stage = "patch"
                val edit = first.values!!.single().copy(sgv = 125.0)
                check(client.updateSvg(edit).response == 200)
                val updated = client.getSgvsModifiedSince(beforeEdit, 1)
                check(updated.values!!.single().date == at && updated.values!!.single().sgv == 125.0)
                stage = "delete"
                check(client.updateSvg(updated.values!!.single().copy(isValid = false)).response == 200)
                val deleted = client.getSgvsModifiedSince(updated.lastServerModified!!, 1)
                check(!deleted.values!!.single().isValid && deleted.values!!.single().date == at)
                check(client.getLastModified().collections.entries == deleted.lastServerModified)
                check(client.getSgvsNewerThan(0, 10).values!!.single().date == at + 60_000)
                check(client.getSgvsModifiedSince(deleted.lastServerModified!!, 10).values!!.isEmpty())
                stage = "calibration"
                val calibration = NSMbgV3(at + 120_000, identifier = null, utcOffset = 0,
                    isValid = true, mbg = 130.0, sensorMgdlAtPairing = 120.0, units = NsUnits.MG_DL)
                val calibrationCreated = client.createCalibration(calibration)
                check(calibrationCreated.response == 201)
                val calibrationRetry = client.createCalibration(calibration)
                check(calibrationRetry.isDeduplication == true && calibrationRetry.identifier == calibrationCreated.identifier)
                val calibrationPage = client.getSgvsModifiedSince(deleted.lastServerModified!!, 1)
                check(calibrationPage.values!!.isEmpty() && calibrationPage.calibrations.single().mbg == 130.0)
                check(client.getSgvs().values!!.single().date == at + 60_000)
                check(client.updateCalibration(calibrationPage.calibrations.single().copy(mbg = 135.0)).response == 200)
                val calibrationUpdated = client.getSgvsModifiedSince(calibrationPage.lastServerModified!!, 1)
                check(calibrationUpdated.calibrations.single().date == at + 120_000)
                check(calibrationUpdated.calibrations.single().mbg == 135.0)
                check(client.updateCalibration(calibrationUpdated.calibrations.single().copy(isValid = false)).response == 200)
                check(!client.getSgvsModifiedSince(calibrationUpdated.lastServerModified!!, 1).calibrations.single().isValid)
                stage = "device-status"
                val status = NSDeviceStatus(date = at, device = "sdk-test-pump", uploaderBattery = 99,
                    isCharging = true, pump = NSDeviceStatus.Pump(reservoir = 60.5),
                    openaps = NSDeviceStatus.OpenAps(iob = buildJsonObject { put("iob", -0.2) }))
                val statusCreated = client.createDeviceStatus(status)
                check(statusCreated.response == 201 && !statusCreated.identifier.isNullOrEmpty())
                val statusRetry = client.createDeviceStatus(status)
                check(statusRetry.response == 200 && statusRetry.identifier == statusCreated.identifier)
                check(statusRetry.lastModified == statusCreated.lastModified)
                val statuses = client.getDeviceStatusModifiedSince(0)
                check(statuses.single().date == at && statuses.single().createdAt == java.time.Instant.ofEpochMilli(at).toString())
                check(statuses.single().pump?.reservoir == 60.5)
                check(statuses.single().openaps?.iob?.get("iob") == JsonPrimitive(-0.2))
                // SDK reconstruction inserts empty optional battery/status/uploader objects.
                val roundTrip = client.createDeviceStatus(statuses.single())
                check(roundTrip.response == 200 && roundTrip.lastModified == statusCreated.lastModified)
                check(client.createDeviceStatus(status.copy(date = at + 60_000)).response == 201)
                check(client.getDeviceStatusModifiedSince(statusCreated.lastModified!!).single().date == at + 60_000)
                check(client.getLastModified().collections.devicestatus > statusCreated.lastModified!!)
                stage = "treatments"
                val bolus = NSBolus(date = at, identifier = null, utcOffset = 0, isValid = true,
                    eventType = EventType.MEAL_BOLUS, notes = "synthetic", pumpId = 123L, endId = null,
                    pumpType = "synthetic", pumpSerial = "pump-a", insulin = 1.2,
                    type = NSBolus.BolusType.NORMAL, isBasalInsulin = false,
                    iCfg = NSICfg("synthetic insulin", 18_000_000, 4_500_000, 100.0))
                val bolusCreated = client.createTreatment(bolus)
                check(bolusCreated.response == 201 && !bolusCreated.identifier.isNullOrEmpty())
                stage = "treatment-retry"
                val bolusRetry = client.createTreatment(bolus)
                check(bolusRetry.response == 200 && bolusRetry.lastModified == bolusCreated.lastModified)
                stage = "treatment-read"
                val readBolus = client.getTreatmentsNewerThan("1970-01-01T00:00:00Z", 10).values!!.single() as NSBolus
                check(readBolus.date == at && readBolus.insulin == 1.2 && readBolus.iCfg == bolus.iCfg)
                stage = "treatment-round-trip"
                check(client.createTreatment(readBolus).lastModified == bolusCreated.lastModified)
                stage = "treatment-edit"
                check(client.updateTreatment(readBolus.copy(insulin = 1.4)).response == 200)
                val editedBolus = client.getTreatmentsModifiedSince(bolusCreated.lastModified!!, 10)
                check((editedBolus.values!!.single() as NSBolus).insulin == 1.4)
                check(editedBolus.values!!.single().date == at)
                stage = "treatment-delete"
                check(client.updateTreatment((editedBolus.values!!.single() as NSBolus).copy(isValid = false)).response == 200)
                val removedBolus = client.getTreatmentsModifiedSince(editedBolus.lastServerModified!!, 10)
                check(!removedBolus.values!!.single().isValid)
                check(client.getTreatmentsNewerThan("1970-01-01T00:00:00Z", 10).values!!.isEmpty())
                stage = "treatment-carbs"
                val carbs = NSCarbs(date = at + 60_000, identifier = null, utcOffset = 0, isValid = true,
                    eventType = EventType.CARBS_CORRECTION, notes = "synthetic", pumpId = null, endId = null,
                    pumpType = null, pumpSerial = null, carbs = 24.0, duration = 1_800_000)
                check(client.createTreatment(carbs).response == 201)
                val readCarbs = client.getTreatmentsModifiedSince(removedBolus.lastServerModified!!, 10).values!!.single() as NSCarbs
                check(readCarbs.carbs == 24.0 && readCarbs.duration == 1_800_000L)
                stage = "treatment-target"
                val target = NSTemporaryTarget(date = at + 120_000, identifier = null, utcOffset = 0,
                    units = NsUnits.MMOL_L, eventType = EventType.TEMPORARY_TARGET, pumpId = null, endId = null,
                    pumpType = null, pumpSerial = null, duration = 1_800_000, targetBottom = 5.5, targetTop = 5.5,
                    reason = NSTemporaryTarget.Reason.EATING_SOON)
                check(client.createTreatment(target).response == 201)
                stage = "treatment-target-read"
                val readTarget = client.getTreatmentsNewerThan("2027-01-15T08:01:00Z", 10).values!!.single() as NSTemporaryTarget
                check(readTarget.duration == 1_800_000L && readTarget.targetBottomAsMgdl() == 99.0)
                check(readTarget.reason == NSTemporaryTarget.Reason.EATING_SOON)
                stage = "treatment-target-edit"
                val changedTarget = client.updateTreatment(readTarget.copy(duration = 1_200_000, targetTop = 5.6))
                check(changedTarget.response == 200)
                check(client.getLastModified().collections.treatments > removedBolus.lastServerModified!!)
                stage = "treatment-extended"
                val extended = NSExtendedBolus(date = at + 180_000, identifier = null, units = NsUnits.MMOL_L,
                    utcOffset = 0, isValid = true, eventType = EventType.COMBO_BOLUS, pumpId = 456L,
                    endId = null, pumpType = "synthetic", pumpSerial = "pump-a", duration = 1_800_000,
                    enteredinsulin = 0.25, isEmulatingTempBasal = true, rate = 0.5)
                val emulated = NSTemporaryBasal(date = at + 180_000, identifier = null, utcOffset = 0,
                    isValid = true, eventType = EventType.TEMPORARY_BASAL, pumpId = 456L, endId = null,
                    pumpType = "synthetic", pumpSerial = "pump-a", duration = 1_800_000, rate = 0.5,
                    isAbsolute = true, absolute = 0.5, type = NSTemporaryBasal.Type.FAKE_EXTENDED,
                    extendedEmulated = extended)
                check(client.createTreatment(emulated).response == 201)
                val readExtended = client.getTreatmentsNewerThan("2027-01-15T08:02:00Z", 10).values!!.single() as NSExtendedBolus
                check(readExtended.enteredinsulin == 0.25 && readExtended.duration == 1_800_000L)
                check(readExtended.units == NsUnits.MMOL_L && readExtended.isValid)
                stage = "settings"
                socket.subscribe()
                fun document(value: String) = buildJsonObject {
                    put("date", 946684800001L); put("utcOffset", 0); put("app", "AAPS")
                    put("schemaVersion", 1); put("opaque", value)
                }
                val settingsCreated = client.updateSettings("sdk-settings-wire", document("one"))
                check(settingsCreated.response == 201)
                check(client.updateSettings("sdk-settings-wire", document("one")).lastModified == settingsCreated.lastModified)
                check(client.patchSettings("sdk-settings-wire", buildJsonObject { put("opaque", "two") }).response == 200)
                val setting = client.getSettings("sdk-settings-wire").values!!
                check(setting["opaque"] == JsonPrimitive("two"))
                check(socket.awaitDocument("sdk-settings-wire")["opaque"] == JsonPrimitive("two"))
                check(client.getSettingsModifiedSince(settingsCreated.lastModified!!, 100).values!!
                    .any { it["identifier"] == JsonPrimitive("sdk-settings-wire") })
                check(client.deleteSettings("sdk-settings-wire").response == 200)
                check(client.getSettings("sdk-settings-wire").code == 404)
                check(client.updateSettings("sdk-settings-wire", document("three")).response == 410)
                check(client.createSettings(document("post")).response == 201)
                val owner = "11111111-2222-4333-8444-555555555555"
                val offerId = "aaps_clientcontrol_offer_$owner"
                stage = "pairing-offer"
                val codec = Json { ignoreUnknownKeys = false; encodeDefaults = true }
                val secret = ByteArray(32) { it.toByte() }
                suspend fun publishAck(counter: Long, payload: String?, timestamp: Long) {
                    val ack = ClientControlCrypto.signAck(secret, AckEnvelope(owner, counter, AckPhase.Done,
                        AckStatus.Ok, payload = payload, timestamp = timestamp, signature = ""))
                    val doc = buildJsonObject {
                        put("date", 946684800001L); put("utcOffset", 0); put("app", "AAPS"); put("schemaVersion", 1)
                        put("ack", codec.encodeToJsonElement(AckEnvelope.serializer(), ack))
                    }
                    check(client.updateSettings("aaps_clientcontrol_ack_$owner", doc).response in 200..299)
                }
                suspend fun envelope(type: String): SignedEnvelope {
                    val id = if (type == "hello") "aaps_clientcontrol_hello_$owner" else "aaps_clientcontrol_cmd_${type}_$owner"
                    val doc = client.getSettings(id).values!!
                    return codec.decodeFromJsonElement(SignedEnvelope.serializer(), doc.getValue("envelope"))
                        .also { check(ClientControlCrypto.verifyEnvelope(secret, it)) }
                }
                val salt = ClientControlPairingCrypto.newSalt()
                val iv = ClientControlPairingCrypto.newIv()
                val expiry = at + 120_000
                val inner = PairingPayload(masterInstallId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                    clientId = owner, secretHex = secret.joinToString("") { "%02x".format(it.toInt() and 255) }, expiresAt = expiry)
                val wrapped = ClientControlPairingCrypto.wrap(codec.encodeToString(PairingPayload.serializer(), inner).toByteArray(),
                    "23456789", salt, iv)
                val b64 = java.util.Base64.getEncoder()
                val offer = PairingOffer(clientId = owner, expiresAt = expiry, kdfSaltB64 = b64.encodeToString(salt),
                    ivB64 = b64.encodeToString(iv), wrappedB64 = b64.encodeToString(wrapped))
                check(client.updateSettings(offerId, JsonObject(document("offer") +
                    ("offer" to codec.encodeToJsonElement(PairingOffer.serializer(), offer)))).response == 201)
                println("STOCK_RC1_PAIRING_READY"); System.out.flush()
                check(readlnOrNull() == "HELLO_READY")
                val hello = envelope("hello")
                check(hello.counter == 1L && !hello.wantsAck)
                check(codec.decodeFromString(ClientControlMessage.serializer(), hello.payload) == ClientControlMessage.Hello(protocolVersion = 1))
                check(client.deleteSettingsPermanent(offerId).response == 200)
                check(client.getSettings(offerId).code == 404)
                check(client.searchSettings(100).values!!.none { it["identifier"] == JsonPrimitive(offerId) })
                check(client.updateSettings("aaps", JsonObject(document("roster") + ("runningConfig" to buildJsonObject {
                    put("authorizedClients", buildJsonObject { put("clientIds", buildJsonArray { add(owner) }) })
                    put("syncedPrefs", buildJsonObject { put("ns_allow_client_control", "true") })
                }))).response == 201)
                println("STOCK_RC1_ROSTER_READY"); System.out.flush()
                check(readlnOrNull() == "PING_READY")
                val ping = envelope("ping")
                check(ping.counter == 2L && ping.wantsAck)
                check(codec.decodeFromString(ClientControlMessage.serializer(), ping.payload) == ClientControlMessage.Ping)
                publishAck(ping.counter, null, ping.timestamp + 1)
                println("STOCK_RC1_PONG_READY"); System.out.flush()
                check(readlnOrNull() == "TARGET_READY")
                stage = "target-envelope"
                val prepare = envelope("batch_prepare")
                check(prepare.clientId == owner && prepare.counter == 3L && prepare.wantsAck)
                check(prepare.validUntil == prepare.timestamp + 8000)
                val batch = codec.decodeFromString(ClientControlMessage.serializer(), prepare.payload) as ClientControlMessage.BatchPrepare
                check(batch.actions == listOf(BatchActionDto(type = "temp_target", reason = "Eating Soon",
                    lowMgdl = 90.0, highMgdl = 99.0, durationMinutes = 30, startOffsetMinutes = 0,
                    notes = "copilot:synthetic-meal")))
                val preview = codec.encodeToString(BolusPreview.serializer(),
                    BolusPreview(321, listOf(ConfirmationLineDto("Normal", "synthetic target"))))
                publishAck(prepare.counter, preview, prepare.timestamp + 1)
                println("STOCK_RC1_TARGET_PREVIEW_READY")
                System.out.flush()
                check(readlnOrNull() == "COMMIT_READY")
                stage = "target-storage-push"
                val commitDoc = socket.awaitDocument("aaps_clientcontrol_cmd_bolus_commit_$owner")
                val commit = codec.decodeFromJsonElement(SignedEnvelope.serializer(), commitDoc.getValue("envelope"))
                check(ClientControlCrypto.verifyEnvelope(secret, commit))
                check(commit.counter == 4L && commit.clientId == owner && commit.wantsAck)
                check(commit.validUntil == commit.timestamp + 8000)
                val confirmation = codec.decodeFromString(ClientControlMessage.serializer(), commit.payload) as ClientControlMessage.BolusCommit
                check(confirmation == ClientControlMessage.BolusCommit(321, asAdvisor = false, correctionU = 0.0))
                publishAck(commit.counter, null, commit.timestamp + 1)
                secret.fill(0)
                check(!client.getStatus().apiPermissions.isFull())
            } finally {
                socket.close()
                client.close()
            }
        }
        println("STOCK_RC1_ENTRIES_OK")
    } catch (failure: Throwable) {
        // No response payload, URL, token or stack trace may enter a CI log.
        println("STOCK_RC1_ENTRIES_FAILED:$stage:${failure.javaClass.simpleName}")
        exitProcess(1)
    }
}

/** Stock namespace/framing contract over the server's advertised Engine.IO polling transport. */
private class StoragePollingPeer(private val origin: String, private val access: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var sid: String? = null
    private fun exchange(packet: String? = null): String {
        val suffix = sid?.let { "&sid=$it" }.orEmpty()
        val request = HttpRequest.newBuilder(URI("$origin/socket.io/?EIO=4&transport=polling$suffix"))
            .timeout(Duration.ofSeconds(7))
        if (packet == null) request.GET() else request.header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(packet))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).let {
            check(it.statusCode() == 200)
            it.body()
        }
    }
    fun subscribe() {
        val opened = exchange()
        check(opened.startsWith("0"))
        sid = Json.parseToJsonElement(opened.drop(1)).jsonObject.getValue("sid").jsonPrimitive.content
        exchange("40/storage,")
        check(exchange().startsWith("40/storage,"))
        val body = buildJsonArray {
            add("subscribe")
            add(buildJsonObject {
                put("accessToken", access)
                put("collections", buildJsonArray {
                    listOf("devicestatus", "entries", "profile", "treatments", "foods", "settings").forEach { add(it) }
                })
            })
        }
        exchange("42/storage,1$body")
        val ack = exchange().removePrefix("43/storage,1")
        check(Json.parseToJsonElement(ack).jsonArray.single().jsonObject["success"] == JsonPrimitive(true))
    }
    fun awaitDocument(identifier: String): JsonObject {
        repeat(4) {
            val packets = exchange().split('\u001e')
            if ("2" in packets) exchange("3")
            packets.filter { it.startsWith("42/storage,") }.forEach { packet ->
                val event = Json.parseToJsonElement(packet.removePrefix("42/storage,")).jsonArray
                if (event[0].jsonPrimitive.content !in setOf("create", "update")) return@forEach
                val wrapper = event[1].jsonObject
                val doc = wrapper["doc"]?.jsonObject ?: return@forEach
                check(doc.getValue("srvModified").jsonPrimitive.long > 0)
                if (wrapper["colName"] == JsonPrimitive("settings") && doc["identifier"] == JsonPrimitive(identifier)) return doc
            }
        }
        error("expected storage document unavailable")
    }
    fun close() {
        if (sid != null) runCatching { exchange("1") }
        sid = null
    }
}
