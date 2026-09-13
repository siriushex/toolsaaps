package io.aaps.copilot.data.repository

import com.google.gson.JsonObject
import io.aaps.copilot.data.local.entity.TherapyEventEntity

internal fun interface RemoteTherapyWriteTransactionRunner {
    suspend fun runInTransaction(block: suspend () -> Unit)
}

internal class RemoteTherapyBatchWriter(
    private val transactionRunner: RemoteTherapyWriteTransactionRunner,
    private val loadLatestByIds: suspend (List<String>) -> List<TherapyEventEntity>,
    private val upsertAll: suspend (List<TherapyEventEntity>) -> Unit,
    private val decodePayload: (String) -> JsonObject?,
    private val encodePayload: (JsonObject) -> String,
    private val batchSize: Int = MAX_ID_BATCH_SIZE
) {
    init {
        require(batchSize in 1..MAX_ID_BATCH_SIZE)
    }

    suspend fun write(incomingRows: List<TherapyEventEntity>) {
        if (incomingRows.isEmpty()) return
        transactionRunner.runInTransaction {
            val latestById = incomingRows
                .map { it.id }
                .distinct()
                .chunked(batchSize)
                .flatMap { ids -> loadLatestByIds(ids) }
                .associateBy { it.id }
            val finalRows = incomingRows.map { incoming ->
                mergeTherapyEventWithCanonicalAapsCarbMetadata(
                    incoming = incoming,
                    latestExisting = latestById[incoming.id],
                    decodePayload = decodePayload,
                    encodePayload = encodePayload
                )
            }
            upsertAll(finalRows)
        }
    }

    private companion object {
        const val MAX_ID_BATCH_SIZE = 900
    }
}
