package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ActionCommandUamExportReservationStore(
    private val actionCommandDao: ActionCommandDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gson: Gson = Gson()
) : UamExportReservationStore {

    override suspend fun reserve(key: String): Boolean = processMutex.withLock {
        require(key.isNotBlank()) { "UAM export reservation key must not be blank" }
        val persistenceKey = IDEMPOTENCY_PREFIX + key
        if (actionCommandDao.byIdempotencyKey(persistenceKey) != null) {
            return@withLock false
        }

        val nowMs = clock()
        actionCommandDao.upsert(
            ActionCommandEntity(
                id = persistenceKey,
                timestamp = nowMs,
                type = ACTION_TYPE,
                payloadJson = gson.toJson(
                    Metadata(
                        key = key,
                        createdAtMs = nowMs,
                        updatedAtMs = nowMs
                    )
                ),
                safetyJson = PINNED_SAFETY_JSON,
                idempotencyKey = persistenceKey,
                status = STATUS_RESERVED
            )
        )
        true
    }

    override suspend fun markSent(key: String, remoteId: String) = processMutex.withLock {
        require(remoteId.isNotBlank()) { "UAM export remote id must not be blank" }
        transition(
            key = key,
            targetStatus = STATUS_SENT,
            idempotentTerminal = { row, metadata ->
                row.status == STATUS_SENT && metadata.remoteId == remoteId
            },
            update = { metadata ->
                metadata.copy(
                    updatedAtMs = clock(),
                    remoteId = remoteId,
                    detail = null
                )
            }
        )
    }

    override suspend fun markPendingUnknown(key: String, detail: String?) = processMutex.withLock {
        transition(
            key = key,
            targetStatus = STATUS_PENDING_UNKNOWN,
            idempotentTerminal = { row, metadata ->
                row.status == STATUS_PENDING_UNKNOWN && metadata.detail == detail
            },
            update = { metadata ->
                metadata.copy(
                    updatedAtMs = clock(),
                    remoteId = null,
                    detail = detail
                )
            }
        )
    }

    override suspend fun release(key: String) = processMutex.withLock {
        require(key.isNotBlank()) { "UAM export reservation key must not be blank" }
        val persistenceKey = IDEMPOTENCY_PREFIX + key
        val deleted = actionCommandDao.deleteByIdempotencyKeyTypeAndStatus(
            idempotencyKey = persistenceKey,
            type = ACTION_TYPE,
            status = STATUS_RESERVED
        )
        if (deleted == 1) return@withLock
        check(deleted == 0) { "Deleted multiple UAM export reservations" }

        val remaining = actionCommandDao.byIdempotencyKey(persistenceKey) ?: return@withLock
        check(remaining.type == ACTION_TYPE) { "Reservation row has unexpected action type" }
        throw UnsupportedOperationException(
            "Cannot release terminal UAM export reservation in ${remaining.status}"
        )
    }

    private suspend fun transition(
        key: String,
        targetStatus: String,
        idempotentTerminal: (ActionCommandEntity, Metadata) -> Boolean,
        update: (Metadata) -> Metadata
    ) {
        require(key.isNotBlank()) { "UAM export reservation key must not be blank" }
        val row = actionCommandDao.byIdempotencyKey(IDEMPOTENCY_PREFIX + key)
            ?: error("Missing UAM export reservation")
        check(row.type == ACTION_TYPE) { "Reservation row has unexpected action type" }
        val metadata = runCatching {
            gson.fromJson(row.payloadJson, Metadata::class.java)
        }.getOrNull()
            ?: error("Reservation metadata is unreadable")
        check(metadata.key == key) { "Reservation metadata key mismatch" }

        if (idempotentTerminal(row, metadata)) return
        check(row.status == STATUS_RESERVED) {
            "Cannot transition UAM export reservation from ${row.status} to $targetStatus"
        }
        val updatedMetadata = update(metadata)
        actionCommandDao.upsert(
            row.copy(
                timestamp = updatedMetadata.updatedAtMs,
                payloadJson = gson.toJson(updatedMetadata),
                safetyJson = PINNED_SAFETY_JSON,
                status = targetStatus
            )
        )
    }

    private data class Metadata(
        val key: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val remoteId: String? = null,
        val detail: String? = null
    )

    companion object {
        const val ACTION_TYPE = "uam_export_reservation"
        const val IDEMPOTENCY_PREFIX = "uam-export-reservation:"
        const val STATUS_RESERVED = "UAM_EXPORT_RESERVED"
        const val STATUS_SENT = "UAM_EXPORT_SENT"
        const val STATUS_PENDING_UNKNOWN = "UAM_EXPORT_PENDING_UNKNOWN"

        private const val PINNED_SAFETY_JSON = "{\"retention\":\"pinned\"}"
        private val processMutex = Mutex()
    }
}
