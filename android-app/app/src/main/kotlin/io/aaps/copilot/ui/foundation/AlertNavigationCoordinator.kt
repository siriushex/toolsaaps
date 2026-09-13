package io.aaps.copilot.ui.foundation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AlertNavigationRequest(
    val token: Long,
    val episodeId: String?
)

enum class AlertNavigationFailureCode {
    VALIDATION_EXCEPTION
}

class AlertNavigationCoordinator(
    scope: CoroutineScope,
    private val onValidationFailure: (AlertNavigationFailureCode) -> Unit = {},
    private val validateEpisodeId: suspend (String?) -> String?
) {
    private val candidates = Channel<String?>(ALERT_NAVIGATION_QUEUE_CAPACITY)
    private val acknowledgements = Channel<Long>(capacity = 1)
    private val accepting = AtomicBoolean(true)
    private val tokenSequence = AtomicLong(0L)
    private val acknowledgementLock = Any()
    private var acceptedAcknowledgement: Long? = null
    private val mutablePendingRequest = MutableStateFlow<AlertNavigationRequest?>(null)
    val pendingRequest: StateFlow<AlertNavigationRequest?> = mutablePendingRequest.asStateFlow()

    private val processingJob = scope.launch {
        try {
            for (candidate in candidates) {
                val validated = validateCandidate(candidate)
                val request = AlertNavigationRequest(
                    token = tokenSequence.incrementAndGet(),
                    episodeId = validated
                )
                synchronized(acknowledgementLock) {
                    acceptedAcknowledgement = null
                    mutablePendingRequest.value = request
                }
                acknowledgements.receive()
                synchronized(acknowledgementLock) {
                    mutablePendingRequest.value = null
                    acceptedAcknowledgement = null
                }
            }
        } finally {
            closePipeline()
        }
    }

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { closePipeline() }
    }

    fun enqueue(candidate: String?): Boolean {
        if (!accepting.get() || !processingJob.isActive) return false
        return candidates.trySend(candidate).isSuccess
    }

    fun acknowledge(token: Long): Boolean = synchronized(acknowledgementLock) {
        if (!accepting.get() || mutablePendingRequest.value?.token != token) return@synchronized false
        if (acceptedAcknowledgement == token) return@synchronized false
        acceptedAcknowledgement = token
        if (acknowledgements.trySend(token).isSuccess) {
            true
        } else {
            acceptedAcknowledgement = null
            false
        }
    }

    private suspend fun validateCandidate(candidate: String?): String? = try {
        validateEpisodeId(candidate)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        reportValidationFailure()
        null
    }

    private fun reportValidationFailure() {
        try {
            onValidationFailure(AlertNavigationFailureCode.VALIDATION_EXCEPTION)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            // Navigation remains history-only when a best-effort local diagnostic sink fails.
        }
    }

    private fun closePipeline() {
        if (accepting.getAndSet(false)) {
            candidates.close()
            acknowledgements.close()
        }
        synchronized(acknowledgementLock) {
            mutablePendingRequest.value = null
            acceptedAcknowledgement = null
        }
    }
}

internal const val ALERT_NAVIGATION_QUEUE_CAPACITY = 8
