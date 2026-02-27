package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.model.GlucosePoint

object GlucoseSanitizer {

    private const val LEGACY_INVALID_SOURCE = "local_broadcast"
    private const val LEGACY_INVALID_THRESHOLD_MMOL = 30.0

    fun filterEntities(samples: List<GlucoseSampleEntity>): List<GlucoseSampleEntity> =
        samples.filterNot(::isLegacyStatusArtifact)

    fun filterPoints(points: List<GlucosePoint>): List<GlucosePoint> =
        points.filterNot(::isLegacyStatusArtifact)

    private fun isLegacyStatusArtifact(sample: GlucoseSampleEntity): Boolean =
        sample.source == LEGACY_INVALID_SOURCE && sample.mmol >= LEGACY_INVALID_THRESHOLD_MMOL

    private fun isLegacyStatusArtifact(point: GlucosePoint): Boolean =
        point.source == LEGACY_INVALID_SOURCE && point.valueMmol >= LEGACY_INVALID_THRESHOLD_MMOL
}
