package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import io.aaps.copilot.data.local.entity.EatingWindowSnapshotEntity
import io.aaps.copilot.domain.eating.EatingEvidencePoint
import io.aaps.copilot.domain.eating.EatingWindowDualHorizonSnapshot
import io.aaps.copilot.domain.eating.EatingWindowSnapshotPlanner
import io.aaps.copilot.domain.eating.ProbableEatingWindow
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

data class PersistedEatingWindowSnapshot(
    val localCompletedDate: String,
    val generatedAt: Long,
    val sourceFingerprint: String,
    val snapshot: EatingWindowDualHorizonSnapshot
)

class EatingWindowSnapshotRepository internal constructor(
    private val gson: Gson,
    private val loadEvidence: suspend (Long, Long) -> List<EatingEvidencePoint>,
    private val loadSnapshot: suspend (String) -> EatingWindowSnapshotEntity?,
    private val storeSnapshot: suspend (EatingWindowSnapshotEntity) -> Unit
) {

    constructor(
        db: CopilotDatabase,
        gson: Gson
    ) : this(
        gson = gson,
        loadEvidence = { fromTs, throughTs ->
            val rows = db.therapyDao().carbCandidates(fromTs, throughTs)
            canonicalEvidence(rows.map { row ->
                ClinicalTherapyProjection(
                    ts = row.timestamp,
                    type = row.type,
                    payloadJson = row.payloadJson,
                    rowId = row.id
                )
            })
        },
        loadSnapshot = db.eatingWindowSnapshotDao()::forLocalCompletedDate,
        storeSnapshot = db.eatingWindowSnapshotDao()::upsert
    )

    suspend fun refresh(
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): PersistedEatingWindowSnapshot {
        val completedDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().minusDays(1)
        val dateKey = completedDate.toString()
        loadSnapshot(dateKey)?.let(::decode)
            ?.let { return it }

        val firstDate = completedDate.minusDays(
            (EatingWindowSnapshotPlanner.STABLE_LOOKBACK_DAYS - 1).toLong()
        )
        val evidence = loadEvidence(
            firstDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            completedDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
        )
        val snapshot = EatingWindowSnapshotPlanner.plan(evidence, now, zoneId)
        val entity = EatingWindowSnapshotEntity(
            localCompletedDate = dateKey,
            generatedAt = now,
            sourceFingerprint = fingerprint(evidence),
            recentWindowsJson = gson.toJson(snapshot.recent.windows),
            stableWindowsJson = gson.toJson(snapshot.stable.windows)
        )
        storeSnapshot(entity)
        return PersistedEatingWindowSnapshot(
            localCompletedDate = dateKey,
            generatedAt = now,
            sourceFingerprint = entity.sourceFingerprint,
            snapshot = snapshot
        )
    }

    fun decode(entity: EatingWindowSnapshotEntity): PersistedEatingWindowSnapshot {
        val recent = decodeWindows(entity.recentWindowsJson)
        val stable = decodeWindows(entity.stableWindowsJson)
        return PersistedEatingWindowSnapshot(
            localCompletedDate = entity.localCompletedDate,
            generatedAt = entity.generatedAt,
            sourceFingerprint = entity.sourceFingerprint,
            snapshot = EatingWindowDualHorizonSnapshot(
                recent = io.aaps.copilot.domain.eating.EatingWindowAnalysis(
                    lookbackDays = EatingWindowSnapshotPlanner.RECENT_LOOKBACK_DAYS,
                    windows = recent,
                    excludedNightEpisodeCount = 0
                ),
                stable = io.aaps.copilot.domain.eating.EatingWindowAnalysis(
                    lookbackDays = EatingWindowSnapshotPlanner.STABLE_LOOKBACK_DAYS,
                    windows = stable,
                    excludedNightEpisodeCount = 0
                )
            )
        )
    }

    private fun decodeWindows(json: String): List<ProbableEatingWindow> = try {
        gson.fromJson(
            json,
            object : TypeToken<List<ProbableEatingWindow>>() {}.type
        ) ?: emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun fingerprint(evidence: List<EatingEvidencePoint>): String {
        val canonical = evidence
            .sortedWith(compareBy<EatingEvidencePoint> { it.ts }.thenBy { it.syntheticUam }.thenBy { it.carbsG })
            .joinToString("\n") { point -> "${point.ts}|${point.carbsG}|${point.syntheticUam}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }

    private companion object {
        fun canonicalEvidence(rows: List<ClinicalTherapyProjection>): List<EatingEvidencePoint> =
            ClinicalReportDatasetBuilder.canonicalTherapyEvents(
                rows.mapNotNull(ClinicalReportDatasetBuilder::sanitizeTherapyEvent)
            ).mapNotNull { event ->
                event.point.carbsG?.let { carbs ->
                    EatingEvidencePoint(event.point.ts, carbs, event.point.syntheticUam)
                }
            }
    }
}
