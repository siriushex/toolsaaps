package io.aaps.copilot.domain.predict

object AcceptedSensitivityTupleFreshness {
    const val MAX_AGE_MS: Long = 15L * 60_000L

    fun isFresh(generationTimestamp: Long, authoritativeNowTs: Long): Boolean {
        val ageMs = runCatching {
            Math.subtractExact(authoritativeNowTs, generationTimestamp)
        }.getOrNull() ?: return false
        return ageMs in 0L..MAX_AGE_MS
    }
}
