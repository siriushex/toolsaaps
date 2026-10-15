package io.aaps.copilot.domain.pump

data class InfusionSetEvidence(val occurrenceTs: Long, val generation: String, val source: String)
enum class InfusionSetCondition { UNKNOWN, NOT_DUE, DUE }
data class InfusionSetTiming(
    val condition: InfusionSetCondition = InfusionSetCondition.UNKNOWN,
    val evidence: InfusionSetEvidence? = null,
    val dueTs: Long? = null,
    val ageMs: Long? = null
)

object InfusionSetPolicy {
    const val INTERVAL_MS = 72 * 3_600_000L
    fun evaluate(nowTs: Long, evidence: InfusionSetEvidence?): InfusionSetTiming {
        if (nowTs <= 0 || evidence == null || evidence.occurrenceTs !in 1..nowTs ||
            evidence.generation.isBlank() || evidence.source.isBlank()) return InfusionSetTiming()
        val due = try { Math.addExact(evidence.occurrenceTs, INTERVAL_MS) }
            catch (_: ArithmeticException) { return InfusionSetTiming() }
        return InfusionSetTiming(if (nowTs >= due) InfusionSetCondition.DUE else InfusionSetCondition.NOT_DUE,
            evidence, due, nowTs - evidence.occurrenceTs)
    }
}
