package io.aaps.copilot.domain.predict

import java.util.Locale

enum class InsulinActionProfileId(val label: String, val isUltraRapid: Boolean) {
    NOVORAPID(label = "NovoRapid / NovoLog", isUltraRapid = false),
    HUMALOG(label = "Humalog", isUltraRapid = false),
    APIDRA(label = "Apidra", isUltraRapid = false),
    FIASP(label = "Fiasp", isUltraRapid = true),
    LYUMJEV(label = "Lyumjev", isUltraRapid = true);

    companion object {
        fun fromRaw(raw: String?): InsulinActionProfileId {
            if (raw.isNullOrBlank()) return NOVORAPID
            val normalized = raw
                .trim()
                .uppercase(Locale.US)
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
            return when (normalized) {
                "NOVORAPID",
                "NOVO_RAPID",
                "NOVOLOG",
                "NOVO_LOG",
                "ASPART" -> NOVORAPID
                "HUMALOG",
                "HUMA_LOG",
                "LISPRO" -> HUMALOG
                "APIDRA",
                "GLULISINE" -> APIDRA
                "FIASP",
                "FASTER_ASPART" -> FIASP
                "LYUMJEV",
                "ULTRA_RAPID_LISPRO",
                "URLI" -> LYUMJEV
                else -> NOVORAPID
            }
        }
    }
}

data class InsulinActionPoint(
    val minute: Double,
    val cumulative: Double
)

data class InsulinActionProfile(
    val id: InsulinActionProfileId,
    val points: List<InsulinActionPoint>
) {
    fun cumulativeAt(ageMinutes: Double): Double {
        if (ageMinutes <= 0.0) return 0.0
        val sorted = points
        if (sorted.isEmpty()) return 0.0
        if (ageMinutes <= sorted.first().minute) return sorted.first().cumulative
        if (ageMinutes >= sorted.last().minute) return sorted.last().cumulative
        for (index in 1 until sorted.size) {
            val left = sorted[index - 1]
            val right = sorted[index]
            if (ageMinutes <= right.minute) {
                val span = (right.minute - left.minute).coerceAtLeast(1e-6)
                val t = (ageMinutes - left.minute) / span
                return (left.cumulative + (right.cumulative - left.cumulative) * t).coerceIn(0.0, 1.0)
            }
        }
        return sorted.last().cumulative.coerceIn(0.0, 1.0)
    }
}

object InsulinActionProfiles {

    private val profiles = mapOf(
        InsulinActionProfileId.NOVORAPID to InsulinActionProfile(
            id = InsulinActionProfileId.NOVORAPID,
            points = listOf(
                InsulinActionPoint(0.0, 0.0),
                InsulinActionPoint(10.0, 0.0),
                InsulinActionPoint(45.0, 0.10),
                InsulinActionPoint(90.0, 0.45),
                InsulinActionPoint(180.0, 0.85),
                InsulinActionPoint(300.0, 1.0)
            )
        ),
        InsulinActionProfileId.HUMALOG to InsulinActionProfile(
            id = InsulinActionProfileId.HUMALOG,
            points = listOf(
                InsulinActionPoint(0.0, 0.0),
                InsulinActionPoint(8.0, 0.0),
                InsulinActionPoint(30.0, 0.10),
                InsulinActionPoint(60.0, 0.38),
                InsulinActionPoint(120.0, 0.72),
                InsulinActionPoint(210.0, 0.93),
                InsulinActionPoint(300.0, 1.0)
            )
        ),
        InsulinActionProfileId.APIDRA to InsulinActionProfile(
            id = InsulinActionProfileId.APIDRA,
            points = listOf(
                InsulinActionPoint(0.0, 0.0),
                InsulinActionPoint(8.0, 0.0),
                InsulinActionPoint(25.0, 0.12),
                InsulinActionPoint(55.0, 0.42),
                InsulinActionPoint(110.0, 0.75),
                InsulinActionPoint(190.0, 0.94),
                InsulinActionPoint(280.0, 1.0)
            )
        ),
        InsulinActionProfileId.FIASP to InsulinActionProfile(
            id = InsulinActionProfileId.FIASP,
            points = listOf(
                InsulinActionPoint(0.0, 0.0),
                InsulinActionPoint(4.0, 0.0),
                InsulinActionPoint(15.0, 0.10),
                InsulinActionPoint(35.0, 0.28),
                InsulinActionPoint(60.0, 0.48),
                InsulinActionPoint(120.0, 0.78),
                InsulinActionPoint(210.0, 0.95),
                InsulinActionPoint(300.0, 1.0)
            )
        ),
        InsulinActionProfileId.LYUMJEV to InsulinActionProfile(
            id = InsulinActionProfileId.LYUMJEV,
            points = listOf(
                InsulinActionPoint(0.0, 0.0),
                InsulinActionPoint(1.0, 0.0),
                InsulinActionPoint(10.0, 0.12),
                InsulinActionPoint(25.0, 0.30),
                InsulinActionPoint(45.0, 0.50),
                InsulinActionPoint(90.0, 0.75),
                InsulinActionPoint(180.0, 0.93),
                InsulinActionPoint(300.0, 1.0)
            )
        )
    )

    fun profile(id: InsulinActionProfileId): InsulinActionProfile {
        return profiles[id] ?: profiles.getValue(InsulinActionProfileId.NOVORAPID)
    }

    fun supportedIds(): List<InsulinActionProfileId> = InsulinActionProfileId.values().toList()
}
