package io.aaps.copilot.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.domain.predict.UamMode
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.service.ApiFactory
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NightscoutUamCarbWriterIntegrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun actionRepositoryIsTheProductionUamCarbGateway() {
        assertThat(AapsCarbGateway::class.java.isAssignableFrom(NightscoutActionRepository::class.java))
            .isTrue()
    }

    @Test
    fun specializedWriterAllowsExactTenMinutePostsAndPreservesTransportIdentity() = runTest {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{"_id":"remote-1"}"""))
            server.enqueue(jsonResponse("""{"_id":"remote-2"}"""))
            server.enqueue(
                jsonResponse(
                    """
                    [
                      {
                        "_id":"remote-1",
                        "created_at":"${Instant.ofEpochMilli(1_784_246_400_000L)}",
                        "date":1784246400000,
                        "mills":1784246400000,
                        "carbs":15.0,
                        "notes":"UAM_ENGINE|id64=ZXBpc29kZQ|seq=1|ver=2|mode=NORMAL|"
                      }
                    ]
                    """.trimIndent()
                )
            )
            val settingsStore = settingsStore(backgroundScope)
            settingsStore.update {
                it.copy(
                    nightscoutUrl = server.url("/").toString(),
                    localNightscoutEnabled = false,
                    localCommandFallbackEnabled = true
                )
            }
            settingsStore.setTherapyActionsArmed(true)
            val settings = settingsStore.settings.first()
            val apiFactory = ApiFactory()
            val firstTs = 1_784_246_400_000L
            val secondTs = firstTs + 10L * 60_000L
            val firstTag = UamTagCodec.buildTag("episode", 1, UamMode.NORMAL, version = 2)
            val secondTag = UamTagCodec.buildTag("episode", 2, UamMode.NORMAL, version = 2)

            val first = NightscoutActionRepository.postUamCarbEntryStatic(
                settings = settings,
                apiFactory = apiFactory,
                tsMs = firstTs,
                grams = 15.0,
                note = firstTag
            )
            val second = NightscoutActionRepository.postUamCarbEntryStatic(
                settings = settings,
                apiFactory = apiFactory,
                tsMs = secondTs,
                grams = 15.0,
                note = secondTag
            )

            assertThat(first.getOrNull()).isEqualTo("remote-1")
            assertThat(second.getOrNull()).isEqualTo("remote-2")
            val firstBody = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            val secondBody = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertThat(firstBody["date"].asLong).isEqualTo(firstTs)
            assertThat(firstBody["mills"].asLong).isEqualTo(firstTs)
            assertThat(firstBody["notes"].asString).isEqualTo(firstTag)
            assertThat(secondBody["date"].asLong).isEqualTo(secondTs)
            assertThat(secondBody["mills"].asLong).isEqualTo(secondTs)
            assertThat(secondBody["notes"].asString).isEqualTo(secondTag)

            val fetched = NightscoutActionRepository.fetchUamCarbEntriesStatic(
                settings = settings,
                apiFactory = apiFactory,
                sinceTsMs = firstTs - 60_000L
            ).getOrThrow()
            assertThat(fetched).containsExactly(
                AapsCarbEntry(
                    remoteId = "remote-1",
                    tsMs = firstTs,
                    grams = 15.0,
                    note = firstTag
                )
            )
        }
    }

    @Test
    fun coordinatorAndSpecializedWriterEnforceUnderTenAndAllowExactTen() = runTest {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{"_id":"remote-1"}"""))
            server.enqueue(jsonResponse("""{"_id":"remote-2"}"""))
            val settingsStore = settingsStore(backgroundScope)
            settingsStore.update {
                it.copy(
                    nightscoutUrl = server.url("/").toString(),
                    localNightscoutEnabled = false,
                    localCommandFallbackEnabled = true
                )
            }
            settingsStore.setTherapyActionsArmed(true)
            val gateway = TransportBackedGateway(
                settings = settingsStore.settings.first(),
                apiFactory = ApiFactory()
            )
            val firstTs = 1_784_246_400_000L
            var wallNow = firstTs
            val coordinator = UamExportCoordinator(
                gateway = gateway,
                reservationStore = FakeReservationStore(),
                wallClockMs = { wallNow }
            )

            wallNow = firstTs
            val first = coordinator.processUnified(
                candidate = candidate(nowTs = firstTs),
                enabled = true,
                dryRun = false
            )
            wallNow = firstTs + 9L * 60_000L
            val tooEarly = coordinator.processUnified(
                candidate = candidate(nowTs = firstTs + 9L * 60_000L),
                enabled = true,
                dryRun = false
            )
            wallNow = firstTs + 10L * 60_000L
            val exactBoundary = coordinator.processUnified(
                candidate = candidate(nowTs = firstTs + 10L * 60_000L),
                enabled = true,
                dryRun = false
            )

            assertThat(first.delivered).isTrue()
            assertThat(first.remoteId).isEqualTo("remote-1")
            assertThat(tooEarly.reason).isEqualTo("write_interval_under_10m")
            assertThat(tooEarly.postAttempted).isFalse()
            assertThat(exactBoundary.delivered).isTrue()
            assertThat(exactBoundary.remoteId).isEqualTo("remote-2")
            assertThat(gateway.posts.map { it.tsMs }).containsExactly(
                firstTs,
                firstTs + 10L * 60_000L
            ).inOrder()
            assertThat(gateway.posts.map { it.note }).containsExactly(
                UamTagCodec.buildTag("episode", 1, UamMode.NORMAL, version = 2),
                UamTagCodec.buildTag("episode", 2, UamMode.NORMAL, version = 2)
            ).inOrder()
        }
    }

    @Test
    fun specializedWriterRejectsSuccessfulPostWithoutVerifiableRemoteId() = runTest {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("{}"))
            val settingsStore = settingsStore(backgroundScope)
            settingsStore.update {
                it.copy(
                    nightscoutUrl = server.url("/").toString(),
                    localNightscoutEnabled = false,
                    localCommandFallbackEnabled = true
                )
            }
            settingsStore.setTherapyActionsArmed(true)

            val result = NightscoutActionRepository.postUamCarbEntryStatic(
                settings = settingsStore.settings.first(),
                apiFactory = ApiFactory(),
                tsMs = 1_784_246_400_000L,
                grams = 15.0,
                note = UamTagCodec.buildTag("episode", 1, UamMode.NORMAL, version = 2)
            )

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo("nightscout_post_missing_remote_id")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun specializedWriterDoesNotTouchNetworkBeforeForegroundArm() = runTest {
        MockWebServer().use { server ->
            val settingsStore = settingsStore(backgroundScope)
            settingsStore.update {
                it.copy(
                    nightscoutUrl = server.url("/").toString(),
                    localNightscoutEnabled = false,
                    therapyActionsArmed = false
                )
            }

            val result = NightscoutActionRepository.postUamCarbEntryStatic(
                settings = settingsStore.settings.first(),
                apiFactory = ApiFactory(),
                tsMs = 1_784_246_400_000L,
                grams = 15.0,
                note = UamTagCodec.buildTag("episode", 1, UamMode.NORMAL, version = 2)
            )

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo("therapy_actions_not_armed")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun specializedWriterRechecksArmImmediatelyBeforeTransport() = runTest {
        MockWebServer().use { server ->
            val settingsStore = settingsStore(backgroundScope)
            settingsStore.update {
                it.copy(
                    nightscoutUrl = server.url("/").toString(),
                    localNightscoutEnabled = false
                )
            }
            settingsStore.setTherapyActionsArmed(true)

            val result = NightscoutActionRepository.postUamCarbEntryStatic(
                settings = settingsStore.settings.first(),
                apiFactory = ApiFactory(),
                tsMs = 1_784_246_400_000L,
                grams = 15.0,
                note = UamTagCodec.buildTag("episode", 1, UamMode.NORMAL, version = 2),
                deliveryGuard = { "therapy_actions_not_armed" }
            )

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo("therapy_actions_not_armed")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    private fun settingsStore(scope: kotlinx.coroutines.CoroutineScope): AppSettingsStore {
        val directory = temporaryFolder.newFolder(UUID.randomUUID().toString())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "settings.preferences_pb") }
        )
        return AppSettingsStore(dataStore)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setBody(body)
        .addHeader("Content-Type", "application/json")

    private fun candidate(nowTs: Long) = UamExportPolicyInput(
        nowTs = nowTs,
        episodeId = "episode",
        activeSinceTs = 1_784_246_400_000L - 10L * 60_000L,
        confidence = 0.80,
        supportedLowerBoundGrams = 30.0,
        lowerBoundStableBuckets = 2,
        sensorTrust = 0.90,
        sensorBlocked = false,
        signedResidualMmol5 = 0.10,
        shortAverageDeltaMmol5 = 0.10,
        currentGlucoseMmol = 8.0,
        forecastMinimumMmol = 8.0,
        effectiveCobGrams = 0.0,
        therapyCoverage = 0.90,
        remoteLedger = emptyList(),
        sourceSnapshotTs = nowTs
    )

    private data class PostCall(
        val tsMs: Long,
        val grams: Double,
        val note: String
    )

    private class TransportBackedGateway(
        private val settings: io.aaps.copilot.config.AppSettings,
        private val apiFactory: ApiFactory
    ) : AapsCarbGateway {
        val posts = mutableListOf<PostCall>()
        private val remoteEntries = mutableListOf<AapsCarbEntry>()

        override suspend fun postCarbEntry(tsMs: Long, grams: Double, note: String): Result<String> {
            posts += PostCall(tsMs = tsMs, grams = grams, note = note)
            val result = NightscoutActionRepository.postUamCarbEntryStatic(
                settings = settings,
                apiFactory = apiFactory,
                tsMs = tsMs,
                grams = grams,
                note = note
            )
            result.getOrNull()?.let { remoteId ->
                remoteEntries += AapsCarbEntry(
                    remoteId = remoteId,
                    tsMs = tsMs,
                    grams = grams,
                    note = note
                )
            }
            return result
        }

        override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> =
            Result.success(remoteEntries.filter { it.tsMs >= sinceTsMs })
    }

    private class FakeReservationStore : UamExportReservationStore {
        private val reserved = linkedSetOf<String>()

        override suspend fun reserve(key: String): Boolean = reserved.add(key)
        override suspend fun markSent(key: String, remoteId: String) = Unit
        override suspend fun markPendingUnknown(key: String, detail: String?) = Unit
        override suspend fun release(key: String) {
            reserved.remove(key)
        }
    }
}
