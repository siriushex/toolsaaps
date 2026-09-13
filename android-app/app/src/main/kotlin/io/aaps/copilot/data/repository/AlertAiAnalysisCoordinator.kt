package io.aaps.copilot.data.repository

import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.executionIdentity
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex

data class AlertAiAnalysisSettings(
    val enabled: Boolean,
    val config: ClinicalAiProviderConfig
)

fun interface AlertAiAnalysisSettingsSource {
    suspend fun current(): AlertAiAnalysisSettings
}

fun interface AlertAiCredentialSource {
    suspend fun credential(providerId: ClinicalAiProviderId): String?
}

fun interface AlertAiAnalysisContextSource {
    suspend fun build(trigger: AlertAiAnalysisTrigger): AlertAiCanonicalContext
}

data class AlertAiAnalysisTrigger(
    val episodeId: String,
    val requestedAt: Long,
    val stage: String,
    val direction: AlertCauseDirection,
    val localCauseSnapshot: AlertCauseSnapshot
)

enum class AlertAiAnalysisStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

enum class AlertAiAnalysisErrorCode {
    ADMISSION_BUSY,
    CONTEXT_UNAVAILABLE,
    EXECUTION_IDENTITY_CHANGED,
    NETWORK_UNAVAILABLE,
    INVALID_RESPONSE,
    IDENTITY_MISMATCH,
    REQUEST_FAILED,
    CANCELLED
}

enum class AlertAiAnalysisOutcome {
    DISABLED,
    CONFIG_UNAVAILABLE,
    CREDENTIAL_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    CONTEXT_UNAVAILABLE,
    ALREADY_CLAIMED,
    COMPLETED,
    FAILED
}

class AlertAiExecutionAdmission internal constructor() {
    private val mutex = Mutex()

    internal fun tryAcquire(): Lease? {
        if (!mutex.tryLock()) return null
        return Lease(mutex)
    }

    internal class Lease(private val mutex: Mutex) {
        fun release() = mutex.unlock()
    }
}

