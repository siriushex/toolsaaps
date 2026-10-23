package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3InsulinInventoryTest {
    private val at = 1_800_000_000_000L
    private fun config(label: String = "historical") = buildJsonObject {
        put("insulinLabel", label); put("insulinEndTime", 18_000_000)
        put("insulinPeakTime", 4_500_000); put("concentration", 1.0)
    }
    private fun row(id: String = "dose-1", time: Long = at - 60_000,
        fields: JsonObject = buildJsonObject { put("insulin", 2.2); put("icfg", config()) },
        revision: Long = at - 1, deleted: Boolean = false): LocalNightscoutV3DocumentEntity {
        val body = buildJsonObject { put("eventType", "Correction Bolus"); put("date", time); put("isValid", true) } + fields
        val json = JsonObject(body).toString()
        return LocalNightscoutV3DocumentEntity("treatments", id, "local-v3-owner", time, json,
            json.toByteArray().size, at - 1000, revision, deleted, false)
    }
    private fun read(vararg rows: LocalNightscoutV3DocumentEntity, from: Long = at - 3_600_000,
        known: Long = at) = AapsV3InsulinInventoryTimeline(LocalV3RetainedSnapshot.Complete(
            rows.maxOfOrNull { it.modifiedRevision } ?: 0, rows.toList())).read(from, at, known)

    @Test fun usesNativeBolusBranchAndItsOwnInsulinContext() {
        val source = row(fields = buildJsonObject {
            put("eventType", "Note"); put("insulin", 2.2); put("type", "SMB"); put("icfg", config("old insulin"))
        })
        val result = read(source)
        assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
        val dose = result.records.single().dose as AapsV3InsulinDose.Bolus
        assertEquals(2.2, dose.units, 0.0)
        assertEquals("SMB", dose.type)
        assertEquals("old insulin", dose.configuration!!.label)
        assertFalse(result.upstreamCompletenessVerified)
    }

    @Test fun basalExtendedAndEmulatedDeliveryKeepDistinctSemantics() {
        val basal = row("basal", fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8)
        })
        val extended = row("extended", fields = buildJsonObject {
            put("eventType", "Combo Bolus"); put("duration", 30); put("enteredinsulin", 1.5)
            put("isEmulatingTempBasal", true)
        })
        val result = read(basal, extended)
        assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
        val temp = result.records.single { it.identifier == "basal" }.dose as AapsV3InsulinDose.TempBasal
        assertEquals(1_800_000L, temp.durationMs)
        assertEquals(0.8, temp.absoluteUnitsPerHour!!, 0.0)
        val ext = result.records.single { it.identifier == "extended" }.dose as AapsV3InsulinDose.Extended
        assertTrue(ext.emulatingTempBasal)
        assertEquals(1.5, ext.units, 0.0)
    }

    @Test fun intervalStartingBeforeQueryRemainsWhenItOverlaps() {
        val source = row(time = at - 7_200_000, fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 180); put("percent", -100)
        })
        assertEquals(1, read(source).records.size)
    }

    @Test fun nativeEmulatedBasalSelectsOnlyTheExtendedChildAndItsPumpMetadata() {
        val child = buildJsonObject {
            put("date", at - 60_000)
            put("duration", 30); put("enteredinsulin", 0.25); put("isEmulatingTempBasal", true)
            put("pumpId", 9); put("pumpType", "pump"); put("pumpSerial", "child-serial"); put("endId", 12)
        }
        val wrapped = row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("absolute", 99); put("duration", 60)
            put("pumpId", 8); put("extendedEmulated", child)
        })
        val result = read(wrapped)
        val dose = result.records.single().dose as AapsV3InsulinDose.Extended
        assertEquals(0.25, dose.units, 0.0)
        assertEquals(1_800_000L, dose.durationMs)
        assertEquals(12L, result.records.single().pumpEndId)
        val direct = row("direct", fields = JsonObject(child + ("eventType" to JsonPrimitive("Combo Bolus"))))
        assertEquals(AapsV3ObservedInsulinStatus.CONFLICTING, read(wrapped, direct).status)
    }

    @Test fun deletionAndInvalidationRemainVisibleWithoutRestoringAnOldDose() {
        for (source in listOf(row(deleted = true), row(fields = buildJsonObject {
            put("insulin", 2.2); put("isValid", false)
        }))) {
            assertTrue(read(source).records.single().invalidated)
        }
    }

    @Test fun missingConfigurationStaysUnknownRatherThanUsingTodaysInsulin() {
        val source = row(fields = buildJsonObject { put("insulin", 2.2) })
        assertNull((read(source).records.single().dose as AapsV3InsulinDose.Bolus).configuration)
    }

    @Test fun malformedRelevantRecordDoesNotYieldAPartialInventory() {
        for (fields in listOf(buildJsonObject { put("insulin", "NaN") }, buildJsonObject {
            put("eventType", "Combo Bolus"); put("enteredinsulin", 1.5); put("duration", 30); put("durationInMilliseconds", 1)
        }, buildJsonObject { put("insulin", 2.2); put("type", "UNKNOWN") })) {
            val result = read(row(), row("bad", fields = fields))
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun duplicatePumpIdentityCannotBecomeTwoDeliveries() {
        fun fields() = buildJsonObject {
            put("insulin", 2.2); put("pumpId", 123); put("pumpType", "pump"); put("pumpSerial", "serial")
        }
        val result = read(row("first", fields = fields()), row("second", fields = fields()))
        assertEquals(AapsV3ObservedInsulinStatus.CONFLICTING, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun malformedFutureDeliveryCannotHideAnEarlierClockOrReturnPartialInventory() {
        val earlier = at - 60_000
        val malformed = listOf(
            buildJsonObject { put("insulin", 2.2); put("duration", 30); put("timestamp", earlier) },
            buildJsonObject {
                put("eventType", "Temp Basal"); put("absolute", 0.8); put("timestamp", earlier)
            },
            buildJsonObject {
                put("eventType", "Temp Basal"); put("extendedEmulated", buildJsonObject {
                    put("date", earlier); put("duration", 30); put("enteredinsulin", -1)
                    put("isEmulatingTempBasal", true)
                })
            }
        )
        for (fields in malformed) {
            for (deleted in listOf(false, true)) {
                val result = read(row(), row("invalid-future", at + 60_000, fields, deleted = deleted))
                assertEquals(AapsV3ObservedInsulinStatus.INVALID, result.status)
                assertTrue(result.records.isEmpty())
                assertFalse(result.upstreamCompletenessVerified)
            }
        }
    }

    @Test fun corruptFuturePayloadCannotBecomeAbsenceOfInsulin() {
        val broken = row("invalid-future", time = at + 60_000)
        for (source in listOf(broken.copy(payloadBytes = 1), broken.copy(payloadJson = "{", payloadBytes = 1))) {
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source).status)
        }
    }

    @Test fun validatedFutureAndExpiredRecordsStillUseTheRequestedWindow() {
        val current = row()
        val result = read(current, row("future", at + 60_000), row("expired", at - 7_200_000))
        assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
        assertEquals(listOf(current.identifier), result.records.map { it.identifier })
        assertFalse(result.upstreamCompletenessVerified)
    }

    @Test fun pumpIdentityIsScopedToNativeRecordKindAndEndIdentitySurvives() {
        val common = buildJsonObject { put("pumpId", 123); put("pumpType", "pump"); put("pumpSerial", "serial"); put("endId", 456) }
        val bolus = row("bolus", fields = JsonObject(common + ("insulin" to JsonPrimitive(2.2))))
        val basal = row("basal", fields = JsonObject(common + buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0)
        }))
        val result = read(bolus, basal)
        assertEquals(2, result.records.size)
        assertNotEquals(result.records[0].pumpRecordIdentity, result.records[1].pumpRecordIdentity)
        assertTrue(result.records.all { it.pumpEndId == 456L })
    }

    @Test fun lateCorrectionCannotBeUsedAsIfItWereAvailableAtPredictionTime() {
        val result = read(row(revision = at + 10))
        assertEquals(AapsV3ObservedInsulinStatus.LATE_EVIDENCE, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun lateMovedShortenedOrDeletedDeliveryCannotBecomeEarlierAbsence() {
        val changed = listOf(
            row(time = at + 1, revision = at + 10),
            row(time = at - 7_200_000, revision = at + 10),
            row(time = at - 7_200_000, revision = at + 10, fields = buildJsonObject {
                put("eventType", "Temp Basal"); put("duration", 1); put("absolute", 0.8)
            }),
            row(time = at + 1, revision = at + 10, deleted = true)
        )
        for (source in changed) {
            val result = read(source)
            assertEquals(AapsV3ObservedInsulinStatus.LATE_EVIDENCE, result.status)
            assertTrue(result.records.isEmpty())
            assertFalse(result.upstreamCompletenessVerified)
        }
    }

    @Test fun newerEmptyOrUnrelatedSnapshotCannotReconstructEarlierInventory() {
        val unrelated = row("note", time = at + 1, revision = at + 10,
            fields = buildJsonObject { put("eventType", "Note") })
        for (rows in listOf(emptyList(), listOf(unrelated), listOf(row(), unrelated))) {
            val timeline = AapsV3InsulinInventoryTimeline(LocalV3RetainedSnapshot.Complete(at + 10, rows))
            val historical = timeline.read(at - 3_600_000, at, at)
            assertEquals(AapsV3ObservedInsulinStatus.LATE_EVIDENCE, historical.status)
            assertTrue(historical.records.isEmpty())
            val current = timeline.read(at - 3_600_000, at, at + 10)
            assertEquals(if (rows.size == 2) AapsV3ObservedInsulinStatus.OBSERVED
                else AapsV3ObservedInsulinStatus.NOT_OBSERVED, current.status)
            assertFalse(current.upstreamCompletenessVerified)
        }
    }

    @Test fun emptyAndOverLimitAreExplicitAndNeverProveCoverage() {
        val empty = read()
        assertEquals(AapsV3ObservedInsulinStatus.NOT_OBSERVED, empty.status)
        assertFalse(empty.upstreamCompletenessVerified)
        val limited = AapsV3InsulinInventoryTimeline(LocalV3RetainedSnapshot.OverLimit(at)).read(1, at, at)
        assertEquals(AapsV3ObservedInsulinStatus.LIMIT_REACHED, limited.status)
    }

    @Test fun insulinWithConflictingDeliveryFieldsCannotLoseItsDuration() {
        val source = row(fields = buildJsonObject { put("insulin", 2.2); put("duration", 30) })
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source).status)
    }

    @Test fun contradictoryAbsoluteStartRateCannotBecomeObservedBasal() {
        val source = row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8); put("rate", 1.2)
        })
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source).status)
    }

    @Test fun unknownTemporaryBasalTypeCannotBeSilentlyTreatedAsNormal() {
        val source = row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8); put("type", "UNRECOGNIZED")
        })
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source).status)
    }

    @Test fun everyNativeTemporaryBasalTypeAndConvertedStartRateRemainExplicit() {
        for (type in AapsV3TempBasalType.entries) {
            val source = row(fields = buildJsonObject {
                put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8)
                put("rate", 0.8); put("type", type.name)
            })
            val dose = read(source).records.single().dose as AapsV3InsulinDose.TempBasal
            assertEquals(type, dose.type)
            assertEquals(0.8, dose.reportedRateAtStart!!, 0.0)
        }
    }

    @Test fun percentageStartConversionIsNotMistakenForThePercentageItself() {
        val source = row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("percent", -100)
            put("rate", 0.0); put("type", "EMULATED_PUMP_SUSPEND")
        })
        val dose = read(source).records.single().dose as AapsV3InsulinDose.TempBasal
        assertEquals(-100.0, dose.percentAdjustment!!, 0.0)
        assertEquals(0.0, dose.reportedRateAtStart!!, 0.0)
        assertEquals(AapsV3TempBasalType.EMULATED_PUMP_SUSPEND, dose.type)
    }

    @Test fun invalidMetadataTimeAndUnitsNeverYieldPartialSuccess() {
        val source = row()
        for (bad in listOf(source.copy(source = "another-owner"), source.copy(payloadBytes = 1),
            source.copy(eventAtMs = at), source.copy(createdRevision = at + 10))) {
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(bad).status)
        }
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source, from = at + 1).status)
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source, known = at - 1).status)
    }

    @Test fun recordsAreImmutableAndDefaultRenderingIsRedacted() {
        val result = read(row())
        assertThrows(UnsupportedOperationException::class.java) { (result.records as MutableList).clear() }
        assertFalse(result.toString().contains("2.2"))
        assertFalse(result.records.single().toString().contains("dose-1"))
        assertFalse(result.records.single().dose.toString().contains("historical"))
    }

    @Test fun unrecognizedDeliveryFieldsCannotBecomeAnEmptyOrPartialInventory() {
        val indicators = listOf(
            buildJsonObject { put("enteredinsulin", 1.0) },
            buildJsonObject { put("absolute", 0.0) },
            buildJsonObject { put("percent", -100) },
            buildJsonObject { put("rate", 0.8) },
            buildJsonObject { put("isEmulatingTempBasal", true) },
            buildJsonObject { put("isBasalInsulin", true) },
            buildJsonObject { put("extendedEmulated", buildJsonObject {
                put("enteredinsulin", 1.0); put("duration", 30); put("isEmulatingTempBasal", true)
            }) }
        )
        for (indicator in indicators) for (event in listOf("Note", "Future Delivery")) {
            val fields = JsonObject(indicator + ("eventType" to JsonPrimitive(event)))
            val hidden = row("hidden", fields = fields)
            for (result in listOf(read(hidden), read(row(), hidden))) {
                assertEquals("$event $indicator", AapsV3ObservedInsulinStatus.INVALID, result.status)
                assertTrue(result.records.isEmpty())
            }
            // Unknown interval support cannot be discarded as expired.
            assertEquals(AapsV3ObservedInsulinStatus.INVALID,
                read(row("old", time = at - 7_200_000, fields = fields)).status)
        }
    }

    @Test fun carbohydrateBranchCannotHideBasalOrExtendedDelivery() {
        for (delivery in listOf(
            buildJsonObject { put("eventType", "Temp Basal"); put("absolute", 0.8); put("duration", 30) },
            buildJsonObject { put("eventType", "Combo Bolus"); put("enteredinsulin", 1.0); put("duration", 30) },
            buildJsonObject { put("eventType", "Carb Correction"); put("enteredinsulin", 1.0) }
        )) {
            val result = read(row(fields = JsonObject(delivery + ("carbs" to JsonPrimitive(24)))))
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun instantaneousInsulinCannotHideAdditionalDeliveryForms() {
        for (extra in listOf(
            buildJsonObject { put("enteredinsulin", 1.0) },
            buildJsonObject { put("absolute", 0.0) },
            buildJsonObject { put("percent", -100) },
            buildJsonObject { put("rate", 0.8) },
            buildJsonObject { put("isEmulatingTempBasal", true) }
        )) {
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(row(fields = JsonObject(extra +
                ("insulin" to JsonPrimitive(2.2))))).status)
        }
    }

    @Test fun basalAndExtendedBranchesCannotHideAnotherForm() {
        val basal = buildJsonObject { put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8) }
        val extended = buildJsonObject { put("eventType", "Combo Bolus"); put("duration", 30); put("enteredinsulin", 1.0) }
        for (fields in listOf(JsonObject(basal + ("enteredinsulin" to JsonPrimitive(1.0))),
            JsonObject(extended + ("absolute" to JsonPrimitive(0.8))),
            JsonObject(extended + ("percent" to JsonPrimitive(0.0))))) {
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(row(fields = fields)).status)
        }
    }

    @Test fun emulatedChildCannotSilentlyDropMixedOrRecursivePayloads() {
        val child = buildJsonObject {
            put("date", at - 60_000); put("enteredinsulin", 1.0); put("duration", 30); put("isEmulatingTempBasal", true)
        }
        assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, read(row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8); put("extendedEmulated", child)
        })).status)
        for (extra in listOf(
            buildJsonObject { put("insulin", 2.0) },
            buildJsonObject { put("carbs", 24) },
            buildJsonObject { put("absolute", 0.8) },
            buildJsonObject { put("isEmulatingTempBasal", false) },
            buildJsonObject { put("extendedEmulated", child) }
        )) {
            val result = read(row(fields = buildJsonObject {
                put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8)
                put("extendedEmulated", JsonObject(child + extra))
            }))
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun nonDeliveryDurationsGlucoseAndInsulinConfigurationDoNotBecomeDoses() {
        for (fields in listOf(
            buildJsonObject { put("eventType", "Carb Correction"); put("carbs", 24); put("duration", 120) },
            buildJsonObject { put("eventType", "Exercise"); put("duration", 30) },
            buildJsonObject { put("eventType", "Note"); put("glucose", 5.5); put("icfg", config()) },
            buildJsonObject { put("eventType", "Note"); put("enteredinsulin", JsonNull); put("rate", JsonNull) }
        )) {
            assertEquals(AapsV3ObservedInsulinStatus.NOT_OBSERVED, read(row(fields = fields)).status)
        }
    }

    @Test fun emulatedDeliveryCannotBorrowWrapperTimeOrContradictItsClock() {
        val child = buildJsonObject {
            put("date", at - 60_000); put("utcOffset", 240)
            put("enteredinsulin", 0.5); put("duration", 30); put("isEmulatingTempBasal", true)
        }
        val invalid = listOf(
            JsonObject(child - "date"),
            JsonObject(child + ("date" to JsonNull)),
            JsonObject(child + ("date" to JsonPrimitive(at + 60_000))),
            JsonObject(child + ("date" to JsonPrimitive(at - 120_000))),
            JsonObject(child + ("utcOffset" to JsonPrimitive(180)))
        )
        for (nested in invalid) {
            val result = read(row(), row("wrapped", fields = buildJsonObject {
                put("eventType", "Temp Basal"); put("absolute", 1); put("duration", 30)
                put("utcOffset", 240); put("extendedEmulated", nested)
            }))
            assertEquals(nested.toString(), AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun contradictoryTimestampAliasesCannotEnterInsulinInventory() {
        for (extra in listOf(
            buildJsonObject { put("mills", at - 1) },
            buildJsonObject { put("timestamp", at - 1) },
            buildJsonObject { put("created_at", java.time.Instant.ofEpochMilli(at - 1).toString()) },
            buildJsonObject { put("created_at", "invalid-time") }
        )) {
            val result = read(row(fields = JsonObject(extra + ("insulin" to JsonPrimitive(2.2)))))
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun malformedRecordedOffsetCannotSilentlyBecomeUtcOrCurrentZone() {
        for (offset in listOf(JsonPrimitive(1441), JsonPrimitive(-1441), JsonPrimitive(1.5), JsonPrimitive("bad"))) {
            val result = read(row(fields = buildJsonObject { put("insulin", 2.2); put("utcOffset", offset) }))
            assertEquals(offset.toString(), AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun recordedOffsetsAndEquivalentAliasesNeverShiftTheUtcInstant() {
        val time = at - 60_000
        for (offset in listOf<Int?>(null, -1440, -300, 0, 240, 1440)) {
            val result = read(row(fields = buildJsonObject {
                put("insulin", 2.2); put("icfg", config()); put("utcOffset", offset?.let(::JsonPrimitive) ?: JsonNull)
                put("mills", time); put("timestamp", time)
                put("created_at", java.time.Instant.ofEpochMilli(time).atOffset(java.time.ZoneOffset.ofHours(4))
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }))
            assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
            assertEquals(time, result.records.single().eventAtMs)
            assertEquals(offset, result.records.single().recordedUtcOffsetMinutes)
            assertEquals(AapsV3InsulinAnchorKind.UNSPECIFIED_EVENT, result.records.single().anchorKind)
            assertFalse(result.upstreamCompletenessVerified)
        }
        assertNull(read(row()).records.single().recordedUtcOffsetMinutes)
    }

    @Test fun comboMetadataCannotConfirmDeliveryStartOrMealIntent() {
        for (type in listOf("NORMAL", "SMB", "PRIMING")) {
            val result = read(row(fields = buildJsonObject {
                put("insulin", 2.2); put("icfg", config()); put("type", type)
                put("pumpId", 123); put("pumpType", "ACCU_CHEK_COMBO"); put("pumpSerial", "fixture-pump")
                put("utcOffset", 240)
            }))
            val record = result.records.single()
            assertEquals(AapsV3InsulinAnchorKind.COMBO_BOLUS_START_UNVERIFIED, record.anchorKind)
            assertEquals(type, (record.dose as AapsV3InsulinDose.Bolus).type)
            assertEquals(at - 60_000, record.eventAtMs)
            assertFalse(result.upstreamCompletenessVerified)
            assertFalse(record.toString().contains("fixture-pump"))
        }
        val basal = read(row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("absolute", 0); put("duration", 30)
            put("pumpId", 123); put("pumpType", "ACCU_CHEK_COMBO"); put("pumpSerial", "fixture-pump")
        })).records.single()
        assertEquals(AapsV3InsulinAnchorKind.REPORTED_INTERVAL_START, basal.anchorKind)
    }

    @Test fun contradictoryFutureAnchorCannotHideEarlierDelivery() {
        val future = at + 60_000
        val nested = buildJsonObject {
            put("eventType", "Temp Basal"); put("absolute", 1); put("duration", 30)
            put("extendedEmulated", buildJsonObject {
                put("date", at - 60_000); put("duration", 30); put("enteredinsulin", 0.5); put("isEmulatingTempBasal", true)
            })
        }
        for (fields in listOf(nested, buildJsonObject {
            put("insulin", 2.2); put("timestamp", at - 60_000)
        }, buildJsonObject {
            put("insulin", 2.2); put("created_at", java.time.Instant.ofEpochMilli(future).plusNanos(1).toString())
        })) {
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(row(time = future, fields = fields)).status)
        }
        assertEquals(AapsV3ObservedInsulinStatus.NOT_OBSERVED, read(row(time = future)).status)
    }

    @Test fun emulatedChildKeepsItsOwnOffsetWithoutParentFallback() {
        for ((parent, child) in listOf(240 to 240, 240 to null, null to -300, null to null)) {
            val result = read(row(fields = buildJsonObject {
                put("eventType", "Temp Basal"); put("absolute", 1); put("duration", 30)
                put("utcOffset", parent?.let(::JsonPrimitive) ?: JsonNull)
                put("extendedEmulated", buildJsonObject {
                    put("date", at - 60_000); put("utcOffset", child?.let(::JsonPrimitive) ?: JsonNull)
                    put("duration", 30); put("enteredinsulin", 0.5); put("isEmulatingTempBasal", true)
                })
            }))
            assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
            assertEquals(child, result.records.single().recordedUtcOffsetMinutes)
            assertEquals(AapsV3InsulinAnchorKind.REPORTED_INTERVAL_START, result.records.single().anchorKind)
        }
    }
}
