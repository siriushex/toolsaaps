package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.AlertCauseAnalysis
import io.aaps.copilot.domain.alerts.AlertCauseAnalyzer
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.alerts.CauseConfidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class EpisodeAlertDeliveryStateMachineTest {

    @Test
    fun postCommitObserverRejectsNoncanonicalPersistedCauseWrapper() = runBlocking<Unit> {
        val observed = mutableListOf<InitialAlertDelivery>()
        val fixture = fixture(
            postCommitObserver = EpisodeAlertPostCommitObserver(observed::add)
        )
        val signal = low(GlucoseAlertState.WARNING_30, 1_000L).copy(
            causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
            causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
        )

        fixture.machine.coordinate(signal) { claim ->
            val event = requireNotNull(fixture.store.event(claim.episodeId))
            fixture.store.upsertEvent(
                event.copy(localSnapshotJson = " ${event.localSnapshotJson}")
            )
            fixture.deliver(claim)
        }

        assertThat(observed).isEmpty()
    }

    @Test
    fun postCommitObserverRejectsSnapshotCauseChangedAfterClaim() = runBlocking<Unit> {
        val observed = mutableListOf<InitialAlertDelivery>()
        val fixture = fixture(
            postCommitObserver = EpisodeAlertPostCommitObserver(observed::add)
        )
        val signal = low(GlucoseAlertState.WARNING_30, 1_000L).copy(
            causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
            causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
        )

        fixture.machine.coordinate(signal) { claim ->
            val event = requireNotNull(fixture.store.event(claim.episodeId))
            fixture.store.upsertEvent(
                event.copy(
                    localSnapshotJson = event.localSnapshotJson.changedCause(
                        from = AlertCauseCode.SENSOR_QUALITY,
                        to = AlertCauseCode.EVENT_CONTEXT,
                        fromEvidence = "SENSOR_BLOCKED",
                        toEvidence = "EVENT_STRESS"
                    )
                )
            )
            fixture.deliver(claim)
        }

        assertThat(observed).isEmpty()
    }

    @Test
    fun postCommitObserverRejectsEventCauseChangedAfterClaim() = runBlocking<Unit> {
        val observed = mutableListOf<InitialAlertDelivery>()
        val fixture = fixture(
            postCommitObserver = EpisodeAlertPostCommitObserver(observed::add)
        )
        val signal = low(GlucoseAlertState.WARNING_30, 1_000L).copy(
            causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
            causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
        )

        fixture.machine.coordinate(signal) { claim ->
            val event = requireNotNull(fixture.store.event(claim.episodeId))
            fixture.store.upsertEvent(
                event.copy(
                    causeCode = AlertCauseCode.EVENT_CONTEXT.name,
                    localSnapshotJson = event.localSnapshotJson.changedCause(
                        from = AlertCauseCode.SENSOR_QUALITY,
                        to = AlertCauseCode.EVENT_CONTEXT,
                        fromEvidence = "SENSOR_BLOCKED",
                        toEvidence = "EVENT_STRESS"
                    )
                )
            )
            fixture.deliver(claim)
        }

        assertThat(observed).isEmpty()
    }

    @Test
    fun postCommitObserverFiresOnlyForOneDeliveredInitialWithPersistedCause() = runBlocking<Unit> {
        val observed = mutableListOf<InitialAlertDelivery>()
        val fixture = fixture(postCommitObserver = EpisodeAlertPostCommitObserver(observed::add))
        val initialSignal = low(GlucoseAlertState.WARNING_30, 1_000L).copy(
            causeAnalysis = cause(AlertCauseCode.MEAL_UAM),
            causeSnapshot = snapshot(AlertCauseCode.MEAL_UAM, "UAM_ACTIVE")
        )

        val initial = fixture.machine.coordinate(initialSignal, fixture::deliver)
        fixture.machine.coordinate(initialSignal.copy(nowTs = 2_000L), fixture::deliver)
        fixture.machine.coordinate(
            initialSignal.copy(stage = GlucoseAlertState.LOW_NOW, nowTs = 3_000L),
            fixture::deliver
        )
        fixture.machine.coordinate(safe(4_000L), fixture::deliver)

        assertThat(observed).hasSize(1)
        assertThat(observed.single().episodeId).isEqualTo(initial.episodeId)
        assertThat(observed.single().requestedAt).isEqualTo(1_000L)
        assertThat(observed.single().stage).isEqualTo(GlucoseAlertState.WARNING_30.name)
        assertThat(observed.single().direction).isEqualTo(
            io.aaps.copilot.domain.alerts.AlertCauseDirection.LOW
        )
        assertThat(
            JsonParser.parseString(observed.single().localCauseSnapshot.canonicalJson)
                .asJsonObject.get("cause").asString
        ).isEqualTo(AlertCauseCode.MEAL_UAM.name)
        assertThat(
            requireNotNull(fixture.store.event(requireNotNull(initial.episodeId))).localSnapshotJson
        ).isEqualTo(
            AlertEpisodeCauseSnapshotCodec.decode(
                requireNotNull(fixture.store.event(requireNotNull(initial.episodeId))).localSnapshotJson
            )?.canonicalEpisodeJson
        )
    }

    @Test
    fun postCommitObserverNeverFiresForSuppressedFailedMissingCauseOrLegacyImport() = runBlocking<Unit> {
        val observed = mutableListOf<InitialAlertDelivery>()
        val observer = EpisodeAlertPostCommitObserver(observed::add)

        val suppressed = fixture(postCommitObserver = observer)
        suppressed.machine.muteFor(1_000L, 30 * 60_000L)
        suppressed.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 2_000L).copy(
                causeAnalysis = cause(AlertCauseCode.EVENT_CONTEXT),
                causeSnapshot = snapshot(AlertCauseCode.EVENT_CONTEXT, "EVENT_STRESS")
            ),
            suppressed::deliver
        )

        val failed = fixture(postCommitObserver = observer)
        failed.sideEffectResult = AlertSideEffectResult.failed("notification_failed")
        failed.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 3_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
            ),
            failed::deliver
        )

        val missingCause = fixture(postCommitObserver = observer)
        missingCause.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 4_000L),
            missingCause::deliver
        )

        val legacy = fixture(postCommitObserver = observer)
        legacy.machine.importLegacyIfNeeded(activeLegacyState(), nowTs = 5_000L)

        assertThat(observed).isEmpty()
    }

    @Test
    fun postCommitObserverRunsAfterReceiptAndEventCommit() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        var observedCommittedState = false
        val fixture = fixture(
            store = store,
            postCommitObserver = EpisodeAlertPostCommitObserver { delivery ->
                val receipt = store.receiptsFor(delivery.episodeId).single()
                val event = store.event(delivery.episodeId)
                observedCommittedState = !store.transactionActive &&
                    receipt.result == AlertReceiptResult.DELIVERED.name &&
                    event?.lastNotificationAt == delivery.requestedAt
            }
        )

        fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.DATA_INCOMPLETE),
                causeSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
            ),
            fixture::deliver
        )

        assertThat(observedCommittedState).isTrue()
    }

    @Test
    fun postCommitObserverFailureCannotChangeDeliveredResultOrDurableState() = runBlocking<Unit> {
        val fixture = fixture(
            postCommitObserver = EpisodeAlertPostCommitObserver {
                throw IllegalStateException("observer failure")
            }
        )

        val result = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.DATA_INCOMPLETE),
                causeSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
            ),
            fixture::deliver
        )

        assertThat(result.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(result.sideEffectAttempted).isTrue()
        assertThat(fixture.store.receiptsFor(result.episodeId!!).single().result)
            .isEqualTo(AlertReceiptResult.DELIVERED.name)
        assertThat(fixture.visibleDeliveries).hasSize(1)
    }

    @Test
    fun oneInitialReceiptPerEpisode() = runBlocking<Unit> {
        val fixture = fixture()

        val first = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), fixture::deliver)
        val repeat = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L), fixture::deliver)

        assertThat(first.deliveryKind).isEqualTo(AlertDeliveryKind.INITIAL)
        assertThat(repeat.deliveryKind).isNull()
        assertThat(fixture.visibleDeliveries).hasSize(1)
        assertThat(fixture.store.receiptsFor(first.episodeId!!).map { it.kind })
            .containsExactly(AlertDeliveryKind.INITIAL.name)
    }

    @Test
    fun stageChangesUpdateWithoutRenotify() = runBlocking<Unit> {
        val fixture = fixture()
        val first = fixture.machine.coordinate(low(GlucoseAlertState.WATCH_60, 1_000L), fixture::deliver)

        val changed = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L), fixture::deliver)

        assertThat(changed.episodeId).isEqualTo(first.episodeId)
        assertThat(changed.deliveryKind).isNull()
        assertThat(fixture.visibleDeliveries).hasSize(1)
        assertThat(fixture.store.event(first.episodeId!!)?.stage).isEqualTo(GlucoseAlertState.WARNING_30.name)
    }

    @Test
    fun newEpisodePersistsInitialCauseAndSnapshotWithoutLaterChurn() = runBlocking<Unit> {
        val fixture = fixture()
        val firstSignal = low(GlucoseAlertState.WARNING_30, 1_000L).copy(
            causeAnalysis = cause(AlertCauseCode.MEAL_UAM),
            causeSnapshot = snapshot(AlertCauseCode.MEAL_UAM, "UAM_ACTIVE")
        )
        val opened = fixture.machine.coordinate(firstSignal, fixture::deliver)

        assertThat(fixture.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.MEAL_UAM)

        fixture.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 2_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
            ),
            fixture::deliver
        )

        val persisted = fixture.store.event(opened.episodeId!!)
        assertThat(persisted?.causeCode).isEqualTo(AlertCauseCode.MEAL_UAM.name)
        assertThat(persisted?.causeSummary).isEqualTo(
            AlertCauseAnalyzer.fixedAdvice(AlertCauseCode.MEAL_UAM)
        )
        assertThat(persisted?.localSnapshotJson).contains("\"initialStage\":\"WARNING_30\"")
        assertThat(persisted?.localSnapshotJson).contains("UAM_ACTIVE")
        assertThat(persisted?.localSnapshotJson).doesNotContain("SENSOR_BLOCKED")
    }

    @Test
    fun lowNowEscalationRendersPersistedInitialCauseInsteadOfRecomputedCause() = runBlocking<Unit> {
        val fixture = fixture()
        val initial = low(GlucoseAlertState.WARNING_30, 1_000L).copy(
            causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
            causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_FALSE_LOW")
        )
        fixture.machine.coordinate(initial, fixture::deliver)

        val recomputed = low(GlucoseAlertState.LOW_NOW, 2_000L).copy(
            causeAnalysis = cause(AlertCauseCode.EVENT_CONTEXT),
            causeSnapshot = snapshot(AlertCauseCode.EVENT_CONTEXT, "EVENT_ACTIVITY")
        )
        fixture.machine.coordinate(recomputed, fixture::deliver)

        val escalationClaim = fixture.lastClaim!!
        val rendered = compactGlucoseAlertText(
            riskLabel = "LOW NOW",
            currentGlucoseMmol = 3.7,
            causeLabel = escalationClaim.persistedCause?.name,
            locale = java.util.Locale.ENGLISH
        )
        val persisted = fixture.store.event(escalationClaim.episodeId)
        assertThat(escalationClaim.kind).isEqualTo(AlertDeliveryKind.LOW_NOW_ESCALATION)
        assertThat(escalationClaim.persistedCause).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
        assertThat(rendered).contains(AlertCauseCode.SENSOR_QUALITY.name)
        assertThat(rendered).doesNotContain(AlertCauseCode.EVENT_CONTEXT.name)
        assertThat(persisted?.causeCode).isEqualTo(AlertCauseCode.SENSOR_QUALITY.name)
        assertThat(persisted?.localSnapshotJson).contains("SENSOR_FALSE_LOW")
        assertThat(persisted?.localSnapshotJson).doesNotContain("EVENT_ACTIVITY")
    }

    @Test
    fun causeOnlyLegacyEpisodeRepairsFromPersistedAuthorityBeforeEscalation() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L),
            fixture::deliver
        )
        val event = fixture.store.event(opened.episodeId!!)!!
        fixture.store.upsertEvent(
            event.copy(
                causeCode = AlertCauseCode.SENSOR_QUALITY.name,
                causeSummary = AlertCauseAnalyzer.fixedAdvice(AlertCauseCode.SENSOR_QUALITY)
            )
        )

        fixture.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 2_000L).copy(
                causeAnalysis = cause(AlertCauseCode.EVENT_CONTEXT),
                causeSnapshot = snapshot(AlertCauseCode.EVENT_CONTEXT, "EVENT_STRESS")
            ),
            fixture::deliver
        )

        val repaired = fixture.store.event(opened.episodeId!!)!!
        assertThat(fixture.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
        assertThat(repaired.causeCode).isEqualTo(AlertCauseCode.SENSOR_QUALITY.name)
        assertThat(repaired.localSnapshotJson).contains("\"cause\":\"SENSOR_QUALITY\"")
        assertThat(repaired.localSnapshotJson).doesNotContain("EVENT_STRESS")
        assertThat(repaired.localSnapshotJson.length).isAtMost(4_096)

        fixture.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 3_000L).copy(
                causeAnalysis = cause(AlertCauseCode.MEAL_UAM),
                causeSnapshot = snapshot(AlertCauseCode.MEAL_UAM, "UAM_ACTIVE")
            ),
            fixture::deliver
        )
        assertThat(fixture.store.event(opened.episodeId!!)?.localSnapshotJson)
            .isEqualTo(repaired.localSnapshotJson)
    }

    @Test
    fun mismatchedLegacySnapshotIsRepairedToPersistedCause() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L),
            fixture::deliver
        )
        val event = fixture.store.event(opened.episodeId!!)!!
        fixture.store.upsertEvent(
            event.copy(
                causeCode = AlertCauseCode.TARGET_RESPONSE.name,
                localSnapshotJson =
                    """{"initialStage":"WARNING_30","version":1,"cause":"EVENT_CONTEXT"}"""
            )
        )

        fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, 2_000L), fixture::deliver)

        val repaired = fixture.store.event(opened.episodeId!!)!!
        assertThat(fixture.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.TARGET_RESPONSE)
        assertThat(repaired.localSnapshotJson).contains("\"cause\":\"TARGET_RESPONSE\"")
        assertThat(repaired.localSnapshotJson).doesNotContain("EVENT_CONTEXT")
    }

    @Test
    fun malformedLegacySnapshotIsRepairedToPersistedCause() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L),
            fixture::deliver
        )
        val event = fixture.store.event(opened.episodeId!!)!!
        fixture.store.upsertEvent(
            event.copy(
                causeCode = AlertCauseCode.CIRCADIAN_PATTERN.name,
                localSnapshotJson = "{malformed"
            )
        )

        fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, 2_000L), fixture::deliver)

        val repaired = fixture.store.event(opened.episodeId!!)!!
        assertThat(fixture.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.CIRCADIAN_PATTERN)
        assertThat(repaired.localSnapshotJson).contains("\"initialStage\":\"WARNING_30\"")
        assertThat(repaired.localSnapshotJson).contains("\"cause\":\"CIRCADIAN_PATTERN\"")
    }

    @Test
    fun ordinaryEpisodeParserFailureRepairsFromPersistedCause() = runBlocking<Unit> {
        var failParsing = false
        val fixture = fixture(parseAlertSnapshot = { json ->
            if (failParsing) throw IllegalArgumentException("malformed legacy parser input")
            JsonParser.parseString(json).asJsonObject
        })
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
            ),
            fixture::deliver
        )
        failParsing = true

        fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 2_000L),
            fixture::deliver
        )

        val repaired = fixture.store.event(opened.episodeId!!)!!
        assertThat(repaired.causeCode).isEqualTo(AlertCauseCode.SENSOR_QUALITY.name)
        assertThat(repaired.localSnapshotJson).contains("\"cause\":\"SENSOR_QUALITY\"")
        assertThat(repaired.localSnapshotJson).contains("LEGACY_REPAIRED")
    }

    @Test
    fun episodeParserRethrowsCancellationAndFatalErrors() = runBlocking<Unit> {
        listOf(
            CancellationException("cancel episode parser"),
            SimulatedEpisodeVmError(),
            ThreadDeath()
        ).forEach { failure ->
            var failParsing = false
            val fixture = fixture(parseAlertSnapshot = { json ->
                if (failParsing) throw failure
                JsonParser.parseString(json).asJsonObject
            })
            fixture.machine.coordinate(
                low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                    causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                    causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
                ),
                fixture::deliver
            )
            failParsing = true

            var escaped: Throwable? = null
            try {
                fixture.machine.coordinate(
                    low(GlucoseAlertState.WARNING_30, 2_000L),
                    fixture::deliver
                )
            } catch (caught: Throwable) {
                escaped = caught
            }

            assertThat(escaped).isSameInstanceAs(failure)
        }
    }

    @Test
    fun unknownLegacyCauseNormalizesToUnknownWithMatchingRepairSnapshot() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L),
            fixture::deliver
        )
        val event = fixture.store.event(opened.episodeId!!)!!
        fixture.store.upsertEvent(event.copy(causeCode = "FUTURE_CAUSE_CODE"))

        fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, 2_000L), fixture::deliver)

        assertThat(fixture.lastClaim?.kind).isEqualTo(AlertDeliveryKind.LOW_NOW_ESCALATION)
        assertThat(fixture.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.UNKNOWN)
        val repaired = fixture.store.event(opened.episodeId!!)!!
        assertThat(repaired.causeCode).isEqualTo(AlertCauseCode.UNKNOWN.name)
        assertThat(repaired.localSnapshotJson).contains("\"cause\":\"UNKNOWN\"")
        assertThat(repaired.causeSummary).isEqualTo(AlertCauseAnalyzer.fixedAdvice(AlertCauseCode.UNKNOWN))
    }

    @Test
    fun legacyNullCauseAndMinimalSnapshotAreFilledExactlyOnce() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.WATCH_60, 1_000L),
            fixture::deliver
        )

        fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 2_000L).copy(
                causeAnalysis = cause(AlertCauseCode.EVENT_CONTEXT),
                causeSnapshot = snapshot(AlertCauseCode.EVENT_CONTEXT, "EVENT_STRESS")
            ),
            fixture::deliver
        )
        fixture.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 3_000L).copy(
                causeAnalysis = cause(AlertCauseCode.DELIVERY_NONRESPONSE),
                causeSnapshot = snapshot(
                    AlertCauseCode.DELIVERY_NONRESPONSE,
                    "DELIVERY_SUSPECTED_NONRESPONSE"
                )
            ),
            fixture::deliver
        )

        val persisted = fixture.store.event(opened.episodeId!!)
        assertThat(persisted?.causeCode).isEqualTo(AlertCauseCode.EVENT_CONTEXT.name)
        assertThat(persisted?.localSnapshotJson).contains("\"initialStage\":\"WATCH_60\"")
        assertThat(persisted?.localSnapshotJson).contains("EVENT_STRESS")
        assertThat(persisted?.localSnapshotJson).doesNotContain("DELIVERY_SUSPECTED_NONRESPONSE")
    }

    @Test
    fun causeAndSnapshotPersistAtomicallyOrNeither() = runBlocking<Unit> {
        val causeOnly = fixture()
        val causeOnlyResult = causeOnly.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY)
            ),
            causeOnly::deliver
        )
        val causeOnlyEvent = causeOnly.store.event(causeOnlyResult.episodeId!!)

        val snapshotOnly = fixture()
        val snapshotOnlyResult = snapshotOnly.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
            ),
            snapshotOnly::deliver
        )
        val snapshotOnlyEvent = snapshotOnly.store.event(snapshotOnlyResult.episodeId!!)

        assertThat(causeOnlyEvent?.causeCode).isNull()
        assertThat(causeOnlyEvent?.causeSummary).isNull()
        assertThat(causeOnlyEvent?.localSnapshotJson).doesNotContain("\"cause\"")
        assertThat(snapshotOnlyEvent?.causeCode).isNull()
        assertThat(snapshotOnlyEvent?.causeSummary).isNull()
        assertThat(snapshotOnlyEvent?.localSnapshotJson).doesNotContain("\"cause\"")
        assertThat(snapshotOnlyEvent?.localSnapshotJson).doesNotContain("SENSOR_BLOCKED")
    }

    @Test
    fun malformedSnapshotCauseTypeIsRejectedWithoutBlockingInitialDelivery() = runBlocking<Unit> {
        val fixture = fixture()

        val result = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                causeSnapshot = AlertCauseSnapshot("""{"version":1,"cause":{"private":"value"}}""")
            ),
            fixture::deliver
        )

        val event = fixture.store.event(result.episodeId!!)
        assertThat(result.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(event?.causeCode).isNull()
        assertThat(event?.localSnapshotJson).doesNotContain("private")
    }

    @Test
    fun episodePersistenceKeepsOnlySanitizedSnapshotSchemaAndInitialStage() = runBlocking<Unit> {
        val fixture = fixture()
        val maliciousSnapshot = AlertCauseSnapshot(
            """{
                "version":1,
                "cause":"SENSOR_QUALITY",
                "confidence":"HIGH",
                "factors":["SENSOR_QUALITY"],
                "evidence":["SENSOR_BLOCKED"],
                "identityStatus":"MATCHED",
                "unknownRoot":"private root text",
                "eventId":"550e8400-e29b-41d4-a716-446655440000",
                "glucose":{"currentMmol":3.8,"note":"private note","nestedId":"secret-id"},
                "sensitivity":{"settingsRevision":42,"cycleId":"private-cycle"},
                "uam":{"timestamp":1900000000000,"state":"ACTIVE","sensitivityCycleId":"private-uam-cycle"}
            }""".trimIndent()
        )

        val result = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                causeSnapshot = maliciousSnapshot
            ),
            fixture::deliver
        )

        val persisted = fixture.store.event(result.episodeId!!)!!.localSnapshotJson
        assertThat(persisted).contains("\"initialStage\":\"WARNING_30\"")
        assertThat(persisted).contains("\"cause\":\"SENSOR_QUALITY\"")
        assertThat(persisted).contains("\"currentMmol\":3.8")
        assertThat(persisted).contains("\"settingsRevision\":42")
        assertThat(persisted).doesNotContain("unknownRoot")
        assertThat(persisted).doesNotContain("eventId")
        assertThat(persisted).doesNotContain("nestedId")
        assertThat(persisted).doesNotContain("cycleId")
        assertThat(persisted).doesNotContain("sensitivityCycleId")
        assertThat(persisted).doesNotContain("private")
        assertThat(persisted).doesNotContain("secret-id")
        assertThat(persisted.length).isAtMost(4_096)
    }

    @Test
    fun deterministicAnalyzerFailurePersistsCompleteNewEpisodePayload() = runBlocking<Unit> {
        val fixture = fixture()
        val result = fixture.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                causeAnalysis = AlertCauseAnalyzer.failureAnalysis(),
                causeSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
            ),
            fixture::deliver
        )

        val event = fixture.store.event(result.episodeId!!)
        assertThat(fixture.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(event?.causeCode).isEqualTo(AlertCauseCode.DATA_INCOMPLETE.name)
        assertThat(event?.localSnapshotJson).contains("\"cause\":\"DATA_INCOMPLETE\"")
        assertThat(event?.localSnapshotJson).contains("CURRENT_EVIDENCE_MISSING")
    }

    @Test
    fun matchingLegacyCauseWithoutPayloadIsCompletedOnceAfterFailureAndSurvivesRestart() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val firstProcess = fixture(store)
        val opened = firstProcess.machine.coordinate(
            low(GlucoseAlertState.WATCH_60, 1_000L),
            firstProcess::deliver
        )
        val legacy = store.event(opened.episodeId!!)!!.copy(
            causeCode = AlertCauseCode.DATA_INCOMPLETE.name,
            causeSummary = AlertCauseAnalyzer.fixedAdvice(AlertCauseCode.DATA_INCOMPLETE),
            localSnapshotJson = """{"initialStage":"WATCH_60"}"""
        )
        store.upsertEvent(legacy)

        val restarted = fixture(store)
        restarted.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 2_000L).copy(
                causeAnalysis = AlertCauseAnalyzer.failureAnalysis(),
                causeSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
            ),
            restarted::deliver
        )
        val completed = store.event(opened.episodeId!!)!!
        assertThat(completed.causeCode).isEqualTo(AlertCauseCode.DATA_INCOMPLETE.name)
        assertThat(completed.localSnapshotJson).contains("\"cause\":\"DATA_INCOMPLETE\"")

        fixture(store).machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 3_000L).copy(
                causeAnalysis = cause(AlertCauseCode.EVENT_CONTEXT),
                causeSnapshot = snapshot(AlertCauseCode.EVENT_CONTEXT, "EVENT_ACTIVITY")
            )
        ) { AlertSideEffectResult.delivered() }
        val nextCycle = store.event(opened.episodeId!!)!!
        assertThat(nextCycle.causeCode).isEqualTo(AlertCauseCode.DATA_INCOMPLETE.name)
        assertThat(nextCycle.localSnapshotJson).contains("\"cause\":\"DATA_INCOMPLETE\"")
        assertThat(nextCycle.localSnapshotJson).doesNotContain("EVENT_ACTIVITY")
    }

    @Test
    fun episodeResolvesAfterFifteenSafeMinutes() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), fixture::deliver)

        fixture.machine.coordinate(safe(2_000L), fixture::deliver)
        fixture.machine.coordinate(safe(2_000L + 15 * 60_000L - 1L), fixture::deliver)
        assertThat(fixture.store.event(opened.episodeId!!)?.status).isEqualTo(AlertEpisodeStatus.SAFE_PENDING.name)

        fixture.machine.coordinate(safe(2_000L + 15 * 60_000L), fixture::deliver)
        val resolved = fixture.store.event(opened.episodeId!!)
        assertThat(resolved?.status).isEqualTo(AlertEpisodeStatus.RESOLVED.name)
        assertThat(resolved?.resolvedAt).isEqualTo(2_000L + 15 * 60_000L)
    }

    @Test
    fun oppositeDirectionCreatesDifferentMonotonicEpisode() = runBlocking<Unit> {
        val fixture = fixture()
        val first = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 10_000L), fixture::deliver)
        val firstClaim = fixture.lastClaim!!
        val second = fixture.machine.coordinate(high(11_000L), fixture::deliver)
        val secondClaim = fixture.lastClaim!!

        assertThat(second.episodeId).isNotEqualTo(first.episodeId)
        assertThat(episodeSequence(second.episodeId!!)).isGreaterThan(episodeSequence(first.episodeId!!))
        assertThat(secondClaim.notificationTag).isEqualTo(firstClaim.notificationTag)
        assertThat(secondClaim.notificationId).isEqualTo(firstClaim.notificationId)
        assertThat(fixture.store.event(first.episodeId!!)?.status).isEqualTo(AlertEpisodeStatus.RESOLVED.name)
    }

    @Test
    fun processRestartAndReplacementReuseOneVisibleNotificationSlot() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val firstProcess = fixture(store)
        firstProcess.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), firstProcess::deliver)
        val firstClaim = firstProcess.lastClaim!!

        val restarted = fixture(store)
        restarted.machine.coordinate(high(2_000L), restarted::deliver)
        val replacementClaim = restarted.lastClaim!!

        assertThat(replacementClaim.episodeId).isNotEqualTo(firstClaim.episodeId)
        assertThat(replacementClaim.notificationTag).isEqualTo(firstClaim.notificationTag)
        assertThat(replacementClaim.notificationId).isEqualTo(firstClaim.notificationId)
    }

    @Test
    fun muteSuppressesLowNowAndAllRiskSideEffects() = runBlocking<Unit> {
        val fixture = fixture()
        fixture.machine.muteFor(nowTs = 1_000L, durationMs = 30 * 60_000L)

        val result = fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, 2_000L), fixture::deliver)

        assertThat(result.deliveryKind).isEqualTo(AlertDeliveryKind.SUPPRESSED_SNOOZE)
        assertThat(fixture.visibleDeliveries).isEmpty()
        assertThat(fixture.notificationCount).isEqualTo(0)
        assertThat(fixture.vibrationCount).isEqualTo(0)
        assertThat(fixture.audioCount).isEqualTo(0)
        assertThat(fixture.clearSideEffectsCount).isEqualTo(1)
    }

    @Test
    fun exactMuteBoundary30And60() = runBlocking<Unit> {
        val beforeThirty = fixture()
        val beforeUntil30 = beforeThirty.machine.muteFor(1_000L, 30 * 60_000L)
        val beforeBoundary30 = beforeThirty.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, beforeUntil30 - 1L),
            beforeThirty::deliver
        )

        val thirty = fixture()
        val until30 = thirty.machine.muteFor(1_000L, 30 * 60_000L)
        val atBoundary30 = thirty.machine.coordinate(low(GlucoseAlertState.LOW_NOW, until30), thirty::deliver)

        val sixty = fixture()
        val until60 = sixty.machine.muteFor(2_000L, 60 * 60_000L)
        val atBoundary60 = sixty.machine.coordinate(low(GlucoseAlertState.LOW_NOW, until60), sixty::deliver)

        assertThat(beforeBoundary30.deliveryKind).isEqualTo(AlertDeliveryKind.SUPPRESSED_SNOOZE)
        assertThat(beforeThirty.visibleDeliveries).isEmpty()
        assertThat(atBoundary30.deliveryKind).isEqualTo(AlertDeliveryKind.INITIAL)
        assertThat(thirty.visibleDeliveries).hasSize(1)
        assertThat(atBoundary60.deliveryKind).isEqualTo(AlertDeliveryKind.INITIAL)
        assertThat(sixty.visibleDeliveries).hasSize(1)
    }

    @Test
    fun episodeStartedDuringMuteNeverDeliveredLater() = runBlocking<Unit> {
        val fixture = fixture()
        val until = fixture.machine.muteFor(1_000L, 30 * 60_000L)
        val suppressed = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L), fixture::deliver)

        val later = fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, until), fixture::deliver)

        assertThat(later.episodeId).isEqualTo(suppressed.episodeId)
        assertThat(later.deliveryKind).isNull()
        assertThat(fixture.visibleDeliveries).isEmpty()
    }

    @Test
    fun deliveredPredictiveLowAllowsOneLowNowEscalationAfterMute() = runBlocking<Unit> {
        val fixture = fixture()
        val initial = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), fixture::deliver)
        val until = fixture.machine.muteFor(2_000L, 30 * 60_000L)

        fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, until - 1L), fixture::deliver)
        val escalation = fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, until), fixture::deliver)
        val duplicate = fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, until + 1L), fixture::deliver)

        assertThat(escalation.episodeId).isEqualTo(initial.episodeId)
        assertThat(escalation.deliveryKind).isEqualTo(AlertDeliveryKind.LOW_NOW_ESCALATION)
        assertThat(duplicate.deliveryKind).isNull()
        assertThat(fixture.visibleDeliveries.map { it.kind })
            .containsExactly(AlertDeliveryKind.INITIAL, AlertDeliveryKind.LOW_NOW_ESCALATION)
            .inOrder()
    }

    @Test
    fun processRestartCannotDuplicateReceipt() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val first = fixture(store)
        val opened = first.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), first::deliver)
        val restarted = fixture(store)

        val duplicate = restarted.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L), restarted::deliver)

        assertThat(duplicate.episodeId).isEqualTo(opened.episodeId)
        assertThat(duplicate.deliveryKind).isNull()
        assertThat(restarted.visibleDeliveries).isEmpty()
        assertThat(store.receiptsFor(opened.episodeId!!)).hasSize(1)
    }

    @Test
    fun permissionDeniedDoesNotRepeatEveryCycle() = runBlocking<Unit> {
        val fixture = fixture()
        fixture.sideEffectResult = AlertSideEffectResult.failed("notifications_denied")

        val first = fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, 1_000L), fixture::deliver)
        val repeat = fixture.machine.coordinate(low(GlucoseAlertState.LOW_NOW, 2_000L), fixture::deliver)

        assertThat(first.receiptResult).isEqualTo(AlertReceiptResult.FAILED)
        assertThat(repeat.deliveryKind).isNull()
        assertThat(fixture.sideEffectAttempts).isEqualTo(1)
    }

    @Test
    fun legacyActiveImportAlreadyNotifiedAndIdempotent() = runBlocking<Unit> {
        val fixture = fixture()
        val legacy = GlucoseAlertRuntimeState(
            activeAlertState = GlucoseAlertState.WARNING_30,
            activeDirection = GlucoseAlertDirection.LOW,
            lastStageChangeTs = 900L
        )

        val first = fixture.machine.importLegacyIfNeeded(legacy, nowTs = 1_000L)
        val second = fixture.machine.importLegacyIfNeeded(legacy, nowTs = 2_000L)

        assertThat(first).isNotNull()
        assertThat(second).isEqualTo(first)
        assertThat(fixture.store.glucoseEvents()).hasSize(1)
        assertThat(fixture.store.receiptsFor(first!!).single().result)
            .isEqualTo(AlertReceiptResult.DELIVERED.name)
        assertThat(fixture.visibleDeliveries).isEmpty()
    }

    @Test
    fun firstStartupWithNoActiveStateWritesTerminalMarker() = runBlocking<Unit> {
        val fixture = fixture()

        val imported = fixture.machine.importLegacyIfNeeded(GlucoseAlertRuntimeState(), nowTs = 1_000L)

        assertThat(imported).isNull()
        val marker = fixture.store.event(EpisodeAlertDeliveryStateMachine.LEGACY_IMPORT_MARKER_ID)
        assertThat(marker).isNotNull()
        assertThat(marker?.status).isEqualTo(AlertEpisodeStatus.RESOLVED.name)
        assertThat(marker?.causeSummary).isNull()
        assertThat(fixture.store.glucoseEvents()).isEmpty()
    }

    @Test
    fun secondCycleWithActiveDataStoreDoesNotImportAfterTerminalEmptyMarker() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val firstStartup = fixture(store)
        firstStartup.machine.importLegacyIfNeeded(GlucoseAlertRuntimeState(), nowTs = 1_000L)
        val runtimeStateWrittenAfterBootstrap = activeLegacyState()

        val restarted = fixture(store)
        val imported = restarted.machine.importLegacyIfNeeded(runtimeStateWrittenAfterBootstrap, nowTs = 2_000L)

        assertThat(imported).isNull()
        assertThat(store.glucoseEvents()).isEmpty()
    }

    @Test
    fun crashAfterRuntimeStatePersistenceCannotSuppressRealInitial() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val firstStartup = fixture(store)
        firstStartup.machine.importLegacyIfNeeded(GlucoseAlertRuntimeState(), nowTs = 1_000L)

        // The engine persisted this runtime state, then the process died before Room delivery.
        val persistedAfterBootstrap = activeLegacyState()
        val restarted = fixture(store)
        restarted.machine.importLegacyIfNeeded(persistedAfterBootstrap, nowTs = 2_000L)
        val delivery = restarted.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 2_000L),
            restarted::deliver
        )

        assertThat(delivery.deliveryKind).isEqualTo(AlertDeliveryKind.INITIAL)
        assertThat(delivery.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(restarted.visibleDeliveries).hasSize(1)
    }

    @Test
    fun activeLegacyAtTrueFirstStartupImportedExactlyOnceAlreadyNotified() = runBlocking<Unit> {
        val fixture = fixture()

        val first = fixture.machine.importLegacyIfNeeded(activeLegacyState(), nowTs = 1_000L)
        val second = fixture.machine.importLegacyIfNeeded(activeLegacyState(), nowTs = 2_000L)

        assertThat(second).isEqualTo(first)
        assertThat(fixture.store.glucoseEvents()).hasSize(1)
        assertThat(fixture.store.receiptsFor(first!!).map { it.result })
            .containsExactly(AlertReceiptResult.DELIVERED.name)
        assertThat(fixture.visibleDeliveries).isEmpty()
    }

    @Test
    fun overviewUsesRoomMuteAfterMirrorCrashAndBellResumes() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val crashing = EpisodeAlertDeliveryStateMachine(
            store = store,
            mirrorMuteUntil = { throw SimulatedProcessDeath() }
        )
        val until = 1_000L + GlucoseAlertMuteOption.MINUTES_30.durationMs

        try {
            crashing.muteFor(1_000L, GlucoseAlertMuteOption.MINUTES_30.durationMs)
        } catch (_: SimulatedProcessDeath) {
            // Simulates process death after the Room transaction but before the DataStore mirror.
        }
        val restarted = EpisodeAlertDeliveryStateMachine(store, clock = { 2_000L })

        assertThat(restarted.mutedUntil.first()).isEqualTo(until)
        assertThat(restarted.currentMutedUntil()).isEqualTo(until)
        val toggle = restarted.toggleFromOverview(nowTs = 2_000L)
        assertThat(toggle.action).isEqualTo(GlucoseAlertBellAction.RESUME)
        assertThat(toggle.previousMutedUntilTs).isEqualTo(until)
        assertThat(toggle.mutedUntilTs).isEqualTo(0L)
        assertThat(restarted.currentMutedUntil()).isEqualTo(0L)
    }

    @Test
    fun receiverMuteCrashAfterRoomCommitKeepsDeliverySuppressed() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        var clearSideEffectsCount = 0
        val crashing = EpisodeAlertDeliveryStateMachine(
            store = store,
            mirrorMuteUntil = { throw SimulatedProcessDeath() },
            clearRiskSideEffects = { clearSideEffectsCount++ }
        )

        try {
            crashing.muteFromNotification(1_000L, GlucoseAlertMuteOption.MINUTES_60.durationMs)
        } catch (_: SimulatedProcessDeath) {
            // Receiver process died before its legacy DataStore mirror completed.
        }
        val restarted = fixture(store)
        val suppressed = restarted.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 2_000L),
            restarted::deliver
        )

        assertThat(restarted.machine.currentMutedUntil())
            .isEqualTo(1_000L + GlucoseAlertMuteOption.MINUTES_60.durationMs)
        assertThat(suppressed.deliveryKind).isEqualTo(AlertDeliveryKind.SUPPRESSED_SNOOZE)
        assertThat(restarted.visibleDeliveries).isEmpty()
        assertThat(clearSideEffectsCount).isEqualTo(1)
    }

    @Test
    fun resumeDoesNotRetroactivelyNotifyActiveSuppressedEpisode() = runBlocking<Unit> {
        val fixture = fixture()
        fixture.machine.muteFor(1_000L, 30 * 60_000L)
        val suppressed = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L), fixture::deliver)

        fixture.machine.resume()
        val afterResume = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 3_000L), fixture::deliver)

        assertThat(afterResume.episodeId).isEqualTo(suppressed.episodeId)
        assertThat(afterResume.deliveryKind).isNull()
        assertThat(fixture.visibleDeliveries).isEmpty()
    }

    @Test
    fun resolvedThenIdenticalValuesCreateNewEpisodeId() = runBlocking<Unit> {
        val fixture = fixture()
        val first = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), fixture::deliver)
        fixture.machine.coordinate(safe(2_000L), fixture::deliver)
        fixture.machine.coordinate(safe(2_000L + 15 * 60_000L), fixture::deliver)

        val second = fixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L + 15 * 60_000L + 1L), fixture::deliver)

        assertThat(second.episodeId).isNotEqualTo(first.episodeId)
        assertThat(episodeSequence(second.episodeId!!)).isGreaterThan(episodeSequence(first.episodeId!!))
    }

    @Test
    fun repeatedMuteOnlyExtendsExistingExpiry() = runBlocking<Unit> {
        val fixture = fixture()
        val firstUntil = fixture.machine.muteFor(1_000L, 60 * 60_000L)

        val shorterRequest = fixture.machine.muteFor(2_000L, 30 * 60_000L)
        val longerRequest = fixture.machine.muteFor(3_000L, 2 * 60 * 60_000L)

        assertThat(shorterRequest).isEqualTo(firstUntil)
        assertThat(longerRequest).isEqualTo(3_000L + 2 * 60 * 60_000L)
    }

    @Test
    fun staleClaimRecoveryReusesStableNotificationIdentity() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val crashed = fixture(store)
        crashed.crashBeforeFinalization = true
        var processDied = false
        try {
            crashed.machine.coordinate(
                low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                    causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                    causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
                ),
                crashed::deliver
            )
        } catch (_: SimulatedProcessDeath) {
            processDied = true
        }
        val initialClaim = crashed.lastClaim!!
        assertThat(processDied).isTrue()
        assertThat(store.receiptsFor(initialClaim.episodeId).single().result)
            .isEqualTo(AlertReceiptResult.CLAIMED.name)
        val restarted = fixture(store)

        val recovered = restarted.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS).copy(
                causeAnalysis = cause(AlertCauseCode.EVENT_CONTEXT),
                causeSnapshot = snapshot(AlertCauseCode.EVENT_CONTEXT, "EVENT_STRESS")
            ),
            restarted::deliver
        )

        assertThat(recovered.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(restarted.lastClaim?.notificationTag).isEqualTo(initialClaim.notificationTag)
        assertThat(restarted.lastClaim?.notificationId).isEqualTo(initialClaim.notificationId)
        assertThat(restarted.lastClaim?.persistedCause).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
    }

    @Test
    fun staleInitialRecoveryKeepsOriginalStageThenAllowsOneLowNowEscalation() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val crashed = fixture(store).apply { crashBeforeFinalization = true }
        try {
            crashed.machine.coordinate(
                low(GlucoseAlertState.WARNING_30, 1_000L).copy(
                    causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                    causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
                ),
                crashed::deliver
            )
        } catch (_: SimulatedProcessDeath) {
            // Simulates process death after the initial claim was committed.
        }
        val restarted = fixture(store)

        val recovered = restarted.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 1_000L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS).copy(
                causeAnalysis = cause(AlertCauseCode.MEAL_UAM),
                causeSnapshot = snapshot(AlertCauseCode.MEAL_UAM, "UAM_ACTIVE")
            ),
            restarted::deliver
        )
        val recoveredClaim = restarted.lastClaim
        val escalation = restarted.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 1_001L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS),
            restarted::deliver
        )
        val escalationClaim = restarted.lastClaim
        val duplicate = restarted.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 1_002L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS),
            restarted::deliver
        )

        assertThat(recovered.deliveryKind).isEqualTo(AlertDeliveryKind.INITIAL)
        assertThat(recoveredClaim?.claimedStage).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(recoveredClaim?.persistedCause).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
        assertThat(escalation.deliveryKind).isEqualTo(AlertDeliveryKind.LOW_NOW_ESCALATION)
        assertThat(escalationClaim?.claimedStage).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(duplicate.deliveryKind).isNull()
        assertThat(store.receiptsFor(recovered.episodeId!!).map { it.kind })
            .containsExactly(AlertDeliveryKind.INITIAL.name, AlertDeliveryKind.LOW_NOW_ESCALATION.name)
            .inOrder()
    }

    @Test
    fun malformedPersistedLowNowSnapshotRepairsWithoutFalseEscalation() = runBlocking<Unit> {
        val fixture = fixture()
        val opened = fixture.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 1_000L).copy(
                causeAnalysis = cause(AlertCauseCode.SENSOR_QUALITY),
                causeSnapshot = snapshot(AlertCauseCode.SENSOR_QUALITY, "SENSOR_BLOCKED")
            ),
            fixture::deliver
        )
        val event = fixture.store.event(opened.episodeId!!)!!
        fixture.store.upsertEvent(event.copy(localSnapshotJson = "{malformed"))

        val next = fixture.machine.coordinate(
            low(GlucoseAlertState.LOW_NOW, 2_000L).copy(
                causeAnalysis = cause(AlertCauseCode.MEAL_UAM),
                causeSnapshot = snapshot(AlertCauseCode.MEAL_UAM, "UAM_ACTIVE")
            ),
            fixture::deliver
        )
        val repaired = fixture.store.event(opened.episodeId!!)!!

        assertThat(next.deliveryKind).isNull()
        assertThat(repaired.localSnapshotJson).contains("\"initialStage\":\"LOW_NOW\"")
        assertThat(repaired.localSnapshotJson).contains("\"cause\":\"SENSOR_QUALITY\"")
        assertThat(fixture.store.receiptsFor(opened.episodeId!!).map { it.kind })
            .containsExactly(AlertDeliveryKind.INITIAL.name)
    }

    @Test
    fun cancellationDuringDeliveryLeavesClaimForLeaseRecovery() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val cancelledProcess = fixture(store)
        var cleanupRan = false
        var cancellationRethrown = false

        try {
            cancelledProcess.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L)) {
                try {
                    throw CancellationException("delivery scope cancelled")
                } finally {
                    cleanupRan = true
                }
            }
        } catch (_: CancellationException) {
            cancellationRethrown = true
        }

        val cancelledEpisodeId = store.glucoseEvents().single().episodeId
        val claimed = store.receiptsFor(cancelledEpisodeId).single()
        assertThat(cleanupRan).isTrue()
        assertThat(cancellationRethrown).isTrue()
        assertThat(claimed.result).isEqualTo(AlertReceiptResult.CLAIMED.name)

        val recovered = fixture(store)
        val result = recovered.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 1_000L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS),
            recovered::deliver
        )

        assertThat(result.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(store.receiptsFor(claimed.episodeId)).hasSize(1)
    }

    @Test
    fun parentTimeoutEscapesAudioTimeoutAndClaimRecoversOnce() = runTest {
        val store = FakeEpisodeAlertReceiptStore()
        val timedOutProcess = fixture(store)
        var deliveryReturned = false
        var timeoutEscaped = false

        try {
            withTimeout(100L) {
                timedOutProcess.machine.coordinate(low(GlucoseAlertState.WARNING_30, 2_000L)) {
                    val result = deliverConfirmedGlucoseAlertChannels(
                        notificationPermissionGranted = false,
                        postNotification = { error("notification must not be attempted") },
                        invokeVibration = { false },
                        audioStartTimeoutMs = 1_000L,
                        startAudio = { awaitCancellation() }
                    )
                    deliveryReturned = true
                    result.toAlertSideEffectResult()
                }
            }
        } catch (_: TimeoutCancellationException) {
            timeoutEscaped = true
        }

        val episodeId = store.glucoseEvents().single().episodeId
        val claimed = store.receiptsFor(episodeId).single()
        assertThat(timeoutEscaped).isTrue()
        assertThat(deliveryReturned).isFalse()
        assertThat(claimed.result).isEqualTo(AlertReceiptResult.CLAIMED.name)

        val recovered = fixture(store)
        val result = recovered.machine.coordinate(
            low(GlucoseAlertState.WARNING_30, 2_000L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS),
            recovered::deliver
        )

        assertThat(result.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(store.receiptsFor(episodeId)).hasSize(1)
        assertThat(store.receiptsFor(episodeId).single().result)
            .isEqualTo(AlertReceiptResult.DELIVERED.name)
    }

    @Test
    fun deletedHistoryStillDoesNotReuseSequenceAfterRestart() = runBlocking<Unit> {
        val store = FakeEpisodeAlertReceiptStore()
        val firstFixture = fixture(store)
        val first = firstFixture.machine.coordinate(low(GlucoseAlertState.WARNING_30, 10_000L), firstFixture::deliver)
        firstFixture.machine.coordinate(safe(11_000L), firstFixture::deliver)
        firstFixture.machine.coordinate(safe(11_000L + 15 * 60_000L), firstFixture::deliver)
        store.deleteResolvedGlucoseEvents()

        val restarted = fixture(store)
        val second = restarted.machine.coordinate(low(GlucoseAlertState.WARNING_30, 1_000L), restarted::deliver)

        assertThat(episodeSequence(second.episodeId!!)).isGreaterThan(episodeSequence(first.episodeId!!))
    }

    private fun fixture(
        store: FakeEpisodeAlertReceiptStore = FakeEpisodeAlertReceiptStore(),
        parseAlertSnapshot: (String) -> JsonObject = { JsonParser.parseString(it).asJsonObject },
        postCommitObserver: EpisodeAlertPostCommitObserver = EpisodeAlertPostCommitObserver {}
    ): Fixture = Fixture(store, parseAlertSnapshot, postCommitObserver)

    private fun low(stage: GlucoseAlertState, nowTs: Long) = EpisodeAlertSignal(stage, GlucoseAlertDirection.LOW, nowTs)

    private fun high(nowTs: Long) = EpisodeAlertSignal(GlucoseAlertState.SOFT_HIGH_RISK, GlucoseAlertDirection.HIGH, nowTs)

    private fun safe(nowTs: Long) = EpisodeAlertSignal(GlucoseAlertState.NONE, null, nowTs)

    private fun activeLegacyState() = GlucoseAlertRuntimeState(
        activeAlertState = GlucoseAlertState.WARNING_30,
        activeDirection = GlucoseAlertDirection.LOW,
        lastStageChangeTs = 900L
    )

    private fun episodeSequence(id: String): Long = id.substringAfterLast('-').toLong()

    private fun cause(code: AlertCauseCode) = AlertCauseAnalysis(
        primary = code,
        factors = listOf(code),
        confidence = CauseConfidence.MEDIUM,
        evidenceCodes = emptyList(),
        shortAdvice = "untrusted test advice"
    )

    private fun snapshot(code: AlertCauseCode, evidenceCode: String) = AlertCauseSnapshot(
        """{"version":1,"cause":"${code.name}","confidence":"MEDIUM","factors":["${code.name}"],"evidence":["$evidenceCode"],"identityStatus":"MATCHED"}"""
    )

    private fun String.changedCause(
        from: AlertCauseCode,
        to: AlertCauseCode,
        fromEvidence: String,
        toEvidence: String
    ): String = replace("\"cause\":\"${from.name}\"", "\"cause\":\"${to.name}\"")
        .replace("\"factors\":[\"${from.name}\"]", "\"factors\":[\"${to.name}\"]")
        .replace("\"$fromEvidence\"", "\"$toEvidence\"")

    private fun compactDecision(kind: GlucoseAlertNotifyKind) = GlucoseAlertDecision(
        state = GlucoseAlertState.LOW_NOW,
        direction = GlucoseAlertDirection.LOW,
        notifyKind = kind,
        nextState = GlucoseAlertRuntimeState(activeAlertState = GlucoseAlertState.LOW_NOW),
        lowThreshold = 4.4,
        highThreshold = 10.0,
        urgentLowThreshold = 4.0,
        pred5 = 3.7,
        pred30 = 3.5,
        pred60 = 3.4,
        ciLow30 = 3.0,
        ciHigh30 = 4.0,
        currentGlucoseMmol = 3.7,
        currentGlucoseFresh = true,
        predictedMinutesToLow = 0,
        trendDelta5Mmol = -0.2,
        softActive = false,
        strongActive = true,
        repeatSuppressedByTrend = false,
        disableReason = null
    )
}

