package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.CompensationEventProfilePolicy
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.profile.PhysiologicalSex
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class EventTimelineRepositoryTest {
    @Test fun preparedTherapySharesAllSourceDedupAndLifecycleWithoutFreezingStatus() {
        val repo = EventTimelineRepository()
        val base = budgetSources()
        val sources = base.copy(
            therapyEvents = base.therapyEvents + TherapyEvent(
                10, "illness", mapOf("endTs" to (10 + 3 * 86_400_000L).toString()), sourceRowId = "long-interval"
            ),
            calibrationEvents = listOf(CompensationEvent(
                "calibration", 10, 20, CompensationEventType.SENSOR_CALIBRATION, source = EventSource.AAPS
            ))
        )
        val prepared = sources.therapyEvents.mapNotNull(repo::prepareTherapyEvent)
        assertThat(prepared.map { it.localId }).containsExactly("same", "same", "long-interval").inOrder()
        val unchanged = prepared.map { it.copy(attributes = LinkedHashMap(it.attributes)) }
        for (now in listOf(9L, 10L, 20L, 86_400_011L, 3 * 86_400_000L + 10, 10L)) {
            assertThat(repo.aggregatePreparedTherapy(sources.copy(therapyEvents = emptyList()), prepared, now))
                .isEqualTo(repo.aggregate(sources, now))
            assertThat(prepared).isEqualTo(unchanged)
            assertThat(prepared.map { it.status }.distinct()).containsExactly(CompensationEventStatus.ACTIVE)
        }
    }

    @Test fun preparedTherapyAttributesAreDetachedFromPayload() {
        val repo = EventTimelineRepository()
        val payload = linkedMapOf("notes" to "original", "endTs" to "20")
        val source = TherapyEvent(10, "note", payload, sourceRowId = "detached")
        val prepared = requireNotNull(repo.prepareTherapyEvent(source))
        val expected = prepared.copy(attributes = LinkedHashMap(prepared.attributes))

        payload["notes"] = "changed"
        payload.remove("endTs")
        payload["new"] = "entry"

        assertThat(prepared.attributes).isNotSameInstanceAs(payload)
        assertThat(prepared).isEqualTo(expected)
        assertThat(prepared.attributes.keys).containsExactly("endTs", "notes").inOrder()
        assertThat(repo.aggregatePreparedTherapy(EventTimelineSources(), listOf(prepared), 20).single())
            .isEqualTo(expected.copy(status = CompensationEventStatus.CLOSED))
    }

    @Test fun preparedTherapyLifecycleCopiesRejectAttributesMutation() {
        val repo = EventTimelineRepository()
        val source = TherapyEvent(10, "note", mapOf("notes" to "original", "endTs" to "20"))
        val prepared = requireNotNull(repo.prepareTherapyEvent(source))
        val expected = prepared.copy(attributes = LinkedHashMap(prepared.attributes))
        for (now in listOf(9L, 10L, 20L, 10L)) {
            val output = repo.aggregatePreparedTherapy(EventTimelineSources(), listOf(prepared), now).single()
            val reference = repo.aggregate(EventTimelineSources(therapyEvents = listOf(source)), now).single()
            assertThat(output).isEqualTo(reference)
            if (now == 20L) assertThat(output).isNotSameInstanceAs(prepared)
            for (event in listOf(prepared, output)) {
                val attributes = event.attributes as MutableMap<String, String>
                assertThrows(UnsupportedOperationException::class.java) { attributes["notes"] = "changed" }
                assertThrows(UnsupportedOperationException::class.java) { attributes["new"] = "entry" }
                assertThrows(UnsupportedOperationException::class.java) { attributes.entries.first().setValue("changed") }
                assertThrows(UnsupportedOperationException::class.java) { attributes.keys.remove("notes") }
                assertThrows(UnsupportedOperationException::class.java) { attributes.clear() }
            }
            assertThat(prepared).isEqualTo(expected)
            assertThat(output).isEqualTo(reference)
        }
    }

    @Test fun preparedTherapyPathRejectsAmbiguousRawAndPreparedInputs() {
        val repo = EventTimelineRepository()
        val source = budgetSources()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            repo.aggregatePreparedTherapy(source, source.therapyEvents.mapNotNull(repo::prepareTherapyEvent), 100)
        }
    }

    @Test fun uncachedAggregatePreservesEveryInputAndOutputReservationOnEveryCall() {
        val repo = EventTimelineRepository()
        repeat(2) {
            val calls = mutableListOf<String>()
            val result = repo.aggregate(
                budgetSources(), 100,
                reserveInput = { calls += "input:$it" },
                reserveOutput = { calls += "output:$it" }
            )
            assertThat(calls).containsExactly(
                "input:1", "input:1", "input:1", "input:2", "input:1", "input:0", "input:1", "output:5"
            ).inOrder()
            assertThat(result.map { it.localId }).containsExactly("same", "tag", "planned", "actual", "delivery")
        }
    }

    @Test fun uncachedAggregateNeverSkipsZeroReservations() {
        val calls = mutableListOf<String>()
        assertThat(EventTimelineRepository().aggregate(
            EventTimelineSources(), 100,
            reserveInput = { calls += "input:$it" }, reserveOutput = { calls += "output:$it" }
        )).isEmpty()
        assertThat(calls).containsExactly("input:0", "input:0", "input:0", "input:0", "output:0").inOrder()
    }

    @Test fun uncachedAggregatePropagatesBudgetFailuresAtEachExactReservationWithoutFurtherWork() {
        val expected = listOf("input:1", "input:1", "input:1", "input:2", "input:1", "input:0", "input:1", "output:5")
        for (failure in listOf(IllegalStateException("budget"), CancellationException("cancel"), AssertionError("error"))) {
            expected.indices.forEach { failAt ->
                val calls = mutableListOf<String>()
                fun reserve(call: String) {
                    calls += call
                    if (calls.size == failAt + 1) throw failure
                }
                val thrown = runCatching {
                    EventTimelineRepository().aggregate(
                        budgetSources(), 100,
                        reserveInput = { reserve("input:$it") }, reserveOutput = { reserve("output:$it") }
                    )
                }.exceptionOrNull()
                assertThat(thrown).isSameInstanceAs(failure)
                assertThat(calls).containsExactlyElementsIn(expected.take(failAt + 1)).inOrder()
            }
        }
    }

    private fun budgetSources(): EventTimelineSources {
        val echo = CopilotContextNoteMarker.commandHeader("manual:budget", 1, AapsContextEventGateway.Operation.CREATE)
        val note = TherapyEvent(10, "note", mapOf("notes" to "ordinary"), sourceRowId = "same")
        fun event(id: String) = CompensationEvent(id, 10, 20, CompensationEventType.STRESS)
        return EventTimelineSources(
            therapyEvents = listOf(
                note, note, TherapyEvent(10, "bolus", mapOf("units" to "2")),
                TherapyEvent(10, "note", mapOf("notes" to echo))
            ),
            contextTags = listOf(PhysioContextTagEntity("tag", 10, 20, "stress", 0.2, "user", "note")),
            plannedActivity = listOf(event("same"), event("planned")),
            actualActivity = listOf(event("actual")),
            deliveryDiagnostics = listOf(event("delivery"))
        )
    }

    @Test fun productionNotesWinAndMapOrdinaryAapsNoteText() {
        val productionNote = "Production first line\n" + "p".repeat(470)
        val therapy = TherapyEventEntity(
            id = "production-note-row",
            timestamp = 10_000L,
            type = "note",
            payloadJson = Gson().toJson(
                mapOf(
                    "notes" to productionNote,
                    "note" to "legacy text must not win"
                )
            )
        ).toDomain(Gson())

        val event = EventTimelineRepository().aggregate(
            EventTimelineSources(therapyEvents = listOf(therapy)),
            nowTs = 20_000L
        ).single()

        assertThat(event.localId).isEqualTo("production-note-row")
        assertThat(event.title).isEqualTo("Production first line")
        assertThat(event.note).isEqualTo(productionNote.take(500))
        assertThat(event.note).doesNotContain("legacy text")
    }

    @Test fun productionNotesSuppressOnlyExactStartProtectedEcho() {
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "manual:production-notes",
            revision = 3L,
            operation = AapsContextEventGateway.Operation.UPDATE
        )
        fun row(id: String, notes: String) = TherapyEventEntity(
            id = id,
            timestamp = 10_000L,
            type = "note",
            payloadJson = Gson().toJson(mapOf("notes" to notes))
        ).toDomain(Gson())

        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    row("exact-production-echo", "$marker|title|note"),
                    row("ordinary-mid-marker", "ordinary production note $marker|remains ordinary"),
                    TherapyEventEntity(
                        id = "production-key-wins",
                        timestamp = 11_000L,
                        type = "note",
                        payloadJson = Gson().toJson(
                            mapOf(
                                "notes" to "ordinary authoritative production note",
                                "note" to "$marker|legacy echo must not win"
                            )
                        )
                    ).toDomain(Gson())
                )
            ),
            nowTs = 20_000L
        )

        assertThat(result.map { it.localId })
            .containsExactly("ordinary-mid-marker", "production-key-wins").inOrder()
        assertThat(result.first().title).isEqualTo("ordinary production note |remains ordinary")
        assertThat(result.first().note).contains("ordinary production note")
        assertThat(result.first().note).contains(marker)
        assertThat(result.last().note).isEqualTo("ordinary authoritative production note")
    }

    @Test fun protectedCopilotContextNoteEchoIsMergedWithOriginatingContext() {
        val localId = "manual:stress-roundtrip"
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = localId,
            revision = 2L,
            operation = AapsContextEventGateway.Operation.CREATE
        )
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEvent(10L, "note", mapOf("eventId" to "aaps-note-42", "note" to "$marker|Stress|private note")),
                    TherapyEvent(11L, "note", mapOf("eventId" to "ordinary-note", "note" to "ordinary AAPS note")),
                    TherapyEvent(12L, "note", mapOf("eventId" to "legacy-r-lookalike", "note" to "COPILOT_CONTEXT_V1|0123456789abcdef|r=2|Stress")),
                    TherapyEvent(13L, "note", mapOf("eventId" to "base-only-lookalike", "note" to "${CopilotContextNoteMarker.baseMarker(localId)}|Stress")),
                    TherapyEvent(14L, "note", mapOf("eventId" to "wrong-hash-lookalike", "note" to "COPILOT_CONTEXT_V1|0123456789abcdef|id=copilot:$localId|rev=2|op=CREATE|Stress"))
                ),
                contextTags = listOf(
                    PhysioContextTagEntity(localId, 10L, 20L, "stress", 0.5, "user", "private note", revision = 2L)
                )
            ),
            nowTs = 15L
        )

        assertThat(result.map { it.localId }).containsExactly(
            localId,
            "ordinary-note",
            "legacy-r-lookalike",
            "base-only-lookalike",
            "wrong-hash-lookalike"
        ).inOrder()
        assertThat(result.single { it.localId == localId }.source).isEqualTo(EventSource.USER)
        assertThat(result.joinToString()).doesNotContain(marker)
        assertThat(ContextEventSyncCoordinator.isExportEligible(result.single { it.localId == "ordinary-note" })).isFalse()
    }

    @Test fun distinctAapsRowsAtSameTimestampAndTypeKeepTypedSourceIdentity() {
        val therapy = listOf("row-one", "row-two").map { rowId ->
            TherapyEventEntity(
                id = rowId,
                timestamp = 10_000L,
                type = "note",
                payloadJson = """{"note":"same ordinary note"}"""
            ).toDomain(Gson())
        }

        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(therapyEvents = therapy),
            nowTs = 20_000L
        )

        assertThat(result.map { it.localId }).containsExactly("row-one", "row-two")
    }

    @Test fun ordinaryAapsNotePreservesBoundedTitleAndFullLocalNoteText() {
        val note = "First meaningful line\n" + "n".repeat(470)
        val therapy = TherapyEventEntity(
            id = "ordinary-row",
            timestamp = 10_000L,
            type = "note",
            payloadJson = Gson().toJson(mapOf("note" to note))
        ).toDomain(Gson())

        val event = EventTimelineRepository().aggregate(
            EventTimelineSources(therapyEvents = listOf(therapy)),
            nowTs = 20_000L
        ).single()

        assertThat(event.localId).isEqualTo("ordinary-row")
        assertThat(event.title).isEqualTo("First meaningful line")
        assertThat(event.note).isEqualTo(note.take(500))
    }

    @Test fun echoSuppressionUsesOnlyAuthoritativeNoteFieldAtExactStart() {
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "manual:exact-field",
            revision = 1L,
            operation = AapsContextEventGateway.Operation.CREATE
        )
        fun row(id: String, payload: Map<String, String>) = TherapyEventEntity(
            id = id,
            timestamp = 10_000L,
            type = "note",
            payloadJson = Gson().toJson(payload)
        ).toDomain(Gson())
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    row("exact", mapOf("note" to "$marker|title|note")),
                    row("mid-note", mapOf("note" to "ordinary prefix $marker|title")),
                    row("leading-space", mapOf("note" to " $marker|title")),
                    row("other-field", mapOf("note" to "ordinary note", "enteredBy" to marker)),
                    row("malformed", mapOf("note" to marker.replace("|rev=1", "|rev=0")))
                )
            ),
            nowTs = 20_000L
        )

        assertThat(result.map { it.localId }).containsExactly(
            "mid-note",
            "leading-space",
            "other-field",
            "malformed"
        )
        assertThat(result.single { it.localId == "mid-note" }.note).contains(marker)
        assertThat(result.single { it.localId == "other-field" }.note).isEqualTo("ordinary note")
    }

    @Test fun protectedMarkerParserAcceptsOnlyBoundedSharedContractGrammar() {
        val localId = "tag-123e4567-e89b-12d3-a456-426614174000"
        val exact = CopilotContextNoteMarker.commandHeader(
            localEventId = localId,
            revision = 7L,
            operation = AapsContextEventGateway.Operation.UPDATE
        ) + "|title|note"

        assertThat(CopilotContextNoteMarker.parseAtStart(exact)?.localEventId).isEqualTo(localId)
        assertThat(CopilotContextNoteMarker.containsProtectedMarker(exact)).isTrue()
        assertThat(CopilotContextNoteMarker.containsProtectedMarker(CopilotContextNoteMarker.baseMarker(localId))).isTrue()
        listOf(
            "COPILOT_CONTEXT_V1|0123456789abcdef|r=7|title",
            CopilotContextNoteMarker.baseMarker(localId),
            exact.replace("copilot:", "copilot:/"),
            exact.replace("|rev=7", "|rev=0"),
            exact.replace("|op=UPDATE", "|op=UPSERT"),
            exact.replace(AapsContextEventGateway.localEventHash(localId).take(16), "0123456789abcdef")
        ).forEach { lookalike ->
            assertThat(CopilotContextNoteMarker.parseAtStart(lookalike)).isNull()
        }
    }

    @Test fun protectedMarkerOperationParsingFallsBackOnlyForOrdinaryExceptions() {
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "event-fatal-boundary",
            revision = 1L,
            operation = AapsContextEventGateway.Operation.CREATE
        )
        assertThat(
            CopilotContextNoteMarker.parseAtStart(marker) {
                throw IllegalArgumentException("malformed operation")
            }
        ).isNull()

        listOf(
            CancellationException("cancel operation parser"),
            SimulatedContextMarkerVmError(),
            ThreadDeath()
        ).forEach { failure ->
            var escaped: Throwable? = null
            try {
                CopilotContextNoteMarker.parseAtStart(marker) { throw failure }
            } catch (caught: Throwable) {
                escaped = caught
            }
            assertThat(escaped).isSameInstanceAs(failure)
        }
    }

    @Test fun deliveryDiagnosticsOnlyProjectSuspectedNonresponseAsInfusionContext() {
        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(10L, "runtime", DeliveryTrustState.NORMAL),
                DeliveryDiagnosticTimelineSample(20L, "runtime", DeliveryTrustState.WATCH),
                DeliveryDiagnosticTimelineSample(30L, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE)
            )
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().type).isEqualTo(CompensationEventType.INFUSION_PUMP_INSULIN)
        assertThat(result.single().subtype).isEqualTo("POSSIBLE_DELIVERY_NONRESPONSE")
        assertThat(result.single().title).contains("Possible")
        assertThat(result.single().source).isEqualTo(EventSource.AUTOMATIC)
    }

    @Test fun deliveryEventIdsPreserveCompleteExactSourceIdentity() {
        val timestamp = 42L
        val lower = stableDeliveryEventLocalId("pump-alpha", timestamp)
        val upper = stableDeliveryEventLocalId("Pump-alpha", timestamp)
        val sharedPrefixA = stableDeliveryEventLocalId("123456789012345678901234-source-a", timestamp)
        val sharedPrefixB = stableDeliveryEventLocalId("123456789012345678901234-source-b", timestamp)

        assertThat(lower).isNotEqualTo(upper)
        assertThat(sharedPrefixA).isNotEqualTo(sharedPrefixB)
        assertThat(stableDeliveryEventLocalId("pump-alpha", timestamp)).isEqualTo(lower)
        assertThat(lower).matches("delivery:[0-9a-f]{64}:42")
    }

    @Test fun distinctDeliverySourcesNeverAggregateWhenCaseOrLongPrefixDiffer() {
        val timestamp = 42L
        val sources = listOf(
            "pump-alpha",
            "Pump-alpha",
            "123456789012345678901234-source-a",
            "123456789012345678901234-source-b"
        )

        val events = EventTimelineRepository().aggregate(
            EventTimelineSources(
                deliveryDiagnostics = EventTimelineRepository().deliveryDiagnosticEvents(
                    sources.map { source ->
                        DeliveryDiagnosticTimelineSample(
                            timestamp = timestamp,
                            source = source,
                            state = DeliveryTrustState.SUSPECTED_NONRESPONSE
                        )
                    }
                )
            ),
            nowTs = timestamp
        )

        assertThat(events).hasSize(sources.size)
        assertThat(events.map { it.localId }.distinct()).hasSize(sources.size)
    }

    @Test fun repeatedDeliveryTrustSamplesCollapseIntoDeterministicEpisodes() {
        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(10L, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(10L + 5L * 60_000L, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(20L * 60_000L, "runtime", DeliveryTrustState.NORMAL),
                DeliveryDiagnosticTimelineSample(21L * 60_000L, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE)
            )
        )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.localId }).containsExactly(
            stableDeliveryEventLocalId("runtime", 10L),
            stableDeliveryEventLocalId("runtime", 21L * 60_000L)
        ).inOrder()
        assertThat(result.first().endTs).isEqualTo(20L * 60_000L)
        assertThat(result.first().status).isEqualTo(CompensationEventStatus.CLOSED)
        assertThat(result.last().status).isEqualTo(CompensationEventStatus.ACTIVE)
    }

    @Test fun deliveryRecoveryIsDeterministicAcrossSourcesOrderAndDuplicates() {
        val minute = 60_000L
        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(20L * minute, "pump-b", DeliveryTrustState.NORMAL),
                DeliveryDiagnosticTimelineSample(10L * minute, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(10L * minute, "pump-b", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(15L * minute, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(15L * minute, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(20L * minute, "pump-a", DeliveryTrustState.WATCH),
                DeliveryDiagnosticTimelineSample(25L * minute, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE)
            )
        )

        assertThat(result.map { it.localId }).containsExactly(
            stableDeliveryEventLocalId("pump-a", 10L * minute),
            stableDeliveryEventLocalId("pump-b", 10L * minute),
            stableDeliveryEventLocalId("pump-a", 25L * minute)
        ).inOrder()
        assertThat(result[0].endTs).isEqualTo(20L * minute)
        assertThat(result[0].status).isEqualTo(CompensationEventStatus.CLOSED)
        assertThat(result[1].endTs).isEqualTo(20L * minute)
        assertThat(result[1].status).isEqualTo(CompensationEventStatus.CLOSED)
        assertThat(result[2].status).isEqualTo(CompensationEventStatus.ACTIVE)
    }

    @Test fun recoveryClosesEveryStillOpenEpisodeForTheSameSource() {
        val minute = 60_000L
        val recoveryTs = 50L * minute

        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(0L, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(40L * minute, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(recoveryTs, "pump-a", DeliveryTrustState.NORMAL)
            )
        )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.endTs }).containsExactly(recoveryTs, recoveryTs).inOrder()
        assertThat(result.map { it.status })
            .containsExactly(CompensationEventStatus.CLOSED, CompensationEventStatus.CLOSED)
            .inOrder()
    }

    @Test fun recoveryForAnotherSourceDoesNotCloseOpenEpisodes() {
        val minute = 60_000L

        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(0L, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(40L * minute, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(50L * minute, "pump-b", DeliveryTrustState.WATCH)
            )
        )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.status })
            .containsExactly(CompensationEventStatus.ACTIVE, CompensationEventStatus.ACTIVE)
            .inOrder()
    }

    @Test fun recoveryLeavesNaturallyEndedEpisodesUnchanged() {
        val hour = 60L * 60L * 1_000L

        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(0L, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(5L * hour, "pump-a", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(5L * hour + 10L * 60_000L, "pump-a", DeliveryTrustState.NORMAL)
            )
        )

        assertThat(result).hasSize(2)
        assertThat(result.first().endTs).isEqualTo(4L * hour)
        assertThat(result.first().status).isEqualTo(CompensationEventStatus.ACTIVE)
        assertThat(result.last().endTs).isEqualTo(5L * hour + 10L * 60_000L)
        assertThat(result.last().status).isEqualTo(CompensationEventStatus.CLOSED)
    }

    @Test fun sameTimestampRecoveryClosesAZeroDurationEpisodeWithoutReopeningItInAggregate() {
        val repository = EventTimelineRepository()
        val episode = repository.deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(10L, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(10L, "runtime", DeliveryTrustState.NORMAL)
            )
        ).single()

        assertThat(episode.startTs).isEqualTo(10L)
        assertThat(episode.endTs).isEqualTo(10L)
        assertThat(episode.status).isEqualTo(CompensationEventStatus.CLOSED)
        assertThat(
            repository.aggregate(EventTimelineSources(deliveryDiagnostics = listOf(episode)), nowTs = 20L).single()
        ).isEqualTo(episode)
    }

    @Test fun unknownDeliveryStateNeitherClosesNorSplitsAnAdjacentSuspectEpisode() {
        val minute = 60_000L
        val result = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(10L * minute, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE),
                DeliveryDiagnosticTimelineSample(12L * minute, "runtime", DeliveryTrustState.UNKNOWN),
                DeliveryDiagnosticTimelineSample(15L * minute, "runtime", DeliveryTrustState.SUSPECTED_NONRESPONSE)
            )
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().endTs).isEqualTo(15L * minute + 4L * 60L * minute)
        assertThat(result.single().status).isEqualTo(CompensationEventStatus.ACTIVE)
    }

    @Test fun sensorFailuresReceiveDefaultPenaltyButNormalCalibrationDoesNot() {
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEvent(10L, "sensor_failure", mapOf("eventId" to "failure")),
                    TherapyEvent(20L, "sensor_error", mapOf("eventId" to "error")),
                    TherapyEvent(30L, "calibration", mapOf("eventId" to "calibration"))
                )
            ),
            nowTs = 40L
        )

        assertThat(result.first { it.localId == "failure" }.attributes["trustPenalty"]?.toDouble()).isGreaterThan(0.0)
        assertThat(result.first { it.localId == "error" }.attributes["trustPenalty"]?.toDouble()).isGreaterThan(0.0)
        assertThat(result.first { it.localId == "calibration" }.attributes["trustPenalty"]?.toDoubleOrNull() ?: 0.0).isEqualTo(0.0)
    }
    @Test fun menstrualCycleCreationIsRejectedForNonFemaleProfiles() {
        assertThat(CompensationEventProfilePolicy.canCreate(CompensationEventType.MENSTRUAL_CYCLE, PhysiologicalSex.MALE)).isFalse()
        assertThat(CompensationEventProfilePolicy.canCreate(CompensationEventType.MENSTRUAL_CYCLE, PhysiologicalSex.UNSPECIFIED)).isFalse()
        assertThat(CompensationEventProfilePolicy.canCreate(CompensationEventType.MENSTRUAL_CYCLE, PhysiologicalSex.FEMALE)).isTrue()
    }

    @Test fun deduplicatesByStableLocalIdAndKeepsNewestRevision() {
        val repo = EventTimelineRepository()
        val a = CompensationEvent("same", 10, 20, CompensationEventType.STRESS, revision = 1)
        val b = a.copy(revision = 2, endTs = 30)
        val result = repo.aggregate(EventTimelineSources(plannedActivity = listOf(a, b)), 100)
        assertThat(result).hasSize(1)
        assertThat(result.single().revision).isEqualTo(2)
    }

    @Test fun uamRowsAreNotConvertedIntoRealMeals() {
        val result = EventTimelineRepository().aggregate(EventTimelineSources(therapyEvents = listOf(TherapyEvent(100, "carbs", mapOf("source" to "uam_engine", "grams" to "60")))), 200)
        assertThat(result).hasSize(1)
        assertThat(result.single().type).isEqualTo(CompensationEventType.CUSTOM)
        assertThat(result.single().subtype).isEqualTo("UAM")
        assertThat(result.single().source).isEqualTo(EventSource.AUTOMATIC)
    }

    @Test fun provenanceIsPreservedAndFemaleVisibilityIsDomainOnly() {
        val tag = PhysioContextTagEntity("x", 10, 20, "stress", 0.2, "user", "note", revision = 2)
        val event = EventTimelineRepository().aggregate(EventTimelineSources(contextTags = listOf(tag)), 20).single()
        assertThat(event.provenance).isEqualTo("physio_context_tags")
        val female = event.copy(type = CompensationEventType.MENSTRUAL_CYCLE)
        assertThat(female.visibleFor(io.aaps.copilot.domain.profile.PhysiologicalSex.FEMALE)).isTrue()
        assertThat(female.visibleFor(io.aaps.copilot.domain.profile.PhysiologicalSex.MALE)).isTrue()
        assertThat(female.visibleFor(io.aaps.copilot.domain.profile.PhysiologicalSex.UNSPECIFIED)).isTrue()
    }
    @Test fun equalRevisionUsesAapsThenAutomaticThenUserPrecedence() {
        val same = CompensationEvent("same", 10, 20, CompensationEventType.STRESS, revision = 2)
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(plannedActivity = listOf(
                same.copy(source = EventSource.AUTOMATIC),
                same.copy(source = EventSource.AAPS),
                same.copy(source = EventSource.USER)
            )),
            100
        )
        assertThat(result.single().source).isEqualTo(EventSource.AAPS)
    }

    @Test fun therapyEventsReceiveProfileAndContextDefaultDurations() {
        val source = listOf(
            TherapyEvent(1, "carbs", mapOf("eventId" to "fast", "profile" to "FAST", "grams" to "20")),
            TherapyEvent(2, "exercise", mapOf("eventId" to "activity")),
            TherapyEvent(3, "illness", mapOf("eventId" to "illness"))
        )
        val result = EventTimelineRepository().aggregate(EventTimelineSources(therapyEvents = source), 10)
        assertThat(result.first { it.localId == "fast" }.endTs).isEqualTo(1 + 60 * 60_000L)
        assertThat(result.first { it.localId == "activity" }.endTs).isEqualTo(2 + 60 * 60_000L)
        assertThat(result.first { it.localId == "illness" }.endTs).isEqualTo(3 + 720 * 60_000L)
    }

    @Test fun nonTherapyDurationsAreBoundedWithoutChangingValidExplicitDuration() {
        val start = Long.MAX_VALUE - 1_000L
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(plannedActivity = listOf(
                CompensationEvent("long", 1L, Long.MAX_VALUE, CompensationEventType.STRESS),
                CompensationEvent("valid", 2L, 2L + 3L * 60L * 60L * 1_000L, CompensationEventType.STRESS)
            )),
            start
        )
        assertThat(result.first { it.localId == "long" }.endTs).isEqualTo(1L + 24L * 60L * 60L * 1_000L)
        assertThat(result.first { it.localId == "valid" }.endTs).isEqualTo(2L + 3L * 60L * 60L * 1_000L)
    }

    @Test fun validatedExplicitThreeDayContextIntervalRemainsActiveBeyondDayOne() {
        val day = 24L * 60L * 60L * 1_000L
        val start = 40L * day
        val explicit = CompensationEvent(
            localId = "three-day-context",
            startTs = start,
            endTs = start + 3L * day,
            type = CompensationEventType.STRESS,
            source = EventSource.USER
        )

        val projected = EventTimelineRepository().aggregate(
            EventTimelineSources(plannedActivity = listOf(explicit)),
            nowTs = start + 2L * day
        ).single()

        assertThat(projected.endTs).isEqualTo(start + 3L * day)
        assertThat(projected.isActiveAt(start + 2L * day)).isTrue()
    }

    @Test fun validatedExplicitThreeDayTherapyIntervalIsNotReplacedByInferredCap() {
        val day = 24L * 60L * 60L * 1_000L
        val start = 40L * day

        val projected = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEvent(
                        start,
                        "infusion_problem",
                        mapOf(
                            "eventId" to "three-day-delivery",
                            "endTs" to (start + 3L * day).toString()
                        )
                    )
                )
            ),
            nowTs = start + 2L * day
        ).single()

        assertThat(projected.endTs).isEqualTo(start + 3L * day)
        assertThat(projected.isActiveAt(start + 2L * day)).isTrue()
    }

    @Test fun therapyExplicitEndIsBoundedAndInvalidPastEndFallsBackSafely() {
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(therapyEvents = listOf(
                TherapyEvent(1_000L, "illness", mapOf("eventId" to "long", "endTs" to Long.MAX_VALUE.toString())),
                TherapyEvent(2_000L, "carbs", mapOf("eventId" to "past", "endTs" to "1", "grams" to "20")),
                TherapyEvent(3_000L, "carbs", mapOf("eventId" to "invalid", "endTs" to "not-a-timestamp", "grams" to "20"))
            )),
            nowTs = 4_000L
        )

        assertThat(result.first { it.localId == "long" }.endTs)
            .isEqualTo(1_000L + 24L * 60L * 60L * 1_000L)
        assertThat(result.first { it.localId == "past" }.endTs)
            .isEqualTo(2_000L + 180L * 60L * 1_000L)
        assertThat(result.first { it.localId == "invalid" }.endTs)
            .isEqualTo(3_000L + 180L * 60L * 1_000L)
    }

    @Test fun ordinaryInsulinTherapyIsNotAnInfusionProblem() {
        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEvent(1_000L, "bolus", mapOf("eventId" to "bolus", "units" to "1.0")),
                    TherapyEvent(2_000L, "correction_bolus", mapOf("eventId" to "correction", "units" to "0.5")),
                    TherapyEvent(3_000L, "insulin", mapOf("eventId" to "insulin", "units" to "0.2"))
                )
            ),
            nowTs = 4_000L
        )

        assertThat(result).isEmpty()
    }

    @Test fun futurePlannedActivityRemainsInTimeline() {
        val future = CompensationEvent(
            localId = "planned:future",
            startTs = 20_000L,
            endTs = 80_000L,
            type = CompensationEventType.ACTIVITY,
            subtype = "PLANNED",
            source = EventSource.USER
        )

        val result = EventTimelineRepository().aggregate(
            EventTimelineSources(plannedActivity = listOf(future)),
            nowTs = 10_000L
        )

        assertThat(result).containsExactly(future)
    }

    @Test fun manualFiniteContextReceivesClinicalDurationBeforePersistence() {
        val manual = CompensationEvent(
            localId = "manual:stress",
            startTs = 10_000L,
            endTs = 10_000L,
            type = CompensationEventType.STRESS,
            severity = EventSeverity.HIGH,
            status = CompensationEventStatus.ACTIVE
        )

        val normalized = CompensationEventManualPolicy.normalizeContext(manual)

        assertThat(normalized.endTs).isEqualTo(10_000L + 4L * 60L * 60_000L)
        assertThat(normalized.status).isEqualTo(CompensationEventStatus.ACTIVE)
    }
}

private class SimulatedContextMarkerVmError : VirtualMachineError()
