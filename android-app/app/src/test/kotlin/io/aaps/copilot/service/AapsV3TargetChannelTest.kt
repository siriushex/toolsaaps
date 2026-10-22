package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import io.aaps.copilot.data.repository.NativeTargetOutcome
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AapsV3TargetChannelTest {
    private lateinit var db: CopilotDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = "11111111-2222-4333-8444-555555555555"
    private val secret = ByteArray(32) { it.toByte() }
    private val at = 1_800_000_000_000L
    private val now = AtomicLong(at + 200)
    private val cipher = KeystoreAapsV3JournalCipher { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    private val journal get() = AapsV3TargetJournal(db, cipher)
    private var storage = true
    private var baseline: suspend () -> String? = { null }
    private lateinit var channel: AapsV3TargetChannel
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        channel = newChannel()
    }
    @After fun teardown() = runBlocking { channel.stop(); scope.coroutineContext.job.cancelAndJoin(); db.close() }
    private fun newChannel() = AapsV3TargetChannel(db, journal, { storage }, { baseline() }, { it() }, now::get)
    private fun command(key: String = "TargetManager.v1:synthetic") = ActionCommand("action-1", "temp_target",
        mapOf("targetMmol" to "5.5", "durationMinutes" to "30"), SafetySnapshot(false, false, null, 0), key)

    private suspend fun settings(id: String, body: JsonObject) {
        val store = LocalNightscoutV3Store(db, now::get)
        val previous = store.get(LocalV3Collection.SETTINGS, id)
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, id, "local-v3-owner", 946_684_800_001,
            JsonObject(body + ("schemaVersion" to JsonPrimitive(1))).toString())
        if (previous == null) store.create(input) else store.update(input, previous.modifiedRevision)
    }
    private suspend fun request(type: String): JsonObject = withTimeout(4000) {
        db.localNightscoutV3Dao().observeRevision().map {
            LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_cmd_${type}_$client")
        }.filterNotNull().first().let { Json.parseToJsonElement(it.payloadJson).jsonObject.getValue("envelope").jsonObject }
    }
    private suspend fun acknowledge(request: JsonObject, payload: String? = null) {
        val counter = request.getValue("counter").jsonPrimitive.long
        val timestamp = request.getValue("timestamp").jsonPrimitive.long + 1
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal("$client|$counter|Done|Ok||${payload ?: ""}|$timestamp".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        settings("aaps_clientcontrol_ack_$client", buildJsonObject { put("ack", buildJsonObject {
            put("clientId", client); put("commandCounter", counter); put("phase", "Done"); put("status", "Ok")
            put("timestamp", timestamp); put("signature", signature)
            payload?.let { put("payload", it) }
        }) })
    }
    private suspend fun connected() {
        AapsV3PairingCredential(client, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", at + 120_000, secret).use { journal.enroll(it, at) }
        settings("aaps", buildJsonObject { put("runningConfig", buildJsonObject {
            put("authorizedClients", buildJsonObject { put("clientIds", buildJsonArray { add(client) }) })
            put("syncedPrefs", buildJsonObject { put("ns_allow_client_control", "true") })
        }) })
        channel.start(scope); channel.checkConnection()
        acknowledge(request("ping")); channel.checkConnection()
        assertEquals(AapsV3ConnectionStatus.CONNECTED, channel.state.value.status)
    }
    private suspend fun preview() = acknowledge(request("batch_prepare"),
        """{"bolusId":321,"lines":[{"role":"Normal","text":"synthetic target"}]}""")

    @Test fun onlySignedCommitConfirmationMarksAppliedAndDuplicateDoesNotPublish() = runBlocking {
        connected()
        val master = launch(Dispatchers.IO) { preview(); acknowledge(request("bolus_commit")) }
        assertEquals(NativeTargetOutcome.APPLIED, channel.send(command()) { null })
        master.join()
        assertEquals(3L, request("batch_prepare").getValue("counter").jsonPrimitive.long)
        assertEquals(4L, request("bolus_commit").getValue("counter").jsonPrimitive.long)
        val head = db.localNightscoutV3Dao().head("settings")
        assertEquals(NativeTargetOutcome.APPLIED, channel.send(command()) { error("no repeat") })
        assertEquals(head, db.localNightscoutV3Dao().head("settings"))
        assertEquals(NativeTargetOutcome.UNKNOWN, channel.send(command().copy(params = command().params + ("targetMmol" to "6.0"))) { null })
        assertEquals(head, db.localNightscoutV3Dao().head("settings"))
    }

    @Test fun stopWhileSafetyGuardSuspendsCannotPublish() = runBlocking {
        connected()
        baseline = { channel.stop(); null }
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.send(command()) { null })
        assertNull(LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_cmd_batch_prepare_$client"))
    }

    @Test fun changedSafetyGuardAfterPreviewCannotCommit() = runBlocking {
        connected()
        var checks = 0
        val master = launch(Dispatchers.IO) { preview() }
        assertEquals(NativeTargetOutcome.UNKNOWN, channel.send(command()) { if (++checks == 1) null else "safety_changed" })
        master.join()
        assertEquals(AapsV3TargetPhase.PREVIEW, journal.pending()!!.phase)
        assertNull(LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_cmd_bolus_commit_$client"))
        now.addAndGet(9000)
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.reconcile(command().idempotencyKey))
    }

    @Test fun runtimeRestartRequiresANewSignedProbeAndNeverReplaysAPrepare() = runBlocking {
        connected()
        val first = request("ping").getValue("counter").jsonPrimitive.long
        channel.stop(); now.incrementAndGet(); channel = newChannel(); channel.start(scope); channel.checkConnection()
        assertEquals(AapsV3ConnectionStatus.VERIFYING, channel.state.value.status)
        assertTrue(request("ping").getValue("counter").jsonPrimitive.long > first)
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.send(command()) { null })
    }

    @Test fun disconnectedOrRevokedPairingCannotPublishTargets() = runBlocking {
        connected(); storage = false
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.send(command()) { null })
        storage = true; channel.revoke()
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.send(command()) { null })
        assertEquals(AapsV3ConnectionStatus.REVOKED, channel.state.value.status)
    }

    @Test fun missingNativeReceiptCannotProveAbsenceOfAPreviousLegacyRequest() = runBlocking {
        connected()
        assertEquals(NativeTargetOutcome.UNKNOWN, channel.reconcile("legacy-unknown"))
        assertNull(journal.pending())
    }

    @Test fun signedConnectivityDoesNotInventAnEffectiveProfileOrBypassClinicalAdmission() = runBlocking {
        connected()
        assertEquals(AapsV3ObservedProfileStatus.NOT_OBSERVED, channel.state.value.observedProfile)
        baseline = { "native_clinical_projection_unavailable" }
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.send(command()) { null })
        assertNull(LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_cmd_batch_prepare_$client"))
        channel.stop()
        assertEquals(AapsV3ObservedProfileStatus.UNCHECKED, channel.state.value.observedProfile)
    }

    @Test fun retainedInsulinStatusRefreshesAndClearsWithRuntimeOwnership() = runBlocking {
        connected()
        assertEquals(AapsV3ObservedInsulinStatus.NOT_OBSERVED, channel.state.value.observedInsulin)
        val store = LocalNightscoutV3Store(db, now::get)
        store.create(LocalV3DocumentInput(LocalV3Collection.TREATMENTS, "observed-dose", "local-v3-owner", at,
            buildJsonObject { put("eventType", "Correction Bolus"); put("date", at); put("insulin", 2.2) }.toString()))
        now.addAndGet(100)
        channel.checkConnection()
        assertEquals(AapsV3ObservedInsulinStatus.PARTIAL_CONTEXT, channel.state.value.observedInsulin)
        assertEquals(1, channel.state.value.insulinRecordCount)
        assertTrue(db.therapyDao().since(0).isEmpty())
        channel.stop()
        assertEquals(AapsV3ObservedInsulinStatus.UNCHECKED, channel.state.value.observedInsulin)
        assertEquals(0, channel.state.value.insulinRecordCount)
    }

    @Test fun profileHistoryDiagnosticsRefreshAndClearWithOwnerWithoutAdmittingTherapy() = runBlocking {
        connected()
        assertEquals(AapsV3ObservedProfileStatus.NOT_OBSERVED, channel.state.value.observedProfileHistory)
        val time = at - 86_400_000
        val body = buildJsonObject {
            put("eventType", "Note"); put("date", time); put("utcOffset", 0)
            put("originalProfileName", "historical"); put("originalCustomizedName", "historical")
            put("originalTimeshift", 0); put("originalPercentage", 100)
            put("originalDuration", 0); put("originalEnd", 0)
            put("icfg", buildJsonObject {
                put("insulinLabel", "historical"); put("insulinEndTime", 18_000_000)
                put("insulinPeakTime", 4_500_000); put("concentration", 1.0)
            })
            put("profileJson", buildJsonObject {
                put("units", "mmol"); put("timezone", "UTC")
                listOf("carbratio", "sens", "basal", "target_low", "target_high").forEach { key ->
                    put(key, buildJsonArray { add(buildJsonObject {
                        put("time", "00:00"); put("timeAsSeconds", 0); put("value", 1.0)
                    }) })
                }
            }.toString())
        }
        LocalNightscoutV3Store(db, now::get).create(LocalV3DocumentInput(LocalV3Collection.TREATMENTS,
            "profile", "local-v3-owner", time, body.toString()))
        now.addAndGet(100); channel.checkConnection()
        assertEquals(AapsV3ObservedProfileStatus.OBSERVED, channel.state.value.observedProfileHistory)
        assertEquals(1, channel.state.value.profileHistoryIntervalCount)
        baseline = { "native_clinical_projection_unavailable" }
        assertEquals(NativeTargetOutcome.NOT_SENT, channel.send(command()) { null })
        assertTrue(db.therapyDao().since(0).isEmpty())
        channel.stop()
        assertEquals(AapsV3ObservedProfileStatus.UNCHECKED, channel.state.value.observedProfileHistory)
        assertEquals(0, channel.state.value.profileHistoryIntervalCount)
    }
}
