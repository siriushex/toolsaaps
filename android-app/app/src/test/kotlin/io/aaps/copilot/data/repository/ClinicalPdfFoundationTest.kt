package io.aaps.copilot.data.repository

import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.report.ClinicalPdfContentBlock
import io.aaps.copilot.report.ClinicalPdfCancellationCheckpoint
import io.aaps.copilot.report.ClinicalPdfContentCursor
import io.aaps.copilot.report.ClinicalPdfContentRole
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.report.ClinicalPdfDocument
import io.aaps.copilot.report.ClinicalPdfDocumentFactory
import io.aaps.copilot.report.ClinicalPdfLimits
import io.aaps.copilot.report.ClinicalPdfLimitDimension
import io.aaps.copilot.report.ClinicalPdfPage
import io.aaps.copilot.report.ClinicalPdfPlanResult
import io.aaps.copilot.report.ClinicalPdfRenderResult
import io.aaps.copilot.report.ClinicalReportPdfRenderer
import io.aaps.copilot.report.findClinicalPdfWordBoundary
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClinicalPdfFoundationTest {

    @Test
    fun exactPreparedPayloadBuildsReopenableDeterministicCompleteSource() {
        val fixture = stressFixture()
        val ready = ClinicalPdfContentSourceFactory.create(
            payload = fixture.payload,
            local = fixture.local,
            complete = null
        ) as ClinicalPdfContentSourceResult.Ready

        val first = read(ready.source)
        val second = read(ready.source)

        assertThat(second.digest).isEqualTo(first.digest)
        assertThat(second.blockCount).isEqualTo(first.blockCount)
        assertThat(second.stableKeys).containsExactlyElementsIn(first.stableKeys).inOrder()
        assertThat(first.stableKeys.distinct()).hasSize(first.stableKeys.size)
        assertThat(first.stableKeys.filter { it.startsWith("30d/glucose/") })
            .hasSize(8_641)
        assertThat(first.stableKeys.filter { it.startsWith("30d/glucose/") }.distinct())
            .hasSize(8_641)
        assertThat(first.stableKeys.filter { it.startsWith("7d/glucose/") })
            .hasSize(2_017)
        assertThat(first.stableKeys.filter { it.startsWith("24h/raw-glucose/") })
            .hasSize(289)
        assertThat(first.stableKeys.filter { it.startsWith("24h/calibrated-glucose/") })
            .hasSize(289)
        assertThat(first.stableKeys.filter { it.startsWith("24h/therapy/") })
            .hasSize(fixture.payload.dataset.detail24h.therapy.size)
        assertThat(first.stableKeys.filter { it.startsWith("24h/target/") })
            .hasSize(fixture.payload.dataset.detail24h.targets.size)
        assertThat(first.stableKeys.filter { it.startsWith("24h/forecast/") })
            .hasSize(fixture.payload.dataset.detail24h.forecasts.size)
        assertThat(first.stableKeys.filter { it.startsWith("24h/telemetry/") })
            .hasSize(fixture.payload.dataset.detail24h.telemetry.size)
        assertThat(first.stableKeys.filter { it.startsWith("24h/event/") }).isNotEmpty()
        assertThat(first.stableKeys.filter { it.startsWith("24h/planned-activity/") }).isNotEmpty()
        assertThat(first.stableKeys.filter { it.startsWith("7d/therapy/") })
            .hasSize(fixture.payload.dataset.therapy7d.size)
        assertThat(first.stableKeys.filter { it.startsWith("7d/target/") })
            .hasSize(fixture.payload.dataset.targets7d.size)
        assertThat(first.stableKeys.filter { it.startsWith("7d/event/") }).isNotEmpty()
        assertThat(first.stableKeys.filter { it.startsWith("7d/planned-activity/") }).isNotEmpty()
        assertThat(first.stableKeys.filter { it.startsWith("30d/therapy/") })
            .hasSize(fixture.payload.dataset.therapy30d.size)
        assertThat(first.stableKeys.filter { it.startsWith("30d/target/") })
            .hasSize(fixture.payload.dataset.targets30d.size)
        assertThat(first.stableKeys.filter { it.startsWith("30d/event/") })
            .hasSize(fixture.payload.dataset.eventSummaries.size)
        assertThat(first.stableKeys.filter { it.startsWith("30d/planned-activity/") })
            .hasSize(fixture.payload.dataset.plannedActivities.size)
        assertThat(first.text).contains("24h detailed canonical data")
        assertThat(first.text).contains("7d full canonical time series")
        assertThat(first.text).contains("30d full canonical time series")
        assertThat(first.text).contains("associations, not diagnoses or therapy instructions")
        assertThat(first.text).doesNotContain("Remote bounded event payload preview")
    }

    @Test
    fun exactPreparedPayloadHasNoElapsedTimeExpiry() {
        val fixture = stressFixture(glucoseCount = 4)

        val result = ClinicalPdfContentSourceFactory.create(
            payload = fixture.payload,
            local = fixture.local,
            complete = null
        )

        assertThat(result).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
    }

    @Test
    fun contentIdentityContainsOnlyBoundedFixedProvenance() {
        assertThat(
            io.aaps.copilot.report.ClinicalPdfContentIdentity::class.java.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
        ).containsExactly(
            "requestSha256",
            "generatedAt",
            "zoneId",
            "schemaVersion"
        )
    }

    @Test
    fun factoryCanonicalizesEveryEmittedRowAfterEquivalentPreCreationMutation() {
        val fixture = stressFixture(glucoseCount = 4)
        val ts = GENERATED_AT - 60_000L
        val glucose = mutableListOf(ClinicalGlucosePoint(ts, 5.0004))
        val therapy = mutableListOf(ClinicalTherapyPoint(ts, insulinU = 1.2344))
        val targets = mutableListOf(
            ClinicalTargetPoint(ts, lowMmol = 5.5554, highMmol = 6.6664, durationMs = 60_000L)
        )
        val forecasts = mutableListOf(ClinicalForecastPoint(ts, 30, 6.1114, 5.5554, 6.7774))
        val telemetry = mutableListOf(
            ClinicalTelemetryPoint(
                ts,
                "activity_ratio",
                1.1114,
                "TRUSTED",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            )
        )
        val dataset = fixture.payload.dataset.copy(
            detail24h = fixture.payload.dataset.detail24h.copy(
                glucose = glucose,
                calibratedGlucose = glucose,
                therapy = therapy,
                targets = targets,
                forecasts = forecasts,
                telemetry = telemetry
            ),
            glucose7d = glucose,
            therapy7d = therapy,
            targets7d = targets,
            glucose30d = glucose,
            therapy30d = therapy,
            targets30d = targets
        )
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        val payload = ClinicalReportPayload(
            dataset = dataset,
            compactJson = compact,
            sha256 = ClinicalReportDatasetBuilder.sha256(compact)
        )

        glucose += ClinicalGlucosePoint(ts, 5.00049)
        glucose += ClinicalGlucosePoint(ts, Double.NaN)
        glucose += ClinicalGlucosePoint(ts + 1L, Double.POSITIVE_INFINITY)
        therapy += ClinicalTherapyPoint(ts, insulinU = 1.23449)
        targets += ClinicalTargetPoint(
            ts,
            lowMmol = 5.55549,
            highMmol = 6.66649,
            durationMs = 60_000L
        )
        forecasts += ClinicalForecastPoint(ts, 30, 6.11149, 5.55549, 6.77749)
        telemetry += ClinicalTelemetryPoint(
            ts,
            "activity_ratio",
            1.11149,
            "TRUSTED",
            ClinicalTelemetryOrigin.LOCAL_ACTIVITY
        )
        val local = fixture.local.copy(
            requestHash = payload.sha256,
            forecastQuality = dataset.detail24h.forecastQuality,
            remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        )

        val source = (ClinicalPdfContentSourceFactory.create(
            payload = payload,
            local = local,
            complete = null
        ) as ClinicalPdfContentSourceResult.Ready).source
        val emitted = blocks(source)

        listOf(
            "24h/raw-glucose/",
            "24h/calibrated-glucose/",
            "24h/therapy/",
            "24h/target/",
            "24h/forecast/",
            "24h/telemetry/",
            "7d/glucose/",
            "7d/therapy/",
            "7d/target/",
            "30d/glucose/",
            "30d/therapy/",
            "30d/target/"
        ).forEach { prefix ->
            assertThat(emitted.count { it.stableKey?.startsWith(prefix) == true }).isEqualTo(1)
        }
        assertThat(emitted.joinToString("\n", transform = ClinicalPdfContentBlock::text))
            .doesNotContain("NaN")
        assertThat(emitted.joinToString("\n", transform = ClinicalPdfContentBlock::text))
            .doesNotContain("Infinity")
    }

    @Test
    fun nonFiniteOptionalAdvisoryFailsBeforeAnyPdfSourceCanEmitIt() {
        val fixture = stressFixture(glucoseCount = 4)

        val result = ClinicalPdfContentSourceFactory.create(
            fixture.payload,
            fixture.local,
            advisoryResult(fixture.payload.sha256, Double.NaN)
        )

        assertFailure(result, ClinicalPdfContentSourceFailure.INVALID_PREPARED_PAYLOAD)
    }

    @Test
    fun nonFiniteForecastQualityFailsAtPdfSnapshotFactory() {
        val fixture = stressFixture(glucoseCount = 4)
        val invalid = fixture.payload.dataset.copy(
            detail24h = fixture.payload.dataset.detail24h.copy(
                forecastQuality = listOf(
                    ClinicalForecastQuality(
                        horizonMin = 30,
                        sampleCount = 4,
                        meanAbsoluteErrorMmol = Double.NaN,
                        ciCoveragePct = 75.0
                    )
                )
            )
        )

        assertInvalidPreparedDataset(fixture, invalid)
    }

    @Test
    fun infiniteActivityValueFailsAtPdfSnapshotFactory() {
        val fixture = stressFixture(glucoseCount = 4)
        val invalid = fixture.payload.dataset.copy(
            summary7d = fixture.payload.dataset.summary7d.copy(
                activity = fixture.payload.dataset.summary7d.activity.copy(
                    activeMinutes = Double.POSITIVE_INFINITY
                )
            )
        )

        assertInvalidPreparedDataset(fixture, invalid)
    }

    @Test
    fun nonFiniteBasalContextFailsAtPdfSnapshotFactory() {
        val fixture = stressFixture(glucoseCount = 4)
        val invalid = fixture.payload.dataset.copy(
            summary30d = fixture.payload.dataset.summary30d.copy(
                basalContext = fixture.payload.dataset.summary30d.basalContext.copy(
                    meanProfileRateUph = Double.NaN
                )
            )
        )

        assertInvalidPreparedDataset(fixture, invalid)
    }

    @Test
    fun nonFiniteMealEnergyFailsAtPdfSnapshotFactory() {
        val fixture = stressFixture(glucoseCount = 4)
        val invalid = fixture.payload.dataset.copy(
            summary24h = fixture.payload.dataset.summary24h!!.copy(
                mealEnergy = fixture.payload.dataset.summary24h.mealEnergy.copy(
                    carbohydrateEnergyKcal = Double.NEGATIVE_INFINITY
                )
            )
        )

        assertInvalidPreparedDataset(fixture, invalid)
    }

    @Test
    fun negativeNestedCounterFailsAtPdfSnapshotFactory() {
        val fixture = stressFixture(glucoseCount = 4)
        val invalid = fixture.payload.dataset.copy(
            summary7d = fixture.payload.dataset.summary7d.copy(
                therapyContext = fixture.payload.dataset.summary7d.therapyContext.copy(
                    sensorChanges = -1
                )
            )
        )

        assertInvalidPreparedDataset(fixture, invalid)
    }

    @Test
    fun nullableUnavailableNumericFieldsRemainValidAtPdfSnapshotFactory() {
        val fixture = stressFixture(glucoseCount = 4)
        val summary7d = fixture.payload.dataset.summary7d
        val valid = fixture.payload.dataset.copy(
            detail24h = fixture.payload.dataset.detail24h.copy(
                forecastQuality = listOf(ClinicalForecastQuality(30, 0, null, null))
            ),
            summary7d = summary7d.copy(
                meanMmol = null,
                medianMmol = null,
                coefficientOfVariationPct = null,
                timeBelow4Pct = null,
                timeInRangePct = null,
                timeAboveRangePct = null,
                meanTargetMmol = null,
                activity = summary7d.activity.copy(
                    steps = null,
                    distanceKm = null,
                    activeMinutes = null,
                    activeCaloriesKcal = null,
                    meanActivityRatio = null,
                    maxActivityRatio = null
                ),
                basalContext = summary7d.basalContext.copy(
                    meanProfileRateUph = null,
                    minProfileRateUph = null,
                    maxProfileRateUph = null,
                    meanProfilePercent = null
                ),
                mealEnergy = summary7d.mealEnergy.copy(
                    manualMealEnergyKcal = null,
                    estimatedTotalMealEnergyKcal = null,
                    netEnergyKcal = null
                )
            )
        )
        val prepared = preparedFixture(fixture, valid)

        val result = ClinicalPdfContentSourceFactory.create(
            prepared.payload,
            prepared.local,
            complete = null
        )

        assertThat(result).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
    }

    @Test
    fun profileFoodDurationUsesExactInferenceBounds() {
        val fixture = stressFixture(glucoseCount = 4)

        listOf(1, 361).forEach { duration ->
            assertInvalidPreparedDataset(
                fixture,
                fixture.payload.dataset.copy(
                    energyProfile = ClinicalEnergyProfileSummary(foodDurationMinutes = duration)
                )
            )
        }
        listOf(30, 360).forEach { duration ->
            val prepared = preparedFixture(
                fixture,
                fixture.payload.dataset.copy(
                    energyProfile = ClinicalEnergyProfileSummary(foodDurationMinutes = duration)
                )
            )
            assertThat(
                ClinicalPdfContentSourceFactory.create(
                    prepared.payload,
                    prepared.local,
                    complete = null
                )
            ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
        }
    }

    @Test
    fun preparedSnapshotRequiresSummary24h() {
        val fixture = stressFixture(glucoseCount = 4)

        assertInvalidPreparedDataset(
            fixture,
            fixture.payload.dataset.copy(summary24h = null)
        )
    }

    @Test
    fun summary24hMustRemainAValidOneDayPreparedWindow() {
        val fixture = stressFixture(glucoseCount = 4)
        val summary = fixture.payload.dataset.summary24h!!

        assertInvalidPreparedDataset(
            fixture,
            fixture.payload.dataset.copy(
                summary24h = summary.copy(
                    fromTs = summary.throughTs
                )
            )
        )
    }

    @Test
    fun detailedCanonicalRowsMustStartWithinPrepared24hWindow() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val detail = source.detail24h
        val before = detail.fromTs - 1L
        val after = detail.throughTs + 1L
        val invalid = listOf(
            source.copy(detail24h = detail.copy(glucose = listOf(ClinicalGlucosePoint(before, 6.0)))),
            source.copy(
                detail24h = detail.copy(
                    calibratedGlucose = listOf(ClinicalGlucosePoint(after, 6.0))
                )
            ),
            source.copy(
                detail24h = detail.copy(
                    therapy = listOf(ClinicalTherapyPoint(before, insulinU = 1.0))
                )
            ),
            source.copy(
                detail24h = detail.copy(
                    targets = listOf(ClinicalTargetPoint(after, 5.0, 6.0))
                )
            ),
            source.copy(
                detail24h = detail.copy(
                    forecasts = listOf(ClinicalForecastPoint(before, 30, 6.0, 5.0, 7.0))
                )
            ),
            source.copy(
                detail24h = detail.copy(
                    telemetry = listOf(ClinicalTelemetryPoint(after, "iob_units", 1.0, "OK"))
                )
            )
        )

        invalid.forEach { assertInvalidPreparedDataset(fixture, it) }
    }

    @Test
    fun sevenDayCanonicalRowsMustStartWithinPreparedSummaryWindow() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val before = source.summary7d.fromTs - 1L
        val after = source.summary7d.throughTs + 1L
        val invalid = listOf(
            source.copy(glucose7d = listOf(ClinicalGlucosePoint(before, 6.0))),
            source.copy(therapy7d = listOf(ClinicalTherapyPoint(after, carbsG = 10.0))),
            source.copy(targets7d = listOf(ClinicalTargetPoint(before, 5.0, 6.0)))
        )

        invalid.forEach { assertInvalidPreparedDataset(fixture, it) }
    }

    @Test
    fun thirtyDayCanonicalRowsMustStartWithinPreparedSummaryWindow() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val before = source.summary30d.fromTs - 1L
        val after = source.summary30d.throughTs + 1L
        val invalid = listOf(
            source.copy(glucose30d = listOf(ClinicalGlucosePoint(after, 6.0))),
            source.copy(therapy30d = listOf(ClinicalTherapyPoint(before, insulinU = 1.0))),
            source.copy(targets30d = listOf(ClinicalTargetPoint(after, 5.0, 6.0)))
        )

        invalid.forEach { assertInvalidPreparedDataset(fixture, it) }
    }

    @Test
    fun targetMayEndAfterPeriodWhenItsStartIsInWindow() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val target = ClinicalTargetPoint(
            ts = source.detail24h.throughTs,
            lowMmol = 5.0,
            highMmol = 6.0,
            durationMs = 60_000L,
            endTs = source.detail24h.throughTs + 60_000L
        )
        val prepared = preparedFixture(
            fixture,
            source.copy(detail24h = source.detail24h.copy(targets = listOf(target)))
        )

        assertThat(
            ClinicalPdfContentSourceFactory.create(prepared.payload, prepared.local, complete = null)
        ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
    }

    @Test
    fun canonicalTherapyRowsUseSharedPerRowComponentBounds() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val ts = source.detail24h.throughTs
        val valid = preparedFixture(
            fixture,
            source.copy(
                detail24h = source.detail24h.copy(
                    therapy = listOf(
                        ClinicalTherapyPoint(ts - 3L, insulinU = 0.0),
                        ClinicalTherapyPoint(ts - 2L, carbsG = 0.0),
                        ClinicalTherapyPoint(ts, insulinU = 100.0),
                        ClinicalTherapyPoint(ts - 1L, carbsG = 500.0, syntheticUam = true)
                    )
                )
            )
        )
        assertThat(
            ClinicalPdfContentSourceFactory.create(valid.payload, valid.local, complete = null)
        ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)

        listOf(
            ClinicalTherapyPoint(ts, insulinU = 100.001),
            ClinicalTherapyPoint(ts, carbsG = 500.001)
        ).forEach { point ->
            assertInvalidPreparedDataset(
                fixture,
                source.copy(detail24h = source.detail24h.copy(therapy = listOf(point)))
            )
        }
    }

    @Test
    fun canonicalTherapyRowsEnforceComponentPairingsAndAllowContextOnlyRows() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val ts = source.detail24h.throughTs
        val contextOnly = preparedFixture(
            fixture,
            source.copy(
                detail24h = source.detail24h.copy(
                    therapy = listOf(
                        ClinicalTherapyPoint(
                            ts = ts,
                            contextKind = ClinicalTherapyContextKind.PROFILE_SWITCH
                        )
                    )
                )
            )
        )
        assertThat(
            ClinicalPdfContentSourceFactory.create(
                contextOnly.payload,
                contextOnly.local,
                complete = null
            )
        ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)

        listOf(
            ClinicalTherapyPoint(ts, insulinU = 1.0, syntheticUam = true),
            ClinicalTherapyPoint(ts, syntheticUam = true),
            ClinicalTherapyPoint(
                ts,
                carbsG = 10.0,
                insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
            ),
            ClinicalTherapyPoint(
                ts,
                insulinU = 1.0,
                contextKind = ClinicalTherapyContextKind.PROFILE_SWITCH
            )
        ).forEach { point ->
            assertInvalidPreparedDataset(
                fixture,
                source.copy(detail24h = source.detail24h.copy(therapy = listOf(point)))
            )
        }
    }

    @Test
    fun canonicalTelemetryNormalizationRejectsUnknownFlagsQualityAndOrigin() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val ts = source.detail24h.throughTs
        val invalid = listOf(
            ClinicalTelemetryPoint(ts, "unknown_key", 1.0, "OK"),
            ClinicalTelemetryPoint(ts, "sensor_quality_blocked", 0.5, "OK"),
            ClinicalTelemetryPoint(ts, "iob_units", 1.0, "ERROR"),
            ClinicalTelemetryPoint(ts, "steps_count", 10.0, "TRUSTED")
        )

        invalid.forEach { point ->
            assertInvalidPreparedDataset(
                fixture,
                source.copy(detail24h = source.detail24h.copy(telemetry = listOf(point)))
            )
        }
    }

    @Test
    fun duplicateEventLocalIdFailsBeforeSourceCreation() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val event = source.eventSummaries.first()

        assertInvalidPreparedDataset(
            fixture,
            source.copy(
                eventSummaries = listOf(
                    event,
                    event.copy(title = "different event with same local identity")
                )
            )
        )
    }

    @Test
    fun eventSummaryRejectsUnknownDomainNamesAndConstructorViolations() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val event = source.eventSummaries.first()
        val invalidEvents = listOf(
            event.copy(type = "UNKNOWN"),
            event.copy(source = "REMOTE"),
            event.copy(severity = "CRITICAL"),
            event.copy(status = "OPEN"),
            event.copy(localId = ""),
            event.copy(title = "t".repeat(61)),
            event.copy(note = "n".repeat(501)),
            event.copy(startTs = -1L),
            event.copy(endTs = event.startTs - 1L)
        )

        invalidEvents.forEach { invalid ->
            assertInvalidPreparedDataset(
                fixture,
                source.copy(eventSummaries = listOf(invalid))
            )
        }
    }

    @Test
    fun eventSummaryAcceptsEveryDomainEnumIncludingCustomAndEmptyOptionalText() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val baseEvent = source.eventSummaries.first()
        val names = buildList {
            CompensationEventType.entries.forEach { type -> add(EventNames(type = type.name)) }
            EventSource.entries.forEach { sourceName -> add(EventNames(source = sourceName.name)) }
            EventSeverity.entries.forEach { severity -> add(EventNames(severity = severity.name)) }
            CompensationEventStatus.entries.forEach { status -> add(EventNames(status = status.name)) }
        }
        val events = names.mapIndexed { index, namesForEvent ->
            baseEvent.copy(
                localId = "enum-$index",
                type = namesForEvent.type ?: CompensationEventType.CUSTOM.name,
                source = namesForEvent.source ?: EventSource.AUTOMATIC.name,
                severity = namesForEvent.severity ?: EventSeverity.MEDIUM.name,
                status = namesForEvent.status ?: CompensationEventStatus.ACTIVE.name,
                title = "",
                subtype = "",
                provenance = ""
            )
        }
        val prepared = preparedFixture(fixture, source.copy(eventSummaries = events))

        assertThat(
            ClinicalPdfContentSourceFactory.create(
                prepared.payload,
                prepared.local,
                complete = null
            )
        ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
    }

    @Test
    fun invalidCanonicalDomainValuesFailAtPdfSnapshotBoundaries() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val ts = GENERATED_AT - 60_000L
        val invalidDatasets = listOf(
            source.copy(
                detail24h = source.detail24h.copy(
                    therapy = listOf(ClinicalTherapyPoint(ts, insulinU = -0.001))
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    therapy = listOf(ClinicalTherapyPoint(ts, carbsG = -0.001))
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    targets = listOf(ClinicalTargetPoint(ts, lowMmol = 8.0, highMmol = 7.0))
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    targets = listOf(
                        ClinicalTargetPoint(
                            ts = ts,
                            lowMmol = 6.0,
                            highMmol = 7.0,
                            durationMs = ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS + 1L
                        )
                    )
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    forecasts = listOf(ClinicalForecastPoint(ts, 181, 6.0, 5.0, 7.0))
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    forecasts = listOf(ClinicalForecastPoint(ts, 30, 6.0, 6.5, 7.0))
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    telemetry = listOf(
                        ClinicalTelemetryPoint(
                            ts,
                            "steps_count",
                            -1.0,
                            "TRUSTED",
                            ClinicalTelemetryOrigin.LOCAL_ACTIVITY
                        )
                    )
                )
            ),
            source.copy(
                detail24h = source.detail24h.copy(
                    forecastQuality = listOf(ClinicalForecastQuality(181, 4, -0.1, 101.0))
                )
            ),
            source.copy(
                summary7d = source.summary7d.copy(
                    activity = source.summary7d.activity.copy(
                        steps = -1.0,
                        meanActivityRatio = 0.1
                    )
                )
            ),
            source.copy(
                summary7d = source.summary7d.copy(
                    basalContext = source.summary7d.basalContext.copy(
                        coveragePct = 101.0,
                        meanProfileRateUph = -0.1,
                        meanProfilePercent = 0.0
                    )
                )
            ),
            source.copy(
                summary7d = source.summary7d.copy(
                    mealEnergy = source.summary7d.mealEnergy.copy(
                        carbohydrateEnergyKcal = -1.0,
                        estimatedTotalMealEnergyKcal = ClinicalRange(-1.0, 10.0)
                    )
                )
            ),
            source.copy(
                energyProfile = ClinicalEnergyProfileSummary(
                    foodProfileSource = "UNBOUNDED",
                    confidence = "CERTAIN",
                    maintenanceEnergyKcal = ClinicalRange(-1.0, 2_000.0)
                )
            ),
            source.copy(
                energyProfile = ClinicalEnergyProfileSummary(),
                plannedActivities = listOf(
                    ClinicalPlannedActivitySummary(
                        type = "UNKNOWN",
                        intensity = "MEDIUM",
                        plannedStartMs = source.summary30d.fromTs,
                        plannedDurationMinutes = 0
                    )
                )
            )
        )

        invalidDatasets.forEach { invalid -> assertInvalidPreparedDataset(fixture, invalid) }
        val invalidCurrent = source.copy(
            currentSnapshot = source.currentSnapshot.copy(
                selectedIsfSourceCode = 4.0,
                uamActiveFlag = 0.5,
                uamConfidence = 1.1,
                activeTargetLowMmol = 8.0,
                activeTargetHighMmol = 7.0
            )
        )
        val currentFailure = runCatching {
            ClinicalPdfSnapshotValidator.requireValid(invalidCurrent)
        }.exceptionOrNull()

        assertThat(currentFailure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun plannedActivityRequestAndPdfUseOneCompleteDeterministicOrder() {
        val fixture = stressFixture(glucoseCount = 4)
        val start = fixture.payload.dataset.summary30d.fromTs + DAY_MS
        val activities = listOf(
            ClinicalPlannedActivitySummary(
                type = "WALKING",
                intensity = "MEDIUM",
                plannedStartMs = start,
                plannedDurationMinutes = 60,
                observedMinutes = 40,
                adherence = "MEASURED"
            ),
            ClinicalPlannedActivitySummary(
                type = "WALKING",
                intensity = "MEDIUM",
                plannedStartMs = start,
                plannedDurationMinutes = 30,
                observedMinutes = 20,
                adherence = "MEASURED"
            )
        )
        val first = preparedFixture(
            fixture,
            fixture.payload.dataset.copy(
                energyProfile = ClinicalEnergyProfileSummary(),
                plannedActivities = activities
            )
        )
        val second = preparedFixture(
            fixture,
            fixture.payload.dataset.copy(
                energyProfile = ClinicalEnergyProfileSummary(),
                plannedActivities = activities.reversed()
            )
        )

        assertThat(first.payload.compactJson).isEqualTo(second.payload.compactJson)
        val source = (ClinicalPdfContentSourceFactory.create(
            first.payload,
            first.local,
            complete = null
        ) as ClinicalPdfContentSourceResult.Ready).source
        val pdfRows = blocks(source)
            .filter { it.stableKey?.startsWith("30d/planned-activity/") == true }
            .map(ClinicalPdfContentBlock::text)

        assertThat(pdfRows).hasSize(2)
        assertThat(pdfRows[0]).contains("durationMin=30")
        assertThat(pdfRows[1]).contains("durationMin=60")
        assertThat(first.payload.compactJson.indexOf("\"durationMin\":30"))
            .isLessThan(first.payload.compactJson.indexOf("\"durationMin\":60"))
    }

    @Test
    fun plannedContentHashBindsOptionalAdvisoryWhileRequestProvenanceStaysStable() {
        val fixture = stressFixture(glucoseCount = 4)

        val first = (ClinicalPdfContentSourceFactory.create(
            fixture.payload,
            fixture.local,
            advisoryResult(fixture.payload.sha256, 6.1)
        ) as ClinicalPdfContentSourceResult.Ready).source
        val second = (ClinicalPdfContentSourceFactory.create(
            fixture.payload,
            fixture.local,
            advisoryResult(fixture.payload.sha256, 6.2)
        ) as ClinicalPdfContentSourceResult.Ready).source

        assertThat(first.identity.requestSha256).isEqualTo(second.identity.requestSha256)
        val renderer = ClinicalReportPdfRenderer(CountingPdfFactory())
        val firstPlan = (renderer.plan(first) as ClinicalPdfPlanResult.Ready).plan
        val secondPlan = (renderer.plan(second) as ClinicalPdfPlanResult.Ready).plan
        assertThat(firstPlan.contentSha256).isNotEqualTo(secondPlan.contentSha256)
    }

    @Test
    fun plannedContentHashBindsLocalEventProvenanceAndActivityBeyondRemoteRequestHash() {
        val fixture = stressFixture(glucoseCount = 4)
        val from30d = fixture.payload.dataset.summary30d.fromTs
        val events = (0 until 520).map { index ->
            ClinicalEventSummary(
                localId = "private-$index",
                type = "STRESS",
                subtype = "identity",
                startTs = from30d + index * 60_000L,
                endTs = from30d + index * 60_000L + 30_000L,
                severity = "MEDIUM",
                source = "USER",
                title = "event-$index",
                note = null,
                status = "CLOSED",
                provenance = "fixture"
            )
        }
        val firstDataset = fixture.payload.dataset.copy(eventSummaries = events)
        val secondDataset = firstDataset.copy(
            eventSummaries = events.toMutableList().also {
                it[0] = it[0].copy(provenance = "changed-only-in-full-local-timeline")
            },
            plannedActivities = firstDataset.plannedActivities.mapIndexed { index, activity ->
                if (index == 0) activity.copy(targetDecision = "NO_DECISION") else activity
            }
        )
        val firstCompact = ClinicalReportDatasetBuilder.serialize(firstDataset)
        val secondCompact = ClinicalReportDatasetBuilder.serialize(secondDataset)
        assertThat(secondCompact).isEqualTo(firstCompact)
        val requestSha = ClinicalReportDatasetBuilder.sha256(firstCompact)

        fun sourceFor(dataset: ClinicalReportDataset): ClinicalPdfContentSource {
            val payload = ClinicalReportPayload(dataset, firstCompact, requestSha)
            val local = fixture.local.copy(
                requestHash = requestSha,
                plannedActivities = dataset.plannedActivities,
                eventSummaries = dataset.eventSummaries,
                remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
            )
            return (ClinicalPdfContentSourceFactory.create(payload, local, complete = null)
                as ClinicalPdfContentSourceResult.Ready).source
        }

        val first = sourceFor(firstDataset)
        val second = sourceFor(secondDataset)

        assertThat(first.identity.requestSha256).isEqualTo(second.identity.requestSha256)
        val renderer = ClinicalReportPdfRenderer(CountingPdfFactory())
        val firstPlan = (renderer.plan(first) as ClinicalPdfPlanResult.Ready).plan
        val secondPlan = (renderer.plan(second) as ClinicalPdfPlanResult.Ready).plan
        assertThat(firstPlan.contentSha256).isNotEqualTo(secondPlan.contentSha256)
        assertThat(blocks(second).count { it.stableKey?.startsWith("30d/event/") == true })
            .isEqualTo(520)
        assertThat(read(second).text).contains("changed-only-in-full-local-timeline")
    }

    @Test
    fun requestAndPdfEventRowsUseExactPreparedPeriodBoundariesWithoutDuplicates() {
        val fixture = stressFixture(glucoseCount = 4)
        val detailThrough = GENERATED_AT - 123_456L
        val sevenThrough = GENERATED_AT - 234_567L
        val thirtyThrough = GENERATED_AT - 111_111L
        val detailFrom = detailThrough - DAY_MS
        val sevenFrom = sevenThrough - 7L * DAY_MS
        val thirtyFrom = thirtyThrough - 30L * DAY_MS
        val boundaries = listOf(
            BoundaryFixture("24h", detailFrom, detailThrough, GENERATED_AT - DAY_MS),
            BoundaryFixture("7d", sevenFrom, sevenThrough, GENERATED_AT - 7L * DAY_MS),
            BoundaryFixture("30d", thirtyFrom, thirtyThrough, GENERATED_AT - 30L * DAY_MS)
        )
        val events = boundaries.flatMap { boundary ->
            listOf(
                eventAt("${boundary.label}-inside-from", boundary.fromTs),
                eventAt("${boundary.label}-outside-from", boundary.fromTs - 1L),
                eventAt("${boundary.label}-inside-through", boundary.throughTs),
                eventAt("${boundary.label}-outside-through", boundary.throughTs + 1L),
                eventAt("${boundary.label}-generated-from", boundary.generatedFromTs),
                eventAt("${boundary.label}-generated-through", GENERATED_AT)
            )
        }
        val activities = boundaries.flatMap { boundary ->
            buildList {
                add(activityAt("${boundary.label}-carry-in", boundary.fromTs - 30_000L))
                if (boundary.label != "30d") {
                    add(activityAt("${boundary.label}-ends-at-from", boundary.fromTs - 60_000L))
                }
                add(
                activityAt("${boundary.label}-inside-from", boundary.fromTs),
                )
                add(activityAt("${boundary.label}-inside-through", boundary.throughTs))
                if (boundary.label != "30d") {
                    add(activityAt("${boundary.label}-outside-from", boundary.fromTs - 60_001L))
                    add(activityAt("${boundary.label}-outside-through", boundary.throughTs + 1L))
                }
            }
        }
        val dataset = fixture.payload.dataset.copy(
            detail24h = fixture.payload.dataset.detail24h.copy(
                fromTs = detailFrom,
                throughTs = detailThrough
            ),
            summary24h = fixture.payload.dataset.summary24h!!.copy(
                fromTs = detailFrom,
                throughTs = detailThrough
            ),
            summary7d = fixture.payload.dataset.summary7d.copy(
                fromTs = sevenFrom,
                throughTs = sevenThrough
            ),
            summary30d = fixture.payload.dataset.summary30d.copy(
                fromTs = thirtyFrom,
                throughTs = thirtyThrough
            ),
            eventSummaries = events,
            plannedActivities = activities
        )
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        val payload = ClinicalReportPayload(
            dataset,
            compact,
            ClinicalReportDatasetBuilder.sha256(compact)
        )
        val local = fixture.local.copy(
            summary24h = dataset.summary24h,
            summary7d = dataset.summary7d,
            summary30d = dataset.summary30d,
            requestHash = payload.sha256,
            plannedActivities = activities,
            eventSummaries = events,
            remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        )

        val source = (ClinicalPdfContentSourceFactory.create(payload, local, complete = null)
            as ClinicalPdfContentSourceResult.Ready).source
        val emitted = blocks(source)
        val request = JsonParser.parseString(compact).asJsonObject

        boundaries.forEach { boundary ->
            val periodRows = emitted
                .filter { it.stableKey?.startsWith("${boundary.label}/") == true }
            val periodText = periodRows
                .joinToString("\n", transform = ClinicalPdfContentBlock::text)
            assertThat(periodText).contains("${boundary.label}-inside-from")
            assertThat(periodText).contains("${boundary.label}-inside-through")
            assertThat(periodText).contains("${boundary.label}-carry-in")
            assertThat(periodText).doesNotContain("${boundary.label}-ends-at-from")
            assertThat(periodText).contains("${boundary.label}-generated-from")
            assertThat(periodText).doesNotContain("${boundary.label}-outside-from")
            assertThat(periodText).doesNotContain("${boundary.label}-outside-through")
            assertThat(periodText).doesNotContain("${boundary.label}-generated-through")

            val requestKey = when (boundary.label) {
                "24h" -> "ev24"
                "7d" -> "ev7"
                else -> "ev30"
            }
            val requestTitles = request.getAsJsonArray(requestKey).map { row ->
                row.asJsonObject.get("title").asString
            }
            val pdfTitles = periodRows
                .filter { it.stableKey?.startsWith("${boundary.label}/event/") == true }
                .map { row ->
                    row.text.substringAfter(" | title=").substringBefore(" | note=")
                }
            assertThat(requestTitles.distinct()).hasSize(requestTitles.size)
            assertThat(pdfTitles.distinct()).hasSize(pdfTitles.size)
            assertThat(requestTitles).containsExactlyElementsIn(pdfTitles).inOrder()
        }
    }

    @Test
    fun associationOutputRejectsInvalidFactoryDomainValues() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val valid = source.eventTypeAssociations.single()
        val invalid = listOf(
            valid.copy(
                glucose = valid.glucose.copy(
                    sampleCount = ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE + 1
                )
            ),
            valid.copy(type = "UNKNOWN"),
            valid.copy(glucose = valid.glucose.copy(unit = "mg/dL")),
            valid.copy(glucose = valid.glucose.copy(sampleCount = 0, mean = 6.0)),
            valid.copy(glucose = valid.glucose.copy(sampleCount = 1, mean = null)),
            unsafeAssociation(valid, "causal", true),
            unsafeAssociation(valid, "eventCount", 0),
            valid.copy(totalDurationMinutes = 12L * 30L * 24L * 60L + 1L),
            valid.copy(glucose = valid.glucose.copy(mean = 0.99)),
            valid.copy(trend = valid.trend.copy(mean = 39.01)),
            valid.copy(uam = valid.uam.copy(mean = -0.01)),
            valid.copy(isf = valid.isf.copy(mean = 0.19)),
            valid.copy(cr = valid.cr.copy(mean = 60.01)),
            valid.copy(forecastError = valid.forecastError.copy(mean = -0.01))
        )

        invalid.forEach { association ->
            assertInvalidPreparedDataset(
                fixture,
                source.copy(eventTypeAssociations = listOf(association))
            )
        }
    }

    @Test
    fun associationOutputUsesCanonicalCarbAndClinicalDeltaUpperBounds() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val association = source.eventTypeAssociations.single()
        val valid = association.copy(
            uam = association.uam.copy(mean = 500.0),
            forecastError = association.forecastError.copy(mean = 39.0)
        )
        val prepared = preparedFixture(
            fixture,
            source.copy(eventTypeAssociations = listOf(valid))
        )
        assertThat(
            ClinicalPdfContentSourceFactory.create(prepared.payload, prepared.local, complete = null)
        ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)

        listOf(
            valid.copy(uam = valid.uam.copy(mean = 500.001)),
            valid.copy(forecastError = valid.forecastError.copy(mean = 39.001))
        ).forEach { invalid ->
            assertInvalidPreparedDataset(
                fixture,
                source.copy(eventTypeAssociations = listOf(invalid))
            )
        }
    }

    @Test
    fun associationOutputAcceptsExactUnitsAndDomainBoundaries() {
        val fixture = stressFixture(glucoseCount = 4)
        val maxSamples = ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE
        val boundary = ClinicalEventTypeAssociation(
            type = "CUSTOM",
            eventCount = 1,
            totalDurationMinutes = 30L * 24L * 60L,
            glucose = ClinicalAssociationMetric(maxSamples, 40.0, "mmol/L"),
            trend = ClinicalAssociationMetric(maxSamples, -39.0, "mmol/L per event"),
            uam = ClinicalAssociationMetric(maxSamples, 500.0, "g"),
            isf = ClinicalAssociationMetric(maxSamples, 18.0, "mmol/L/U"),
            cr = ClinicalAssociationMetric(maxSamples, 60.0, "g/U"),
            forecastError = ClinicalAssociationMetric(maxSamples, 39.0, "mmol/L")
        )
        val unavailable = ClinicalEventTypeAssociation(
            type = "STRESS",
            eventCount = 1,
            totalDurationMinutes = 0L,
            glucose = ClinicalAssociationMetric(0, null, "mmol/L"),
            trend = ClinicalAssociationMetric(0, null, "mmol/L per event"),
            uam = ClinicalAssociationMetric(0, null, "g"),
            isf = ClinicalAssociationMetric(0, null, "mmol/L/U"),
            cr = ClinicalAssociationMetric(0, null, "g/U"),
            forecastError = ClinicalAssociationMetric(0, null, "mmol/L")
        )
        val lowerBoundary = boundary.copy(
            glucose = boundary.glucose.copy(mean = 1.0),
            trend = boundary.trend.copy(mean = 39.0),
            uam = boundary.uam.copy(mean = 0.0),
            isf = boundary.isf.copy(mean = 0.2),
            cr = boundary.cr.copy(mean = 2.0),
            forecastError = boundary.forecastError.copy(mean = 0.0)
        )
        val prepared = preparedFixture(
            fixture,
            fixture.payload.dataset.copy(
                eventTypeAssociations = listOf(boundary, lowerBoundary, unavailable)
            )
        )

        assertThat(
            ClinicalPdfContentSourceFactory.create(
                prepared.payload,
                prepared.local,
                complete = null
            )
        ).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
    }

    @Test
    fun missingOrMismatchedPreparedPayloadFailsClosed() {
        val fixture = stressFixture(glucoseCount = 4)

        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = null,
                local = fixture.local,
                complete = null
            ),
            ClinicalPdfContentSourceFailure.MISSING_PREPARED_PAYLOAD
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload,
                local = fixture.local.copy(requestHash = "f".repeat(64)),
                complete = null
            ),
            ClinicalPdfContentSourceFailure.REQUEST_HASH_MISMATCH
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload,
                local = fixture.local.copy(zoneId = "UTC"),
                complete = null
            ),
            ClinicalPdfContentSourceFailure.ZONE_MISMATCH
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload.copy(compactJson = fixture.payload.compactJson + " "),
                local = fixture.local,
                complete = null
            ),
            ClinicalPdfContentSourceFailure.PAYLOAD_HASH_MISMATCH
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload.copy(
                    dataset = fixture.payload.dataset.copy(generatedAt = GENERATED_AT - 1L)
                ),
                local = fixture.local,
                complete = null
            ),
            ClinicalPdfContentSourceFailure.GENERATED_AT_MISMATCH
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload.copy(
                    dataset = fixture.payload.dataset.copy(
                        schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION - 1
                    )
                ),
                local = fixture.local,
                complete = null
            ),
            ClinicalPdfContentSourceFailure.SCHEMA_MISMATCH
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload,
                local = fixture.local.copy(
                    summary30d = fixture.local.summary30d.copy(meanMmol = 7.7)
                ),
                complete = null
            ),
            ClinicalPdfContentSourceFailure.LOCAL_SUMMARY_MISMATCH
        )
        val otherCanonical = ClinicalReportDatasetBuilder.serialize(
            fixture.payload.dataset.copy(zoneId = "UTC")
        )
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                payload = fixture.payload.copy(
                    compactJson = otherCanonical,
                    sha256 = ClinicalReportDatasetBuilder.sha256(otherCanonical)
                ),
                local = fixture.local.copy(
                    requestHash = ClinicalReportDatasetBuilder.sha256(otherCanonical)
                ),
                complete = null
            ),
            ClinicalPdfContentSourceFailure.PAYLOAD_CONTENT_MISMATCH
        )
    }

    @Test
    fun localTargetBlockPreservesUnavailableCancellation() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val target = ClinicalTargetPoint(
            ts = source.detail24h.throughTs,
            lowMmol = 5.0,
            highMmol = 6.0,
            durationMs = 60_000L,
            endTs = source.detail24h.throughTs + 60_000L,
            cancelled = null
        )
        val prepared = preparedFixture(
            fixture,
            source.copy(detail24h = source.detail24h.copy(targets = listOf(target)))
        )
        val pdfSource = (
            ClinicalPdfContentSourceFactory.create(prepared.payload, prepared.local, complete = null)
                as ClinicalPdfContentSourceResult.Ready
            ).source

        assertThat(blocks(pdfSource).single { it.stableKey == "24h/target/0" }.text)
            .contains("cancelled=none")
    }

    @Test
    fun localEventBlockPreservesEveryExactFieldAndUsesLocalIdentityKey() {
        val fixture = stressFixture(glucoseCount = 4)
        val source = fixture.payload.dataset
        val event = ClinicalEventSummary(
            localId = "local-event-42",
            type = "CUSTOM",
            subtype = "DELIVERY_DIAGNOSTIC",
            startTs = source.detail24h.throughTs - 60_000L,
            endTs = source.detail24h.throughTs,
            severity = "HIGH",
            source = "AAPS",
            title = "Exact local title",
            note = "Exact local note",
            status = "ACTIVE",
            provenance = "therapy_events:aapsDbId=42",
            syntheticUam = true
        )
        val prepared = preparedFixture(fixture, source.copy(eventSummaries = listOf(event)))
        val pdfSource = (
            ClinicalPdfContentSourceFactory.create(prepared.payload, prepared.local, complete = null)
                as ClinicalPdfContentSourceResult.Ready
            ).source
        val block = blocks(pdfSource).single {
            it.stableKey == "30d/event/${event.localId}"
        }

        assertThat(block.text).isEqualTo(
            "localId=${event.localId} | type=${event.type} | subtype=${event.subtype}" +
                " | start=${iso(event.startTs)} | end=${iso(event.endTs)}" +
                " | severity=${event.severity} | source=${event.source}" +
                " | title=${event.title} | note=${event.note}" +
                " | status=${event.status} | provenance=${event.provenance}" +
                " | syntheticUam=${event.syntheticUam}"
        )
        assertThat(prepared.payload.compactJson).doesNotContain(event.localId)
        assertThat(prepared.payload.compactJson).doesNotContain(event.provenance)
    }

    @Test
    fun readySourceDeepSnapshotsMutableInputsAndPreservesExactClinicalText() {
        val fixture = stressFixture(glucoseCount = 4)
        val longEventTitle = "event-title-" + "x".repeat(48)
        val longEventNote = "event-note-" + "y".repeat(489)
        val longBlocker = "blocker-" + "z".repeat(112)
        val blockers = mutableListOf(longBlocker, "secondary-blocker")
        val planned = mutableListOf(
            ClinicalPlannedActivitySummary(
                type = "WALKING",
                intensity = "MEDIUM",
                plannedStartMs = GENERATED_AT - 5L * 60_000L,
                plannedDurationMinutes = 45,
                observedMinutes = 30,
                adherence = "MEASURED",
                targetDecision = "BLOCK_SAFETY_BOUNDS",
                targetBlockers = blockers
            )
        )
        val events = mutableListOf(
            ClinicalEventSummary(
                localId = "private-long-event",
                type = "STRESS",
                subtype = "fixture",
                startTs = GENERATED_AT - 10L * 60_000L,
                endTs = GENERATED_AT - 5L * 60_000L,
                severity = "MEDIUM",
                source = "USER",
                title = longEventTitle,
                note = longEventNote,
                status = "CLOSED",
                provenance = "fixture"
            )
        )
        val enteredCarbs = ClinicalTherapyPoint(
            ts = GENERATED_AT - 2L * 60_000L,
            carbsG = 17.25,
            syntheticUam = false
        )
        val uamCarbs = ClinicalTherapyPoint(
            ts = GENERATED_AT - 60_000L,
            carbsG = 8.5,
            syntheticUam = true
        )
        val therapy = mutableListOf(enteredCarbs, uamCarbs)
        val mutableGlucose = fixture.payload.dataset.glucose30d.toMutableList()
        val dataset = fixture.payload.dataset.copy(
            detail24h = fixture.payload.dataset.detail24h.copy(therapy = therapy),
            glucose30d = mutableGlucose,
            therapy7d = therapy,
            therapy30d = therapy,
            plannedActivities = planned,
            eventSummaries = events
        )
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        val payload = ClinicalReportPayload(
            dataset = dataset,
            compactJson = compact,
            sha256 = ClinicalReportDatasetBuilder.sha256(compact)
        )
        val local = fixture.local.copy(
            requestHash = payload.sha256,
            plannedActivities = planned,
            eventSummaries = events,
            remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        )
        val source = (ClinicalPdfContentSourceFactory.create(
            payload = payload,
            local = local,
            complete = null
        ) as ClinicalPdfContentSourceResult.Ready).source

        mutableGlucose.clear()
        therapy.clear()
        planned.clear()
        events.clear()
        blockers[0] = "mutated-blocker"

        val firstOpening = read(source)
        val secondOpening = read(source)
        val blocks = blocks(source)

        assertThat(secondOpening).isEqualTo(firstOpening)
        assertThat(firstOpening.stableKeys.filter { it.startsWith("30d/glucose/") }).hasSize(4)
        assertThat(blocks.single { it.stableKey == "30d/event/private-long-event" }.text)
            .isEqualTo(
                "localId=private-long-event | type=STRESS | subtype=fixture" +
                    " | start=${iso(GENERATED_AT - 10L * 60_000L)}" +
                    " | end=${iso(GENERATED_AT - 5L * 60_000L)}" +
                    " | severity=MEDIUM | source=USER" +
                    " | title=$longEventTitle | note=$longEventNote" +
                    " | status=CLOSED | provenance=fixture | syntheticUam=false"
            )
        assertThat(blocks.single { it.stableKey == "30d/planned-activity/0" }.text)
            .isEqualTo(
                "${iso(GENERATED_AT - 5L * 60_000L)} | type=WALKING | intensity=MEDIUM" +
                    " | durationMin=45 | observedMin=30 | adherence=MEASURED" +
                    " | targetDecision=BLOCK_SAFETY_BOUNDS | blockers=$longBlocker, secondary-blocker"
            )
        assertThat(blocks.single { it.stableKey == "30d/therapy/0" }.text).isEqualTo(
            "${iso(GENERATED_AT - 2L * 60_000L)} | insulin=none U" +
                " | enteredCarbs=17.2500 g | uamCarbs=none g"
        )
        assertThat(blocks.single { it.stableKey == "30d/therapy/1" }.text).isEqualTo(
            "${iso(GENERATED_AT - 60_000L)} | insulin=none U" +
                " | enteredCarbs=none g | uamCarbs=8.5000 g"
        )
    }

    @Test
    fun stressFixturePlansAndRendersInTwoPassesWithOneActivePage() = runTest {
        val fixture = stressFixture()
        val source = (ClinicalPdfContentSourceFactory.create(
            payload = fixture.payload,
            local = fixture.local,
            complete = null
        ) as ClinicalPdfContentSourceResult.Ready).source
        val trackingSource = TrackingSource(source)
        val pdf = TrackingPdfDocument()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })
        val output = ByteArrayOutputStream()

        val result = renderer.renderSource(trackingSource, output)

        assertThat(result).isInstanceOf(ClinicalPdfRenderResult.Rendered::class.java)
        val rendered = result as ClinicalPdfRenderResult.Rendered
        assertThat(rendered.plan.pageCount).isGreaterThan(1)
        assertThat(rendered.plan.pageCount).isAtMost(ClinicalPdfLimits.DEFAULT.maxPages)
        assertThat(rendered.plan.contentLineCount).isGreaterThan(8_641)
        assertThat(trackingSource.openCount).isEqualTo(2)
        assertThat(trackingSource.visitsByPass).hasSize(2)
        assertThat(trackingSource.visitsByPass[0])
            .containsExactlyElementsIn(trackingSource.visitsByPass[1]).inOrder()
        assertThat(pdf.maxActivePages).isEqualTo(1)
        assertThat(pdf.finishedPages).isEqualTo(rendered.plan.pageCount)
        assertThat(pdf.closed).isTrue()
        assertThat(output.toByteArray()).isEqualTo(PDF_BYTES)
    }

    @Test
    fun tooLargeIsTypedAndDetectedBeforePdfCreationOrOutput() = runTest {
        val fixture = stressFixture()
        val source = (ClinicalPdfContentSourceFactory.create(
            payload = fixture.payload,
            local = fixture.local,
            complete = null
        ) as ClinicalPdfContentSourceResult.Ready).source

        val pageFactory = CountingPdfFactory()
        val pageLimited = ClinicalReportPdfRenderer(
            documentFactory = pageFactory,
            limits = ClinicalPdfLimits(maxPages = 10, maxInputBytes = 16 * 1024 * 1024)
        )
        val pageOutput = ByteArrayOutputStream()
        val pageResult = pageLimited.renderSource(source, pageOutput)

        assertThat(pageResult).isInstanceOf(ClinicalPdfRenderResult.TooLarge::class.java)
        assertThat((pageResult as ClinicalPdfRenderResult.TooLarge).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.PAGES)
        assertThat(pageFactory.createCount).isEqualTo(0)
        assertThat(pageOutput.size()).isEqualTo(0)

        val byteFactory = CountingPdfFactory()
        val byteLimited = ClinicalReportPdfRenderer(
            documentFactory = byteFactory,
            limits = ClinicalPdfLimits(maxPages = 512, maxInputBytes = 512)
        )
        val byteOutput = ByteArrayOutputStream()
        val byteResult = byteLimited.renderSource(source, byteOutput)

        assertThat(byteResult).isInstanceOf(ClinicalPdfRenderResult.TooLarge::class.java)
        assertThat((byteResult as ClinicalPdfRenderResult.TooLarge).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.INPUT_BYTES)
        assertThat(byteFactory.createCount).isEqualTo(0)
        assertThat(byteOutput.size()).isEqualTo(0)
    }

    @Test
    fun defaultPageBoundaryAccepts512AndRejects513BeforePdfCreationOrOutput() = runTest {
        val probe = ClinicalReportPdfRenderer(
            documentFactory = CountingPdfFactory(),
            limits = ClinicalPdfLimits(maxPages = 513, maxInputBytes = 64 * 1024 * 1024)
        )
        val source512 = sourceWithExactPageCount(probe, 512)
        val source513 = sourceWithExactPageCount(probe, 513)

        val acceptedFactory = CountingPdfFactory()
        val acceptedOutput = ByteArrayOutputStream()
        val accepted = ClinicalReportPdfRenderer(acceptedFactory)
            .renderSource(source512, acceptedOutput)

        assertThat(accepted).isInstanceOf(ClinicalPdfRenderResult.Rendered::class.java)
        assertThat((accepted as ClinicalPdfRenderResult.Rendered).plan.pageCount).isEqualTo(512)
        assertThat(acceptedFactory.createCount).isEqualTo(1)
        assertThat(acceptedOutput.toByteArray()).isEqualTo(PDF_BYTES)

        val rejectedFactory = CountingPdfFactory()
        val rejectedOutput = ByteArrayOutputStream()
        val rejected = ClinicalReportPdfRenderer(rejectedFactory)
            .renderSource(source513, rejectedOutput)

        assertThat(rejected).isInstanceOf(ClinicalPdfRenderResult.TooLarge::class.java)
        assertThat((rejected as ClinicalPdfRenderResult.TooLarge).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.PAGES)
        assertThat(rejected.observed).isEqualTo(513L)
        assertThat(rejectedFactory.createCount).isEqualTo(0)
        assertThat(rejectedOutput.size()).isEqualTo(0)
    }

    @Test
    fun defaultFramedInputBoundaryAccepts16MiBAndRejectsOneAdditionalByteBeforeCreate() = runTest {
        val exact = sourceWithExactFramedBytes(ClinicalPdfLimits.DEFAULT.maxInputBytes)
        val over = sourceWithExactFramedBytes(ClinicalPdfLimits.DEFAULT.maxInputBytes + 1)

        val acceptedFactory = CountingPdfFactory()
        val acceptedOutput = ByteArrayOutputStream()
        val accepted = ClinicalReportPdfRenderer(acceptedFactory)
            .renderSource(exact, acceptedOutput)

        assertThat(accepted).isInstanceOf(ClinicalPdfRenderResult.Rendered::class.java)
        assertThat((accepted as ClinicalPdfRenderResult.Rendered).plan.encodedInputBytes)
            .isEqualTo(ClinicalPdfLimits.DEFAULT.maxInputBytes.toLong())
        assertThat(acceptedFactory.createCount).isEqualTo(1)
        assertThat(acceptedOutput.toByteArray()).isEqualTo(PDF_BYTES)

        val rejectedFactory = CountingPdfFactory()
        val rejectedOutput = ByteArrayOutputStream()
        val rejected = ClinicalReportPdfRenderer(rejectedFactory)
            .renderSource(over, rejectedOutput)

        assertThat(rejected).isInstanceOf(ClinicalPdfRenderResult.TooLarge::class.java)
        assertThat((rejected as ClinicalPdfRenderResult.TooLarge).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.INPUT_BYTES)
        assertThat(rejected.observed).isEqualTo(ClinicalPdfLimits.DEFAULT.maxInputBytes + 1L)
        assertThat(rejectedFactory.createCount).isEqualTo(0)
        assertThat(rejectedOutput.size()).isEqualTo(0)
    }

    @Test
    fun estimatedOutputLimitFailsBeforePdfCreationWhenEncodedInputIsWithinBudget() = runTest {
        val source = ListContentSource(
            (0 until 40).map { index ->
                ClinicalPdfContentBlock(
                    text = "short row $index",
                    role = ClinicalPdfContentRole.ITEM,
                    stableKey = "row/$index"
                )
            }
        )
        val probe = ClinicalReportPdfRenderer(CountingPdfFactory())
        val unconstrained = (probe.plan(source) as ClinicalPdfPlanResult.Ready).plan
        val limit = Math.addExact(unconstrained.encodedInputBytes, 1L).toInt()
        assertThat(unconstrained.encodedInputBytes).isLessThan(limit.toLong())
        assertThat(unconstrained.estimatedOutputBytes).isGreaterThan(limit.toLong())
        val factory = CountingPdfFactory()
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(
            documentFactory = factory,
            limits = ClinicalPdfLimits(
                maxPages = 512,
                maxInputBytes = limit,
                maxEstimatedOutputBytes = limit
            )
        )

        val result = renderer.renderSource(source, output)

        assertThat(result).isInstanceOf(ClinicalPdfRenderResult.TooLarge::class.java)
        assertThat((result as ClinicalPdfRenderResult.TooLarge).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.ESTIMATED_OUTPUT_BYTES)
        assertThat(result.observed).isGreaterThan(result.limit)
        assertThat(factory.createCount).isEqualTo(0)
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun framedContentHashSeparatesEmbeddedDelimitersFromMultipleBlocksAndCounts() {
        val single = ListContentSource(
            listOf(
                ClinicalPdfContentBlock(
                    text = "first\nBODY\u0000\u0000second",
                    role = ClinicalPdfContentRole.BODY
                )
            )
        )
        val split = ListContentSource(
            listOf(
                ClinicalPdfContentBlock("first", ClinicalPdfContentRole.BODY),
                ClinicalPdfContentBlock("second", ClinicalPdfContentRole.BODY)
            )
        )
        val renderer = ClinicalReportPdfRenderer(CountingPdfFactory())

        val singlePlan = (renderer.plan(single) as ClinicalPdfPlanResult.Ready).plan
        val splitPlan = (renderer.plan(split) as ClinicalPdfPlanResult.Ready).plan

        assertThat(singlePlan.sourceBlockCount).isEqualTo(1L)
        assertThat(splitPlan.sourceBlockCount).isEqualTo(2L)
        assertThat(singlePlan.contentSha256).isNotEqualTo(splitPlan.contentSha256)
    }

    @Test
    fun cancellationDuringHashingStopsBeforePdfCreationOrOutput() = runTest {
        var checkpoints = 0
        val cancellation = ClinicalPdfCancellationCheckpoint {
            checkpoints += 1
            if (checkpoints >= 40) throw CancellationException("cancel during framed hash")
        }
        val factory = CountingPdfFactory()
        val output = ByteArrayOutputStream()
        val source = CloseTrackingSource(
            listOf(
                ClinicalPdfContentBlock(
                    text = "\u0000".repeat(256 * 1_024),
                    role = ClinicalPdfContentRole.BODY
                )
            )
        )
        val renderer = ClinicalReportPdfRenderer(
            documentFactory = factory,
            cancellationCheckpoint = cancellation
        )

        var thrown: Throwable? = null
        try {
            renderer.renderSource(source, output)
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(factory.createCount).isEqualTo(0)
        assertThat(source.openCount).isEqualTo(1)
        assertThat(source.closeCount).isEqualTo(1)
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun oneHugeBlockIsRejectedAtTypedInputLimitBeforePdfCreationOrOutput() = runTest {
        val source = CloseTrackingSource(
            listOf(
                ClinicalPdfContentBlock(
                    text = "x".repeat(ClinicalPdfLimits.DEFAULT.maxInputBytes + 1),
                    role = ClinicalPdfContentRole.ITEM,
                    stableKey = "huge"
                )
            )
        )
        val factory = CountingPdfFactory()
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(factory)

        val result = renderer.renderSource(source, output)

        assertThat(result).isInstanceOf(ClinicalPdfRenderResult.TooLarge::class.java)
        assertThat((result as ClinicalPdfRenderResult.TooLarge).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.INPUT_BYTES)
        assertThat(factory.createCount).isEqualTo(0)
        assertThat(source.openCount).isEqualTo(1)
        assertThat(source.closeCount).isEqualTo(1)
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun unpairedSurrogateFailsBeforePdfCreationOrOutput() = runTest {
        val factory = CountingPdfFactory()
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(factory)

        var thrown: Throwable? = null
        try {
            renderer.renderSource(
                ListContentSource(
                    listOf(
                        ClinicalPdfContentBlock(
                            text = "invalid-\uD800-text",
                            role = ClinicalPdfContentRole.BODY
                        )
                    )
                ),
                output
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(factory.createCount).isEqualTo(0)
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun plannerReturnsExactStablePlanWithoutCreatingPdf() {
        val source = ListContentSource(
            listOf(
                ClinicalPdfContentBlock("Title", ClinicalPdfContentRole.TITLE),
                ClinicalPdfContentBlock("Heading", ClinicalPdfContentRole.HEADING),
                ClinicalPdfContentBlock("Body", ClinicalPdfContentRole.BODY, "row/0")
            )
        )
        val factory = CountingPdfFactory()
        val renderer = ClinicalReportPdfRenderer(factory)

        val first = renderer.plan(source) as ClinicalPdfPlanResult.Ready
        val second = renderer.plan(source) as ClinicalPdfPlanResult.Ready

        assertThat(second.plan).isEqualTo(first.plan)
        assertThat(first.plan.sourceBlockCount).isEqualTo(3)
        assertThat(first.plan.contentLineCount).isEqualTo(3)
        assertThat(factory.createCount).isEqualTo(0)
    }

    @Test
    fun sourceDriftFailsBeforeOutputAndClosesDocument() = runTest {
        val source = ChangingContentSource()
        val pdf = TrackingPdfDocument()
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        val result = renderer.renderSource(source, output)

        assertThat(result).isEqualTo(ClinicalPdfRenderResult.SourceChanged)
        assertThat(source.openCount).isEqualTo(2)
        assertThat(output.size()).isEqualTo(0)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.closed).isTrue()
    }

    @Test
    fun emptySourceRendersOneClosedPageAndClosesBothCursors() = runTest {
        val source = CloseTrackingSource(emptyList())
        val pdf = TrackingPdfDocument()
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        val result = renderer.renderSource(source, output) as ClinicalPdfRenderResult.Rendered

        assertThat(result.plan.pageCount).isEqualTo(1)
        assertThat(result.plan.sourceBlockCount).isEqualTo(0L)
        assertThat(source.openCount).isEqualTo(2)
        assertThat(source.closeCount).isEqualTo(2)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.finishedPages).isEqualTo(1)
        assertThat(pdf.closed).isTrue()
        assertThat(output.toByteArray()).isEqualTo(PDF_BYTES)
    }

    @Test
    fun sourceGrowthBetweenPassesReturnsDriftWithZeroOutput() = runTest {
        val source = GrowingContentSource()
        val pdf = TrackingPdfDocument()
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        val result = renderer.renderSource(source, output)

        assertThat(result).isEqualTo(ClinicalPdfRenderResult.SourceChanged)
        assertThat(source.closeCount).isEqualTo(2)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.closed).isTrue()
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun startFailureKeepsOutputEmptyAndSuppressesCloseFailure() = runTest {
        val startFailure = IOException("start failed")
        val closeFailure = IOException("close failed")
        val pdf = TrackingPdfDocument(
            startFailure = startFailure,
            closeFailure = closeFailure
        )
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        var thrown: Throwable? = null
        try {
            renderer.renderSource(
                ListContentSource(listOf(ClinicalPdfContentBlock("row", ClinicalPdfContentRole.BODY))),
                output
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isSameInstanceAs(startFailure)
        assertThat(thrown!!.suppressed.asList()).containsExactly(closeFailure)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.closed).isTrue()
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun cancellationClosesActivePageAndDocumentWithoutOutput() = runTest {
        val source = ListContentSource(
            listOf(ClinicalPdfContentBlock("cancel", ClinicalPdfContentRole.BODY))
        )
        val pdf = TrackingPdfDocument(
            drawFailure = CancellationException("test cancellation")
        )
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        var thrown: Throwable? = null
        try {
            renderer.renderSource(source, output)
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.finishCalls).isEqualTo(1)
        assertThat(pdf.closed).isTrue()
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun finalFinishFailureIsNotRetriedAndPublishesNoBytes() = runTest {
        val failure = CancellationException("finish cancelled")
        val pdf = TrackingPdfDocument(finishFailure = failure)
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        var thrown: Throwable? = null
        try {
            renderer.renderSource(
                ListContentSource(listOf(ClinicalPdfContentBlock("row", ClinicalPdfContentRole.BODY))),
                output
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(pdf.finishCalls).isEqualTo(1)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.closed).isTrue()
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun writeFailurePropagatesWithPartialBytesAndSuppressesCloseFailure() = runTest {
        val writeFailure = IOException("write failed")
        val closeFailure = IOException("close failed")
        val partial = "%PDF-partial".toByteArray()
        val pdf = TrackingPdfDocument(
            writeFailure = writeFailure,
            closeFailure = closeFailure,
            partialWriteBeforeFailure = partial
        )
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        var thrown: Throwable? = null
        try {
            renderer.renderSource(
                ListContentSource(listOf(ClinicalPdfContentBlock("row", ClinicalPdfContentRole.BODY))),
                output
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isSameInstanceAs(writeFailure)
        assertThat(thrown!!.suppressed.asList()).containsExactly(closeFailure)
        assertThat(output.toByteArray()).isEqualTo(partial)
        assertThat(pdf.closed).isTrue()
    }

    @Test
    fun cancellationDuringWritePropagatesAfterPartialBytesAndClosesDocument() = runTest {
        val cancellation = CancellationException("write cancelled")
        val partial = "%PDF-cancelled-partial".toByteArray()
        val pdf = TrackingPdfDocument(
            writeFailure = cancellation,
            partialWriteBeforeFailure = partial
        )
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })

        var thrown: Throwable? = null
        try {
            renderer.renderSource(
                ListContentSource(listOf(ClinicalPdfContentBlock("row", ClinicalPdfContentRole.BODY))),
                output
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(output.toByteArray()).isEqualTo(partial)
        assertThat(pdf.activePages).isEqualTo(0)
        assertThat(pdf.closed).isTrue()
    }

    @Test
    fun successfulWriteIsCommitPointDespiteImmediateCancellationAndCloseFailure() = runTest {
        var committed = false
        val closeFailure = IOException("close failed after commit")
        val pdf = TrackingPdfDocument(
            closeFailure = closeFailure,
            afterSuccessfulWrite = { committed = true }
        )
        val checkpoint = ClinicalPdfCancellationCheckpoint {
            if (committed) throw CancellationException("cancelled after commit")
        }
        val output = ByteArrayOutputStream()
        val renderer = ClinicalReportPdfRenderer(
            documentFactory = ClinicalPdfDocumentFactory { pdf },
            cancellationCheckpoint = checkpoint
        )

        val result = renderer.renderSource(
            ListContentSource(listOf(ClinicalPdfContentBlock("row", ClinicalPdfContentRole.BODY))),
            output
        )

        assertThat(result).isInstanceOf(ClinicalPdfRenderResult.Rendered::class.java)
        assertThat(output.toByteArray()).isEqualTo(PDF_BYTES)
        assertThat(pdf.closed).isTrue()
    }

    @Test
    fun longMinifiedUnicodeTokenWrapsWithoutTruncationOrSurrogateSplits() = runTest {
        val raw = "{\"data\":\"" + "x\uD83D\uDE00".repeat(20_000) + "\"}"
        val source = ListContentSource(
            listOf(ClinicalPdfContentBlock(raw, ClinicalPdfContentRole.BODY, "long-json"))
        )
        val pdf = TrackingPdfDocument()
        val renderer = ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })
        val output = ByteArrayOutputStream()

        val result = renderer.renderSource(source, output) as ClinicalPdfRenderResult.Rendered

        assertThat(pdf.drawnBodyText.joinToString("")).isEqualTo(raw)
        pdf.drawnBodyText.forEach(::assertNoUnpairedSurrogates)
        assertThat(result.plan.contentLineCount).isAtMost(raw.codePointCount(0, raw.length).toLong())
        assertThat(pdf.drawnBodyText).hasSize(result.plan.contentLineCount.toInt())
        assertThat(output.toByteArray()).isEqualTo(PDF_BYTES)
    }

    @Test
    fun nearLimitMinifiedUnicodeTokenUsesLinearBoundaryProbeWork() {
        val maxInputBytes = ClinicalPdfLimits.DEFAULT.maxInputBytes
        val raw = "x\uD83D\uDE00".repeat((maxInputBytes - 16 * 1024) / 5)
        assertThat(raw.toByteArray(StandardCharsets.UTF_8).size)
            .isAtLeast(maxInputBytes - 20 * 1024)
        var boundaryProbes = 0L
        val rebuilt = StringBuilder(raw.length)
        var start = 0
        while (start < raw.length) {
            var measuredEnd = (start + 97).coerceAtMost(raw.length)
            if (
                measuredEnd < raw.length &&
                raw[measuredEnd - 1].isHighSurrogate() &&
                raw[measuredEnd].isLowSurrogate()
            ) {
                measuredEnd -= 1
            }
            val end = findClinicalPdfWordBoundary(
                text = raw,
                start = start,
                end = measuredEnd,
                boundaryProbe = {
                    boundaryProbes += 1L
                    check(boundaryProbes <= raw.length.toLong()) {
                        "Boundary search exceeded the linear work bound"
                    }
                }
            )
            rebuilt.append(raw, start, end)
            start = end
        }

        assertThat(rebuilt.toString()).isEqualTo(raw)
        assertThat(boundaryProbes).isAtMost(raw.length.toLong())
    }

    private fun assertFailure(
        result: ClinicalPdfContentSourceResult,
        expected: ClinicalPdfContentSourceFailure
    ) {
        assertThat(result).isInstanceOf(ClinicalPdfContentSourceResult.Failed::class.java)
        assertThat((result as ClinicalPdfContentSourceResult.Failed).reason).isEqualTo(expected)
    }

    private fun assertInvalidPreparedDataset(
        fixture: StressFixture,
        dataset: ClinicalReportDataset
    ) {
        val prepared = preparedFixture(fixture, dataset)
        assertFailure(
            ClinicalPdfContentSourceFactory.create(
                prepared.payload,
                prepared.local,
                complete = null
            ),
            ClinicalPdfContentSourceFailure.INVALID_PREPARED_PAYLOAD
        )
    }

    private fun preparedFixture(
        fixture: StressFixture,
        dataset: ClinicalReportDataset
    ): StressFixture {
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        val payload = ClinicalReportPayload(
            dataset = dataset,
            compactJson = compact,
            sha256 = ClinicalReportDatasetBuilder.sha256(compact)
        )
        return StressFixture(
            payload = payload,
            local = fixture.local.copy(
                summary24h = dataset.summary24h,
                summary7d = dataset.summary7d,
                summary30d = dataset.summary30d,
                requestHash = payload.sha256,
                generatedAt = dataset.generatedAt,
                zoneId = dataset.zoneId,
                energyProfile = dataset.energyProfile,
                plannedActivities = dataset.plannedActivities,
                forecastQuality = dataset.detail24h.forecastQuality,
                eventSummaries = dataset.eventSummaries,
                eventTypeAssociations = dataset.eventTypeAssociations,
                remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
            )
        )
    }

    private fun read(source: ClinicalPdfContentSource): SourceRead {
        val digest = MessageDigest.getInstance("SHA-256")
        val keys = mutableListOf<String>()
        val text = StringBuilder()
        var count = 0L
        source.open().use { cursor ->
            while (true) {
                val block = cursor.next() ?: break
                count += 1L
                block.stableKey?.let(keys::add)
                val canonical = listOf(
                    block.role.name,
                    block.stableKey.orEmpty(),
                    block.text
                ).joinToString("\u0000") + "\n"
                digest.update(canonical.toByteArray(StandardCharsets.UTF_8))
                text.appendLine(block.text)
            }
        }
        return SourceRead(
            digest = digest.digest().joinToString("") { "%02x".format(it) },
            blockCount = count,
            stableKeys = keys,
            text = text.toString()
        )
    }

    private fun blocks(source: ClinicalPdfContentSource): List<ClinicalPdfContentBlock> = buildList {
        source.open().use { cursor ->
            while (true) add(cursor.next() ?: break)
        }
    }

    private fun sourceWithExactPageCount(
        renderer: ClinicalReportPdfRenderer,
        targetPages: Int
    ): ListContentSource {
        var low = 1
        var high = 64_000
        while (low < high) {
            val middle = low + (high - low) / 2
            val pageCount = when (val result = renderer.plan(rowSource(middle))) {
                is ClinicalPdfPlanResult.Ready -> result.plan.pageCount
                is ClinicalPdfPlanResult.TooLarge -> {
                    check(result.dimension == ClinicalPdfLimitDimension.PAGES)
                    Int.MAX_VALUE
                }
            }
            if (pageCount < targetPages) low = middle + 1 else high = middle
        }
        val source = rowSource(low)
        assertThat((renderer.plan(source) as ClinicalPdfPlanResult.Ready).plan.pageCount)
            .isEqualTo(targetPages)
        return source
    }

    private fun rowSource(count: Int) = ListContentSource(
        (0 until count).map { index ->
            ClinicalPdfContentBlock("row-$index", ClinicalPdfContentRole.BODY, "row/$index")
        }
    )

    private fun sourceWithExactFramedBytes(targetBytes: Int): ListContentSource {
        val generous = ClinicalReportPdfRenderer(
            documentFactory = CountingPdfFactory(),
            limits = ClinicalPdfLimits(maxPages = 512, maxInputBytes = Int.MAX_VALUE)
        )
        val baseline = ListContentSource(
            listOf(ClinicalPdfContentBlock("x", ClinicalPdfContentRole.BODY, ""))
        )
        val baselineBytes = (generous.plan(baseline) as ClinicalPdfPlanResult.Ready)
            .plan.encodedInputBytes
        val fillerBytes = targetBytes.toLong() - baselineBytes
        require(fillerBytes in 0..Int.MAX_VALUE.toLong())
        val source = ListContentSource(
            listOf(
                ClinicalPdfContentBlock(
                    text = "x",
                    role = ClinicalPdfContentRole.BODY,
                    stableKey = "k".repeat(fillerBytes.toInt())
                )
            )
        )
        val measured = (generous.plan(source) as ClinicalPdfPlanResult.Ready).plan.encodedInputBytes
        assertThat(measured).isEqualTo(targetBytes.toLong())
        return source
    }

    private fun unsafeAssociation(
        source: ClinicalEventTypeAssociation,
        field: String,
        value: Any
    ): ClinicalEventTypeAssociation {
        val json = Gson().toJsonTree(source).asJsonObject
        when (value) {
            is Boolean -> json.addProperty(field, value)
            is Number -> json.addProperty(field, value)
            else -> error("Unsupported fixture value")
        }
        return Gson().fromJson(json, ClinicalEventTypeAssociation::class.java)
    }

    private fun iso(timestamp: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

    private fun assertNoUnpairedSurrogates(value: String) {
        value.forEachIndexed { index, character ->
            if (character.isHighSurrogate()) {
                assertThat(value.getOrNull(index + 1)?.isLowSurrogate()).isTrue()
            }
            if (character.isLowSurrogate()) {
                assertThat(value.getOrNull(index - 1)?.isHighSurrogate()).isTrue()
            }
        }
    }

    private fun eventAt(title: String, timestamp: Long) = ClinicalEventSummary(
        localId = "private-$title",
        type = "CUSTOM",
        subtype = "boundary",
        startTs = timestamp,
        endTs = timestamp,
        severity = "LOW",
        source = "USER",
        title = title,
        note = null,
        status = "CLOSED",
        provenance = "fixture"
    )

    private fun activityAt(label: String, timestamp: Long) = ClinicalPlannedActivitySummary(
        type = "WALKING",
        intensity = "MEDIUM",
        plannedStartMs = timestamp,
        plannedDurationMinutes = 1,
        adherence = "NOT_MEASURED",
        targetDecision = "NO_DECISION",
        targetBlockers = listOf(label)
    )

    private fun advisoryResult(requestHash: String, evidenceValue: Double) = ClinicalOpenAiResult(
        report = ClinicalAdvisoryReport(
            summary7dStatus = ClinicalSummaryStatus.STABLE,
            summary30dStatus = ClinicalSummaryStatus.STABLE,
            dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
            patterns = listOf(
                ClinicalFinding(
                    topic = ClinicalPatternTopic.GLUCOSE_STABILITY,
                    period = ClinicalEvidencePeriod.LAST_7_DAYS,
                    direction = ClinicalPatternDirection.STABLE,
                    confidence = ClinicalFindingConfidence.HIGH,
                    timeBand = ClinicalTimeBand.ALL_DAY,
                    evidenceMetric = ClinicalEvidenceMetric.MEAN_GLUCOSE,
                    evidenceValue = evidenceValue
                )
            ),
            safetyObservations = emptyList(),
            recommendations = emptyList(),
            careTeamQuestions = emptyList()
        ),
        metadata = ClinicalOpenAiMetadata(
            model = "gpt-5",
            requestedModel = "gpt-5",
            systemFingerprint = null,
            schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
            schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
            datasetSchemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            requestHash = requestHash,
            chunkCount = 1,
            usedSynthesis = false
        )
    )

    private fun stressFixture(glucoseCount: Int = 8_641): StressFixture {
        val from30d = GENERATED_AT - 30L * DAY_MS
        val glucose30d = (0 until glucoseCount).map { index ->
            ClinicalGlucosePoint(
                ts = from30d + index * FIVE_MINUTES_MS,
                mmol = 4.5 + (index % 80) / 20.0
            )
        }
        val glucose7d = glucose30d.filter { it.ts >= GENERATED_AT - 7L * DAY_MS }
        val detailGlucose = glucose30d.filter { it.ts >= GENERATED_AT - DAY_MS }
        val therapy30d = (0 until 360).map { index ->
            ClinicalTherapyPoint(
                ts = from30d + index * 2L * 60L * 60L * 1_000L,
                insulinU = if (index % 2 == 0) 0.5 + index % 5 else null,
                carbsG = if (index % 2 == 1) 5.0 + index % 30 else null,
                syntheticUam = index % 4 == 1,
                insulinEvidence = if (index % 2 == 0) {
                    ClinicalInsulinEvidence.CONFIRMED
                } else {
                    null
                }
            )
        }.filter { it.ts <= GENERATED_AT }
        val targets30d = (0 until 60).map { index ->
            ClinicalTargetPoint(
                ts = from30d + index * 12L * 60L * 60L * 1_000L,
                lowMmol = 5.5,
                highMmol = 6.5,
                durationMs = 60L * 60L * 1_000L
            )
        }.filter { it.ts <= GENERATED_AT }
        val events = (0 until 30).map { index ->
            val start = from30d + index * DAY_MS
            ClinicalEventSummary(
                localId = "private-$index",
                type = if (index % 2 == 0) "ACTIVITY" else "STRESS",
                subtype = "fixture",
                startTs = start,
                endTs = start + 30L * 60L * 1_000L,
                severity = "MEDIUM",
                source = "USER",
                title = "Synthetic fixture event $index",
                note = "Association context only",
                status = "CLOSED",
                provenance = "fixture"
            )
        }
        val planned = (0 until 16).map { index ->
            ClinicalPlannedActivitySummary(
                type = "WALKING",
                intensity = "MEDIUM",
                plannedStartMs = from30d + index * 2L * DAY_MS,
                plannedDurationMinutes = 45,
                observedMinutes = 30,
                adherence = "MEASURED"
            )
        }
        val detail = ClinicalDetailWindow(
            fromTs = GENERATED_AT - DAY_MS,
            throughTs = GENERATED_AT,
            glucose = detailGlucose,
            calibratedGlucose = detailGlucose.map { it.copy(mmol = it.mmol + 0.1) },
            therapy = therapy30d.filter { it.ts >= GENERATED_AT - DAY_MS },
            targets = targets30d.filter { it.ts >= GENERATED_AT - DAY_MS },
            forecasts = detailGlucose.takeLast(30).mapIndexed { index, point ->
                ClinicalForecastPoint(point.ts, 30, 6.0 + index / 100.0, 5.5, 6.8)
            },
            telemetry = detailGlucose.takeLast(120).flatMapIndexed { index, point ->
                listOf(
                    ClinicalTelemetryPoint(
                        point.ts,
                        "activity_ratio",
                        1.0 + index / 100.0,
                        "TRUSTED",
                        ClinicalTelemetryOrigin.LOCAL_ACTIVITY
                    ),
                    ClinicalTelemetryPoint(
                        point.ts,
                        "steps_count",
                        index.toDouble(),
                        "TRUSTED",
                        ClinicalTelemetryOrigin.LOCAL_ACTIVITY
                    )
                )
            }
        )
        val summary24h = summary(1, detailGlucose.size.coerceAtMost(289))
        val summary7d = summary(7, glucose7d.size.coerceAtMost(2_017))
        val summary30d = summary(30, glucose30d.size.coerceAtMost(8_641)).copy(
            probableMealWindows = listOf(
                io.aaps.copilot.domain.eating.ProbableEatingWindow(
                    medianMinuteOfDay = 480,
                    startMinuteOfDay = 450,
                    endMinuteOfDay = 510,
                    iqrMinutes = 60,
                    supportDays = 10,
                    lookbackDays = 14,
                    episodeCount = 12,
                    enteredEpisodeCount = 8,
                    uamEpisodeCount = 4,
                    confidencePct = 71.0
                )
            )
        )
        val dataset = ClinicalReportDataset(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = GENERATED_AT,
            zoneId = "Asia/Tbilisi",
            detail24h = detail,
            glucose7d = glucose7d,
            therapy7d = therapy30d.filter { it.ts >= GENERATED_AT - 7L * DAY_MS },
            targets7d = targets30d.filter { it.ts >= GENERATED_AT - 7L * DAY_MS },
            glucose30d = glucose30d,
            therapy30d = therapy30d,
            targets30d = targets30d,
            summary24h = summary24h,
            summary7d = summary7d,
            summary30d = summary30d,
            energyProfile = ClinicalEnergyProfileSummary(),
            plannedActivities = planned,
            eventSummaries = events,
            eventTypeAssociations = listOf(
                ClinicalEventTypeAssociation(
                    type = "STRESS",
                    eventCount = 12,
                    totalDurationMinutes = 360,
                    glucose = ClinicalAssociationMetric(12, 6.4, "mmol/L"),
                    trend = ClinicalAssociationMetric(12, 0.1, "mmol/L per event"),
                    uam = ClinicalAssociationMetric(4, 8.0, "g"),
                    isf = ClinicalAssociationMetric(8, 3.0, "mmol/L/U"),
                    cr = ClinicalAssociationMetric(8, 10.0, "g/U"),
                    forecastError = ClinicalAssociationMetric(12, 0.4, "mmol/L")
                )
            )
        )
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        val payload = ClinicalReportPayload(
            dataset = dataset,
            compactJson = compact,
            sha256 = ClinicalReportDatasetBuilder.sha256(compact)
        )
        val local = ClinicalLocalReport(
            summary24h = summary24h,
            summary7d = summary7d,
            summary30d = summary30d,
            requestHash = payload.sha256,
            generatedAt = GENERATED_AT,
            zoneId = dataset.zoneId,
            energyProfile = dataset.energyProfile,
            plannedActivities = planned,
            forecastQuality = detail.forecastQuality,
            eventSummaries = events,
            eventTypeAssociations = dataset.eventTypeAssociations,
            remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        )
        return StressFixture(payload, local)
    }

    private fun summary(days: Int, covered: Int): ClinicalPeriodSummary {
        val expected = days * 288
        val boundedCovered = covered.coerceAtMost(expected + 1)
        return ClinicalPeriodSummary(
            days = days,
            fromTs = GENERATED_AT - days * DAY_MS,
            throughTs = GENERATED_AT,
            coveragePct = (boundedCovered.coerceAtMost(expected) * 100.0 / expected),
            meanMmol = 6.2,
            medianMmol = 6.1,
            coefficientOfVariationPct = 22.0,
            timeBelow4Pct = 1.0,
            timeInRangePct = 88.0,
            timeAboveRangePct = 11.0,
            totalInsulinU = days * 20.0,
            totalCarbsG = days * 50.0,
            meanTargetMmol = 6.0,
            weekdayPattern = emptyList(),
            weekendPattern = emptyList(),
            quality = ClinicalDataQuality(
                expectedBuckets = expected,
                coveredBuckets = boundedCovered,
                missingBuckets = (expected - boundedCovered).coerceAtLeast(0),
                maxGapMinutes = 5,
                rejected = ClinicalRejectionCounts()
            ),
            enteredCarbsG = days * 40.0,
            uamCarbsG = days * 10.0
        )
    }

    private data class StressFixture(
        val payload: ClinicalReportPayload,
        val local: ClinicalLocalReport
    )

    private data class BoundaryFixture(
        val label: String,
        val fromTs: Long,
        val throughTs: Long,
        val generatedFromTs: Long
    )

    private data class EventNames(
        val type: String? = null,
        val source: String? = null,
        val severity: String? = null,
        val status: String? = null
    )

    private data class SourceRead(
        val digest: String,
        val blockCount: Long,
        val stableKeys: List<String>,
        val text: String
    )

    private class TrackingSource(
        private val delegate: ClinicalPdfContentSource
    ) : ClinicalPdfContentSource {
        override val identity = delegate.identity
        var openCount = 0
            private set
        val visitsByPass = mutableListOf<MutableList<String>>()

        override fun open(): ClinicalPdfContentCursor {
            openCount += 1
            val visits = mutableListOf<String>()
            visitsByPass += visits
            val cursor = delegate.open()
            return object : ClinicalPdfContentCursor {
                override fun next(): ClinicalPdfContentBlock? = cursor.next()?.also { block ->
                    block.stableKey?.let(visits::add)
                }

                override fun close() = cursor.close()
            }
        }
    }

    private class ListContentSource(
        private val blocks: List<ClinicalPdfContentBlock>
    ) : ClinicalPdfContentSource {
        override val identity = io.aaps.copilot.report.ClinicalPdfContentIdentity(
            requestSha256 = "a".repeat(64),
            generatedAt = GENERATED_AT,
            zoneId = "UTC",
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION
        )

        override fun open(): ClinicalPdfContentCursor {
            val iterator = blocks.iterator()
            return object : ClinicalPdfContentCursor {
                override fun next(): ClinicalPdfContentBlock? =
                    if (iterator.hasNext()) iterator.next() else null

                override fun close() = Unit
            }
        }
    }

    private class ChangingContentSource : ClinicalPdfContentSource {
        override val identity = io.aaps.copilot.report.ClinicalPdfContentIdentity(
            requestSha256 = "b".repeat(64),
            generatedAt = GENERATED_AT,
            zoneId = "UTC",
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION
        )
        var openCount = 0
            private set

        override fun open(): ClinicalPdfContentCursor {
            openCount += 1
            val block = ClinicalPdfContentBlock(
                text = if (openCount == 1) "first" else "second",
                role = ClinicalPdfContentRole.BODY
            )
            return ListContentSource(listOf(block)).open()
        }
    }

    private class CloseTrackingSource(
        private val blocks: List<ClinicalPdfContentBlock>
    ) : ClinicalPdfContentSource {
        override val identity = io.aaps.copilot.report.ClinicalPdfContentIdentity(
            requestSha256 = "e".repeat(64),
            generatedAt = GENERATED_AT,
            zoneId = "UTC",
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION
        )
        var openCount = 0
            private set
        var closeCount = 0
            private set

        override fun open(): ClinicalPdfContentCursor {
            openCount += 1
            val iterator = blocks.iterator()
            return object : ClinicalPdfContentCursor {
                override fun next(): ClinicalPdfContentBlock? =
                    if (iterator.hasNext()) iterator.next() else null

                override fun close() {
                    closeCount += 1
                }
            }
        }
    }

    private class GrowingContentSource : ClinicalPdfContentSource {
        override val identity = io.aaps.copilot.report.ClinicalPdfContentIdentity(
            requestSha256 = "1".repeat(64),
            generatedAt = GENERATED_AT,
            zoneId = "UTC",
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION
        )
        private var openCount = 0
        var closeCount = 0
            private set

        override fun open(): ClinicalPdfContentCursor {
            openCount += 1
            val count = if (openCount == 1) 1 else 2
            val iterator = (0 until count).map { index ->
                ClinicalPdfContentBlock("row-$index", ClinicalPdfContentRole.BODY, "row/$index")
            }.iterator()
            return object : ClinicalPdfContentCursor {
                override fun next(): ClinicalPdfContentBlock? =
                    if (iterator.hasNext()) iterator.next() else null

                override fun close() {
                    closeCount += 1
                }
            }
        }
    }

    private class CountingPdfFactory : ClinicalPdfDocumentFactory {
        var createCount = 0
            private set

        override fun create(): ClinicalPdfDocument {
            createCount += 1
            return TrackingPdfDocument()
        }
    }

    private class TrackingPdfDocument(
        private val startFailure: Throwable? = null,
        private val drawFailure: Throwable? = null,
        private val finishFailure: Throwable? = null,
        private val writeFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val partialWriteBeforeFailure: ByteArray = byteArrayOf(),
        private val afterSuccessfulWrite: () -> Unit = {}
    ) : ClinicalPdfDocument {
        private var activePage: TrackingPdfPage? = null
        var activePages = 0
            private set
        var maxActivePages = 0
            private set
        var finishedPages = 0
            private set
        var closed = false
            private set
        var finishCalls = 0
            private set
        val drawnBodyText = mutableListOf<String>()

        override fun startPage(pageNumber: Int): ClinicalPdfPage {
            check(!closed)
            check(activePage == null)
            startFailure?.let { throw it }
            activePages += 1
            maxActivePages = maxOf(maxActivePages, activePages)
            return TrackingPdfPage(this).also { activePage = it }
        }

        override fun finishPage(page: ClinicalPdfPage) {
            finishCalls += 1
            val tracked = requireNotNull(page as? TrackingPdfPage)
            require(tracked.owner === this)
            check(activePage === tracked)
            check(!tracked.finishAttempted)
            tracked.finishAttempted = true
            activePage = null
            activePages -= 1
            check(activePages >= 0)
            finishFailure?.let { throw it }
            tracked.finished = true
            finishedPages += 1
        }

        override fun writeTo(output: OutputStream) {
            check(!closed)
            check(activePage == null)
            writeFailure?.let { failure ->
                output.write(partialWriteBeforeFailure)
                throw failure
            }
            output.write(PDF_BYTES)
            afterSuccessfulWrite()
        }

        override fun close() {
            check(!closed)
            closed = true
            activePage = null
            activePages = 0
            closeFailure?.let { throw it }
        }

        private class TrackingPdfPage(
            val owner: TrackingPdfDocument
        ) : ClinicalPdfPage {
            var finishAttempted = false
            var finished = false

            override fun drawText(text: String, x: Float, baseline: Float, paint: Paint) {
                checkDrawable()
                owner.drawFailure?.let { throw it }
                if (paint.textSize == 10.5f) owner.drawnBodyText += text
            }

            override fun drawLine(
                startX: Float,
                startY: Float,
                stopX: Float,
                stopY: Float,
                paint: Paint
            ) {
                checkDrawable()
                owner.drawFailure?.let { throw it }
            }

            private fun checkDrawable() {
                check(!owner.closed)
                check(owner.activePage === this)
                check(!finishAttempted)
                check(!finished)
            }
        }
    }

    private companion object {
        const val GENERATED_AT = 1_800_000_000_000L
        const val DAY_MS = 86_400_000L
        const val FIVE_MINUTES_MS = 300_000L
        val PDF_BYTES = "%PDF-foundation".toByteArray()
    }
}