class AlertAiAnalysisCoordinator(
    private val settingsSource: AlertAiAnalysisSettingsSource,
    private val credentialSource: AlertAiCredentialSource,
    private val networkGate: AlertAiNetworkGate,
    private val contextSource: AlertAiAnalysisContextSource,
    private val dao: AlertAiAnalysisDao,
    private val gatewayFactory: ClinicalAiGatewayFactory,
    private val analysisIdFactory: (String) -> String,
    private val clock: () -> Long,
    private val admission: AlertAiExecutionAdmission = GLOBAL_ADMISSION
) {
    suspend fun run(trigger: AlertAiAnalysisTrigger): AlertAiAnalysisOutcome {
        if (!validTrigger(trigger)) return AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE
        val settings = try {
            settingsSource.current()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return AlertAiAnalysisOutcome.CONFIG_UNAVAILABLE
        }
        if (!settings.enabled) return AlertAiAnalysisOutcome.DISABLED

        val gateway = try {
            gatewayFactory.create(settings.config)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return AlertAiAnalysisOutcome.CONFIG_UNAVAILABLE
        }
        val credential = try {
            credentialSource.credential(settings.config.providerId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }?.takeIf(String::isNotBlank)
            ?: return AlertAiAnalysisOutcome.CREDENTIAL_UNAVAILABLE

        val networkLease = try {
            networkGate.currentValidatedNetwork()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
            ?: return AlertAiAnalysisOutcome.NETWORK_UNAVAILABLE

        val analysisId = newAnalysisId(trigger.episodeId)
            ?: return AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE
        val running = AlertAiAnalysisEntity(
            analysisId = analysisId,
            episodeId = trigger.episodeId,
            provider = settings.config.providerId.name,
            model = settings.config.modelId,
            requestHash = PENDING_REQUEST_HASH,
            status = AlertAiAnalysisStatus.RUNNING.name,
            resultJson = null,
            requestedAt = trigger.requestedAt,
            completedAt = null,
            sanitizedError = null
        )
        val inserted = try {
            dao.insert(running)
        } catch (cancellation: CancellationException) {
            terminalizeAmbiguousInsert(running, trigger.requestedAt)
            throw cancellation
        } catch (_: Exception) {
            return AlertAiAnalysisOutcome.FAILED
        }
        if (inserted == -1L) return AlertAiAnalysisOutcome.ALREADY_CLAIMED
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            terminalizeAmbiguousInsert(running, trigger.requestedAt)
            throw cancellation
        }

        var ownedClaim = running
        var admissionLease: AlertAiExecutionAdmission.Lease? = null
        return try {
            admissionLease = admission.tryAcquire()
            if (admissionLease == null) {
                terminalUpdateBounded(
                    ownedClaim,
                    trigger.requestedAt,
                    AlertAiAnalysisStatus.FAILED,
                    AlertAiAnalysisErrorCode.ADMISSION_BUSY
                )
                return AlertAiAnalysisOutcome.FAILED
            }

            val context = try {
                contextSource.build(trigger)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                terminalUpdateBounded(
                    ownedClaim,
                    trigger.requestedAt,
                    AlertAiAnalysisStatus.FAILED,
                    AlertAiAnalysisErrorCode.CONTEXT_UNAVAILABLE
                )
                return AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE
            }
            if (!validContext(context, trigger)) {
                terminalUpdateBounded(
                    ownedClaim,
                    trigger.requestedAt,
                    AlertAiAnalysisStatus.FAILED,
                    AlertAiAnalysisErrorCode.CONTEXT_UNAVAILABLE
                )
                return AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE
            }
            currentCoroutineContext().ensureActive()
            val boundClaim = running.copy(requestHash = context.sha256)
            if (transitionOwnedRunning(running, boundClaim) != 1) throw IllegalStateException()
            ownedClaim = boundClaim

            val execution = revalidateExecutionIdentity(
                settings = settings,
                credential = credential,
                networkLease = networkLease
            )
            if (execution == null) {
                terminalUpdateBounded(
                    ownedClaim,
                    trigger.requestedAt,
                    AlertAiAnalysisStatus.FAILED,
                    AlertAiAnalysisErrorCode.EXECUTION_IDENTITY_CHANGED
                )
                return AlertAiAnalysisOutcome.FAILED
            }

            currentCoroutineContext().ensureActive()
            val response = gateway.analyzeAlert(
                context = context,
                credential = { execution.credential },
                networkLease = networkLease
            )
            val parsed = AlertAiTypedPresentationParser.parse(response.resultJson)
                ?: throw ClinicalOpenAiException.InvalidResponse()
            val canonical = AlertAiTypedPresentationParser.encodeCanonical(parsed)
                ?: throw ClinicalOpenAiException.InvalidResponse()
            val completed = ownedClaim.copy(
                status = AlertAiAnalysisStatus.COMPLETED.name,
                resultJson = canonical,
                completedAt = completionTime(trigger.requestedAt),
                sanitizedError = null
            )
            if (transitionOwnedRunning(ownedClaim, completed) != 1) {
                throw IllegalStateException()
            }
            AlertAiAnalysisOutcome.COMPLETED
        } catch (cancellation: CancellationException) {
            terminalUpdateBounded(
                ownedClaim,
                trigger.requestedAt,
                AlertAiAnalysisStatus.CANCELLED,
                AlertAiAnalysisErrorCode.CANCELLED
            )
            throw cancellation
        } catch (failure: Exception) {
            terminalUpdateBounded(
                ownedClaim,
                trigger.requestedAt,
                AlertAiAnalysisStatus.FAILED,
                failure.errorCode()
            )
            AlertAiAnalysisOutcome.FAILED
        } finally {
            admissionLease?.release()
        }
    }

    private suspend fun revalidateExecutionIdentity(
        settings: AlertAiAnalysisSettings,
        credential: String,
        networkLease: AlertAiValidatedNetworkLease
    ): RevalidatedExecution? {
        val freshSettings = try {
            settingsSource.current()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        if (!freshSettings.enabled ||
            freshSettings.config.providerId != settings.config.providerId ||
            freshSettings.config.modelId != settings.config.modelId ||
            freshSettings.config.executionIdentity() != settings.config.executionIdentity()
        ) {
            return null
        }
        val freshCredential = try {
            credentialSource.credential(freshSettings.config.providerId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }?.takeIf(String::isNotBlank) ?: return null
        if (freshCredential != credential) return null
        val freshNetworkLease = try {
            networkGate.currentValidatedNetwork()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        } ?: return null
        if (freshNetworkLease.snapshot != networkLease.snapshot) return null
        return RevalidatedExecution(freshCredential)
    }

    private suspend fun terminalizeAmbiguousInsert(
        running: AlertAiAnalysisEntity,
        requestedAt: Long
    ) {
        withContext(NonCancellable) {
            try {
                withTimeout(TERMINAL_UPDATE_TIMEOUT_MS) {
                    val stored = dao.byEpisodeId(running.episodeId)
                    if (stored != null && stored.isMatchingRunningClaim(running)) {
                        terminalUpdate(
                            stored,
                            requestedAt,
                            AlertAiAnalysisStatus.CANCELLED,
                            AlertAiAnalysisErrorCode.CANCELLED
                        )
                    }
                }
            } catch (_: Exception) {
                // The durable unique claim still prevents any retry after an ambiguous insert.
            }
        }
    }

    private suspend fun terminalUpdateBounded(
        running: AlertAiAnalysisEntity,
        requestedAt: Long,
        status: AlertAiAnalysisStatus,
        errorCode: AlertAiAnalysisErrorCode
    ) {
        withContext(NonCancellable) {
            try {
                withTimeout(TERMINAL_UPDATE_TIMEOUT_MS) {
                    terminalUpdate(running, requestedAt, status, errorCode)
                }
            } catch (_: Exception) {
                // The unique RUNNING claim still prevents recovery or another request after restart.
            }
        }
    }

    private suspend fun terminalUpdate(
        running: AlertAiAnalysisEntity,
        requestedAt: Long,
        status: AlertAiAnalysisStatus,
        errorCode: AlertAiAnalysisErrorCode
    ) {
        transitionOwnedRunning(
            running,
            running.copy(
                status = status.name,
                resultJson = null,
                completedAt = completionTime(requestedAt),
                sanitizedError = errorCode.name
            )
        )
    }

    private suspend fun transitionOwnedRunning(
        expected: AlertAiAnalysisEntity,
        next: AlertAiAnalysisEntity
    ): Int = dao.transitionOwnedRunning(
        analysisId = expected.analysisId,
        episodeId = expected.episodeId,
        provider = expected.provider,
        model = expected.model,
        expectedRequestHash = expected.requestHash,
        newRequestHash = next.requestHash,
        newStatus = next.status,
        newResultJson = next.resultJson,
        newCompletedAt = next.completedAt,
        newSanitizedError = next.sanitizedError
    )

    private fun AlertAiAnalysisEntity.isMatchingRunningClaim(expected: AlertAiAnalysisEntity): Boolean =
        analysisId == expected.analysisId &&
            episodeId == expected.episodeId &&
            provider == expected.provider &&
            model == expected.model &&
            requestHash == expected.requestHash &&
            status == AlertAiAnalysisStatus.RUNNING.name &&
            resultJson == null &&
            completedAt == null &&
            sanitizedError == null

    private fun validTrigger(trigger: AlertAiAnalysisTrigger): Boolean =
        trigger.episodeId.length in 1..128 &&
            trigger.episodeId.all(::safeIdChar) &&
            trigger.requestedAt > 0L &&
            trigger.stage.length in 1..64 &&
            trigger.stage.all(::safeIdChar) &&
            AlertCauseSnapshotCodec.sanitize(trigger.localCauseSnapshot) != null

    private fun validContext(
        context: AlertAiCanonicalContext,
        trigger: AlertAiAnalysisTrigger
    ): Boolean = AlertAiContextValidator.isValid(context, trigger)

    private fun newAnalysisId(episodeId: String): String? {
        val prefix = analysisIdFactory(episodeId)
            .takeIf { it.length in 1..MAX_ANALYSIS_ID_LENGTH && it.all(::safeIdChar) }
            ?: return null
        val claimToken = UUID.randomUUID().toString()
        val prefixLimit = MAX_ANALYSIS_ID_LENGTH - claimToken.length - 1
        if (prefixLimit < 1) return null
        return "${prefix.take(prefixLimit)}-$claimToken"
    }

    private fun completionTime(requestedAt: Long): Long = maxOf(clock(), requestedAt)

    private fun Exception.errorCode(): AlertAiAnalysisErrorCode = when (this) {
        is AlertAiNetworkLeaseUnavailableException ->
            AlertAiAnalysisErrorCode.NETWORK_UNAVAILABLE
        is ClinicalAiGatewayException.IdentityMismatch ->
            AlertAiAnalysisErrorCode.IDENTITY_MISMATCH
        is ClinicalOpenAiException.InvalidResponse,
        is ClinicalOpenAiException.OversizedResponse,
        is ClinicalOpenAiException.Incomplete,
        is ClinicalOpenAiException.Refusal -> AlertAiAnalysisErrorCode.INVALID_RESPONSE
        else -> AlertAiAnalysisErrorCode.REQUEST_FAILED
    }

    private companion object {
        val GLOBAL_ADMISSION = AlertAiExecutionAdmission()
        const val PENDING_REQUEST_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val TERMINAL_UPDATE_TIMEOUT_MS = 2_000L
        const val MAX_ANALYSIS_ID_LENGTH = 128

        fun safeIdChar(char: Char): Boolean =
            char == '_' || char == '-' || char.isLetterOrDigit()
    }

    private data class RevalidatedExecution(val credential: String)
}
