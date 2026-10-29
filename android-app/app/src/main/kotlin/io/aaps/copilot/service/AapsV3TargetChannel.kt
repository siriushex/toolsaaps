package io.aaps.copilot.service

import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.NativeTargetOutcome
import io.aaps.copilot.data.repository.NativeTargetTransport
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.target.EatingSoonPolicy
import io.aaps.copilot.util.UnitConverter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class AapsV3ConnectionStatus { UNPAIRED, WAITING, VERIFYING, CONNECTED, CONTROL_DISABLED, REVOKED, OFFLINE, ERROR }
data class AapsV3ConnectionState(
    val status: AapsV3ConnectionStatus = AapsV3ConnectionStatus.UNPAIRED,
    val busy: Boolean = false,
    val observedProfile: AapsV3ObservedProfileStatus = AapsV3ObservedProfileStatus.UNCHECKED,
    val observedInsulin: AapsV3ObservedInsulinStatus = AapsV3ObservedInsulinStatus.UNCHECKED,
    val insulinRecordCount: Int = 0,
    val observedProfileHistory: AapsV3ObservedProfileStatus = AapsV3ObservedProfileStatus.UNCHECKED,
    val profileHistoryIntervalCount: Int = 0,
    val observedBasal: AapsV3ReportedBasalStatus = AapsV3ReportedBasalStatus.UNCHECKED,
    val basalSegmentCount: Int = 0,
    val basalGapCount: Int = 0,
    val comboBolusStartUnverifiedCount: Int = 0,
    val insulinMissingUtcOffsetCount: Int = 0,
    val retainedComboHistory: AapsV3ComboHistoryStatus = AapsV3ComboHistoryStatus.UNCHECKED,
    val retainedComboHazardKindCount: Int = 0,
    val comboTransfer: ComboTransferState = ComboTransferState()
)