private class Fixture(
    val store: FakeEpisodeAlertReceiptStore,
    parseAlertSnapshot: (String) -> JsonObject,
    postCommitObserver: EpisodeAlertPostCommitObserver
) {
    var notificationCount = 0
    var vibrationCount = 0
    var audioCount = 0
    var clearSideEffectsCount = 0
    var sideEffectAttempts = 0
    var sideEffectResult = AlertSideEffectResult.delivered()
    var crashBeforeFinalization = false
    var lastClaim: EpisodeDeliveryClaim? = null
    val visibleDeliveries = mutableListOf<EpisodeDeliveryClaim>()
    val machine = EpisodeAlertDeliveryStateMachine(
        store = store,
        mirrorMuteUntil = {},
        clearRiskSideEffects = { clearSideEffectsCount++ },
        parseAlertSnapshot = parseAlertSnapshot,
        postCommitObserver = postCommitObserver
    )

    fun deliver(claim: EpisodeDeliveryClaim): AlertSideEffectResult {
        lastClaim = claim
        sideEffectAttempts++
        if (crashBeforeFinalization) throw SimulatedProcessDeath()
        if (sideEffectResult.receiptResult == AlertReceiptResult.DELIVERED) {
            notificationCount++
            vibrationCount++
            audioCount++
            visibleDeliveries += claim
        }
        return sideEffectResult
    }
}

