package io.aaps.copilot.telegram

import io.aaps.copilot.security.SecretStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface TelegramPersistence {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun clear()
}

class EncryptedTelegramPersistence(private val storage: SecretStorage) : TelegramPersistence {
    override suspend fun read(): String? {
        if (storage.isDeletionPending()) {
            clear()
            return null
        }
        return storage.read()
    }

    override suspend fun write(value: String) = withContext(NonCancellable + Dispatchers.IO) {
        try {
            storage.stagePending(value, null)
            check(storage.readPending() == value)
            storage.commitPending()
            check(storage.read() == value)
            storage.discardRollback()
            storage.clearPending()
        } catch (_: Exception) {
            try { storage.rollbackPromotion() } catch (_: Exception) { }
            throw TelegramFailure(TelegramIssue.STORAGE)
        }
    }

    override suspend fun clear() = withContext(NonCancellable + Dispatchers.IO) {
        storage.markDeletionPending(null)
        storage.clear()
    }
}
