package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.service.hasActiveManualTempTargetStatic
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TempTargetAttemptOwnershipRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CopilotDatabase
    private lateinit var settings: AppSettingsStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun concurrentFreshSubmissionsCreateOneRowAndAtMostOnePost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val firstRepository = repository(server)
            val secondRepository = repository(server, resetSettings = false)
            val firstCommand = eatingSoonCommand("concurrent-fresh").copy(id = "fresh-first")
            val secondCommand = firstCommand.copy(id = "fresh-second")

            val results = coroutineScope {
                val first = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    firstRepository.submitTempTarget(firstCommand) { delay(150L); null }
                }
                val second = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    secondRepository.submitTempTarget(secondCommand) { delay(150L); null }
                }
                first.start()
                second.start()
                listOf(first.await(), second.await())
            }

            assertThat(results.count { it }).isEqualTo(2)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(commandRows(firstCommand.idempotencyKey)).hasSize(1)
            assertThat(commandRows(firstCommand.idempotencyKey).single().status).isEqualTo("SENT")
        }
    }

    @Test
    fun concurrentReconciliationRetriesPostOnceAndWaiterRereadsSent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val firstRepository = repository(server)
            val secondRepository = repository(server, resetSettings = false)
            val command = managerCommand("concurrent-retry")
            db.actionCommandDao().upsert(entity(command, "PENDING"))
            val guards = AtomicInteger(0)

            val results = coroutineScope {
                val first = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    firstRepository.submitOrRetryTempTarget(command) {
                        guards.incrementAndGet()
                        delay(150L)
                        null
                    }
                }
                val second = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    secondRepository.submitOrRetryTempTarget(command.copy(id = "ignored-retry-id")) {
                        guards.incrementAndGet()
                        delay(150L)
                        null
                    }
                }
                first.start()
                second.start()
                listOf(first.await(), second.await())
            }

            assertThat(results).containsExactly(true, true)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(guards.get()).isEqualTo(2)
            assertThat(commandRows(command.idempotencyKey)).hasSize(1)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo("SENT")
        }
    }

    @Test
    fun concurrentReconciliationWaiterDoesNotRetryAfterUnknownPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val firstRepository = repository(server)
            val secondRepository = repository(server, resetSettings = false)
            val command = managerCommand("concurrent-unknown")
            db.actionCommandDao().upsert(entity(command, "PENDING"))
            val guards = AtomicInteger(0)

            val results = coroutineScope {
                val first = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    runCatching {
                        firstRepository.submitOrRetryTempTarget(command) {
                            guards.incrementAndGet()
                            delay(150L)
                            null
                        }
                    }
                }
                val second = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    runCatching {
                        secondRepository.submitOrRetryTempTarget(command.copy(id = "ignored-unknown-id")) {
                            guards.incrementAndGet()
                            delay(150L)
                            null
                        }
                    }
                }
                first.start()
                second.start()
                listOf(first.await(), second.await())
            }

            assertThat(results.count { it.exceptionOrNull() is
                NightscoutActionRepository.TempTargetDeliveryUnknownException }).isEqualTo(1)
            assertThat(results.count { it.getOrNull() == false }).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(guards.get()).isEqualTo(2)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo("PENDING")
        }
    }

    @Test
    fun duplicatePendingFailedBlockedAndSentSurviveDisarmingWithoutGuardOrPost() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            settings.setTherapyActionsArmed(false)
            val statuses = listOf("PENDING", "FAILED", "BLOCKED", "SENT")

            statuses.forEach { status ->
                val command = eatingSoonCommand("disarmed-${status.lowercase()}")
                val original = entity(command, status)
                db.actionCommandDao().upsert(original)

                val result = repository.submitTempTarget(command) { error("duplicate guard must not run") }

                assertThat(result).isEqualTo(status == "SENT")
                assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey))
                    .isEqualTo(original)
            }
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun preexistingPendingAndLegacyFailedRemainByteForByteAndKeepOwnership() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val pendingCommand = eatingSoonCommand("existing-pending")
            val failedCommand = eatingSoonCommand("legacy-failed")
            val pending = entity(pendingCommand, "PENDING", payloadJson = "{\"legacy\":\"pending\"}")
            val failed = entity(failedCommand, "FAILED", payloadJson = "{\"legacy\":\"failed\"}")
            db.actionCommandDao().upsert(pending)
            db.actionCommandDao().upsert(failed)

            assertThat(repository.submitTempTarget(pendingCommand) { "glucose_too_low" }).isFalse()
            assertThat(repository.submitTempTarget(failedCommand) { "glucose_too_low" }).isFalse()

            assertThat(db.actionCommandDao().byIdempotencyKey(pending.idempotencyKey)).isEqualTo(pending)
            assertThat(db.actionCommandDao().byIdempotencyKey(failed.idempotencyKey)).isEqualTo(failed)
            assertThat(hasActiveManualTempTargetStatic(listOf(pending), pending.timestamp, Gson())).isTrue()
            assertThat(
                hasActiveManualTempTargetStatic(
                    commands = listOf(failed),
                    now = failed.timestamp,
                    gson = Gson(),
                    replaceableSentIdempotencyKey = failed.idempotencyKey
                )
            ).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun automaticOwnershipFailurePreservesExistingUncertainLegacyRows() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            settings.setTargetManagerModeManually(TargetManagerMode.ACTIVE)
            listOf("PENDING", "FAILED").forEach { status ->
                val command = managerCommand("legacy-${status.lowercase()}").copy(
                    idempotencyKey = "AdaptiveTargetController.v1:legacy-${status.lowercase()}"
                )
                val original = entity(command, status, payloadJson = "{\"legacy\":\"$status\"}")
                db.actionCommandDao().upsert(original)

                assertThat(
                    repository.submitOrRetryTempTarget(command) {
                        error("ownership failure must precede guard")
                    }
                ).isFalse()
                assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey))
                    .isEqualTo(original)
            }
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun postFailureStaysPendingAndDuplicateHasNoGuardFallbackOrPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            val command = eatingSoonCommand("post-unknown")

            val failure = runCatching { repository.submitTempTarget(command) { null } }.exceptionOrNull()
            assertThat(failure)
                .isInstanceOf(NightscoutActionRepository.TempTargetDeliveryUnknownException::class.java)
            val pending = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)!!
            assertThat(pending.status).isEqualTo("PENDING")
            assertThat(hasActiveManualTempTargetStatic(listOf(pending), pending.timestamp, Gson())).isTrue()

            assertThat(repository.submitTempTarget(command) { error("duplicate guard must not run") }).isFalse()
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)).isEqualTo(pending)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun freshSubmissionNeverRetriesButReconciliationEntryPointMayRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val command = managerCommand("entrypoint-only")
            val failed = entity(command, "FAILED", payloadJson = "{\"legacy\":true}")
            db.actionCommandDao().upsert(failed)

            assertThat(repository.submitTempTarget(command) { error("fresh duplicate guard") }).isFalse()
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)).isEqualTo(failed)
            assertThat(server.requestCount).isEqualTo(0)

            assertThat(repository.submitOrRetryTempTarget(command) { null }).isTrue()
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo("SENT")
        }
    }

    @Test
    fun cancellationAndFatalGuardPropagateWithPendingReservationAndNoPost() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val cancelledCommand = eatingSoonCommand("cancelled")
            val guardEntered = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            val delivery = async(Dispatchers.Default) {
                repository.submitTempTarget(cancelledCommand) {
                    guardEntered.complete(Unit)
                    neverRelease.await()
                    null
                }
            }
            guardEntered.await()
            delivery.cancel()
            assertThat(runCatching { delivery.await() }.exceptionOrNull())
                .isInstanceOf(CancellationException::class.java)
            assertThat(db.actionCommandDao().byIdempotencyKey(cancelledCommand.idempotencyKey)?.status)
                .isEqualTo("PENDING")

            val fatalCommand = eatingSoonCommand("fatal")
            val fatal = runCatching {
                repository.submitTempTarget(fatalCommand) { throw AssertionError("fatal guard") }
            }.exceptionOrNull()
            assertThat(fatal).isInstanceOf(AssertionError::class.java)
            assertThat(db.actionCommandDao().byIdempotencyKey(fatalCommand.idempotencyKey)?.status)
                .isEqualTo("PENDING")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun blockedAndSentOwnershipSurviveDatabaseReopen() = runBlocking {
        val databaseName = "target-attempt-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var fileDb = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            MockWebServer().use { server ->
                val fileSettings = configuredSettings(server)
                val repository = repository(server, targetDb = fileDb, targetSettings = fileSettings, resetSettings = false)
                val command = eatingSoonCommand("reopen-blocked")
                assertThat(repository.submitTempTarget(command) { "glucose_too_low" }).isFalse()
                fileDb.close()

                fileDb = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseName)
                    .allowMainThreadQueries()
                    .build()
                val reopened = fileDb.actionCommandDao().byIdempotencyKey(command.idempotencyKey)!!
                assertThat(reopened.status).isEqualTo("BLOCKED")
                assertThat(hasActiveManualTempTargetStatic(listOf(reopened), reopened.timestamp, Gson())).isFalse()
                val reopenedRepository = repository(
                    server,
                    targetDb = fileDb,
                    targetSettings = fileSettings,
                    resetSettings = false
                )
                assertThat(reopenedRepository.submitTempTarget(command) { error("reopened duplicate guard") })
                    .isFalse()
                assertThat(server.requestCount).isEqualTo(0)
            }
        } finally {
            fileDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun activeSentIsReplaceableOnlyByExactKeyAndExpiredSentDoesNotBlock() {
        val now = 1_800_000_000_000L
        val activeCommand = eatingSoonCommand("active-sent")
        val active = entity(activeCommand, "SENT", timestamp = now - 5 * 60_000L)
        val expired = active.copy(
            id = "expired",
            idempotencyKey = eatingSoonCommand("expired-sent").idempotencyKey,
            timestamp = now - 31 * 60_000L
        )

        assertThat(hasActiveManualTempTargetStatic(listOf(expired), now, Gson())).isFalse()
        assertThat(hasActiveManualTempTargetStatic(listOf(active), now, Gson())).isTrue()
        assertThat(
            hasActiveManualTempTargetStatic(
                commands = listOf(active),
                now = now,
                gson = Gson(),
                replaceableSentIdempotencyKey = "manual:meal:other:eating-soon"
            )
        ).isTrue()
        assertThat(
            hasActiveManualTempTargetStatic(
                commands = listOf(active),
                now = now,
                gson = Gson(),
                replaceableSentIdempotencyKey = active.idempotencyKey
            )
        ).isFalse()
    }

    private suspend fun repository(
        server: MockWebServer,
        targetDb: CopilotDatabase = db,
        targetSettings: AppSettingsStore? = null,
        resetSettings: Boolean = true
    ): NightscoutActionRepository {
        val effectiveSettings = targetSettings ?: if (::settings.isInitialized) settings else AppSettingsStore(context)
        if (resetSettings) {
            effectiveSettings.update {
                it.copy(
                    nightscoutUrl = server.url("/").toString(),
                    localNightscoutEnabled = false,
                    localCommandFallbackEnabled = true,
                    localNightscoutLegacyMigrationAcknowledged = true
                )
            }
            effectiveSettings.setTherapyActionsArmed(true)
        }
        settings = effectiveSettings
        return NightscoutActionRepository(
            context = context,
            db = targetDb,
            settingsStore = effectiveSettings,
            apiFactory = ApiFactory(),
            carbsSendThrottle = CarbsSendThrottle(targetDb.actionCommandDao()),
            tempTargetSendThrottle = TempTargetSendThrottle(targetDb.actionCommandDao()),
            gson = Gson(),
            auditLogger = AuditLogger(targetDb.auditLogDao(), Gson())
        )
    }

    private suspend fun configuredSettings(server: MockWebServer): AppSettingsStore {
        val configured = AppSettingsStore(context)
        configured.update {
            it.copy(
                nightscoutUrl = server.url("/").toString(),
                localNightscoutEnabled = false,
                localCommandFallbackEnabled = true,
                localNightscoutLegacyMigrationAcknowledged = true
            )
        }
        configured.setTherapyActionsArmed(true)
        return configured
    }

    private suspend fun commandRows(idempotencyKey: String): List<ActionCommandEntity> =
        db.actionCommandDao().latest(100).filter { it.idempotencyKey == idempotencyKey }

    private fun eatingSoonCommand(suffix: String) = ActionCommand(
        id = "meal-$suffix",
        type = "temp_target",
        params = mapOf(
            "targetMmol" to "4.1",
            "durationMinutes" to "30",
            "reason" to "Eating Soon"
        ),
        safetySnapshot = SafetySnapshot(false, true, null, 0),
        idempotencyKey = "manual:meal:$suffix:eating-soon"
    )

    private fun managerCommand(suffix: String) = ActionCommand(
        id = "manager-$suffix",
        type = "temp_target",
        params = mapOf(
            "targetMmol" to "6.0",
            "durationMinutes" to "30",
            "reason" to "target_manager"
        ),
        safetySnapshot = SafetySnapshot(false, true, null, 0),
        idempotencyKey = "TargetManager.v1:$suffix"
    )

    private fun entity(
        command: ActionCommand,
        status: String,
        payloadJson: String = Gson().toJson(command.params),
        timestamp: Long = 1_800_000_000_000L
    ) = ActionCommandEntity(
        id = command.id,
        timestamp = timestamp,
        type = command.type,
        payloadJson = payloadJson,
        safetyJson = Gson().toJson(command.safetySnapshot),
        idempotencyKey = command.idempotencyKey,
        status = status
    )
}
