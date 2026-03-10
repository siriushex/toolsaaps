package io.aaps.copilot.config

enum class SensorLagCorrectionMode {
    OFF,
    SHADOW,
    ACTIVE;

    companion object {
        fun fromRaw(raw: String?): SensorLagCorrectionMode {
            return runCatching {
                valueOf(raw?.trim().orEmpty().uppercase())
            }.getOrDefault(OFF)
        }
    }
}
