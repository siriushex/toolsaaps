package integration.entries

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NSMbgV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
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
