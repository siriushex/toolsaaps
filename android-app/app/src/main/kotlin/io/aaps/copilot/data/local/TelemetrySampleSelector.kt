package io.aaps.copilot.data.local

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity

object TelemetrySampleSelector {

    fun selectLatestByKey(
        samples: Iterable<TelemetrySampleEntity>
    ): Map<String, TelemetrySampleEntity> {
        val result = HashMap<String, TelemetrySampleEntity>()
        samples.forEach { sample ->
            val existing = result[sample.key]
            if (existing == null || isPreferred(sample, existing)) {
                result[sample.key] = sample
            }
        }
        return result
    }

    fun isPreferred(
        candidate: TelemetrySampleEntity,
        current: TelemetrySampleEntity
    ): Boolean {
        if (candidate.timestamp != current.timestamp) {
            return candidate.timestamp > current.timestamp
        }
        val candidatePriority = sourcePriority(candidate.source)
        val currentPriority = sourcePriority(current.source)
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority
        }
        return candidate.id > current.id
    }

    internal fun sourcePriority(source: String): Int = when (source) {
        "aaps_broadcast" -> 100
        "local_nightscout_devicestatus" -> 95
        "nightscout_devicestatus" -> 80
        "nightscout_treatment" -> 75
        "nightscout" -> 70
        "xdrip_broadcast" -> 60
        else -> 10
    }
}
