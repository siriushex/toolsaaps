package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.ForecastSnapshotResolver
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecompositionCodec
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.testSensitivityRuntimeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SensitivitySourceChangeApplicationTest {

    @Test
    fun viewModelDelegatesSourceMutationAndAcceptanceToOneRepositoryCommand() = runTest {
        val accepted = testSensitivityRuntimeSnapshot(
            settingsRevision = 12L,
            cycleId = "cycle-12"
        )
        val calls = mutableListOf<String>()

        val result = applySensitivitySourceChange(
            metric = SensitivityMetricKind.ISF,
            requestedSource = SensitivitySourcePreference.COPILOT,
            applyAtomically = { _ ->
                calls += "ISF:COPILOT"
                accepted
            }
        )

        assertThat(result === accepted).isTrue()
        assertThat(calls).containsExactly("ISF:COPILOT")
    }

    @Test
    fun overviewRejectsNewEffectiveSensitivityUntilForecastCycleIsAccepted() {
        val snapshot = testSensitivityRuntimeSnapshot(
            settingsRevision = 21L,
            cycleId = "cycle-21",
            timestamp = 9_000L
        )
        val forecasts = listOf(5, 30, 60).associateWith { horizon ->
            ForecastEntity(
                timestamp = 9_000L + horizon * 60_000L,
                horizonMinutes = horizon,
                valueMmol = 6.0,
                ciLow = 5.0,
                ciHigh = 7.0,
                modelVersion = "test"
            )
        }

        assertThat(
            ForecastSnapshotResolver.resolveAcceptedTuple(
                snapshot = snapshot,
                currentSettings = identity(21L),
                authoritativeNowTs = 10_000L,
                forecasts = forecasts.values.toList(),
                telemetry = acceptedTelemetry(
                    cycleId = "old-cycle",
                    revision = 20L,
                    forecastTimestamp = 8_000L,
                    forecasts = forecasts.values.toList()
                ).values.toList()
            ).sensitivity
        ).isNull()
        assertThat(
            ForecastSnapshotResolver.resolveAcceptedTuple(
                snapshot = snapshot,
                currentSettings = identity(21L),
                authoritativeNowTs = 10_000L,
                forecasts = forecasts.values.toList(),
                telemetry = acceptedTelemetry(
                    cycleId = "cycle-21",
                    revision = 21L,
                    forecastTimestamp = 9_000L,
                    forecasts = forecasts.values.toList()
                ).values.toList()
            ).sensitivity
        ).isEqualTo(snapshot)
    }

    @Test
    fun primaryOverviewRequiresCommittedPublicationStateForOtherwiseExactTuple() {
        val snapshot = testSensitivityRuntimeSnapshot(
            settingsRevision = 22L,
            cycleId = "cycle-22",
            timestamp = 9_000L
        )
        val forecasts = forecastRows(generationTimestamp = 9_000L, base = 6.0)
        val committed = acceptedTelemetry(
            cycleId = snapshot.forecastCycleId,
            revision = snapshot.settingsRevision,
            forecastTimestamp = snapshot.timestamp,
            forecasts = forecasts
        )

        assertThat(
            ForecastSnapshotResolver.resolveAcceptedTuple(
                snapshot = snapshot,
                currentSettings = identity(22L),
                authoritativeNowTs = 10_000L,
                forecasts = forecasts,
                telemetry = committed.values.toList()
            ).sensitivity
        ).isEqualTo(snapshot)
        assertThat(
            ForecastSnapshotResolver.resolveAcceptedTuple(
                snapshot = snapshot,
                currentSettings = identity(22L),
                authoritativeNowTs = 10_000L,
                forecasts = forecasts,
                telemetry = committed.values.filterNot {
                    it.key == SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
                }
            ).sensitivity
        ).isNull()
    }

    @Test
    fun primaryOverviewKeepsPreviousCommittedTupleWhenNewerPublicationIsPending() {
        val snapshot = testSensitivityRuntimeSnapshot(
            settingsRevision = 23L,
            cycleId = "cycle-23",
            timestamp = 9_000L
        )
        val forecasts = forecastRows(generationTimestamp = 9_000L, base = 6.0)
        val committed = acceptedTelemetry(
            cycleId = snapshot.forecastCycleId,
            revision = snapshot.settingsRevision,
            forecastTimestamp = snapshot.timestamp,
            forecasts = forecasts,
            markerTimestamp = 10_000L
        ).values
        val pending = acceptedTelemetry(
            cycleId = "cycle-24-pending",
            revision = 24L,
            forecastTimestamp = 10_500L,
            forecasts = forecastRows(generationTimestamp = 10_500L, base = 8.0),
            markerTimestamp = 11_000L,
            publicationState = "PENDING"
        ).values

        val resolved = ForecastSnapshotResolver.resolveAcceptedTuple(
            snapshot = snapshot,
            currentSettings = identity(23L),
            authoritativeNowTs = 11_000L,
            forecasts = forecasts,
            telemetry = committed + pending
        )

        assertThat(resolved.sensitivity).isEqualTo(snapshot)
        assertThat(resolved.forecastsByHorizon.keys).containsExactly(5, 30, 60)
    }

    @Test
    fun overviewRejectsSameTimestampForecastOverwriteWithDifferentValues() {
        val snapshot = testSensitivityRuntimeSnapshot(
            settingsRevision = 21L,
            cycleId = "cycle-21",
            timestamp = 9_000L
        )
        val acceptedRows = forecastRows(generationTimestamp = 9_000L, base = 6.0)
        val overwrittenRows = forecastRows(generationTimestamp = 9_000L, base = 8.0)

        val resolved = ForecastSnapshotResolver.resolveAcceptedTuple(
            snapshot = snapshot,
            currentSettings = identity(21L),
            authoritativeNowTs = 9_000L,
            forecasts = overwrittenRows,
            telemetry = acceptedTelemetry(
                cycleId = "cycle-21",
                revision = 21L,
                forecastTimestamp = 9_000L,
                forecasts = acceptedRows
            ).values.toList()
        ).sensitivity

        assertThat(resolved).isNull()
    }

    @Test
    fun overviewRejectsMarkerFieldsThatDoNotBelongToOneAtomicTuple() {
        val snapshot = testSensitivityRuntimeSnapshot(
            settingsRevision = 21L,
            cycleId = "cycle-21",
            timestamp = 9_000L
        )
        val forecasts = forecastRows(generationTimestamp = 9_000L, base = 6.0)
        val mixedMarkers = acceptedTelemetry(
            cycleId = "cycle-21",
            revision = 21L,
            forecastTimestamp = 9_000L,
            forecasts = forecasts
        ).toMutableMap().apply {
            this[SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY] =
                getValue(SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY).copy(timestamp = 10_001L)
        }

        assertThat(
            ForecastSnapshotResolver.resolveAcceptedTuple(
                snapshot = snapshot,
                currentSettings = identity(21L),
                authoritativeNowTs = 10_000L,
                forecasts = forecasts,
                telemetry = mixedMarkers.values.toList()
            ).sensitivity
        ).isNull()
    }

    @Test
    fun failedAtomicSourceCommandPropagatesWithoutAnyViewModelSideMutation() = runTest {
        var commands = 0

        val error = runCatching {
            applySensitivitySourceChange(
                metric = SensitivityMetricKind.ISF,
                requestedSource = SensitivitySourcePreference.COPILOT,
                applyAtomically = { _ ->
                    commands += 1
                    error("calculation-only cycle did not publish")
                }
            )
        }.exceptionOrNull()

        assertThat(commands).isEqualTo(1)
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("did not publish")
    }

    @Test
    fun sourceChangeRejectsAcceptedCurrentRevisionFromAConcurrentDifferentSelection() = runTest {
        val acceptedForOtherSelection = testSensitivityRuntimeSnapshot(
            settingsRevision = 13L,
            cycleId = "cycle-other"
        )

        val error = runCatching {
            applySensitivitySourceChange(
                metric = SensitivityMetricKind.ISF,
                requestedSource = SensitivitySourcePreference.AAPS,
                applyAtomically = { _ -> acceptedForOtherSelection }
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("requested source mismatch")
    }

    private fun acceptedTelemetry(
        cycleId: String,
        revision: Long,
        forecastTimestamp: Long,
        forecasts: List<ForecastEntity>,
        markerTimestamp: Long = 10_000L,
        publicationState: String = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
    ): Map<String, TelemetrySampleEntity> = mapOf(
        SENSITIVITY_ACCEPTED_CYCLE_ID_KEY to telemetry(
            key = SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
            valueText = cycleId,
            timestamp = markerTimestamp
        ),
        SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY to telemetry(
            key = SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
            valueDouble = revision.toDouble(),
            timestamp = markerTimestamp
        ),
        SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY to telemetry(
            key = SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
            valueDouble = forecastTimestamp.toDouble(),
            timestamp = markerTimestamp
        ),
        SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY to telemetry(
            key = SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
            valueText = SensitivityAcceptedForecastDecompositionCodec.encode(
                SensitivityAcceptedForecastDecomposition.unavailable()
            ),
            timestamp = markerTimestamp
        ),
        SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY to telemetry(
            key = SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
            valueText = SensitivityAcceptedForecastDigest.compute(
                cycleId = cycleId,
                settingsRevision = revision,
                forecasts = forecasts.map { it.acceptedDigestRow() },
                decomposition = SensitivityAcceptedForecastDecomposition.unavailable()
            ),
            timestamp = markerTimestamp
        ),
        SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY to telemetry(
            key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
            valueText = publicationState,
            timestamp = markerTimestamp
        )
    )

    private fun identity(revision: Long) = SensitivityRuntimeSettingsIdentity(
        revision = revision,
        isfSource = SensitivitySourcePreference.COPILOT,
        crSource = SensitivitySourcePreference.COPILOT
    )

    private fun forecastRows(generationTimestamp: Long, base: Double) = listOf(5, 30, 60).map { horizon ->
        ForecastEntity(
            timestamp = generationTimestamp + horizon * 60_000L,
            horizonMinutes = horizon,
            valueMmol = base + horizon / 100.0,
            ciLow = base - 0.5,
            ciHigh = base + 0.5,
            modelVersion = "test"
        )
    }

    private fun ForecastEntity.acceptedDigestRow() = SensitivityAcceptedForecastRow(
        horizonMinutes = horizonMinutes,
        targetTimestamp = timestamp,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )

    private fun telemetry(
        key: String,
        valueDouble: Double? = null,
        valueText: String? = null,
        timestamp: Long = 10_000L
    ) = TelemetrySampleEntity(
        id = key,
        timestamp = timestamp,
        source = "copilot_sensitivity_cycle",
        key = key,
        valueDouble = valueDouble,
        valueText = valueText,
        unit = null,
        quality = "OK"
    )
}
