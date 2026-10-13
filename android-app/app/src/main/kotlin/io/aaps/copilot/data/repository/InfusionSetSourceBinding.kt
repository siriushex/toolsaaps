package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.pump.InfusionSetCondition
import io.aaps.copilot.domain.pump.InfusionSetPolicy

internal suspend fun postCurrentInfusionReminder(db: CopilotDatabase, generation: String, dueTs: Long,
    clock: () -> Long = System::currentTimeMillis, post: () -> InfusionPostResult): InfusionPostResult = db.withTransaction {
    val rows = db.therapyDao().latestInfusionChangeCandidates(InfusionSetEvidenceReader.TYPES,
        InfusionSetEvidenceReader.MAX_CANDIDATES + 1)
    val now = clock()
    val timing = InfusionSetPolicy.evaluate(now, InfusionSetEvidenceReader.read(rows, now))
    if (timing.evidence?.generation != generation || timing.dueTs != dueTs || timing.condition != InfusionSetCondition.DUE)
        InfusionPostResult.STALE else post()
}
