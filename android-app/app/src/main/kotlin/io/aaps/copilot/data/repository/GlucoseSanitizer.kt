package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.model.GlucosePoint

object GlucoseSanitizer {

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
        return sourcePriority(sample.source) * 10 + qualityPriority(sample.quality)
    }

    private fun pointPriority(point: GlucosePoint): Int {
        return sourcePriority(point.source) * 10 + qualityPriority(point.quality.name)
    }

    private fun qualityPriority(quality: String): Int = when (quality.uppercase()) {
        "OK" -> 3
        "STALE" -> 2
        "SENSOR_ERROR" -> 1
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
