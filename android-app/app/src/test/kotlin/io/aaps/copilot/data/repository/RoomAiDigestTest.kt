package io.aaps.copilot.data.repository

import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAiDigestTest {
    @Test fun preservesKnownSha256Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", roomAiDigest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", roomAiDigest("abc"))
    }

    @Test fun preservesPersistedIdentitiesIncludingLeadingZeroAndSignedBytes() {
        val bytesSeen = mutableSetOf<Int>()
        val inputs = listOf("\u0415\u0434\u0430", "\ud83c\udf54", "\u0000", "x".repeat(8192)) +
            (0..255).map { "history-$it" }
        inputs.forEach { value ->
            val digest = MessageDigest.getInstance("SHA-256").digest(HistoryJson.encodeUtf8(value))
            bytesSeen += digest.map { it.toInt() and 0xff }
            val legacy = digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            assertEquals(legacy, roomAiDigest(value))
        }
        assertEquals(256, bytesSeen.size)
        assertTrue(bytesSeen.contains(0))
    }
}
