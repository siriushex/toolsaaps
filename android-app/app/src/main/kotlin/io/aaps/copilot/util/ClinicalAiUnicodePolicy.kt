package io.aaps.copilot.util

internal object ClinicalAiUnicodePolicy {
    fun isDefaultIgnorable(codePoint: Int): Boolean =
        codePoint == 0x00ad ||
            codePoint == 0x034f ||
            codePoint == 0x061c ||
            codePoint in 0x115f..0x1160 ||
            codePoint in 0x17b4..0x17b5 ||
            codePoint in 0x180b..0x180f ||
            codePoint in 0x200b..0x200f ||
            codePoint in 0x202a..0x202e ||
            codePoint in 0x2060..0x206f ||
            codePoint == 0x3164 ||
            codePoint in 0xfe00..0xfe0f ||
            codePoint == 0xfeff ||
            codePoint == 0xffa0 ||
            codePoint in 0xfff0..0xfff8 ||
            codePoint in 0x1bca0..0x1bca3 ||
            codePoint in 0x1d173..0x1d17a ||
            codePoint in 0xe0000..0xe0fff
}
