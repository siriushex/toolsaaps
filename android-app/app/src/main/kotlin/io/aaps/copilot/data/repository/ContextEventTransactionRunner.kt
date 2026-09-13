package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase

internal interface ContextEventTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

internal class RoomContextEventTransactionRunner(
    private val database: CopilotDatabase
) : ContextEventTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T =
        database.withTransaction { block() }
}
