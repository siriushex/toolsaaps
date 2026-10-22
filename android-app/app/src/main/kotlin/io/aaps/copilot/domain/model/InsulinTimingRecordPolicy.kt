package io.aaps.copilot.domain.model

import java.util.Locale

/** Shared exclusions only; passing this filter is not proof of insulin delivery or meal intent. */
internal fun isExcludedInsulinTimingRecord(recordId: String, payload: Map<String, String>): Boolean {
    return recordId.startsWith("iob-inf-") || recordId.startsWith("br-local_broadcast-") || payload.any { (rawKey, rawValue) ->
        val key = rawKey.lowercase(Locale.US).filter(Char::isLetterOrDigit)
        val value = rawValue.trim().uppercase(Locale.US)
        when (key) {
            "issmb", "inferred", "recovered", "synthetic", "cancelled", "canceled", "iscancelled", "iscanceled" ->
                value in setOf("TRUE", "1")
            "bolustype", "type" -> value in setOf("SMB", "MICROBOLUS", "PRIMING")
            "classification" -> value in setOf("INFERRED_IOB", "RECOVERED_MICROBOLUS", "LOCAL_ACTION", "UAM_SYNTHETIC")
            else -> false
        }
    }
}
