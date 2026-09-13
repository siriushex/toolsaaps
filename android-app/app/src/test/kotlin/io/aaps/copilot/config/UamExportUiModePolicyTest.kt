package io.aaps.copilot.config

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.AapsCarbEntry
import io.aaps.copilot.data.repository.AapsCarbGateway
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.UamExportCoordinator
import io.aaps.copilot.data.repository.UamExportPolicyInput
import io.aaps.copilot.data.repository.UamExportReservationStore
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamExportMode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UamExportUiModePolicyTest {

    @Test
    fun resolveExhaustivelyMapsPersistedSettingsWithoutPromotingLegacyStatesToAuto() {
        val expected = mapOf(
            Triple(false, UamExportMode.OFF, true) to UamExportUiMode.OFF,
            Triple(false, UamExportMode.OFF, false) to UamExportUiMode.OFF,
            Triple(false, UamExportMode.CONFIRMED_ONLY, true) to UamExportUiMode.OFF,
            Triple(false, UamExportMode.CONFIRMED_ONLY, false) to UamExportUiMode.OFF,
            Triple(false, UamExportMode.INCREMENTAL, true) to UamExportUiMode.OFF,
            Triple(false, UamExportMode.INCREMENTAL, false) to UamExportUiMode.OFF,
            Triple(true, UamExportMode.OFF, true) to UamExportUiMode.OFF,
            Triple(true, UamExportMode.OFF, false) to UamExportUiMode.OFF,
            Triple(true, UamExportMode.CONFIRMED_ONLY, true) to UamExportUiMode.OBSERVE,
            Triple(true, UamExportMode.CONFIRMED_ONLY, false) to UamExportUiMode.OBSERVE,
            Triple(true, UamExportMode.INCREMENTAL, true) to UamExportUiMode.OBSERVE,
            Triple(true, UamExportMode.INCREMENTAL, false) to UamExportUiMode.AUTO
        )

        val actual = buildMap {
            listOf(false, true).forEach { enabled ->
                UamExportMode.entries.forEach { mode ->
                    listOf(false, true).forEach { dryRun ->
                        val key = Triple(enabled, mode, dryRun)
                        put(
                            key,
                            UamExportUiModePolicy.resolve(
                                testSettings().copy(
                                    enableUamExportToAaps = enabled,
                                    uamExportMode = mode,
                                    dryRunExport = dryRun
                                )
                            )
                        )
                    }
                }
            }
        }

        assertThat(actual).containsExactlyEntriesIn(expected)
    }

    @Test
    fun inferenceOffForcesEffectiveModeOffWithoutChangingPersistedAuto() {
        val settings = testSettings().copy(
            enableUamInference = false,
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.INCREMENTAL,
            dryRunExport = false
        )

        assertThat(UamExportUiModePolicy.resolve(settings)).isEqualTo(UamExportUiMode.OFF)
        assertThat(settings.enableUamExportToAaps).isTrue()
        assertThat(settings.uamExportMode).isEqualTo(UamExportMode.INCREMENTAL)
        assertThat(settings.dryRunExport).isFalse()
    }

    @Test
    fun applyOffChangesOnlyExportFields() {
        val settings = testSettings().copy(
            enableUamInference = false,
            enableUamBoost = true,
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.INCREMENTAL,
            dryRunExport = false,
            uamLearnedMultiplier = 1.37
        )

        val applied = UamExportUiModePolicy.apply(settings, UamExportUiMode.OFF)

        assertThat(applied).isEqualTo(
            settings.copy(
                enableUamExportToAaps = false,
                uamExportMode = UamExportMode.OFF,
                dryRunExport = true
            )
        )
        assertThat(applied.enableUamInference).isFalse()
        assertThat(applied.enableUamBoost).isTrue()
    }

    @Test
    fun applyObserveChangesOnlyExportFields() {
        val settings = testSettings().copy(
            enableUamInference = true,
            enableUamBoost = false,
            enableUamExportToAaps = false,
            uamExportMode = UamExportMode.CONFIRMED_ONLY,
            dryRunExport = false,
            uamLearnedMultiplier = 1.21
        )

        val applied = UamExportUiModePolicy.apply(settings, UamExportUiMode.OBSERVE)

        assertThat(applied).isEqualTo(
            settings.copy(
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = true
            )
        )
        assertThat(applied.enableUamInference).isTrue()
        assertThat(applied.enableUamBoost).isFalse()
    }

    @Test
    fun applyAutoChangesOnlyExportFields() {
        val settings = testSettings().copy(
            enableUamInference = false,
            enableUamBoost = false,
            enableUamExportToAaps = false,
            uamExportMode = UamExportMode.OFF,
            dryRunExport = true,
            uamLearnedMultiplier = 1.11
        )

        val applied = UamExportUiModePolicy.apply(settings, UamExportUiMode.AUTO)

        assertThat(applied).isEqualTo(
            settings.copy(
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = false
            )
        )
        assertThat(applied.enableUamInference).isFalse()
        assertThat(applied.enableUamBoost).isFalse()
    }

    @Test
    fun runtimeRouteUsesResolvedUiModeIncludingLegacyFailClosedObserve() {
        fun route(
            enabled: Boolean,
            mode: UamExportMode,
            dryRun: Boolean
        ) = AutomationRepository.resolveUnifiedUamExportRuntimeRouteStatic(
            testSettings().copy(
                enableUamExportToAaps = enabled,
                uamExportMode = mode,
                dryRunExport = dryRun
            )
        )

        val off = AutomationRepository.UnifiedUamExportRuntimeRoute(
            invokeCoordinator = false,
            dryRun = true
        )
        val observe = AutomationRepository.UnifiedUamExportRuntimeRoute(
            invokeCoordinator = true,
            dryRun = true
        )
        val auto = AutomationRepository.UnifiedUamExportRuntimeRoute(
            invokeCoordinator = true,
            dryRun = false
        )

        assertThat(route(false, UamExportMode.INCREMENTAL, false)).isEqualTo(off)
        assertThat(route(true, UamExportMode.OFF, false)).isEqualTo(off)
        assertThat(route(true, UamExportMode.CONFIRMED_ONLY, false)).isEqualTo(observe)
        assertThat(route(true, UamExportMode.INCREMENTAL, true)).isEqualTo(observe)
        assertThat(route(true, UamExportMode.INCREMENTAL, false)).isEqualTo(auto)
    }

    @Test
    fun runtimeDispatchPerformsZeroOrOneUnifiedFetchForResolvedMode() = runTest {
        val cases = listOf(
            Triple(
                testSettings().copy(
                    enableUamInference = false,
                    enableUamExportToAaps = true,
                    uamExportMode = UamExportMode.INCREMENTAL,
                    dryRunExport = false
                ),
                0,
                0
            ),
            Triple(
                testSettings().copy(
                    enableUamExportToAaps = false,
                    uamExportMode = UamExportMode.OFF,
                    dryRunExport = true
                ),
                0,
                0
            ),
            Triple(
                testSettings().copy(
                    enableUamExportToAaps = true,
                    uamExportMode = UamExportMode.INCREMENTAL,
                    dryRunExport = true
                ),
                1,
                0
            ),
            Triple(
                testSettings().copy(
                    enableUamExportToAaps = true,
                    uamExportMode = UamExportMode.INCREMENTAL,
                    dryRunExport = false
                ),
                1,
                1
            )
        )

        cases.forEach { (settings, expectedFetches, expectedPosts) ->
            val gateway = CountingGateway()
            AutomationRepository.dispatchUnifiedUamExportStatic(
                settings = settings,
                candidate = validCandidate(),
                coordinator = UamExportCoordinator(
                    gateway = gateway,
                    reservationStore = AcceptingReservationStore(),
                    wallClockMs = { NOW_TS }
                )
            )

            assertThat(gateway.fetchCalls).isEqualTo(expectedFetches)
            assertThat(gateway.postCalls).isEqualTo(expectedPosts)
        }
    }

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
        insulinProfileId = InsulinActionProfileId.FIASP.name,
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

    private fun validCandidate() = UamExportPolicyInput(
        nowTs = NOW_TS,
        episodeId = "runtime-fetch-count",
        activeSinceTs = NOW_TS - 10L * 60_000L,
        confidence = 0.80,
        supportedLowerBoundGrams = 15.0,
        lowerBoundStableBuckets = 2,
        sensorTrust = 0.90,
        sensorBlocked = false,
        signedResidualMmol5 = 0.10,
        shortAverageDeltaMmol5 = 0.10,
        currentGlucoseMmol = 8.0,
        forecastMinimumMmol = 8.0,
        effectiveCobGrams = 0.0,
        therapyCoverage = 0.90,
        remoteLedger = emptyList(),
        sourceSnapshotTs = NOW_TS
    )

    private class CountingGateway : AapsCarbGateway {
        var fetchCalls = 0
        var postCalls = 0

        override suspend fun postCarbEntry(tsMs: Long, grams: Double, note: String): Result<String> {
            postCalls += 1
            return Result.success("remote-$postCalls")
        }

        override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
            fetchCalls += 1
            return Result.success(emptyList())
        }
    }

    private class AcceptingReservationStore : UamExportReservationStore {
        override suspend fun reserve(key: String): Boolean = true
        override suspend fun markSent(key: String, remoteId: String) = Unit
        override suspend fun markPendingUnknown(key: String, detail: String?) = Unit
        override suspend fun release(key: String) = Unit
    }

    private companion object {
        const val NOW_TS = 1_784_246_400_000L
    }
}
