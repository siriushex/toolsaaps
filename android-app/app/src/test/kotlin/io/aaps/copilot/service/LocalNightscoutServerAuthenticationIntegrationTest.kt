package io.aaps.copilot.service

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.GlucoseSanitizer
import io.aaps.copilot.data.repository.CarbsSendThrottle
import io.aaps.copilot.data.repository.TempTargetSendThrottle
import io.aaps.copilot.data.repository.NightscoutActionRepository
import io.aaps.copilot.data.repository.SyncRepository
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Before
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
// Host socket TLS must not change with Robolectric's platform-dependent provider default.
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalNightscoutServerAuthenticationIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CopilotDatabase
    private lateinit var storage: MemoryAtomicStorage
    private lateinit var caCertificate: X509Certificate
    private lateinit var apiSecretSha1: String
    private var server: LocalNightscoutServer? = null
    private var importedDatabaseName: String? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = MemoryAtomicStorage()
        val loaded = LocalNightscoutTlsIdentityRepository(storage).load()
        try {
            caCertificate = loaded.caCertificate
            apiSecretSha1 = sha1Hex(loaded.apiSecretBytes)
        } finally {
            loaded.clearSecrets()
        }
    }

    @After
    fun tearDown() {
        server?.stop()
        db.close()
        importedDatabaseName?.let(context::deleteDatabase)
    }

    @Test
    fun internalClientReconcilesOlderTargetBeforeApplyingCountAndNeverWrites() = runBlocking {
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState())
        assertThat(server?.update(true, port)).isEqualTo(port)
        val peer = trustedClient(caCertificate)
        val sid = openSocket(peer, port)
        postSocket(peer, port, sid, "40")
        getSocket(peer, port, sid).close()
        postSocket(peer, port, sid, authorizePacket())
        getSocket(peer, port, sid).close()
        val settings = AppSettingsStore(context)
        settings.update { it.copy(nightscoutUrl = "", apiSecret = "unrelated-remote-secret",
            localNightscoutEnabled = true, localNightscoutPort = port,
            localNightscoutLegacyMigrationAcknowledged = true) }
        val command = "TargetManager.v1:older-test"
        db.therapyDao().upsertAll(listOf(TherapyEventEntity(
            id = "old-target", timestamp = 1_000L, type = "temp_target",
            payloadJson = """{"notes":"copilot:$command","duration":"30","targetTop":"110","targetBottom":"110"}"""
        )) + (1..40).map { i -> TherapyEventEntity(id = "new-$i", timestamp = 1_000L + i,
            type = "carbs", payloadJson = """{"notes":"unrelated-$i","carbs":"10"}""") })
        fun repository(secret: String = apiSecretSha1) = NightscoutActionRepository(
            context, db, settings, ApiFactory { LocalNightscoutClientIdentity(caCertificate, secret) },
            CarbsSendThrottle(db.actionCommandDao()), TempTargetSendThrottle(db.actionCommandDao()),
            Gson(), AuditLogger(db.auditLogDao(), Gson())
        )
        val matched = ApiFactory { LocalNightscoutClientIdentity(caCertificate, apiSecretSha1) }
            .nightscoutApi(settings.settings.first())
            .getTreatments(mapOf("find[notes]" to "copilot:$command", "count" to "10"))
        assertThat(matched.map { it.notes }).containsExactly("copilot:$command")
        assertThat(repository().reconcileTempTargetDelivery(command))
            .isEqualTo(NightscoutActionRepository.TempTargetDeliveryReconciliation.SENT)
        assertThat(repository().reconcileTempTargetDelivery("TargetManager.v1:missing-test"))
            .isEqualTo(NightscoutActionRepository.TempTargetDeliveryReconciliation.CONFIRMED_ABSENT)
        assertThat(repository("0".repeat(40)).reconcileTempTargetDelivery(command))
            .isEqualTo(NightscoutActionRepository.TempTargetDeliveryReconciliation.UNKNOWN)
        assertThat(db.therapyDao().since(0L)).hasSize(41)
        assertThat(db.actionCommandDao().byIdempotencyKey(command)).isNull()
        db.therapyDao().upsertAll(listOf(TherapyEventEntity(
            id = "large-matching", timestamp = 5_000L, type = "temp_target",
            payloadJson = Gson().toJson(mapOf("notes" to "copilot:$command-large", "padding" to "x".repeat(17_000)))
        )))
        assertThat(repository().reconcileTempTargetDelivery("$command-large"))
            .isEqualTo(NightscoutActionRepository.TempTargetDeliveryReconciliation.UNKNOWN)
    }

    @Test
    fun longDiagnosticReasonSurvivesAuthenticatedGetAndRoomRoundTrip() = runBlocking {
        val port = freePort()
        val settings = configuredLocalSettings(port)
        server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
        assertThat(server?.update(true, port)).isEqualTo(port)
        authorizeLocalServer(port)
        val reason = "x".repeat(829)
        val note = "copilot:TargetManager.v1:long-reason"
        val original = TherapyEventEntity("long-target", 1_000L, "temp_target", Gson().toJson(mapOf(
            "duration" to "30", "targetTop" to "176", "targetBottom" to "176",
            "units" to "mg/dl", "reason" to reason, "notes" to note, "isValid" to "true"
        )))
        db.therapyDao().upsertAll(listOf(original))
        val api = ApiFactory { LocalNightscoutClientIdentity(caCertificate, apiSecretSha1) }
            .nightscoutApi(settings.settings.first())
        val treatment = api.getTreatments(mapOf("count" to "10")).single()
        assertThat(treatment.duration).isEqualTo(30)
        assertThat(treatment.targetTop).isEqualTo(176.0)
        assertThat(treatment.targetBottom).isEqualTo(176.0)
        assertThat(treatment.reason).isEqualTo(reason)
        assertThat(treatment.notes).isEqualTo(note)
        db.therapyDao().upsertAll(listOf(original.copy(payloadJson = Gson().toJson(
            SyncRepository.buildNightscoutTreatmentPayloadStatic(treatment, "local_nightscout_treatment")
        ))))
        val reconciled = api.getTreatments(mapOf("find[notes]" to note, "count" to "10")).single()
        assertThat(reconciled.duration).isEqualTo(30)
        assertThat(reconciled.targetBottom).isEqualTo(176.0)
        assertThat(reconciled.notes).isEqualTo(note)
    }

    @Test
    fun malformedPayloadMustNotBecomeSuccessfulEmptyTreatment() = runBlocking {
        val port = freePort()
        val settings = configuredLocalSettings(port)
        server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
        assertThat(server?.update(true, port)).isEqualTo(port)
        authorizeLocalServer(port)
        db.therapyDao().upsertAll(listOf(TherapyEventEntity(
            "malformed-target", 1_000L, "temp_target", "{broken"
        )))
        val api = ApiFactory { LocalNightscoutClientIdentity(caCertificate, apiSecretSha1) }
            .nightscoutApi(settings.settings.first())
        val result = runCatching { api.getTreatments(mapOf("count" to "10")) }
        assertThat(result.isFailure).isTrue()
        assertThat(db.therapyDao().since(0L).single().payloadJson).isEqualTo("{broken")
    }

    @Test
    fun transportPreservesExplicitInvalidationAndCancellation() = runBlocking {
        val port = freePort()
        val settings = configuredLocalSettings(port)
        server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
        assertThat(server?.update(true, port)).isEqualTo(port)
        authorizeLocalServer(port)
        db.therapyDao().upsertAll(listOf(
            TherapyEventEntity("invalidated", 2_000L, "temp_target",
                """{"duration":"30","targetTop":"100","targetBottom":"100","isValid":"0"}"""),
            TherapyEventEntity("cancelled", 1_000L, "temp_target",
                """{"duration":"0","isValid":true}""")
        ))
        val rows = ApiFactory { LocalNightscoutClientIdentity(caCertificate, apiSecretSha1) }
            .nightscoutApi(settings.settings.first()).getTreatments(mapOf("count" to "10"))
        assertThat(rows.single { it.id == "invalidated" }.isValid).isFalse()
        assertThat(rows.single { it.id == "cancelled" }.duration).isEqualTo(0)
    }

    @Test
    fun missingIdentityKeepsManagerDeliveryInAuditedUnknownState() = runBlocking {
        val settings = AppSettingsStore(context)
        settings.update { it.copy(nightscoutUrl = "", localNightscoutEnabled = true,
            localNightscoutLegacyMigrationAcknowledged = true) }
        settings.setTherapyActionsArmed(true)
        val repository = NightscoutActionRepository(
            context, db, settings, ApiFactory { throw java.io.IOException("Local identity unavailable") },
            CarbsSendThrottle(db.actionCommandDao()), TempTargetSendThrottle(db.actionCommandDao()),
            Gson(), AuditLogger(db.auditLogDao(), Gson())
        )
        val command = ActionCommand("test-command", "temp_target",
            mapOf("targetMmol" to "6.0", "durationMinutes" to "30", "reason" to "test"),
            SafetySnapshot(false, true, null, 0), "TargetManager.v1:test-identity-missing")
        val result = runCatching { repository.submitOrRetryTempTarget(command) }
        assertThat(result.exceptionOrNull()).isInstanceOf(NightscoutActionRepository.TempTargetDeliveryUnknownException::class.java)
        assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
            .isEqualTo(NightscoutActionRepository.STATUS_PENDING)
        assertThat(db.therapyDao().since(0L)).isEmpty()
    }

    @Test
    fun disarmDuringManagedGuardPreventsLocalApiCreationAndPost() {
        runBlocking {
            val port = freePort()
            val settings = configuredLocalSettings(port)
            server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
            assertThat(server?.update(true, port)).isEqualTo(port)
            authorizeLocalServer(port)

            val apiFactoryInvocations = AtomicInteger(0)
            val repository = repository(settings) {
                apiFactoryInvocations.incrementAndGet()
                LocalNightscoutClientIdentity(caCertificate, apiSecretSha1)
            }
            val secondGuardEntered = CompletableDeferred<Unit>()
            val releaseSecondGuard = CompletableDeferred<Unit>()
            val guardInvocations = AtomicInteger(0)
            val command = managerTempTargetCommand("disarm-during-guard")

            val delivery = async {
                runCatching {
                    repository.submitOrRetryTempTarget(command) {
                        if (guardInvocations.incrementAndGet() == 2) {
                            secondGuardEntered.complete(Unit)
                            releaseSecondGuard.await()
                        }
                        null
                    }
                }
            }
            withTimeout(5_000L) { secondGuardEntered.await() }
            settings.setTherapyActionsArmed(false)
            releaseSecondGuard.complete(Unit)

            val result = withTimeout(5_000L) { delivery.await() }
            assertThat(result.exceptionOrNull()).isNull()
            assertThat(result.getOrNull()).isFalse()
            assertThat(apiFactoryInvocations.get()).isEqualTo(0)
            assertThat(db.therapyDao().since(0L)).isEmpty()
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo(NightscoutActionRepository.STATUS_BLOCKED)
        }
    }

    @Test
    fun successfulManagedLocalRelayDoesNotDeadlockOnTransportLease() {
        runBlocking {
            val port = freePort()
            val settings = configuredLocalSettings(port)
            installAapsRelayReceiver()
            server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
            assertThat(server?.update(true, port)).isEqualTo(port)
            authorizeLocalServer(port)

            val apiFactoryInvocations = AtomicInteger(0)
            val repository = repository(settings) {
                apiFactoryInvocations.incrementAndGet()
                LocalNightscoutClientIdentity(caCertificate, apiSecretSha1)
            }
            val baseCommand = managerTempTargetCommand("successful-local-relay")
            val command = baseCommand.copy(params = baseCommand.params + ("reason" to "x".repeat(829)))

            val delivered = withTimeout(5_000L) {
                repository.submitOrRetryTempTarget(command) { null }
            }

            assertThat(delivered).isTrue()
            val transportPayload = JsonParser.parseString(db.therapyDao().since(0L).single().payloadJson).asJsonObject
            assertThat(transportPayload.get("reason").asString.length).isAtMost(512)
            assertThat(command.params["reason"]?.length).isEqualTo(829)
            assertThat(apiFactoryInvocations.get()).isEqualTo(1)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo(NightscoutActionRepository.STATUS_SENT)
            assertThat(db.therapyDao().since(0L).map { it.id })
                .containsExactly(
                    stableCopilotTreatmentIdStatic(
                        "Temporary Target",
                        "copilot:${command.idempotencyKey}"
                    )
                )
        }
    }

    @Test
    fun localRouteKeepsItsIdentityWhenConfiguredPortChangesDuringGuard() {
        runBlocking {
            val port = freePort()
            val settings = configuredLocalSettings(port)
            installAapsRelayReceiver()
            server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
            assertThat(server?.update(true, port)).isEqualTo(port)
            authorizeLocalServer(port)
            val localIdentityLoads = AtomicInteger(0)
            val repository = repository(settings) {
                localIdentityLoads.incrementAndGet()
                LocalNightscoutClientIdentity(caCertificate, apiSecretSha1)
            }
            val guards = AtomicInteger(0)
            val command = managerTempTargetCommand("local-route-settings-race")
            val result = runCatching {
                withTimeout(5_000L) {
                    repository.submitOrRetryTempTarget(command) {
                        if (guards.incrementAndGet() == 2) {
                            settings.update { it.copy(localNightscoutPort = freePort()) }
                        }
                        null
                    }
                }
            }
            assertThat(localIdentityLoads.get()).isEqualTo(1)
            assertThat(result.exceptionOrNull()).isNull()
            assertThat(result.getOrNull()).isTrue()
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo(NightscoutActionRepository.STATUS_SENT)
        }
    }

    @Test
    fun explicitLocalhostManagedRelayDoesNotTakeOuterTransportLease() {
        runBlocking {
            val port = freePort()
            val settings = configuredLocalSettings(port)
            settings.update { it.copy(nightscoutUrl = "https://localhost:$port") }
            installAapsRelayReceiver()
            server = newServer(MutableLocalNightscoutRuntimeState(), settingsStore = settings)
            assertThat(server?.update(true, port)).isEqualTo(port)
            authorizeLocalServer(port)
            val command = managerTempTargetCommand("localhost-route")
            val result = runCatching {
                withTimeout(5_000L) {
                    repository(settings) { LocalNightscoutClientIdentity(caCertificate, apiSecretSha1) }
                        .submitOrRetryTempTarget(command) { null }
                }
            }
            assertThat(result.exceptionOrNull()).isNull()
            assertThat(result.getOrNull()).isTrue()
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo(NightscoutActionRepository.STATUS_SENT)
        }
    }

    @Test
    fun noteLookupChecksDeadlineBeforeEmptyOrFinalResult() = runBlocking {
        var reads = 0
        assertThat(runCatching {
            LocalNightscoutNoteLookup(db, nanoTime = { if (reads++ == 0) 0L else 3_000_000_001L })
                .find("missing", 0L, 1)
        }.isFailure).isTrue()
        db.therapyDao().upsertAll(listOf(TherapyEventEntity("match", 1L, "temp_target", """{"notes":"match"}""")))
        reads = 0
        assertThat(runCatching {
            LocalNightscoutNoteLookup(db, nanoTime = { if (reads++ < 2) 0L else 3_000_000_001L })
                .find("match", 0L, 1)
        }.isFailure).isTrue()
    }

    @Test
    fun noteLookupBudgetAndMalformedRowsNeverBecomeAbsence() = runBlocking {
        db.therapyDao().upsertAll(listOf(
            TherapyEventEntity("newer", 2_000L, "carbs", """{"notes":"unrelated"}"""),
            TherapyEventEntity("older", 1_000L, "temp_target", """{"notes":"copilot:TargetManager.v1:\u0061bc"}""")
        ))
        val note = "copilot:TargetManager.v1:abc"
        assertThat(LocalNightscoutNoteLookup(db).find(note, 0L, 1).map { it.id }).containsExactly("older")
        assertThat(runCatching { LocalNightscoutNoteLookup(db, maxRows = 1).find(note, 0L, 1) }.isFailure).isTrue()
        assertThat(runCatching { LocalNightscoutNoteLookup(db, maxBytes = 1).find(note, 0L, 1) }.isFailure).isTrue()
        for (invalid in listOf("{", """{"notes":"a","notes":"b"}""", """{"notes":{}}""")) {
            db.therapyDao().upsertAll(listOf(TherapyEventEntity("broken", 3_000L, "temp_target", invalid)))
            assertThat(runCatching { LocalNightscoutNoteLookup(db).find("missing", 0L, 1) }.isFailure).isTrue()
        }
    }

    @Test
    fun fullInitialHistoryIsDeliveredInBoundedPacketsWithoutMissingRows() {
        val count = 1_200
        val start = 1_780_000_000_000L
        runBlocking {
            db.glucoseDao().upsertAll((0 until count).map { index ->
                GlucoseSampleEntity(timestamp = start + index * 60_000L, mmol = 6.0,
                    source = "test", quality = "GOOD")
            })
            db.therapyDao().upsertAll((0 until count).map { index ->
                TherapyEventEntity(id = "initial-therapy-$index", timestamp = start + index * 60_000L,
                    type = "CARBS", payloadJson = "{\"carbs\":10,\"notes\":\"${"x".repeat(120)}\"}")
            })
        }
        assertInitialHistory(
            expectedTherapyIds = (0 until count).map { "initial-therapy-$it" },
            expectedGlucoseTimes = (0 until count).map { start + it * 60_000L }
        )
    }

    @Test
    fun copiedPhoneInitialHistorySurvivesRealTlsAuthorization() {
        val path = System.getenv("COPILOT_PHONE_SOCKET_DB_COPY")?.takeIf(String::isNotBlank)
        Assume.assumeTrue("Provide a standalone disposable phone database copy", path != null)
        val source = File(requireNotNull(path)).absoluteFile
        require(source.isFile && source.canRead())
        require(!File("${source.path}-wal").exists() && !File("${source.path}-shm").exists())
        db.close()
        val name = "phone-socket-${java.util.UUID.randomUUID()}.db"
        importedDatabaseName = name
        db = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
            .createFromFile(source).build()
        val expected = runBlocking {
            db.therapyDao().sinceDescLimit(0L, 1_200).map { it.id } to
                GlucoseSanitizer.filterEntities(db.glucoseDao().latest(1_200)).map { it.timestamp }
        }
        assertThat(expected.first).isNotEmpty()
        assertThat(expected.second).isNotEmpty()
        assertInitialHistory(expected.first, expected.second)
    }

    private fun assertInitialHistory(expectedTherapyIds: List<String>, expectedGlucoseTimes: List<Long>) {
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState())
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        postSocket(client, port, sid, "40")
        getSocket(client, port, sid).close()
        postSocket(client, port, sid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\",\"from\":0}]")
        val liveTimestamp = expectedGlucoseTimes.max() + 60_000L
        assertThat(postApiJsonCode(client, port, "/api/v1/entries.json",
            "{\"sgv\":110,\"date\":$liveTimestamp}")).isEqualTo(200)
        val therapyIds = mutableListOf<String>()
        val glucoseTimes = mutableListOf<Long>()
        val eventHighWaters = mutableListOf<Long>()
        repeat(64) {
            if (therapyIds.size == expectedTherapyIds.size && glucoseTimes.size == expectedGlucoseTimes.size + 1) return@repeat
            getSocket(client, port, sid).use { response ->
                assertThat(response.code).isEqualTo(200)
                val body = response.body!!.string()
                assertThat(body.toByteArray(Charsets.UTF_8).size).isAtMost(256 * 1024)
                body.split('\u001e').filter { it.startsWith("42[") }.forEach { packet ->
                    val event = JsonParser.parseString(packet.drop(2)).asJsonArray
                    if (event[0].asString == "dataUpdate") {
                        val payload = event[1].asJsonObject
                        payload.getAsJsonArray("treatments")?.forEach { row ->
                            therapyIds += row.asJsonObject["_id"].asString
                        }
                        payload.getAsJsonArray("sgvs")?.forEach { row ->
                            glucoseTimes += row.asJsonObject["date"].asLong
                        }
                        listOf("treatments", "sgvs").flatMap { key ->
                            payload.getAsJsonArray(key)?.map { it.asJsonObject["date"].asLong }.orEmpty()
                        }.maxOrNull()?.let(eventHighWaters::add)
                    }
                }
            }
        }
        assertThat(therapyIds).containsExactlyElementsIn(expectedTherapyIds)
        assertThat(glucoseTimes).containsExactlyElementsIn(expectedGlucoseTimes + liveTimestamp)
        assertThat(eventHighWaters).isInOrder()
    }

    @Test
    fun engineIo4ServerSendsHeartbeatWhenDue() {
        var now = 1_000L
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(), socketNowMs = { now })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        now += 25_000L
        assertThat(getSocket(client, port, sid).use { it.body!!.string() }).isEqualTo("2")
        postSocket(client, port, sid, "3")
        now += 25_000L
        assertThat(getSocket(client, port, sid).use { it.body!!.string() }).isEqualTo("2")
    }

    @Test
    fun emptyPollWaitsForDataAndWakesOnAnEnqueuedPacket() {
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState())
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val poll = executor.submit<String> {
                entered.countDown()
                getSocket(client, port, sid).use { it.body!!.string() }
            }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(runCatching { poll.get(200, TimeUnit.MILLISECONDS) }.exceptionOrNull())
                .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            postSocket(client, port, sid, "2")
            assertThat(poll.get(2, TimeUnit.SECONDS)).isEqualTo("3")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun heartbeatWithoutPongClosesTheSession() {
        var now = 1_000L
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(), socketNowMs = { now })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        now += 25_000L
        assertThat(getSocket(client, port, sid).use { it.body!!.string() }).isEqualTo("2")
        now += 20_000L
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
        assertThat(postSocketCode(client, port, sid, "3")).isEqualTo(400)
    }

    @Test
    fun httpsAndAndroidApsSocketAuthorizeAreAuthenticatedEndToEnd() {
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(runtime)

        assertThat(server?.update(enabled = true, port = port)).isEqualTo(port)
        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
        val client = trustedClient(caCertificate)
        assertThat(getCode(client, port, "/")).isEqualTo(200)
        assertThat(getCode(client, port, "/api/v1/status.json")).isEqualTo(401)
        assertThat(getCode(client, port, "/api/v1/status.json", apiSecretSha1))
            .isEqualTo(503)

        val sid = openSocket(client, port)
        postSocket(client, port, sid, "40")
        getSocket(client, port, sid).close()

        postSocket(
            client,
            port,
            sid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"history\":48,\"status\":true,\"from\":0}]"
        )
        val rejected = getSocket(client, port, sid).use { it.body?.string().orEmpty() }
        assertThat(rejected).contains("\"read\":false")
        assertThat(rejected).doesNotContain("dataUpdate")
        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)

        postSocket(
            client,
            port,
            sid,
            "422[\"dbAdd\",{\"collection\":\"treatments\",\"data\":{\"eventType\":\"Sensor Change\",\"mills\":1780000000000}}]"
        )
        assertThat(getSocket(client, port, sid).use { it.body?.string().orEmpty() })
            .contains("unauthorized")
        assertThat(runBlocking { db.therapyDao().since(0L) }).isEmpty()

        postSocket(
            client,
            port,
            sid,
            "423[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\",\"history\":48,\"status\":true,\"from\":0}]"
        )
        val accepted = getSocket(client, port, sid).use { it.body?.string().orEmpty() }
        assertThat(accepted).contains("\"read\":true")
        assertThat(accepted).contains("dataUpdate")
        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.READY)

        assertThat(getCode(client, port, "/api/v1/status.json")).isEqualTo(401)
        assertThat(
            getCode(
                client,
                port,
                "/api/v1/status.json?api-secret=$apiSecretSha1",
                headers = mapOf("user-agent" to apiSecretSha1, "x-aaps-copilot-client" to apiSecretSha1)
            )
        ).isEqualTo(401)
        assertThat(getCode(client, port, "/api/v1/status.json", apiSecretSha1))
            .isEqualTo(200)
    }

    @Test
    fun occupiedConfiguredPortFailsWithoutAlternativeBinding() {
        val occupied = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(runtime)
        try {
            val configuredPort = occupied.localPort

            assertThat(server?.update(enabled = true, port = configuredPort)).isNull()
            assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.FAILED)
            assertThat(runtime.value.port).isEqualTo(configuredPort)
            assertThat(runtime.value.reason)
                .isEqualTo(LocalNightscoutRuntimeReason.PORT_UNAVAILABLE.name)
        } finally {
            occupied.close()
        }
    }

    @Test
    fun invalidConfiguredPortFailsWithoutClampingOrBinding() {
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(runtime)

        assertThat(server?.update(enabled = true, port = 1)).isNull()
        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.FAILED)
        assertThat(runtime.value.port).isEqualTo(1)
        assertThat(runtime.value.reason)
            .isEqualTo(LocalNightscoutRuntimeReason.PORT_INVALID.name)
    }

    @Test
    fun restartRetainsIdentityButReturnsToSetupUntilNewAuthorization() {
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(runtime)

        assertThat(server?.update(true, port)).isEqualTo(port)
        val firstFingerprint = runtime.value.caFingerprint
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        postSocket(
            client,
            port,
            sid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\"}]"
        )
        getSocket(client, port, sid).close()
        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.READY)
        server?.stop()

        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
        assertThat(runtime.value.reason)
            .isEqualTo(LocalNightscoutRuntimeReason.SERVICE_STOPPED.name)

        assertThat(server?.update(true, port)).isEqualTo(port)
        assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
        assertThat(runtime.value.caFingerprint).isEqualTo(firstFingerprint)
    }

    @Test
    fun socketSessionCapRejectsGrowthAndExpiredSessionsReleaseCapacity() {
        var nowMs = 1_000L
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(runtime, socketNowMs = { nowMs })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val acceptedSids = mutableListOf<String>()

        repeat(32) {
            val response = openSocketResponse(client, port)
            assertThat(response.first).isEqualTo(200)
            acceptedSids += requireNotNull(response.second)
        }
        assertThat(openSocketResponse(client, port).first).isEqualTo(429)

        nowMs += 3 * 60_000L + 1L
        assertThat(getSocket(client, port, acceptedSids.first()).use { it.code }).isEqualTo(400)
        assertThat(openSocketResponse(client, port).first).isEqualTo(200)
    }

    @Test
    fun requestBodyPolicyRejectsMissingInvalidAndOversizedLengthBeforeParsing() {
        assertThat(LocalNightscoutRequestBodyPolicy.evaluate(emptyMap()))
            .isEqualTo(LocalNightscoutRequestBodyDecision.MISSING_LENGTH)
        assertThat(LocalNightscoutRequestBodyPolicy.evaluate(mapOf("content-length" to "NaN")))
            .isEqualTo(LocalNightscoutRequestBodyDecision.INVALID_LENGTH)
        assertThat(LocalNightscoutRequestBodyPolicy.evaluate(mapOf("content-length" to "1000001")))
            .isEqualTo(LocalNightscoutRequestBodyDecision.TOO_LARGE)
        assertThat(LocalNightscoutRequestBodyPolicy.evaluate(mapOf("content-length" to "1000000")))
            .isEqualTo(LocalNightscoutRequestBodyDecision.ALLOWED)
    }

    @Test
    fun inboundPacketCountOverflowClosesSessionBeforeProcessing() {
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(
            runtime,
            socketLimits = LocalNightscoutSocketLimits(maxInboundPacketsPerPost = 4)
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        val payload = List(5) { "2" }.joinToString("\u001e")

        assertThat(postSocketCode(client, port, sid, payload)).isEqualTo(413)
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
    }

    @Test
    fun outboundPacketCountOverflowClosesSession() {
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(
            runtime,
            socketLimits = LocalNightscoutSocketLimits(
                maxOutboundPackets = 1,
                maxOutboundBytes = 1_024
            )
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)

        assertThat(postSocketCode(client, port, sid, "2\u001e2")).isEqualTo(413)
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
    }

    @Test
    fun outboundByteOverflowClosesSession() {
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(
            runtime,
            socketLimits = LocalNightscoutSocketLimits(
                maxOutboundPackets = 8,
                maxOutboundBytes = 1
            )
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)

        assertThat(postSocketCode(client, port, sid, "40")).isEqualTo(413)
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
    }

    @Test
    fun unauthenticatedTrafficCannotRefreshAbsoluteSessionTtl() {
        var nowMs = 1_000L
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(runtime, socketNowMs = { nowMs })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)

        nowMs += 3 * 60_000L - 1L
        assertThat(postSocketCode(client, port, sid, "2")).isEqualTo(200)
        nowMs += 2L

        assertThat(postSocketCode(client, port, sid, "2")).isEqualTo(400)
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
    }

    @Test
    fun boundedAuthenticatedFlowRefreshesTtlAndDeliversAckDataAndPong() {
        var nowMs = 1_000L
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(
            runtime,
            socketNowMs = { nowMs },
            socketLimits = LocalNightscoutSocketLimits(
                maxInboundPacketsPerPost = 4,
                maxOutboundPackets = 4,
                maxOutboundBytes = 1_000_000
            )
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        postSocket(client, port, sid, "40")
        getSocket(client, port, sid).close()

        nowMs += 1_000L
        postSocket(
            client,
            port,
            sid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\"}]"
        )
        val authorized = getSocket(client, port, sid).use { it.body?.string().orEmpty() }
        assertThat(authorized).contains("\"read\":true")
        assertThat(authorized).contains("dataUpdate")

        nowMs += 3 * 60_000L - 1L
        assertThat(postSocketCode(client, port, sid, "2")).isEqualTo(200)
        assertThat(getSocket(client, port, sid).use { it.body?.string().orEmpty() }).contains("3")
    }

    @Test
    fun serverBroadcastQueueOverflowRemovesAuthenticatedSessionAndFreshSessionStillWorks() {
        var nowMs = 1_000L
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        server = newServer(
            runtime,
            socketNowMs = { nowMs },
            socketLimits = LocalNightscoutSocketLimits(
                maxOutboundPackets = 2,
                maxOutboundBytes = 1_000_000
            )
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val staleSid = openSocket(client, port)
        postSocket(client, port, staleSid, "40")
        getSocket(client, port, staleSid).close()
        postSocket(
            client,
            port,
            staleSid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\"}]"
        )
        assertThat(getSocket(client, port, staleSid).use { it.body?.string().orEmpty() })
            .contains("dataUpdate")

        repeat(3) { index ->
            val timestamp = 1_800_000_000_000L + index
            assertThat(
                postApiJsonCode(
                    client = client,
                    port = port,
                    path = "/api/v1/entries.json",
                    body = """{"sgv":${100 + index},"date":$timestamp}"""
                )
            ).isEqualTo(200)
        }

        getSocket(client, port, staleSid).use { response ->
            assertThat(response.code).isEqualTo(400)
            assertThat(response.body?.string().orEmpty()).isEqualTo("unknown sid")
        }
        assertThat(postSocketCode(client, port, staleSid, "2")).isEqualTo(400)
        nowMs += 3 * 60_000L + 1L
        assertThat(getSocket(client, port, staleSid).use { it.code }).isEqualTo(400)

        val freshSid = openSocket(client, port)
        postSocket(client, port, freshSid, "40")
        getSocket(client, port, freshSid).close()
        postSocket(
            client,
            port,
            freshSid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\"}]"
        )
        val authorized = getSocket(client, port, freshSid).use { it.body?.string().orEmpty() }
        assertThat(authorized).contains("\"read\":true")
        assertThat(authorized).contains("dataUpdate")

        assertThat(
            postApiJsonCode(
                client = client,
                port = port,
                path = "/api/v1/entries.json",
                body = """{"sgv":104,"date":1800000000004}"""
            )
        ).isEqualTo(200)
        assertThat(getSocket(client, port, freshSid).use { it.body?.string().orEmpty() })
            .contains("dataUpdate")
    }

    @Test
    fun broadcastOverflowBeforePacketLinearizationRejectsStalePostWithoutSideEffects() {
        val port = freePort()
        val runtime = MutableLocalNightscoutRuntimeState()
        val postDecoded = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val blockNextPost = AtomicBoolean(false)
        val invalidationCalls = AtomicInteger(0)
        server = newServer(
            runtime = runtime,
            socketLimits = LocalNightscoutSocketLimits(
                maxOutboundPackets = 2,
                maxOutboundBytes = 1_000_000
            ),
            onClinicalInputPersisted = { invalidationCalls.incrementAndGet() },
            socketPostBeforePacketProcessing = {
                if (blockNextPost.compareAndSet(true, false)) {
                    postDecoded.countDown()
                    check(releasePost.await(5, TimeUnit.SECONDS))
                }
            }
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val staleSid = openSocket(client, port)
        postSocket(client, port, staleSid, "40")
        getSocket(client, port, staleSid).close()
        postSocket(
            client,
            port,
            staleSid,
            "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\"}]"
        )
        assertThat(getSocket(client, port, staleSid).use { it.body?.string().orEmpty() })
            .contains("dataUpdate")

        val executor = Executors.newSingleThreadExecutor()
        blockNextPost.set(true)
        val stalePost = executor.submit<Int> {
            postSocketCode(
                client = client,
                port = port,
                sid = staleSid,
                packet = listOf(
                    "422[\"dbAdd\",{\"collection\":\"treatments\",\"data\":{\"_id\":\"stale-post-treatment\",\"eventType\":\"Sensor Change\",\"mills\":1800000010000}}]",
                    "423[\"dbUpdate\",{\"collection\":\"treatments\",\"data\":{\"_id\":\"stale-post-treatment\",\"eventType\":\"Sensor Change\",\"mills\":1800000010001}}]"
                ).joinToString("\u001e")
            )
        }
        try {
            assertThat(postDecoded.await(5, TimeUnit.SECONDS)).isTrue()
            repeat(3) { index ->
                assertThat(
                    postApiJsonCode(
                        client = client,
                        port = port,
                        path = "/api/v1/entries.json",
                        body = """{"sgv":${110 + index},"date":${1_800_000_010_001L + index}}"""
                    )
                ).isEqualTo(200)
            }
            val invalidationsAfterOverflow = invalidationCalls.get()
            assertThat(getSocket(client, port, staleSid).use { it.code }).isEqualTo(400)

            releasePost.countDown()

            assertThat(stalePost.get(5, TimeUnit.SECONDS)).isEqualTo(400)
            assertThat(runBlocking { db.therapyDao().byId("stale-post-treatment") }).isNull()
            assertThat(invalidationCalls.get()).isEqualTo(invalidationsAfterOverflow)
        } finally {
            releasePost.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun updateAfterSnapshotCaptureIsDeliveredExactlyOnceAfterHistory() =
        assertUpdateDuringSnapshot(LocalNightscoutSnapshotPhase.AFTER_CAPTURE)

    @Test
    fun updateBeforeSnapshotCaptureIsNotDuplicatedByBufferedDelta() =
        assertUpdateDuringSnapshot(LocalNightscoutSnapshotPhase.BEFORE_CAPTURE)

    @Test
    fun olderBufferedRevisionCannotOverwriteNewerSnapshotValue() =
        assertTherapyRevisionDuringSnapshot(LocalNightscoutSnapshotPhase.BEFORE_CAPTURE)

    @Test
    fun correctedTherapyAfterSnapshotIsDeliveredAfterItsOldVersion() =
        assertTherapyRevisionDuringSnapshot(LocalNightscoutSnapshotPhase.AFTER_CAPTURE)

    @Test
    fun correctionBackToSnapshotValuePreservesOrderedSuffix() =
        assertTherapyRevisionDuringSnapshot(LocalNightscoutSnapshotPhase.AFTER_CAPTURE, returnToSnapshot = true)

    @Test
    fun stopAndRestartCanWinBeforeReadyPublicationWithoutLockInversion() = assertStopBeforeReady(disable = false)

    @Test
    fun disableAndRestartCanWinBeforeReadyPublicationWithoutLockInversion() = assertStopBeforeReady(disable = true)

    private fun assertStopBeforeReady(disable: Boolean) {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopObserved = AtomicBoolean(false)
        val checkpointResumed = CountDownLatch(1)
        val runtime = MutableLocalNightscoutRuntimeState()
        val port = freePort()
        server = newServer(runtime, socketSnapshotCheckpoint = { phase ->
            if (phase == LocalNightscoutSnapshotPhase.BEFORE_PUBLISH) {
                entered.countDown()
                val completed = stopped.await(2, TimeUnit.SECONDS)
                stopObserved.set(completed)
                checkpointResumed.countDown()
                // Abort before the lifecycle callback on RED, avoiding an uninterruptible deadlock.
                check(completed)
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        val executor = Executors.newFixedThreadPool(2)
        val authorization = executor.submit<Unit> { runCatching { postSocketCode(client, port, sid, authorizePacket()) }; Unit }
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val stop = executor.submit<Unit> {
                try {
                    if (disable) server?.update(false, port) else server?.stop()
                } finally { stopped.countDown() }
            }
            stop.get(5, TimeUnit.SECONDS)
            assertThat(checkpointResumed.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(stopObserved.get()).isTrue()
            val nextPort = freePort()
            assertThat(server?.update(true, nextPort)).isEqualTo(nextPort)
            release.countDown()
            authorization.get(5, TimeUnit.SECONDS)
            assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
            assertThat(runtime.value.port).isEqualTo(nextPort)
        } finally {
            release.countDown()
            stopped.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun uploadedTreatmentDoesNotEchoToItsSourceWhilePeersReceiveCanonicalRetries() {
        val invalidations = AtomicInteger()
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(),
            socketLimits = LocalNightscoutSocketLimits(pollWaitMs = 20L),
            onClinicalInputPersisted = { invalidations.incrementAndGet() })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        fun authorizedSocket(): String = openSocket(client, port).also { sid ->
            postSocket(client, port, sid, "40")
            getSocket(client, port, sid).close()
            postSocket(client, port, sid, authorizePacket())
            getSocket(client, port, sid).close()
        }
        val donor = authorizedSocket()
        val observer = authorizedSocket()
        fun upload(event: String, grams: Int) = postSocket(client, port, donor,
            "422[\"$event\",{\"collection\":\"treatments\",\"_id\":\"uploaded-meal\",\"data\":{\"_id\":\"uploaded-meal\",\"eventType\":\"Carb Correction\",\"carbs\":$grams,\"mills\":1800000010000}}]")
        fun poll(sid: String): String = getSocket(client, port, sid).use { response ->
            assertThat(response.code).isEqualTo(200)
            response.body!!.string()
        }
        fun carbs(body: String): List<Double> = body.split('\u001e')
            .filter { it.startsWith("42[") }.flatMap { packet ->
                val event = JsonParser.parseString(packet.drop(2)).asJsonArray
                if (event[0].asString != "dataUpdate") emptyList()
                else event[1].asJsonObject.getAsJsonArray("treatments")
                    ?.map { it.asJsonObject["carbs"].asDouble }.orEmpty()
            }
        fun assertAcknowledgedWithoutEcho() {
            val body = poll(donor)
            assertThat(body).contains("432[")
            assertThat(carbs(body)).isEmpty()
        }

        upload("dbAdd", 10)
        assertAcknowledgedWithoutEcho()
        assertThat(carbs(poll(observer))).containsExactly(10.0)
        assertThat(invalidations.get()).isEqualTo(1)
        val original = runBlocking { db.therapyDao().byId("uploaded-meal") }

        upload("dbAdd", 10)
        assertAcknowledgedWithoutEcho()
        assertThat(carbs(poll(observer))).containsExactly(10.0)
        assertThat(runBlocking { db.therapyDao().byId("uploaded-meal") }).isEqualTo(original)
        assertThat(invalidations.get()).isEqualTo(1)

        upload("dbUpdate", 20)
        assertAcknowledgedWithoutEcho()
        assertThat(carbs(poll(observer))).containsExactly(20.0)
        assertThat(invalidations.get()).isEqualTo(2)

        upload("dbUpdate", 20)
        assertAcknowledgedWithoutEcho()
        assertThat(carbs(poll(observer))).containsExactly(20.0)
        assertThat(invalidations.get()).isEqualTo(2)

        // A separate HTTP writer must still reach AAPS as well as the other client.
        assertThat(postApiJsonCode(client, port, "/api/v1/treatments.json",
            "{\"_id\":\"http-meal\",\"eventType\":\"Carb Correction\",\"carbs\":15,\"mills\":1800000010001}"))
            .isEqualTo(200)
        assertThat(carbs(poll(donor))).containsExactly(15.0)
        assertThat(carbs(poll(observer))).containsExactly(15.0)
    }

    @Test
    fun committedTreatmentRetryRepairsPeerDeliveryAfterPostcommitAuditFailureWithoutSourceEcho() {
        val invalidations = AtomicInteger()
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(),
            socketLimits = LocalNightscoutSocketLimits(pollWaitMs = 20L),
            onClinicalInputPersisted = { invalidations.incrementAndGet() })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        fun poll(sid: String): String = getSocket(client, port, sid).use { response ->
            assertThat(response.code).isEqualTo(200)
            response.body!!.string()
        }
        fun authorizedSocket(): String = openSocket(client, port).also { sid ->
            postSocket(client, port, sid, "40")
            poll(sid)
            postSocket(client, port, sid, authorizePacket())
            // Drain initial history separately: retry recovery must reach the already-connected peer.
            assertThat(poll(sid)).contains("431[")
        }
        val source = authorizedSocket()
        val peer = authorizedSocket()
        val packet = """422["dbAdd",{"collection":"treatments","data":{"_id":"postcommit-meal","eventType":"Carb Correction","carbs":10,"mills":1800000010000}}]"""
        val sql = db.openHelper.writableDatabase
        // This audit runs after the therapy commit, before canonical publication and the source ACK.
        sql.execSQL("""
            CREATE TRIGGER fail_socket_postcommit_audit BEFORE INSERT ON audit_logs
            WHEN NEW.message = 'local_nightscout_clinical_input_persisted'
            BEGIN SELECT RAISE(ABORT, 'test postcommit audit failure'); END
        """.trimIndent())
        try {
            assertThat(postSocketCode(client, port, source, packet)).isEqualTo(400)
        } finally {
            sql.execSQL("DROP TRIGGER fail_socket_postcommit_audit")
        }

        val committed = requireNotNull(runBlocking { db.therapyDao().byId("postcommit-meal") })
        assertThat(committed.timestamp).isEqualTo(1_800_000_010_000L)
        assertThat(JsonParser.parseString(committed.payloadJson).asJsonObject["carbs"].asDouble)
            .isEqualTo(10.0)
        assertThat(invalidations.get()).isEqualTo(1)
        val failures = runBlocking {
            db.auditLogDao().recentByMessage("local_nightscout_socket_packet_parse_failed", 0L, 10)
        }
        assertThat(failures).hasSize(1)
        assertThat(JsonParser.parseString(failures.single().metadataJson).asJsonObject["errorType"].asString)
            .isEqualTo("SQLiteConstraintException")
        assertThat(getSocket(client, port, source).use { it.code }).isEqualTo(400)
        assertThat(poll(peer)).doesNotContain("42[")

        val retrySource = authorizedSocket()
        assertThat(retrySource).isNotEqualTo(source)
        repeat(2) {
            postSocket(client, port, retrySource, packet)
            val ack = poll(retrySource)
            assertThat(ack).contains("432[")
            assertThat(ack).contains("postcommit-meal")
            assertThat(ack).doesNotContain("42[")
            val updates = poll(peer).split('\u001e').filter { it.startsWith("42[") }
                .map { JsonParser.parseString(it.drop(2)).asJsonArray }
            assertThat(updates).hasSize(1)
            assertThat(updates.single()[0].asString).isEqualTo("dataUpdate")
            val payload = updates.single()[1].asJsonObject
            assertThat(payload["delta"].asBoolean).isTrue()
            val rows = payload.getAsJsonArray("treatments")
            assertThat(rows.size()).isEqualTo(1)
            val row = rows.single().asJsonObject
            assertThat(row["_id"].asString).isEqualTo(committed.id)
            assertThat(row["date"].asLong).isEqualTo(committed.timestamp)
            assertThat(row["mills"].asLong).isEqualTo(committed.timestamp)
            assertThat(row["carbs"].asDouble).isEqualTo(10.0)
            assertThat(runBlocking { db.therapyDao().byId(committed.id) }).isEqualTo(committed)
            assertThat(invalidations.get()).isEqualTo(1)
        }
    }

    @Test
    fun delayedOlderCommittedTreatmentCannotRevertPublishedSnapshot() {
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pauseNext = AtomicBoolean(false)
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(),
            socketLimits = LocalNightscoutSocketLimits(pollWaitMs = 20L),
            onClinicalInputPersisted = {
                if (pauseNext.compareAndSet(true, false)) {
                    committed.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val firstDonor = openSocket(client, port)
        val secondDonor = openSocket(client, port)
        postSocket(client, port, firstDonor, authorizePacket())
        postSocket(client, port, secondDonor, authorizePacket())
        fun update(sid: String, carbs: Int) = postSocketCode(client, port, sid,
            "422[\"dbUpdate\",{\"collection\":\"treatments\",\"data\":{\"_id\":\"delayed-revision\",\"eventType\":\"Carb Correction\",\"carbs\":$carbs,\"mills\":1800000010000}}]")
        val executor = Executors.newSingleThreadExecutor()
        pauseNext.set(true)
        val oldWrite = executor.submit<Int> { update(firstDonor, 10) }
        try {
            assertThat(committed.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(update(secondDonor, 20)).isEqualTo(200)
            val sid = openSocket(client, port)
            postSocket(client, port, sid, "40")
            getSocket(client, port, sid).close()
            postSocket(client, port, sid, authorizePacket())
            fun readCarbs(): List<Double> = getSocket(client, port, sid).use { response ->
                assertThat(response.code).isEqualTo(200)
                response.body!!.string().split('\u001e').filter { it.startsWith("42[") }.flatMap {
                    JsonParser.parseString(it.drop(2)).asJsonArray[1].asJsonObject.getAsJsonArray("treatments")
                        ?.filter { row -> row.asJsonObject["_id"].asString == "delayed-revision" }
                        ?.map { row -> row.asJsonObject["carbs"].asDouble }.orEmpty()
                }
            }
            assertThat(readCarbs()).containsExactly(20.0)
            release.countDown()
            assertThat(oldWrite.get(5, TimeUnit.SECONDS)).isEqualTo(200)
            val persisted = requireNotNull(runBlocking { db.therapyDao().byId("delayed-revision") })
            assertThat(JsonParser.parseString(persisted.payloadJson).asJsonObject["carbs"].asDouble).isEqualTo(20.0)
            assertThat(readCarbs()).doesNotContain(10.0)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun delayedEntryUsesSameCanonicalSourceAsInitialSnapshot() {
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pauseNext = AtomicBoolean(false)
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(),
            socketLimits = LocalNightscoutSocketLimits(pollWaitMs = 20L),
            onClinicalInputPersisted = {
                if (pauseNext.compareAndSet(true, false)) {
                    committed.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        postSocket(client, port, openSocket(client, port), authorizePacket())
        val executor = Executors.newSingleThreadExecutor()
        pauseNext.set(true)
        val oldWrite = executor.submit<Int> { postApiJsonCode(client, port, "/api/v1/entries.json",
            """{"sgv":108,"date":1800000010000}""") }
        try {
            assertThat(committed.await(5, TimeUnit.SECONDS)).isTrue()
            runBlocking {
                db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(timestamp = 1_800_000_010_000L,
                    mmol = 7.0, source = "aaps_broadcast", quality = "GOOD")))
            }
            val sid = openSocket(client, port)
            postSocket(client, port, sid, "40")
            getSocket(client, port, sid).close()
            postSocket(client, port, sid, authorizePacket())
            fun readGlucose(): List<Int> = getSocket(client, port, sid).use { response ->
                assertThat(response.code).isEqualTo(200)
                response.body!!.string().split('\u001e').filter { it.startsWith("42[") }.flatMap {
                    JsonParser.parseString(it.drop(2)).asJsonArray[1].asJsonObject.getAsJsonArray("sgvs")
                        ?.map { row -> row.asJsonObject["sgv"].asInt }.orEmpty()
                }
            }
            assertThat(readGlucose()).containsExactly(126)
            release.countDown()
            assertThat(oldWrite.get(5, TimeUnit.SECONDS)).isEqualTo(200)
            assertThat(readGlucose()).containsExactly(126)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertTherapyRevisionDuringSnapshot(phase: LocalNightscoutSnapshotPhase, returnToSnapshot: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNext = AtomicBoolean(false)
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(),
            socketLimits = LocalNightscoutSocketLimits(pollWaitMs = 20L),
            socketSnapshotCheckpoint = { checkpoint ->
                if (checkpoint == phase && blockNext.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val donor = openSocket(client, port)
        postSocket(client, port, donor, authorizePacket())
        fun update(carbs: Int) = postSocket(client, port, donor,
            "422[\"dbUpdate\",{\"collection\":\"treatments\",\"data\":{\"_id\":\"capture-revision\",\"eventType\":\"Carb Correction\",\"carbs\":$carbs,\"mills\":1800000010000}}]")
        if (phase == LocalNightscoutSnapshotPhase.AFTER_CAPTURE) update(if (returnToSnapshot) 20 else 10)
        val sid = openSocket(client, port)
        postSocket(client, port, sid, "40")
        getSocket(client, port, sid).close()
        val executor = Executors.newSingleThreadExecutor()
        blockNext.set(true)
        val authorization = executor.submit<Int> { postSocketCode(client, port, sid, authorizePacket()) }
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            if (phase == LocalNightscoutSnapshotPhase.BEFORE_CAPTURE || returnToSnapshot) update(10)
            update(20)
            val persisted = requireNotNull(runBlocking { db.therapyDao().byId("capture-revision") })
            assertThat(JsonParser.parseString(persisted.payloadJson).asJsonObject["carbs"].asDouble).isEqualTo(20.0)
            release.countDown()
            assertThat(authorization.get(5, TimeUnit.SECONDS)).isEqualTo(200)
            val delivered = mutableListOf<Double>()
            repeat(3) {
                val body = getSocket(client, port, sid).use { response ->
                    assertThat(response.code).isEqualTo(200)
                    response.body!!.string()
                }
                body.split('\u001e').filter { it.startsWith("42[") }.forEach { packet ->
                    val event = JsonParser.parseString(packet.drop(2)).asJsonArray
                    if (event[0].asString == "dataUpdate") event[1].asJsonObject.getAsJsonArray("treatments")?.forEach {
                        if (it.asJsonObject["_id"].asString == "capture-revision") delivered += it.asJsonObject["carbs"].asDouble
                    }
                }
            }
            assertThat(delivered).containsExactlyElementsIn(
                when {
                    returnToSnapshot -> listOf(20.0, 10.0, 20.0)
                    phase == LocalNightscoutSnapshotPhase.BEFORE_CAPTURE -> listOf(20.0)
                    else -> listOf(10.0, 20.0)
                }
            ).inOrder()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertUpdateDuringSnapshot(phase: LocalNightscoutSnapshotPhase) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNext = AtomicBoolean(false)
        val port = freePort()
        server = newServer(
            MutableLocalNightscoutRuntimeState(),
            socketLimits = LocalNightscoutSocketLimits(pollWaitMs = 20L),
            socketSnapshotCheckpoint = { checkpoint ->
                if (checkpoint == phase && blockNext.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        )
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val donor = openSocket(client, port)
        postSocket(client, port, donor, authorizePacket())
        val sid = openSocket(client, port)
        postSocket(client, port, sid, "40")
        getSocket(client, port, sid).close()
        val executor = Executors.newSingleThreadExecutor()
        blockNext.set(true)
        val authorization = executor.submit<Int> { postSocketCode(client, port, sid, authorizePacket()) }
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(postApiJsonCode(client, port, "/api/v1/entries.json",
                """{"sgv":108,"date":1800000010000}""")).isEqualTo(200)
            val duringCapture = getSocket(client, port, sid).use { it.body!!.string() }
            assertThat(duringCapture).doesNotContain("dataUpdate")
            release.countDown()
            assertThat(authorization.get(5, TimeUnit.SECONDS)).isEqualTo(200)
            val dates = mutableListOf<Long>()
            val deliveredValues = mutableListOf<Int>()
            repeat(3) {
                val body = getSocket(client, port, sid).use { response ->
                    assertThat(response.code).isEqualTo(200)
                    response.body!!.string()
                }
                body.split('\u001e').filter { it.startsWith("42[") }.forEach { packet ->
                    val event = JsonParser.parseString(packet.drop(2)).asJsonArray
                    if (event[0].asString == "dataUpdate") {
                        event[1].asJsonObject.getAsJsonArray("sgvs")?.forEach {
                            dates += it.asJsonObject["date"].asLong
                            deliveredValues += it.asJsonObject["sgv"].asInt
                        }
                    }
                }
            }
            assertThat(dates).containsExactly(1_800_000_010_000L)
            assertThat(deliveredValues).containsExactly(108)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun overlappingValidAuthorizePostsCloseSessionWithoutLateReady() = assertOverlappingAuthorize(validSecond = true)

    @Test
    fun overlappingRejectedAuthorizeCannotBeOverriddenByLateValidPost() = assertOverlappingAuthorize(validSecond = false)

    private fun assertOverlappingAuthorize(validSecond: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockFirst = AtomicBoolean(true)
        val runtime = MutableLocalNightscoutRuntimeState()
        val port = freePort()
        server = newServer(runtime, socketSnapshotCheckpoint = { phase ->
            if (phase == LocalNightscoutSnapshotPhase.AFTER_CAPTURE && blockFirst.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<Int> { postSocketCode(client, port, sid, authorizePacket()) }
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(postSocketCode(client, port, sid,
                if (validSecond) authorizePacket() else "422[\"authorize\",{}]")).isEqualTo(400)
            release.countDown()
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(400)
            assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
            assertThat(runtime.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
            assertThat(runBlocking { db.therapyDao().since(0L) }).isEmpty()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun latePongCannotReviveExpiredHeartbeat() = assertExpiredHeartbeatPost("3")

    @Test
    fun treatmentPostCannotRunAfterHeartbeatExpires() = assertExpiredHeartbeatPost(
        "422[\"dbAdd\",{\"collection\":\"treatments\",\"data\":{\"_id\":\"expired-post\",\"eventType\":\"Sensor Change\",\"mills\":1800000010000}}]"
    )

    private fun assertExpiredHeartbeatPost(packet: String) {
        var now = 1_000L
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState(), socketNowMs = { now })
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        postSocket(client, port, sid, authorizePacket())
        getSocket(client, port, sid).close()
        now += 25_000L
        assertThat(getSocket(client, port, sid).use { it.body!!.string() }).isEqualTo("2")
        now += 20_000L
        assertThat(postSocketCode(client, port, sid, packet)).isEqualTo(400)
        assertThat(runBlocking { db.therapyDao().byId("expired-post") }).isNull()
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
    }

    @Test
    fun normalClosePacketAcknowledgesThenRejectsFurtherRequests() {
        val port = freePort()
        server = newServer(MutableLocalNightscoutRuntimeState())
        assertThat(server?.update(true, port)).isEqualTo(port)
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        assertThat(postSocketCode(client, port, sid, "1")).isEqualTo(200)
        assertThat(getSocket(client, port, sid).use { it.code }).isEqualTo(400)
    }

    private fun authorizePacket() = "421[\"authorize\",{\"client\":\"AndroidAPS\",\"secret\":\"$apiSecretSha1\"}]"

    private fun newServer(
        runtime: MutableLocalNightscoutRuntimeState,
        socketNowMs: () -> Long = System::currentTimeMillis,
        socketLimits: LocalNightscoutSocketLimits = LocalNightscoutSocketLimits(),
        onClinicalInputPersisted: suspend () -> Unit = {},
        socketPostBeforePacketProcessing: () -> Unit = {},
        socketSnapshotCheckpoint: (LocalNightscoutSnapshotPhase) -> Unit = {},
        settingsStore: AppSettingsStore = AppSettingsStore(context)
    ) = LocalNightscoutServer(
        context = context,
        db = db,
        settingsStore = settingsStore,
        gson = Gson(),
        auditLogger = AuditLogger(db.auditLogDao(), Gson()),
        tlsMaterialProvider = ::loadMaterial,
        runtimeState = runtime,
        socketNowMs = socketNowMs,
        socketLimits = socketLimits,
        onClinicalInputPersisted = onClinicalInputPersisted,
        socketPostBeforePacketProcessing = socketPostBeforePacketProcessing,
        socketSnapshotCheckpoint = socketSnapshotCheckpoint
    )

    private suspend fun configuredLocalSettings(port: Int): AppSettingsStore =
        AppSettingsStore(context).also { settings ->
            settings.update {
                it.copy(
                    nightscoutUrl = "",
                    localNightscoutEnabled = true,
                    localNightscoutPort = port,
                    localNightscoutLegacyMigrationAcknowledged = true,
                    localCommandFallbackEnabled = false
                )
            }
            settings.setTherapyActionsArmed(true)
        }

    private fun repository(
        settings: AppSettingsStore,
        localIdentityProvider: () -> LocalNightscoutClientIdentity
    ) = NightscoutActionRepository(
        context = context,
        db = db,
        settingsStore = settings,
        apiFactory = ApiFactory(localIdentityProvider),
        carbsSendThrottle = CarbsSendThrottle(db.actionCommandDao()),
        tempTargetSendThrottle = TempTargetSendThrottle(db.actionCommandDao()),
        gson = Gson(),
        auditLogger = AuditLogger(db.auditLogDao(), Gson())
    )

    private fun managerTempTargetCommand(suffix: String) = ActionCommand(
        id = "command-$suffix",
        type = "temp_target",
        params = mapOf(
            "targetMmol" to "6.0",
            "durationMinutes" to "30",
            "reason" to "test"
        ),
        safetySnapshot = SafetySnapshot(false, true, null, 0),
        idempotencyKey = "TargetManager.v1:$suffix"
    )

    private fun authorizeLocalServer(port: Int) {
        val client = trustedClient(caCertificate)
        val sid = openSocket(client, port)
        postSocket(client, port, sid, "40")
        getSocket(client, port, sid).close()
        postSocket(client, port, sid, authorizePacket())
        assertThat(getSocket(client, port, sid).use { it.body?.string().orEmpty() })
            .contains("\"read\":true")
    }

    private fun installAapsRelayReceiver() {
        val packageName = "info.nightscout.androidaps"
        val relayIntent = Intent("com.eveningoutpost.dexdrip.NS_EMULATOR").setPackage(packageName)
        val receiver = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.TestNightscoutReceiver"
                exported = true
            }
        }
        shadowOf(context.packageManager).apply {
            installPackage(PackageInfo().apply { this.packageName = packageName })
            addResolveInfoForIntent(relayIntent, receiver)
        }
    }

    private fun loadMaterial(): LocalNightscoutTlsServerMaterial {
        val loaded = LocalNightscoutTlsIdentityRepository(storage).load()
        var authenticator: LocalNightscoutApiAuthenticator? = null
        return try {
            authenticator = LocalNightscoutApiAuthenticator.fromRawSecret(loaded.apiSecretBytes)
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(loaded.keyStore, loaded.password) }
            LocalNightscoutTlsServerMaterial(
                socketFactory = NanoHTTPD.makeSSLSocketFactory(loaded.keyStore, keyManagers),
                caCertificate = loaded.caCertificate,
                serverCertificate = loaded.serverCertificate,
                authenticator = authenticator
            )
        } catch (failure: Throwable) {
            authenticator?.clear()
            throw failure
        } finally {
            loaded.clearSecrets()
        }
    }

    private fun trustedClient(ca: X509Certificate): OkHttpClient {
        val certificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(ca)
            .build()
        return OkHttpClient.Builder()
            .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
            .build()
    }

    private fun get(
        client: OkHttpClient,
        port: Int,
        path: String,
        apiSecret: String? = null,
        headers: Map<String, String> = emptyMap()
    ) = client.newCall(
        Request.Builder()
            .url("https://127.0.0.1:$port$path")
            .apply {
                apiSecret?.let { header("api-secret", it) }
                headers.forEach(::header)
            }
            .build()
    ).execute()

    private fun getCode(
        client: OkHttpClient,
        port: Int,
        path: String,
        apiSecret: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Int = get(client, port, path, apiSecret, headers).use { it.code }

    private fun postApiJsonCode(
        client: OkHttpClient,
        port: Int,
        path: String,
        body: String
    ): Int = client.newCall(
        Request.Builder()
            .url("https://127.0.0.1:$port$path")
            .header("api-secret", apiSecretSha1)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute().use { it.code }

    private fun openSocket(client: OkHttpClient, port: Int): String {
        val response = openSocketResponse(client, port)
        assertThat(response.first).isEqualTo(200)
        return requireNotNull(response.second)
    }

    private fun openSocketResponse(client: OkHttpClient, port: Int): Pair<Int, String?> =
        get(client, port, "/socket.io/?EIO=4&transport=polling").use { response ->
            val body = response.body?.string().orEmpty()
            response.code to if (response.code == 200) {
                JsonParser.parseString(body.drop(1)).asJsonObject.get("sid").asString
            } else {
                null
            }
        }

    private fun postSocket(client: OkHttpClient, port: Int, sid: String, packet: String) {
        assertThat(postSocketCode(client, port, sid, packet)).isEqualTo(200)
    }

    private fun postSocketCode(client: OkHttpClient, port: Int, sid: String, packet: String): Int {
        client.newCall(
            Request.Builder()
                .url("https://127.0.0.1:$port/socket.io/?EIO=4&transport=polling&sid=$sid")
                .post(packet.toRequestBody("text/plain".toMediaType()))
                .build()
        ).execute().use { response -> return response.code }
    }

    private fun getSocket(client: OkHttpClient, port: Int, sid: String) =
        get(client, port, "/socket.io/?EIO=4&transport=polling&sid=$sid")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class MemoryAtomicStorage : AtomicLocalNightscoutTlsIdentityStorage {
        private var value: ByteArray? = null

        @Synchronized
        override fun readOrCreate(create: () -> ByteArray): ByteArray {
            value?.let { return it.copyOf() }
            val created = create()
            return try {
                value = created.copyOf()
                created.copyOf()
            } finally {
                created.fill(0)
            }
        }

        @Synchronized
        override fun reset() {
            value?.fill(0)
            value = null
        }
    }
}
