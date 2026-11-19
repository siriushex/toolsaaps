package io.aaps.copilot.service

import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.NativeTargetOutcome
import io.aaps.copilot.data.repository.NativeMealSubmission
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

enum class AapsV3ConnectionStatus { UNPAIRED, WAITING, VERIFYING, LOCAL_PEER_VERIFIED, CONNECTED, CONTROL_DISABLED, REVOKED, OFFLINE, ERROR }
enum class AapsV3PairingRoute { SAME_PHONE, STORAGE }
enum class AapsV3PairingIssue { LOCAL_INVITATION_MISSING, LOCAL_NOT_READY }
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
    val comboTransfer: ComboTransferState = ComboTransferState(),
    val settingsSubscribedAtCheck: Boolean? = null,
    val pairingIssue: AapsV3PairingIssue? = null,
    val localLinkCheckedAtMs: Long? = null,
    val localEnrollmentRole: AapsFinalClientRole? = null,
    val localMembershipVerifiedAtMs: Long? = null,
    val localMembershipRole: AapsFinalClientRole? = null,
    val localSettingsProbeVerified: Boolean? = null
)

/** Foreground-runtime owner. Observation never commits a preview or replays a command. */
internal class AapsV3TargetChannel(
    private val db: CopilotDatabase,
    private val journal: AapsV3TargetJournal,
    private val storageAvailable: () -> Boolean,
    private val baselineGuard: suspend (masterInstallId: String) -> String?,
    private val publicationLease: suspend (suspend () -> AapsV3TargetJournalSnapshot) -> AapsV3TargetJournalSnapshot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyStore: (() -> AapsV3ComboJournalStore)? = null,
    private val localOffers: LocalEnrollmentOffers? = null,
    private val localWitnessPeer: (suspend (String, String, ByteArray) -> ByteArray?)? = null,
    private val localSettingsPeer: (suspend (String, String, ByteArray) -> ByteArray?)? = null,
    private val reportTargetFailure: suspend (String) -> Unit = {},
    private val capturePairedBaseline: (suspend (String, Boolean) -> (suspend () -> Boolean))? = null
) : NativeTargetTransport {
    private val store = LocalNightscoutV3Store(db)
    private val profileSource = AapsV3EffectiveProfileSource(store)
    private val refreshMutex = Mutex()
    suspend fun submitManualMeal(id: String, recordPeer: ((String, ByteArray) -> Unit)?,
                                 peer: (String, ByteArray) -> ByteArray?): NativeMealSubmission = withContext(Dispatchers.IO) {
        val epoch = generation.get()
        val pair = journal.pairingSnapshot() ?: return@withContext NativeMealSubmission.NOT_VERIFIED
        fun guard() = check(runtimeAllowed && generation.get() == epoch && pair.localEnrollment)
        val prepared = journal.reserveLocalMealPreview(id, clock, ::guard) ?: return@withContext NativeMealSubmission.NOT_VERIFIED
        val bytes = prepared.envelope.json.toString().encodeToByteArray()
        try {
            guard()
            val reply = peer(pair.masterInstallId, bytes) ?: return@withContext NativeMealSubmission.NOT_VERIFIED
            try {
                guard()
                if (!journal.acceptLocalMealPreview(id, HistoryJson.parse(reply, 16_384), clock())) return@withContext NativeMealSubmission.NOT_VERIFIED
                if (recordPeer != null) {
                    val wire = journal.claimLocalMealRecord(id, clock, ::guard).json.toString().encodeToByteArray()
                    try { guard(); recordPeer(pair.masterInstallId, wire) } finally { wire.fill(0) }
                    NativeMealSubmission.CHECK_RECORD
                } else NativeMealSubmission.PREVIEW_ONLY
            } finally { reply.fill(0) }
        } finally {
            bytes.fill(0)
            withContext(NonCancellable) { journal.retireLocalMealPreview(id) }
        }
    }
    suspend fun verifyMealRow(row: io.aaps.copilot.data.local.entity.ActionCommandEntity, grams: Double,
                              reader: suspend (String, Long, Double) -> Long?): Boolean {
        val at = reader(row.idempotencyKey, row.timestamp, grams) ?: return false
        return journal.confirmNativeMeal(row, at, grams)
    }
    private class MembershipCheck(val epoch: Long, val client: String, val attempted: Long,
                                  val receipt: AapsLocalRoleReceipt?, val settings: Boolean?)
    // Accessed under refreshMutex; never used for command admission.
    private var membershipCheck: MembershipCheck? = null
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
                        if (generation.get() == epoch) updateUnpaired()
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

    suspend fun pair(pin: CharArray, route: AapsV3PairingRoute) = try { enrollmentMutex.withLock {
        mutableState.update { it.copy(busy = true, pairingIssue = null) }
        val matches = mutableListOf<AapsV3PairingCredential>()
        try {
            val local = if (route == AapsV3PairingRoute.SAME_PHONE) localOffers?.current() else null
            if (route == AapsV3PairingRoute.SAME_PHONE && (local == null || localOffers?.ready(local) != true || !runtimeAllowed)) {
                mutableState.update { it.copy(pairingIssue = if (local == null) AapsV3PairingIssue.LOCAL_INVITATION_MISSING else AapsV3PairingIssue.LOCAL_NOT_READY) }
                return@withLock
            }
            val routeReady = {
                runtimeAllowed && if (local != null) localOffers?.ready(local) == true
                else localOffers?.current() == null && storageAvailable()
            }
            check(routeReady())
            journal.reservePairingAttempt(clock())
            if (local != null) matches += AapsV3PairingProtocol.unwrapFinal(local.wrapped, pin.copyOf(), clock())
            val offers = if (local == null) db.localNightscoutV3Dao().pairingOffers() else emptyList()
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
                    val credential = AapsV3PairingProtocol.unwrapFinal(offer, pin.copyOf(), clock())
                    if (row.identifier == "aaps_clientcontrol_offer_${credential.clientId}") matches += credential else credential.close()
                } catch (_: AapsV3PairingFailure) { }
            }
            currentCoroutineContext().ensureActive()
            check(matches.size == 1 && routeReady())
            journal.enroll(matches.single(), clock(), local?.handle, routeReady)
            runtimeScope?.let(::start)
            refresh()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.ERROR) }
        finally { pin.fill('\u0000'); matches.forEach { it.close() }; mutableState.update { it.copy(busy = false) } }
    } } finally { pin.fill('\u0000') }

    suspend fun checkConnection(checkSettings: Boolean = false) {
        mutableState.update { it.copy(pairingIssue = null) }
        if (runtimeAllowed) runtimeScope?.let(::start)
        val epoch = generation.get()
        try { refresh(recheckMembership = true, checkSettings = checkSettings) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { connectionFailed(epoch) }
    }

    /** The key arrived via IPC, so there is no settings-document revision to wake this runtime. */
    suspend fun acceptLocalEnrollmentKey(id: String, handle: String, bytes: ByteArray): Boolean {
        val offers = localOffers ?: return false
        if (!runtimeAllowed || !journal.acceptLocalKey(offers, id, handle, bytes, clock)) return false
        synchronized(this) {
            if (runtimeAllowed) runtimeScope?.launch(Dispatchers.IO) { checkConnection() }
        }
        return true
    }

    @Synchronized private fun connectionFailed(epoch: Long) {
        if (generation.get() != epoch) return
        stopHistory()
        sessionProbeCounter = 0
        mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.ERROR)
    }

    suspend fun acceptLocalEnrollmentPong(id: String, handle: String, bytes: ByteArray): Boolean {
        val offers = localOffers ?: return false
        if (!runtimeAllowed || !journal.acceptLocalPong(offers, id, handle, bytes, clock)) return false
        synchronized(this) {
            if (runtimeAllowed) runtimeScope?.launch(Dispatchers.IO) { checkConnection() }
        }
        return true
    }

    suspend fun acceptLocalEnrollmentRole(id: String, handle: String, bytes: ByteArray): Boolean {
        val offers = localOffers ?: return false
        if (!runtimeAllowed || !journal.acceptLocalRole(offers, id, handle, bytes, clock)) return false
        synchronized(this) {
            if (runtimeAllowed) runtimeScope?.launch(Dispatchers.IO) { checkConnection() }
        }
        return true
    }

    suspend fun revoke() = try {
        stopHistory()
        localOffers?.clear()
        journal.revokePairing()
        sessionProbeCounter = 0
        mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.REVOKED)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { mutableState.value = AapsV3ConnectionState(AapsV3ConnectionStatus.ERROR) }

    override suspend fun isConfigured() = journal.hasNativeState()

    suspend fun checkLocalTargetRow(read: suspend (String, LocalTargetReceiptQuery) -> Long?): LocalTargetRowCheck? = try {
        val query = journal.localReceiptQuery(clock())
        if (query == null) null else {
            val startedAt = read(query.first, query.second)
            LocalTargetRowCheck(startedAt).takeIf { journal.localReceiptQuery(clock()) == query }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun updateUnpaired() {
        val subscribed = runtimeAllowed && storageAvailable()
        mutableState.update { AapsV3ConnectionState(settingsSubscribedAtCheck = subscribed, busy = it.busy, pairingIssue = it.pairingIssue) }
    }

    private suspend fun refresh(recheckMembership: Boolean = false, checkSettings: Boolean = false) = refreshMutex.withLock {
        val epoch = generation.get()
        if (!journal.hasNativeState()) {
            synchronized(this) {
                if (generation.get() == epoch) {
                    stopHistory()
                    updateUnpaired()
                }
            }
            return@withLock
        }
        var pair = checkNotNull(journal.pairingSnapshot())
        if (runtimeAllowed && storageAvailable() && generation.get() == epoch) journal.rotateFinalSecret(clock())
        val ackRow = store.get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_ack_${pair.clientId}")
        val ack = ackRow?.takeIf { !it.deleted && it.source == "local-v3-owner" }
            ?.let { HistoryJson.parse(it.payloadJson.toByteArray(), 65_536)["ack"] as? JsonObject }
        if (ack != null) journal.acknowledgeProbe(ack, clock())
        store.get(LocalV3Collection.SETTINGS, "aaps")?.let { journal.observeRoster(it, clock()) }
        if (ack != null) journal.withTargetProtocol { journal.acknowledgePending(it, ack, clock()) }
        journal.expire(clock())
        fun probeRouteReady(pair: AapsV3PairingSnapshot) = if (pair.localEnrollment)
            localOffers?.current()?.let { it.clientId == pair.clientId && localOffers.ready(it) } == true
        else storageAvailable()
        if (runtimeAllowed && probeRouteReady(pair) && generation.get() == epoch) {
            journal.probe(clock(), sessionStartedAt, localOffers)?.let { publication ->
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
        val witnessPeer = localWitnessPeer
        val witness = if (witnessPeer != null && runtimeAllowed && epoch == generation.get() && pair.localEnrollment &&
            pair.enrollmentRole != null && pair.phase != AapsV3PairingPhase.REVOKED && localOffers?.current() == null) {
            val now = clock()
            val previous = membershipCheck?.takeIf { it.epoch == epoch && it.client == pair.clientId }
            if (previous != null && ((!recheckMembership && now - previous.attempted in 0 until 60_000L) ||
                (checkSettings && previous.settings != null && now - previous.attempted in 0 until 5_000L))) previous.receipt
            else {
                val storagePeer = localSettingsPeer.takeIf { checkSettings }
                val overSettings = storagePeer?.let { journal.checkLocalMembership(clock, it) }
                val result = overSettings ?: if (storagePeer != null && previous != null &&
                    previous.settings == null && now - previous.attempted in 0 until 5_000L) previous.receipt
                    else journal.checkLocalMembership(clock, witnessPeer)
                membershipCheck = MembershipCheck(epoch, pair.clientId, now, result, storagePeer?.let { overSettings != null })
                result
            }
        } else { membershipCheck = null; null }
        val status = when {
            pair.phase == AapsV3PairingPhase.REVOKED -> AapsV3ConnectionStatus.REVOKED
            !runtimeAllowed -> AapsV3ConnectionStatus.OFFLINE
            witness != null -> AapsV3ConnectionStatus.LOCAL_PEER_VERIFIED
            !probeRouteReady(pair) -> AapsV3ConnectionStatus.OFFLINE
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
            mutableHistoryState.value, pairingIssue = mutableState.value.pairingIssue,
            localLinkCheckedAtMs = pair.lastPongAt.takeIf { pair.localEnrollment && pair.phase == AapsV3PairingPhase.WAITING && pair.pongCounter == 2L && it > 0 },
            localEnrollmentRole = pair.enrollmentRole.takeIf { pair.localEnrollment && pair.phase == AapsV3PairingPhase.WAITING },
            localMembershipVerifiedAtMs = witness?.signedAt, localMembershipRole = witness?.role,
            localSettingsProbeVerified = membershipCheck?.settings)
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
        var commitAttempted = false
        var stage = "request_validation"
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
            stage = "session_refresh"
            refresh()
            stage = "signed_protocol"
            journal.withTargetProtocol { protocol ->
                var pairedCheck: (suspend () -> Boolean)? = null
                var pairedMaster: String? = null
                suspend fun capture(commit: Boolean) {
                    val read = capturePairedBaseline ?: return
                    stage = "paired_input_read"
                    val master = journal.requireLivePairing(clock(), sessionStartedAt, sessionProbeCounter, requireTargetRole = true)
                    pairedMaster = master
                    pairedCheck = read(master, commit)
                }
                suspend fun guard() {
                    currentCoroutineContext().ensureActive()
                    stage = "runtime_storage_session"
                    check(runtimeAllowed && generation.get() == epoch && storageAvailable() && sessionProbeCounter > 0)
                    stage = "live_pairing"
                    val master = journal.requireLivePairing(clock(), sessionStartedAt, sessionProbeCounter, requireTargetRole = true)
                    stage = "paired_input_match"
                    if (capturePairedBaseline != null) check(pairedMaster == master && pairedCheck?.invoke() == true)
                    stage = "clinical_baseline"
                    check(baselineGuard(master) == null)
                    stage = "delivery_guard"
                    check(deliveryGuard() == null)
                    stage = "live_pairing"
                    check(journal.requireLivePairing(clock(), sessionStartedAt, sessionProbeCounter, requireTargetRole = true) == master)
                    // Either guard can suspend while the runtime/storage owner is stopping.
                    currentCoroutineContext().ensureActive()
                    stage = "runtime_recheck"
                    check(runtimeAllowed && generation.get() == epoch && storageAvailable() && sessionProbeCounter > 0)
                    stage = "journal_publication"
                }
                stage = "prepare"
                capture(commit = false)
                val prepared = publicationLease {
                    journal.prepare(command.idempotencyKey, target, protocol, clock(),
                        currentTime = clock, expectedActionId = command.id) { guard() }
                }
                // Only the caller that atomically published this prepare may decide to commit it.
                if (prepared.publication == null) return@withTargetProtocol outcome(prepared.phase)
                stage = "preview"
                val preview = awaitPhase(command.idempotencyKey, setOf(AapsV3TargetPhase.PREVIEW))
                if (preview != AapsV3TargetPhase.PREVIEW) return@withTargetProtocol outcome(journal.status(command.idempotencyKey))
                stage = "commit"
                capture(commit = true)
                publicationLease {
                    commitAttempted = true
                    journal.commit(command.idempotencyKey, protocol, clock(), currentTime = clock) { guard() }
                }
                stage = "acknowledgement"
                awaitPhase(command.idempotencyKey, emptySet())
                outcome(journal.status(command.idempotencyKey))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // A failed fingerprint check must not relabel an older applied request
            // as application of this payload. Missing private state is not absence.
            try {
                try { reportTargetFailure(stage) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Diagnostics must not alter delivery classification. */ }
                if (!commitAttempted && journal.pairingSnapshot() != null && journal.status(command.idempotencyKey) == null)
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

    internal fun outcome(phase: AapsV3TargetPhase?) = when (phase) {
        AapsV3TargetPhase.APPLIED -> NativeTargetOutcome.APPLIED
        AapsV3TargetPhase.REJECTED, AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT -> NativeTargetOutcome.NOT_SENT
        else -> NativeTargetOutcome.UNKNOWN
    }
    private val terminal = setOf(AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED, AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT)
}
