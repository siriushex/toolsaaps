package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.aaps.copilot.config.ClinicalAiConfigState
import io.aaps.copilot.config.ClinicalAiConfigIdentityPolicy
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.executionIdentity
import io.aaps.copilot.data.local.dao.ClinicalReportDao
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.security.OpenAiCredentialProvider
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

fun interface ClinicalReportDatasetFactory {
    suspend fun build(nowTs: Long, zoneId: ZoneId): ClinicalReportPayload
}

sealed interface ClinicalPdfSourceCaptureResult {
    data class Ready(val source: ClinicalPdfContentSource) : ClinicalPdfSourceCaptureResult
    data object Failed : ClinicalPdfSourceCaptureResult
}

sealed interface ClinicalPdfSourceLeaseResult {
    data class Ready(val lease: ClinicalPdfSourceLease) : ClinicalPdfSourceLeaseResult
    data object Failed : ClinicalPdfSourceLeaseResult
}

enum class ClinicalPdfReprepareResult {
    PREPARE_REQUIRED,
    REJECTED
}

class ClinicalPdfSourceLease internal constructor(
    val reportIdentity: ClinicalPdfReportIdentity,
    sourceBuilder: () -> ClinicalPdfSourceCaptureResult,
    private val releaseLease: suspend () -> Unit
) {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var builder: (() -> ClinicalPdfSourceCaptureResult)? = sourceBuilder
    private var buildCompletion: CompletableDeferred<Unit>? = null

    fun buildSource(): ClinicalPdfSourceCaptureResult {
        val claimed = synchronized(lock) {
            if (closed.get()) return ClinicalPdfSourceCaptureResult.Failed
            val current = builder ?: return ClinicalPdfSourceCaptureResult.Failed
            builder = null
            buildCompletion = CompletableDeferred()
            current
        }
        return try {
            claimed()
        } finally {
            synchronized(lock) {
                buildCompletion?.complete(Unit)
            }
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val inFlightBuild = synchronized(lock) {
            builder = null
            buildCompletion
        }
        withContext(NonCancellable) {
            inFlightBuild?.await()
            releaseLease()
        }
    }
}

fun interface ClinicalAiConfigSource {
    suspend fun currentState(): ClinicalAiConfigState
}

fun interface ClinicalAiCredentialSource {
    suspend fun requireCredential(providerId: ClinicalAiProviderId): String
}

internal fun interface RequestIdGenerator {
    fun nextId(): String
}

internal fun interface ClinicalPdfPreparedLocalTransformForTest {
    fun transform(local: ClinicalLocalReport): ClinicalLocalReport
}

private object UuidRequestIdGenerator : RequestIdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

data class ClinicalLocalReport(
    val summary7d: ClinicalPeriodSummary,
    val summary30d: ClinicalPeriodSummary,
    val requestHash: String,
    val generatedAt: Long,
    val zoneId: String,
    val summary24h: ClinicalPeriodSummary? = null,
    val energyProfile: ClinicalEnergyProfileSummary? = null,
    val plannedActivities: List<ClinicalPlannedActivitySummary> = emptyList(),
    val forecastQuality: List<ClinicalForecastQuality> = emptyList(),
    val eventSummaries: List<ClinicalEventSummary> = emptyList(),
    val eventTypeAssociations: List<ClinicalEventTypeAssociation> = emptyList(),
    val remoteEventPreviewJson: String = EMPTY_REMOTE_EVENT_PREVIEW_JSON
)

internal const val EMPTY_REMOTE_EVENT_PREVIEW_JSON =
    "{\"ev24\":[],\"ev7\":[],\"ev30\":[]}"

sealed interface ClinicalReportState {
    data object Idle : ClinicalReportState

    data class Building(
        val requestId: String
    ) : ClinicalReportState

    data class LocalReady(
        val requestId: String,
        val local: ClinicalLocalReport
    ) : ClinicalReportState

    data class Uploading(
        val requestId: String,
        val local: ClinicalLocalReport,
        val completed: Int,
        val total: Int,
        val stage: ClinicalOpenAiProgressStage = ClinicalOpenAiProgressStage.PREPARING,
        val level: Int = 0
    ) : ClinicalReportState

    data class Complete(
        val requestId: String,
        val local: ClinicalLocalReport,
        val report: ClinicalAdvisoryReport,
        val metadata: ClinicalOpenAiMetadata
    ) : ClinicalReportState

    data class Failed(
        val requestId: String,
        val local: ClinicalLocalReport?,
        val reason: ClinicalReportFailureReason
    ) : ClinicalReportState

    data class Cancelled(
        val requestId: String,
        val local: ClinicalLocalReport?
    ) : ClinicalReportState
}

enum class ClinicalReportStatus {
    BUILDING,
    LOCAL_READY,
    UPLOADING,
    COMPLETE,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    ACKNOWLEDGED_UNKNOWN_OUTCOME
}

enum class ClinicalReportFailureReason {
    LOCAL_BUILD,
    CREDENTIAL_UNAVAILABLE,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,
    HTTP,
    TIMEOUT,
    NETWORK,
    REFUSAL,
    INCOMPLETE,
    INVALID_RESPONSE,
    OVERSIZED_RESPONSE,
    REQUEST_TOO_LARGE,
    DATASET_TOO_LARGE,
    INVALID_INPUT,
    PARTIAL_CHUNK,
    UNKNOWN_REMOTE_OUTCOME,
    PROCESS_INTERRUPTED_PRE_REQUEST,
    CANCELLED
}

enum class ClinicalReportRunDisposition {
    LOCAL_STARTED,
    REMOTE_STARTED,
    LOCAL_IN_FLIGHT,
    REMOTE_IN_FLIGHT,
    PREPARED_REUSED,
    COOLDOWN,
    RETRY_REQUIRED
}

data class ClinicalReportRun(
    val requestId: String,
    val job: Job,
    val disposition: ClinicalReportRunDisposition
)

internal class ClinicalReportConfigurationChangedException :
    Exception("Clinical AI configuration changed")

class ClinicalReportRepository private constructor(
    private val datasetFactory: ClinicalReportDatasetFactory,
    private val configSource: ClinicalAiConfigSource,
    private val gatewayFactory: ClinicalAiGatewayFactory,
    private val credentialSource: ClinicalAiCredentialSource,
    private val reportDao: ClinicalReportDao,
    private val auditLogger: AuditLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestIdGenerator: RequestIdGenerator,
    private val preparedLocalTransformForTest: ClinicalPdfPreparedLocalTransformForTest?
) {
    constructor(
        datasetFactory: ClinicalReportDatasetFactory,
        configSource: ClinicalAiConfigSource,
        gatewayFactory: ClinicalAiGatewayFactory,
        credentialSource: ClinicalAiCredentialSource,
        reportDao: ClinicalReportDao,
        auditLogger: AuditLogger,
        clock: () -> Long = System::currentTimeMillis,
        monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        datasetFactory = datasetFactory,
        configSource = configSource,
        gatewayFactory = gatewayFactory,
        credentialSource = credentialSource,
        reportDao = reportDao,
        auditLogger = auditLogger,
        clock = clock,
        monotonicClock = monotonicClock,
        dispatcher = dispatcher,
        requestIdGenerator = UuidRequestIdGenerator,
        preparedLocalTransformForTest = null
    )

    constructor(
        datasetFactory: ClinicalReportDatasetFactory,
        credentialProvider: OpenAiCredentialProvider,
        client: ClinicalOpenAiClient,
        reportDao: ClinicalReportDao,
        auditLogger: AuditLogger,
        clock: () -> Long = System::currentTimeMillis,
        monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        datasetFactory = datasetFactory,
        configSource = fixedConfigSource(client),
        gatewayFactory = fixedGatewayFactory(client),
        credentialSource = fixedCredentialSource(credentialProvider),
        reportDao = reportDao,
        auditLogger = auditLogger,
        clock = clock,
        monotonicClock = monotonicClock,
        dispatcher = dispatcher,
        requestIdGenerator = UuidRequestIdGenerator,
        preparedLocalTransformForTest = null
    )

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<ClinicalReportState>(
        ClinicalReportState.Idle
    )

    val state: kotlinx.coroutines.flow.StateFlow<ClinicalReportState> =
        mutableState.asStateFlow()

    @Volatile
    private var active: ActiveRun? = null
    @Volatile
    private var prepared: PreparedReport? = null
    private val activePdfSourceLeases = mutableSetOf<Long>()
    private var nextPdfSourceLeaseId = 0L
    private var pdfSourceLeasesIdle = CompletableDeferred(Unit)
    private var lastRemoteTerminal: TerminalRun? = null
    private var pendingRemoteStart: PendingRemoteStart? = null
    private val recovery: Deferred<Unit> = scope.async(start = CoroutineStart.LAZY) {
        recoverInterruptedRows()
    }

    suspend fun prepareLocal(nowTs: Long, zoneId: ZoneId): ClinicalReportRun {
        awaitRecovery()
        val callerJob = currentCoroutineContext()[Job]
        val run = withContext(NonCancellable) {
            val published = mutex.withLock {
                active?.let {
                    return@withLock ClinicalReportRun(
                        it.requestId,
                        it.job,
                        it.currentDisposition()
                    )
                }
                val guardedUnknown = guardedUnknownState()
                freshPrepared(nowTs, zoneId)?.let {
                    if (guardedUnknown != null) {
                        mutableState.value = guardedUnknown.copy(local = it.local)
                    }
                    return@withLock ClinicalReportRun(
                        guardedUnknown?.requestId ?: it.requestId,
                        it.job,
                        ClinicalReportRunDisposition.PREPARED_REUSED
                    )
                }
                if (guardedUnknown != null) {
                    return@withLock createRun(
                        reservation = RunReservation(
                            requestId = guardedUnknown.requestId,
                            createdAt = clock()
                        ),
                        nowTs = nowTs,
                        zoneId = zoneId,
                        remote = false,
                        reusable = null,
                        preservedUnknownGuard = guardedUnknown
                    )
                }
                createRun(
                    reservation = reserveInitial(nowTs),
                    nowTs = nowTs,
                    zoneId = zoneId,
                    remote = false,
                    reusable = null
                )
            }
            activatePublishedRun(published, callerJob)
        }
        if (run.disposition == ClinicalReportRunDisposition.LOCAL_STARTED) {
            run.job.join()
        }
        return run
    }

    suspend fun start(
        nowTs: Long,
        zoneId: ZoneId,
        force: Boolean = false,
        expectedConfigIdentity: String? = null
    ): ClinicalReportRun {
        expectedConfigIdentity?.let(ClinicalAiConfigIdentityPolicy::requireValid)
        if (expectedConfigIdentity == null) {
            awaitRecovery()
        }
        val callerJob = currentCoroutineContext()[Job]
        while (true) {
            val claim = mutex.withLock {
                pendingRemoteStart?.let {
                    if (it.expectedConfigIdentity != expectedConfigIdentity) {
                        throw ClinicalReportConfigurationChangedException()
                    }
                    return@withLock RemoteStartClaim.Await(it.result)
                }
                blockedStartDecision(force)?.let {
                    return@withLock RemoteStartClaim.Decided(it)
                }
                val pending = PendingRemoteStart(expectedConfigIdentity)
                pendingRemoteStart = pending
                RemoteStartClaim.Owner(pending)
            }
            when (claim) {
                is RemoteStartClaim.Await -> return claim.result.await()
                is RemoteStartClaim.Decided -> when (val decision = claim.decision) {
                    is StartDecision.WaitForLocal -> {
                        decision.job.join()
                        continue
                    }
                    is StartDecision.Publish -> return decision.run
                }
                is RemoteStartClaim.Owner -> {
                    return startClaimedRemote(
                        pending = claim.pending,
                        nowTs = nowTs,
                        zoneId = zoneId,
                        force = force,
                        callerJob = callerJob
                    )
                }
            }
        }
    }

    private suspend fun startClaimedRemote(
        pending: PendingRemoteStart,
        nowTs: Long,
        zoneId: ZoneId,
        force: Boolean,
        callerJob: Job?
    ): ClinicalReportRun {
        try {
            val remoteExecution = resolveRemoteExecution(pending.expectedConfigIdentity)
            if (pending.expectedConfigIdentity != null) {
                awaitRecovery()
            }
            currentCoroutineContext().ensureActive()
            while (true) {
                val decision = withContext(NonCancellable) {
                    mutex.withLock {
                        check(pendingRemoteStart === pending) {
                            "Clinical report remote start ownership changed"
                        }
                        blockedStartDecision(force)?.let { return@withLock it }
                        val terminal = lastRemoteTerminal
                        val stateBeforeReservation = mutableState.value
                        val reusable = freshPrepared(nowTs, zoneId)
                        val reservation = if (
                            !force &&
                            reusable != null &&
                            reusable.requestId != terminal?.requestId
                        ) {
                            RunReservation(
                                requestId = reusable.requestId,
                                createdAt = reusable.createdAt
                            )
                        } else {
                            reserveInitial(nowTs, remoteExecution.config)
                        }
                        if (force && terminal?.explicitRetryRequired == true) {
                            val acknowledged = try {
                                reportDao.acknowledgeAndDeleteUnknownOutcome(
                                    requestId = terminal.requestId,
                                    expectedStatuses = UNACKNOWLEDGED_UNKNOWN_STATUSES,
                                    sanitizedError =
                                        ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name,
                                    acknowledgedStatus =
                                        ClinicalReportStatus.ACKNOWLEDGED_UNKNOWN_OUTCOME.name
                                )
                            } catch (failure: Exception) {
                                reconcileCancelledReservation(reservation.requestId)
                                mutableState.value = stateBeforeReservation
                                throw failure
                            }
                            if (acknowledged != 1) {
                                reconcileCancelledReservation(reservation.requestId)
                                mutableState.value = stateBeforeReservation
                                throw IllegalStateException(
                                    "Clinical report unknown outcome acknowledgement conflict"
                                )
                            }
                            lastRemoteTerminal = null
                        }
                        StartDecision.Publish(
                            createRun(
                                reservation = reservation,
                                nowTs = nowTs,
                                zoneId = zoneId,
                                remote = true,
                                reusable = reusable,
                                remoteExecution = remoteExecution
                            )
                        )
                    }
                }

                when (decision) {
                    is StartDecision.WaitForLocal -> {
                        decision.job.join()
                        currentCoroutineContext().ensureActive()
                    }
                    is StartDecision.Publish -> {
                        val run = withContext(NonCancellable) {
                            activatePublishedRun(decision.run, callerJob)
                        }
                        completePendingRemoteStart(pending, run)
                        return run
                    }
                }
            }
        } catch (failure: Throwable) {
            failPendingRemoteStart(pending, failure)
            throw failure
        }
    }

    private suspend fun completePendingRemoteStart(
        pending: PendingRemoteStart,
        run: ClinicalReportRun
    ) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (pendingRemoteStart === pending) {
                    pendingRemoteStart = null
                }
                pending.result.complete(run)
            }
        }
    }

    private suspend fun failPendingRemoteStart(
        pending: PendingRemoteStart,
        failure: Throwable
    ) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (pendingRemoteStart === pending) {
                    pendingRemoteStart = null
                }
                pending.result.completeExceptionally(failure)
            }
        }
    }

    private fun blockedStartDecision(force: Boolean): StartDecision? {
        active?.let { activeRun ->
            if (
                force &&
                !activeRun.remote &&
                guardedUnknownState() != null
            ) {
                return StartDecision.WaitForLocal(activeRun.job)
            }
            return StartDecision.Publish(
                ClinicalReportRun(
                    activeRun.requestId,
                    activeRun.job,
                    activeRun.currentDisposition()
                )
            )
        }
        val terminal = lastRemoteTerminal
        require(
            !force ||
                mutableState.value.isTerminal() ||
                terminal?.explicitRetryRequired == true
        ) {
            "Forced clinical report retry requires a terminal remote state"
        }
        if (!force && terminal != null) {
            val blockedDisposition = when {
                terminal.explicitRetryRequired ->
                    ClinicalReportRunDisposition.RETRY_REQUIRED
                isCoolingDown(terminal) -> ClinicalReportRunDisposition.COOLDOWN
                else -> null
            }
            if (blockedDisposition != null) {
                return StartDecision.Publish(
                    ClinicalReportRun(
                        terminal.requestId,
                        terminal.job,
                        blockedDisposition
                    )
                )
            }
        }
        return null
    }

    suspend fun cancelActive() {
        val job = mutex.withLock {
            active?.takeIf { it.job.isActive }?.also {
                it.cancelRequested = true
                it.job.cancel(CancellationException("Clinical report cancelled"))
            }?.job
        }
        job?.join()
    }

    suspend fun releasePreparedPayloadWhenIdle() {
        while (true) {
            val waitFor = mutex.withLock {
                active?.job?.takeIf { it.isActive }
                    ?: pdfSourceLeasesIdle.takeIf { activePdfSourceLeases.isNotEmpty() }
                    ?: run {
                        prepared = null
                        null
                    }
            }
            if (waitFor == null) return
            waitFor.join()
        }
    }

    suspend fun acquireClinicalPdfSourceLease(): ClinicalPdfSourceLeaseResult = mutex.withLock {
        val currentPrepared = prepared ?: return@withLock ClinicalPdfSourceLeaseResult.Failed
        val currentState = mutableState.value
        val local: ClinicalLocalReport
        val complete: ClinicalOpenAiResult?
        val requestId: String
        when (currentState) {
            is ClinicalReportState.LocalReady -> {
                requestId = currentState.requestId
                local = currentState.local
                complete = null
            }
            is ClinicalReportState.Complete -> {
                requestId = currentState.requestId
                local = currentState.local
                complete = ClinicalOpenAiResult(currentState.report, currentState.metadata)
            }
            ClinicalReportState.Idle,
            is ClinicalReportState.Building,
            is ClinicalReportState.Uploading,
            is ClinicalReportState.Failed,
            is ClinicalReportState.Cancelled ->
                return@withLock ClinicalPdfSourceLeaseResult.Failed
        }
        if (
            requestId != currentPrepared.requestId ||
            local !== currentPrepared.local ||
            local.requestHash != currentPrepared.payload.sha256
        ) {
            return@withLock ClinicalPdfSourceLeaseResult.Failed
        }
        val reportIdentity = ClinicalPdfReportIdentity.from(currentState)
        if (!reportIdentity.isExportable) return@withLock ClinicalPdfSourceLeaseResult.Failed
        val leaseId = ++nextPdfSourceLeaseId
        if (activePdfSourceLeases.isEmpty()) {
            pdfSourceLeasesIdle = CompletableDeferred()
        }
        activePdfSourceLeases += leaseId
        ClinicalPdfSourceLeaseResult.Ready(
            ClinicalPdfSourceLease(
                reportIdentity = reportIdentity,
                sourceBuilder = {
                    when (
                        val result = ClinicalPdfContentSourceFactory.create(
                            payload = currentPrepared.payload,
                            local = local,
                            complete = complete
                        )
                    ) {
                        is ClinicalPdfContentSourceResult.Ready ->
                            ClinicalPdfSourceCaptureResult.Ready(result.source)
                        is ClinicalPdfContentSourceResult.Failed ->
                            ClinicalPdfSourceCaptureResult.Failed
                    }
                },
                releaseLease = { releaseClinicalPdfSourceLease(leaseId) }
            )
        )
    }

    suspend fun claimClinicalPdfReprepare(
        expectedIdentity: ClinicalPdfReportIdentity
    ): ClinicalPdfReprepareResult =
        mutex.withLock {
            val currentState = mutableState.value
            val currentIdentity = ClinicalPdfReportIdentity.from(currentState)
            val currentRequestId: String
            val currentLocal: ClinicalLocalReport
            when (currentState) {
                is ClinicalReportState.LocalReady -> {
                    currentRequestId = currentState.requestId
                    currentLocal = currentState.local
                }
                is ClinicalReportState.Complete -> {
                    currentRequestId = currentState.requestId
                    currentLocal = currentState.local
                }
                ClinicalReportState.Idle,
                is ClinicalReportState.Building,
                is ClinicalReportState.Uploading,
                is ClinicalReportState.Failed,
                is ClinicalReportState.Cancelled ->
                    return@withLock ClinicalPdfReprepareResult.REJECTED
            }
            if (
                !expectedIdentity.isExportable ||
                currentIdentity != expectedIdentity ||
                active?.job?.isActive == true ||
                activePdfSourceLeases.isNotEmpty()
            ) {
                return@withLock ClinicalPdfReprepareResult.REJECTED
            }
            val currentPrepared = prepared
            if (
                currentPrepared != null &&
                (
                    currentPrepared.requestId != currentRequestId ||
                        currentPrepared.local !== currentLocal ||
                        currentPrepared.payload.sha256 != currentLocal.requestHash
                )
            ) {
                return@withLock ClinicalPdfReprepareResult.REJECTED
            }
            prepared = null
            ClinicalPdfReprepareResult.PREPARE_REQUIRED
        }

    private suspend fun releaseClinicalPdfSourceLease(leaseId: Long) {
        mutex.withLock {
            if (activePdfSourceLeases.remove(leaseId) && activePdfSourceLeases.isEmpty()) {
                pdfSourceLeasesIdle.complete(Unit)
            }
        }
    }

    fun releasePreparedPayloadWhenIdleAsync() {
        scope.launch {
            releasePreparedPayloadWhenIdle()
        }
    }

    internal fun initialize() {
        recovery.start()
    }

    suspend fun recoverInterrupted() {
        awaitRecovery()
    }

    private suspend fun awaitRecovery() {
        recovery.start()
        recovery.await()
    }

    private suspend fun recoverInterruptedRows() {
        withContext(NonCancellable) {
            val interruptedAt = clock()
            val stale = reportDao.activeForRecovery(
                statuses = ACTIVE_RECOVERY_STATUSES,
                limit = RECOVERY_LIMIT
            )
            val preRequestRecovered = mutableListOf<RecoveredRun>()
            var interrupted = 0
            stale.forEach { entity ->
                val reason = if (entity.status == ClinicalReportStatus.UPLOADING.name) {
                    ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
                } else {
                    ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST
                }
                val changed = reportDao.markInterruptedIfStatusIn(
                    requestId = entity.requestId,
                    expectedStatuses = listOf(entity.status),
                    interruptedStatus = ClinicalReportStatus.INTERRUPTED.name,
                    completedAt = interruptedAt,
                    sanitizedError = reason.name
                )
                interrupted += changed
                if (
                    changed == 1 &&
                    reason == ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST
                ) {
                    preRequestRecovered += RecoveredRun(entity, reason)
                }
            }
            deleteExpiredReports()
            val unknownGuard = reportDao.latestUnknownOutcomeGuard(
                statuses = UNACKNOWLEDGED_UNKNOWN_STATUSES,
                sanitizedError = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
            )?.let { entity ->
                RecoveredRun(
                    entity,
                    ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
                )
            }
            (unknownGuard ?: preRequestRecovered.firstOrNull())
                ?.let(::hydrateRecovered)
            if (interrupted > 0) {
                safeAudit(
                    level = AuditLevel.WARN,
                    message = "clinical_report_interrupted_recovered",
                    metadata = mapOf("count" to interrupted)
                )
            }
        }
    }

    private fun hydrateRecovered(recovered: RecoveredRun) {
        val entity = recovered.entity
        val local = ClinicalReportPersistenceSerializer.local(entity)
        mutableState.value = ClinicalReportState.Failed(
            requestId = entity.requestId,
            local = local,
            reason = recovered.reason
        )
        if (recovered.reason == ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME) {
            lastRemoteTerminal = TerminalRun(
                requestId = entity.requestId,
                job = completedJob(),
                completedMonotonicAt = monotonicClock(),
                explicitRetryRequired = true
            )
        }
    }

    private fun completedJob(): Job = Job().also { job ->
        job.complete()
    }

    private suspend fun resolveRemoteExecution(
        expectedConfigIdentity: String?
    ): RemoteExecution {
        val state = try {
            configSource.currentState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            if (expectedConfigIdentity != null) {
                throw ClinicalReportConfigurationChangedException()
            }
            return RemoteExecution.Rejected(
                config = null,
                reason = ClinicalReportFailureReason.INVALID_INPUT
            )
        }
        val config = when (state) {
            is ClinicalAiConfigState.UnconfiguredDefault -> state.config
            is ClinicalAiConfigState.Valid -> state.config
            is ClinicalAiConfigState.Invalid -> {
                if (expectedConfigIdentity != null) {
                    throw ClinicalReportConfigurationChangedException()
                }
                return RemoteExecution.Rejected(
                    config = null,
                    reason = ClinicalReportFailureReason.INVALID_INPUT
                )
            }
        }
        if (
            expectedConfigIdentity != null &&
            config.executionIdentity() != expectedConfigIdentity
        ) {
            throw ClinicalReportConfigurationChangedException()
        }
        return try {
            RemoteExecution.Ready(
                config = config,
                gateway = gatewayFactory.create(config)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            RemoteExecution.Rejected(
                config = config,
                reason = ClinicalReportFailureReason.INVALID_RESPONSE
            )
        }
    }

    private suspend fun reserveInitial(
        nowTs: Long,
        config: ClinicalAiProviderConfig? = null
    ): RunReservation {
        deleteExpiredReports()
        repeat(REQUEST_ID_RESERVATION_ATTEMPTS) {
            val requestId = generatedRequestId()
            val createdAt = clock()
            val entity = buildingEntity(requestId, nowTs, createdAt, config)
            val inserted = try {
                reportDao.insertInitial(entity)
            } catch (cancelled: CancellationException) {
                reconcileCancelledReservation(requestId)
                throw cancelled
            }
            if (inserted != -1L) {
                mutableState.value = ClinicalReportState.Building(requestId)
                return RunReservation(
                    requestId = requestId,
                    createdAt = createdAt
                )
            }
        }
        throw IllegalStateException(
            "Unable to reserve a unique clinical report request ID after " +
                "$REQUEST_ID_RESERVATION_ATTEMPTS attempts"
        )
    }

    private suspend fun reconcileCancelledReservation(requestId: String) {
        reportDao.markInterruptedIfStatusIn(
            requestId = requestId,
            expectedStatuses = listOf(ClinicalReportStatus.BUILDING.name),
            interruptedStatus = ClinicalReportStatus.INTERRUPTED.name,
            completedAt = clock(),
            sanitizedError =
                ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST.name
        )
    }

    private fun createRun(
        reservation: RunReservation,
        nowTs: Long,
        zoneId: ZoneId,
        remote: Boolean,
        reusable: PreparedReport?,
        preservedUnknownGuard: ClinicalReportState.Failed? = null,
        remoteExecution: RemoteExecution? = null
    ): ClinicalReportRun {
        check(remote == (remoteExecution != null)) {
            "Clinical report remote execution snapshot is inconsistent"
        }
        val run = ActiveRun(
            requestId = reservation.requestId,
            createdAt = reservation.createdAt,
            remote = remote,
            preservedUnknownGuard = preservedUnknownGuard,
            remoteExecution = remoteExecution
        )
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            execute(run, nowTs, zoneId, reusable)
        }
        active = run
        return ClinicalReportRun(
            run.requestId,
            run.job,
            if (remote) {
                ClinicalReportRunDisposition.REMOTE_STARTED
            } else {
                ClinicalReportRunDisposition.LOCAL_STARTED
            }
        )
    }

    private suspend fun activatePublishedRun(
        published: ClinicalReportRun,
        callerJob: Job?
    ): ClinicalReportRun {
        if (
            published.disposition != ClinicalReportRunDisposition.LOCAL_STARTED &&
            published.disposition != ClinicalReportRunDisposition.REMOTE_STARTED
        ) {
            return published
        }
        if (callerJob?.isActive == false) {
            cancelUnstartedRun(published)
            throw CancellationException(
                "Clinical report caller cancelled during reservation"
            )
        }
        published.job.start()
        return published
    }

    private suspend fun cancelUnstartedRun(published: ClinicalReportRun) {
        mutex.withLock {
            val run = active?.takeIf {
                it.requestId == published.requestId && it.job === published.job
            } ?: return
            val changed = reportDao.markInterruptedIfStatusIn(
                requestId = run.requestId,
                expectedStatuses = listOf(ClinicalReportStatus.BUILDING.name),
                interruptedStatus = ClinicalReportStatus.CANCELLED.name,
                completedAt = clock(),
                sanitizedError = ClinicalReportFailureReason.CANCELLED.name
            )
            if (changed == 1) {
                run.terminalPersisted = true
                mutableState.value = ClinicalReportState.Cancelled(run.requestId, null)
            }
            active = null
            run.job.cancel(CancellationException("Clinical report caller cancelled"))
        }
    }

    private suspend fun execute(
        run: ActiveRun,
        nowTs: Long,
        zoneId: ZoneId,
        reusable: PreparedReport?
    ) {
        var local: ClinicalLocalReport? = null
        try {
            val currentPrepared = if (reusable != null && reusable.isFresh(nowTs, zoneId)) {
                reusable
            } else {
                null
            }
            val payload = if (currentPrepared == null) {
                val built = datasetFactory.build(nowTs, zoneId)
                currentCoroutineContext().ensureActive()
                run.payload = built
                local = built.toLocalReport()
                val preparedLocal = preparedLocalTransformForTest?.transform(checkNotNull(local))
                    ?: checkNotNull(local)
                prepared = PreparedReport(
                    requestId = run.requestId,
                    payload = built,
                    local = preparedLocal,
                    preparedForTs = nowTs,
                    zoneId = zoneId,
                    createdAt = run.createdAt,
                    job = run.job
                )
                persistLocalReady(run, built, checkNotNull(local))
                built
            } else {
                run.payload = currentPrepared.payload
                local = currentPrepared.local
                if (run.requestId != currentPrepared.requestId) {
                    prepared = currentPrepared.copy(
                        requestId = run.requestId,
                        createdAt = run.createdAt,
                        job = run.job
                    )
                }
                persistLocalReady(run, currentPrepared.payload, currentPrepared.local)
                currentPrepared.payload
            }

            if (!run.remote) return
            val remoteExecution = checkNotNull(run.remoteExecution)
            if (remoteExecution is RemoteExecution.Rejected) {
                failOrPreserveUnknown(run, local, remoteExecution.reason)
                return
            }
            remoteExecution as RemoteExecution.Ready
            yield()
            currentCoroutineContext().ensureActive()
            persistUploading(run, payload, checkNotNull(local))
            run.remoteBegan = true
            val observer = object :
                ClinicalOpenAiProgressCallback,
                ClinicalOpenAiExecutionObserver {
                override fun onProgress(progress: ClinicalOpenAiProgress) {
                    publishProgress(run, checkNotNull(local), progress)
                }

                override fun onExecutionSnapshot(
                    snapshot: ClinicalOpenAiExecutionSnapshot
                ) {
                    run.latestExecutionSnapshot = snapshot
                }
            }
            val result = remoteExecution.gateway.analyze(
                payload = payload,
                credential = {
                    requireCredential(remoteExecution.config.providerId)
                },
                progress = observer
            )
            currentCoroutineContext().ensureActive()
            requireMatchingProviderMetadata(run, result.metadata)
            complete(run, payload, checkNotNull(local), result)
        } catch (cancelled: CancellationException) {
            if (!run.terminalPersisted) {
                cancel(run, local)
            }
        } catch (fatal: Error) {
            if (!run.terminalPersisted) {
                failOrPreserveUnknown(
                    run = run,
                    local = local,
                    reason = if (run.remoteBegan) {
                        ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
                    } else {
                        ClinicalReportFailureReason.LOCAL_BUILD
                    }
                )
            }
            throw fatal
        } catch (failure: ClinicalOpenAiException) {
            if (!run.terminalPersisted) {
                failOrPreserveUnknown(run, local, failure.toReason(run.remoteBegan))
            }
        } catch (_: ClinicalAiGatewayException.IdentityMismatch) {
            if (!run.terminalPersisted) {
                failOrPreserveUnknown(
                    run,
                    local,
                    ClinicalReportFailureReason.INVALID_RESPONSE
                )
            }
        } catch (_: Exception) {
            if (!run.terminalPersisted) {
                failOrPreserveUnknown(
                    run = run,
                    local = local,
                    reason = if (run.remoteBegan) {
                        ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
                    } else {
                        ClinicalReportFailureReason.LOCAL_BUILD
                    }
                )
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (active === run) active = null
                }
            }
        }
    }

    private suspend fun requireCredential(providerId: ClinicalAiProviderId): String {
        val credential = try {
            credentialSource.requireCredential(providerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            throw ClinicalOpenAiException.CredentialUnavailable()
        }
        if (credential.isBlank()) {
            throw ClinicalOpenAiException.CredentialUnavailable()
        }
        return credential
    }

    private fun buildingEntity(
        requestId: String,
        nowTs: Long,
        createdAt: Long,
        config: ClinicalAiProviderConfig?
    ): ClinicalReportEntity = ClinicalReportEntity(
        requestId = requestId,
        status = ClinicalReportStatus.BUILDING.name,
        requestedFromTs = nowTs - THIRTY_DAYS_MS,
        requestedThroughTs = nowTs,
        requestHash = "",
        coverageJson = EMPTY_JSON,
        localSummaryJson = EMPTY_JSON,
        responseJson = null,
        renderedText = null,
        model = config?.modelId,
        provider = config?.providerId?.name ?: LOCAL_ONLY_PROVIDER,
        schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
        createdAt = createdAt,
        completedAt = null,
        sanitizedError = null
    )

    private suspend fun persistLocalReady(
        run: ActiveRun,
        payload: ClinicalReportPayload,
        local: ClinicalLocalReport
    ) {
        deleteExpiredReports()
        val preservedUnknownGuard = run.preservedUnknownGuard
        if (preservedUnknownGuard != null) {
            mutex.withLock {
                if (!run.canPublish()) return
                check(
                    reportDao.refreshUnknownOutcomeLocalIfStatusIn(
                        requestId = preservedUnknownGuard.requestId,
                        expectedStatuses = UNACKNOWLEDGED_UNKNOWN_STATUSES,
                        sanitizedError =
                            ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name,
                        requestedFromTs = payload.dataset.summary30d.fromTs,
                        requestedThroughTs = payload.dataset.summary30d.throughTs,
                        requestHash = payload.sha256,
                        coverageJson = ClinicalReportPersistenceSerializer.coverage(
                            payload.dataset
                        ),
                        localSummaryJson = ClinicalReportPersistenceSerializer.localSummary(
                            payload.dataset
                        ),
                        schemaVersion = payload.dataset.schemaVersion
                    ) == 1
                ) {
                    "Clinical report unknown outcome local refresh conflict"
                }
                mutableState.value = preservedUnknownGuard.copy(local = local)
            }
            safeAudit(
                AuditLevel.INFO,
                "clinical_report_unknown_local_refreshed",
                mapOf("requestId" to preservedUnknownGuard.requestId)
            )
            return
        }
        val entity = localEntity(
            run = run,
            payload = payload,
            status = ClinicalReportStatus.LOCAL_READY
        )
        mutex.withLock {
            if (!run.canPublish()) return
            requireLifecycleTransition(
                entity = entity,
                expectedStatuses = listOf(
                    ClinicalReportStatus.BUILDING.name,
                    ClinicalReportStatus.LOCAL_READY.name
                )
            )
            mutableState.value = ClinicalReportState.LocalReady(run.requestId, local)
        }
        safeAudit(
            AuditLevel.INFO,
            "clinical_report_local_ready",
            mapOf(
                "requestId" to run.requestId,
                "requestHash" to payload.sha256,
                "schemaVersion" to payload.dataset.schemaVersion
            )
        )
    }

    private suspend fun persistUploading(
        run: ActiveRun,
        payload: ClinicalReportPayload,
        local: ClinicalLocalReport
    ) {
        val entity = localEntity(
            run = run,
            payload = payload,
            status = ClinicalReportStatus.UPLOADING
        )
        mutex.withLock {
            if (!run.canPublish()) return
            requireLifecycleTransition(
                entity = entity,
                expectedStatuses = listOf(ClinicalReportStatus.LOCAL_READY.name)
            )
            run.stageTiming.transition(
                ClinicalOpenAiProgressStage.PREPARING,
                monotonicClock()
            )
            mutableState.value = ClinicalReportState.Uploading(
                run.requestId,
                local,
                completed = 0,
                total = 1,
                stage = ClinicalOpenAiProgressStage.PREPARING,
                level = 0
            )
        }
    }

    private fun publishProgress(
        run: ActiveRun,
        local: ClinicalLocalReport,
        progress: ClinicalOpenAiProgress
    ) {
        if (!run.canPublish()) return
        run.stageTiming.transition(progress.stage, monotonicClock())
        val total = progress.count.coerceAtLeast(1)
        val completed = when (progress.stage) {
            ClinicalOpenAiProgressStage.PREPARING -> 0
            ClinicalOpenAiProgressStage.ANALYZING_CHUNK -> progress.index
            ClinicalOpenAiProgressStage.REDUCING -> progress.index
            ClinicalOpenAiProgressStage.SYNTHESIZING,
            ClinicalOpenAiProgressStage.VALIDATING -> 0
            ClinicalOpenAiProgressStage.COMPLETED -> progress.count
        }.coerceIn(0, total)
        mutableState.value = ClinicalReportState.Uploading(
            run.requestId,
            local,
            completed,
            total,
            stage = progress.stage,
            level = progress.level.coerceAtLeast(0)
        )
    }

    private suspend fun complete(
        run: ActiveRun,
        payload: ClinicalReportPayload,
        local: ClinicalLocalReport,
        result: ClinicalOpenAiResult
    ) {
        val stageDurations = run.stageTiming.finish(monotonicClock())
        val terminalized = withContext(NonCancellable) {
            val completedAt = clock()
            val entity = localEntity(
                run = run,
                payload = payload,
                status = ClinicalReportStatus.COMPLETE,
                responseJson = ClinicalReportPersistenceSerializer.response(result.report),
                model = result.metadata.model,
                schemaVersion = result.metadata.schemaVersion,
                completedAt = completedAt,
                executionMetadata = result.metadata
            )
            mutex.withLock {
                if (!run.canPublish()) return@withLock false
                requireLifecycleTransition(
                    entity = entity,
                    expectedStatuses = listOf(ClinicalReportStatus.UPLOADING.name)
                )
                run.terminalPersisted = true
                mutableState.value = ClinicalReportState.Complete(
                    run.requestId,
                    local,
                    result.report,
                    result.metadata
                )
                lastRemoteTerminal = terminalRun(
                    run = run,
                    explicitRetryRequired = false
                )
                true
            }
        }
        if (!terminalized) {
            if (run.cancelRequested && !run.terminalPersisted) {
                cancel(run, local)
            }
            return
        }
        terminalAudit(
            AuditLevel.INFO,
            "clinical_report_complete",
            mapOf(
                "requestId" to run.requestId,
                "requestHash" to payload.sha256,
                "coverageLedgerHash" to result.metadata.coverageLedgerHash.orEmpty(),
                "provider" to result.metadata.providerId.name,
                "model" to result.metadata.model,
                "schemaVersion" to result.metadata.schemaVersion,
                "leafCount" to result.metadata.chunkCount,
                "reductionLevels" to result.metadata.reductionLevels,
                "maxRequestBytes" to result.metadata.maxRequestBytes,
                "maxResponseBytes" to result.metadata.maxResponseBytes,
                "totalRequestBytes" to result.metadata.totalRequestBytes,
                "totalResponseBytes" to result.metadata.totalResponseBytes,
                "durationMs" to result.metadata.durationMs,
                "preparingDurationMs" to stageDurations.preparingMs,
                "analyzingDurationMs" to stageDurations.analyzingMs,
                "reducingDurationMs" to stageDurations.reducingMs,
                "synthesizingDurationMs" to stageDurations.synthesizingMs,
                "validatingDurationMs" to stageDurations.validatingMs
            )
        )
    }

    private suspend fun fail(
        run: ActiveRun,
        local: ClinicalLocalReport?,
        reason: ClinicalReportFailureReason
    ) {
        val stageDurations = run.stageTiming.finish(monotonicClock())
        withContext(NonCancellable) {
            val completedAt = clock()
            val entity = terminalEntity(
                run = run,
                local = local,
                status = ClinicalReportStatus.FAILED,
                completedAt = completedAt,
                reason = reason
            )
            val persisted: Boolean? = mutex.withLock {
                if (active !== run) return@withLock false
                if (run.cancelRequested) return@withLock null
                val changed = reportDao.updateIfStatusIn(
                    entity = entity,
                    expectedStatuses = ACTIVE_LIFECYCLE_STATUSES
                )
                if (changed != 1) return@withLock false
                run.terminalPersisted = true
                run.explicitRetryRequired =
                    reason == ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
                mutableState.value = ClinicalReportState.Failed(run.requestId, local, reason)
                if (run.remoteBegan) {
                    lastRemoteTerminal = terminalRun(
                        run = run,
                        explicitRetryRequired = run.explicitRetryRequired
                    )
                }
                true
            }
            if (persisted == null) {
                cancel(run, local)
                return@withContext
            }
            if (persisted) {
                terminalAudit(
                    AuditLevel.WARN,
                    "clinical_report_failed",
                    mapOf("requestId" to run.requestId, "reason" to reason.name) +
                        terminalExecutionMetadata(run, stageDurations)
                )
            }
        }
    }

    private suspend fun failOrPreserveUnknown(
        run: ActiveRun,
        local: ClinicalLocalReport?,
        reason: ClinicalReportFailureReason
    ) {
        val preservedUnknownGuard = run.preservedUnknownGuard
        if (preservedUnknownGuard == null) {
            fail(run, local, reason)
            return
        }
        prepared = prepared?.takeUnless { it.requestId == run.requestId }
        mutableState.value = preservedUnknownGuard
        safeAudit(
            AuditLevel.WARN,
            "clinical_report_unknown_local_refresh_failed",
            mapOf("reason" to reason.name)
        )
    }

    private suspend fun cancel(run: ActiveRun, local: ClinicalLocalReport?) {
        val stageDurations = run.stageTiming.finish(monotonicClock())
        withContext(NonCancellable) {
            val completedAt = clock()
            val entity = terminalEntity(
                run = run,
                local = local,
                status = ClinicalReportStatus.CANCELLED,
                completedAt = completedAt,
                reason = ClinicalReportFailureReason.CANCELLED
            )
            val persisted = mutex.withLock {
                if (active !== run) return@withLock false
                val changed = reportDao.updateIfStatusIn(
                    entity = entity,
                    expectedStatuses = ACTIVE_LIFECYCLE_STATUSES
                )
                if (changed != 1) return@withLock false
                run.terminalPersisted = true
                mutableState.value = ClinicalReportState.Cancelled(run.requestId, local)
                if (run.remoteBegan) {
                    lastRemoteTerminal = terminalRun(
                        run = run,
                        explicitRetryRequired = false
                    )
                }
                true
            }
            if (persisted) {
                terminalAudit(
                    AuditLevel.INFO,
                    "clinical_report_cancelled",
                    mapOf(
                        "requestId" to run.requestId,
                        "localSummaryAvailable" to (local != null)
                    ) + terminalExecutionMetadata(run, stageDurations)
                )
            }
        }
    }

    private fun terminalExecutionMetadata(
        run: ActiveRun,
        stageDurations: ClinicalStageDurations
    ): Map<String, Any?> {
        val execution = run.latestExecutionSnapshot
        val config = run.remoteExecution?.config
        return mapOf(
            "provider" to config?.providerId?.name.orEmpty(),
            "model" to config?.modelId.orEmpty(),
            "maxRequestBytes" to (execution?.maxRequestBytes ?: 0L),
            "maxResponseBytes" to (execution?.maxResponseBytes ?: 0L),
            "totalRequestBytes" to (execution?.totalRequestBytes ?: 0L),
            "totalResponseBytes" to (execution?.totalResponseBytes ?: 0L),
            "durationMs" to (execution?.durationMs ?: 0L),
            "sourceGlucoseRows" to (execution?.sourceRows?.glucose ?: 0),
            "sourceInsulinRows" to (execution?.sourceRows?.insulin ?: 0),
            "sourceCarbRows" to (execution?.sourceRows?.carbs ?: 0),
            "sourceTargetRows" to (execution?.sourceRows?.targets ?: 0),
            "preparingDurationMs" to stageDurations.preparingMs,
            "analyzingDurationMs" to stageDurations.analyzingMs,
            "reducingDurationMs" to stageDurations.reducingMs,
            "synthesizingDurationMs" to stageDurations.synthesizingMs,
            "validatingDurationMs" to stageDurations.validatingMs
        )
    }

    private fun terminalEntity(
        run: ActiveRun,
        local: ClinicalLocalReport?,
        status: ClinicalReportStatus,
        completedAt: Long,
        reason: ClinicalReportFailureReason
    ): ClinicalReportEntity {
        val payload = run.payload
        return if (payload != null && local != null) {
            localEntity(
                run = run,
                payload = payload,
                status = status,
                completedAt = completedAt,
                sanitizedError = reason.name
            )
        } else {
            ClinicalReportEntity(
                requestId = run.requestId,
                status = status.name,
                requestedFromTs = 0L,
                requestedThroughTs = 0L,
                requestHash = local?.requestHash.orEmpty(),
                coverageJson = EMPTY_JSON,
                localSummaryJson = EMPTY_JSON,
                responseJson = null,
                renderedText = null,
                model = run.remoteExecution?.config?.modelId,
                provider = run.remoteExecution?.config?.providerId?.name
                    ?: LOCAL_ONLY_PROVIDER,
                schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
                createdAt = run.createdAt,
                completedAt = completedAt,
                sanitizedError = reason.name
            )
        }
    }

    private fun localEntity(
        run: ActiveRun,
        payload: ClinicalReportPayload,
        status: ClinicalReportStatus,
        responseJson: String? = null,
        model: String? = null,
        schemaVersion: Int = payload.dataset.schemaVersion,
        completedAt: Long? = null,
        sanitizedError: String? = null,
        executionMetadata: ClinicalOpenAiMetadata? = null
    ): ClinicalReportEntity = ClinicalReportEntity(
        requestId = run.requestId,
        status = status.name,
        requestedFromTs = payload.dataset.summary30d.fromTs,
        requestedThroughTs = payload.dataset.summary30d.throughTs,
        requestHash = payload.sha256,
        coverageJson = ClinicalReportPersistenceSerializer.coverage(
            payload.dataset,
            executionMetadata
        ),
        localSummaryJson = ClinicalReportPersistenceSerializer.localSummary(payload.dataset),
        responseJson = responseJson,
        renderedText = null,
        model = model ?: run.remoteExecution?.config?.modelId,
        provider = run.remoteExecution?.config?.providerId?.name ?: LOCAL_ONLY_PROVIDER,
        schemaVersion = schemaVersion,
        createdAt = run.createdAt,
        completedAt = completedAt,
        sanitizedError = sanitizedError?.take(MAX_REASON_LENGTH)
    )

    private fun requireMatchingProviderMetadata(
        run: ActiveRun,
        metadata: ClinicalOpenAiMetadata
    ) {
        val config = checkNotNull(run.remoteExecution?.config)
        if (
            metadata.providerId != config.providerId ||
            metadata.requestedProviderId != config.providerId ||
            metadata.model != config.modelId ||
            metadata.requestedModel != config.modelId
        ) {
            throw ClinicalAiGatewayException.IdentityMismatch()
        }
    }

    private fun freshPrepared(nowTs: Long, zoneId: ZoneId): PreparedReport? =
        prepared?.takeIf { it.isFresh(nowTs, zoneId) }

    private fun PreparedReport.isFresh(nowTs: Long, requestedZone: ZoneId): Boolean =
        zoneId == requestedZone &&
            nowTs >= preparedForTs &&
            nowTs - preparedForTs <= PREPARED_DATASET_TTL_MS &&
            payload.sha256 == local.requestHash

    private fun isCoolingDown(terminal: TerminalRun): Boolean {
        val now = monotonicClock()
        if (now < terminal.completedMonotonicAt) return true
        return now - terminal.completedMonotonicAt < REMOTE_COOLDOWN_MS
    }

    private fun retentionCutoff(): Long = clock() - RETENTION_MS

    private suspend fun deleteExpiredReports() {
        reportDao.redactUnknownOutcomeGuardsOlderThan(
            olderThan = retentionCutoff(),
            protectedStatuses = UNACKNOWLEDGED_UNKNOWN_STATUSES,
            protectedSanitizedError =
                ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
        )
        reportDao.deleteOlderThan(
            olderThan = retentionCutoff(),
            protectedStatuses = UNACKNOWLEDGED_UNKNOWN_STATUSES,
            protectedSanitizedError =
                ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
        )
    }

    private fun generatedRequestId(): String {
        val requestId = try {
            requestIdGenerator.nextId()
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            throw IllegalStateException("Clinical report request ID generation failed")
        }
        if (
            requestId.isBlank() ||
            requestId != requestId.trim() ||
            requestId.length > MAX_REQUEST_ID_LENGTH
        ) {
            throw IllegalStateException("Clinical report request ID is invalid")
        }
        return requestId
    }

    private suspend fun requireLifecycleTransition(
        entity: ClinicalReportEntity,
        expectedStatuses: List<String>
    ) {
        check(reportDao.updateIfStatusIn(entity, expectedStatuses) == 1) {
            "Clinical report lifecycle persistence conflict for ${entity.requestId}"
        }
    }

    private fun terminalRun(
        run: ActiveRun,
        explicitRetryRequired: Boolean
    ) = TerminalRun(
        requestId = run.requestId,
        job = run.job,
        completedMonotonicAt = monotonicClock(),
        explicitRetryRequired = explicitRetryRequired
    )

    private fun ActiveRun.canPublish(): Boolean =
        active === this && !cancelRequested

    private fun ActiveRun.currentDisposition(): ClinicalReportRunDisposition =
        when {
            terminalPersisted && explicitRetryRequired ->
                ClinicalReportRunDisposition.RETRY_REQUIRED
            terminalPersisted && remote -> ClinicalReportRunDisposition.COOLDOWN
            remote -> ClinicalReportRunDisposition.REMOTE_IN_FLIGHT
            else -> ClinicalReportRunDisposition.LOCAL_IN_FLIGHT
        }

    private suspend fun safeAudit(
        level: AuditLevel,
        message: String,
        metadata: Map<String, Any?>
    ) {
        try {
            when (level) {
                AuditLevel.INFO -> auditLogger.info(message, metadata)
                AuditLevel.WARN -> auditLogger.warn(message, metadata)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            // Report state and persistence must not depend on best-effort audit writes.
        }
    }

    private suspend fun terminalAudit(
        level: AuditLevel,
        message: String,
        metadata: Map<String, Any?>
    ) {
        withContext(NonCancellable) {
            try {
                when (level) {
                    AuditLevel.INFO -> auditLogger.info(message, metadata)
                    AuditLevel.WARN -> auditLogger.warn(message, metadata)
                }
            } catch (_: Exception) {
                // Terminal persistence is authoritative; audit is observational only.
            }
        }
    }

    private fun ClinicalReportPayload.toLocalReport() = ClinicalLocalReport(
        summary24h = dataset.summary24h,
        summary7d = dataset.summary7d,
        summary30d = dataset.summary30d,
        requestHash = sha256,
        generatedAt = dataset.generatedAt,
        zoneId = dataset.zoneId,
        energyProfile = dataset.energyProfile,
        plannedActivities = dataset.plannedActivities,
        forecastQuality = dataset.detail24h.forecastQuality,
        eventSummaries = dataset.eventSummaries,
        eventTypeAssociations = dataset.eventTypeAssociations,
        remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
    )

    private fun ClinicalOpenAiException.toReason(
        remoteBegan: Boolean
    ): ClinicalReportFailureReason =
        if (this is ClinicalOpenAiException.ChunkFailed) {
            if (
                chunkFailure == ClinicalOpenAiFailureKind.NETWORK ||
                chunkFailure == ClinicalOpenAiFailureKind.TIMEOUT
            ) {
                ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
            } else {
                ClinicalReportFailureReason.PARTIAL_CHUNK
            }
        } else if (
            remoteBegan &&
            (kind == ClinicalOpenAiFailureKind.NETWORK ||
                kind == ClinicalOpenAiFailureKind.TIMEOUT)
        ) {
            ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
        } else {
            when (kind) {
                ClinicalOpenAiFailureKind.UNAUTHORIZED -> ClinicalReportFailureReason.UNAUTHORIZED
                ClinicalOpenAiFailureKind.RATE_LIMITED -> ClinicalReportFailureReason.RATE_LIMITED
                ClinicalOpenAiFailureKind.SERVER -> ClinicalReportFailureReason.SERVER
                ClinicalOpenAiFailureKind.HTTP -> ClinicalReportFailureReason.HTTP
                ClinicalOpenAiFailureKind.TIMEOUT -> ClinicalReportFailureReason.TIMEOUT
                ClinicalOpenAiFailureKind.NETWORK -> ClinicalReportFailureReason.NETWORK
                ClinicalOpenAiFailureKind.REFUSAL -> ClinicalReportFailureReason.REFUSAL
                ClinicalOpenAiFailureKind.INCOMPLETE -> ClinicalReportFailureReason.INCOMPLETE
                ClinicalOpenAiFailureKind.INVALID_RESPONSE ->
                    ClinicalReportFailureReason.INVALID_RESPONSE
                ClinicalOpenAiFailureKind.OVERSIZED_RESPONSE ->
                    ClinicalReportFailureReason.OVERSIZED_RESPONSE
                ClinicalOpenAiFailureKind.REQUEST_TOO_LARGE ->
                    ClinicalReportFailureReason.REQUEST_TOO_LARGE
                ClinicalOpenAiFailureKind.DATASET_TOO_LARGE ->
                    ClinicalReportFailureReason.DATASET_TOO_LARGE
                ClinicalOpenAiFailureKind.CREDENTIAL ->
                    ClinicalReportFailureReason.CREDENTIAL_UNAVAILABLE
                ClinicalOpenAiFailureKind.INPUT -> ClinicalReportFailureReason.INVALID_INPUT
            }
        }

    private fun ClinicalReportState.isTerminal(): Boolean =
        this is ClinicalReportState.Complete ||
            this is ClinicalReportState.Failed ||
            this is ClinicalReportState.Cancelled

    private fun guardedUnknownState(): ClinicalReportState.Failed? {
        val terminal = lastRemoteTerminal ?: return null
        if (!terminal.explicitRetryRequired) return null
        return (mutableState.value as? ClinicalReportState.Failed)?.takeIf {
            it.requestId == terminal.requestId &&
                it.reason == ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
        }
    }

    private class ActiveRun(
        val requestId: String,
        val createdAt: Long,
        val remote: Boolean,
        val preservedUnknownGuard: ClinicalReportState.Failed?,
        val remoteExecution: RemoteExecution?
    ) {
        lateinit var job: Job

        @Volatile
        var cancelRequested: Boolean = false

        @Volatile
        var remoteBegan: Boolean = false

        @Volatile
        var payload: ClinicalReportPayload? = null

        @Volatile
        var terminalPersisted: Boolean = false

        @Volatile
        var explicitRetryRequired: Boolean = false

        @Volatile
        var latestExecutionSnapshot: ClinicalOpenAiExecutionSnapshot? = null

        val stageTiming = ClinicalStageTiming()
    }

    private sealed interface RemoteExecution {
        val config: ClinicalAiProviderConfig?

        data class Ready(
            override val config: ClinicalAiProviderConfig,
            val gateway: ClinicalAiGateway
        ) : RemoteExecution

        data class Rejected(
            override val config: ClinicalAiProviderConfig?,
            val reason: ClinicalReportFailureReason
        ) : RemoteExecution
    }

    private data class ClinicalStageDurations(
        val preparingMs: Long,
        val analyzingMs: Long,
        val reducingMs: Long,
        val synthesizingMs: Long,
        val validatingMs: Long
    )

    private class ClinicalStageTiming {
        private val durationsMs = LongArray(TRACKED_STAGE_COUNT)
        private var activeStage: ClinicalOpenAiProgressStage? = null
        private var activeSinceMs = 0L
        private var totalDurationMs = 0L
        private var finished = false

        @Synchronized
        fun transition(stage: ClinicalOpenAiProgressStage, nowMs: Long) {
            if (finished) return
            if (stage == ClinicalOpenAiProgressStage.COMPLETED) {
                closeActive(nowMs)
                finished = true
                return
            }
            if (!stage.isTimedStage()) return
            if (activeStage == stage) {
                if (nowMs < activeSinceMs) {
                    activeSinceMs = nowMs
                }
                return
            }
            closeActive(nowMs)
            activeStage = stage
            activeSinceMs = nowMs
        }

        @Synchronized
        fun finish(nowMs: Long): ClinicalStageDurations {
            if (!finished) {
                closeActive(nowMs)
                finished = true
            }
            return snapshot()
        }

        private fun closeActive(nowMs: Long) {
            val stage = activeStage ?: return
            val elapsedMs = boundedElapsed(activeSinceMs, nowMs)
            val remainingMs = MAX_STAGE_TIMING_TOTAL_MS - totalDurationMs
            val creditedMs = minOf(elapsedMs, remainingMs.coerceAtLeast(0L))
            val index = stage.timingIndex()
            durationsMs[index] += creditedMs
            totalDurationMs += creditedMs
            activeStage = null
            activeSinceMs = nowMs
        }

        private fun snapshot() = ClinicalStageDurations(
            preparingMs = durationsMs[PREPARING_INDEX],
            analyzingMs = durationsMs[ANALYZING_INDEX],
            reducingMs = durationsMs[REDUCING_INDEX],
            synthesizingMs = durationsMs[SYNTHESIZING_INDEX],
            validatingMs = durationsMs[VALIDATING_INDEX]
        )

        private fun boundedElapsed(startMs: Long, nowMs: Long): Long {
            if (nowMs < startMs) return 0L
            val elapsed = try {
                Math.subtractExact(nowMs, startMs)
            } catch (_: ArithmeticException) {
                MAX_STAGE_TIMING_TOTAL_MS
            }
            return elapsed.coerceIn(0L, MAX_STAGE_TIMING_TOTAL_MS)
        }

        private fun ClinicalOpenAiProgressStage.isTimedStage(): Boolean =
            this != ClinicalOpenAiProgressStage.COMPLETED

        private fun ClinicalOpenAiProgressStage.timingIndex(): Int = when (this) {
            ClinicalOpenAiProgressStage.PREPARING -> PREPARING_INDEX
            ClinicalOpenAiProgressStage.ANALYZING_CHUNK -> ANALYZING_INDEX
            ClinicalOpenAiProgressStage.REDUCING -> REDUCING_INDEX
            ClinicalOpenAiProgressStage.SYNTHESIZING -> SYNTHESIZING_INDEX
            ClinicalOpenAiProgressStage.VALIDATING -> VALIDATING_INDEX
            ClinicalOpenAiProgressStage.COMPLETED ->
                error("Completed progress has no duration bucket")
        }

        private companion object {
            const val PREPARING_INDEX = 0
            const val ANALYZING_INDEX = 1
            const val REDUCING_INDEX = 2
            const val SYNTHESIZING_INDEX = 3
            const val VALIDATING_INDEX = 4
            const val TRACKED_STAGE_COUNT = 5

            // Audit timing is diagnostic only; one run can contribute at most 24 hours.
            const val MAX_STAGE_TIMING_TOTAL_MS = 24L * 60L * 60L * 1_000L
        }
    }

    private data class PreparedReport(
        val requestId: String,
        val payload: ClinicalReportPayload,
        val local: ClinicalLocalReport,
        val preparedForTs: Long,
        val zoneId: ZoneId,
        val createdAt: Long,
        val job: Job
    )

    private data class TerminalRun(
        val requestId: String,
        val job: Job,
        val completedMonotonicAt: Long,
        val explicitRetryRequired: Boolean
    )

    private data class RunReservation(
        val requestId: String,
        val createdAt: Long
    )

    private data class RecoveredRun(
        val entity: ClinicalReportEntity,
        val reason: ClinicalReportFailureReason
    )

    private class PendingRemoteStart(
        val expectedConfigIdentity: String?
    ) {
        val result = CompletableDeferred<ClinicalReportRun>()
    }

    private sealed interface RemoteStartClaim {
        data class Owner(val pending: PendingRemoteStart) : RemoteStartClaim
        data class Await(val result: Deferred<ClinicalReportRun>) : RemoteStartClaim
        data class Decided(val decision: StartDecision) : RemoteStartClaim
    }

    private sealed interface StartDecision {
        data class WaitForLocal(val job: Job) : StartDecision
        data class Publish(val run: ClinicalReportRun) : StartDecision
    }

    private enum class AuditLevel {
        INFO,
        WARN
    }

    companion object {
        internal fun forTest(
            datasetFactory: ClinicalReportDatasetFactory,
            configSource: ClinicalAiConfigSource,
            gatewayFactory: ClinicalAiGatewayFactory,
            credentialSource: ClinicalAiCredentialSource,
            reportDao: ClinicalReportDao,
            auditLogger: AuditLogger,
            clock: () -> Long,
            monotonicClock: () -> Long,
            dispatcher: CoroutineDispatcher,
            requestIdGenerator: RequestIdGenerator,
            preparedLocalTransformForTest: ClinicalPdfPreparedLocalTransformForTest? = null
        ): ClinicalReportRepository = ClinicalReportRepository(
            datasetFactory = datasetFactory,
            configSource = configSource,
            gatewayFactory = gatewayFactory,
            credentialSource = credentialSource,
            reportDao = reportDao,
            auditLogger = auditLogger,
            clock = clock,
            monotonicClock = monotonicClock,
            dispatcher = dispatcher,
            requestIdGenerator = requestIdGenerator,
            preparedLocalTransformForTest = preparedLocalTransformForTest
        )

        internal fun forTest(
            datasetFactory: ClinicalReportDatasetFactory,
            credentialProvider: OpenAiCredentialProvider,
            client: ClinicalOpenAiClient,
            reportDao: ClinicalReportDao,
            auditLogger: AuditLogger,
            clock: () -> Long,
            monotonicClock: () -> Long,
            dispatcher: CoroutineDispatcher,
            requestIdGenerator: RequestIdGenerator,
            preparedLocalTransformForTest: ClinicalPdfPreparedLocalTransformForTest? = null
        ): ClinicalReportRepository = ClinicalReportRepository(
            datasetFactory = datasetFactory,
            configSource = fixedConfigSource(client),
            gatewayFactory = fixedGatewayFactory(client),
            credentialSource = fixedCredentialSource(credentialProvider),
            reportDao = reportDao,
            auditLogger = auditLogger,
            clock = clock,
            monotonicClock = monotonicClock,
            dispatcher = dispatcher,
            requestIdGenerator = requestIdGenerator,
            preparedLocalTransformForTest = preparedLocalTransformForTest
        )

        private fun fixedConfigSource(client: ClinicalOpenAiClient) =
            ClinicalAiConfigSource {
                ClinicalAiConfigState.Valid(
                    ClinicalAiProviderConfig(
                        providerId = client.providerId,
                        modelId = client.modelId
                    )
                )
            }

        private fun fixedGatewayFactory(client: ClinicalOpenAiClient) =
            ClinicalAiGatewayFactory(
                mapOf(
                    client.providerId to ClinicalAiGatewayBuilder { client }
                )
            )

        private fun fixedCredentialSource(credentialProvider: OpenAiCredentialProvider) =
            ClinicalAiCredentialSource {
                credentialProvider.requireCredential()
            }

        const val PREPARED_DATASET_TTL_MS = 2L * 60L * 1_000L
        const val REMOTE_COOLDOWN_MS = 30L * 1_000L
        const val RETENTION_MS = 90L * 24L * 60L * 60L * 1_000L
        internal const val REQUEST_ID_RESERVATION_ATTEMPTS = 8
        private const val RECOVERY_LIMIT = 128
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val MAX_REASON_LENGTH = 64
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val EMPTY_JSON = "{}"
        private val LOCAL_ONLY_PROVIDER = ClinicalAiProviderId.OPENAI.name
        private val ACTIVE_RECOVERY_STATUSES = listOf(
            ClinicalReportStatus.BUILDING.name,
            ClinicalReportStatus.UPLOADING.name
        )
        private val UNACKNOWLEDGED_UNKNOWN_STATUSES = listOf(
            ClinicalReportStatus.FAILED.name,
            ClinicalReportStatus.INTERRUPTED.name
        )
        private val ACTIVE_LIFECYCLE_STATUSES = listOf(
            ClinicalReportStatus.BUILDING.name,
            ClinicalReportStatus.LOCAL_READY.name,
            ClinicalReportStatus.UPLOADING.name
        )
    }
}

private object ClinicalReportPersistenceSerializer {
    private val gson = Gson()

    fun coverage(
        dataset: ClinicalReportDataset,
        metadata: ClinicalOpenAiMetadata? = null
    ): String = JsonObject().apply {
        add("summary7d", quality(dataset.summary7d))
        add("summary30d", quality(dataset.summary30d))
        if (metadata != null) {
            if (metadata.coverageLedgerHash == null) {
                add("ledgerHash", JsonNull.INSTANCE)
            } else {
                addProperty("ledgerHash", metadata.coverageLedgerHash)
            }
            addProperty("leafCount", metadata.chunkCount)
            addProperty("reductionLevels", metadata.reductionLevels)
            add("sourceRows", JsonObject().apply {
                addProperty("glucose", metadata.sourceRows.glucose)
                addProperty("insulin", metadata.sourceRows.insulin)
                addProperty("carbs", metadata.sourceRows.carbs)
                addProperty("targets", metadata.sourceRows.targets)
            })
        }
    }.toString()

    fun localSummary(dataset: ClinicalReportDataset): String = JsonObject().apply {
        addProperty("generatedAt", dataset.generatedAt)
        addProperty("zoneId", dataset.zoneId)
        dataset.summary24h?.let { add("summary24h", summary(it)) }
        add("summary7d", summary(dataset.summary7d))
        add("summary30d", summary(dataset.summary30d))
        dataset.energyProfile?.let { add("energyProfile", gson.toJsonTree(it)) }
        add("plannedActivities", gson.toJsonTree(dataset.plannedActivities))
        add("forecastQuality", gson.toJsonTree(dataset.detail24h.forecastQuality))
        add("eventSummaries", gson.toJsonTree(dataset.eventSummaries))
        add("eventTypeAssociations", gson.toJsonTree(dataset.eventTypeAssociations))
        addProperty(
            "remoteEventPreviewJson",
            ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        )
    }.toString()

    fun local(entity: ClinicalReportEntity): ClinicalLocalReport? {
        if (entity.requestHash.isBlank() || entity.localSummaryJson == "{}") return null
        return runCatching {
            val root = gson.fromJson(entity.localSummaryJson, JsonObject::class.java)
            val summary24h = root.getAsJsonObject("summary24h")?.let { source ->
                checkNotNull(
                    gson.fromJson(
                        normalizedSummary(source),
                        ClinicalPeriodSummary::class.java
                    )
                )
            }
            val summary7d = checkNotNull(
                gson.fromJson(
                    normalizedSummary(root.getAsJsonObject("summary7d")),
                    ClinicalPeriodSummary::class.java
                )
            )
            val summary30d = checkNotNull(
                gson.fromJson(
                    normalizedSummary(root.getAsJsonObject("summary30d")),
                    ClinicalPeriodSummary::class.java
                )
            )
            ClinicalLocalReport(
                summary24h = summary24h,
                summary7d = summary7d,
                summary30d = summary30d,
                requestHash = entity.requestHash,
                generatedAt = root.get("generatedAt")?.asLong ?: entity.requestedThroughTs,
                zoneId = root.get("zoneId")?.asString ?: ZoneId.systemDefault().id,
                energyProfile = root.getAsJsonObject("energyProfile")?.let { source ->
                    gson.fromJson(source, ClinicalEnergyProfileSummary::class.java)
                },
                plannedActivities = root.getAsJsonArray("plannedActivities")?.let { source ->
                    gson.fromJson(
                        source,
                        Array<ClinicalPlannedActivitySummary>::class.java
                    ).toList()
                }.orEmpty(),
                forecastQuality = root.getAsJsonArray("forecastQuality")?.let { source ->
                    gson.fromJson(source, Array<ClinicalForecastQuality>::class.java).toList()
                }.orEmpty(),
                eventSummaries = root.getAsJsonArray("eventSummaries")?.let { source ->
                    gson.fromJson(source, Array<ClinicalEventSummary>::class.java).toList()
                }.orEmpty(),
                eventTypeAssociations = root.getAsJsonArray("eventTypeAssociations")?.let { source ->
                    gson.fromJson(source, Array<ClinicalEventTypeAssociation>::class.java).toList()
                }.orEmpty(),
                remoteEventPreviewJson = root.get("remoteEventPreviewJson")?.asString
                    ?: EMPTY_REMOTE_EVENT_PREVIEW_JSON
            )
        }.getOrNull()
    }

    private fun normalizedSummary(source: JsonObject): JsonObject =
        source.deepCopy().apply {
            if (!has("confirmedInsulinU")) {
                add("confirmedInsulinU", get("totalInsulinU") ?: JsonPrimitive(0.0))
            }
            if (!has("estimatedInsulinU")) addProperty("estimatedInsulinU", 0.0)
            if (!has("insulinTotalSource")) {
                addProperty("insulinTotalSource", ClinicalInsulinTotalSource.COPILOT_EVENTS.name)
            }
            if (!has("enteredCarbsG")) {
                add("enteredCarbsG", get("totalCarbsG") ?: JsonPrimitive(0.0))
            }
            if (!has("uamCarbsG")) addProperty("uamCarbsG", 0.0)
            if (!has("insulinEventCount")) addProperty("insulinEventCount", 0)
            if (!has("estimatedInsulinEventCount")) {
                addProperty("estimatedInsulinEventCount", 0)
            }
            if (!has("enteredCarbEventCount")) addProperty("enteredCarbEventCount", 0)
            if (!has("uamCarbEventCount")) addProperty("uamCarbEventCount", 0)
            if (!has("mealEnergy") || get("mealEnergy").isJsonNull) {
                add("mealEnergy", JsonObject().apply {
                    addProperty("carbohydrateEnergyKcal", get("totalCarbsG")?.asDouble?.times(4.0) ?: 0.0)
                    add("estimatedTotalMealEnergyKcal", JsonNull.INSTANCE)
                    addProperty("source", ClinicalMealEnergySource.NOT_AVAILABLE.name)
                    add("netEnergyKcal", JsonNull.INSTANCE)
                })
            }
            if (!has("activity") || get("activity").isJsonNull) {
                add("activity", JsonObject())
            }
            if (!has("basalContext") || get("basalContext").isJsonNull) {
                add("basalContext", JsonObject())
            }
            if (!has("therapyContext") || get("therapyContext").isJsonNull) {
                add("therapyContext", JsonObject())
            }
            if (!has("probableMealWindows") || get("probableMealWindows").isJsonNull) {
                add("probableMealWindows", JsonArray())
            }
            if (!has("recentProbableMealWindows") || get("recentProbableMealWindows").isJsonNull) {
                add("recentProbableMealWindows", JsonArray())
            }
        }

    fun response(report: ClinicalAdvisoryReport): String = JsonObject().apply {
        addProperty("summary7dStatus", report.summary7dStatus.name)
        addProperty("summary30dStatus", report.summary30dStatus.name)
        add("dataQuality", enumArray(report.dataQuality))
        add("patterns", JsonArray().apply {
            report.patterns.forEach { finding ->
                add(JsonObject().apply {
                    addProperty("topic", finding.topic.name)
                    addProperty("period", finding.period.name)
                    addProperty("direction", finding.direction.name)
                    addProperty("confidence", finding.confidence.name)
                    addProperty("timeBand", finding.timeBand.name)
                    addProperty("evidenceMetric", finding.evidenceMetric.name)
                    addProperty("evidenceValue", finding.evidenceValue)
                })
            }
        })
        add("safetyObservations", enumArray(report.safetyObservations))
        add("recommendations", JsonArray().apply {
            report.recommendations.forEach { recommendation ->
                add(JsonObject().apply {
                    addProperty(
                        "careTeamDiscussionTopic",
                        recommendation.careTeamDiscussionTopic.name
                    )
                    addProperty("priority", recommendation.priority.name)
                    add("evidenceFindingIndices", JsonArray().apply {
                        recommendation.evidenceFindingIndices.forEach(::add)
                    })
                    addProperty("period", recommendation.period.name)
                })
            }
        })
        add("careTeamQuestions", enumArray(report.careTeamQuestions))
    }.toString()

    private fun summary(value: ClinicalPeriodSummary): JsonObject = JsonObject().apply {
        addProperty("days", value.days)
        addProperty("fromTs", value.fromTs)
        addProperty("throughTs", value.throughTs)
        addProperty("coveragePct", value.coveragePct)
        addNullable("meanMmol", value.meanMmol)
        addNullable("medianMmol", value.medianMmol)
        addNullable("coefficientOfVariationPct", value.coefficientOfVariationPct)
        addNullable("timeBelow4Pct", value.timeBelow4Pct)
        addNullable("timeInRangePct", value.timeInRangePct)
        addNullable("timeAboveRangePct", value.timeAboveRangePct)
        addProperty("totalInsulinU", value.totalInsulinU)
        addProperty("confirmedInsulinU", value.confirmedInsulinU)
        addProperty("estimatedInsulinU", value.estimatedInsulinU)
        addNullable("deliveredBasalInsulinU", value.deliveredBasalInsulinU)
        addNullable("deliveredBolusInsulinU", value.deliveredBolusInsulinU)
        addProperty("insulinTotalSource", value.insulinTotalSource.name)
        addProperty("insulinEventCount", value.insulinEventCount)
        addProperty("estimatedInsulinEventCount", value.estimatedInsulinEventCount)
        addProperty("totalCarbsG", value.totalCarbsG)
        addProperty("enteredCarbsG", value.enteredCarbsG)
        addProperty("uamCarbsG", value.uamCarbsG)
        addNullable("aapsCarbsG", value.aapsCarbsG)
        addProperty("enteredCarbEventCount", value.enteredCarbEventCount)
        addProperty("uamCarbEventCount", value.uamCarbEventCount)
        add("mealEnergy", JsonObject().apply {
            addProperty("carbohydrateEnergyKcal", value.mealEnergy.carbohydrateEnergyKcal)
            add("estimatedTotalMealEnergyKcal", value.mealEnergy.estimatedTotalMealEnergyKcal?.let {
                JsonObject().apply {
                    addProperty("minimum", it.minimum)
                    addProperty("maximum", it.maximum)
                }
            } ?: JsonNull.INSTANCE)
            addProperty("source", value.mealEnergy.source.name)
            add("netEnergyKcal", value.mealEnergy.netEnergyKcal?.let {
                JsonObject().apply {
                    addProperty("minimum", it.minimum)
                    addProperty("maximum", it.maximum)
                }
            } ?: JsonNull.INSTANCE)
        })
        add("activity", JsonObject().apply {
            addProperty("coveragePct", value.activity.coveragePct)
            addNullable("steps", value.activity.steps)
            addNullable("distanceKm", value.activity.distanceKm)
            addNullable("activeMinutes", value.activity.activeMinutes)
            addNullable("activeCaloriesKcal", value.activity.activeCaloriesKcal)
            addNullable("meanActivityRatio", value.activity.meanActivityRatio)
            addNullable("maxActivityRatio", value.activity.maxActivityRatio)
        })
        add("basalContext", JsonObject().apply {
            addProperty("coveragePct", value.basalContext.coveragePct)
            addNullable("meanProfileRateUph", value.basalContext.meanProfileRateUph)
            addNullable("minProfileRateUph", value.basalContext.minProfileRateUph)
            addNullable("maxProfileRateUph", value.basalContext.maxProfileRateUph)
            addNullable("meanProfilePercent", value.basalContext.meanProfilePercent)
        })
        add("therapyContext", JsonObject().apply {
            addProperty("infusionSetChanges", value.therapyContext.infusionSetChanges)
            addProperty("sensorChanges", value.therapyContext.sensorChanges)
            addProperty("insulinRefills", value.therapyContext.insulinRefills)
            addProperty("pumpBatteryChanges", value.therapyContext.pumpBatteryChanges)
            addProperty("exerciseEvents", value.therapyContext.exerciseEvents)
            addProperty("profileSwitches", value.therapyContext.profileSwitches)
        })
        add("probableMealWindows", gson.toJsonTree(value.probableMealWindows))
        add("recentProbableMealWindows", gson.toJsonTree(value.recentProbableMealWindows))
        addNullable("meanTargetMmol", value.meanTargetMmol)
        add("weekdayPattern", hourly(value.weekdayPattern))
        add("weekendPattern", hourly(value.weekendPattern))
        add("quality", quality(value))
    }

    private fun quality(summary: ClinicalPeriodSummary): JsonObject = JsonObject().apply {
        addProperty("coveragePct", summary.coveragePct)
        addProperty("expectedBuckets", summary.quality.expectedBuckets)
        addProperty("coveredBuckets", summary.quality.coveredBuckets)
        addProperty("missingBuckets", summary.quality.missingBuckets)
        addNullable("maxGapMinutes", summary.quality.maxGapMinutes)
        add("rejected", JsonObject().apply {
            addProperty("glucose", summary.quality.rejected.glucose)
            addProperty("therapy", summary.quality.rejected.therapy)
            addProperty("target", summary.quality.rejected.target)
            addProperty("forecast", summary.quality.rejected.forecast)
            addProperty("telemetry", summary.quality.rejected.telemetry)
        })
    }

    private fun hourly(values: List<ClinicalHourlyMetric>): JsonArray = JsonArray().apply {
        values.forEach { metric ->
            add(JsonObject().apply {
                addProperty("hour", metric.hour)
                addProperty("sampleCount", metric.sampleCount)
                addNullable("meanMmol", metric.meanMmol)
                addNullable("medianMmol", metric.medianMmol)
            })
        }
    }

    private fun enumArray(values: List<Enum<*>>): JsonArray = JsonArray().apply {
        values.forEach { add(it.name) }
    }

    private fun JsonObject.addNullable(name: String, value: Number?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }
}