private class SimulatedProcessDeath : Error("simulated process death")

private class SimulatedEpisodeVmError : VirtualMachineError()

private class FakeEpisodeAlertReceiptStore : EpisodeAlertReceiptStore {
    private val events = linkedMapOf<String, AlertEventEntity>()
    private val receipts = linkedMapOf<Pair<String, String>, AlertDeliveryReceiptEntity>()
    private val muteUntilFlow = MutableStateFlow(0L)

    var transactionActive: Boolean = false
        private set

    override suspend fun <T> transaction(block: suspend EpisodeAlertReceiptStore.() -> T): T {
        transactionActive = true
        return try {
            block(this)
        } finally {
            transactionActive = false
        }
    }

    override suspend fun latestUnresolvedGlucoseEpisode(): AlertEventEntity? = events.values
        .filter {
            it.eventType in setOf("GLUCOSE_ALERT_LOW", "GLUCOSE_ALERT_HIGH") &&
                it.status != AlertEpisodeStatus.RESOLVED.name
        }
        .maxByOrNull { it.createdAt }

    override suspend fun maxGlucoseEpisodeSequence(): Long = events.values
        .filter { it.eventType in setOf("GLUCOSE_ALERT_LOW", "GLUCOSE_ALERT_HIGH") }
        .maxOfOrNull { it.createdAt } ?: 0L

