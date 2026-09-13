package io.aaps.copilot.util

import kotlinx.coroutines.CancellationException

/** Converts malformed ordinary input to null without intercepting cancellation or any Error. */
internal inline fun <T> ordinaryExceptionOrNull(block: () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}
