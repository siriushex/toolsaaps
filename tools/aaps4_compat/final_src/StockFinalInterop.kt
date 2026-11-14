package integration.finalupload

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import app.aaps.core.nssdk.localmodel.treatment.EventType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import kotlinx.coroutines.runBlocking
import java.net.URI
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