    override suspend fun eventById(episodeId: String): AlertEventEntity? = events[episodeId]

    override suspend fun upsertEvent(event: AlertEventEntity) {
        events[event.episodeId] = event
    }

    override suspend fun receipt(episodeId: String, kind: AlertDeliveryKind): AlertDeliveryReceiptEntity? =
        receipts[episodeId to kind.name]

    override suspend fun insertReceipt(receipt: AlertDeliveryReceiptEntity): Boolean {
        val key = receipt.episodeId to receipt.kind
        if (key in receipts) return false
        receipts[key] = receipt
        return true
    }

    override suspend fun updateReceipt(receipt: AlertDeliveryReceiptEntity) {
        receipts[receipt.episodeId to receipt.kind] = receipt
    }

    override suspend fun muteUntil(): Long = events[EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID]?.suppressionUntil ?: 0L

    override fun observeMuteUntil(): Flow<Long> = muteUntilFlow

    override suspend fun writeMuteUntil(until: Long, nowTs: Long) {
        events[EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID] = AlertEventEntity(
            episodeId = EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID,
            eventType = "GLUCOSE_ALERT_MUTE_STATE",
            stage = "MUTE",
            status = if (until > 0L) AlertEpisodeStatus.OPEN.name else AlertEpisodeStatus.RESOLVED.name,
            severity = "MUTE",
            createdAt = events[EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID]?.createdAt ?: nowTs,
            updatedAt = nowTs,
            resolvedAt = if (until > 0L) null else nowTs,
            localSnapshotJson = "{}",
            causeCode = null,
            causeSummary = null,
            suppressionUntil = until,
            lastNotificationAt = null,
            revision = (events[EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID]?.revision ?: 0L) + 1L
        )
        muteUntilFlow.value = until
    }

    fun event(episodeId: String): AlertEventEntity? = events[episodeId]
    fun receiptsFor(episodeId: String): List<AlertDeliveryReceiptEntity> = receipts.values.filter { it.episodeId == episodeId }
    fun glucoseEvents(): List<AlertEventEntity> = events.values.filter { it.eventType in setOf("GLUCOSE_ALERT_LOW", "GLUCOSE_ALERT_HIGH") }
    fun deleteResolvedGlucoseEvents() {
        events.entries.removeAll {
            it.value.eventType in setOf("GLUCOSE_ALERT_LOW", "GLUCOSE_ALERT_HIGH") &&
                it.value.status == AlertEpisodeStatus.RESOLVED.name
        }
    }
}
