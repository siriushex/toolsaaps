package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class TargetOwnershipPolicyTest {

    @Test
    fun exactObservationAndPriorityPassPreflight() {
        val active = activeTarget()
        val candidate = candidate(
            targetObservation = TargetOwnershipPolicy.capture(
                activeAapsTarget = active,
                copilotPriorityEnabled = true,
                priorityRevision = 4L
            )
        )

        val reason = TargetOwnershipPolicy.preflightFailure(
            candidate = candidate,
            currentObservation = TargetOwnershipPolicy.capture(
                activeAapsTarget = active.copy(),
                copilotPriorityEnabled = true,
                priorityRevision = 4L
            ),
            nowTs = NOW
        )

        assertThat(reason).isNull()
    }

    @Test
    fun everyActiveTargetIdentityChangeFailsPreflight() {
        val active = activeTarget()
        val candidate = candidate(
            targetObservation = TargetOwnershipPolicy.capture(active, true, 4L)
        )
        val changedTargets = listOf(
            active.copy(targetMmol = 6.000000000000001),
            active.copy(startedAt = active.startedAt + 1L),
            active.copy(expiresAt = active.expiresAt + 1L),
            active.copy(source = "other-source"),
            active.copy(ownership = ActiveTargetOwnership.UNKNOWN),
            active.copy(idempotencyKey = "other-command")
        )

        changedTargets.forEach { changed ->
            assertThat(
                TargetOwnershipPolicy.preflightFailure(
                    candidate = candidate,
                    currentObservation = TargetOwnershipPolicy.capture(changed, true, 4L),
                    nowTs = NOW
                )
            ).isEqualTo("active_target_observation_changed")
        }
        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = candidate,
                currentObservation = TargetOwnershipPolicy.capture(null, true, 4L),
                nowTs = NOW
            )
        ).isEqualTo("active_target_observation_changed")
    }

    @Test
    fun losingConfirmedEatingSoonProofFailsPreflight() {
        val active = activeTarget().copy(targetMmol = 4.1,
            idempotencyKey = "manual:meal:meal-a:eating-soon", eatingSoonConfirmed = true)
        val observation = TargetOwnershipPolicy.capture(active, true, 4L)
        assertThat(TargetOwnershipPolicy.preflightFailure(candidate(observation),
            observation.copy(activeAapsTarget = active.copy(eatingSoonConfirmed = false)), NOW))
            .isEqualTo(TargetOwnershipPolicy.ACTIVE_TARGET_OBSERVATION_CHANGED)
        val malformed = observation.copy(activeAapsTarget = active.copy(targetMmol = 4.2))
        assertThat(TargetOwnershipPolicy.preflightFailure(candidate(malformed), malformed, NOW))
            .isEqualTo(TargetOwnershipPolicy.TARGET_OBSERVATION_INVALID)
    }

    @Test
    fun observedAbsenceRejectsNewTargetAndAcceptsContinuedAbsence() {
        val candidate = candidate(
            targetObservation = TargetOwnershipPolicy.capture(null, false, 3L)
        )

        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = candidate,
                currentObservation = TargetOwnershipPolicy.capture(null, false, 3L),
                nowTs = NOW
            )
        ).isNull()
        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = candidate,
                currentObservation = TargetOwnershipPolicy.capture(activeTarget(), false, 3L),
                nowTs = NOW
            )
        ).isEqualTo("active_target_observation_changed")
    }

    @Test
    fun changedPriorityFailsPreflight() {
        val candidate = candidate(
            targetObservation = TargetOwnershipPolicy.capture(null, true, 3L)
        )

        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = candidate,
                currentObservation = TargetOwnershipPolicy.capture(null, false, 3L),
                nowTs = NOW
            )
        ).isEqualTo("copilot_priority_policy_changed")
    }

    @Test
    fun changedPriorityRevisionFailsPreflightEvenWhenEnabledStateReturnsToSameValue() {
        val candidate = candidate(
            targetObservation = TargetOwnershipPolicy.capture(null, true, 3L)
        )

        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = candidate,
                currentObservation = TargetOwnershipPolicy.capture(null, true, 5L),
                nowTs = NOW
            )
        ).isEqualTo("copilot_priority_revision_changed")
    }

    @Test
    fun observedTargetThatExpiresBeforePreflightFailsClosed() {
        val active = activeTarget()
        val observation = TargetOwnershipPolicy.capture(active, true, 3L)

        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = candidate(targetObservation = observation),
                currentObservation = observation,
                nowTs = active.expiresAt
            )
        ).isEqualTo("target_observation_expired")
    }

    @Test
    fun equallyMalformedObservationsFailClosedBeforeEquality() {
        val malformedTargets = listOf(
            activeTarget().copy(targetMmol = Double.NaN),
            activeTarget().copy(startedAt = 0L),
            activeTarget().copy(startedAt = NOW + 1L),
            activeTarget().copy(expiresAt = activeTarget().startedAt),
            activeTarget().copy(source = ""),
            activeTarget().copy(idempotencyKey = ""),
            activeTarget().copy(evidenceResolved = false)
        )

        malformedTargets.forEach { malformed ->
            val observation = TargetOwnershipPolicy.capture(malformed, true, 3L)
            assertThat(
                TargetOwnershipPolicy.preflightFailure(
                    candidate = candidate(targetObservation = observation),
                    currentObservation = observation,
                    nowTs = NOW
                )
            ).isEqualTo("target_observation_invalid")
        }
    }

    @Test
    fun legacyGsonCandidateDefaultsFailClosedWithoutObservation() {
        val legacyJson = """
            {
              "targetMmol": 6.0,
              "durationMinutes": 30,
              "ownerRuleId": "adaptive",
              "intent": "NORMAL_CONTROL",
              "reason": "adaptive",
              "idempotencyKey": "TargetManager.v1:legacy",
              "semanticFingerprint": "legacy",
              "baseProvenance": {
                "scheduleRevision": 1,
                "intervalId": "default"
              },
              "generatedAt": 1800000000000
            }
        """.trimIndent()

        val legacy = Gson().fromJson(legacyJson, TargetCommandCandidate::class.java)

        assertThat(legacy.targetObservation).isNull()
        assertThat(legacy.sensitivityCycleId).isNull()
        assertThat(
            TargetOwnershipPolicy.preflightFailure(
                candidate = legacy,
                currentObservation = TargetOwnershipPolicy.capture(activeTarget(), true, 1L),
                nowTs = NOW
            )
        ).isEqualTo("target_observation_missing")
    }

    private fun candidate(
        targetObservation: TargetCommandObservation? = null
    ) = TargetCommandCandidate(
        targetMmol = 6.0,
        durationMinutes = 30,
        ownerRuleId = "adaptive",
        intent = TargetIntent.NORMAL_CONTROL,
        reason = "adaptive",
        idempotencyKey = "TargetManager.v1:test",
        semanticFingerprint = "test",
        baseProvenance = TargetBaseProvenance(
            scheduleRevision = 1L,
            intervalId = "default",
            adjustmentRunId = null
        ),
        generatedAt = NOW,
        targetObservation = targetObservation
    )

    private fun activeTarget() = ActiveAapsTarget(
        targetMmol = 7.0,
        startedAt = NOW - 5 * MINUTE_MS,
        expiresAt = NOW + 25 * MINUTE_MS,
        source = "aaps_automation",
        ownership = ActiveTargetOwnership.MANUAL_OR_FOREIGN,
        idempotencyKey = null,
        evidenceResolved = true
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val MINUTE_MS = 60_000L
    }
}
