package integration.entries

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NSMbgV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.nssdk.localmodel.clientcontrol.*
import app.aaps.core.nssdk.utils.ClientControlCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
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
                stage = "settings"
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
                check(client.getSettingsModifiedSince(settingsCreated.lastModified!!, 100).values!!
                    .any { it["identifier"] == JsonPrimitive("sdk-settings-wire") })
                check(client.deleteSettings("sdk-settings-wire").response == 200)
                check(client.getSettings("sdk-settings-wire").code == 404)
                check(client.updateSettings("sdk-settings-wire", document("three")).response == 410)
                check(client.createSettings(document("post")).response == 201)
                val owner = "11111111-2222-4333-8444-555555555555"
                val offerId = "aaps_clientcontrol_offer_$owner"
                check(client.updateSettings(offerId, JsonObject(document("offer") +
                    ("offer" to buildJsonObject { put("wrappedB64", "synthetic-ciphertext") }))).response == 201)
                check(client.deleteSettingsPermanent(offerId).response == 200)
                check(client.getSettings(offerId).code == 404)
                check(client.searchSettings(100).values!!.none { it["identifier"] == JsonPrimitive(offerId) })
                stage = "target-envelope"
                val codec = Json { ignoreUnknownKeys = false }
                val secret = ByteArray(32) { it.toByte() }
                suspend fun envelope(type: String): SignedEnvelope {
                    val doc = client.getSettings("aaps_clientcontrol_cmd_${type}_$owner").values!!
                    return codec.decodeFromJsonElement(SignedEnvelope.serializer(), doc.getValue("envelope"))
                        .also { check(ClientControlCrypto.verifyEnvelope(secret, it)) }
                }
                val prepare = envelope("batch_prepare")
                check(prepare.clientId == owner && prepare.counter == 7L && prepare.wantsAck)
                check(prepare.validUntil == prepare.timestamp + 8000)
                val batch = codec.decodeFromString(ClientControlMessage.serializer(), prepare.payload) as ClientControlMessage.BatchPrepare
                check(batch.actions == listOf(BatchActionDto(type = "temp_target", reason = "Eating Soon",
                    lowMgdl = 90.0, highMgdl = 99.0, durationMinutes = 30, startOffsetMinutes = 0)))
                suspend fun publishAck(counter: Long, payload: String?, timestamp: Long) {
                    val ack = ClientControlCrypto.signAck(secret, AckEnvelope(owner, counter, AckPhase.Done,
                        AckStatus.Ok, payload = payload, timestamp = timestamp, signature = ""))
                    val doc = buildJsonObject {
                        put("date", 946684800001L); put("utcOffset", 0); put("app", "AAPS"); put("schemaVersion", 1)
                        put("ack", codec.encodeToJsonElement(AckEnvelope.serializer(), ack))
                    }
                    check(client.updateSettings("aaps_clientcontrol_ack_$owner", doc).response in 200..299)
                }
                val preview = codec.encodeToString(BolusPreview.serializer(),
                    BolusPreview(321, listOf(ConfirmationLineDto("Normal", "synthetic target"))))
                publishAck(prepare.counter, preview, prepare.timestamp + 1)
                println("STOCK_RC1_TARGET_PREVIEW_READY")
                System.out.flush()
                check(readlnOrNull() == "COMMIT_READY")
                val commit = envelope("bolus_commit")
                check(commit.counter == 8L && commit.clientId == owner && commit.wantsAck)
                check(commit.validUntil == commit.timestamp + 8000)
                val confirmation = codec.decodeFromString(ClientControlMessage.serializer(), commit.payload) as ClientControlMessage.BolusCommit
                check(confirmation == ClientControlMessage.BolusCommit(321, asAdvisor = false, correctionU = 0.0))
                publishAck(commit.counter, null, commit.timestamp + 1)
                secret.fill(0)
                check(!client.getStatus().apiPermissions.isFull())
            } finally {
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
