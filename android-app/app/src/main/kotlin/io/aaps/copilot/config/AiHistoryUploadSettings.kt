package io.aaps.copilot.config

/** Background history transport only; enabling this does not authorize inference. */
data class AiHistoryUploadSettings(
    val enabled: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES
) {
    init { require(intervalMinutes in INTERVALS) }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 120
        val INTERVALS = listOf(60, 120, 180, 240)

        fun fromStored(enabled: Boolean?, intervalMinutes: Int?) = AiHistoryUploadSettings(
            enabled = enabled ?: true,
            intervalMinutes = intervalMinutes?.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL_MINUTES
        )
    }
}
