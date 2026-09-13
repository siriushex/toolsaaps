package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.model.GlucosePoint

object GlucoseSanitizer {

    internal const val CURRENT_CAUSAL_QUERY_LIMIT = 2

    private const val LEGACY_INVALID_SOURCE = "local_broadcast"
    private const val LEGACY_INVALID_THRESHOLD_MMOL = 30.0

    fun filterEntities(samples: List<GlucoseSampleEntity>): List<GlucoseSampleEntity> {
        if (samples.isEmpty()) return emptyList()
        val selected = linkedMapOf<Long, GlucoseSampleEntity>()
        samples
            .asSequence()
            .filterNot(::isLegacyStatusArtifact)
            .forEach { sample ->
                val existing = selected[sample.timestamp]
                if (existing == null || shouldReplace(existing, sample)) {
                    selected[sample.timestamp] = sample
                }
            }
        return selected.values.sortedBy { it.timestamp }
    }

    internal fun selectCausalEntities(
        samples: List<GlucoseSampleEntity>,
        atTs: Long
    ): CausalGlucoseSelection {
        if (atTs < 0L) return CausalGlucoseSelection()
        val selected = samples
            .asSequence()
            .filter { sample ->
                sample.timestamp in 0L..atTs &&
                    sample.mmol.isFinite() &&
                    sample.mmol > 0.0 &&
                    !isKnownInvalidQuality(sample.quality) &&
                    !isLegacyStatusArtifact(sample)
            }
            .toList()
        return CausalGlucoseSelection(
            glucose = filterEntities(selected).takeLast(CURRENT_CAUSAL_QUERY_LIMIT)
        )
    }

    fun duplicateEntityIdsToDelete(samples: List<GlucoseSampleEntity>): List<Long> {
        if (samples.isEmpty()) return emptyList()
        val selected = linkedMapOf<Long, GlucoseSampleEntity>()
        val droppedIds = mutableListOf<Long>()
        samples
            .asSequence()
            .filterNot(::isLegacyStatusArtifact)
            .forEach { sample ->
                val existing = selected[sample.timestamp]
                when {
                    existing == null -> selected[sample.timestamp] = sample
                    shouldReplace(existing, sample) -> {
                        droppedIds += existing.id
                        selected[sample.timestamp] = sample
                    }
                    else -> droppedIds += sample.id
                }
            }
        return droppedIds
    }

    fun filterPoints(points: List<GlucosePoint>): List<GlucosePoint> {
        if (points.isEmpty()) return emptyList()
        val selected = linkedMapOf<Long, GlucosePoint>()
        points
            .asSequence()
            .filterNot(::isLegacyStatusArtifact)
            .forEach { point ->
                val existing = selected[point.ts]
                if (existing == null || shouldReplace(existing, point)) {
                    selected[point.ts] = point
                }
            }
        return selected.values.sortedBy { it.ts }
    }

    private fun isLegacyStatusArtifact(sample: GlucoseSampleEntity): Boolean =
        sample.source == LEGACY_INVALID_SOURCE && sample.mmol >= LEGACY_INVALID_THRESHOLD_MMOL

    private fun isLegacyStatusArtifact(point: GlucosePoint): Boolean =
        point.source == LEGACY_INVALID_SOURCE && point.valueMmol >= LEGACY_INVALID_THRESHOLD_MMOL

    private fun shouldReplace(existing: GlucoseSampleEntity, candidate: GlucoseSampleEntity): Boolean {
        val existingInvalid = isKnownInvalidQuality(existing.quality)
        val candidateInvalid = isKnownInvalidQuality(candidate.quality)
        if (existingInvalid != candidateInvalid) return existingInvalid
        val existingScore = samplePriority(existing)
        val candidateScore = samplePriority(candidate)
        return when {
            candidateScore > existingScore -> true
            candidateScore < existingScore -> false
            else -> candidate.id > existing.id
        }
    }

    private fun shouldReplace(existing: GlucosePoint, candidate: GlucosePoint): Boolean {
        val existingScore = pointPriority(existing)
        val candidateScore = pointPriority(candidate)
        return candidateScore >= existingScore
    }

    private fun samplePriority(sample: GlucoseSampleEntity): Int {
        return clinicalPriority(sample.source, sample.quality)
    }

    private fun pointPriority(point: GlucosePoint): Int {
        return clinicalPriority(point.source, point.quality.name)
    }

    internal fun clinicalPriority(source: String, quality: String): Int =
        sourcePriority(source) * 10 + qualityPriority(quality)

    internal fun isKnownInvalidQuality(quality: String): Boolean =
        quality.trim().uppercase() in setOf("SENSOR_ERROR", "ERROR", "INVALID")

    internal fun isLegacyStatusArtifact(source: String, mmol: Double): Boolean =
        source == LEGACY_INVALID_SOURCE && mmol >= LEGACY_INVALID_THRESHOLD_MMOL

    private fun qualityPriority(quality: String): Int = when (quality.uppercase()) {
        "OK", "GOOD", "VALID" -> 3
        "STALE" -> 2
        "SENSOR_ERROR", "ERROR", "INVALID" -> 1
        else -> 0
    }

    private fun sourcePriority(source: String): Int {
        return when {
            source.equals("aaps_broadcast", ignoreCase = true) -> 60
            source.equals("nightscout", ignoreCase = true) -> 50
            source.equals("xdrip_broadcast", ignoreCase = true) -> 45
            source.equals("local_nightscout_entry", ignoreCase = true) -> 42
            source.startsWith("local_nightscout", ignoreCase = true) -> 40
            source.equals("local_broadcast", ignoreCase = true) -> 10
            else -> 20
        }
    }
}

internal data class CausalGlucoseSelection(
    val glucose: List<GlucoseSampleEntity> = emptyList()
) {
    val latest: GlucoseSampleEntity? get() = glucose.lastOrNull()
    val previous: GlucoseSampleEntity? get() = glucose.dropLast(1).lastOrNull()
    val authorityPointTs: Long? get() = latest?.timestamp
}
