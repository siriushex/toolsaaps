package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.EatingSoonPolicy
import io.aaps.copilot.service.hasActiveManualTempTargetStatic
import io.aaps.copilot.util.UnitConverter
import org.junit.Test

class ManualEatingSoonTargetContextTest {
    private val gson = Gson()
    private val now = 1_800_000_000_000L
    private val key = "manual:meal:meal-a:eating-soon"
    private fun command() = ActionCommandEntity(key, now - 59_000L, "temp_target",
        """{"targetMmol":"4.1","durationMinutes":"30","reason":"Eating Soon"}""",
        "{}", key, NightscoutActionRepository.STATUS_SENT)
    private fun active() = ActiveAapsTarget(4.1, now - 60_000L, now + 1_740_000L,
        "therapy_history", ActiveTargetOwnership.MANUAL_OR_FOREIGN, key)

    @Test fun exactAndWireRoundedObservationsShareOneConfirmedIntent() {
        listOf(4.1, UnitConverter.mgdlToMmol(74.0)).forEach { target ->
            val result = withConfirmedEatingSoonContext(active().copy(targetMmol = target), command(), now, gson)
            assertThat(result?.eatingSoonConfirmed).isTrue()
            assertThat(result?.targetMmol).isEqualTo(target)
            assertThat(result?.startedAt).isEqualTo(active().startedAt)
            assertThat(result?.expiresAt).isEqualTo(active().expiresAt)
        }
    }

    @Test fun absenceAndUnknownFailedBlockedOrWrongTypeNeverConfirmIntent() {
        assertThat(withConfirmedEatingSoonContext(active(), null, now, gson)?.eatingSoonConfirmed).isFalse()
        listOf("PENDING", "FAILED", "BLOCKED", "unknown").forEach { status ->
            assertThat(withConfirmedEatingSoonContext(active(), command().copy(status = status), now, gson)
                ?.eatingSoonConfirmed).isFalse()
        }
        assertThat(withConfirmedEatingSoonContext(active(), command().copy(type = "carbs"), now, gson)
            ?.eatingSoonConfirmed).isFalse()
    }

    @Test fun changedKeyRequestOrObservedTargetCannotConfirmCanonicalEatingSoon() {
        val changedRequests = listOf(
            command().copy(idempotencyKey = "manual:meal:meal-b:eating-soon"),
            command().copy(payloadJson = """{"targetMmol":"4.2","durationMinutes":"30","reason":"Eating Soon"}"""),
            command().copy(payloadJson = """{"targetMmol":"4.1","durationMinutes":"45","reason":"Eating Soon"}"""),
            command().copy(payloadJson = """{"targetMmol":"4.1","durationMinutes":"30","reason":"Other target"}"""),
            command().copy(payloadJson = "[]"), command().copy(payloadJson = "{"),
            command().copy(payloadJson = " ".repeat(4097)))
        changedRequests.forEach { changed ->
            assertThat(withConfirmedEatingSoonContext(active(), changed, now, gson)?.eatingSoonConfirmed).isFalse()
        }
        listOf(active().copy(targetMmol = 4.2), active().copy(expiresAt = active().expiresAt + 1L),
            active().copy(ownership = ActiveTargetOwnership.UNKNOWN), active().copy(evidenceResolved = false),
            active().copy(source = ""), active().copy(idempotencyKey = "manual:meal::eating-soon")).forEach { changed ->
            assertThat(withConfirmedEatingSoonContext(changed, command(), now, gson)?.eatingSoonConfirmed).isFalse()
        }
    }

    @Test fun futureBeforeObservationExpiredOrBackwardsTimeCannotConfirmOrExtendWindow() {
        listOf(command().copy(timestamp = now + 1L), command().copy(timestamp = active().startedAt - 1L),
            command().copy(timestamp = 0L)).forEach { changed ->
            assertThat(withConfirmedEatingSoonContext(active(), changed, now, gson)?.eatingSoonConfirmed).isFalse()
        }
        listOf(active().startedAt - 1L, active().expiresAt, active().expiresAt + 1L).forEach { clock ->
            assertThat(withConfirmedEatingSoonContext(active(), command(), clock, gson)?.eatingSoonConfirmed).isFalse()
        }
        val confirmed = withConfirmedEatingSoonContext(active(), command(), now, gson)!!
        assertThat(EatingSoonPolicy.isActiveConfirmedTarget(confirmed, confirmed.expiresAt)).isFalse()
        assertThat(withConfirmedEatingSoonContext(confirmed, null, now, gson)?.eatingSoonConfirmed).isFalse()
    }

