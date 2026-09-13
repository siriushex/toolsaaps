package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.data.local.dao.ContextEventSyncDao
import io.aaps.copilot.data.local.dao.PhysioContextTagDao
import io.aaps.copilot.data.local.entity.ContextEventSyncEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.FemaleCyclePhase
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.scheduler.ClinicalInputInvalidationSource
import io.aaps.copilot.service.reconcileContextEventsAndInvalidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextEventSyncCoordinatorTest {
    private val gson = Gson()

    @Test
    fun `durable lifecycle mutations invalidate once while no-op and failure do not`() = runTest {
        val fixture = Fixture()
        val original = event()

        assertThat(fixture.save(original, expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.durableMutations).isEqualTo(1)

        assertThat(fixture.save(original, expectedRevision = fixture.tags.require(original.localId).revision))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.durableMutations).isEqualTo(1)

        fixture.gateway.results += ContextEventGatewayResult.Failed("offline")
        assertThat(
            fixture.save(
                original.copy(note = "changed"),
                expectedRevision = fixture.tags.require(original.localId).revision
            )
        ).isEqualTo(ContextEventCommandResult.FAILED)
        assertThat(fixture.durableMutations).isEqualTo(1)
    }

    @Test
    fun `changed update close delete and close all notify once per durable mutation`() = runTest {
        val update = Fixture()
        update.save(event(title = "original"), expectedRevision = null)
        update.durableMutations = 0
        assertThat(update.save(event(title = "updated"), expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(update.durableMutations).isEqualTo(1)

        val close = Fixture(now = 3_000L)
        close.save(event(startTs = 1_000L, endTs = 8_000L), expectedRevision = null)
        close.durableMutations = 0
        assertThat(close.close("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(close.durableMutations).isEqualTo(1)

        val delete = Fixture()
        delete.save(event(), expectedRevision = null)
        delete.durableMutations = 0
        assertThat(delete.delete("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(delete.durableMutations).isEqualTo(1)

        val closeAll = Fixture(now = 3_000L)
        closeAll.save(event(localId = "event-a"), expectedRevision = null)
        closeAll.save(event(localId = "event-b"), expectedRevision = null)
        closeAll.durableMutations = 0
        assertThat(closeAll.coordinator.closeAllActive()).containsExactly(
            ContextEventCommandResult.ACKNOWLEDGED,
            ContextEventCommandResult.ACKNOWLEDGED
        )
        assertThat(closeAll.durableMutations).isEqualTo(2)
    }

    @Test
    fun `no-op gateway database and cancellation paths do not notify`() = runTest {
        val noOp = Fixture()
        val original = event()
        noOp.save(original, expectedRevision = null)
        noOp.durableMutations = 0
        assertThat(noOp.save(original, expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(noOp.close("missing", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.NOT_FOUND)
        assertThat(noOp.delete("missing", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.NOT_FOUND)
        assertThat(noOp.durableMutations).isEqualTo(0)

        val gatewayFailure = Fixture()
        gatewayFailure.save(event(), expectedRevision = null)
        gatewayFailure.durableMutations = 0
        gatewayFailure.gateway.results += ContextEventGatewayResult.Failed("offline")
        assertThat(gatewayFailure.save(event(title = "changed"), expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.FAILED)
        assertThat(gatewayFailure.durableMutations).isEqualTo(0)

        val databaseFailure = Fixture()
        databaseFailure.sync.failNextUpdate = true
        assertThat(databaseFailure.save(event(), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(databaseFailure.durableMutations).isEqualTo(0)

        val cancelled = Fixture()
        cancelled.save(event(), expectedRevision = null)
        cancelled.durableMutations = 0
        cancelled.gateway.throwOnSend = CancellationException("stop")
        val cancellation = runCatching {
            cancelled.close("event-1", expectedRevision = 1L)
        }.exceptionOrNull()
        assertThat(cancellation).isInstanceOf(CancellationException::class.java)
        assertThat(cancelled.durableMutations).isEqualTo(0)
    }

    @Test
    fun `actual startup and nightscout reconciliation invalidate only after applied mutation`() = runTest {
        val invalidated = mutableListOf<ClinicalInputInvalidationSource>()
        val startup = Fixture()
        startup.gateway.results += ContextEventGatewayResult.Pending("timeout")
        assertThat(startup.save(event(), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.PENDING)
        startup.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        val startupSummary = reconcileContextEventsAndInvalidate(
            startup = true,
            reconcile = { startup.newCoordinator().reconcileStartupPending() },
            invalidate = { source -> invalidated += source }
        )

        assertThat(startupSummary.appliedCount).isEqualTo(1)
        assertThat(invalidated).containsExactly(
            ClinicalInputInvalidationSource.STARTUP_RECONCILIATION
        )

        val nightscout = Fixture()
        nightscout.gateway.results += ContextEventGatewayResult.Pending("timeout")
        assertThat(nightscout.save(event(), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.PENDING)
        nightscout.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        val nightscoutSummary = reconcileContextEventsAndInvalidate(
            startup = false,
            reconcile = { nightscout.newCoordinator().reconcileAllPending() },
            invalidate = { source -> invalidated += source }
        )

        assertThat(nightscoutSummary.appliedCount).isEqualTo(1)
        assertThat(invalidated).containsExactly(
            ClinicalInputInvalidationSource.STARTUP_RECONCILIATION,
            ClinicalInputInvalidationSource.NIGHTSCOUT_RECONCILIATION
        ).inOrder()

        val emptySummary = reconcileContextEventsAndInvalidate(
            startup = true,
            reconcile = { Fixture().newCoordinator().reconcileStartupPending() },
            invalidate = { source -> invalidated += source }
        )
        assertThat(emptySummary.appliedCount).isEqualTo(0)
        assertThat(invalidated).hasSize(2)
    }

    @Test
    fun `create and update preserve every event field with monotonic metadata`() = runTest {
        val fixture = Fixture(now = 10_000L)
        val created = event(
            subtype = "acute",
            severity = EventSeverity.HIGH,
            title = "Stress title",
            attributes = linkedMapOf("confidence" to "high", "trigger" to "work"),
            note = "first note"
        )

        fixture.save(created)

        val first = fixture.tags.require("event-1")
        assertThat(first.tsStart).isEqualTo(created.startTs)
        assertThat(first.tsEnd).isEqualTo(created.endTs)
        assertThat(first.tagType).isEqualTo("STRESS")
        assertThat(first.subtype).isEqualTo("acute")
        assertThat(first.severity).isEqualTo(0.9)
        assertThat(first.source).isEqualTo("USER")
        assertThat(first.title).isEqualTo("Stress title")
        assertThat(first.note).isEqualTo("first note")
        assertThat(decode(first.attributesJson)).containsExactlyEntriesIn(created.attributes)
        assertThat(first.revision).isEqualTo(1L)
        assertThat(first.updatedAt).isEqualTo(10_000L)
        assertThat(first.status).isEqualTo("ACTIVE")
        assertThat(fixture.gateway.sent.single().operation)
            .isEqualTo(AapsContextEventGateway.Operation.CREATE)

        fixture.gateway.sent.clear()
        val updated = created.copy(
            startTs = 2_000L,
            endTs = 8_000L,
            subtype = "sustained",
            severity = EventSeverity.LOW,
            title = "Updated title",
            attributes = linkedMapOf("confidence" to "medium", "escaped" to "a\"b"),
            note = "updated note",
            status = CompensationEventStatus.CLOSED,
            revision = 99L
        )

        fixture.save(updated)

        val second = fixture.tags.require("event-1")
        assertThat(second.tsStart).isEqualTo(2_000L)
        assertThat(second.tsEnd).isEqualTo(8_000L)
        assertThat(second.subtype).isEqualTo("sustained")
        assertThat(second.severity).isEqualTo(0.25)
        assertThat(second.title).isEqualTo("Updated title")
        assertThat(second.note).isEqualTo("updated note")
        assertThat(decode(second.attributesJson)).containsExactlyEntriesIn(updated.attributes)
        assertThat(second.revision).isEqualTo(2L)
        assertThat(second.updatedAt).isEqualTo(10_001L)
        assertThat(second.status).isEqualTo("CLOSED")
        assertThat(fixture.gateway.sent.single().operation)
            .isEqualTo(AapsContextEventGateway.Operation.UPDATE)
        assertThat(fixture.gateway.sent.single().event.revision).isEqualTo(2L)
        assertThat(fixture.sync.records.map { it.status }).containsExactly(
            ContextEventSyncCoordinator.STATUS_COMPLETED,
            ContextEventSyncCoordinator.STATUS_COMPLETED
        )
    }

    @Test
    fun `export create exposes only immutable pending before exact ack`() = runTest {
        val fixture = Fixture()
        fixture.gateway.beforeSend = {
            assertThat(fixture.transactions.inTransaction).isFalse()
            assertThat(fixture.tags.rows).doesNotContainKey("event-1")
            val pending = fixture.sync.records.single()
            assertThat(pending.status).isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
            assertThat(pending.requestHash).contains(".")
        }

        assertThat(fixture.save(event()))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)

        assertThat(fixture.tags.rows).containsKey("event-1")
        assertThat(fixture.transactions.commits).isEqualTo(2)
        assertThat(fixture.transactions.rollbacks).isEqualTo(0)
    }

    @Test
    fun `pending and failed create remain absent locally`() = runTest {
        listOf(
            ContextEventGatewayResult.Pending("ack_timeout") to ContextEventCommandResult.PENDING,
            ContextEventGatewayResult.Failed("bridge_rejected") to ContextEventCommandResult.FAILED
        ).forEach { (gatewayResult, expected) ->
            val fixture = Fixture()
            fixture.gateway.results += gatewayResult

            assertThat(fixture.save(event())).isEqualTo(expected)

            assertThat(fixture.tags.rows).doesNotContainKey("event-1")
            assertThat(fixture.sync.records.single().status).isEqualTo(
                if (expected == ContextEventCommandResult.PENDING) {
                    ContextEventSyncCoordinator.STATUS_PENDING
                } else {
                    ContextEventSyncCoordinator.STATUS_FAILED
                }
            )
        }
    }

    @Test
    fun `pending and failed update keep prior row byte for byte`() = runTest {
        listOf(
            ContextEventGatewayResult.Pending("ack_timeout") to ContextEventCommandResult.PENDING,
            ContextEventGatewayResult.Failed("bridge_rejected") to ContextEventCommandResult.FAILED
        ).forEach { (gatewayResult, expected) ->
            val fixture = Fixture()
            assertThat(fixture.save(event(title = "Original")))
                .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
            val prior = fixture.tags.require("event-1")
            fixture.gateway.results += gatewayResult
            fixture.gateway.beforeSend = {
                assertThat(fixture.tags.require("event-1")).isEqualTo(prior)
            }

            assertThat(fixture.save(event(title = "Candidate"))).isEqualTo(expected)

            assertThat(fixture.tags.require("event-1")).isEqualTo(prior)
        }
    }

    @Test
    fun `pending create reconciliation applies immutable candidate only after eventual ack`() = runTest {
        val fixture = Fixture()
        val original = event(title = "Immutable original", note = "original note")
        fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")

        assertThat(fixture.save(original)).isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        val firstCommand = fixture.gateway.sent.single()
        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate_ack")

        val reconciliation = fixture.newCoordinator().reconcileAllPending()

        assertThat(fixture.gateway.sent).hasSize(2)
        assertThat(fixture.gateway.sent.last()).isEqualTo(firstCommand)
        assertThat(fixture.tags.require("event-1").title).isEqualTo("Immutable original")
        assertThat(fixture.tags.require("event-1").note).isEqualTo("original note")
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(1L)
        assertThat(reconciliation.appliedCount).isEqualTo(1)
    }

    @Test
    fun `pending update reconciliation keeps prior row then applies immutable candidate after ack`() = runTest {
        val fixture = Fixture()
        assertThat(fixture.save(event(title = "Committed")))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        val prior = fixture.tags.require("event-1")
        val candidate = event(title = "Pending update", note = "immutable update")
        fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")

        assertThat(fixture.save(candidate)).isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(fixture.tags.require("event-1")).isEqualTo(prior)
        val updateCommand = fixture.gateway.sent.last()
        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate_ack")

        val reconciliation = fixture.newCoordinator().reconcileAllPending()

        assertThat(fixture.gateway.sent.last()).isEqualTo(updateCommand)
        assertThat(fixture.tags.require("event-1").title).isEqualTo("Pending update")
        assertThat(fixture.tags.require("event-1").note).isEqualTo("immutable update")
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(2L)
        assertThat(reconciliation.appliedCount).isEqualTo(1)
    }

    @Test
    fun `older save acknowledgement cannot overwrite newer local revision`() = runTest {
        val fixture = Fixture()
        fixture.gateway.beforeSend = {
            fixture.tags.rows["event-1"] = PhysioContextTagEntity(
                id = "event-1",
                tsStart = 1_000L,
                tsEnd = 5_000L,
                tagType = CompensationEventType.STRESS.name,
                severity = 0.5,
                source = EventSource.USER.name,
                note = "newer",
                title = "Newer local",
                revision = 5L,
                status = CompensationEventStatus.ACTIVE.name,
                updatedAt = 50_000L
            )
        }

        assertThat(fixture.save(event(title = "Older command")))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)

        assertThat(fixture.tags.require("event-1").revision).isEqualTo(5L)
        assertThat(fixture.tags.require("event-1").title).isEqualTo("Newer local")
    }

    @Test
    fun `older close and delete acknowledgements cannot mutate newer local revision`() = runTest {
        listOf(
            AapsContextEventGateway.Operation.CLOSE,
            AapsContextEventGateway.Operation.DELETE
        ).forEach { operation ->
            val fixture = Fixture(now = 3_000L)
            fixture.save(event(startTs = 1_000L, endTs = 8_000L))
            fixture.gateway.beforeSend = {
                fixture.tags.rows["event-1"] = fixture.tags.require("event-1").copy(
                    title = "Newer local",
                    revision = 5L,
                    updatedAt = 50_000L
                )
            }

            val result = when (operation) {
                AapsContextEventGateway.Operation.CLOSE -> fixture.close("event-1", expectedRevision = 1L)
                AapsContextEventGateway.Operation.DELETE -> fixture.delete("event-1", expectedRevision = 1L)
                else -> error("Unsupported test operation")
            }

            assertThat(result).isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
            assertThat(fixture.tags.require("event-1").revision).isEqualTo(5L)
            assertThat(fixture.tags.require("event-1").title).isEqualTo("Newer local")
        }
    }

    @Test
    fun `audit insert failure rolls back export save before gateway`() = runTest {
        val fixture = Fixture()
        fixture.sync.failNextInsert = true

        val failure = runCatching { fixture.save(event()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SimulatedProcessDeath::class.java)
        assertThat(fixture.tags.rows).isEmpty()
        assertThat(fixture.sync.records).isEmpty()
        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.transactions.commits).isEqualTo(0)
        assertThat(fixture.transactions.rollbacks).isEqualTo(1)
    }

    @Test
    fun `audit insert failure rolls back close and keeps active row`() = runTest {
        val fixture = Fixture()
        assertThat(fixture.save(event()))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        val active = fixture.tags.require("event-1")
        fixture.gateway.sent.clear()
        fixture.sync.failNextInsert = true

        val failure = runCatching { fixture.close("event-1") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SimulatedProcessDeath::class.java)
        assertThat(fixture.tags.require("event-1")).isEqualTo(active)
        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.sync.records).hasSize(1)
    }

    @Test
    fun `delete keeps row through send and removes it with completed audit after exact ack`() = runTest {
        val fixture = Fixture()
        assertThat(fixture.save(event()))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        fixture.gateway.sent.clear()
        fixture.gateway.beforeSend = {
            assertThat(fixture.transactions.inTransaction).isFalse()
            assertThat(fixture.tags.rows).containsKey("event-1")
            val pendingDelete = fixture.sync.records.single { it.operation == "DELETE" }
            assertThat(pendingDelete.status).isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
        }

        assertThat(fixture.delete("event-1"))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)

        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        assertThat(fixture.sync.records.single { it.operation == "DELETE" }.status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_COMPLETED)
        assertThat(fixture.transactions.commits).isEqualTo(4)
    }

    @Test
    fun `quick physiology metadata preserves exact tag type and severity`() = runTest {
        val fixture = Fixture()

        fixture.save(
            event(
                type = CompensationEventType.MEDICATION_STEROID,
                severity = EventSeverity.MEDIUM,
                attributes = mapOf(
                    "physioTagType" to "steroids",
                    "physioSeverity" to "0.7"
                )
            )
        )

        assertThat(fixture.tags.require("event-1").tagType).isEqualTo("steroids")
        assertThat(fixture.tags.require("event-1").severity).isEqualTo(0.7)
        assertThat(fixture.gateway.sent.single().event.type)
            .isEqualTo(CompensationEventType.MEDICATION_STEROID)
    }

    @Test
    fun `close persists closed status bounded end and next revision before sending`() = runTest {
        val fixture = Fixture(now = 3_000L)
        fixture.save(event(startTs = 1_000L, endTs = 8_000L))
        fixture.gateway.sent.clear()
        fixture.now = 4_500L

        fixture.close("event-1")

        val closed = fixture.tags.require("event-1")
        assertThat(closed.status).isEqualTo("CLOSED")
        assertThat(closed.tsEnd).isEqualTo(4_500L)
        assertThat(closed.revision).isEqualTo(2L)
        assertThat(closed.updatedAt).isEqualTo(4_500L)
        assertThat(fixture.gateway.sent.single().operation)
            .isEqualTo(AapsContextEventGateway.Operation.CLOSE)
        assertThat(fixture.gateway.sent.single().event.status)
            .isEqualTo(CompensationEventStatus.CLOSED)
    }

    @Test
    fun `close dispatches before local lifecycle mutation and applies it only after ack`() = runTest {
        val fixture = Fixture(now = 3_000L)
        fixture.save(event(startTs = 1_000L, endTs = 8_000L))
        val active = fixture.tags.require("event-1")
        fixture.gateway.sent.clear()
        fixture.now = 4_500L
        fixture.gateway.beforeSend = {
            assertThat(fixture.tags.require("event-1")).isEqualTo(active)
            assertThat(fixture.tags.require("event-1").status).isEqualTo("ACTIVE")
        }

        assertThat(fixture.close("event-1"))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)

        assertThat(fixture.tags.require("event-1").status).isEqualTo("CLOSED")
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(2L)
    }

    @Test
    fun `close gateway rejection leaves historical local row unchanged`() = runTest {
        val fixture = Fixture(now = 3_000L)
        fixture.save(event(startTs = 1_000L, endTs = 8_000L))
        val active = fixture.tags.require("event-1")
        fixture.gateway.sent.clear()
        fixture.gateway.results += ContextEventGatewayResult.Failed("bridge_rejected")
        fixture.now = 4_500L

        assertThat(fixture.close("event-1"))
            .isEqualTo(ContextEventCommandResult.FAILED)

        assertThat(fixture.tags.require("event-1")).isEqualTo(active)
        assertThat(fixture.sync.latestForEvent("event-1")?.operation).isEqualTo("CLOSE")
        assertThat(fixture.sync.latestForEvent("event-1")?.status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_FAILED)
    }

    @Test
    fun `close all active persists and audits each event independently`() = runTest {
        val fixture = Fixture(now = 2_000L)
        fixture.save(event(localId = "event-1"))
        fixture.save(event(localId = "event-2"))
        fixture.gateway.sent.clear()
        fixture.sync.records.clear()
        fixture.now = 3_000L

        val results = fixture.coordinator.closeAllActive()

        assertThat(results).containsExactly(
            ContextEventCommandResult.ACKNOWLEDGED,
            ContextEventCommandResult.ACKNOWLEDGED
        )
        listOf("event-1", "event-2").forEach { eventId ->
            val closed = fixture.tags.require(eventId)
            assertThat(closed.status).isEqualTo(CompensationEventStatus.CLOSED.name)
            assertThat(closed.revision).isEqualTo(2L)
            assertThat(closed.updatedAt).isEqualTo(3_000L)
        }
        assertThat(fixture.gateway.sent.map { it.operation }).containsExactly(
            AapsContextEventGateway.Operation.CLOSE,
            AapsContextEventGateway.Operation.CLOSE
        )
        assertThat(fixture.sync.records.map { it.eventId }).containsExactly("event-1", "event-2")
        assertThat(fixture.sync.records.map { it.status }).containsExactly(
            ContextEventSyncCoordinator.STATUS_COMPLETED,
            ContextEventSyncCoordinator.STATUS_COMPLETED
        )
    }

    @Test
    fun `delete removes local row only after explicit acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.save(event())
        fixture.gateway.sent.clear()

        fixture.delete("event-1")

        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        assertThat(fixture.gateway.sent.single().operation)
            .isEqualTo(AapsContextEventGateway.Operation.DELETE)
        assertThat(fixture.gateway.sent.single().event.revision).isEqualTo(2L)
        val audit = fixture.sync.latestForEvent("event-1")!!
        assertThat(audit.operation).isEqualTo("DELETE")
        assertThat(audit.revision).isEqualTo(2L)
        assertThat(audit.status).isEqualTo(ContextEventSyncCoordinator.STATUS_COMPLETED)
        assertThat(audit.completedAt).isNotNull()
    }

    @Test
    fun `delete failure retains local row and persisted failed audit`() = runTest {
        val fixture = Fixture()
        fixture.save(event())
        fixture.gateway.sent.clear()
        fixture.gateway.results.add(ContextEventGatewayResult.Failed("bridge_rejected"))

        fixture.delete("event-1")

        assertThat(fixture.tags.rows).containsKey("event-1")
        val audit = fixture.sync.latestForEvent("event-1")!!
        assertThat(audit.operation).isEqualTo("DELETE")
        assertThat(audit.revision).isEqualTo(2L)
        assertThat(audit.status).isEqualTo(ContextEventSyncCoordinator.STATUS_FAILED)
        assertThat(audit.sanitizedError).isEqualTo("bridge_rejected")
    }

    @Test
    fun `pending delete retries after process death and removes row on acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.save(event())
        fixture.gateway.sent.clear()
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))

        fixture.delete("event-1")

        assertThat(fixture.tags.rows).containsKey("event-1")
        assertThat(fixture.gateway.sent).hasSize(1)
        val pending = fixture.sync.latestForEvent("event-1")!!
        assertThat(pending.status).isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
        assertThat(pending.completedAt).isNull()
        assertThat(pending.sanitizedError).isEqualTo("ack_timeout")

        fixture.now = 20_000L
        fixture.newCoordinator().reconcileAllPending()

        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        assertThat(fixture.gateway.sent).hasSize(2)
        assertThat(fixture.gateway.sent.map { it.event.revision }).containsExactly(2L, 2L)
        assertThat(fixture.gateway.sent.map { it.operation }).containsExactly(
            AapsContextEventGateway.Operation.DELETE,
            AapsContextEventGateway.Operation.DELETE
        )
        val completed = fixture.sync.latestForEvent("event-1")!!
        assertThat(completed.status).isEqualTo(ContextEventSyncCoordinator.STATUS_COMPLETED)
        assertThat(completed.attemptedAt).isGreaterThan(pending.attemptedAt)
        assertThat(completed.completedAt).isNotNull()
        assertThat(completed.sanitizedError).isNull()
    }

    @Test
    fun `changed save reconciles exact pending command before applying next revision`() = runTest {
        val fixture = Fixture()
        val original = event(
            subtype = "acute",
            severity = EventSeverity.HIGH,
            title = "Original stress",
            attributes = mapOf("factor" to "1.2"),
            note = "original note"
        )
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))

        assertThat(fixture.save(original)).isEqualTo(ContextEventCommandResult.PENDING)

        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        val pendingAudit = fixture.sync.records.single()
        fixture.now = 20_000L
        fixture.gateway.results.add(ContextEventGatewayResult.Acknowledged("old-command-ack"))
        fixture.gateway.results.add(ContextEventGatewayResult.Acknowledged("new-command-ack"))
        val changed = original.copy(
            startTs = 2_000L,
            endTs = 7_000L,
            subtype = "sustained",
            severity = EventSeverity.LOW,
            title = "Changed stress",
            attributes = mapOf("factor" to "0.8"),
            note = "changed note"
        )

        val restarted = fixture.newCoordinator()
        assertThat(restarted.reconcileAllPending().appliedCount).isEqualTo(1)
        assertThat(restarted.save(changed, expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)

        assertThat(fixture.gateway.sent).hasSize(3)
        assertThat(fixture.gateway.sent[1]).isEqualTo(fixture.gateway.sent[0])
        assertThat(fixture.gateway.sent[2].operation)
            .isEqualTo(AapsContextEventGateway.Operation.UPDATE)
        assertThat(fixture.gateway.sent[2].event.revision).isEqualTo(2L)
        assertThat(fixture.gateway.sent[2].event.title).isEqualTo("Changed stress")
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(2L)
        assertThat(fixture.sync.records.map { it.status }).containsExactly(
            ContextEventSyncCoordinator.STATUS_COMPLETED,
            ContextEventSyncCoordinator.STATUS_COMPLETED
        )
        assertThat(fixture.sync.records.first().requestHash).isEqualTo(pendingAudit.requestHash)
    }

    @Test
    fun `pending reconciliation timeout leaves changed candidate unapplied`() = runTest {
        val fixture = Fixture()
        val original = event(title = "Original stress", note = "original note")
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))
        fixture.save(original)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        val pendingAudit = fixture.sync.records.single()
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))

        val result = fixture.newCoordinator().save(
            original.copy(title = "Changed stress", note = "changed note"),
            expectedRevision = null
        )

        assertThat(result).isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(fixture.gateway.sent).hasSize(1)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        assertThat(fixture.sync.records).hasSize(1)
        assertThat(fixture.sync.records.single().requestHash).isEqualTo(pendingAudit.requestHash)
        assertThat(fixture.sync.records.single().status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
    }

    @Test
    fun `close does not replay pending create through a UI command`() = runTest {
        val fixture = Fixture()
        val original = event(title = "Original stress", note = "original note")
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))
        assertThat(fixture.save(original)).isEqualTo(ContextEventCommandResult.PENDING)
        val firstCommand = fixture.gateway.sent.single()
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))

        val result = fixture.newCoordinator().close("event-1", expectedRevision = 1L)

        assertThat(result).isEqualTo(ContextEventCommandResult.NOT_FOUND)
        assertThat(fixture.gateway.sent).containsExactly(firstCommand)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        assertThat(fixture.sync.records).hasSize(1)
        assertThat(fixture.sync.records.single().revision).isEqualTo(1L)
    }

    @Test
    fun `delete does not replay pending create through a UI command`() = runTest {
        val fixture = Fixture()
        val original = event(title = "Original stress", note = "original note")
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))
        assertThat(fixture.save(original)).isEqualTo(ContextEventCommandResult.PENDING)
        val firstCommand = fixture.gateway.sent.single()
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("ack_timeout"))

        val result = fixture.newCoordinator().delete("event-1", expectedRevision = 1L)

        assertThat(result).isEqualTo(ContextEventCommandResult.NOT_FOUND)
        assertThat(fixture.gateway.sent).containsExactly(firstCommand)
        assertThat(fixture.sync.records).hasSize(1)
        assertThat(fixture.sync.records.single().revision).isEqualTo(1L)
    }

    @Test
    fun `legacy multiple pending commands reconcile oldest revision first`() = runTest {
        val fixture = Fixture()
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("create_timeout"))
        fixture.save(event(title = "Original"))
        val firstCommand = fixture.gateway.sent.single()

        val laterFixture = Fixture()
        laterFixture.save(event(title = "Original"))
        laterFixture.gateway.results.add(ContextEventGatewayResult.Pending("close_timeout"))
        laterFixture.close("event-1")
        fixture.sync.records += laterFixture.sync.records.single { it.revision == 2L }
        fixture.gateway.results.add(ContextEventGatewayResult.Pending("create_timeout"))

        val result = fixture.newCoordinator().reconcileAllPending(limit = 1)

        assertThat(result.stillPendingCount).isEqualTo(1)
        assertThat(fixture.gateway.sent.last()).isEqualTo(firstCommand)
        assertThat(fixture.sync.records.filter { it.status == ContextEventSyncCoordinator.STATUS_PENDING })
            .hasSize(2)
    }

    @Test
    fun `pending create blocks every later operation until exact ack and local apply`() = runTest {
        listOf(
            AapsContextEventGateway.Operation.UPDATE,
            AapsContextEventGateway.Operation.CLOSE,
            AapsContextEventGateway.Operation.DELETE
        ).forEach { laterOperation ->
            val fixture = Fixture()
            fixture.gateway.results += ContextEventGatewayResult.Pending("create_timeout")
            assertThat(fixture.save(event(title = "rev1"), expectedRevision = null))
                .isEqualTo(ContextEventCommandResult.PENDING)

            val later = Fixture()
            assertThat(later.save(event(title = "rev1"), expectedRevision = null))
                .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
            later.gateway.results += ContextEventGatewayResult.Pending("later_timeout")
            when (laterOperation) {
                AapsContextEventGateway.Operation.UPDATE -> assertThat(
                    later.save(event(title = "rev2"), expectedRevision = 1L)
                ).isEqualTo(ContextEventCommandResult.PENDING)
                AapsContextEventGateway.Operation.CLOSE -> assertThat(
                    later.close("event-1", expectedRevision = 1L)
                ).isEqualTo(ContextEventCommandResult.PENDING)
                AapsContextEventGateway.Operation.DELETE -> assertThat(
                    later.delete("event-1", expectedRevision = 1L)
                ).isEqualTo(ContextEventCommandResult.PENDING)
                AapsContextEventGateway.Operation.CREATE -> error("not a later operation")
            }
            fixture.sync.records += later.sync.records.single { it.revision == 2L }
            fixture.gateway.sent.clear()

            fixture.gateway.results += ContextEventGatewayResult.Pending("create_timeout")
            val pendingPass = fixture.newCoordinator().reconcileAllPending(limit = 8)

            assertThat(fixture.gateway.sent.map { it.operation })
                .containsExactly(AapsContextEventGateway.Operation.CREATE)
            assertThat(pendingPass.stillPendingCount).isEqualTo(1)
            assertThat(fixture.tags.rows).doesNotContainKey("event-1")

            fixture.gateway.results += ContextEventGatewayResult.Acknowledged("create_ack")
            val createAckPass = fixture.newCoordinator().reconcileAllPending(limit = 8)

            assertThat(fixture.gateway.sent.map { it.operation }).containsExactly(
                AapsContextEventGateway.Operation.CREATE,
                AapsContextEventGateway.Operation.CREATE
            ).inOrder()
            assertThat(createAckPass.appliedCount).isEqualTo(1)
            assertThat(fixture.tags.require("event-1").revision).isEqualTo(1L)

            fixture.gateway.results += ContextEventGatewayResult.Acknowledged("later_ack")
            fixture.newCoordinator().reconcileAllPending(limit = 8)

            assertThat(fixture.gateway.sent.map { it.operation }).containsExactly(
                AapsContextEventGateway.Operation.CREATE,
                AapsContextEventGateway.Operation.CREATE,
                laterOperation
            ).inOrder()
        }
    }

    @Test
    fun `failed command allows only exact rearm and blocks changed save`() = runTest {
        val fixture = Fixture()
        val original = event(title = "Original stress")
        fixture.gateway.results.add(ContextEventGatewayResult.Failed("bridge_rejected"))

        assertThat(fixture.save(original)).isEqualTo(ContextEventCommandResult.FAILED)

        val failedAudit = fixture.sync.records.single()
        fixture.now = 20_000L
        fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
        assertThat(fixture.newCoordinator().save(original, expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(fixture.gateway.sent).hasSize(2)
        assertThat(fixture.sync.records).hasSize(1)
        assertThat(fixture.sync.records.single().syncId).isEqualTo(failedAudit.syncId)
        assertThat(fixture.sync.records.single().status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")

        assertThat(
            fixture.newCoordinator().save(
                original.copy(title = "Changed stress"),
                expectedRevision = null
            )
        )
            .isEqualTo(ContextEventCommandResult.PENDING)

        assertThat(fixture.gateway.sent).hasSize(2)
        assertThat(fixture.sync.records).hasSize(1)

        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")
        val recovered = fixture.newCoordinator().reconcileAllPending(limit = 8)

        assertThat(recovered.appliedCount).isEqualTo(1)
        assertThat(fixture.tags.require("event-1").title).isEqualTo("Original stress")
        assertThat(fixture.sync.records.single().status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_COMPLETED)
    }

    @Test
    fun `failed predecessor blocks changed save close and delete without new audit`() = runTest {
        val fixture = Fixture()
        assertThat(fixture.save(event(title = "rev1"), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        fixture.gateway.results += ContextEventGatewayResult.Failed("bridge_rejected")
        assertThat(fixture.save(event(title = "rev2"), expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.FAILED)
        val auditCount = fixture.sync.records.size
        fixture.gateway.sent.clear()

        assertThat(fixture.save(event(title = "changed"), expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.CONFLICT)
        assertThat(fixture.close("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.CONFLICT)
        assertThat(fixture.delete("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.CONFLICT)

        assertThat(fixture.sync.records).hasSize(auditCount)
        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.tags.require("event-1").title).isEqualTo("rev1")
    }

    @Test
    fun `failed close and delete are rearmed from the exact immutable audit`() = runTest {
        val closeFixture = Fixture(now = 3_000L)
        closeFixture.save(event(startTs = 1_000L, endTs = 8_000L), expectedRevision = null)
        closeFixture.gateway.results += ContextEventGatewayResult.Failed("bridge_rejected")
        assertThat(closeFixture.close("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.FAILED)
        val closeAuditCount = closeFixture.sync.records.size
        closeFixture.now = 4_000L
        closeFixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        assertThat(closeFixture.close("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(closeFixture.sync.records).hasSize(closeAuditCount)
        assertThat(closeFixture.tags.require("event-1").status).isEqualTo("CLOSED")
        assertThat(closeFixture.tags.require("event-1").tsEnd).isEqualTo(3_000L)

        val deleteFixture = Fixture(now = 3_000L)
        deleteFixture.save(event(), expectedRevision = null)
        deleteFixture.gateway.results += ContextEventGatewayResult.Failed("bridge_rejected")
        assertThat(deleteFixture.delete("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.FAILED)
        val deleteAuditCount = deleteFixture.sync.records.size
        deleteFixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        assertThat(deleteFixture.delete("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(deleteFixture.sync.records).hasSize(deleteAuditCount)
        assertThat(deleteFixture.tags.rows).doesNotContainKey("event-1")
    }

    @Test
    fun `request hash binds every outbound event value`() = runTest {
        suspend fun hashFor(candidate: CompensationEvent): String {
            val fixture = Fixture()
            fixture.save(candidate)
            return fixture.sync.records.single().requestHash
        }

        val base = event()
        val hashes = listOf(
            hashFor(base),
            hashFor(base.copy(type = CompensationEventType.ILLNESS)),
            hashFor(base.copy(startTs = base.startTs + 1L)),
            hashFor(base.copy(endTs = base.endTs + 1L)),
            hashFor(base.copy(severity = EventSeverity.HIGH)),
            hashFor(base.copy(title = "Different title")),
            hashFor(base.copy(note = "different note"))
        )

        assertThat(hashes.toSet()).hasSize(hashes.size)
    }

    @Test
    fun `pending digest rejects every formerly unauthenticated field`() = runTest {
        val mutations = linkedMapOf<String, (JsonObject) -> Unit>(
            "source" to { it.add("source", JsonPrimitive(EventSource.AUTOMATIC.name)) },
            "subtype" to { it.add("subtype", JsonPrimitive("tampered")) },
            "attributes" to {
                it.add("attributes", JsonObject().apply { addProperty("factor", "9.9") })
            },
            "status" to { it.add("status", JsonPrimitive(CompensationEventStatus.CLOSED.name)) },
            "provenance" to { it.add("provenance", JsonPrimitive("tampered")) }
        )

        mutations.forEach { (_, mutate) ->
            val fixture = Fixture()
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            assertThat(fixture.save(event(), expectedRevision = null))
                .isEqualTo(ContextEventCommandResult.PENDING)
            val original = fixture.sync.records.single()
            fixture.sync.records[0] = original.copy(
                requestHash = mutatePendingPayload(original.requestHash, mutate)
            )
            fixture.gateway.sent.clear()

            val summary = fixture.newCoordinator().reconcileAllPending(limit = 8)

            assertThat(summary.malformedCount).isEqualTo(1)
            assertThat(fixture.gateway.sent).isEmpty()
            assertThat(fixture.tags.rows).isEmpty()
        }
    }

    @Test
    fun `pending canonical digest is independent of attribute insertion order`() = runTest {
        suspend fun recordFor(attributes: Map<String, String>): String {
            val fixture = Fixture()
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            fixture.save(event(attributes = attributes), expectedRevision = null)
            return fixture.sync.records.single().requestHash
        }

        val first = recordFor(linkedMapOf("alpha" to "1", "beta" to "2"))
        val second = recordFor(linkedMapOf("beta" to "2", "alpha" to "1"))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `only contextual user events are export eligible`() = runTest {
        val excluded = setOf(
            CompensationEventType.MEAL,
            CompensationEventType.ACTIVITY,
            CompensationEventType.SENSOR_CALIBRATION,
            CompensationEventType.INFUSION_PUMP_INSULIN
        )
        excluded.forEach { type ->
            assertThat(ContextEventSyncCoordinator.isExportEligible(event(type = type))).isFalse()
        }
        assertThat(ContextEventSyncCoordinator.isExportEligible(event(type = CompensationEventType.STRESS))).isTrue()
        assertThat(ContextEventSyncCoordinator.isExportEligible(event(source = EventSource.AAPS))).isFalse()
        assertThat(ContextEventSyncCoordinator.isExportEligible(event(source = EventSource.AUTOMATIC))).isFalse()

        val fixture = Fixture()
        val result = fixture.save(event(type = CompensationEventType.MEAL))

        assertThat(result).isEqualTo(ContextEventCommandResult.REJECTED)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.sync.records).isEmpty()
    }

    @Test
    fun `menstrual save requires female profile and valid phase before persistence`() = runTest {
        val missingPhase = event(
            type = CompensationEventType.MENSTRUAL_CYCLE,
            attributes = emptyMap()
        )
        val nonFemale = Fixture(sex = PhysiologicalSex.MALE)
        val validPhase = missingPhase.copy(
            attributes = mapOf("cyclePhase" to FemaleCyclePhase.OVULATION.name)
        )

        assertThat(Fixture(sex = PhysiologicalSex.FEMALE).save(missingPhase))
            .isEqualTo(ContextEventCommandResult.REJECTED)
        assertThat(nonFemale.save(validPhase))
            .isEqualTo(ContextEventCommandResult.REJECTED)
        assertThat(nonFemale.tags.rows).isEmpty()
        assertThat(nonFemale.sync.records).isEmpty()
        assertThat(nonFemale.gateway.sent).isEmpty()
    }

    @Test
    fun `profile change does not block exact seven day menstrual close or delete`() = runTest {
        val day = 24L * 60L * 60L * 1_000L
        val fixture = Fixture(now = 10L * day, sex = PhysiologicalSex.FEMALE)
        val menstrual = event(
            localId = "historical-cycle",
            startTs = 5L * day,
            endTs = 12L * day,
            type = CompensationEventType.MENSTRUAL_CYCLE,
            attributes = mapOf("cyclePhase" to FemaleCyclePhase.LUTEAL.name)
        )
        assertThat(fixture.save(menstrual))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)

        fixture.sex = PhysiologicalSex.MALE
        fixture.now = 11L * day
        assertThat(fixture.close("historical-cycle"))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.tags.require("historical-cycle").status).isEqualTo("CLOSED")

        assertThat(fixture.delete("historical-cycle"))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.tags.rows).doesNotContainKey("historical-cycle")
        assertThat(fixture.gateway.sent.map { it.operation }).containsExactly(
            AapsContextEventGateway.Operation.CREATE,
            AapsContextEventGateway.Operation.CLOSE,
            AapsContextEventGateway.Operation.DELETE
        ).inOrder()
    }

    @Test
    fun `close and delete reject historical interval above seven days before audit or dispatch`() = runTest {
        val day = 24L * 60L * 60L * 1_000L
        val fixture = Fixture(now = 10L * day)
        val oversized = PhysioContextTagEntity(
            id = "oversized-history",
            tsStart = day,
            tsEnd = 8L * day + 1L,
            tagType = CompensationEventType.STRESS.name,
            severity = 0.5,
            source = EventSource.USER.name,
            note = "history",
            revision = 4L,
            status = CompensationEventStatus.CLOSED.name
        )
        fixture.tags.upsert(oversized)

        assertThat(fixture.close(oversized.id))
            .isEqualTo(ContextEventCommandResult.REJECTED)
        assertThat(fixture.delete(oversized.id))
            .isEqualTo(ContextEventCommandResult.REJECTED)

        assertThat(fixture.tags.require(oversized.id)).isEqualTo(oversized)
        assertThat(fixture.sync.records).isEmpty()
        assertThat(fixture.gateway.sent).isEmpty()
    }

    @Test
    fun `invalid lifecycle identity and future close reject before mutation or dispatch`() = runTest {
        val fixture = Fixture(now = 5_000L)
        val invalidId = PhysioContextTagEntity(
            id = "invalid/id",
            tsStart = 1_000L,
            tsEnd = 8_000L,
            tagType = "STRESS",
            severity = 0.5,
            source = "USER",
            note = "history"
        )
        val future = invalidId.copy(
            id = "future-event",
            tsStart = 6_000L,
            tsEnd = 8_000L
        )
        fixture.tags.upsert(invalidId)
        fixture.tags.upsert(future)

        assertThat(fixture.close("invalid/id"))
            .isEqualTo(ContextEventCommandResult.REJECTED)
        assertThat(fixture.close("future-event"))
            .isEqualTo(ContextEventCommandResult.REJECTED)
        assertThat(fixture.delete("invalid/id"))
            .isEqualTo(ContextEventCommandResult.REJECTED)

        assertThat(fixture.tags.require("invalid/id")).isEqualTo(invalidId)
        assertThat(fixture.tags.require("future-event")).isEqualTo(future)
        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.sync.records).isEmpty()
    }

    @Test
    fun `invalid timestamps durations and overflow reject atomically`() = runTest {
        val fixture = Fixture(now = Long.MAX_VALUE, sex = PhysiologicalSex.FEMALE)
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1_000L
        val invalid = listOf(
            event(startTs = 0L, endTs = 1L),
            event(startTs = 1L, endTs = 1L + sevenDaysMs + 1L),
            event(
                startTs = Long.MAX_VALUE - 1_000L,
                endTs = Long.MAX_VALUE - 1_000L,
                attributes = mapOf("durationMinutes" to Long.MAX_VALUE.toString())
            )
        )

        invalid.forEach { candidate ->
            assertThat(fixture.save(candidate))
                .isEqualTo(ContextEventCommandResult.REJECTED)
        }
        assertThat(fixture.tags.rows).isEmpty()
        assertThat(fixture.sync.records).isEmpty()
        assertThat(fixture.gateway.sent).isEmpty()
    }

    @Test
    fun `valid explicit duration is preserved through persistence and export`() = runTest {
        val fixture = Fixture(now = 10L * 24L * 60L * 60L * 1_000L)
        val candidate = event(
            startTs = 1_000L,
            endTs = 1_000L + 3L * 24L * 60L * 60L * 1_000L
        )

        assertThat(fixture.save(candidate))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.tags.require("event-1").tsEnd).isEqualTo(candidate.endTs)
        assertThat(fixture.gateway.sent.single().event.endTs).isEqualTo(candidate.endTs)
    }

    @Test
    fun `global reconciliation restores lost ack create from immutable audit`() = runTest {
        val fixture = Fixture()
        val original = event(title = "Lost ACK", note = "private note")
        fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")

        assertThat(fixture.save(original, expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(fixture.tags.rows).doesNotContainKey(original.localId)
        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        val summary = fixture.newCoordinator().reconcileAllPending(limit = 8)

        assertThat(summary.processedCount).isEqualTo(1)
        assertThat(summary.acknowledgedCount).isEqualTo(1)
        assertThat(summary.appliedCount).isEqualTo(1)
        assertThat(summary.remainingPendingCount).isEqualTo(0)
        assertThat(summary.toString()).doesNotContain(original.localId)
        assertThat(summary.toString()).doesNotContain("private note")
        assertThat(fixture.tags.require(original.localId).title).isEqualTo("Lost ACK")
    }

    @Test
    fun `global reconciliation is bounded deterministic and reports remainder`() = runTest {
        val fixture = Fixture()
        listOf("event-c", "event-a", "event-b").forEach { id ->
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            assertThat(fixture.save(event(localId = id), expectedRevision = null))
                .isEqualTo(ContextEventCommandResult.PENDING)
        }
        fixture.gateway.sent.clear()
        repeat(2) { fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate") }

        val first = fixture.newCoordinator().reconcileAllPending(limit = 2)

        assertThat(fixture.gateway.sent.map { it.event.localId })
            .containsExactly("event-a", "event-b").inOrder()
        assertThat(first.processedCount).isEqualTo(2)
        assertThat(first.appliedCount).isEqualTo(2)
        assertThat(first.remainingPendingCount).isEqualTo(1)

        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")
        val second = fixture.newCoordinator().reconcileAllPending(limit = 2)
        assertThat(second.processedCount).isEqualTo(1)
        assertThat(second.remainingPendingCount).isEqualTo(0)
        assertThat(fixture.tags.rows.keys).containsExactly("event-a", "event-b", "event-c")
    }

    @Test
    fun `bounded reconciliation rotates fairly across more events than batch size`() = runTest {
        val fixture = Fixture()
        val eventIds = (1..5).map { "event-$it" }
        eventIds.forEach { id ->
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            assertThat(fixture.save(event(localId = id), expectedRevision = null))
                .isEqualTo(ContextEventCommandResult.PENDING)
        }
        fixture.gateway.sent.clear()
        repeat(6) { fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout") }

        repeat(3) {
            val summary = fixture.newCoordinator().reconcileAllPending(limit = 2)
            assertThat(summary.processedCount).isEqualTo(2)
            assertThat(summary.remainingPendingCount).isEqualTo(5)
        }

        assertThat(fixture.gateway.sent.take(5).map { it.event.localId })
            .containsExactlyElementsIn(eventIds).inOrder()
    }

    @Test
    fun `malformed audit is terminal and cancellation preserves durable pending`() = runTest {
        val malformed = Fixture()
        malformed.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
        malformed.save(event(), expectedRevision = null)
        malformed.sync.records[0] = malformed.sync.records.single().copy(
            requestHash = "bad.private-marker"
        )

        val malformedSummary = malformed.newCoordinator().reconcileAllPending(limit = 8)

        assertThat(malformedSummary.malformedCount).isEqualTo(1)
        assertThat(malformedSummary.remainingPendingCount).isEqualTo(0)
        assertThat(malformed.sync.records.single().status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_FAILED)
        assertThat(malformed.sync.records.single().sanitizedError)
            .isEqualTo("invalid_pending_command")

        val cancelled = Fixture()
        cancelled.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
        cancelled.save(event(), expectedRevision = null)
        cancelled.gateway.throwOnSend = CancellationException("stop")

        val error = runCatching {
            cancelled.newCoordinator().reconcileAllPending(limit = 8)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(cancelled.sync.records.single().status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
        assertThat(cancelled.tags.rows).isEmpty()
    }

    @Test
    fun `malformed nullable payload is isolated and independent valid head still reconciles`() = runTest {
        val fixture = Fixture()
        listOf("event-a", "event-b").forEach { id ->
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            assertThat(fixture.save(event(localId = id), expectedRevision = null))
                .isEqualTo(ContextEventCommandResult.PENDING)
        }
        val malformed = fixture.sync.records.single { it.eventId == "event-a" }
        fixture.sync.records[fixture.sync.records.indexOf(malformed)] = malformed.copy(
            requestHash = mutatePendingPayload(malformed.requestHash) { payload ->
                payload.remove("subtype")
            }
        )
        fixture.gateway.sent.clear()
        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        val summary = fixture.newCoordinator().reconcileAllPending(limit = 8)

        assertThat(summary.processedCount).isEqualTo(2)
        assertThat(summary.malformedCount).isEqualTo(1)
        assertThat(summary.acknowledgedCount).isEqualTo(1)
        assertThat(fixture.gateway.sent.map { it.event.localId }).containsExactly("event-b")
        assertThat(fixture.sync.records.single { it.eventId == "event-a" }.sanitizedError)
            .isEqualTo("invalid_pending_command")
        assertThat(fixture.tags.rows.keys).containsExactly("event-b")
    }

    @Test
    fun `nullable wire payload rejects omitted null and wrong typed required fields`() = runTest {
        val invalidMutations = listOf<(JsonObject) -> Unit>(
            { it.remove("subtype") },
            { it.add("subtype", com.google.gson.JsonNull.INSTANCE) },
            { it.add("subtype", JsonObject()) },
            { it.add("title", com.google.gson.JsonNull.INSTANCE) },
            { it.add("title", JsonPrimitive(42)) },
            { it.remove("provenance") },
            { it.add("provenance", com.google.gson.JsonNull.INSTANCE) },
            { it.add("attributes", com.google.gson.JsonArray()) },
            { it.add("attributes", JsonObject().apply { addProperty("factor", 1.2) }) },
            { it.add("revision", JsonPrimitive("1")) },
            { it.add("startTs", JsonPrimitive(1.5)) },
            { it.add("source", com.google.gson.JsonNull.INSTANCE) },
            { it.addProperty("unexpected", "not_authenticated") },
            { it.add("note", JsonObject()) }
        )

        invalidMutations.forEach { mutate ->
            val fixture = Fixture()
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            fixture.save(event(), expectedRevision = null)
            val pending = fixture.sync.records.single()
            fixture.sync.records[0] = pending.copy(
                requestHash = mutatePendingPayload(pending.requestHash, mutate)
            )
            fixture.gateway.sent.clear()

            val summary = fixture.newCoordinator().reconcileAllPending(limit = 8)

            assertThat(summary.malformedCount).isEqualTo(1)
            assertThat(fixture.sync.records.single().status)
                .isEqualTo(ContextEventSyncCoordinator.STATUS_FAILED)
            assertThat(fixture.sync.records.single().sanitizedError)
                .isEqualTo("invalid_pending_command")
            assertThat(fixture.gateway.sent).isEmpty()
        }
    }

    @Test
    fun `omitted nullable note remains recoverable`() = runTest {
        val fixture = Fixture()
        fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
        assertThat(fixture.save(event(note = null), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.PENDING)
        fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")

        val summary = fixture.newCoordinator().reconcileAllPending(limit = 8)

        assertThat(summary.appliedCount).isEqualTo(1)
        assertThat(summary.malformedCount).isEqualTo(0)
        assertThat(fixture.tags.require("event-1").note).isEmpty()
    }

    @Test
    fun `startup drain attempts more than sixteen event heads once without retry loop`() = runTest {
        val fixture = Fixture()
        val ids = (1..20).map { "startup-$it" }
        ids.forEach { id ->
            fixture.gateway.results += ContextEventGatewayResult.Pending("ack_timeout")
            fixture.save(event(localId = id), expectedRevision = null)
        }
        fixture.gateway.sent.clear()
        repeat(ids.size) {
            fixture.gateway.results += ContextEventGatewayResult.Acknowledged("duplicate")
        }

        val summary = fixture.newCoordinator().reconcileStartupPending(pageSize = 4, hardCap = 64)

        assertThat(summary.processedCount).isEqualTo(20)
        assertThat(summary.acknowledgedCount).isEqualTo(20)
        assertThat(summary.appliedCount).isEqualTo(20)
        assertThat(summary.stillPendingCount).isEqualTo(0)
        assertThat(summary.remainingPendingCount).isEqualTo(0)
        assertThat(summary.startupSnapshotCount).isEqualTo(20)
        assertThat(summary.safetyCapReached).isFalse()
        assertThat(fixture.gateway.sent.map { it.event.localId })
            .containsExactlyElementsIn(ids.sorted()).inOrder()
    }

    @Test
    fun `startup drain reports hard cap and never retries pending head in one invocation`() = runTest {
        val fixture = Fixture()
        (1..12).forEach { index ->
            fixture.gateway.results += ContextEventGatewayResult.Pending("initial_timeout")
            fixture.save(event(localId = "capped-$index"), expectedRevision = null)
        }
        fixture.gateway.sent.clear()
        repeat(7) { fixture.gateway.results += ContextEventGatewayResult.Pending("still_pending") }

        val summary = fixture.newCoordinator().reconcileStartupPending(pageSize = 3, hardCap = 7)

        assertThat(summary.processedCount).isEqualTo(7)
        assertThat(summary.stillPendingCount).isEqualTo(7)
        assertThat(summary.remainingPendingCount).isEqualTo(12)
        assertThat(summary.startupSnapshotCount).isEqualTo(8)
        assertThat(summary.safetyCapReached).isTrue()
        assertThat(fixture.gateway.sent.map { it.event.localId }.toSet()).hasSize(7)
    }

    @Test
    fun `failed durable pending refresh blocks gateway dispatch`() = runTest {
        val fixture = Fixture()
        fixture.sync.failNextUpdate = true

        val result = fixture.save(event(), expectedRevision = null)

        assertThat(result).isEqualTo(ContextEventCommandResult.PENDING)
        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.tags.rows).isEmpty()
        assertThat(fixture.sync.records.single().status)
            .isEqualTo(ContextEventSyncCoordinator.STATUS_PENDING)
    }

    @Test
    fun `optimistic revision conflicts have zero dispatch audit and mutation`() = runTest {
        val fixture = Fixture()
        assertThat(fixture.save(event(title = "rev1"), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        val rev1 = fixture.tags.require("event-1")
        fixture.gateway.sent.clear()
        val auditCount = fixture.sync.records.size

        assertThat(
            fixture.save(
                event(title = "stale update"),
                expectedRevision = 0L
            )
        ).isEqualTo(ContextEventCommandResult.CONFLICT)
        assertThat(fixture.close("event-1", expectedRevision = 0L))
            .isEqualTo(ContextEventCommandResult.CONFLICT)
        assertThat(fixture.delete("event-1", expectedRevision = 0L))
            .isEqualTo(ContextEventCommandResult.CONFLICT)
        assertThat(fixture.save(event(title = "duplicate create"), expectedRevision = null))
            .isEqualTo(ContextEventCommandResult.CONFLICT)

        assertThat(fixture.gateway.sent).isEmpty()
        assertThat(fixture.sync.records).hasSize(auditCount)
        assertThat(fixture.tags.require("event-1")).isEqualTo(rev1)

        assertThat(
            fixture.save(
                event(title = "rev2"),
                expectedRevision = rev1.revision
            )
        ).isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(2L)
    }

    @Test
    fun `concurrent updates from one revision allow exactly one command`() = runTest {
        val fixture = Fixture()
        fixture.save(event(title = "rev1"), expectedRevision = null)
        fixture.gateway.sent.clear()
        val auditCount = fixture.sync.records.size

        val results = coroutineScope {
            listOf("candidate-a", "candidate-b").map { title ->
                async {
                    fixture.coordinator.save(
                        event(title = title),
                        expectedRevision = 1L
                    )
                }
            }.awaitAll()
        }

        assertThat(results).containsExactly(
            ContextEventCommandResult.ACKNOWLEDGED,
            ContextEventCommandResult.CONFLICT
        )
        assertThat(fixture.gateway.sent).hasSize(1)
        assertThat(fixture.sync.records).hasSize(auditCount + 1)
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(2L)
    }

    @Test
    fun `exact revisions close then delete successfully`() = runTest {
        val fixture = Fixture(now = 3_000L)
        fixture.save(event(startTs = 1_000L, endTs = 8_000L), expectedRevision = null)

        assertThat(fixture.coordinator.close("event-1", expectedRevision = 1L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.tags.require("event-1").revision).isEqualTo(2L)
        assertThat(fixture.coordinator.delete("event-1", expectedRevision = 2L))
            .isEqualTo(ContextEventCommandResult.ACKNOWLEDGED)
        assertThat(fixture.tags.rows).doesNotContainKey("event-1")
    }

    @Test
    fun `raw timestamp envelope rejects negative inverted oversized and overflow ranges`() {
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1_000L

        assertThat(CompensationEventManualPolicy.validEnvelope(-1L, 1L)).isFalse()
        assertThat(CompensationEventManualPolicy.validEnvelope(0L, 1L)).isFalse()
        assertThat(CompensationEventManualPolicy.validEnvelope(2L, 1L)).isFalse()
        assertThat(CompensationEventManualPolicy.validEnvelope(1L, 1L + sevenDaysMs + 1L)).isFalse()
        assertThat(CompensationEventManualPolicy.validEnvelope(1L, Long.MAX_VALUE)).isFalse()
        assertThat(CompensationEventManualPolicy.validEnvelope(1L, 1L + sevenDaysMs)).isTrue()
    }

    private fun event(
        localId: String = "event-1",
        startTs: Long = 1_000L,
        endTs: Long = 5_000L,
        type: CompensationEventType = CompensationEventType.STRESS,
        subtype: String = "",
        severity: EventSeverity = EventSeverity.MEDIUM,
        source: EventSource = EventSource.USER,
        title: String = "Stress",
        attributes: Map<String, String> = mapOf("factor" to "1.2"),
        note: String? = "note"
    ) = CompensationEvent(
        localId = localId,
        startTs = startTs,
        endTs = endTs,
        type = type,
        subtype = subtype,
        severity = severity,
        source = source,
        title = title,
        attributes = attributes,
        note = note
    )

    private fun decode(json: String): Map<String, String> {
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun mutatePendingPayload(record: String, mutate: (JsonObject) -> Unit): String {
        val digest = record.substringBefore('.')
        val encoded = record.substringAfter('.')
        val json = String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        val payload = JsonParser.parseString(json).asJsonObject
        mutate(payload)
        val tampered = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(gson.toJson(payload).toByteArray(Charsets.UTF_8))
        return "$digest.$tampered"
    }

    private class Fixture(
        now: Long = 10_000L,
        sex: PhysiologicalSex = PhysiologicalSex.FEMALE
    ) {
        val tags = FakePhysioContextTagDao()
        val sync = FakeContextEventSyncDao()
        val transactions = FakeContextEventTransactionRunner(tags, sync)
        val gateway = FakeContextEventGateway()
        var now = now
        var sex = sex
        var durableMutations = 0
        val coordinator = newCoordinator()

        suspend fun save(
            event: CompensationEvent,
            expectedRevision: Long? = tags.rows[event.localId]?.revision
        ): ContextEventCommandResult = coordinator.save(event, expectedRevision)

        suspend fun close(
            eventId: String,
            expectedRevision: Long = tags.require(eventId).revision
        ): ContextEventCommandResult = coordinator.close(eventId, expectedRevision)

        suspend fun delete(
            eventId: String,
            expectedRevision: Long = tags.require(eventId).revision
        ): ContextEventCommandResult = coordinator.delete(eventId, expectedRevision)

        fun newCoordinator() = ContextEventSyncCoordinator(
            contextTagDao = tags,
            syncDao = sync,
            transactionRunner = transactions,
            gateway = gateway,
            gson = Gson(),
            now = { this.now },
            physiologicalSex = { sex },
            onDurableMutationApplied = { durableMutations++ }
        )
    }

    private data class Sent(
        val event: CompensationEvent,
        val operation: AapsContextEventGateway.Operation
    )

    private class FakeContextEventGateway : ContextEventGateway {
        val sent = mutableListOf<Sent>()
        val results = ArrayDeque<ContextEventGatewayResult>()
        var beforeSend: (() -> Unit)? = null
        var throwOnSend: CancellationException? = null

        override suspend fun send(
            event: CompensationEvent,
            operation: AapsContextEventGateway.Operation
        ): ContextEventGatewayResult {
            beforeSend?.invoke()
            throwOnSend?.let { throw it }
            sent += Sent(event, operation)
            return if (results.isEmpty()) {
                ContextEventGatewayResult.Acknowledged("ack-${event.localId}-${event.revision}")
            } else {
                results.removeFirst()
            }
        }
    }

    private class FakePhysioContextTagDao : PhysioContextTagDao {
        val rows = linkedMapOf<String, PhysioContextTagEntity>()

        fun require(id: String): PhysioContextTagEntity = checkNotNull(rows[id])

        override suspend fun upsert(entity: PhysioContextTagEntity) {
            rows[entity.id] = entity
        }

        override suspend fun upsertAll(entities: List<PhysioContextTagEntity>) {
            entities.forEach { rows[it.id] = it }
        }

        override suspend fun byId(id: String): PhysioContextTagEntity? = rows[id]

        override suspend fun activeAt(ts: Long): List<PhysioContextTagEntity> =
            rows.values.filter { it.tsStart <= ts && it.tsEnd >= ts }

        override suspend fun activeAtLimited(ts: Long, limit: Int): List<PhysioContextTagEntity> =
            activeAt(ts).take(limit)

        override suspend fun between(since: Long, through: Long): List<PhysioContextTagEntity> =
            rows.values.filter { it.tsEnd >= since && it.tsStart <= through }

        override fun observeRecent(since: Long): Flow<List<PhysioContextTagEntity>> =
            flowOf(rows.values.filter { it.tsEnd >= since })

        override suspend fun closeById(id: String, closeTs: Long): Int = 0

        override suspend fun deleteById(id: String): Int = if (rows.remove(id) != null) 1 else 0

        override suspend fun deleteByIdAndRevision(id: String, revision: Long): Int {
            val row = rows[id] ?: return 0
            if (row.revision != revision) return 0
            rows.remove(id)
            return 1
        }

        override suspend fun deleteByIdBelowRevision(id: String, revision: Long): Int {
            val row = rows[id] ?: return 0
            if (row.revision >= revision) return 0
            rows.remove(id)
            return 1
        }

        override suspend fun deleteOlderThan(olderThan: Long) {
            rows.entries.removeAll { it.value.tsEnd < olderThan }
        }

        override suspend fun deleteOlderThanWithoutPendingSync(olderThan: Long) {
            rows.entries.removeAll { it.value.tsEnd < olderThan }
        }
    }

    private class FakeContextEventSyncDao : ContextEventSyncDao {
        val records = mutableListOf<ContextEventSyncEntity>()
        var failNextInsert = false
        var failNextUpdate = false

        override suspend fun insert(entity: ContextEventSyncEntity): Long {
            if (failNextInsert) {
                failNextInsert = false
                throw SimulatedProcessDeath()
            }
            if (records.any {
                    it.eventId == entity.eventId &&
                        it.revision == entity.revision &&
                        it.operation == entity.operation
                }
            ) return -1L
            records += entity
            return records.size.toLong()
        }

        override suspend fun update(entity: ContextEventSyncEntity): Int {
            if (failNextUpdate) {
                failNextUpdate = false
                return 0
            }
            val index = records.indexOfFirst { it.syncId == entity.syncId }
            if (index < 0) return 0
            records[index] = entity
            return 1
        }

        override suspend fun forOperation(
            eventId: String,
            revision: Long,
            operation: String
        ): ContextEventSyncEntity? = records.singleOrNull {
            it.eventId == eventId && it.revision == revision && it.operation == operation
        }

        override suspend fun latestForEvent(eventId: String): ContextEventSyncEntity? =
            records.filter { it.eventId == eventId }
                .maxWithOrNull(compareBy<ContextEventSyncEntity> { it.revision }.thenBy { it.attemptedAt })

        override suspend fun firstUnresolvedForEvent(eventId: String): ContextEventSyncEntity? = records
            .filter {
                it.eventId == eventId &&
                    it.status != ContextEventSyncCoordinator.STATUS_COMPLETED
            }
            .minWithOrNull(
                compareBy<ContextEventSyncEntity> { it.revision }
                    .thenBy { it.operation }
                    .thenBy { it.attemptedAt }
                    .thenBy { it.syncId }
            )

        override suspend fun byStatus(status: String): List<ContextEventSyncEntity> =
            records.filter { it.status == status }.sortedBy { it.attemptedAt }

        override suspend fun orderedBatchByStatus(
            status: String,
            limit: Int
        ): List<ContextEventSyncEntity> = records
            .filter { candidate ->
                candidate.status == status && records.none { predecessor ->
                    predecessor.eventId == candidate.eventId &&
                        predecessor.status != ContextEventSyncCoordinator.STATUS_COMPLETED &&
                        (predecessor.revision < candidate.revision ||
                            (predecessor.revision == candidate.revision &&
                                predecessor.syncId < candidate.syncId))
                }
            }
            .sortedWith(
                compareBy<ContextEventSyncEntity> { it.attemptedAt }
                    .thenBy { it.eventId }
                    .thenBy { it.revision }
                    .thenBy { it.operation }
                    .thenBy { it.syncId }
            )
            .take(limit)

        override suspend fun countByStatus(status: String): Int = records.count { it.status == status }

        override suspend fun deleteCompletedOlderThan(olderThan: Long): Int {
            val before = records.size
            records.removeAll { it.completedAt != null && it.completedAt < olderThan }
            return before - records.size
        }
    }

    private class FakeContextEventTransactionRunner(
        private val tags: FakePhysioContextTagDao,
        private val sync: FakeContextEventSyncDao
    ) : ContextEventTransactionRunner {
        var inTransaction = false
        var commits = 0
        var rollbacks = 0

        override suspend fun <T> run(block: suspend () -> T): T {
            check(!inTransaction)
            val tagSnapshot = LinkedHashMap(tags.rows)
            val syncSnapshot = sync.records.toList()
            inTransaction = true
            return try {
                block().also { commits++ }
            } catch (error: Throwable) {
                tags.rows.clear()
                tags.rows.putAll(tagSnapshot)
                sync.records.clear()
                sync.records.addAll(syncSnapshot)
                rollbacks++
                throw error
            } finally {
                inTransaction = false
            }
        }
    }

    private class SimulatedProcessDeath : RuntimeException()
}
