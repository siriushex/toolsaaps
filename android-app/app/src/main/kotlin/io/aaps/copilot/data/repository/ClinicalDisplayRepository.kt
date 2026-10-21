package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.report.ClinicalReportDocument
import io.aaps.copilot.scheduler.NightlyAdviceSchedule
import io.aaps.copilot.config.ClinicalAiProviderId
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Aggregate display projection only. Never a prepared payload, consent or retry receipt. */
internal data class ClinicalDisplaySnapshot(val source: ClinicalReportEntity, val savedAt: Long, val version: Int = 1) {
    val local: ClinicalLocalReport get() = requireNotNull(ClinicalReportPersistenceSerializer.local(source))
    val report: ClinicalAdvisoryReport? get() = source.responseJson?.let(ClinicalReportSchemaParser::parse)
}

internal class ClinicalDisplayRepository(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val datasetFactory: ClinicalReportDatasetFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentZone: () -> ZoneId = ZoneId::systemDefault,
    private val processOwner: String = PROCESS_OWNER
) {
    private val telemetry = db.telemetryDao()
    private val observing = AtomicBoolean(false)
    private val mutableRefreshing = MutableStateFlow(false)
    private val readFailed = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()
    val snapshots: Flow<ClinicalDisplaySnapshot?> = telemetry.observeCurrentBySourceAndKey(SOURCE, SNAPSHOT_KEY)
        .distinctUntilChanged().map(::decodeRow).catch { readFailed.value = true }
    val failed: Flow<Boolean> = combine(
        telemetry.observeCurrentBySourceAndKey(SOURCE, ATTEMPT_KEY).distinctUntilChanged(), snapshots, readFailed
    ) { row, snapshot, unavailable ->
        val attempt = decodeAttempt(row)
        unavailable || row != null && (attempt == null || attempt.status !in setOf("COMPLETE", "RUNNING") ||
            attempt.status == "RUNNING" && attempt.owner != processOwner ||
            attempt.status == "COMPLETE" && snapshot == null)
    }.distinctUntilChanged().catch { emit(true) }

    fun initialize(scope: CoroutineScope) {
        if (!observing.compareAndSet(false, true)) return
        scope.launch {
            try {
                db.clinicalReportDao().observeSuccessfulForDisplay().collect { rows ->
                    rows.asReversed().forEach { entity -> recordSuccessfulSource(entity) }
                    readFailed.value = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readFailed.value = true }
            finally { observing.set(false) }
        }
    }

    internal suspend fun recordSuccessfulSource(entity: ClinicalReportEntity) {
        val snapshot = normalized(entity, clock()) ?: return
        if (gson.toJson(snapshot).toByteArray(Charsets.UTF_8).size > MAX_BYTES) return
        db.withTransaction {
            publishIfNewer(snapshot)
            val attempt = decodeAttempt(telemetry.byId(ATTEMPT_KEY))
            if (attempt != null && attempt.status != "RUNNING" && snapshot.local.generatedAt >= attempt.end)
                storeAttempt(attempt.copy(status = "COMPLETE"))
        }
    }

    suspend fun refreshIfDue(now: Long, zone: ZoneId, force: Boolean = false) = withContext(Dispatchers.IO) {
        require(now > 0)
        val end = NightlyAdviceSchedule.windowEnd(Instant.ofEpochMilli(now).atZone(zone)).toInstant().toEpochMilli()
        val attempt = try { db.withTransaction {
            val saved = decodeRow(telemetry.byId(SNAPSHOT_KEY))
            val row = telemetry.byId(ATTEMPT_KEY)
            var previous = decodeAttempt(row)
            if (previous?.status == "RUNNING" && previous.owner != processOwner) {
                previous = previous.copy(status = "FAILED")
                storeAttempt(previous)
            }
            if (saved != null && (saved.savedAt > now || saved.local.generatedAt >= end)) return@withTransaction null
            if (previous != null && (previous.startedAt > now || previous.end > end)) return@withTransaction null
            if (row != null && previous == null && !force) return@withTransaction null
            if (previous?.end == end && (!force || previous.status == "RUNNING" ||
                    previous.status == "COMPLETE" && saved != null))
                return@withTransaction null
            Attempt(UUID.randomUUID().toString(), end, zone.id, now, "RUNNING", processOwner).also { storeAttempt(it) }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { readFailed.value = true; null } ?: return@withContext
        mutableRefreshing.value = true
        try {
            val payload = withTimeout(120_000) { datasetFactory.build(end, zone) }
            val data = payload.dataset
            require(data.generatedAt == end && data.zoneId == zone.id)
            val local = ClinicalLocalReport(data.summary7d, data.summary30d, payload.sha256, data.generatedAt,
                data.zoneId, summary24h = data.summary24h)
            val entity = ClinicalReportEntity(attempt.token, "LOCAL_READY", data.summary30d.fromTs, end,
                payload.sha256, "{}", ClinicalReportPersistenceSerializer.displaySummary(local),
                null, null, null, "LOCAL_DISPLAY", data.schemaVersion, now, null, null)
            val candidate = requireNotNull(normalized(entity, clock()))
            db.withTransaction {
                val current = decodeAttempt(telemetry.byId(ATTEMPT_KEY))
                if (current?.token != attempt.token) return@withTransaction
                if (currentZone().id != zone.id || clock() < now) {
                    storeAttempt(attempt.copy(status = "FAILED"))
                    return@withTransaction
                }
                publishIfNewer(candidate)
                storeAttempt(attempt.copy(status = "COMPLETE"))
            }
            readFailed.value = false
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) { finishFailed(attempt, "FAILED") }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { finishFailed(attempt, "CANCELLED") }
            throw cancelled
        } catch (_: Exception) {
            finishFailed(attempt, "FAILED")
        } finally { mutableRefreshing.value = false }
    }

    private suspend fun finishFailed(attempt: Attempt, status: String) {
        try {
            db.withTransaction {
                if (decodeAttempt(telemetry.byId(ATTEMPT_KEY))?.token == attempt.token) storeAttempt(attempt.copy(status = status))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { readFailed.value = true }
    }

    private suspend fun publishIfNewer(candidate: ClinicalDisplaySnapshot) {
        val current = decodeRow(telemetry.byId(SNAPSHOT_KEY))
        if (current != null) {
            if (candidate.savedAt < current.savedAt || candidate.local.generatedAt < current.local.generatedAt) return
            if (candidate.local.generatedAt == current.local.generatedAt) {
                if (candidate.source.requestId == current.source.requestId && candidate.source.status == current.source.status) return
                if (candidate.source.status != "COMPLETE" || current.source.status == "COMPLETE" &&
                    candidate.source.createdAt <= current.source.createdAt) return
            }
        }
        val json = gson.toJson(candidate)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        telemetry.upsertAll(listOf(TelemetrySampleEntity(SNAPSHOT_KEY, candidate.savedAt, SOURCE,
            SNAPSHOT_KEY, null, json, null, "OK")))
    }

    private suspend fun storeAttempt(attempt: Attempt) {
        telemetry.upsertAll(listOf(TelemetrySampleEntity(ATTEMPT_KEY, attempt.startedAt, SOURCE,
            ATTEMPT_KEY, null, gson.toJson(attempt), null, "OK")))
    }

    private fun decodeAttempt(row: TelemetrySampleEntity?): Attempt? = try {
        row?.takeIf { it.id == ATTEMPT_KEY && it.key == ATTEMPT_KEY && it.source == SOURCE && it.quality == "OK" }
            ?.valueText?.takeIf { it.length <= 1024 }?.let { gson.fromJson(it, Attempt::class.java) }?.takeIf {
                it.token.length in 1..128 && it.end > 0 && it.startedAt >= it.end &&
                    it.owner.length in 1..128 && it.status in setOf("RUNNING", "COMPLETE", "FAILED", "CANCELLED") &&
                    ZoneId.of(it.zone).id == it.zone
            }
    } catch (_: RuntimeException) { null }

    private fun decodeRow(row: TelemetrySampleEntity?): ClinicalDisplaySnapshot? = try {
        row?.takeIf { it.id == SNAPSHOT_KEY && it.key == SNAPSHOT_KEY && it.source == SOURCE && it.quality == "OK" }
            ?.valueText?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
            ?.let { gson.fromJson(it, ClinicalDisplaySnapshot::class.java) }?.takeIf { saved ->
                saved.version == 1 && normalized(saved.source, saved.savedAt) == saved
            }
    } catch (_: RuntimeException) { null }

    private fun normalized(entity: ClinicalReportEntity, savedAt: Long): ClinicalDisplaySnapshot? = try {
        require(entity.status in setOf("LOCAL_READY", "COMPLETE") && entity.sanitizedError == null)
        require(entity.localSummaryJson.length <= 262144 && entity.requestId.length in 1..128)
        require(entity.requestHash.matches(Regex("[0-9a-f]{64}")))
        val local = requireNotNull(ClinicalReportPersistenceSerializer.local(entity))
        require(local.generatedAt in 1..savedAt && savedAt <= 4_102_444_800_000L)
        require(entity.requestedThroughTs == local.generatedAt && entity.requestedFromTs == local.summary30d.fromTs)
        require(entity.createdAt in local.generatedAt..savedAt && entity.schemaVersion in 1..1000)
        ZoneId.of(local.zoneId)
        ClinicalReportDocument.requireValidPeriodSummary(local.summary7d, 7, local.generatedAt)
        ClinicalReportDocument.requireValidPeriodSummary(local.summary30d, 30, local.generatedAt)
        local.summary24h?.let { ClinicalReportDocument.requireValidPeriodSummary(it, 1, local.generatedAt) }
        if (entity.status == "COMPLETE") {
            require(entity.responseJson != null && entity.responseJson.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            ClinicalReportSchemaParser.parse(requireNotNull(entity.responseJson))
            ClinicalAiProviderId.valueOf(entity.provider)
            require(!entity.model.isNullOrBlank() && entity.model.length <= 128 && entity.schemaVersion > 0)
            require(entity.completedAt != null && entity.completedAt in entity.createdAt..savedAt)
        } else require(entity.responseJson == null)
        ClinicalDisplaySnapshot(entity.copy(coverageJson = "{}", renderedText = null,
            localSummaryJson = ClinicalReportPersistenceSerializer.displaySummary(local)), savedAt)
    } catch (_: ClinicalOpenAiException.InvalidResponse) { null }
    catch (_: RuntimeException) { null }

    private data class Attempt(val token: String, val end: Long, val zone: String, val startedAt: Long,
        val status: String, val owner: String)
    companion object {
        internal const val SOURCE = "clinical_display"
        internal const val SNAPSHOT_KEY = "clinical_display_snapshot_v1"
        internal const val ATTEMPT_KEY = "clinical_display_attempt_v1"
        private const val MAX_BYTES = 131072
        private val PROCESS_OWNER = UUID.randomUUID().toString()
    }
}
