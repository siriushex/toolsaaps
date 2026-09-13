package io.aaps.copilot

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.data.repository.ClinicalPdfReportIdentity
import io.aaps.copilot.data.repository.ClinicalPdfReportPhase
import io.aaps.copilot.data.repository.ClinicalPdfSourceCaptureResult
import io.aaps.copilot.data.repository.ClinicalPdfSourceLease
import io.aaps.copilot.data.repository.ClinicalPdfSourceLeaseResult
import io.aaps.copilot.report.ClinicalPdfContentBlock
import io.aaps.copilot.report.ClinicalPdfContentCursor
import io.aaps.copilot.report.ClinicalPdfContentIdentity
import io.aaps.copilot.report.ClinicalPdfContentRole
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.storage.ClinicalPdfCopyResult
import io.aaps.copilot.storage.ClinicalPdfReadyArtifact
import io.aaps.copilot.storage.ClinicalPdfShareResult
import io.aaps.copilot.storage.ClinicalPdfStageResult
import io.aaps.copilot.ui.ClinicalReportPdfExportCoordinator
import io.aaps.copilot.ui.foundation.screens.AiAnalysisScreen
import io.aaps.copilot.ui.foundation.screens.AiAnalysisUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalPdfExportUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalReportPhaseUi
import io.aaps.copilot.ui.foundation.screens.ClinicalReportUiState
import io.aaps.copilot.ui.foundation.screens.ScreenLoadState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.io.File
import kotlinx.coroutines.Dispatchers

class ClinicalPdfPickerRecreationActivity : ComponentActivity() {
    private val model: ClinicalPdfPickerRecreationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registryOwner = (application as UiTestApplication).pdfPickerRegistryOwner
        setContent {
            val export by model.state.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                AapsCopilotTheme {
                    AiAnalysisScreen(
                        state = AiAnalysisUiState(
                            loadState = ScreenLoadState.READY,
                            isStale = false,
                            clinicalReport = ClinicalReportUiState(
                                phase = ClinicalReportPhaseUi.LOCAL_READY,
                                canSavePdf = true,
                                pdfExportState = export.phase,
                                pdfExportTicket = export.ticket
                            )
                        ),
                        onClaimClinicalReportPdfExportTicket = model::claim,
                        onResolveClinicalReportPdfExport = model::resolve
                    )
                }
            }
        }
    }

    fun viewModelIdentityForTest(): Any = model.identityToken

    fun resolveCountForTest(): Int = model.resolveCount

    fun exportPhaseForTest(): ClinicalPdfExportUiState = model.state.value.phase
}

internal class ClinicalPdfPickerRecreationViewModel(
    application: Application
) : AndroidViewModel(application) {
    val identityToken = Any()
    var resolveCount: Int = 0
        private set

    private val reportIdentity = ClinicalPdfReportIdentity(
        ClinicalPdfReportPhase.LOCAL_READY,
        "4".repeat(64)
    )
    private val artifactFile = File(application.cacheDir, "clinical-pdf-picker-recreation.ready.pdf")
    private val artifact = ClinicalPdfReadyArtifact(
        file = artifactFile,
        sizeBytes = 128L,
        sha256 = "5".repeat(64),
        pageCount = 1,
        createdAt = 1_800_000_000_000L
    )
    private val coordinator = ClinicalReportPdfExportCoordinator(
        scope = viewModelScope,
        acquireSourceLease = {
            ClinicalPdfSourceLeaseResult.Ready(
                ClinicalPdfSourceLease(
                    reportIdentity = reportIdentity,
                    sourceBuilder = {
                        ClinicalPdfSourceCaptureResult.Ready(TEST_SOURCE)
                    },
                    releaseLease = {}
                )
            )
        },
        sourceDispatcher = Dispatchers.Unconfined,
        stage = { ClinicalPdfStageResult.Ready(artifact) },
        copy = { _, _ ->
            resolveCount += 1
            ClinicalPdfCopyResult.CopiedVerified
        },
        share = { ClinicalPdfShareResult.Failed },
        discardShare = {},
        currentReportIdentity = { reportIdentity },
        initialReportIdentity = reportIdentity,
        release = { it.file.delete() }
    )

    internal val state = coordinator.state

    init {
        coordinator.begin()
    }

    fun claim(ticketId: Long): Boolean = coordinator.claimReadyTicket(ticketId)

    fun resolve(ticketId: Long, uri: Uri?) = coordinator.resolvePicker(ticketId, uri)

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }

    private companion object {
        val TEST_SOURCE = object : ClinicalPdfContentSource {
            override val identity = ClinicalPdfContentIdentity(
                requestSha256 = "6".repeat(64),
                generatedAt = 1_800_000_000_000L,
                zoneId = "UTC",
                schemaVersion = 7
            )

            override fun open(): ClinicalPdfContentCursor = object : ClinicalPdfContentCursor {
                private var emitted = false

                override fun next(): ClinicalPdfContentBlock? = if (emitted) {
                    null
                } else {
                    emitted = true
                    ClinicalPdfContentBlock("title", ClinicalPdfContentRole.TITLE)
                }

                override fun close() = Unit
            }
        }
    }
}
