package io.aaps.copilot.scheduler

import androidx.work.Data
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ClinicalInputInvalidationLedgerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preferencesDataStoreRoundTripsExactPendingAndInFlightOwnership() = runTest {
        val fixture = fixture()
        val expected = ClinicalInputInvalidationLedgerState(
            latestGeneration = 8L,
            acknowledgedGeneration = 5L,
            pending = ClinicalInputInvalidationPending(
                generation = 8L,
                sources = setOf(ClinicalInputInvalidationSource.CONTEXT_EVENT),
                attempt = 1
            ),
            inFlight = ClinicalInputInvalidationDispatch(
                generation = 7L,
                dispatchToken = "exact-token-7",
                mode = ClinicalInputInvalidationMode.LOCAL_READ_ONLY,
                sources = setOf(ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT),
                attempt = 0
            )
        )

        fixture.ledger.write(expected)

        assertThat(fixture.ledger.read()).isEqualTo(expected)
        fixture.close()
    }

    @Test
    fun malformedInFlightWithoutTokenBecomesExplicitRecoverablePending() = runTest {
        val fixture = fixture()
        fixture.dataStore.edit { preferences ->
            preferences[longPreferencesKey("latest_generation")] = 4L
            preferences[longPreferencesKey("acknowledged_generation")] = 2L
            preferences[longPreferencesKey("in_flight_generation")] = 4L
            preferences[stringPreferencesKey("in_flight_mode")] = ClinicalInputInvalidationMode.NORMAL.name
            preferences[stringPreferencesKey("in_flight_sources")] = ClinicalInputInvalidationSource.BROADCAST_INGEST.name
            preferences[intPreferencesKey("in_flight_attempt")] = 0
        }

        val restored = fixture.ledger.read()

        assertRecoverable(restored, generation = 4L)
        fixture.close()
    }

    @Test
    fun malformedInFlightWithoutModeOrSourcesBecomesExplicitRecoverablePending() = runTest {
        listOf("mode", "sources").forEach { missing ->
            val fixture = fixture(missing)
            fixture.dataStore.edit { preferences ->
                preferences[longPreferencesKey("latest_generation")] = 6L
                preferences[longPreferencesKey("acknowledged_generation")] = 1L
                preferences[longPreferencesKey("in_flight_generation")] = 6L
                preferences[stringPreferencesKey("in_flight_token")] = "token-6"
                if (missing != "mode") {
                    preferences[stringPreferencesKey("in_flight_mode")] = ClinicalInputInvalidationMode.NORMAL.name
                }
                if (missing != "sources") {
                    preferences[stringPreferencesKey("in_flight_sources")] = ClinicalInputInvalidationSource.LOCAL_ACTIVITY.name
                }
                preferences[intPreferencesKey("in_flight_attempt")] = 0
            }

            assertRecoverable(fixture.ledger.read(), generation = 6L)
            fixture.close()
        }
    }

    @Test
    fun negativeAcknowledgedGenerationIsSanitizedWithoutDiscardingValidPendingOwner() = runTest {
        val fixture = fixture("negative-ack")
        fixture.dataStore.edit { preferences ->
            preferences[longPreferencesKey("latest_generation")] = 4L
            preferences[longPreferencesKey("acknowledged_generation")] = -9L
            preferences[longPreferencesKey("pending_generation")] = 4L
            preferences[stringPreferencesKey("pending_sources")] =
                ClinicalInputInvalidationSource.CONTEXT_EVENT.name
            preferences[intPreferencesKey("pending_attempt")] = 0
        }

        val restored = fixture.ledger.read()

        assertThat(restored.latestGeneration).isEqualTo(4L)
        assertThat(restored.acknowledgedGeneration).isEqualTo(0L)
        assertThat(restored.pending?.generation).isEqualTo(4L)
        fixture.close()
    }

    @Test
    fun zeroAndNegativeGenerationsCannotRestoreOwners() = runTest {
        listOf(0L, -3L).forEach { malformedGeneration ->
            val fixture = fixture("invalid-generation-$malformedGeneration")
            fixture.dataStore.edit { preferences ->
                preferences[longPreferencesKey("latest_generation")] = malformedGeneration
                preferences[longPreferencesKey("acknowledged_generation")] = -1L
                preferences[longPreferencesKey("pending_generation")] = malformedGeneration
                preferences[stringPreferencesKey("pending_sources")] =
                    ClinicalInputInvalidationSource.LOCAL_ACTIVITY.name
                preferences[intPreferencesKey("pending_attempt")] = 0
                preferences[longPreferencesKey("in_flight_generation")] = malformedGeneration
                preferences[stringPreferencesKey("in_flight_token")] = "invalid-token"
                preferences[stringPreferencesKey("in_flight_mode")] =
                    ClinicalInputInvalidationMode.NORMAL.name
                preferences[stringPreferencesKey("in_flight_sources")] =
                    ClinicalInputInvalidationSource.LOCAL_ACTIVITY.name
                preferences[intPreferencesKey("in_flight_attempt")] = 0
            }

            assertThat(fixture.ledger.read()).isEqualTo(ClinicalInputInvalidationLedgerState())
            fixture.close()
        }
    }

    @Test
    fun oversizedAttemptsBecomeBoundedRecoveryAndCannotDecodeAsWorkData() = runTest {
        val fixture = fixture("oversized-attempt")
        fixture.dataStore.edit { preferences ->
            preferences[longPreferencesKey("latest_generation")] = 7L
            preferences[longPreferencesKey("acknowledged_generation")] = 3L
            preferences[longPreferencesKey("in_flight_generation")] = 7L
            preferences[stringPreferencesKey("in_flight_token")] = "oversized-token"
            preferences[stringPreferencesKey("in_flight_mode")] =
                ClinicalInputInvalidationMode.NORMAL.name
            preferences[stringPreferencesKey("in_flight_sources")] =
                ClinicalInputInvalidationSource.BROADCAST_INGEST.name
            preferences[intPreferencesKey("in_flight_attempt")] = Int.MAX_VALUE
        }

        val restored = fixture.ledger.read()

        assertRecoverable(restored, generation = 7L)
        assertThat(restored.pending?.attempt).isAtMost(2)
        assertThat(
            ClinicalInvalidationWorkData.from(
                Data.Builder()
                    .putLong("clinical_invalidation_generation", 7L)
                    .putString("clinical_invalidation_dispatch_token", "oversized-token")
                    .putString("clinical_invalidation_mode", ClinicalInputInvalidationMode.NORMAL.name)
                    .putInt("clinical_invalidation_coordinator_attempt", Int.MAX_VALUE)
                    .build()
            )
        ).isNull()
        fixture.close()
    }

    @Test
    fun maximumGenerationRestoresAsBoundedRecoveryWithoutOverflowingWorkIdentity() = runTest {
        val fixture = fixture("maximum-generation")
        fixture.dataStore.edit { preferences ->
            preferences[longPreferencesKey("latest_generation")] = Long.MAX_VALUE
            preferences[longPreferencesKey("acknowledged_generation")] = -1L
        }

        val restored = fixture.ledger.read()

        assertRecoverable(restored, generation = Long.MAX_VALUE)
        assertThat(restored.pending?.attempt).isEqualTo(0)
        fixture.close()
    }

    @Test
    fun staleTerminalAttemptCannotStrandLatestRecoveryGeneration() = runTest {
        val fixture = fixture("stale-terminal-recovery")
        fixture.dataStore.edit { preferences ->
            preferences[longPreferencesKey("latest_generation")] = 7L
            preferences[longPreferencesKey("acknowledged_generation")] = 3L
            preferences[longPreferencesKey("pending_generation")] = 6L
            preferences[stringPreferencesKey("pending_sources")] =
                ClinicalInputInvalidationSource.LOCAL_ACTIVITY.name
            preferences[intPreferencesKey("pending_attempt")] = 2
            preferences[longPreferencesKey("in_flight_generation")] = 7L
            preferences[stringPreferencesKey("in_flight_mode")] =
                ClinicalInputInvalidationMode.NORMAL.name
            preferences[stringPreferencesKey("in_flight_sources")] =
                ClinicalInputInvalidationSource.BROADCAST_INGEST.name
            preferences[intPreferencesKey("in_flight_attempt")] = 0
        }

        val restored = fixture.ledger.read()

        assertThat(restored.pending?.generation).isEqualTo(7L)
        assertThat(restored.pending?.attempt).isEqualTo(0)
        assertThat(restored.pending?.sources).containsAtLeast(
            ClinicalInputInvalidationSource.LEDGER_RECOVERY,
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY
        )
        assertThat(restored.inFlight).isNull()

        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = fixture.ledger,
            trailingDelayMs = 100L,
            retryDelayMs = 25L,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "recovered-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(25L)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(7L)
        assertThat(requests.single().attempt).isEqualTo(0)
        advanceTimeBy(1_000L)
        runCurrent()
        assertThat(requests).hasSize(1)
        fixture.close()
    }

    @Test
    fun ordinaryLedgerReadAndWriteFailuresAreReportedWithoutEscapingProducerApi() = runTest {
        val failures = mutableListOf<Throwable>()
        val readFailure = coordinator(
            ledger = ThrowingLedger(readFailure = IllegalStateException("read_failed")),
            failures = failures
        )
        assertThat(
            readFailure.invalidatePersistedInput(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        ).isFalse()
        assertThat(failures.single()).hasMessageThat().isEqualTo("read_failed")

        failures.clear()
        val writeFailure = coordinator(
            ledger = ThrowingLedger(writeFailure = IllegalStateException("write_failed")),
            failures = failures
        )
        assertThat(
            writeFailure.invalidatePersistedInput(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        ).isFalse()
        assertThat(failures.single()).hasMessageThat().isEqualTo("write_failed")
    }

    @Test
    fun producerApiStillPropagatesCancellation() = runTest {
        val coordinator = coordinator(
            ledger = ThrowingLedger(readFailure = CancellationException("cancelled")),
            failures = mutableListOf()
        )

        val result = runCatching {
            coordinator.invalidatePersistedInput(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun producerApiPropagatesLedgerReadAndWriteErrors() = runTest {
        val readError = AssertionError("fatal_read")
        val writeError = AssertionError("fatal_write")

        val readResult = runCatching {
            coordinator(ThrowingLedger(readFailure = readError), mutableListOf())
                .invalidatePersistedInput(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        }
        val writeResult = runCatching {
            coordinator(ThrowingLedger(writeFailure = writeError), mutableListOf())
                .invalidatePersistedInput(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        }

        assertThat(readResult.exceptionOrNull()).isSameInstanceAs(readError)
        assertThat(writeResult.exceptionOrNull()).isSameInstanceAs(writeError)
    }

    private fun assertRecoverable(state: ClinicalInputInvalidationLedgerState, generation: Long) {
        assertThat(state.latestGeneration).isEqualTo(generation)
        assertThat(state.acknowledgedGeneration).isLessThan(generation)
        assertThat(state.inFlight).isNull()
        assertThat(state.pending?.generation).isEqualTo(generation)
        assertThat(state.pending?.sources)
            .contains(ClinicalInputInvalidationSource.LEDGER_RECOVERY)
    }

    private fun fixture(suffix: String = "roundtrip"): Fixture {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { temporaryFolder.newFile("ledger-$suffix.preferences_pb") }
        )
        return Fixture(
            dataStore = dataStore,
            ledger = DataStoreClinicalInputInvalidationLedger(dataStore),
            close = scope::cancel
        )
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        ledger: ClinicalInputInvalidationLedger,
        failures: MutableList<Throwable>
    ) = ClinicalInputInvalidationCoordinator(
        scope = this,
        ledger = ledger,
        failureReporter = { failures += it },
        nowMs = { testScheduler.currentTime },
        modeSource = { ClinicalInputInvalidationMode.NORMAL },
        sink = { true }
    )

    private data class Fixture(
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        val ledger: DataStoreClinicalInputInvalidationLedger,
        val close: () -> Unit
    )

    private class ThrowingLedger(
        private val readFailure: Throwable? = null,
        private val writeFailure: Throwable? = null
    ) : ClinicalInputInvalidationLedger {
        override suspend fun read(): ClinicalInputInvalidationLedgerState {
            readFailure?.let { throw it }
            return ClinicalInputInvalidationLedgerState()
        }

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            writeFailure?.let { throw it }
        }
    }
}
