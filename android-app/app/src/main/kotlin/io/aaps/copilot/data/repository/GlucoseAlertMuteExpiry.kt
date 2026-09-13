package io.aaps.copilot.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Long>.withExactMuteExpiry(
    nowTs: () -> Long = System::currentTimeMillis
): Flow<Long> = distinctUntilChanged()
    .flatMapLatest { mutedUntilTs ->
        flow {
            val remainingMs = mutedUntilTs - nowTs()
            if (remainingMs <= 0L) {
                emit(0L)
            } else {
                emit(mutedUntilTs)
                delay(remainingMs)
                emit(0L)
            }
        }
    }
    .distinctUntilChanged()
