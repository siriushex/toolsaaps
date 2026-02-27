package io.aaps.copilot.util

import java.util.Locale

object GlucoseUnitNormalizer {

    fun normalizeToMmol(
        valueRaw: Double,
        valueKey: String,
        units: String?
    ): Double {
        val normalizedKey = normalizeKey(valueKey)
        val compactKey = normalizedKey.replace("_", "")
        val keyIndicatesMgdl = normalizedKey.contains("mgdl")
        val keyIndicatesSgv = normalizedKey == "sgv" || normalizedKey.endsWith("_sgv")
        val keyIndicatesMmol = normalizedKey.contains("mmol")
        val keySuggestsBgEstimate = compactKey.contains("bgestimate")
        val explicitMmol = units?.contains("mmol") == true
        val explicitMg = units?.contains("mg") == true

        return when {
            keyIndicatesMgdl -> UnitConverter.mgdlToMmol(valueRaw)
            keyIndicatesMmol -> valueRaw
            explicitMg -> UnitConverter.mgdlToMmol(valueRaw)
            // SGV is commonly mg/dL; keep mmol-like values (<=22) as-is for mmol relays.
            keyIndicatesSgv && !explicitMmol && valueRaw > 22.0 -> UnitConverter.mgdlToMmol(valueRaw)
            // Some payloads incorrectly mark mmol units while still sending mg/dL.
            explicitMmol && (valueRaw > 30.0 || (keySuggestsBgEstimate && valueRaw >= 18.0)) ->
                UnitConverter.mgdlToMmol(valueRaw)
            explicitMmol -> valueRaw
            // BgEstimate without explicit units is often in mg/dL in xDrip/AAPS relays.
            !explicitMmol && !explicitMg && keySuggestsBgEstimate && valueRaw >= 18.0 ->
                UnitConverter.mgdlToMmol(valueRaw)
            valueRaw > 35.0 -> UnitConverter.mgdlToMmol(valueRaw)
            else -> valueRaw
        }
    }

    private fun normalizeKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }
}
