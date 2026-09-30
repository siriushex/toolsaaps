package io.aaps.copilot.domain.meal

/**
 * A record acknowledgement is not an acknowledgement that food was eaten.
 * Revision is the local monotonic reconciliation revision, not an AAPS timestamp.
 */
data class MealRecordRevision(
    val canonicalId: String,
    val revision: Long,
    val recordedAtMs: Long,
    val grams: Double,
    val deleted: Boolean
) {
    init {
        require(canonicalId.isNotBlank() && revision >= 0 && recordedAtMs > 0)
        require(grams.isFinite() && grams >= 0)
    }
}

class MealEpisodeIdentity(val input: MealInput, val aapsRecord: MealRecordRevision? = null) {
    /** Caller must establish canonical linkage, never infer it from time proximity. */
    fun reconcile(record: MealRecordRevision): MealEpisodeIdentity {
        val current = aapsRecord
        if (current != null) {
            require(current.canonicalId == record.canonicalId) { "Different canonical meal" }
            if (record.revision < current.revision) return this
            if (record.revision == current.revision) {
                require(record == current) { "Conflicting meal revision" }
                return this
            }
        }
        return MealEpisodeIdentity(input, record)
    }
}