/** Foreground-runtime owner. Observation never commits a preview or replays a command. */
internal class AapsV3TargetChannel(
    private val db: CopilotDatabase,
    private val journal: AapsV3TargetJournal,
    private val storageAvailable: () -> Boolean,
    private val baselineGuard: suspend () -> String?,
    private val publicationLease: suspend (suspend () -> AapsV3TargetJournalSnapshot) -> AapsV3TargetJournalSnapshot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyStore: (() -> AapsV3ComboJournalStore)? = null
) : NativeTargetTransport {
    private val store = LocalNightscoutV3Store(db)
    private val profileSource = AapsV3EffectiveProfileSource(store)
    private val refreshMutex = Mutex()
    private val commandMutex = Mutex()
    private val enrollmentMutex = Mutex()
    private val generation = AtomicLong(0)
    private val mutableState = MutableStateFlow(AapsV3ConnectionState())
    val state: StateFlow<AapsV3ConnectionState> = mutableState.asStateFlow()
    @Volatile private var runtimeAllowed = false
    @Volatile private var sessionStartedAt = 0L
    @Volatile private var sessionProbeCounter = 0L
    private var runtimeScope: CoroutineScope? = null
    private var ownerJob: Job? = null
    private val historyGeneration = AtomicLong(0)
    private val historyPermit = Mutex()
    private var historyJob: Job? = null
    private var historyAttemptAt: Long? = null
    private val mutableHistoryState = MutableStateFlow(ComboTransferState())
    internal val historyState: StateFlow<ComboTransferState> = mutableHistoryState.asStateFlow()

    @Synchronized fun start(scope: CoroutineScope) {
        runtimeScope = scope
        runtimeAllowed = true
        if (ownerJob?.isActive == true) return
        stopHistory()
        val epoch = generation.incrementAndGet()
        sessionStartedAt = clock()
        sessionProbeCounter = 0
        ownerJob = scope.launch(Dispatchers.IO) {
            try {
                if (!journal.hasNativeState()) {
                    synchronized(this@AapsV3TargetChannel) {
                        if (generation.get() == epoch) mutableState.value = AapsV3ConnectionState()
                    }
                    return@launch
                }
                merge(db.localNightscoutV3Dao().observeRevision().map { Unit }, flow {
                    while (currentCoroutineContext().isActive) { emit(Unit); delay(60_000) }
                }).conflate().collect { if (generation.get() == epoch) refresh() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { connectionFailed(epoch) }
        }
    }

    @Synchronized fun stop() {
        runtimeAllowed = false
        generation.incrementAndGet()
        sessionProbeCounter = 0
        ownerJob?.cancel(); ownerJob = null
        stopHistory()
        mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.OFFLINE)
    }

    suspend fun pair(pin: CharArray) = try { enrollmentMutex.withLock {
        mutableState.value = mutableState.value.copy(busy = true)
        val matches = mutableListOf<AapsV3PairingCredential>()
        try {
            check(runtimeAllowed && storageAvailable())
            journal.reservePairingAttempt(clock())
            val offers = db.localNightscoutV3Dao().pairingOffers()
            check(offers.size <= 8)
            for (row in offers) {
                currentCoroutineContext().ensureActive()
                if (row.source != "local-v3-owner") continue
                val offer = try {
                    val body = HistoryJson.parse(row.payloadJson.toByteArray(), 65_536)
                    check(body["schemaVersion"] == JsonPrimitive(1))
                    HistoryJson.obj(body.getValue("offer"))
                } catch (_: Exception) { continue }
                try {
                    val credential = AapsV3PairingProtocol.unwrap(offer, pin.copyOf(), clock())
                    if (row.identifier == "aaps_clientcontrol_offer_${credential.clientId}") matches += credential else credential.close()
                } catch (_: AapsV3PairingFailure) { }
            }
            currentCoroutineContext().ensureActive()
            check(matches.size == 1 && runtimeAllowed && storageAvailable())
            journal.enroll(matches.single(), clock())
            runtimeScope?.let(::start)
            refresh()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.ERROR) }
        finally { pin.fill('\u0000'); matches.forEach { it.close() }; mutableState.value = mutableState.value.copy(busy = false) }
    } } finally { pin.fill('\u0000') }

    suspend fun checkConnection() {
        if (runtimeAllowed) runtimeScope?.let(::start)
        val epoch = generation.get()
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { connectionFailed(epoch) }
    }

    @Synchronized private fun connectionFailed(epoch: Long) {
        if (generation.get() != epoch) return
        stopHistory()
        sessionProbeCounter = 0
        mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.ERROR)
    }

    suspend fun revoke() = try {
        stopHistory()
        journal.revokePairing()
        sessionProbeCounter = 0
        mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.REVOKED)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.ERROR) }

    override suspend fun isConfigured() = journal.hasNativeState()

    private suspend fun refresh() = refreshMutex.withLock {
        val epoch = generation.get()
        if (!journal.hasNativeState()) {
            synchronized(this) {
                if (generation.get() == epoch) {
                    stopHistory()
                    mutableState.value = AapsV3ConnectionState()
                }
            }
            return@withLock
        }
        var pair = checkNotNull(journal.pairingSnapshot())
        store.get(LocalV3Collection.SETTINGS, "aaps")?.let { journal.observeRoster(it) }
        val ackRow = store.get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_ack_${pair.clientId}")
        if (ackRow != null && !ackRow.deleted && ackRow.source == "local-v3-owner") {
            val ack = HistoryJson.parse(ackRow.payloadJson.toByteArray(), 65_536)["ack"] as? JsonObject
            if (ack != null) {
                journal.acknowledgeProbe(ack, clock())
                journal.withTargetProtocol { journal.acknowledgePending(it, ack, clock()) }
            }
        }
        journal.expire(clock())
        if (runtimeAllowed && storageAvailable() && generation.get() == epoch) {
            journal.probe(clock(), sessionStartedAt)?.let { publication ->
                val request = HistoryJson.obj(HistoryJson.parse(publication.document.payloadJson.toByteArray(), 65_536).getValue("envelope"))
                synchronized(this) {
                    if (runtimeAllowed && generation.get() == epoch) sessionProbeCounter = HistoryJson.long(request.getValue("counter"))
                }
            }
        }
        val observedAt = clock()
        val evidence = if (runtimeAllowed && storageAvailable())
            profileSource.readEvidence(maxOf(1, observedAt - 86_400_000L), observedAt, clock()) else null
        // Profile IO may suspend; derive connectivity from the pairing again after that read.
        pair = checkNotNull(journal.pairingSnapshot())
        val status = when {
            pair.phase == AapsV3PairingPhase.REVOKED -> AapsV3ConnectionStatus.REVOKED
            !runtimeAllowed || !storageAvailable() -> AapsV3ConnectionStatus.OFFLINE
            pair.phase == AapsV3PairingPhase.WAITING -> AapsV3ConnectionStatus.WAITING
            !pair.controlEnabled -> AapsV3ConnectionStatus.CONTROL_DISABLED
            sessionProbeCounter <= 0 || pair.pongCounter < sessionProbeCounter || clock() - pair.lastPongAt !in 0..90_000 -> AapsV3ConnectionStatus.VERIFYING
            else -> AapsV3ConnectionStatus.CONNECTED
        }
        val observedProfile = if (status in setOf(AapsV3ConnectionStatus.CONNECTED, AapsV3ConnectionStatus.CONTROL_DISABLED)) {
            evidence?.profile?.status ?: AapsV3ObservedProfileStatus.UNCHECKED
        } else AapsV3ObservedProfileStatus.UNCHECKED
        val showEvidence = status in setOf(AapsV3ConnectionStatus.CONNECTED, AapsV3ConnectionStatus.CONTROL_DISABLED)
        synchronized(this) {
            if (generation.get() != epoch) return@synchronized
            mutableState.value = AapsV3ConnectionState(status, mutableState.value.busy, observedProfile,
            if (showEvidence) evidence?.insulin?.status ?: AapsV3ObservedInsulinStatus.UNCHECKED else AapsV3ObservedInsulinStatus.UNCHECKED,
            if (showEvidence) evidence?.insulin?.records?.size ?: 0 else 0,
            if (showEvidence) evidence?.profileHistory?.status ?: AapsV3ObservedProfileStatus.UNCHECKED else AapsV3ObservedProfileStatus.UNCHECKED,
            if (showEvidence) evidence?.profileHistory?.intervals?.size ?: 0 else 0,
            if (showEvidence) evidence?.reportedBasal?.status ?: AapsV3ReportedBasalStatus.UNCHECKED else AapsV3ReportedBasalStatus.UNCHECKED,
            if (showEvidence) evidence?.reportedBasal?.segments?.size ?: 0 else 0,
            if (showEvidence) evidence?.reportedBasal?.gaps?.size ?: 0 else 0,
            if (showEvidence) evidence?.insulin?.records?.count {
                !it.invalidated && it.anchorKind == AapsV3InsulinAnchorKind.COMBO_BOLUS_START_UNVERIFIED
            } ?: 0 else 0,
            if (showEvidence) evidence?.insulin?.records?.count {
                !it.invalidated && it.recordedUtcOffsetMinutes == null
            } ?: 0 else 0,
            if (showEvidence) evidence?.comboHistory?.status ?: AapsV3ComboHistoryStatus.UNCHECKED else AapsV3ComboHistoryStatus.UNCHECKED,
            if (showEvidence) evidence?.comboHistory?.hazards?.size ?: 0 else 0,
            mutableHistoryState.value)
            if (status == AapsV3ConnectionStatus.CONNECTED) startHistory(epoch)
            else stopHistory(clearAudit = status != AapsV3ConnectionStatus.VERIFYING)
        }
    }

    @Synchronized private fun publishHistory(value: ComboTransferState) {
        mutableHistoryState.value = value
        mutableState.value = mutableState.value.copy(comboTransfer = value)
    }

    @Synchronized private fun stopHistory(clearAudit: Boolean = true) {
        historyGeneration.incrementAndGet()
        historyJob?.cancel(); historyJob = null
        publishHistory(ComboTransferState(lastAudit = if (clearAudit) null else mutableHistoryState.value.lastAudit))
    }

    @Synchronized private fun startHistory(epoch: Long) {
        val factory = historyStore ?: return
        val scope = runtimeScope ?: return
        if (!runtimeAllowed || generation.get() != epoch || historyJob?.isActive == true || commandMutex.isLocked) return
        val now = clock()
        historyAttemptAt?.let { if (now - it < AapsV3ComboTransfer.INTERVAL_MS) return }
        if (!historyPermit.tryLock()) return
        historyAttemptAt = now
        val readEpoch = historyGeneration.incrementAndGet()
        val session = sessionStartedAt
        val probe = sessionProbeCounter
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            suspend fun admit() {
                currentCoroutineContext().ensureActive()
                check(runtimeAllowed && generation.get() == epoch && historyGeneration.get() == readEpoch &&
                    sessionStartedAt == session && sessionProbeCounter >= probe && !commandMutex.isLocked && storageAvailable())
            }
            try {
                admit()
                synchronized(this@AapsV3TargetChannel) {
                    if (generation.get() == epoch && historyGeneration.get() == readEpoch)
                        publishHistory(ComboTransferState(ComboTransferPhase.READING,
                            lastAudit = mutableHistoryState.value.lastAudit))
                }
                val result = AapsV3ComboTransfer(db, journal, factory(), clock).run(session, probe, ::admit)
                admit()
                synchronized(this@AapsV3TargetChannel) {
                    if (generation.get() == epoch && historyGeneration.get() == readEpoch) publishHistory(result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(this@AapsV3TargetChannel) {
                    if (generation.get() == epoch && historyGeneration.get() == readEpoch)
                        publishHistory(ComboTransferState(ComboTransferPhase.UNAVAILABLE,
                            lastAudit = mutableHistoryState.value.lastAudit))
                }
            }
        }
        historyJob = job
        // Cancellation can precede execution, or non-suspending storage may return late.
        job.invokeOnCompletion { historyPermit.unlock() }
        job.start()
    }

    override suspend fun send(command: ActionCommand, deliveryGuard: suspend () -> String?): NativeTargetOutcome = commandMutex.withLock {
        stopHistory(clearAudit = false)
        val epoch = generation.get()
        try {
            check(command.type == "temp_target")
            val mmol = checkNotNull(command.params["targetMmol"]?.toDoubleOrNull())
            val duration = checkNotNull(command.params["durationMinutes"]?.toIntOrNull())
            check(mmol.isFinite())
            val reason = when {
                EatingSoonPolicy.isRequestKey(command.idempotencyKey) -> "Eating Soon"
                command.params["targetIntent"] in setOf("HYPO_PROTECTION", "POST_HYPO_PROTECTION") -> "Hypo"
                command.params["targetIntent"] in setOf("ACTIVITY_PROTECTION", "PLANNED_ACTIVITY_ADAPTATION") -> "Activity"
                else -> "Automation"
            }
            val mgdl = if (duration == 0) 0.0 else UnitConverter.mmolToMgdl(mmol).toDouble()
            val target = AapsV3TargetSpec(mgdl, mgdl, duration, reason)
            refresh()
            journal.withTargetProtocol { protocol ->
                suspend fun guard() {
                    currentCoroutineContext().ensureActive()
                    check(runtimeAllowed && generation.get() == epoch && storageAvailable() && sessionProbeCounter > 0)
                    check(baselineGuard() == null && deliveryGuard() == null)
                    journal.requireLivePairing(clock(), sessionStartedAt, sessionProbeCounter)
                    // Either guard can suspend while the runtime/storage owner is stopping.
                    currentCoroutineContext().ensureActive()
                    check(runtimeAllowed && generation.get() == epoch && storageAvailable() && sessionProbeCounter > 0)
                }
                val prepared = publicationLease {
                    journal.prepare(command.idempotencyKey, target, protocol, clock(), currentTime = clock) { guard() }
                }
                // Only the caller that atomically published this prepare may decide to commit it.
                if (prepared.publication == null) return@withTargetProtocol outcome(prepared.phase)
                val preview = awaitPhase(command.idempotencyKey, setOf(AapsV3TargetPhase.PREVIEW))
                if (preview != AapsV3TargetPhase.PREVIEW) return@withTargetProtocol outcome(journal.status(command.idempotencyKey))
                publicationLease {
                    journal.commit(command.idempotencyKey, protocol, clock(), currentTime = clock) { guard() }
                }
                awaitPhase(command.idempotencyKey, emptySet())
                outcome(journal.status(command.idempotencyKey))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // A failed fingerprint check must not relabel an older applied request
            // as application of this payload. Missing private state is not absence.
            try {
                if (journal.pairingSnapshot() != null && journal.status(command.idempotencyKey) == null)
                    NativeTargetOutcome.NOT_SENT else NativeTargetOutcome.UNKNOWN
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { NativeTargetOutcome.UNKNOWN }
        }
    }

    override suspend fun reconcile(operationId: String): NativeTargetOutcome = try {
        refresh()
        journal.status(operationId)?.let(::outcome) ?: NativeTargetOutcome.UNKNOWN
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { NativeTargetOutcome.UNKNOWN }

    private suspend fun awaitPhase(operationId: String, intermediate: Set<AapsV3TargetPhase>): AapsV3TargetPhase? {
        val result = withTimeoutOrNull(8500) {
            db.localNightscoutV3Dao().observeRevision().map {
                refresh()
                journal.status(operationId)
            }.first { it in intermediate || it in terminal }
        }
        journal.expire(clock())
        return result
    }

    private fun outcome(phase: AapsV3TargetPhase?) = when (phase) {
        AapsV3TargetPhase.APPLIED -> NativeTargetOutcome.APPLIED
        null, AapsV3TargetPhase.REJECTED, AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT -> NativeTargetOutcome.NOT_SENT
        else -> NativeTargetOutcome.UNKNOWN
    }
    private val terminal = setOf(AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED, AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT)
}
