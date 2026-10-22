package io.aaps.copilot.domain.meal

/** A posted message is neither evidence of visibility nor evidence of consumed food. */
internal data class MealMessageEpisode(
    val id: String,
    val meal: MealRecordRevision,
    val insulinRecordId: String,
    val insulinRecordedAtMs: Long,
    val canonicalTherapyRevision: String,
    val messagePostedAtMs: Long? = null
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(meal.canonicalId.length <= 128 && meal.grams <= 500)
        require(canonicalTherapyRevision.isNotBlank() && canonicalTherapyRevision.length <= 256)
        require(insulinRecordId.isNotBlank() && insulinRecordId.length <= 512 && insulinRecordedAtMs > 0)
        require(messagePostedAtMs == null || (!meal.deleted &&
            messagePostedAtMs >= maxOf(insulinRecordedAtMs, meal.recordedAtMs)))
    }

    val assumedStartAtMs: Long? get() = messagePostedAtMs
    val intakeConfirmed: Boolean get() = false
    val trainingLabelEligible: Boolean get() = false
    val missedMealScenarioRequired: Boolean get() = true

    fun recordPosted(atMs: Long): MealMessageEpisode {
        require(!meal.deleted && atMs >= maxOf(insulinRecordedAtMs, meal.recordedAtMs))
        return if (messagePostedAtMs == null) copy(messagePostedAtMs = atMs) else this
    }

    fun reconcile(record: MealRecordRevision, therapyRevision: String = canonicalTherapyRevision): MealMessageEpisode {
        require(record.canonicalId == meal.canonicalId)
        if (record.revision < meal.revision) return this
        if (record.revision == meal.revision) {
            require(record == meal && therapyRevision == canonicalTherapyRevision) { "Conflicting meal revision" }
            return this
        }
        return copy(meal = record, canonicalTherapyRevision = therapyRevision, messagePostedAtMs = null)
    }
}
