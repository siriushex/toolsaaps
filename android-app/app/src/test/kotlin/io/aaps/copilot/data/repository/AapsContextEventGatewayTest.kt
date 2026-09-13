package io.aaps.copilot.data.repository

import android.content.BroadcastReceiver
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.FemaleCyclePhase
import io.aaps.copilot.domain.profile.PhysiologicalSex
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AapsContextEventGatewayTest {
    @Test fun `allowlist contains only non-therapy context types`() {
        assertThat(AapsContextEventGateway.ALLOWED_TYPES).containsExactly(
            "STRESS",
            "ILLNESS",
            "SLEEP",
            "HORMONAL",
            "MEDICATION_STEROID",
            "ALCOHOL",
            "CUSTOM",
            "MENSTRUAL_CYCLE"
        )
    }
    @Test fun `bridge is explicit and signature protected`() {
        assertThat(AapsContextEventGateway.ACTION).contains("WRITE_COPILOT_CONTEXT_NOTE")
        assertThat(AapsContextEventGateway.PERMISSION).contains("permission.WRITE_COPILOT_CONTEXT_NOTE")
    }
    @Test fun `title bound is sixty`() {
        val event = CompensationEvent("e", 1, 1, CompensationEventType.MEAL, title = "x".repeat(60))
        assertThat(event.title).hasLength(60)
    }
    @Test fun `response must acknowledge exact applied or duplicate command`() {
        fun acknowledged(
            resultCode: Int = 1,
            responseHash: String? = "expected",
            operation: String? = "UPDATE",
            revision: Long = 7L,
            status: String? = "APPLIED"
        ) = AapsContextEventGateway.isAcknowledged(
            resultCode = resultCode,
            responseHash = responseHash,
            expectedHash = "expected",
            responseOperation = operation,
            expectedOperation = AapsContextEventGateway.Operation.UPDATE,
            responseRevision = revision,
            expectedRevision = 7L,
            responseStatus = status
        )

        assertThat(acknowledged()).isTrue()
        assertThat(acknowledged(status = "DUPLICATE")).isTrue()
        assertThat(acknowledged(resultCode = 0)).isFalse()
        assertThat(acknowledged(responseHash = "wrong")).isFalse()
        assertThat(acknowledged(operation = "CLOSE")).isFalse()
        assertThat(acknowledged(revision = 6L)).isFalse()
        assertThat(acknowledged(status = "STALE")).isFalse()
        assertThat(acknowledged(status = "REJECTED")).isFalse()
    }

    @Test fun `lifecycle identity and nonce stay stable across revisions and operations`() {
        val localId = "manual-context-1"
        val key = AapsContextEventGateway.idempotencyKey(localId)

        assertThat(key).isEqualTo("copilot:$localId")
        assertThat(AapsContextEventGateway.idempotencyKey(localId)).isEqualTo(key)
        assertThat(AapsContextEventGateway.nonceFor(key)).hasLength(64)
        assertThat(AapsContextEventGateway.nonceFor(key))
            .isEqualTo(AapsContextEventGateway.nonceFor(key))
    }

    @Test fun `direct gateway calls reject AAPS source and therapy event types before broadcast`() = runTest {
        var broadcastCount = 0
        val gateway = AapsContextEventGateway(
            ContextEventBroadcastSender { _: Intent, _: String, _: BroadcastReceiver -> broadcastCount++ }
        )
        val forbidden = listOf(
            CompensationEvent("aaps", 1, 1, CompensationEventType.STRESS, source = EventSource.AAPS),
            CompensationEvent("meal", 1, 1, CompensationEventType.MEAL),
            CompensationEvent("activity", 1, 1, CompensationEventType.ACTIVITY),
            CompensationEvent("calibration", 1, 1, CompensationEventType.SENSOR_CALIBRATION),
            CompensationEvent("insulin", 1, 1, CompensationEventType.INFUSION_PUMP_INSULIN),
            CompensationEvent("invalid/id", 1, 1, CompensationEventType.STRESS),
            CompensationEvent("x".repeat(249), 1, 1, CompensationEventType.STRESS)
        )

        forbidden.forEach { event ->
            assertThat(gateway.send(event, AapsContextEventGateway.Operation.CREATE))
                .isInstanceOf(ContextEventGatewayResult.Failed::class.java)
        }

        assertThat(broadcastCount).isEqualTo(0)
    }

    @Test fun `direct gateway rejects invalid menstrual phase sex and timestamp envelope`() = runTest {
        suspend fun sendCount(
            event: CompensationEvent,
            sex: PhysiologicalSex = PhysiologicalSex.FEMALE
        ): Int {
            var count = 0
            val gateway = AapsContextEventGateway(
                broadcastSender = ContextEventBroadcastSender { _, _, _ -> count++ },
                physiologicalSex = { sex },
                clock = { 1_000_000L }
            )
            assertThat(gateway.send(event, timeoutMs = 1L))
                .isInstanceOf(ContextEventGatewayResult.Failed::class.java)
            return count
        }
        val validMenstrual = CompensationEvent(
            localId = "cycle",
            startTs = 1L,
            endTs = 60_001L,
            type = CompensationEventType.MENSTRUAL_CYCLE,
            attributes = mapOf("cyclePhase" to FemaleCyclePhase.LUTEAL.name)
        )

        assertThat(sendCount(validMenstrual.copy(attributes = emptyMap()))).isEqualTo(0)
        assertThat(sendCount(validMenstrual, PhysiologicalSex.MALE)).isEqualTo(0)
        assertThat(sendCount(validMenstrual.copy(startTs = 0L))).isEqualTo(0)
        assertThat(
            sendCount(
                validMenstrual.copy(
                    endTs = validMenstrual.startTs + 7L * 24L * 60L * 60L * 1_000L + 1L
                )
            )
        ).isEqualTo(0)
    }

    @Test fun `direct gateway accepts valid explicit seven day bounded duration`() = runTest {
        var sent: Intent? = null
        val gateway = AapsContextEventGateway(
            broadcastSender = ContextEventBroadcastSender { intent, _, _ -> sent = intent },
            physiologicalSex = { PhysiologicalSex.FEMALE },
            clock = { 1_000_000L }
        )
        val event = CompensationEvent(
            localId = "cycle-valid",
            startTs = 1L,
            endTs = 1L + 7L * 24L * 60L * 60L * 1_000L,
            type = CompensationEventType.MENSTRUAL_CYCLE,
            attributes = mapOf("cyclePhase" to FemaleCyclePhase.MENSTRUATION.name)
        )

        assertThat(gateway.send(event, timeoutMs = 1L))
            .isInstanceOf(ContextEventGatewayResult.Pending::class.java)
        assertThat(sent?.getLongExtra("startTs", -1L)).isEqualTo(event.startTs)
        assertThat(sent?.getLongExtra("endTs", -1L)).isEqualTo(event.endTs)
    }

    @Test fun `close and delete exact seven day menstrual event do not read current profile sex`() = runTest {
        val sentOperations = mutableListOf<String>()
        val day = 24L * 60L * 60L * 1_000L
        val gateway = AapsContextEventGateway(
            broadcastSender = ContextEventBroadcastSender { intent, _, _ ->
                sentOperations += checkNotNull(intent.getStringExtra("operation"))
            },
            physiologicalSex = { error("lifecycle operation must not read profile sex") },
            clock = { 10L * day }
        )
        val historical = CompensationEvent(
            localId = "historical-cycle",
            startTs = day,
            endTs = 8L * day,
            type = CompensationEventType.MENSTRUAL_CYCLE,
            attributes = emptyMap(),
            revision = 4L,
            status = CompensationEventStatus.CLOSED
        )

        assertThat(
            gateway.send(historical, AapsContextEventGateway.Operation.CLOSE, timeoutMs = 1L)
        ).isInstanceOf(ContextEventGatewayResult.Pending::class.java)
        assertThat(
            gateway.send(historical, AapsContextEventGateway.Operation.DELETE, timeoutMs = 1L)
        ).isInstanceOf(ContextEventGatewayResult.Pending::class.java)
        assertThat(sentOperations).containsExactly("CLOSE", "DELETE").inOrder()
    }

    @Test fun `close and delete reject interval above seven days before broadcast`() = runTest {
        var broadcastCount = 0
        val day = 24L * 60L * 60L * 1_000L
        val gateway = AapsContextEventGateway(
            broadcastSender = ContextEventBroadcastSender { _, _, _ -> broadcastCount++ },
            physiologicalSex = { error("lifecycle operation must not read profile sex") },
            clock = { 10L * day }
        )
        val oversized = CompensationEvent(
            localId = "oversized-history",
            startTs = day,
            endTs = 8L * day + 1L,
            type = CompensationEventType.STRESS,
            revision = 4L,
            status = CompensationEventStatus.CLOSED
        )

        assertThat(gateway.send(oversized, AapsContextEventGateway.Operation.CLOSE, timeoutMs = 1L))
            .isInstanceOf(ContextEventGatewayResult.Failed::class.java)
        assertThat(gateway.send(oversized, AapsContextEventGateway.Operation.DELETE, timeoutMs = 1L))
            .isInstanceOf(ContextEventGatewayResult.Failed::class.java)
        assertThat(broadcastCount).isEqualTo(0)
    }

    @Test fun `invalid close lifecycle shape fails before broadcast`() = runTest {
        var broadcastCount = 0
        val gateway = AapsContextEventGateway(
            broadcastSender = ContextEventBroadcastSender { _, _, _ -> broadcastCount++ },
            physiologicalSex = { PhysiologicalSex.FEMALE },
            clock = { 100_000L }
        )
        val notClosed = CompensationEvent(
            localId = "not-closed",
            startTs = 1_000L,
            endTs = 2_000L,
            type = CompensationEventType.STRESS,
            status = CompensationEventStatus.ACTIVE
        )

        assertThat(
            gateway.send(notClosed, AapsContextEventGateway.Operation.CLOSE, timeoutMs = 1L)
        ).isInstanceOf(ContextEventGatewayResult.Failed::class.java)
        assertThat(broadcastCount).isEqualTo(0)
    }
}
