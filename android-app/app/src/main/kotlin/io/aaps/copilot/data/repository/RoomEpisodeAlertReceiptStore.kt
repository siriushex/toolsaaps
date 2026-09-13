package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomEpisodeAlertReceiptStore(
    private val db: CopilotDatabase
) : EpisodeAlertReceiptStore {
    override val coordinationIdentity: Any
        get() = db

    override suspend fun <T> transaction(block: suspend EpisodeAlertReceiptStore.() -> T): T =
        db.withTransaction { block(this@RoomEpisodeAlertReceiptStore) }

    override suspend fun latestUnresolvedGlucoseEpisode(): AlertEventEntity? =
        db.alertEventDao().latestUnresolvedGlucoseEpisode()

    override suspend fun maxGlucoseEpisodeSequence(): Long =
        db.alertEventDao().maxGlucoseEpisodeCreatedAt()

    override suspend fun eventById(episodeId: String): AlertEventEntity? =
        db.alertEventDao().byEpisodeId(episodeId)

    override suspend fun upsertEvent(event: AlertEventEntity) {
        db.alertEventDao().upsert(event)
    }

    override suspend fun receipt(
        episodeId: String,
        kind: AlertDeliveryKind
    ): AlertDeliveryReceiptEntity? = db.alertDeliveryReceiptDao().byEpisodeAndKind(episodeId, kind.name)

    override suspend fun insertReceipt(receipt: AlertDeliveryReceiptEntity): Boolean =
        db.alertDeliveryReceiptDao().insert(receipt) != -1L

    override suspend fun updateReceipt(receipt: AlertDeliveryReceiptEntity) {
        check(db.alertDeliveryReceiptDao().update(receipt) == 1) {
            "Alert receipt disappeared while updating ${receipt.receiptId}"
        }
    }

    override suspend fun muteUntil(): Long =
        db.alertEventDao().byEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)?.suppressionUntil ?: 0L

    override fun observeMuteUntil(): Flow<Long> =
        db.alertEventDao()
            .observeByEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)
            .map { it?.suppressionUntil ?: 0L }

    override suspend fun writeMuteUntil(until: Long, nowTs: Long) {
        val dao = db.alertEventDao()
        val existing = dao.byEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)
        dao.upsert(
            AlertEventEntity(
                episodeId = EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID,
                eventType = "GLUCOSE_ALERT_MUTE_STATE",
                stage = "MUTE",
                status = if (until > 0L) AlertEpisodeStatus.OPEN.name else AlertEpisodeStatus.RESOLVED.name,
                severity = "MUTE",
                createdAt = existing?.createdAt ?: nowTs,
                updatedAt = nowTs,
                resolvedAt = if (until > 0L) null else nowTs,
                localSnapshotJson = "{}",
                causeCode = null,
                causeSummary = null,
                suppressionUntil = until,
                lastNotificationAt = null,
                revision = (existing?.revision ?: 0L) + 1L
            )
        )
    }
}