    @Test fun onlyActualConfirmedNewerManagerTargetSupersedesSentEatingSoonHold() {
        val managed = ActiveAapsTarget(8.0, now - 30_000L, now + 1_770_000L,
            "therapy_history", ActiveTargetOwnership.TARGET_MANAGER, "TargetManager.v1:newer")
        val record = command().copy(timestamp = now - 29_000L, idempotencyKey = managed.idempotencyKey!!,
            payloadJson = """{"targetMmol":"8.0","durationMinutes":"30"}""")
        val proof = ConfirmedSupersedingTarget.fromObservation(managed, record, now, gson)
        assertThat(proof).isNotNull()
        val sent = command()
        assertThat(hasActiveManualTempTargetStatic(listOf(sent), now, gson)).isTrue()
        assertThat(hasActiveManualTempTargetStatic(listOf(sent), now, gson,
            confirmedSupersedingTarget = proof)).isFalse()
        listOf(sent.copy(status = "PENDING"), sent.copy(status = "FAILED"),
            sent.copy(timestamp = managed.startedAt + 1L),
            sent.copy(payloadJson = """{"durationMinutes":"30"}""")).forEach { unknown ->
            assertThat(hasActiveManualTempTargetStatic(listOf(unknown), now, gson,
                confirmedSupersedingTarget = proof)).isTrue()
        }
        listOf(managed.copy(ownership = ActiveTargetOwnership.UNKNOWN),
            managed.copy(evidenceResolved = false), managed.copy(targetMmol = 7.9)).forEach { unconfirmed ->
            assertThat(hasActiveManualTempTargetStatic(listOf(sent), now, gson,
                confirmedSupersedingTarget = ConfirmedSupersedingTarget.fromObservation(unconfirmed, record, now, gson))).isTrue()
        }
        assertThat(hasActiveManualTempTargetStatic(listOf(sent), now, gson,
            confirmedSupersedingTarget = ConfirmedSupersedingTarget.fromObservation(managed,
                record.copy(status = "PENDING"), now, gson))).isTrue()
    }

    @Test fun supersedingTargetRequiresExactSentObservedCommandBeforeCallerCanUseIt() {
        val managed = active().copy(targetMmol = 8.0, ownership = ActiveTargetOwnership.TARGET_MANAGER,
            idempotencyKey = "TargetManager.v1:newer")
        val record = command().copy(idempotencyKey = managed.idempotencyKey!!,
            payloadJson = """{"targetMmol":"8.0","durationMinutes":"30"}""")
        assertThat(isConfirmedObservedTargetCommand(managed, record, now, gson)).isTrue()
        assertThat(isConfirmedObservedTargetCommand(managed, record.copy(status = "PENDING"), now, gson)).isFalse()
        assertThat(isConfirmedObservedTargetCommand(managed, record.copy(payloadJson = "{}"), now, gson)).isFalse()
        assertThat(isConfirmedObservedTargetCommand(managed.copy(targetMmol = 7.9), record, now, gson)).isFalse()
    }

    @Test fun aNewerConfirmedEatingSoonReplacesOnlyOlderCanonicalSentHolds() {
        val current = withConfirmedEatingSoonContext(active(), command(), now, gson)!!
        val older = command().copy(id = "old-meal-target", timestamp = now - 120_000L,
            idempotencyKey = "manual:meal:meal-old:eating-soon")
        val proof = ConfirmedSupersedingTarget.fromObservation(current, command(), now, gson)
        assertThat(hasActiveManualTempTargetStatic(listOf(older, command()), now, gson,
            replaceableSentIdempotencyKey = key, confirmedSupersedingTarget = proof)).isFalse()
        listOf(older.copy(status = "PENDING"), older.copy(status = "FAILED"),
            older.copy(timestamp = current.startedAt + 1L),
            older.copy(payloadJson = """{"targetMmol":"7.0","durationMinutes":"30"}""")).forEach { other ->
            assertThat(hasActiveManualTempTargetStatic(listOf(other), now, gson,
                confirmedSupersedingTarget = proof)).isTrue()
        }
        assertThat(hasActiveManualTempTargetStatic(listOf(command()), now, gson,
            confirmedSupersedingTarget = proof)).isTrue()
        assertThat(ConfirmedSupersedingTarget.fromObservation(current.copy(eatingSoonConfirmed = false),
            command(), now, gson)).isNull()
    }
}
