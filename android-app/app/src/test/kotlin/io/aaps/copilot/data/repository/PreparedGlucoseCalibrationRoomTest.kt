package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.widget.CopilotGlucoseWidgetUpdateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PreparedGlucoseCalibrationRoomTest {

    private lateinit var db: CopilotDatabase
    private lateinit var repository: GlucoseCalibrationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { CYCLE_NOW }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pendingCalibrationIsPreparedSideEffectFreeAndCommittedWithExactIdentity() = runBlocking {
        seedPendingCalibrationTransition()
        val beforeModel = repository.latestActiveModel(nowTs = BLOOD_TS + 30_000L)
        val beforeCheck = db.bloodGlucoseCheckDao().latest(1).single()

        val prepared = repository.prepareAcceptedCycleCalibration(
            rawGlucose = rawGlucose(),
            nowTs = CYCLE_NOW,
            allowMaintenance = true
        )

        assertThat(prepared.model).isNotNull()
        assertThat(prepared.model?.id).isNotEqualTo(beforeModel?.id)
        assertThat(prepared.resolvedGlucose.last().calibrationModelId).isEqualTo(prepared.model?.id)
        assertThat(prepared.resolvedGlucose.last().calibratedMmol).isWithin(0.0001).of(6.2)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single()).isEqualTo(beforeCheck)
        assertThat(repository.latestActiveModel(nowTs = CYCLE_NOW)?.id).isEqualTo(beforeModel?.id)

        repository.commitPreparedCalibrationAcceptance(prepared, CYCLE_NOW) {
            db.telemetryDao().upsertAll(
                listOf(calibrationIdentityTelemetry(prepared.model?.id))
            )
        }

        val publishedModel = repository.latestActiveModel(nowTs = CYCLE_NOW)
        val uiIdentity = db.telemetryDao().latestBySourceAndKeyAtOrBefore(
            source = "accepted_runtime_test",
            key = "glucose_calibration_model_id",
            atTs = CYCLE_NOW
        )
        assertThat(publishedModel?.id).isEqualTo(prepared.model?.id)
        assertThat(uiIdentity?.valueText).isEqualTo(prepared.model?.id)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().status).isEqualTo("VALID")
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().reason).isEqualTo("aligned")
    }

    @Test
    fun directManualAddRemainsImmediateWithoutAcceptedCycle() = runBlocking {
        seedImmediateManualCalibrationInput()

        val check = repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(db.bloodGlucoseCheckDao().latest(1).single().id).isEqualTo(check.id)
        assertThat(check.status.name).isEqualTo("VALID")
        assertThat(check.reason).isEqualTo(PROVISIONAL_CURRENT_RAW_REASON)
    }

    @Test
    fun forecastPreparationRejectsDurablyBoundModelCreatedAfterCycleNow() = runBlocking {
        seedImmediateManualCalibrationInput()
        val sessionKey = requireNotNull(
            calibrationSensorSessionKeyFromAgeSample(BLOOD_TS, 1.0)
        )
        val futureModel = GlucoseCalibrationModelEntity(
            id = "future-prepared-model",
            sensorSessionKey = sessionKey,
            createdAt = CYCLE_NOW + 1L,
            validFromTs = BLOOD_TS - 60_000L,
            validToTs = CYCLE_NOW + 60L * 60L * 1000L,
            modelType = "OFFSET",
            gain = 1.0,
            offsetMmol = 0.8,
            confidence = 0.9,
            checkCount = 2,
            sensorAgeHours = 1.0,
            lagMinutesAtFit = 0.0,
            status = "ACTIVE",
            diagnosticsJson = "{}"
        )
        db.glucoseCalibrationModelDao().upsert(futureModel)
        db.telemetryDao().upsertAll(
            listOf(
                calibrationAuthorityTokenRowForTest(
                    CalibrationAuthorityStateCodec.active("future-prepared", futureModel.toDomain()),
                    CYCLE_NOW
                )
            )
        )

        val prepared = repository.prepareAcceptedCycleCalibration(
            rawGlucose = rawGlucose(),
            nowTs = CYCLE_NOW,
            allowMaintenance = false
        )

        assertThat(prepared.model).isNull()
        assertThat(prepared.resolvedGlucose).isNotEmpty()
        prepared.resolvedGlucose.forEach { point ->
            assertThat(point.calibrationApplied).isFalse()
            assertThat(point.calibratedMmol).isWithin(0.0001).of(point.rawMmol)
        }
    }

    @Test
    fun manualAddCallbackObservesDurablyFinalizedActiveModel() = runBlocking {
        seedImmediateManualCalibrationInput()
        var refreshCount = 0
        var modelAtRefresh: GlucoseCalibrationModelEntity? = null
        val hookedRepository = repositoryWithHooks(
            onDurableMutation = { mutation ->
                assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.ADD)
                refreshCount += 1
                modelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
            }
        )

        hookedRepository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(refreshCount).isEqualTo(1)
        assertThat(modelAtRefresh).isNotNull()
        assertThat(modelAtRefresh?.status).isEqualTo("ACTIVE")
        assertThat(modelAtRefresh?.offsetMmol).isWithin(0.0001).of(2.0)
        assertThat(modelAtRefresh?.validFromTs).isAtMost(BLOOD_TS)
        assertThat(modelAtRefresh?.validToTs).isAtLeast(BLOOD_TS)
    }

    @Test
    fun durableManualAddRefreshesWidgetOnceBeforeModelPostCommitCancellation() = runBlocking {
        seedImmediateManualCalibrationInput()
        var refreshCount = 0
        var modelAtRefresh: GlucoseCalibrationModelEntity? = null
        val cancellingRepository = repositoryWithHooks(
            auditFailure = { row ->
                if (row.message == "glucose_calibration_model_updated") {
                    CancellationException("cancel model audit after durable add")
                } else {
                    null
                }
            },
            onDurableMutation = { mutation ->
                assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.ADD)
                refreshCount += 1
                modelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
            }
        )

        val failure = try {
            cancellingRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(modelAtRefresh?.status).isEqualTo("ACTIVE")
        assertThat(modelAtRefresh?.offsetMmol).isWithin(0.0001).of(2.0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).hasSize(1)
    }

    @Test
    fun durableManualResetRefreshesWidgetOnceBeforePostCommitAuditCancellation() = runBlocking {
        seedImmediateManualCalibrationInput()
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )
        var refreshCount = 0
        val cancellingRepository = repositoryWithHooks(
            auditFailure = { row ->
                if (row.message == "glucose_calibration_manual_reset") {
                    CancellationException("cancel reset audit after durable reset")
                } else {
                    null
                }
            },
            onDurableMutation = { mutation ->
                assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.RESET)
                refreshCount += 1
            }
        )

        val failure = try {
            cancellingRepository.resetManualCalibration(resetAt = CYCLE_NOW)
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().reason).isEqualTo("manual_reset")
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L).single().status)
            .isEqualTo("RETIRED")
    }

    @Test
    fun directThrowableFromPostCommitAddAuditPropagatesAfterOneDurableWriteAndRefresh() = runBlocking {
        seedImmediateManualCalibrationInput()
        val expected = DirectFatalCalibrationThrowable("fatal add audit")
        var refreshCount = 0
        val fatalRepository = repositoryWithHooks(
            auditFailure = { row ->
                expected.takeIf { row.message == "blood_glucose_check_added" }
            },
            onDurableMutation = { refreshCount += 1 }
        )

        val failure = try {
            fatalRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isSameInstanceAs(expected)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).isEmpty()
    }

    @Test
    fun directThrowableFromModelRefreshPropagatesWithoutRetryingDurableState() = runBlocking {
        seedImmediateManualCalibrationInput()
        val expected = DirectFatalCalibrationThrowable("fatal model refresh audit")
        var refreshCount = 0
        val fatalRepository = repositoryWithHooks(
            auditFailure = { row ->
                expected.takeIf { row.message == "glucose_calibration_model_updated" }
            },
            onDurableMutation = { refreshCount += 1 }
        )

        val failure = try {
            fatalRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isSameInstanceAs(expected)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).hasSize(1)
    }

    @Test
    fun ordinaryWidgetRefreshFailureKeepsManualAddSuccessfulAndAuditsWarning() = runBlocking {
        seedImmediateManualCalibrationInput()
        var refreshCount = 0
        val privateFailureMessage = "glucose=12.4 patient-context widget host unavailable"
        val refreshFailureRepository = repositoryWithHooks(
            onDurableMutation = {
                refreshCount += 1
                throw IllegalStateException(privateFailureMessage)
            }
        )

        val check = refreshFailureRepository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(check.mmol).isWithin(0.0001).of(6.2)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        val warnings = db.auditLogDao().recentByMessage(
            message = "manual_glucose_calibration_widget_refresh_failed",
            sinceTs = 0L,
            limit = 10
        )
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().level).isEqualTo("WARN")
        assertThat(warnings.single().metadataJson).contains(
            "\"errorCode\":\"MANUAL_WIDGET_REFRESH_CALLBACK_FAILED\""
        )
        assertThat(warnings.single().metadataJson).contains(
            "\"errorType\":\"IllegalStateException\""
        )
        assertThat(warnings.single().metadataJson).doesNotContain(privateFailureMessage)
        assertThat(warnings.single().metadataJson).doesNotContain("\"error\"")
    }

    @Test
    fun sanitizedAwaitedWidgetFailureAuditsStableUnderlyingTypeOnly() = runBlocking {
        seedImmediateManualCalibrationInput()
        val refreshFailureRepository = repositoryWithHooks(
            onDurableMutation = {
                throw CopilotGlucoseWidgetUpdateException(
                    errorCode = "WIDGET_PUBLICATION_FAILED",
                    errorType = "RemoteViewsActionException"
                )
            }
        )

        val check = refreshFailureRepository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(check.mmol).isWithin(0.0001).of(6.2)
        val warning = db.auditLogDao().recentByMessage(
            message = "manual_glucose_calibration_widget_refresh_failed",
            sinceTs = 0L,
            limit = 10
        ).single()
        assertThat(warning.metadataJson).contains(
            "\"errorCode\":\"WIDGET_PUBLICATION_FAILED\""
        )
        assertThat(warning.metadataJson).contains(
            "\"errorType\":\"RemoteViewsActionException\""
        )
        assertThat(warning.metadataJson).doesNotContain("CopilotGlucoseWidgetUpdateException")
        assertThat(warning.metadataJson).doesNotContain("\"error\"")
    }

    @Test
    fun ordinaryPostCommitCheckAuditFailureRetainsFinalizedModelAndKeepsAddSuccessful() =
        runBlocking {
            seedImmediateManualCalibrationInput()
            var refreshCount = 0
            var modelAtRefresh: GlucoseCalibrationModelEntity? = null
            val failingRepository = repositoryWithHooks(
                auditFailure = { row ->
                    IllegalStateException("check audit unavailable")
                        .takeIf { row.message == "blood_glucose_check_added" }
                },
                onDurableMutation = {
                    refreshCount += 1
                    modelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
                }
            )

            val check = failingRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )

            assertThat(check.mmol).isWithin(0.0001).of(6.2)
            assertThat(refreshCount).isEqualTo(1)
            assertThat(modelAtRefresh?.status).isEqualTo("ACTIVE")
            assertThat(modelAtRefresh?.offsetMmol).isWithin(0.0001).of(2.0)
            assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
            assertThat(db.glucoseCalibrationModelDao().latestActive()).isEqualTo(modelAtRefresh)
            val failures = db.auditLogDao().recentByMessage(
                message = "glucose_calibration_refresh_failed_closed",
                sinceTs = 0L,
                limit = 10
            )
            assertThat(failures).isEmpty()
        }

    @Test
    fun ordinaryModelAuditFailureRetainsReplacementBeforeSingleRefreshAndKeepsAddSuccessful() =
        runBlocking {
            seedImmediateManualCalibrationInput()
            var refreshCount = 0
            var modelAtRefresh: GlucoseCalibrationModelEntity? = null
            val failingRepository = repositoryWithHooks(
                auditFailure = { row ->
                    IllegalStateException("model audit unavailable")
                        .takeIf { row.message == "glucose_calibration_model_updated" }
                },
                onDurableMutation = {
                    refreshCount += 1
                    modelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
                }
            )

            val check = failingRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )

            assertThat(check.mmol).isWithin(0.0001).of(6.2)
            assertThat(refreshCount).isEqualTo(1)
            assertThat(modelAtRefresh?.status).isEqualTo("ACTIVE")
            assertThat(modelAtRefresh?.offsetMmol).isWithin(0.0001).of(2.0)
            assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
            assertThat(db.glucoseCalibrationModelDao().latestActive()).isEqualTo(modelAtRefresh)
            val failures = db.auditLogDao().recentByMessage(
                message = "glucose_calibration_refresh_failed_closed",
                sinceTs = 0L,
                limit = 10
            )
            assertThat(failures).isEmpty()
        }

    @Test
    fun unprovenActiveModelAfterFinalizationFailureSkipsWidgetRefresh() = runBlocking {
        seedImmediateManualCalibrationInput()
        var refreshCount = 0
        val failingRepository = repositoryWithHooks(
            beforeAuditInsert = { row ->
                if (row.message == "blood_glucose_check_added") {
                    val sessionKey = requireNotNull(
                        db.bloodGlucoseCheckDao().latest(1).single().sensorSessionKey
                    )
                    db.glucoseCalibrationModelDao().upsert(
                        GlucoseCalibrationModelEntity(
                            id = "unproven-active-model",
                            sensorSessionKey = sessionKey,
                            createdAt = BLOOD_TS + 30_000L,
                            validFromTs = BLOOD_TS,
                            validToTs = CYCLE_NOW + 60L * 60L * 1000L,
                            modelType = "OFFSET",
                            gain = 1.0,
                            offsetMmol = 1.5,
                            confidence = 0.9,
                            checkCount = 1,
                            sensorAgeHours = null,
                            lagMinutesAtFit = 0.0,
                            status = "ACTIVE",
                            diagnosticsJson = "{}"
                        )
                    )
                    installActiveModelRetirementFailureTrigger()
                    throw IllegalStateException("post-commit audit unavailable")
                }
            },
            onDurableMutation = { refreshCount += 1 }
        )

        val failure = try {
            failingRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("coherent durable calibration state")
        assertThat(refreshCount).isEqualTo(0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().latestActive()?.id)
            .isEqualTo("unproven-active-model")
    }

    @Test
    fun modelPersistenceFailureRefreshesOnlyAfterReadbackProvesRawState() = runBlocking {
        seedImmediateManualCalibrationInput()
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_manual_model_insert
            BEFORE INSERT ON glucose_calibration_models
            BEGIN
                SELECT RAISE(FAIL, 'model persistence unavailable');
            END
            """.trimIndent()
        )
        var refreshCount = 0
        var activeModelAtRefresh: GlucoseCalibrationModelEntity? = null
        val failingRepository = repositoryWithHooks(
            onDurableMutation = {
                refreshCount += 1
                activeModelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
            }
        )

        val check = failingRepository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(check.mmol).isWithin(0.0001).of(6.2)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(activeModelAtRefresh).isNull()
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isNull()
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
    }

    @Test
    fun overviewActiveModelFlowRejectsLegacyOpaqueAuthorityAfterRestart() = runBlocking {
        seedImmediateManualCalibrationInput()
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isNotNull()
        db.telemetryDao().upsertAll(
            listOf(calibrationAuthorityTokenRowForTest("legacy-opaque-manual-token", CYCLE_NOW))
        )
        val restartedRepository = repositoryWithHooks()

        val overviewModel = restartedRepository.observeLatestActiveModel().first()

        assertThat(overviewModel).isNull()
    }

    @Test
    fun acceptedNormalCycleReplacesLegacyAuthorityWithExactStructuredState() = runBlocking {
        seedImmediateManualCalibrationInput()
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )
        db.telemetryDao().upsertAll(
            listOf(calibrationAuthorityTokenRowForTest("legacy-opaque-manual-token", CYCLE_NOW))
        )
        val prepared = repository.prepareAcceptedCycleCalibration(
            rawGlucose = rawGlucose(),
            nowTs = CYCLE_NOW,
            allowMaintenance = true
        )

        repository.commitPreparedCalibrationAcceptance(prepared, CYCLE_NOW) { Unit }

        val currentAuthority = requireNotNull(
            db.telemetryDao().currentBySourceAndKey(
                source = CALIBRATION_AUTHORITY_SOURCE,
                key = CALIBRATION_AUTHORITY_TOKEN_KEY
            )?.valueText
        )
        val acceptedAuthority = db.telemetryDao().atTimestampBySourceAndKeys(
            source = ACCEPTED_CALIBRATION_SOURCE,
            timestamp = CYCLE_NOW,
            keys = listOf(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY)
        ).single().valueText
        assertThat(currentAuthority).contains("\"version\":1")
        assertThat(currentAuthority).contains("\"state\":\"ACTIVE\"")
        assertThat(currentAuthority).contains(requireNotNull(prepared.model).id)
        assertThat(currentAuthority).contains(acceptedCalibrationModelFingerprint(prepared.model))
        assertThat(acceptedAuthority).isEqualTo(currentAuthority)
        assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, CYCLE_NOW)).isTrue()
    }

    @Test
    fun boundedWidgetRefreshTimeoutKeepsDurableManualAddSuccessful() = runBlocking {
        seedImmediateManualCalibrationInput()
        var refreshCount = 0
        val timingOutRepository = repositoryWithHooks(
            manualWidgetRefreshTimeoutMs = 25L,
            onDurableMutation = {
                refreshCount += 1
                awaitCancellation()
            }
        )

        val check = timingOutRepository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(check.mmol).isWithin(0.0001).of(6.2)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        val warnings = db.auditLogDao().recentByMessage(
            message = "manual_glucose_calibration_widget_refresh_failed",
            sinceTs = 0L,
            limit = 10
        )
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().metadataJson).contains(
            "\"errorCode\":\"MANUAL_WIDGET_REFRESH_TIMEOUT\""
        )
        assertThat(warnings.single().metadataJson).contains(
            "\"errorType\":\"Timeout\""
        )
        assertThat(warnings.single().metadataJson).doesNotContain("timed out")
    }

    @Test
    fun boundedPostCommitFinalizationTimeoutFailsClosedThenRefreshesOnce() = runBlocking {
        seedImmediateManualCalibrationInput()
        var finalizationBlocked = false
        var refreshCount = 0
        var modelAtRefresh: GlucoseCalibrationModelEntity? = null
        val timingOutRepository = repositoryWithHooks(
            beforeAuditInsert = { row ->
                if (row.message == "blood_glucose_check_added") {
                    finalizationBlocked = true
                    awaitCancellation()
                }
            },
            // The same budget covers real Room readback; 25 ms also timed out that unrelated step.
            manualPostCommitFinalizationTimeoutMs = 5_000L,
            onDurableMutation = {
                refreshCount += 1
                modelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
            }
        )

        val check = timingOutRepository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )

        assertThat(finalizationBlocked).isTrue()
        assertThat(check.mmol).isWithin(0.0001).of(6.2)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(modelAtRefresh).isNull()
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        val failures = db.auditLogDao().recentByMessage(
            message = "glucose_calibration_refresh_failed_closed",
            sinceTs = 0L,
            limit = 10
        )
        assertThat(failures).hasSize(1)
        assertThat(failures.single().metadataJson).contains("manual_post_commit_timeout")
    }

    @Test
    fun directThrowableFromProtectedWidgetRefreshPropagatesAfterDurableWrite() = runBlocking {
        seedImmediateManualCalibrationInput()
        val expected = DirectFatalCalibrationThrowable("fatal widget refresh")
        var refreshCount = 0
        val fatalRepository = repositoryWithHooks(
            onDurableMutation = {
                refreshCount += 1
                throw expected
            }
        )

        val failure = try {
            fatalRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isSameInstanceAs(expected)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().latestActive()?.offsetMmol)
            .isWithin(0.0001).of(2.0)
    }

    @Test
    fun errorFromProtectedWidgetRefreshPropagatesAfterDurableWrite() = runBlocking {
        seedImmediateManualCalibrationInput()
        val expected = LinkageError("fatal widget linkage")
        var refreshCount = 0
        val fatalRepository = repositoryWithHooks(
            onDurableMutation = {
                refreshCount += 1
                throw expected
            }
        )

        val failure = try {
            fatalRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(LinkageError::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(expected.message)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().latestActive()?.offsetMmol)
            .isWithin(0.0001).of(2.0)
    }

    @Test
    fun cancellationWhileWaitingBeforeDurableResetDoesNotRefreshOrReset() = runBlocking {
        seedImmediateManualCalibrationInput()
        val firstMutationCommitted = CompletableDeferred<Unit>()
        val releaseFirstRefresh = CompletableDeferred<Unit>()
        val durableMutations = mutableListOf<ManualCalibrationDurableMutation>()
        val blockingRepository = repositoryWithHooks(
            onDurableMutation = { mutation ->
                durableMutations += mutation
                if (mutation == ManualCalibrationDurableMutation.ADD) {
                    firstMutationCommitted.complete(Unit)
                    releaseFirstRefresh.await()
                }
            }
        )
        val add = async(Dispatchers.IO) {
            blockingRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
        }
        firstMutationCommitted.await()
        val reset = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            blockingRepository.resetManualCalibration(resetAt = CYCLE_NOW)
        }

        reset.cancelAndJoin()
        releaseFirstRefresh.complete(Unit)
        add.await()

        assertThat(durableMutations).containsExactly(ManualCalibrationDurableMutation.ADD)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().reason)
            .isEqualTo(PROVISIONAL_CURRENT_RAW_REASON)
    }

    @Test
    fun parentCancellationAfterDurableAddWaitsForOneProtectedRefreshThenPropagates() = runBlocking {
        seedImmediateManualCalibrationInput()
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var refreshCount = 0
        val hookedRepository = repositoryWithHooks(
            onDurableMutation = {
                refreshCount += 1
                refreshEntered.complete(Unit)
                releaseRefresh.await()
            }
        )
        val add = launch(Dispatchers.IO) {
            hookedRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
        }
        refreshEntered.await()

        add.cancel(CancellationException("cancel after durable add"))
        releaseRefresh.complete(Unit)
        add.join()

        assertThat(add.isCancelled).isTrue()
        assertThat(refreshCount).isEqualTo(1)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
    }

    @Test
    fun parentCancellationAfterCheckCommitFinalizesModelBeforeSingleRefresh() = runBlocking {
        seedImmediateManualCalibrationInput()
        val postCommitAuditEntered = CompletableDeferred<Unit>()
        val releasePostCommitAudit = CompletableDeferred<Unit>()
        var refreshCount = 0
        var modelAtRefresh: GlucoseCalibrationModelEntity? = null
        val hookedRepository = repositoryWithHooks(
            beforeAuditInsert = { row ->
                if (row.message == "blood_glucose_check_added") {
                    postCommitAuditEntered.complete(Unit)
                    releasePostCommitAudit.await()
                }
            },
            onDurableMutation = {
                refreshCount += 1
                modelAtRefresh = db.glucoseCalibrationModelDao().latestActive()
            }
        )
        val add = launch(Dispatchers.IO) {
            hookedRepository.addManualBloodGlucoseCheck(
                value = 6.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
        }
        postCommitAuditEntered.await()

        add.cancel(CancellationException("cancel after check commit"))
        releasePostCommitAudit.complete(Unit)
        add.join()

        assertThat(add.isCancelled).isTrue()
        assertThat(refreshCount).isEqualTo(1)
        assertThat(modelAtRefresh?.status).isEqualTo("ACTIVE")
        assertThat(modelAtRefresh?.offsetMmol).isWithin(0.0001).of(2.0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).hasSize(1)
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).hasSize(1)
    }

    @Test
    fun validationFailureBeforeDurableAddDoesNotRefreshOrPersist() = runBlocking {
        var refreshCount = 0
        val hookedRepository = repositoryWithHooks(
            onDurableMutation = { refreshCount += 1 }
        )

        val failure = try {
            hookedRepository.addManualBloodGlucoseCheck(
                value = 1.0,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = BLOOD_TS + 30_000L
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refreshCount).isEqualTo(0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).isEmpty()
    }

    @Test
    fun preparedCalibrationIdentityMustBeTheSameObjectAcrossEveryRuntimeConsumer() = runBlocking {
        seedPendingCalibrationTransition()
        val prepared = repository.prepareAcceptedCycleCalibration(rawGlucose(), CYCLE_NOW, true)
        val identity = prepared.identity

        AutomationRepository.requireExactCalibrationIdentityStatic(
            prepared = identity,
            forecast = identity,
            uam = identity,
            targetManager = identity,
            ui = identity
        )

        assertThat(identity.modelId).isEqualTo(prepared.model?.id)
        assertThat(prepared.resolvedGlucose.last().calibrationModelId).isEqualTo(identity.modelId)
        val mismatch = runCatching {
            AutomationRepository.requireExactCalibrationIdentityStatic(
                prepared = identity,
                forecast = identity.copy(),
                uam = identity,
                targetManager = identity,
                ui = identity
            )
        }.exceptionOrNull()
        assertThat(mismatch).isNotNull()
        assertThat(mismatch).hasMessageThat().contains("exact calibration identity")
    }

    @Test
    fun exactAcceptedReadbackRequiresCalibrationMarkerAndExactPersistedModel() = runBlocking {
        seedPendingCalibrationTransition()
        val prepared = repository.prepareAcceptedCycleCalibration(rawGlucose(), CYCLE_NOW, true)

        repository.commitPreparedCalibrationAcceptance(
            prepared = prepared,
            acceptedAtTs = CYCLE_NOW
        ) { Unit }

        assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, CYCLE_NOW)).isTrue()
        val model = requireNotNull(prepared.model)
        db.glucoseCalibrationModelDao().upsert(
            model.copy(offsetMmol = model.offsetMmol + 0.25).toEntity()
        )
        assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, CYCLE_NOW)).isFalse()
        db.glucoseCalibrationModelDao().upsert(model.toEntity())
        assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, CYCLE_NOW)).isTrue()
        db.telemetryDao().deleteBySourceAndTimestamp(
            source = ACCEPTED_CALIBRATION_SOURCE,
            timestamp = CYCLE_NOW
        )
        assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, CYCLE_NOW)).isFalse()
    }

    @Test
    fun preparedCalibrationRejectsInputDriftBeforeAcceptedCommit() = runBlocking {
        seedPendingCalibrationTransition()
        val beforeModel = repository.latestActiveModel(nowTs = CYCLE_NOW)
        val prepared = repository.prepareAcceptedCycleCalibration(rawGlucose(), CYCLE_NOW, true)
        db.glucoseDao().upsertAll(
            listOf(glucose(CYCLE_NOW, 5.0))
        )
        var acceptedCommit = 0

        val failure = runCatching {
            repository.commitPreparedCalibrationAcceptance(prepared, CYCLE_NOW) { acceptedCommit += 1 }
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure).hasMessageThat().contains("calibration input changed")
        assertThat(acceptedCommit).isEqualTo(0)
        assertThat(repository.latestActiveModel(nowTs = CYCLE_NOW)?.id).isEqualTo(beforeModel?.id)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().reason)
            .isEqualTo(PROVISIONAL_CURRENT_RAW_REASON)
    }

    @Test
    fun cancellationDuringAcceptedCommitRollsBackPreparedCalibrationAndPublication() = runBlocking {
        seedPendingCalibrationTransition()
        val beforeModel = repository.latestActiveModel(nowTs = CYCLE_NOW)
        val prepared = repository.prepareAcceptedCycleCalibration(rawGlucose(), CYCLE_NOW, true)
        val commitEntered = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.IO) {
            repository.commitPreparedCalibrationAcceptance(prepared, CYCLE_NOW) {
                db.telemetryDao().upsertAll(listOf(calibrationIdentityTelemetry(prepared.model?.id)))
                commitEntered.complete(Unit)
                awaitCancellation()
            }
        }
        commitEntered.await()
        job.cancelAndJoin()

        assertThat(repository.latestActiveModel(nowTs = CYCLE_NOW)?.id).isEqualTo(beforeModel?.id)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().reason)
            .isEqualTo(PROVISIONAL_CURRENT_RAW_REASON)
        assertThat(db.telemetryDao().latestBySourceAndKeyAtOrBefore(
            "accepted_runtime_test",
            "glucose_calibration_model_id",
            CYCLE_NOW
        )).isNull()
        assertThat(db.telemetryDao().atTimestampBySourceAndKeys(
            ACCEPTED_CALIBRATION_SOURCE,
            CYCLE_NOW,
            listOf("calibration_model_id")
        )).isEmpty()
    }

    @Test
    fun rejectedAcceptanceDoesNotPublishPreparedCalibration() = runBlocking {
        seedPendingCalibrationTransition()
        val beforeModel = repository.latestActiveModel(nowTs = CYCLE_NOW)
        val prepared = repository.prepareAcceptedCycleCalibration(rawGlucose(), CYCLE_NOW, true)
        var commitCalls = 0

        val failure = runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = { null as String? },
                commitAcceptedRoomTuple = {
                    repository.commitPreparedCalibrationAcceptance(prepared, CYCLE_NOW) { commitCalls += 1 }
                },
                reconcileAcceptedRoomTuple = { true },
                finalizeAccepted = {},
                clinicalSideEffects = {}
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(commitCalls).isEqualTo(0)
        assertThat(repository.latestActiveModel(nowTs = CYCLE_NOW)?.id).isEqualTo(beforeModel?.id)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single().reason)
            .isEqualTo(PROVISIONAL_CURRENT_RAW_REASON)
    }

    @Test
    fun readOnlyAndSourceChangeCyclesCannotPublishCalibrationMaintenance() = runBlocking {
        seedPendingCalibrationTransition()
        val beforeModel = repository.latestActiveModel(nowTs = CYCLE_NOW)
        val beforeCheck = db.bloodGlucoseCheckDao().latest(1).single()
        val beforeAuthorityToken = db.telemetryDao().currentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        )?.valueText
        val noMaintenanceIntents = listOf(
            AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY,
            AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE
        )

        noMaintenanceIntents.forEachIndexed { index, intent ->
            assertThat(AutomationRepository.allowsCalibrationMaintenanceStatic(intent)).isFalse()
            val prepared = repository.prepareAcceptedCycleCalibration(
                rawGlucose = rawGlucose(),
                nowTs = CYCLE_NOW,
                allowMaintenance = AutomationRepository.allowsCalibrationMaintenanceStatic(intent)
            )
            repository.commitPreparedCalibrationAcceptance(prepared, CYCLE_NOW + index) { Unit }
            assertThat(prepared.maintenancePrepared).isFalse()
        }

        assertThat(AutomationRepository.allowsCalibrationMaintenanceStatic(
            AutomationRepository.AutomationCycleIntent.NORMAL
        )).isTrue()
        assertThat(repository.latestActiveModel(nowTs = CYCLE_NOW)?.id).isEqualTo(beforeModel?.id)
        assertThat(db.bloodGlucoseCheckDao().latest(1).single()).isEqualTo(beforeCheck)
        assertThat(db.telemetryDao().currentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        )?.valueText).isEqualTo(beforeAuthorityToken)
    }

    private suspend fun seedPendingCalibrationTransition() {
        db.glucoseDao().upsertAll(
            listOf(
                glucose(BLOOD_TS - 15L * 60_000L, 4.0),
                glucose(BLOOD_TS - 10L * 60_000L, 4.1),
                glucose(BLOOD_TS - 5L * 60_000L, 4.15),
                glucose(BLOOD_TS, 4.2)
            )
        )
        db.telemetryDao().upsertAll(
            trustTelemetry(BLOOD_TS)
        )
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = BLOOD_TS,
            enteredAt = BLOOD_TS + 30_000L
        )
        db.glucoseDao().upsertAll(
            listOf(
                glucose(BLOOD_TS + 5L * 60_000L, 4.5),
                glucose(ALIGNED_TS, 4.8)
            )
        )
        db.telemetryDao().upsertAll(trustTelemetry(ALIGNED_TS))
    }

    private suspend fun seedImmediateManualCalibrationInput() {
        db.glucoseDao().upsertAll(
            listOf(
                glucose(BLOOD_TS - 15L * 60_000L, 4.0),
                glucose(BLOOD_TS - 10L * 60_000L, 4.1),
                glucose(BLOOD_TS - 5L * 60_000L, 4.15),
                glucose(BLOOD_TS, 4.2)
            )
        )
        db.telemetryDao().upsertAll(trustTelemetry(BLOOD_TS))
    }

    private fun repositoryWithHooks(
        auditFailure: (AuditLogEntity) -> Throwable? = { null },
        beforeAuditInsert: suspend (AuditLogEntity) -> Unit = {},
        manualPostCommitFinalizationTimeoutMs: Long = 5_000L,
        manualWidgetRefreshTimeoutMs: Long = 5_000L,
        onDurableMutation: suspend (ManualCalibrationDurableMutation) -> Unit = {}
    ): GlucoseCalibrationRepository {
        val delegate = db.auditLogDao()
        val auditDao = object : AuditLogDao {
            override suspend fun insert(entity: AuditLogEntity) {
                beforeAuditInsert(entity)
                auditFailure(entity)?.let { throw it }
                delegate.insert(entity)
            }

            override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> =
                delegate.observeLatest(limit)

            override suspend fun recentByMessage(
                message: String,
                sinceTs: Long,
                limit: Int
            ): List<AuditLogEntity> = delegate.recentByMessage(message, sinceTs, limit)

            override suspend fun deleteOlderThan(olderThan: Long): Int =
                delegate.deleteOlderThan(olderThan)

            override suspend fun deleteOlderThanInfoMessages(
                olderThan: Long,
                messages: List<String>
            ): Int = delegate.deleteOlderThanInfoMessages(olderThan, messages)
        }
        return GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(auditDao, Gson()) { CYCLE_NOW },
            onManualCalibrationDurableMutation = onDurableMutation,
            manualPostCommitFinalizationTimeoutMs = manualPostCommitFinalizationTimeoutMs,
            manualWidgetRefreshTimeoutMs = manualWidgetRefreshTimeoutMs
        )
    }

    private fun installActiveModelRetirementFailureTrigger() {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_active_model_retirement
            BEFORE UPDATE ON glucose_calibration_models
            WHEN OLD.status IN ('ACTIVE', 'SHADOW')
            BEGIN
                SELECT RAISE(FAIL, 'active model retirement unavailable');
            END
            """.trimIndent()
        )
    }

    private suspend fun rawGlucose(): List<GlucosePoint> = db.glucoseDao()
        .since(BLOOD_TS - 20L * 60_000L)
        .map { GlucosePoint(it.timestamp, it.mmol, it.source) }

    private fun trustTelemetry(timestamp: Long): List<TelemetrySampleEntity> = listOf(
        telemetry("sensor_lag_minutes", 10.0, timestamp),
        telemetry("sensor_quality_score", 1.0, timestamp),
        telemetry("sensor_quality_blocked", 0.0, timestamp),
        telemetry("sensor_quality_suspect_false_low", 0.0, timestamp),
        telemetry(
            "sensor_age_hours",
            1.0 + (timestamp - BLOOD_TS).toDouble() / (60L * 60L * 1000L).toDouble(),
            timestamp
        )
    )

    private fun telemetry(key: String, value: Double, timestamp: Long) = TelemetrySampleEntity(
        id = "prepared-$key-$timestamp",
        timestamp = timestamp,
        source = "test",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = null,
        quality = "OK"
    )

    private fun calibrationIdentityTelemetry(modelId: String?) = TelemetrySampleEntity(
        id = "accepted-calibration-identity",
        timestamp = CYCLE_NOW,
        source = "accepted_runtime_test",
        key = "glucose_calibration_model_id",
        valueDouble = null,
        valueText = modelId,
        unit = null,
        quality = "OK"
    )

    private fun calibrationAuthorityTokenRowForTest(value: String, timestamp: Long) =
        TelemetrySampleEntity(
            id = "glucose-calibration-authority-current",
            timestamp = timestamp,
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY,
            valueDouble = null,
            valueText = value,
            unit = null,
            quality = "OK"
        )

    private fun glucose(timestamp: Long, mmol: Double) = GlucoseSampleEntity(
        timestamp = timestamp,
        mmol = mmol,
        source = "test",
        quality = "OK"
    )

    private companion object {
        const val BLOOD_TS = 2_000_000_000_000L
        const val ALIGNED_TS = BLOOD_TS + 10L * 60_000L
        const val CYCLE_NOW = ALIGNED_TS + 30_000L
    }
}

private class DirectFatalCalibrationThrowable(message: String) : Throwable(message)
