package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.NightscoutActionRepository
import io.aaps.copilot.data.repository.TargetCommandPreflightBlockedException
import io.aaps.copilot.data.repository.TargetCommandPreflightFailure
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetCommandCandidate
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetOwnershipPolicy
import io.aaps.copilot.domain.target.TargetManagerMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AppContainerManagedTargetPreflightTest {

    private val gson = Gson()

    @Test
    fun confirmedEatingSoonBlocksOrdinaryRetargetButAllowsExistingQualifiedProtection() {
        val now = 1_800_000_000_000L
        val active = activeTarget(now, ActiveTargetOwnership.MANUAL_OR_FOREIGN).copy(
            targetMmol = 4.1, idempotencyKey = "manual:meal:meal-a:eating-soon", eatingSoonConfirmed = true)
        val observation = TargetOwnershipPolicy.capture(active, true, 4L)
        assertThat(managedTargetOwnershipPreflightFailureStatic(candidate(observation), observation, now))
            .isEqualTo("eating_soon_target_active")
        val protective = candidate(observation).copy(targetMmol = 8.0, intent = TargetIntent.HYPO_PROTECTION)
        assertThat(managedTargetOwnershipPreflightFailureStatic(protective, observation, now)).isNull()
        assertThat(managedTargetOwnershipPreflightFailureStatic(protective,
            observation.copy(activeAapsTarget = active.copy(eatingSoonConfirmed = false)), now))
            .isEqualTo(TargetOwnershipPolicy.ACTIVE_TARGET_OBSERVATION_CHANGED)
        assertThat(managedTargetOwnershipPreflightFailureStatic(protective.copy(targetMmol = 4.1), observation, now))
            .isEqualTo("eating_soon_target_active")
        val disabled = observation.copy(copilotPriorityEnabled = false)
        assertThat(managedTargetOwnershipPreflightFailureStatic(protective.copy(targetObservation = disabled), disabled, now))
            .isEqualTo("manual_or_foreign_target_active")
    }

    @Test
    fun productionDispatchWiringStopsBeforeDeliveryWithTypedKnownPreflightReason() = runBlocking {
        val blockedReasons = mutableListOf<String>()
        var deliveryCalls = 0

        val failure = captureFailure {
            dispatchManagedTargetAfterPreflightStatic(
                preflight = { "manual_target_active_or_pending" },
                onPreflightBlocked = { blockedReasons += it },
                deliver = { deliveryCalls++; true }
            )
        }

        assertThat(failure).isInstanceOf(TargetCommandPreflightBlockedException::class.java)
        assertThat((failure as TargetCommandPreflightBlockedException).failure)
            .isEqualTo(TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING)
        assertThat(blockedReasons).containsExactly("manual_target_active_or_pending")
        assertThat(deliveryCalls).isEqualTo(0)
    }

    @Test
    fun productionDispatchWiringLeavesRealTransportBooleanUnchanged() = runBlocking {
        var deliveryCalls = 0

        val delivered = dispatchManagedTargetAfterPreflightStatic(
            preflight = { null },
            onPreflightBlocked = { throw AssertionError("unexpected preflight block") },
            deliver = { deliveryCalls++; false }
        )

        assertThat(delivered).isFalse()
        assertThat(deliveryCalls).isEqualTo(1)
    }

    @Test
    fun onlyExactObservedSentManualCommandMayBeReplaced() {
        val now = 1_800_000_000_000L
        val sent = manualCommand(now - 60_000L, NightscoutActionRepository.STATUS_SENT,
            """{"durationMinutes":"30"}""")
        assertThat(hasActiveManualTempTargetStatic(listOf(sent), now, gson,
            replaceableSentIdempotencyKey = sent.idempotencyKey)).isFalse()
        assertThat(hasActiveManualTempTargetStatic(listOf(sent), now, gson,
            replaceableSentIdempotencyKey = "different")).isTrue()
        assertThat(hasActiveManualTempTargetStatic(listOf(sent.copy(status = NightscoutActionRepository.STATUS_PENDING)),
            now, gson, replaceableSentIdempotencyKey = sent.idempotencyKey)).isTrue()
        assertThat(hasActiveManualTempTargetStatic(listOf(sent.copy(payloadJson = "{")),
            now, gson, replaceableSentIdempotencyKey = sent.idempotencyKey)).isTrue()
    }

    @Test
    fun sentManualTargetBeforeCandidateStillBlocksWhileItsDurationIsActive() {
        val now = 1_800_000_000_000L
        val command = manualCommand(
            timestamp = now - 60_000L,
            status = NightscoutActionRepository.STATUS_SENT,
            payloadJson = """{"durationMinutes":"30"}"""
        )

        assertThat(hasActiveManualTempTargetStatic(listOf(command), now, gson)).isTrue()
    }

    @Test
    fun expiredOrZeroDurationManualTargetDoesNotBlock() {
        val now = 1_800_000_000_000L
        val expired = manualCommand(
            timestamp = now - 31L * 60_000L,
            status = NightscoutActionRepository.STATUS_SENT,
            payloadJson = """{"durationMinutes":"30"}"""
        )
        val cancelled = manualCommand(
            timestamp = now - 60_000L,
            status = NightscoutActionRepository.STATUS_SENT,
            payloadJson = """{"durationMinutes":"0"}"""
        )

        assertThat(hasActiveManualTempTargetStatic(listOf(expired, cancelled), now, gson)).isFalse()
    }

    @Test
    fun recentPendingManualTargetBlocksBeforeTherapyBroadcastArrives() {
        val now = 1_800_000_000_000L
        val pending = manualCommand(
            timestamp = now - 60_000L,
            status = NightscoutActionRepository.STATUS_PENDING,
            payloadJson = "{}"
        )

        assertThat(hasActiveManualTempTargetStatic(listOf(pending), now, gson)).isTrue()
    }

    @Test
    fun unknownManualCommandStatusFailsClosed() {
        val now = 1_800_000_000_000L
        val unknown = manualCommand(
            timestamp = now - 60_000L,
            status = "transport_unknown",
            payloadJson = """{"durationMinutes":"30"}"""
        )

        assertThat(
            hasActiveManualTempTargetStatic(
                commands = listOf(unknown),
                now = now,
                gson = gson,
                replaceableSentIdempotencyKey = unknown.idempotencyKey
            )
        ).isTrue()
    }

    @Test
    fun confirmedBlockedManualCommandDoesNotBlockButFailedDeliveryRemainsUnknownPastNominalDuration() {
        val now = 1_800_000_000_000L
        val blocked = manualCommand(
            timestamp = now - 31L * 60L * 1_000L,
            status = NightscoutActionRepository.STATUS_BLOCKED,
            payloadJson = """{"durationMinutes":"30"}"""
        )
        val failed = blocked.copy(status = NightscoutActionRepository.STATUS_FAILED)

        assertThat(hasActiveManualTempTargetStatic(listOf(blocked), now, gson)).isFalse()
        assertThat(hasActiveManualTempTargetStatic(listOf(failed), now, gson)).isTrue()
    }

    @Test
    fun matchingExternalObservationStillRequiresCurrentPriorityAuthorization() {
        val now = 1_800_000_000_000L
        val external = activeTarget(now, ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        val disabledObservation = TargetOwnershipPolicy.capture(external, false, 8L)
        val enabledObservation = TargetOwnershipPolicy.capture(external, true, 9L)

        assertThat(
            managedTargetOwnershipPreflightFailureStatic(
                candidate(disabledObservation),
                disabledObservation,
                now
            )
        ).isEqualTo("manual_or_foreign_target_active")
        assertThat(
            managedTargetOwnershipPreflightFailureStatic(
                candidate(enabledObservation),
                enabledObservation,
                now
            )
        ).isNull()
    }

    @Test
    fun matchingLegacyObservationStillRequiresCurrentPriorityAuthorization() {
        val now = 1_800_000_000_000L
        val legacy = activeTarget(now, ActiveTargetOwnership.LEGACY_COPILOT)
        val observation = TargetOwnershipPolicy.capture(legacy, false, 4L)

        assertThat(
            managedTargetOwnershipPreflightFailureStatic(candidate(observation), observation, now)
        ).isEqualTo("legacy_target_active")
    }

    @Test
    fun onlyValidatedObservedManualSentKeyIsReplaceable() {
        val now = 1_800_000_000_000L
        val active = activeTarget(now, ActiveTargetOwnership.MANUAL_OR_FOREIGN).copy(
            idempotencyKey = "manual:temp_target:observed"
        )
        val observation = TargetOwnershipPolicy.capture(active, true, 12L)
        val command = candidate(observation)

        assertThat(
            replaceableObservedManualSentIdempotencyKeyStatic(command, observation, now)
        ).isEqualTo("manual:temp_target:observed")
        assertThat(
            replaceableObservedManualSentIdempotencyKeyStatic(
                command,
                observation.copy(priorityRevision = 13L),
                now
            )
        ).isNull()
    }

    @Test
    fun managedTargetFreshnessRequiresCurrentAcceptedCycleAndGlucose() {
        val now = 1_800_000_000_000L
        val command = candidate(
            TargetOwnershipPolicy.capture(null, true, 2L),
            generatedAt = now - 30_000L,
            sensitivityCycleId = "accepted-cycle"
        )

        assertThat(
            managedTargetFreshnessPreflightFailureStatic(
                candidate = command,
                acceptedSensitivityCycleId = "accepted-cycle",
                latestGlucoseTs = now - 60_000L,
                nowTs = now,
                glucoseFreshnessMs = 15 * 60_000L
            )
        ).isNull()
        assertThat(
            managedTargetFreshnessPreflightFailureStatic(
                command,
                acceptedSensitivityCycleId = "new-cycle",
                latestGlucoseTs = now - 60_000L,
                nowTs = now,
                glucoseFreshnessMs = 15 * 60_000L
            )
        ).isEqualTo("accepted_forecast_cycle_changed")
        assertThat(
            managedTargetFreshnessPreflightFailureStatic(
                command,
                acceptedSensitivityCycleId = "accepted-cycle",
                latestGlucoseTs = now - 20_000L,
                nowTs = now,
                glucoseFreshnessMs = 15 * 60_000L
            )
        ).isEqualTo("current_glucose_newer_than_candidate")
        assertThat(
            managedTargetFreshnessPreflightFailureStatic(
                command,
                acceptedSensitivityCycleId = "accepted-cycle",
                latestGlucoseTs = null,
                nowTs = now,
                glucoseFreshnessMs = 15 * 60_000L
            )
        ).isEqualTo("current_glucose_missing")
    }

    @Test
    fun freshObservationTimeExpiresPreviouslyActiveTarget() {
        val initialNow = 1_800_000_000_000L
        val active = activeTarget(initialNow, ActiveTargetOwnership.MANUAL_OR_FOREIGN).copy(
            expiresAt = initialNow + 1L
        )
        val observation = TargetOwnershipPolicy.capture(active, true, 2L)

        assertThat(
            managedTargetOwnershipPreflightFailureStatic(
                candidate(observation, generatedAt = initialNow),
                observation,
                initialNow + 2L
            )
        ).isEqualTo(TargetOwnershipPolicy.TARGET_OBSERVATION_EXPIRED)
    }

    @Test
    fun finalSettingsFenceRejectsEveryManagedTargetAuthorityChange() {
        val expected = testSettings().copy(
            targetManagerMode = TargetManagerMode.ACTIVE,
            killSwitch = false,
            safetyMinTargetMmol = 4.0,
            safetyMaxTargetMmol = 10.0,
            targetManagerCopilotPriorityEnabled = true,
            targetManagerPolicyRevision = 7L,
            sensitivitySettingsRevision = 11L
        )
        val changed = listOf(
            expected.copy(targetManagerMode = TargetManagerMode.OFF),
            expected.copy(killSwitch = true),
            expected.copy(safetyMinTargetMmol = 4.1),
            expected.copy(safetyMaxTargetMmol = 9.9),
            expected.copy(targetManagerCopilotPriorityEnabled = false),
            expected.copy(targetManagerPolicyRevision = 8L),
            expected.copy(sensitivitySettingsRevision = 12L),
            expected.copy(baseTargetSchedule = expected.baseTargetSchedule.copy(revision = 1L))
        )

        assertThat(managedTargetSettingsIdentityMatchesStatic(expected, expected.copy())).isTrue()
        changed.forEach { current ->
            assertThat(managedTargetSettingsIdentityMatchesStatic(expected, current)).isFalse()
        }
    }

    @Test
    fun managedTargetPreflightRequiresArmedBaselineBeforeAuthorityReads() {
        assertThat(managedTargetBaselineArmPreflightFailureStatic(false))
            .isEqualTo("therapy_actions_not_armed")
        assertThat(managedTargetBaselineArmPreflightFailureStatic(true)).isNull()

        val direct = File("src/main/kotlin/io/aaps/copilot/service/AppContainer.kt")
        val source = (if (direct.exists()) {
            direct
        } else {
            File("app/src/main/kotlin/io/aaps/copilot/service/AppContainer.kt")
        }).readText()
        val preflightBody = source.substringAfter(
            "private suspend fun managedTargetPreflightFailureInTransaction"
        ).substringBefore("private suspend fun loadLocalSafetyEvidenceAt")
        val armedBaselineCheck = preflightBody.indexOf(
            "managedTargetBaselineArmPreflightFailureStatic(settings.therapyActionsArmed)"
        )
        val acceptedTupleRead = preflightBody.indexOf("acceptedSensitivityTupleRoomLoader")

        assertThat(armedBaselineCheck).isAtLeast(0)
        assertThat(acceptedTupleRead).isGreaterThan(armedBaselineCheck)
    }

    @Test
    fun productionPreflightRecapturesObservationClockAndNeverUsesCandidateTimeForSafetyRead() {
        val direct = File("src/main/kotlin/io/aaps/copilot/service/AppContainer.kt")
        val source = (if (direct.exists()) {
            direct
        } else {
            File("app/src/main/kotlin/io/aaps/copilot/service/AppContainer.kt")
        }).readText()

        assertThat(source).contains("val observationClock = captureLocalSafetyReadClock()")
        assertThat(source).contains("loadLocalSafetyEvidenceAt(observationClock)")
        assertThat(source).contains("nowTs = observationNow")
        assertThat(source).doesNotContain("nowTs = candidate.generatedAt")
        assertThat(source).contains("managedTargetSettingsIdentityMatchesStatic(settings, finalSettings)")
    }

    @Test
    fun automationInputCarriesCurrentPriorityPolicyIdentity() {
        val direct = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt"
        )
        val source = (if (direct.exists()) {
            direct
        } else {
            File("app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt")
        }).readText()

        assertThat(source).contains(
            "copilotPriorityEnabled = settings.targetManagerCopilotPriorityEnabled"
        )
        assertThat(source).contains("priorityRevision = settings.targetManagerPolicyRevision")
    }

    @Test
    fun sixHourManualTargetStillBlocksAfterFourHours() {
        val now = 1_800_000_000_000L
        val active = manualCommand(
            timestamp = now - 4L * 60L * 60_000L,
            status = NightscoutActionRepository.STATUS_SENT,
            payloadJson = """{"durationMinutes":"360"}"""
        )

        assertThat(hasActiveManualTempTargetStatic(listOf(active), now, gson)).isTrue()
    }

    @Test
    fun waitingDataRunAllowsManualOnlyProvenance() {
        assertThat(
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = true,
                latestRunId = "waiting-run",
                latestRunStatus = "WAITING_DATA",
                candidateRunId = null
            )
        ).isNull()
    }

    @Test
    fun activeRunRequiresExactAppliedRunProvenance() {
        assertThat(
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = true,
                latestRunId = "active-run",
                latestRunStatus = "ACTIVE",
                candidateRunId = null
            )
        ).isEqualTo("adjustment_run_changed")
        assertThat(
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = true,
                latestRunId = "active-run",
                latestRunStatus = "ACTIVE",
                candidateRunId = "active-run"
            )
        ).isNull()
    }

    @Test
    fun nonActiveRunRejectsUnrelatedRunProvenance() {
        assertThat(
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = true,
                latestRunId = "waiting-run",
                latestRunStatus = "WAITING_DATA",
                candidateRunId = "older-run"
            )
        ).isEqualTo("adjustment_run_changed")
    }

    @Test
    fun invalidRunStateFailsClosed() {
        assertThat(
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = true,
                latestRunId = "run",
                latestRunStatus = "CORRUPT",
                candidateRunId = null
            )
        ).isEqualTo("adjustment_run_changed")
    }

    private fun manualCommand(
        timestamp: Long,
        status: String,
        payloadJson: String
    ) = ActionCommandEntity(
        id = "manual-command",
        timestamp = timestamp,
        type = "temp_target",
        payloadJson = payloadJson,
        safetyJson = "{}",
        idempotencyKey = "manual:temp_target:$timestamp",
        status = status
    )

    private fun activeTarget(
        now: Long,
        ownership: ActiveTargetOwnership
    ) = ActiveAapsTarget(
        targetMmol = 7.0,
        startedAt = now - 5 * 60_000L,
        expiresAt = now + 25 * 60_000L,
        source = "aaps_automation",
        ownership = ownership,
        idempotencyKey = null
    )

    private fun candidate(
        observation: io.aaps.copilot.domain.target.TargetCommandObservation,
        generatedAt: Long = 1_800_000_000_000L,
        sensitivityCycleId: String = "accepted-cycle"
    ) = TargetCommandCandidate(
        targetMmol = 6.0,
        durationMinutes = 30,
        ownerRuleId = "adaptive",
        intent = TargetIntent.NORMAL_CONTROL,
        reason = "control",
        idempotencyKey = "TargetManager.v1:test",
        semanticFingerprint = "test",
        baseProvenance = TargetBaseProvenance(1L, "interval", null),
        generatedAt = generatedAt,
        targetObservation = observation,
        sensitivityCycleId = sensitivityCycleId
    )

    private fun testSettings() = AppSettings(
        nightscoutUrl = "",
        apiSecret = "",
        cloudBaseUrl = "",
        killSwitch = false,
        rootExperimentalEnabled = false,
        localBroadcastIngestEnabled = true,
        strictBroadcastSenderValidation = false,
        localNightscoutEnabled = true,
        localNightscoutPort = 17_582,
        localCommandFallbackEnabled = true,
        localCommandPackage = "info.nightscout.androidaps",
        localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
        insulinProfileId = "FIASP",
        baseTargetMmol = 5.5,
        postHypoThresholdMmol = 3.0,
        postHypoDeltaThresholdMmol5m = 0.2,
        postHypoTargetMmol = 4.4,
        postHypoDurationMinutes = 60,
        postHypoLookbackMinutes = 90,
        rulePostHypoEnabled = true,
        rulePatternEnabled = true,
        ruleSegmentEnabled = true,
        adaptiveControllerEnabled = true,
        rulePostHypoPriority = 100,
        rulePatternPriority = 50,
        ruleSegmentPriority = 40,
        adaptiveControllerPriority = 120,
        rulePostHypoCooldownMinutes = 30,
        rulePatternCooldownMinutes = 30,
        ruleSegmentCooldownMinutes = 30,
        adaptiveControllerRetargetMinutes = 5,
        adaptiveControllerSafetyProfile = "BALANCED",
        adaptiveControllerStaleMaxMinutes = 15,
        adaptiveControllerMaxActions6h = 4,
        adaptiveControllerMaxStepMmol = 0.25,
        patternMinSamplesPerWindow = 40,
        patternMinActiveDaysPerWindow = 7,
        patternLowRateTrigger = 0.12,
        patternHighRateTrigger = 0.18,
        analyticsLookbackDays = 365,
        maxActionsIn6Hours = 3,
        staleDataMaxMinutes = 10,
        exportFolderUri = null
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }
}
