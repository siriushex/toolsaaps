package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DeliveryTelemetryDaoRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun denseThirtyDayDeliveryHistoryRetainsLookbackBoundaryAndWindowRecovery() = runBlocking {
        val minute = 60_000L
        val day = 24L * 60L * minute
        val fromTs = 10L * day
        val throughTs = fromTs + 30L * day
        val suspectTs = deliveryDiagnosticLookbackFrom(fromTs)
        val recoveryTs = fromTs + 10L * minute
        val crossingSuspectTs = fromTs - 2L * 60L * minute
        val denseStepMs = (throughTs - fromTs) / 2_200L
        val denseRows = (0 until 2_200).map { index ->
            telemetry(
                id = "dense-$index",
                timestamp = fromTs + index.toLong() * denseStepMs,
                source = "dense-source-$index",
                state = DeliveryTrustState.NORMAL
            )
        }
        db.telemetryDao().upsertAll(
            denseRows + listOf(
                telemetry("boundary", suspectTs, "boundary-source", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                telemetry("crossing", crossingSuspectTs, "crossing-source", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                telemetry("recovery", recoveryTs, "crossing-source", DeliveryTrustState.NORMAL),
                TelemetrySampleEntity(
                    id = "mixed-activity",
                    timestamp = recoveryTs,
                    source = "local_sensor",
                    key = "activity_ratio",
                    valueDouble = 1.8,
                    valueText = null,
                    unit = "ratio",
                    quality = "OK"
                )
            )
        )

        val rows = db.telemetryDao().observeDeliveryTrustInWindow(
            stableKey = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
            legacyKey = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
            legacySource = DeliveryTrustStateWireCodec.LEGACY_SOURCE,
            fromTs = suspectTs,
            throughTs = throughTs
        ).first()
        val events = deliveryDiagnosticEventsForWindow(
            rows = rows.map { row ->
                DeliveryTrustTelemetryValue(row.timestamp, row.source, row.key, row.valueDouble)
            },
            fromTs = fromTs,
            throughTs = throughTs
        )

        assertThat(rows).hasSize(2_203)
        assertThat(rows.map { it.id }).doesNotContain("mixed-activity")
        assertThat(rows.map { row -> listOf(row.timestamp.toString(), row.source, row.key, row.id) })
            .containsExactlyElementsIn(
                rows.sortedWith(
                    compareBy<TelemetrySampleEntity> { it.timestamp }
                        .thenBy { it.source }
                        .thenBy { it.key }
                        .thenBy { it.id }
                ).map { row -> listOf(row.timestamp.toString(), row.source, row.key, row.id) }
            )
            .inOrder()
        assertThat(events.map { it.localId })
            .contains(stableDeliveryEventLocalId("boundary-source", suspectTs))
        assertThat(events.map { it.localId })
            .contains(stableDeliveryEventLocalId("crossing-source", crossingSuspectTs))
        val crossing = events.single {
            it.localId == stableDeliveryEventLocalId("crossing-source", crossingSuspectTs)
        }
        assertThat(crossing.endTs).isEqualTo(recoveryTs)
    }

    @Test
    fun alertProjectionPreservesForeignLegacySourceInsteadOfFabricatingTrustedSource() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "foreign-legacy",
                    timestamp = 2_000L,
                    source = "target_manager_off",
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    valueDouble = 2.0,
                    valueText = null,
                    unit = null,
                    quality = "OK"
                )
            )
        )

        val rows = db.telemetryDao().latestBySourceAndKeySince(0L)
        val selected = AutomationRepository.selectAlertDeliveryTrustTelemetryStatic(rows)

        assertThat(selected).containsExactly(
            DeliveryTrustTelemetryValue(
                timestamp = 2_000L,
                source = "target_manager_off",
                key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                value = 2.0
            )
        )
        assertThat(AutomationRepository.decodeAlertDeliveryTrustStatic(selected)).isNull()
        Unit
    }

    @Test
    fun acceptedAlertProjectionRetainsSameTimestampStableConflictBeforeGeneralDeduplication() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "stable-watch",
                    timestamp = 3_000L,
                    source = AutomationRepository.TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    valueDouble = DeliveryTrustStateWireCodec.encode(DeliveryTrustState.WATCH),
                    valueText = null,
                    unit = "code",
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "stable-suspect",
                    timestamp = 3_000L,
                    source = AutomationRepository.TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    valueDouble = DeliveryTrustStateWireCodec.encode(
                        DeliveryTrustState.SUSPECTED_NONRESPONSE
                    ),
                    valueText = null,
                    unit = "code",
                    quality = "OK"
                )
            )
        )

        val baseRows = db.telemetryDao().latestBySourceAndKeySince(0L)
        val selected = AutomationRepository.selectAcceptedAlertDeliveryTrustTelemetryStatic(
            baseRows = baseRows,
            reportRows = emptyList()
        )

        assertThat(selected).hasSize(2)
        assertThat(AutomationRepository.decodeAlertDeliveryTrustStatic(selected)).isNull()
        Unit
    }

    @Test
    fun successfulEvaluationPersistsOneStableRowAndRestartProjectionUsesItsProvenance() = runBlocking {
        var evaluations = 0

        val first = requireNotNull(
            AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = 5_000L,
                state = DeliveryTrustState.WATCH,
                evaluate = {
                    evaluations++
                    "accepted"
                },
                persist = { row -> db.telemetryDao().upsertAll(listOf(row)) }
            )
        )
        val retry = requireNotNull(
            AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = 5_000L,
                state = DeliveryTrustState.WATCH,
                evaluate = {
                    evaluations++
                    "accepted-retry"
                },
                persist = { row -> db.telemetryDao().upsertAll(listOf(row)) }
            )
        )

        val rows = db.telemetryDao().sinceByKeys(
            since = 0L,
            keys = listOf(DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY)
        )
        val row = rows.single()
        val restartedProjection = AutomationRepository.selectAlertDeliveryTrustTelemetryStatic(
            db.telemetryDao().latestBySourceAndKeySince(0L)
        )

        assertThat(first.evaluation).isEqualTo("accepted")
        assertThat(first.telemetryPersisted).isTrue()
        assertThat(first.persistenceFailureType).isNull()
        assertThat(retry.evaluation).isEqualTo("accepted-retry")
        assertThat(evaluations).isEqualTo(2)
        assertThat(row.timestamp).isEqualTo(5_000L)
        assertThat(row.source).isEqualTo("copilot_target_manager")
        assertThat(row.key).isEqualTo(DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY)
        assertThat(row.valueDouble).isFinite()
        assertThat(DeliveryTrustStateWireCodec.decodeTelemetry(row.key, row.source, row.valueDouble))
            .isEqualTo(DeliveryTrustState.WATCH)
        assertThat(AutomationRepository.decodeAlertDeliveryTrustStatic(restartedProjection))
            .isEqualTo(DeliveryTrustState.WATCH)
    }

    @Test
    fun foreignAndLegacyRowsCannotOverwritePersistedTargetManagerTrust() = runBlocking {
        requireNotNull(
            AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = 6_000L,
                state = DeliveryTrustState.NORMAL,
                evaluate = { "accepted" },
                persist = { row -> db.telemetryDao().upsertAll(listOf(row)) }
            )
        )
        val persisted = db.telemetryDao().sinceByKeys(
            since = 0L,
            keys = listOf(DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY)
        ).single()
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "trusted-legacy",
                    timestamp = 7_000L,
                    source = DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    valueDouble = 2.0,
                    valueText = null,
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "foreign-legacy",
                    timestamp = 8_000L,
                    source = "foreign",
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    valueDouble = 1.0,
                    valueText = null,
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "foreign-stable",
                    timestamp = 9_000L,
                    source = "foreign",
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    valueDouble = DeliveryTrustStateWireCodec.encode(
                        DeliveryTrustState.SUSPECTED_NONRESPONSE
                    ),
                    valueText = null,
                    unit = "code",
                    quality = "OK"
                )
            )
        )

        val reloaded = db.telemetryDao().byId(persisted.id)
        val selected = AutomationRepository.selectAlertDeliveryTrustTelemetryStatic(
            db.telemetryDao().latestBySourceAndKeySince(0L)
        )

        assertThat(reloaded).isEqualTo(persisted)
        assertThat(selected.filter { it.key == DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY })
            .containsExactly(
                DeliveryTrustTelemetryValue(
                    timestamp = 6_000L,
                    source = "copilot_target_manager",
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    value = DeliveryTrustStateWireCodec.encode(DeliveryTrustState.NORMAL)
                )
            )
        assertThat(AutomationRepository.decodeAlertDeliveryTrustStatic(selected))
            .isEqualTo(DeliveryTrustState.NORMAL)
    }

    @Test
    fun incompleteOrFailedEvaluationCreatesNoStableRow() = runBlocking {
        var persistenceCalls = 0
        val incomplete = AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic<String>(
            nowTs = 9_000L,
            state = DeliveryTrustState.UNKNOWN,
            evaluate = { null },
            persist = {
                persistenceCalls++
                db.telemetryDao().upsertAll(listOf(it))
            }
        )
        val failure = IllegalStateException("evaluation failed")
        val caught = try {
            AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = 10_000L,
                state = DeliveryTrustState.UNKNOWN,
                evaluate = { throw failure },
                persist = {
                    persistenceCalls++
                    db.telemetryDao().upsertAll(listOf(it))
                }
            )
            null
        } catch (error: IllegalStateException) {
            error
        }

        val rows = db.telemetryDao().sinceByKeys(
            since = 0L,
            keys = listOf(DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY)
        )
        assertThat(incomplete).isNull()
        assertThat(caught).isSameInstanceAs(failure)
        assertThat(persistenceCalls).isEqualTo(0)
        assertThat(rows).isEmpty()
    }

    @Test
    fun postEvaluationPersistencePreservesCancellationAndErrorSemantics() = runBlocking {
        suspend fun caught(error: Throwable, duringPersistence: Boolean): Throwable = try {
            AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = 11_000L,
                state = DeliveryTrustState.WATCH,
                evaluate = {
                    if (!duringPersistence) throw error
                    "accepted"
                },
                persist = {
                    if (duringPersistence) throw error
                }
            )
            AssertionError("expected failure to escape")
        } catch (caught: Throwable) {
            caught
        }

        listOf(
            CancellationException("cancelled"),
            TestVirtualMachineError(),
            ThreadDeath()
        ).forEach { failure ->
            assertThat(caught(failure, duringPersistence = false)).isSameInstanceAs(failure)
            assertThat(caught(failure, duringPersistence = true)).isSameInstanceAs(failure)
        }
    }

    @Test
    fun ordinaryPersistenceFailureReportsOneAccurateAuditOutcome() = runBlocking {
        val result = requireNotNull(
            AutomationRepository.evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = 12_000L,
                state = DeliveryTrustState.WATCH,
                evaluate = { "accepted" },
                persist = { throw IllegalStateException("private storage detail") }
            )
        )
        val audits = mutableListOf<Pair<String, Map<String, Any?>>>()

        AutomationRepository.reportTargetManagerDeliveryTrustPersistenceFailureStatic(
            nowTs = 12_000L,
            failureType = result.persistenceFailureType,
            warn = { message, metadata -> audits += message to metadata }
        )
        AutomationRepository.reportTargetManagerDeliveryTrustPersistenceFailureStatic(
            nowTs = 12_000L,
            failureType = null,
            warn = { message, metadata -> audits += message to metadata }
        )

        assertThat(audits).containsExactly(
            "target_manager_delivery_trust_persistence_failed" to mapOf(
                "timestamp" to 12_000L,
                "source" to "copilot_target_manager",
                "key" to DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                "failureType" to "IllegalStateException"
            )
        )
        assertThat(audits.single().second.toString()).doesNotContain("private storage detail")
    }

    @Test
    fun postEvaluationAuditPreservesCancellationAndEveryError() = runBlocking {
        suspend fun caught(failure: Throwable, duringWarning: Boolean): Throwable = try {
            AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                nowTs = 13_000L,
                state = DeliveryTrustState.WATCH,
                evaluate = { "accepted" },
                persist = {
                    if (duringWarning) throw IllegalStateException("telemetry storage unavailable")
                },
                warn = { _, _ ->
                    if (duringWarning) throw failure
                },
                reportDecision = {
                    if (!duringWarning) throw failure
                }
            )
            IllegalStateException("expected audit failure to escape")
        } catch (caught: Throwable) {
            caught
        }

        listOf(
            CancellationException("cancelled"),
            AssertionError("assertion"),
            LinkageError("linkage"),
            TestVirtualMachineError(),
            ThreadDeath()
        ).forEach { failure ->
            assertThat(caught(failure, duringWarning = true)).isSameInstanceAs(failure)
            assertThat(caught(failure, duringWarning = false)).isSameInstanceAs(failure)
        }
    }

    private fun telemetry(
        id: String,
        timestamp: Long,
        source: String,
        state: DeliveryTrustState
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
        valueDouble = DeliveryTrustStateWireCodec.encode(state),
        valueText = null,
        unit = "code",
        quality = "OK"
    )

    private class TestVirtualMachineError : VirtualMachineError()
}
