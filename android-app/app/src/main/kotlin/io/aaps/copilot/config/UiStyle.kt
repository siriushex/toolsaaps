package io.aaps.copilot.config

enum class UiStyle {
    CLARITY,
    CLASSIC,
    DYNAMIC_GRADIENT,
    MIDNIGHT_GLASS;

    companion object {
        fun fromRaw(raw: String?): UiStyle {
            return runCatching {
                valueOf(raw?.trim().orEmpty().uppercase())
            }.getOrDefault(CLASSIC)
        }
    }
}
