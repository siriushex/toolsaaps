package io.aaps.copilot.config

enum class UiStyle {
    CLASSIC,
    DYNAMIC_GRADIENT;

    companion object {
        fun fromRaw(raw: String?): UiStyle {
            return runCatching {
                valueOf(raw?.trim().orEmpty().uppercase())
            }.getOrDefault(CLASSIC)
        }
    }
}
